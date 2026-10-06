package app.arttodo.domain.bridge

import app.arttodo.core.AppDate
import app.arttodo.core.ClockSample
import app.arttodo.core.CommandEnvelope
import app.arttodo.core.DayTotal
import app.arttodo.core.DayWindow
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainOutcome
import app.arttodo.core.DomainState
import app.arttodo.core.IntervalSlice
import app.arttodo.core.LedgerRow
import app.arttodo.core.LedgerRowTitle
import app.arttodo.core.OccurrenceCompletionEvent
import app.arttodo.core.OccurrenceRecord
import app.arttodo.core.PeriodTotal
import app.arttodo.core.RecoveryChoice
import app.arttodo.core.RecoveryAmounts
import app.arttodo.core.TaskRecord
import app.arttodo.core.TaskShare
import app.arttodo.core.TemporaryCompletionEvent
import app.arttodo.core.TimeBucket
import app.arttodo.core.TitleRevision
import app.arttodo.core.WorkSegment

/**
 * The Kotlin-side seam over the domain core (架构契约 §9, "可测接口").
 *
 * Production routes through UniFFI; unit tests can substitute a scripted fake, which is what keeps
 * UI tests independent of the native library.
 */
interface DomainBridge {
    /** Time bucketing: only the domain core decides which date an instant belongs to. */
    fun appDateOf(wallMs: Long, zoneId: String, zoneEpochSeq: Int): DomainResult<AppDate>

    fun dayWindow(appDate: String, zoneId: String, zoneEpochSeq: Int): DomainResult<DayWindow>

    fun sliceInterval(
        sessionId: String,
        segSeq: Int,
        startWallMs: Long,
        endWallMs: Long,
        zoneId: String,
        zoneEpochSeq: Int,
        titleRevisions: List<TitleRevision>,
    ): DomainResult<List<IntervalSlice>>

    fun initialState(zoneId: String, createdWallMs: Long): DomainResult<DomainState>

    /**
     * Applies one user command. Never throws: a Rust panic surfaces as `DomainFailure.Internal`
     * rather than crashing the app (架构契约 §3.4 / §10).
     */
    fun reduce(state: DomainState, command: DomainCommand, envelope: CommandEnvelope): DomainResult<DomainOutcome>

    /** Replays one task-day's ledger rows; ordering is the core's decision, not Kotlin's. */
    fun replayDailyTotal(taskId: String, appDate: String, rows: List<LedgerRow>): DomainResult<Long>

    /** Running total after each row, for the before/after preview in the ledger editor. */
    fun replayDailyTrace(rows: List<LedgerRow>): DomainResult<List<Long>>

    /** Total the day would end at without one row — drives the kind=2 deletion notice. */
    fun totalWithoutLedgerEntry(rows: List<LedgerRow>, ledgerSeq: Long): DomainResult<Long>

    /** `EditLedgerEntry` precondition check, so a screen can validate before dispatching. */
    fun validateEditLedgerEntry(
        rowKind: Int,
        newDeltaSeconds: Long?,
        newSetTotalSeconds: Long?,
    ): DomainResult<Boolean>

    /**
     * Trusted / gap / booked seconds for an interrupted session (AC-09).
     *
     * The three amounts are all decided by the core, including the countdown cap, so no screen
     * recomputes a recovery split.
     */
    fun recoveryAmounts(
        startWallMs: Long,
        heartbeatWallMs: Long,
        recoveryWallMs: Long,
        choice: RecoveryChoice,
        targetSeconds: Long?,
    ): DomainResult<RecoveryAmounts>

    // ---- Projections (架构契约 §3.2 查询行) -------------------------------------------------
    // Totals, trends, shares and heat-map cells all read through these, which is what keeps the
    // calendar, the trend chart, the heat map and the share list from disagreeing (AC-12 / N10).

    fun projectDayTotals(rows: List<LedgerRow>): DomainResult<List<DayTotal>>

    fun projectPeriodTotals(rows: List<LedgerRow>, bucket: TimeBucket): DomainResult<List<PeriodTotal>>

    fun projectTaskShares(rows: List<LedgerRow>): DomainResult<List<TaskShare>>

    /**
     * The name to show for each ledger row (AC-14).
     *
     * An automatic slice resolves to the segment's stored `title_snapshot` — the name the time was
     * actually invested under — so a later rename cannot rewrite an earlier row; a manual add or a
     * set-total row has no past name of its own and comes back flagged as the task's *current* title.
     * Resolving this in the screen from `TaskRecord.title` would be exactly the masquerade AC-14
     * forbids, so it is a core projection rather than a UI lookup.
     */
    fun ledgerRowTitles(
        rows: List<LedgerRow>,
        segments: List<WorkSegment>,
        tasks: List<TaskRecord>,
    ): DomainResult<List<LedgerRowTitle>>

    /** Civil day label of an instant, using the same day windows slicing uses. */
    fun dayLabelOf(wallMs: Long, zoneId: String, zoneEpochSeq: Int): DomainResult<String>

    // ---- Event replay reads (completion truth) ----------------------------------------------

    /** Todo/done of a temporary task, replayed from its events (AC-11). */
    fun temporaryCompleted(
        events: List<TemporaryCompletionEvent>,
        taskId: String,
    ): DomainResult<Boolean>

    /**
     * Completion of one daily instance, replayed from the authoritative events (AC-03 / AC-07).
     *
     * This is the read that decides; the cached flag on the record is never trusted alone.
     */
    fun occurrenceCompleted(
        events: List<OccurrenceCompletionEvent>,
        occurrenceId: String,
    ): DomainResult<Boolean>

    /** True when a cached instance already reflects all of its events (架构契约 §4.1). */
    fun occurrenceViewIsCurrent(
        occurrence: OccurrenceRecord,
        events: List<OccurrenceCompletionEvent>,
    ): DomainResult<Boolean>

    /** Investment seconds of a session, which is what pause must not inflate. */
    fun effectiveSeconds(sessionId: String, segments: List<WorkSegment>, nowWallMs: Long): DomainResult<Long>

    fun clock(wallMs: Long, zoneId: String, elapsedMs: Long = 0, bootTag: String = ""): ClockSample
}

/** Builds an envelope without repeating the clock sample at each call site. */
fun DomainBridge.envelope(
    commandId: String,
    wallMs: Long,
    zoneId: String,
    expectedRevision: Long,
): CommandEnvelope = CommandEnvelope(
    commandId = commandId,
    issuedAt = clock(wallMs, zoneId),
    expectedRevision = expectedRevision.toULong(),
)
