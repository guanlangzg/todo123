package app.arttodo.domain.bridge

import app.arttodo.core.AppDate
import app.arttodo.core.ClockSample
import app.arttodo.core.CommandEnvelope
import app.arttodo.core.DayTotal
import app.arttodo.core.DayWindow
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainException
import app.arttodo.core.DomainOutcome
import app.arttodo.core.DomainState
import app.arttodo.core.IntervalSlice
import app.arttodo.core.InternalException
import app.arttodo.core.LedgerRow
import app.arttodo.core.LedgerRowTitle
import app.arttodo.core.OccurrenceCompletionEvent
import app.arttodo.core.OccurrenceRecord
import app.arttodo.core.PeriodTotal
import app.arttodo.core.RecoveryAmounts
import app.arttodo.core.RecoveryChoice
import app.arttodo.core.TaskRecord
import app.arttodo.core.TaskShare
import app.arttodo.core.TemporaryCompletionEvent
import app.arttodo.core.TimeBucket
import app.arttodo.core.TitleRevision
import app.arttodo.core.WorkSegment
import android.os.SystemClock
import app.arttodo.core.appDateOf as coreAppDateOf
import app.arttodo.core.dayLabelOf as coreDayLabelOf
import app.arttodo.core.dayWindow as coreDayWindow
import app.arttodo.core.effectiveSeconds as coreEffectiveSeconds
import app.arttodo.core.initialState as coreInitialState
import app.arttodo.core.occurrenceCompleted as coreOccurrenceCompleted
import app.arttodo.core.occurrenceViewIsCurrent as coreOccurrenceViewIsCurrent
import app.arttodo.core.projectDayTotals as coreProjectDayTotals
import app.arttodo.core.projectLedgerRowTitles as coreProjectLedgerRowTitles
import app.arttodo.core.projectPeriodTotals as coreProjectPeriodTotals
import app.arttodo.core.projectTaskShares as coreProjectTaskShares
import app.arttodo.core.recoveryAmounts as coreRecoveryAmounts
import app.arttodo.core.reduce as coreReduce
import app.arttodo.core.replayDailyTotal as coreReplayDailyTotal
import app.arttodo.core.replayDailyTrace as coreReplayDailyTrace
import app.arttodo.core.sliceInterval as coreSliceInterval
import app.arttodo.core.temporaryCompleted as coreTemporaryCompleted
import app.arttodo.core.totalWithoutLedgerEntry as coreTotalWithoutLedgerEntry
import app.arttodo.core.validateEditLedgerEntry as coreValidateEditLedgerEntry

/**
 * Production bridge: the real UniFFI calls into `arttodo_core`.
 *
 * Both exception channels are caught here. User errors arrive as [DomainException]; a Rust panic
 * arrives as UniFFI's own [InternalException], and it must be contained rather than crashing the
 * app (架构契约 §3.4, E17).
 */
class UniffiDomainBridge : DomainBridge {

    override fun appDateOf(wallMs: Long, zoneId: String, zoneEpochSeq: Int): DomainResult<AppDate> =
        guarded { coreAppDateOf(wallMs, zoneId, zoneEpochSeq.toUInt()) }

    override fun dayWindow(appDate: String, zoneId: String, zoneEpochSeq: Int): DomainResult<DayWindow> =
        guarded { coreDayWindow(appDate, zoneId, zoneEpochSeq.toUInt()) }

    override fun sliceInterval(
        sessionId: String,
        segSeq: Int,
        startWallMs: Long,
        endWallMs: Long,
        zoneId: String,
        zoneEpochSeq: Int,
        titleRevisions: List<TitleRevision>,
    ): DomainResult<List<IntervalSlice>> = guarded {
        coreSliceInterval(sessionId, segSeq.toUInt(), startWallMs, endWallMs, zoneId, zoneEpochSeq.toUInt(), titleRevisions)
    }

    override fun initialState(zoneId: String, createdWallMs: Long): DomainResult<DomainState> =
        guarded { coreInitialState(zoneId, createdWallMs) }

    override fun reduce(
        state: DomainState,
        command: DomainCommand,
        envelope: CommandEnvelope,
    ): DomainResult<DomainOutcome> = guarded { coreReduce(state, command, envelope) }

    override fun replayDailyTotal(taskId: String, appDate: String, rows: List<LedgerRow>): DomainResult<Long> =
        guarded { coreReplayDailyTotal(rows) }

    override fun effectiveSeconds(
        sessionId: String,
        segments: List<WorkSegment>,
        nowWallMs: Long,
    ): DomainResult<Long> = guarded { coreEffectiveSeconds(sessionId, segments, nowWallMs) }

    override fun replayDailyTrace(rows: List<LedgerRow>): DomainResult<List<Long>> =
        guarded { coreReplayDailyTrace(rows) }

    override fun totalWithoutLedgerEntry(rows: List<LedgerRow>, ledgerSeq: Long): DomainResult<Long> =
        guarded { coreTotalWithoutLedgerEntry(rows, ledgerSeq) }

    override fun validateEditLedgerEntry(
        rowKind: Int,
        newDeltaSeconds: Long?,
        newSetTotalSeconds: Long?,
    ): DomainResult<Boolean> = guarded {
        coreValidateEditLedgerEntry(rowKind, newDeltaSeconds, newSetTotalSeconds)
    }

    override fun recoveryAmounts(
        startWallMs: Long,
        heartbeatWallMs: Long,
        recoveryWallMs: Long,
        choice: RecoveryChoice,
        targetSeconds: Long?,
    ): DomainResult<RecoveryAmounts> = guarded {
        coreRecoveryAmounts(startWallMs, heartbeatWallMs, recoveryWallMs, choice, targetSeconds)
    }

    override fun projectDayTotals(rows: List<LedgerRow>): DomainResult<List<DayTotal>> =
        guarded { coreProjectDayTotals(rows) }

    override fun projectPeriodTotals(
        rows: List<LedgerRow>,
        bucket: TimeBucket,
    ): DomainResult<List<PeriodTotal>> = guarded { coreProjectPeriodTotals(rows, bucket) }

    override fun projectTaskShares(rows: List<LedgerRow>): DomainResult<List<TaskShare>> =
        guarded { coreProjectTaskShares(rows) }

    override fun ledgerRowTitles(
        rows: List<LedgerRow>,
        segments: List<WorkSegment>,
        tasks: List<TaskRecord>,
    ): DomainResult<List<LedgerRowTitle>> = guarded {
        coreProjectLedgerRowTitles(rows, segments, tasks)
    }

    override fun dayLabelOf(wallMs: Long, zoneId: String, zoneEpochSeq: Int): DomainResult<String> =
        guarded { coreDayLabelOf(wallMs, zoneId, zoneEpochSeq.toUInt()) }

    override fun temporaryCompleted(
        events: List<TemporaryCompletionEvent>,
        taskId: String,
    ): DomainResult<Boolean> = guarded { coreTemporaryCompleted(events, taskId) }

    override fun occurrenceCompleted(
        events: List<OccurrenceCompletionEvent>,
        occurrenceId: String,
    ): DomainResult<Boolean> = guarded { coreOccurrenceCompleted(events, occurrenceId) }

    override fun occurrenceViewIsCurrent(
        occurrence: OccurrenceRecord,
        events: List<OccurrenceCompletionEvent>,
    ): DomainResult<Boolean> = guarded { coreOccurrenceViewIsCurrent(occurrence, events) }

    override fun clock(wallMs: Long, zoneId: String, elapsedMs: Long, bootTag: String): ClockSample = ClockSample(
        wallMs = wallMs,
        zoneId = zoneId,
        elapsedMs = elapsedMs,
        bootTag = bootTag,
    )
}

/**
 * Runs one FFI call and converts both exception channels into a [DomainResult].
 *
 * Nothing thrown by the native layer may escape the bridge, which is exactly why the two `catch`
 * clauses are both required rather than one being a subclass of the other.
 */
private inline fun <T> guarded(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (error: DomainException) {
    DomainResult.Failure(error.toFailure())
} catch (error: InternalException) {
    // UniFFI's containment of a Rust panic. The app must stay up and offer a retry.
    DomainResult.Failure(DomainFailure.Internal(error.message ?: "Rust panic"))
} catch (error: UnsatisfiedLinkError) {
    // A missing or mismatched native library is an integration defect, not a domain error; it is
    // reported through the same channel so the UI never crashes on it.
    DomainResult.Failure(DomainFailure.Internal("native library unavailable: ${error.message}"))
}
