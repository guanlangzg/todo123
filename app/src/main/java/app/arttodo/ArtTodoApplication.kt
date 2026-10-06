package app.arttodo

import android.app.Application
import app.arttodo.data.AppDatabase
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.bridge.DomainBridge
import app.arttodo.domain.bridge.UniffiDomainBridge
import app.arttodo.system.ZoneProvider
import app.arttodo.system.SessionOwner
import app.arttodo.system.FocusReminder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide wiring.
 *
 * All components live in the default process: the single-writer guarantee is an in-process
 * invariant, so declaring a second process would break it (架构契约 §5.2 rule 1).
 */
class ArtTodoApplication : Application() {

    /** Held here, not in an Activity, so an Activity recreation never loses the active session. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        FocusReminder.ensureChannel(this)
        container.warmUp()
    }
}

/** Explicit dependency container; small enough that a DI framework would be more machinery. */
class AppContainer(private val application: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val applicationContext get() = application.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(application) }

    val domainBridge: DomainBridge by lazy { UniffiDomainBridge() }

    val zoneProvider: ZoneProvider by lazy { ZoneProvider(database) }

    /**
     * The one clock read in the app.
     *
     * It is exposed rather than inlined so the screens and the command writer use the *same* instant
     * source: the domain core never reads a clock itself (架构契约 §1), so every instant has to enter
     * through here.
     */
    val wallClock: () -> Long = { System.currentTimeMillis() }

    val sessionOwner = SessionOwner()
    private val sessionServiceLock = Mutex()

    suspend fun startSessionService(sessionId: String) = sessionServiceLock.withLock {
        val service = android.content.Intent(applicationContext, app.arttodo.system.SessionRuntimeService::class.java)
            .putExtra(app.arttodo.system.SessionRuntimeService.EXTRA_SESSION_ID, sessionId)
        androidx.core.content.ContextCompat.startForegroundService(applicationContext, service)
    }

    val backupManager by lazy {
        app.arttodo.data.BackupManager(
            application,
            database,
            wallClock,
            // The daily point's boundary is the application's own day, and only the core may name it
            // (架构契约 §2 / §8.4) — never a UTC calendar date computed in Kotlin.
            dayLabelOf = { wallMs -> appDayLabel.of(wallMs) },
        )
    }

    val appDayLabel by lazy { app.arttodo.system.AppDayLabel(database, domainBridge) { zoneProvider.appZoneId() } }

    val recoveryPoints by lazy {
        app.arttodo.system.RecoveryPointCoordinator(
            database,
            backupManager,
            // A restore replaces the whole file, so it takes the same lock a command does; otherwise a
            // command that read the old timeline could commit into the restored one (架构契约 §5.2 rule 1).
            exclusive = { block -> commandExecutor.exclusive(block) },
        )
    }

    val commandExecutor: CommandExecutor by lazy {
        CommandExecutor(
            beforeMutation = { recoveryPoints.beforeMutation() },
            database = database,
            bridge = domainBridge,
            zoneIdProvider = { zoneProvider.appZoneId() },
            wallClock = wallClock,
            sessionOwner = sessionOwner,
            onZoneObserved = zoneProvider::observeZone,
        )
    }

    /**
     * Decides whether a session left in the database awaits the user's adjudication (AC-09).
     *
     * 架构契约 §7.3 gives this exactly three clauses: the row says `Running`/`Paused`, the active slot
     * still points at it, and **this process** does not hold it. Ownership is the whole test — the
     * heartbeat's age and the clock's plausibility decide only how long the gap is *after* the session
     * is pending, because a session the previous process wrote looks perfectly plausible from the
     * inside of a restart in the same boot (and its whole dead span would otherwise be booked as
     * investment when the timer is next stopped).
     *
     * A session this process started keeps running: normal backgrounding, locking the screen and an
     * Activity recreation all leave the owner in place, so none of them prompts.
     */
    suspend fun assessRecovery(state: app.arttodo.core.DomainState, sessionId: String) {
        val session = state.sessions.firstOrNull { it.sessionId == sessionId } ?: return
        if (session.state !in setOf(app.arttodo.core.SessionState.RUNNING, app.arttodo.core.SessionState.PAUSED)) return
        if (sessionOwner.owns(sessionId)) return
        commandExecutor.dispatch(app.arttodo.core.DomainCommand.MarkRecoveryPending(sessionId))
    }

    /**
     * Loads the native library and seeds epoch 1 on first run.
     *
     * A failure is recorded rather than thrown: an unavailable domain core has to surface as an
     * in-app message, not a crash on launch (架构契约 §3.4).
     */
    fun warmUp() {
        scope.launch {
            runCatching {
                // Capture the installation zone before anything reads it, so a later device zone
                // change cannot move the app's dates.
                zoneProvider.ensureInitialised()
                val state = commandExecutor.readState()
                val activeId = state.activeSessionId
                if (activeId != null && !sessionOwner.owns(activeId)) {
                    assessRecovery(state, activeId)
                }
            }.onFailure { error ->
                startupFailure = error
            }
        }
    }

    @Volatile
    var startupFailure: Throwable? = null
}
