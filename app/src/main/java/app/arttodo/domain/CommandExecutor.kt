package app.arttodo.domain

import app.arttodo.core.CommandEnvelope
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainOutcome
import app.arttodo.core.DomainState
import app.arttodo.core.Notice
import app.arttodo.data.AppDatabase
import app.arttodo.data.BackupException
import app.arttodo.data.DataViolationException
import app.arttodo.data.DomainStateLoader
import app.arttodo.data.EffectsApplier
import app.arttodo.domain.bridge.DomainBridge
import app.arttodo.domain.bridge.DomainFailure
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.domain.bridge.envelope
import app.arttodo.domain.bridge.toFailure
import app.arttodo.system.SessionOwner
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** What the UI gets back from one dispatched command. */
sealed interface Executed {
    data class Applied(val notices: List<Notice>) : Executed
    data class Rejected(val failure: DomainFailure) : Executed
}

/**
 * The single writer (架构契约 §5.2 rule 1).
 *
 * Every state change runs on one dedicated thread behind a mutex: the state is loaded, the core is
 * asked for an outcome, and the whole effect list is committed in one Room transaction. Because the
 * load and the commit sit inside the same critical section, two concurrent commands can never
 * interleave into two divergent truths.
 *
 * That critical section is also what makes [exclusive] the only safe place for a whole-database
 * replacement: a restore that ran beside a command could take the file away between the command's
 * load and its commit, and the command would then write the old timeline into the new file.
 */
class CommandExecutor(
    private val beforeMutation: suspend () -> Unit = {},
    private val database: AppDatabase,
    private val bridge: DomainBridge,
    private val zoneIdProvider: () -> String,
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val bootTag: () -> String = { android.os.Build.FINGERPRINT },
    private val sessionOwner: SessionOwner = SessionOwner(),
    /** Called with a zone the core has just recorded, so the bootstrap pointer stays coherent. */
    private val onZoneObserved: (String) -> Unit = {},
) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "arttodo-command-writer") }
            .asCoroutineDispatcher()

    private val mutex = Mutex()
    private val sequence = AtomicLong(0)
    private val loader = DomainStateLoader(database)

    /**
     * Zone epoch of the most recently loaded state.
     *
     * Held in memory rather than re-read, because the effects applier runs inside a transaction and
     * must not start a second load there. It is refreshed on every dispatch and read.
     */
    @Volatile
    private var knownEpochSeq: Int = 1

    /**
     * The zone every date this writer produces is resolved in.
     *
     * It is the zone of the newest `zone_epoch` row — the same value the core reasons with — rather
     * than a separately maintained Kotlin cache. After a manual zone change the two would otherwise
     * disagree, and a fact would be stamped with the new epoch while its date came from the old zone
     * (架构契约 §2.4). The provider is only a bootstrap value for a database that has no epoch yet.
     */
    @Volatile
    private var effectiveZoneId: String = ""

    private val applier = EffectsApplier(database) { wallMs ->
        // Dates come from the core, never from Kotlin (架构契约 §2.2).
        val zone = effectiveZoneId.ifBlank { zoneIdProvider() }
        when (val date = bridge.appDateOf(wallMs, zone, knownEpochSeq)) {
            is DomainResult.Success -> date.value.iso
            is DomainResult.Failure -> ""
        }
    }

    /**
     * Runs one user command end to end.
     *
     * A returned failure is normal, expected traffic: a validation error or a stale revision is not
     * an exception.
     */
    suspend fun dispatch(command: DomainCommand, commandId: String? = null): Executed =
        withContext(dispatcher) {
            mutex.withLock {
                val now = wallClock()
                val state = prepared()
                val zoneId = effectiveZoneId
                val envelope: CommandEnvelope = bridge.envelope(
                    commandId = commandId ?: "cmd:${now}:${sequence.incrementAndGet()}",
                    wallMs = now,
                    zoneId = zoneId,
                    expectedRevision = state.revision.toLong(),
                ).let { envelope ->
                    envelope.copy(
                        issuedAt = bridge.clock(now, zoneId, elapsedClock(), bootTag()),
                    )
                }

                when (val outcome = bridge.reduce(state, command, envelope)) {
                    is DomainResult.Failure -> Executed.Rejected(outcome.failure)
                    is DomainResult.Success -> {
                        val result: DomainOutcome = outcome.value
                        val error = result.error
                        if (error != null) {
                            Executed.Rejected(error.toFailure())
                        } else {
                            // Effects and the command log land in one transaction; a failure here
                            // leaves the database exactly as it was.
                            val failure = applyGuarded {
                                applier.apply(result.effects)
                                syncSessionOwner(result, state.activeSessionId)
                            }
                            if (failure != null) {
                                Executed.Rejected(failure)
                            } else {
                                if (result.nextState.zoneId != zoneId) {
                                    onZoneObserved(result.nextState.zoneId)
                                }
                                Executed.Applied(result.notices)
                            }
                        }
                    }
                }
            }
        }

    /**
     * Runs [block] on the writer thread under the same lock every command takes.
     *
     * Used for work that must not interleave with a command — a whole-database restore, a settings
     * write — so that nothing can read a timeline and commit into another one. [block] must not
     * dispatch a command: the lock is not reentrant.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock { block() }
    }

    /** Reads the state the UI should render, through the same single writer. */
    suspend fun readState(): DomainState = withContext(dispatcher) {
        mutex.withLock { prepared() }
    }

    /**
     * Loads the authoritative state and makes the zone and epoch it carries current for this writer.
     *
     * The state's zone comes from the newest `zone_epoch` row when there is one, so every command is
     * dated in the zone the core itself uses; the provider only bootstraps a database that has not
     * recorded an epoch yet.
     */
    private suspend fun prepared(): DomainState {
        val state = loader.load(zoneIdProvider())
        knownEpochSeq = state.zoneEpochSeq.toInt()
        effectiveZoneId = state.zoneId.ifBlank { zoneIdProvider() }
        return state
    }

    private fun syncSessionOwner(result: DomainOutcome, previousActiveId: String?) {
        val activeId = result.nextState.activeSessionId
        if (activeId != null && result.nextState.sessions.any {
                it.sessionId == activeId && it.state == app.arttodo.core.SessionState.RECOVERY_PENDING
            }
        ) {
            sessionOwner.release(activeId)
        } else if (activeId != null) {
            sessionOwner.claim(activeId)
        } else {
            previousActiveId?.let(sessionOwner::release)
        }
    }

    /**
     * Runs the write half of one mutation behind the recovery-point boundary and normalises failures.
     *
     * **The order of the catch clauses is the contract**, because Kotlin takes the first applicable
     * one and both `DataViolationException` and `BackupException` extend `Exception`:
     *
     * 1. a value the write boundary refuses is the caller's error → `Validation`,
     * 2. a recovery point that could not be captured is a storage condition of its own → its own
     *    failure, because the user has to be told that nothing was written rather than "内部错误",
     * 3. anything else is genuinely internal.
     *
     * Cancellation is not an error: it is rethrown so a cancelled caller is never resumed with a
     * fabricated `Internal` failure.
     */
    private suspend fun applyGuarded(block: suspend () -> Unit): DomainFailure? = try {
        beforeMutation()
        block()
        null
    } catch (violation: DataViolationException) {
        // The write boundary rejected a value the core would also reject. Throwing rolled the
        // transaction back, so nothing was persisted and the failure travels the normal
        // `DomainResult` channel instead of crashing the app.
        DomainFailure.Validation(violation.code)
    } catch (error: BackupException) {
        DomainFailure.RecoveryPointUnavailable(error.code)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DomainFailure.Internal(error.message ?: error.javaClass.simpleName)
    }
}
