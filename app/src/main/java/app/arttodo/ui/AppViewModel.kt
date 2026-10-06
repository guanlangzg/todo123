package app.arttodo.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.arttodo.AppContainer
import app.arttodo.core.DayTotal
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainState
import app.arttodo.core.LedgerRow
import app.arttodo.core.LedgerRowTitle
import app.arttodo.core.Notice
import app.arttodo.core.PeriodTotal
import app.arttodo.core.RecoveryChoice
import app.arttodo.core.RecoveryAmounts
import app.arttodo.core.SessionMode
import app.arttodo.core.SessionRecord
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.core.TaskShare
import app.arttodo.core.TimeBucket
import app.arttodo.core.WorkSegment
import app.arttodo.data.DomainCommands
import app.arttodo.data.DomainStateLoader
import app.arttodo.data.TaskEntity
import app.arttodo.system.FocusReminder
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.Executed
import app.arttodo.domain.bridge.DomainBridge
import app.arttodo.domain.bridge.DomainFailure
import app.arttodo.domain.bridge.DomainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One screen-facing snapshot of the truth.
 *
 * Every number in it was produced by the domain core (dates, totals, session state, completion) or is
 * a stored row; the UI adds presentation only. That is what keeps the calendar, the trend, the heat
 * map and the share list from ever disagreeing: they are all projections over the same ledger
 * (架构契约 §9, AC-12 / N10).
 */
data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val coreAvailable: Boolean = true,
    /** `CurrentAppDate` — the domain's answer to "what is today". Never computed in Kotlin. */
    val today: String = "",
    val zoneId: String = "",
    val zoneEpochSeq: Int = 1,
    val revision: Long = 0,
    val tasks: List<TaskRecord> = emptyList(),
    /** Archived tasks, replayed from `archived_at_ms`; the settings list reads this. */
    val archivedTasks: List<TaskEntity> = emptyList(),
    val occurrenceCompletion: Map<String, Boolean> = emptyMap(),
    /** Keyed by `(taskId, appDate)`; the pair is the instance's unique key (架构契约 §2.4). */
    val occurrenceIds: Map<Pair<String, String>, String> = emptyMap(),
    val temporaryCompletion: Map<String, Boolean> = emptyMap(),
    val activeSession: SessionRecord? = null,
    val activeSegments: List<WorkSegment> = emptyList(),
    /**
     * Application date the active session started on, straight from the core.
     *
     * A value other than [today] means the session crossed midnight, which is when completing it has
     * to ask which day the completion belongs to (AC-08). Comparing two core-produced labels involves
     * no date arithmetic here; nothing in Kotlin derives a date.
     */
    val activeSessionStartDate: String? = null,
    val completedCountdownSession: SessionRecord? = null,
    val completedCountdownSeconds: Long = 0,
    val completedCountdownTitle: String = "专注任务",
    val recoveryAmounts: RecoveryAmounts? = null,
    val recoverySessionId: String? = null,
    val ledger: List<LedgerRow> = emptyList(),
    val dayTotals: List<DayTotal> = emptyList(),
    val weekTotals: List<PeriodTotal> = emptyList(),
    val monthTotals: List<PeriodTotal> = emptyList(),
    val taskShares: List<TaskShare> = emptyList(),
    val notices: List<Notice> = emptyList(),
    val lastBackupAtMs: Long? = null,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
) {
    fun taskById(taskId: String): TaskRecord? = tasks.firstOrNull { it.taskId == taskId }

    /**
     * Display name for a task that a ledger row or a share row refers to, as of now.
     *
     * `tasks` deliberately excludes archived tasks, while the ledger keeps their history for good, so a
     * records or insight row has to look in both lists before falling back to the raw id.
     *
     * This is the **current** name and must not be used to label a past row: what a row was recorded
     * under comes from [ledgerRowTitles], which resolves the core's segment snapshot per ledger row
     * (AC-14).
     */
    fun titleOf(taskId: String): String =
        tasks.firstOrNull { it.taskId == taskId }?.title
            ?: archivedTasks.firstOrNull { it.taskId == taskId }?.title
            ?: taskId

    fun isCompleted(task: TaskRecord): Boolean = when (task.kind) {
        TaskKind.DAILY -> occurrenceCompletion[occurrenceIds[task.taskId to today]] ?: false
        TaskKind.TEMPORARY -> temporaryCompletion[task.taskId] ?: false
    }

    /** Today's total over every task, straight from the core's own day projection. */
    val todayTotalSeconds: Long
        get() = dayTotals.firstOrNull { it.appDate == today }?.seconds ?: 0

}

/**
 * The application state holder.
 *
 * It owns no domain logic: it loads state, asks the bridge for projections, and dispatches commands.
 * A rejected command leaves the screen exactly as it was and reports a human sentence instead.
 */
class AppViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val database = container.database
    private val bridge: DomainBridge = container.domainBridge
    private val executor: CommandExecutor = container.commandExecutor
    private val loader = DomainStateLoader(database)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Commands dispatched while a refresh is in flight are serialised by the executor itself. */
    init {
        refresh()
    }

    /**
     * Reloads everything the UI shows.
     *
     * Ordering matters twice here: the domain's `today` is read first, and the ledger projections are
     * asked for the range that starts at the current month's Monday so the week trend has its full
     * bucket. Nothing is derived from `System.currentTimeMillis()`.
     */
    @Suppress("LongMethod")
    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val domain = executor.readState()
                // The zone comes from the newest `zone_epoch` row the state carries, never from a
                // separate Kotlin cache: after a manual zone change a cached zone would date "today"
                // in the zone the user just left while the core dates in the new one (架构契约 §2.4).
                val zoneId = domain.zoneId.ifBlank { container.zoneProvider.appZoneId() }
                val today = when (val date = bridge.appDateOf(container.wallClock(), zoneId, domain.zoneEpochSeq.toInt())) {
                    is DomainResult.Success -> date.value.iso
                    is DomainResult.Failure -> ""
                }
                val facts = loader.completionFacts()
                val occurrenceIds = database.occurrenceDao().all()
                    .associate { (it.taskId to it.appDate) to it.occurrenceId }

                // The selected-year heat map, calendar/trend projections, and all-history task shares
                // derive from this one ledger snapshot (AC-12 / N10). Screens slice by date only for display.
                val ledger = loader.allLedgerRows()
                Snapshot(
                    domain = domain,
                    today = today,
                    zoneId = zoneId,
                    occurrenceIds = occurrenceIds,
                    facts = facts,
                    ledger = ledger,
                    archived = loader.archivedTasks(),
                    lastBackupAtMs = loader.latestBackupRecord()?.createdWallMs,
                    soundEnabled = readFlag(KEY_SOUND),
                    vibrationEnabled = readFlag(KEY_VIBRATION),
                )
            }.onSuccess { snapshot -> publish(snapshot) }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        coreAvailable = false,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }

    private data class Snapshot(
        val domain: DomainState,
        val today: String,
        val zoneId: String,
        val occurrenceIds: Map<Pair<String, String>, String>,
        val facts: DomainStateLoader.CompletionFacts,
        val ledger: List<LedgerRow>,
        val archived: List<TaskEntity>,
        val lastBackupAtMs: Long?,
        val soundEnabled: Boolean,
        val vibrationEnabled: Boolean,
    )

    @Suppress("LongMethod")
    private fun publish(snapshot: Snapshot) {
        val domain = snapshot.domain
        val dayTotals = successOrNull(bridge.projectDayTotals(snapshot.ledger))
        val weekTotals = successOrNull(bridge.projectPeriodTotals(snapshot.ledger, TimeBucket.WEEK))
        val monthTotals = successOrNull(bridge.projectPeriodTotals(snapshot.ledger, TimeBucket.MONTH))
        val shares = successOrNull(bridge.projectTaskShares(snapshot.ledger))

        val active = domain.activeSessionId?.let { id -> domain.sessions.firstOrNull { it.sessionId == id } }
        // The date the session started on is the core's call, like every other date label.
        val activeStartDate = active?.let { session ->
            val zone = domain.zoneId.ifBlank { snapshot.zoneId }
            when (val date = bridge.appDateOf(session.createdWallMs, zone, domain.zoneEpochSeq.toInt())) {
                is DomainResult.Success -> date.value.iso
                is DomainResult.Failure -> null
            }
        }
        val completedCountdown = domain.sessions.asSequence()
            .filter { it.state == SessionState.FINISHED && it.mode == SessionMode.COUNTDOWN && it.source != "recovery" }
            .filter { FocusReminder.isResultPending(container.applicationContext, it.sessionId) }
            .maxByOrNull { it.finishedWallMs ?: 0L }
        val completedCountdownSeconds = completedCountdown?.let { session ->
            domain.segments.filter { it.sessionId == session.sessionId && it.endWallMs != null }
                .sumOf { segment -> ((segment.endWallMs ?: segment.startWallMs) - segment.startWallMs).coerceAtLeast(0) / 1000 }
                .coerceAtMost(session.targetSeconds ?: Long.MAX_VALUE)
        } ?: 0L
        val completedCountdownTitle = completedCountdown?.let { session ->
            domain.segments.lastOrNull { it.sessionId == session.sessionId }?.titleSnapshot
                ?: domain.tasks.firstOrNull { it.taskId == session.taskId }?.title
        } ?: "专注任务"
        val recoveryBeat = active?.takeIf { it.state == SessionState.RECOVERY_PENDING }
            ?.let { session -> domain.heartbeats.filter { it.sessionId == session.sessionId }.maxByOrNull { it.wallMs } }
        val recoverySegment = active?.takeIf { it.state == SessionState.RECOVERY_PENDING }
            ?.let { session -> domain.segments.filter { it.sessionId == session.sessionId }.maxByOrNull { it.segSeq } }
        val recovery = if (active != null && recoveryBeat != null && recoverySegment != null) {
            // The window a recovery can still book ends where the session stopped running. A paused
            // session has no open segment, so it ends at the pause heartbeat and the gap is zero:
            // paused time is never investment (AC-05), and the prompt must not offer what the
            // resolution would refuse to book.
            val runningUntil = recoverySegment.endWallMs ?: container.wallClock()
            val pausedRecovery = recoverySegment.endWallMs != null
            val closedSeconds = domain.segments
                .filter { it.sessionId == active.sessionId && it.endWallMs != null }
                .sumOf { segment ->
                    ((segment.endWallMs ?: segment.startWallMs) - segment.startWallMs).coerceAtLeast(0) / 1000
                }
            val targetRemaining = active.targetSeconds?.let { (it - closedSeconds).coerceAtLeast(0) }
            if (pausedRecovery) {
                RecoveryAmounts(
                    trustedSeconds = closedSeconds,
                    gapSeconds = 0,
                    bookedSeconds = closedSeconds,
                )
            } else successOrNull(
                bridge.recoveryAmounts(
                    recoverySegment.startWallMs,
                    recoveryBeat.wallMs,
                    runningUntil,
                    RecoveryChoice.Accept,
                    targetRemaining,
                ),
            )?.let { amounts ->
                amounts.copy(
                    trustedSeconds = closedSeconds + amounts.trustedSeconds,
                    gapSeconds = if (active.targetSeconds == null) amounts.gapSeconds
                    else (amounts.bookedSeconds - amounts.trustedSeconds).coerceAtLeast(0),
                )
            }
        } else null
        _state.value = UiState(
            loading = false,
            error = null,
            coreAvailable = true,
            today = snapshot.today,
            zoneId = domain.zoneId.ifBlank { snapshot.zoneId },
            zoneEpochSeq = domain.zoneEpochSeq.toInt(),
            revision = domain.revision.toLong(),
            tasks = domain.tasks.filter { it.archivedAtMs == null },
            archivedTasks = snapshot.archived,
            occurrenceCompletion = snapshot.facts.occurrenceCompleted,
            occurrenceIds = snapshot.occurrenceIds,
            temporaryCompletion = snapshot.facts.temporaryCompleted,
            activeSession = active,
            completedCountdownSession = completedCountdown,
            completedCountdownSeconds = completedCountdownSeconds,
            completedCountdownTitle = completedCountdownTitle,
            activeSegments = active?.let { session ->
                domain.segments.filter { it.sessionId == session.sessionId }
            }.orEmpty(),
            activeSessionStartDate = activeStartDate,
            recoveryAmounts = recovery,
            recoverySessionId = if (recovery != null) active?.sessionId else null,
            ledger = snapshot.ledger,
            dayTotals = dayTotals.orEmpty(),
            weekTotals = weekTotals.orEmpty(),
            monthTotals = monthTotals.orEmpty(),
            taskShares = shares.orEmpty(),
            lastBackupAtMs = snapshot.lastBackupAtMs,
            soundEnabled = snapshot.soundEnabled,
            vibrationEnabled = snapshot.vibrationEnabled,
        )
    }

    /** Reads a stored boolean setting; absent means the documented default (both reminders on). */
    private suspend fun readFlag(key: String): Boolean =
        database.supportDao().setting(key)?.let { it != "0" } ?: true

    private fun <T> successOrNull(result: DomainResult<T>): T? =
        (result as? DomainResult.Success<T>)?.value

    // ---- Commands ------------------------------------------------------------------------------

    /** One user command end to end; the screen never mutates state itself. */
    fun dispatch(command: DomainCommand, onResult: (DomainFailure?) -> Unit = {}) {
        viewModelScope.launch {
            when (val executed = executor.dispatch(command)) {
                is Executed.Applied -> {
                    if (executed.notices.isNotEmpty()) {
                        _state.value = _state.value.copy(notices = executed.notices)
                    }
                    refresh()
                    onResult(null)
                }
                is Executed.Rejected -> {
                    _state.value = _state.value.copy(error = executed.failure.describe())
                    onResult(executed.failure)
                }
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun consumeNotices() {
        _state.value = _state.value.copy(notices = emptyList())
    }

    // ---- Today ---------------------------------------------------------------------------------

    fun createTask(kind: TaskKind, title: String, note: String) {
        dispatch(DomainCommands.createTask(kind, title, note))
    }

    fun renameTask(taskId: String, title: String) {
        dispatch(DomainCommand.RenameTask(taskId = taskId, title = title))
    }

    fun editNote(taskId: String, note: String) {
        dispatch(DomainCommand.EditNote(taskId = taskId, note = note))
    }

    fun archiveTask(taskId: String) {
        dispatch(DomainCommand.ArchiveTask(taskId = taskId))
    }

    fun unarchiveTask(taskId: String) {
        dispatch(DomainCommand.UnarchiveTask(taskId = taskId))
    }

    /**
     * Toggles a daily instance's completion.
     *
     * The occurrence is ensured first: `EnsureOccurrence` is idempotent per `(taskId, appDate)`, which
     * is what makes "mark yesterday done" work for a date that never had an instance (AC-01 / 规格 3).
     */
    fun setDailyCompletion(taskId: String, appDate: String, completed: Boolean) {
        viewModelScope.launch {
            dispatch(DomainCommands.ensureOccurrence(taskId, appDate)) { failure ->
                if (failure == null) {
                    dispatch(DomainCommands.setCompletion(taskId, appDate, completed))
                }
            }
        }
    }

    fun setTemporaryCompletion(taskId: String, completed: Boolean) {
        dispatch(if (completed) DomainCommands.completeTemporary(taskId) else DomainCommands.reopenTemporary(taskId))
    }

    /** Moves a task one slot inside its own group. Cross-group moves are refused by the core. */
    fun moveTask(taskId: String, delta: Int) {
        val current = _state.value
        val task = current.taskById(taskId) ?: return
        val group = current.tasks.filter { it.kind == task.kind }.sortedBy { it.sortKey }
        val index = group.indexOfFirst { it.taskId == taskId }
        val target = index + delta
        if (index < 0 || target !in group.indices) return
        val reordered = group.toMutableList().apply { add(target, removeAt(index)) }
        dispatch(DomainCommands.reorderTasks(task.kind, reordered.map { it.taskId }))
    }

    fun moveTaskToEdge(taskId: String, toEnd: Boolean) {
        val current = _state.value
        val task = current.taskById(taskId) ?: return
        val group = current.tasks.filter { it.kind == task.kind }.sortedBy { it.sortKey }
        val reordered = group.filterNot { it.taskId == taskId }
            .let { if (toEnd) it + task else listOf(task) + it }
        dispatch(DomainCommands.reorderTasks(task.kind, reordered.map { it.taskId }))
    }

    // ---- Focus ---------------------------------------------------------------------------------

    /**
     * Starts a session for [taskId].
     *
     * The recent-countdown memory is written here, not in the mode picker: 规格 5.1 and AC-04 require
     * that changing the minutes and then cancelling does not overwrite the stored default.
     */
    fun startSession(
        taskId: String,
        mode: SessionMode,
        targetSeconds: Long?,
        replacesSessionId: String? = null,
        rememberMinutes: Int? = null,
        onResult: (DomainFailure?) -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val result = executor.dispatch(DomainCommands.startSession(taskId, mode, targetSeconds, replacesSessionId))) {
                is Executed.Rejected -> {
                    _state.value = _state.value.copy(error = result.failure.describe())
                    onResult(result.failure)
                }
                is Executed.Applied -> {
                    refresh()
                    if (replacesSessionId != null) {
                        app.arttodo.system.SessionDeadline.cancel(container.applicationContext, replacesSessionId)
                    }
                    if (rememberMinutes != null) {
                        dispatch(DomainCommand.SetTaskRecentCountdown(taskId = taskId, minutes = rememberMinutes.toUInt()))
                    }
                    val state = executor.readState()
                    val active = state.activeSessionId
                    if (active != null) {
                        if (targetSeconds != null) {
                            val elapsed = state.segments
                                .filter { it.sessionId == active }
                                .sumOf { segment ->
                                    val end = segment.endWallMs ?: container.wallClock()
                                    if (end <= segment.startWallMs) 0L else (end - segment.startWallMs) / 1000L
                                }
                            app.arttodo.system.SessionDeadline.schedule(
                                container.applicationContext,
                                active,
                                targetSeconds,
                                elapsed,
                                container.wallClock(),
                            )
                        }
                        runCatching { container.startSessionService(active) }
                            .onFailure { _state.value = _state.value.copy(error = "后台计时服务启动失败；请保持应用打开。") }
                    }
                    onResult(null)
                }
            }
        }
    }

    fun pauseSession(sessionId: String) {
        dispatch(DomainCommands.pauseSession(sessionId)) { failure ->
            if (failure == null) {
                app.arttodo.system.SessionDeadline.cancel(container.applicationContext, sessionId)
            }
        }
    }

    fun resumeSession(sessionId: String) {
        viewModelScope.launch {
            when (val result = executor.dispatch(DomainCommands.resumeSession(sessionId))) {
                is Executed.Applied -> {
                    refresh()
                    val state = executor.readState()
                    val session = state.sessions.firstOrNull { it.sessionId == sessionId }
                    val target = session?.targetSeconds
                    if (target != null) {
                        val elapsed = state.segments
                            .filter { it.sessionId == sessionId }
                            .sumOf { segment ->
                                val end = segment.endWallMs ?: container.wallClock()
                                if (end <= segment.startWallMs) 0L else (end - segment.startWallMs) / 1000L
                            }
                        app.arttodo.system.SessionDeadline.schedule(
                            container.applicationContext,
                            sessionId,
                            target,
                            elapsed,
                            container.wallClock(),
                        )
                    }
                    runCatching { container.startSessionService(sessionId) }
                        .onFailure { _state.value = _state.value.copy(error = "后台计时服务启动失败；请保持应用打开。") }
                }
                is Executed.Rejected -> _state.value = _state.value.copy(error = result.failure.describe())
            }
        }
    }

    /** Ends a session, keeping the real invested seconds (never the target). */
    fun finishSession(sessionId: String, onResult: (DomainFailure?) -> Unit = {}) {
        dispatch(DomainCommands.finishNow(sessionId)) { failure ->
            if (failure == null) app.arttodo.system.SessionDeadline.cancel(container.applicationContext, sessionId)
            onResult(failure)
        }
    }

    /**
     * Ends a session at exactly [seconds] of investment — the session's **accumulated** seconds, the
     * number the clock shows after every pause and resume, not the open segment's own length.
     *
     * The core turns that number into an end instant (already-closed segments count as spent, only
     * the remainder goes into the open segment), so the booked contribution equals what the clock
     * showed instead of rounding up to whenever the command happened to run. Passing the accumulated
     * number is what makes "结束本次专注，已投入 …" mean what it says.
     */
    fun finishAt(sessionId: String, seconds: Long, onResult: (DomainFailure?) -> Unit = {}) {
        dispatch(DomainCommands.finishAfter(sessionId, seconds.coerceAtLeast(0))) { failure ->
            if (failure == null) app.arttodo.system.SessionDeadline.cancel(container.applicationContext, sessionId)
            onResult(failure)
        }
    }

    /**
     * Ends the running session and completes its task as one atomic step (AC-07 / AC-08).
     *
     * [targetAppDate] is the day the completion belongs to; `null` means today. The screen passes the
     * user's choice because a session that crossed midnight must not silently complete today.
     */
    fun completeWhileRunning(sessionId: String, targetAppDate: String? = null) {
        dispatch(DomainCommand.CompleteTaskWhileRunning(sessionId = sessionId, targetAppDate = targetAppDate)) { failure ->
            if (failure == null) app.arttodo.system.SessionDeadline.cancel(container.applicationContext, sessionId)
        }
    }

    fun resolveRecovery(sessionId: String, choice: RecoveryChoice, onResult: (DomainFailure?) -> Unit = {}) {
        dispatch(DomainCommand.ResolveRecovery(sessionId = sessionId, choice = choice)) { failure ->
            if (failure == null) app.arttodo.system.SessionDeadline.cancel(container.applicationContext, sessionId)
            onResult(failure)
        }
    }

    /**
     * Accepts a finished countdown's result: the decision it was waiting for has been made.
     *
     * Clearing the device marker is what stops the result from being pending, and the reload is the
     * other half: the projection that made the result visible was computed from that marker, so
     * without re-reading it the focus screen would keep rendering the result in front of the user
     * instead of the picker that 继续专注 returns to (AC-07).
     */
    fun consumeCountdownResult(sessionId: String) {
        FocusReminder.clearResultPending(container.applicationContext, sessionId)
        refresh()
    }

    /** The core decides the trusted/gap/booked split; no screen recomputes it (AC-09). */
    suspend fun recoveryAmounts(
        startWallMs: Long,
        heartbeatWallMs: Long,
        recoveryWallMs: Long,
        choice: RecoveryChoice,
        targetSeconds: Long?,
    ) = successOrNull(bridge.recoveryAmounts(startWallMs, heartbeatWallMs, recoveryWallMs, choice, targetSeconds))

    /** The invested seconds of the active session, including its open segment. */
    suspend fun effectiveSeconds(sessionId: String, segments: List<WorkSegment>, nowWallMs: Long): Long =
        successOrNull(bridge.effectiveSeconds(sessionId, segments, nowWallMs)) ?: 0

    suspend fun dayWindow(appDate: String): app.arttodo.core.DayWindow? =
        successOrNull(bridge.dayWindow(appDate, _state.value.zoneId, _state.value.zoneEpochSeq))

    // ---- Records -------------------------------------------------------------------------------

    /** Rows of one task-day in replay order, for the detail list. */
    suspend fun ledgerForTaskDay(taskId: String, appDate: String): List<LedgerRow> =
        loader.ledgerForTaskDay(taskId, appDate)

    suspend fun ledgerForDay(appDate: String): List<LedgerRow> =
        loader.ledgerBetween(appDate, appDate)

    suspend fun ledgerBetween(fromIso: String, toIso: String): List<LedgerRow> =
        loader.ledgerBetween(fromIso, toIso)

    suspend fun replayDailyTotal(taskId: String, appDate: String, rows: List<LedgerRow>): Long? =
        successOrNull(bridge.replayDailyTotal(taskId, appDate, rows))

    /**
     * The name each of [rows] was recorded under, keyed by `ledgerSeq` (AC-14).
     *
     * The core decides, never the screen: an automatic slice carries the segment snapshot from the time
     * it was actually invested, so a rename cannot rewrite the past, while a manual add or a set-total
     * row comes back as the task's current title flagged as *not* a snapshot
     * ([app.arttodo.core.LedgerRowTitle.isTitleSnapshot]). A missing entry means "no name could be
     * established" and the page keeps showing just the kind label rather than inventing one.
     */
    suspend fun ledgerRowTitles(rows: List<LedgerRow>): Map<Long, LedgerRowTitle> {
        if (rows.isEmpty()) return emptyMap()
        val sources = loader.titleSources()
        return successOrNull(bridge.ledgerRowTitles(rows, sources.segments, sources.tasks))
            ?.associateBy { it.ledgerSeq }
            .orEmpty()
    }

    suspend fun totalWithout(rows: List<LedgerRow>, ledgerSeq: Long): Long? =
        successOrNull(bridge.totalWithoutLedgerEntry(rows, ledgerSeq))

    fun addManualSeconds(taskId: String, appDate: String, seconds: Long) {
        dispatch(DomainCommands.addManualSeconds(taskId, appDate, seconds))
    }

    fun setDailyTotal(taskId: String, appDate: String, totalSeconds: Long) {
        dispatch(DomainCommands.setDailyTotal(taskId, appDate, totalSeconds))
    }

    fun editLedgerEntry(ledgerSeq: Long, newDeltaSeconds: Long?, newSetTotalSeconds: Long?, onResult: (DomainFailure?) -> Unit = {}) {
        dispatch(
            DomainCommand.EditLedgerEntry(
                ledgerSeq = ledgerSeq,
                newDeltaSeconds = newDeltaSeconds,
                newSetTotalSeconds = newSetTotalSeconds,
            ),
            onResult,
        )
    }

    fun deleteLedgerEntry(ledgerSeq: Long, onResult: (DomainFailure?) -> Unit = {}) {
        dispatch(DomainCommand.DeleteLedgerEntry(ledgerSeq = ledgerSeq), onResult)
    }

    /**
     * Undo of a delete, valid for this session only.
     *
     * `RestoreLedgerEntry` clears the deleted flag **in place**, so the row returns to its original
     * replay slot and the day's total returns to what it was. Re-appending an equal value would land
     * after a later set-total and silently keep the post-delete total (see the Rust guard test
     * `undoing_a_delete_restores_the_row_in_its_original_slot`).
     */
    fun restoreLedgerEntry(ledgerSeq: Long) {
        dispatch(DomainCommand.RestoreLedgerEntry(ledgerSeq = ledgerSeq))
    }

    // ---- Settings ------------------------------------------------------------------------------

    fun appendZoneEpoch(zoneId: String, impactSummary: String) {
        dispatch(DomainCommand.AppendZoneEpoch(zoneId = zoneId, impactSummary = impactSummary))
    }

    /** Device zone right now, offered so the user can align the app's fixed zone. */
    fun deviceZoneId(): String = container.zoneProvider.deviceZoneId()

    fun setSoundEnabled(enabled: Boolean) {
        writeReminderFlag(KEY_SOUND, enabled) { _state.value = _state.value.copy(soundEnabled = enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        writeReminderFlag(KEY_VIBRATION, enabled) { _state.value = _state.value.copy(vibrationEnabled = enabled) }
    }

    /**
     * Writes one reminder flag through the recovery-point boundary.
     *
     * These flags are part of a backup, so toggling one is a data change like any other: the day's
     * first change of any kind must leave a point behind it, and the write must not race the command
     * writer. A failed capture therefore leaves the setting untouched and says so, instead of
     * pretending the change was saved (架构契约 §8.4).
     */
    private fun writeReminderFlag(key: String, enabled: Boolean, onWritten: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.recoveryPoints.writeSetting(key, if (enabled) "1" else "0") }
                .onSuccess { onWritten() }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        error = "无法创建本机恢复点，设置没有改动（${(error as? app.arttodo.data.BackupException)?.code ?: "RecoveryPointUnavailable"}）",
                    )
                }
        }
    }

    companion object {
        const val KEY_SOUND = app.arttodo.data.AppSettings.KEY_SOUND
        const val KEY_VIBRATION = app.arttodo.data.AppSettings.KEY_VIBRATION

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}

/** Short, code-based failure text. Real copy belongs in resources, not in the domain layer. */
fun DomainFailure.describe(): String = when (this) {
    is DomainFailure.Validation -> "输入无效（$code）"
    is DomainFailure.Conflict -> "有正在进行的计时（$code）"
    is DomainFailure.PreconditionChanged -> "状态已变化，请重试：$reason"
    is DomainFailure.NotFound -> "找不到这条记录（$code）"
    is DomainFailure.RecoveryPointUnavailable -> "无法创建本机恢复点，本次变更没有写入（$code）"
    is DomainFailure.Internal -> "内部错误：$detail"
}
