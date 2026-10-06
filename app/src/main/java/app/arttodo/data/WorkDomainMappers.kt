package app.arttodo.data

import app.arttodo.core.AppDate
import app.arttodo.core.ClockSample
import app.arttodo.core.CommandEnvelope
import app.arttodo.core.CommandLogEntry
import app.arttodo.core.CompletionChoice
import app.arttodo.core.DayWindow
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainOutcome
import app.arttodo.core.DomainState
import app.arttodo.core.EventHighWater
import app.arttodo.core.HeartbeatRecord
import app.arttodo.core.IntervalSlice
import app.arttodo.core.LedgerAuditRow
import app.arttodo.core.LedgerEffect
import app.arttodo.core.LedgerRow
import app.arttodo.core.OccurrenceCompletionEvent
import app.arttodo.core.OccurrenceRecord
import app.arttodo.core.SessionMode
import app.arttodo.core.SessionRecord
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.core.TemporaryCompletionEvent
import app.arttodo.core.TitleRevision
import app.arttodo.core.WorkSegment
import app.arttodo.core.ZoneEpoch

/**
 * Conversion between the Room rows and the domain-core records.
 *
 * Kotlin may not reinterpret domain values, so these are pure field-for-field translations: no
 * re-derivation of dates, day lengths or totals happens here (架构契约 §9).
 */
object WorkDomainMappers {

    fun TaskEntity.toDomain(): TaskRecord = TaskRecord(
        taskId = taskId,
        kind = if (kind == 0) TaskKind.DAILY else TaskKind.TEMPORARY,
        title = title,
        note = note,
        sortKey = sortKey.toLong(),
        createdWallMs = createdWallMs,
        archivedAtMs = archivedAtMs,
        lastCountdownMinutes = lastCountdownMinutes.toUInt(),
        artAssetId = artAssetId,
    )

    fun TaskRecord.toEntity(): TaskEntity = TaskEntity(
        taskId = taskId,
        kind = if (kind == TaskKind.DAILY) 0 else 1,
        title = title,
        note = note,
        sortKey = sortKey.toInt(),
        createdWallMs = createdWallMs,
        archivedAtMs = archivedAtMs,
        lastCountdownMinutes = lastCountdownMinutes.toInt(),
        artAssetId = artAssetId,
    )

    fun DailyOccurrenceEntity.toDomain(completion: DerivedCompletion): OccurrenceRecord = OccurrenceRecord(
        occurrenceId = occurrenceId,
        taskId = taskId,
        appDate = appDate,
        zoneEpochSeq = zoneEpochSeq.toUInt(),
        displayTitleSnapshot = displayTitleSnapshot,
        createdAtMs = createdAtMs,
        isCompleted = completion.isCompleted,
        completedWallMs = completion.completedWallMs,
        derivedFromEventHighWater = completion.highWater,
    )

    fun OccurrenceRecord.toEntity(): DailyOccurrenceEntity = DailyOccurrenceEntity(
        occurrenceId = occurrenceId,
        taskId = taskId,
        appDate = appDate,
        zoneEpochSeq = zoneEpochSeq.toInt(),
        displayTitleSnapshot = displayTitleSnapshot,
        createdAtMs = createdAtMs,
    )

    fun FocusSessionEntity.toDomain(): SessionRecord = SessionRecord(
        sessionId = sessionId,
        taskId = taskId,
        mode = if (mode == 0) SessionMode.COUNT_UP else SessionMode.COUNTDOWN,
        targetSeconds = targetSeconds,
        state = when (state) {
            0 -> SessionState.RUNNING
            1 -> SessionState.PAUSED
            2 -> SessionState.FINISHED
            else -> SessionState.RECOVERY_PENDING
        },
        createdWallMs = createdWallMs,
        finishedWallMs = finishedWallMs,
        source = source,
    )

    fun SessionRecord.toEntity(): FocusSessionEntity = FocusSessionEntity(
        sessionId = sessionId,
        taskId = taskId,
        mode = if (mode == SessionMode.COUNT_UP) 0 else 1,
        targetSeconds = targetSeconds,
        state = when (state) {
            SessionState.RUNNING -> 0
            SessionState.PAUSED -> 1
            SessionState.FINISHED -> 2
            SessionState.RECOVERY_PENDING -> 3
        },
        createdWallMs = createdWallMs,
        finishedWallMs = finishedWallMs,
        source = source,
    )

    fun SessionSegmentEntity.toDomain(): WorkSegment = WorkSegment(
        segmentId = segmentId,
        sessionId = sessionId,
        segSeq = segSeq.toUInt(),
        startWallMs = startWallMs,
        endWallMs = endWallMs,
        titleSnapshot = titleSnapshot,
        zoneEpochSeq = zoneEpochSeq.toUInt(),
        derived = derived != 0,
    )

    fun WorkSegment.toEntity(derivedTag: Boolean): SessionSegmentEntity = SessionSegmentEntity(
        segmentId = segmentId,
        sessionId = sessionId,
        segSeq = segSeq.toInt(),
        startWallMs = startWallMs,
        endWallMs = endWallMs,
        startElapsedMs = 0,
        endElapsedMs = null,
        startBootTag = "",
        endBootTag = null,
        titleSnapshot = titleSnapshot,
        zoneEpochSeq = zoneEpochSeq.toInt(),
        derived = if (derived || derivedTag) 1 else 0,
    )

    fun LedgerEntryEntity.toDomain(): LedgerRow = LedgerRow(
        ledgerSeq = ledgerSeq,
        kind = kind,
        refId = refId,
        taskId = taskId,
        appDate = appDate,
        occurredWallMs = occurredWallMs,
        zoneEpochSeq = zoneEpochSeq.toUInt(),
        deltaSeconds = deltaSeconds,
        setTotalSeconds = setTotalSeconds,
        createdWallMs = createdWallMs,
        editedAtMs = editedAtMs,
        isDeleted = isDeleted != 0,
        deletedAtMs = deletedAtMs,
    )

    fun LedgerRow.toEntity(): LedgerEntryEntity = LedgerEntryEntity(
        ledgerSeq = ledgerSeq,
        kind = kind,
        refId = refId,
        taskId = taskId,
        appDate = appDate,
        occurredWallMs = occurredWallMs,
        zoneEpochSeq = zoneEpochSeq.toInt(),
        deltaSeconds = deltaSeconds,
        setTotalSeconds = setTotalSeconds,
        createdWallMs = createdWallMs,
        editedAtMs = editedAtMs,
        isDeleted = if (isDeleted) 1 else 0,
        deletedAtMs = deletedAtMs,
    )

    fun LedgerAuditRow.toEntity(): LedgerEntryAuditEntity = LedgerEntryAuditEntity(
        auditSeq = auditSeq,
        ledgerSeq = ledgerSeq,
        prevDeltaSeconds = prevDeltaSeconds,
        prevSetTotalSeconds = prevSetTotalSeconds,
        newDeltaSeconds = newDeltaSeconds,
        newSetTotalSeconds = newSetTotalSeconds,
        changedAtMs = changedAtMs,
        changeKind = changeKind,
    )

    fun LedgerEntryAuditEntity.toDomainAudit(): LedgerAuditRow = LedgerAuditRow(
        auditSeq = auditSeq,
        ledgerSeq = ledgerSeq,
        prevDeltaSeconds = prevDeltaSeconds,
        prevSetTotalSeconds = prevSetTotalSeconds,
        newDeltaSeconds = newDeltaSeconds,
        newSetTotalSeconds = newSetTotalSeconds,
        changedAtMs = changedAtMs,
        changeKind = changeKind,
    )

    fun OccurrenceCompletionEvent.toEntity(): OccurrenceCompletionEventEntity = OccurrenceCompletionEventEntity(
        eventSeq = eventSeq,
        occurrenceId = occurrenceId,
        action = action,
        occurredWallMs = occurredWallMs,
        appDate = appDate,
        zoneEpochSeq = zoneEpochSeq.toInt(),
    )

    fun OccurrenceCompletionEventEntity.toDomain(): OccurrenceCompletionEvent = OccurrenceCompletionEvent(
        eventSeq = eventSeq,
        occurrenceId = occurrenceId,
        action = action,
        occurredWallMs = occurredWallMs,
        appDate = appDate,
        zoneEpochSeq = zoneEpochSeq.toUInt(),
    )

    fun TemporaryCompletionEventEntity.toDomain(): TemporaryCompletionEvent = TemporaryCompletionEvent(
        eventSeq = eventSeq,
        taskId = taskId,
        action = action,
        occurredWallMs = occurredWallMs,
        appDate = appDate,
        titleSnapshot = titleSnapshot,
    )

    fun ZoneEpoch.toEntity(): ZoneEpochEntity = ZoneEpochEntity(
        epochSeq = epochSeq.toInt(),
        zoneId = zoneId,
        effectiveWallMs = effectiveWallMs,
        impactSummary = impactSummary,
        createdWallMs = createdWallMs,
    )

    fun ZoneEpochEntity.toDomain(): ZoneEpoch = ZoneEpoch(
        epochSeq = epochSeq.toUInt(),
        zoneId = zoneId,
        effectiveWallMs = effectiveWallMs,
        impactSummary = impactSummary,
        createdWallMs = createdWallMs,
    )

    fun CommandLogEntry.toEntity(): CommandLogEntity = CommandLogEntity(
        commandId = commandId,
        appliedWallMs = appliedWallMs,
        commandKind = commandKind,
        resultDigest = resultDigest,
        expectedRevision = expectedRevision.toLong(),
        actualRevision = actualRevision.toLong(),
    )

    fun CommandLogEntity.toDomain(): CommandLogEntry = CommandLogEntry(
        commandId = commandId,
        appliedWallMs = appliedWallMs,
        commandKind = commandKind,
        resultDigest = resultDigest,
        expectedRevision = expectedRevision.toULong(),
        actualRevision = actualRevision.toULong(),
    )

    fun SessionHeartbeatEntity.toDomain(): HeartbeatRecord = HeartbeatRecord(
        sessionId = sessionId,
        wallMs = wallMs,
        elapsedMs = elapsedMs,
        bootTag = bootTag,
        heartbeatKind = heartbeatKind,
    )

    /** Completion state derived from the append-only event log, never from a stored flag. */
    data class DerivedCompletion(
        val isCompleted: Boolean,
        val completedWallMs: Long?,
        val highWater: Long,
    ) {
        companion object {
            fun from(events: List<OccurrenceCompletionEventEntity>): DerivedCompletion {
                var completed = false
                var at: Long? = null
                var high = 0L
                for (event in events.sortedBy { it.eventSeq }) {
                    high = maxOf(high, event.eventSeq)
                    if (event.action == 0) {
                        completed = true
                        at = event.occurredWallMs
                    } else {
                        completed = false
                        at = null
                    }
                }
                return DerivedCompletion(completed, at, high)
            }
        }
    }
}

/** Structural equality for `IntervalSlice`, used to assert slice determinism in tests. */
fun IntervalSlice.sameAs(other: IntervalSlice): Boolean =
    sliceId == other.sliceId && startWallMs == other.startWallMs &&
        endWallMs == other.endWallMs && appDate.iso == other.appDate.iso

/** Structural equality for `DayWindow`. */
fun DayWindow.sameAs(other: DayWindow): Boolean =
    appDate.iso == other.appDate.iso && startWallMs == other.startWallMs &&
        endWallMs == other.endWallMs && daySeconds == other.daySeconds

/** Structural equality for `AppDate`. */
fun AppDate.sameAs(other: AppDate): Boolean = iso == other.iso

/** Structural equality for `ClockSample`. */
fun ClockSample.sameAs(other: ClockSample): Boolean = wallMs == other.wallMs && zoneId == other.zoneId

/** Re-exported so callers do not need to import the generated enum directly for common cases. */
object DomainCommands {
    fun startSession(taskId: String, mode: SessionMode, targetSeconds: Long?, replaces: String?): DomainCommand =
        DomainCommand.StartSession(
            taskId = taskId,
            mode = mode,
            targetSeconds = targetSeconds,
            replacesSessionId = replaces,
        )

    fun finishNow(sessionId: String): DomainCommand =
        DomainCommand.FinishSession(sessionId = sessionId, completion = CompletionChoice.Now)

    fun finishAfter(sessionId: String, seconds: Long): DomainCommand =
        DomainCommand.FinishSession(sessionId = sessionId, completion = CompletionChoice.AfterSeconds(seconds))

    fun pauseSession(sessionId: String): DomainCommand = DomainCommand.PauseSession(sessionId = sessionId)

    fun resumeSession(sessionId: String): DomainCommand = DomainCommand.ResumeSession(sessionId = sessionId)

    fun completeTemporary(taskId: String): DomainCommand = DomainCommand.CompleteTemporary(taskId = taskId)

    fun reopenTemporary(taskId: String): DomainCommand = DomainCommand.ReopenTemporary(taskId = taskId)

    fun createTask(kind: TaskKind, title: String, note: String): DomainCommand =
        DomainCommand.CreateTask(kind = kind, title = title, note = note)

    fun ensureOccurrence(taskId: String, appDate: String): DomainCommand =
        DomainCommand.EnsureOccurrence(taskId = taskId, appDate = appDate)

    fun setCompletion(taskId: String, appDate: String, completed: Boolean): DomainCommand =
        DomainCommand.SetOccurrenceCompletion(taskId = taskId, appDate = appDate, completed = completed)

    fun addManualSeconds(taskId: String, appDate: String, seconds: Long): DomainCommand =
        DomainCommand.AddManualSeconds(taskId = taskId, appDate = appDate, seconds = seconds)

    fun setDailyTotal(taskId: String, appDate: String, totalSeconds: Long): DomainCommand =
        DomainCommand.SetDailyTotalSeconds(taskId = taskId, appDate = appDate, totalSeconds = totalSeconds)

    fun reorderTasks(kind: TaskKind, orderedTaskIds: List<String>): DomainCommand =
        DomainCommand.ReorderTasks(kind = kind, orderedTaskIds = orderedTaskIds)
}

/** Conversion helpers kept next to the mapper for readability. */
fun CommandEnvelope.withRevision(revision: Long): CommandEnvelope =
    copy(expectedRevision = revision.toULong())

/** Title revisions for one task, ordered by effectiveness, as the core expects them. */
fun List<TaskTitleRevisionEntity>.toDomainRevisions(): List<TitleRevision> =
    sortedBy { it.effectiveWallMs }.map { TitleRevision(effectiveWallMs = it.effectiveWallMs, title = it.title) }
