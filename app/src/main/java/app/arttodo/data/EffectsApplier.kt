package app.arttodo.data

import androidx.room.withTransaction
import app.arttodo.core.LedgerEffect
import app.arttodo.data.WorkDomainMappers.toEntity

/**
 * Applies the domain core's intent list to Room.
 *
 * The whole list — every effect plus the `command_log` row — lands in **one** transaction, so a
 * crash can never persist part of a command (架构契约 §3.3). Nothing here interprets the domain:
 * effects are persistence intent, and this class only writes them.
 *
 * Three properties this class is responsible for, all covered by `DatabaseWriteTest`:
 *
 * 1. **No write is destructive.** Parent tables (`task`, `daily_occurrence`, `focus_session`) are
 *    updated in place rather than replaced, because `INSERT OR REPLACE` would delete the row and
 *    cascade its history away.
 * 2. **Append-only tables stay append-only, and only the rows with a deterministic key are
 *    deduplicated.** The whole effect list is one transaction, so an append-only row can never be
 *    half-written; the only repair a replay can need is for a row whose *content* is a pure function
 *    of the booked span, which is exactly the `kind=0` ledger slice keyed by `ref_id` (架构契约 §6).
 *    Event rows carry no such key — their order and repetition are the meaning — so they are inserted
 *    as emitted and never merged on a heuristic such as "same action, same instant", which would
 *    silently drop a legitimate second event whenever the clock has not advanced between two taps.
 * 3. **Nothing reaches the file that the write boundary rejects.** Title, note and countdown minutes
 *    are checked here, inside the transaction, so a refusal rolls back the entire effect list.
 */
class EffectsApplier(
    private val database: AppDatabase,
    /**
     * Resolves an instant to its application date through the domain core. Injected because Kotlin
     * is not allowed to derive dates itself (架构契约 §2.2); it exists only to fill the
     * denormalised `effective_app_date` audit column on a title revision.
     */
    private val appDateResolver: suspend (wallMs: Long) -> String,
) {

    private val taskDao = database.taskDao()
    private val titleDao = database.titleRevisionDao()
    private val occurrenceDao = database.occurrenceDao()
    private val sessionDao = database.sessionDao()
    private val ledgerDao = database.ledgerDao()
    private val supportDao = database.supportDao()

    /** Result of one applied command: how many effects were written. */
    data class Result(val effectsApplied: Int)

    suspend fun apply(effects: List<LedgerEffect>): Result = database.withTransaction {
        var applied = 0
        for (effect in effects) {
            when (effect) {
                is LedgerEffect.UpsertTask -> {
                    val task = effect.task.toEntity()
                    FieldLimits.titleViolation(task.title)?.let { throw DataViolationException(it) }
                    FieldLimits.noteViolation(task.note)?.let { throw DataViolationException(it) }
                    FieldLimits.countdownMinutesViolation(task.lastCountdownMinutes)
                        ?.let { throw DataViolationException(it) }
                    upsertTask(task)
                    applied++
                }

                is LedgerEffect.RenameTitleRevision -> {
                    // A rename records when it took effect so a later slice can be cut at that
                    // instant; the past keeps the old title (AC-14).
                    FieldLimits.titleViolation(effect.revision.title)
                        ?.let { throw DataViolationException(it) }
                    titleDao.insert(
                        TaskTitleRevisionEntity(
                            taskId = effect.taskId,
                            title = effect.revision.title,
                            effectiveWallMs = effect.revision.effectiveWallMs,
                            effectiveAppDate = appDateResolver(effect.revision.effectiveWallMs),
                            zoneEpochSeq = effect.zoneEpochSeq.toInt(),
                        ),
                    )
                    applied++
                }

                is LedgerEffect.UpsertOccurrence -> {
                    val occurrence = effect.occurrence.toEntity()
                    upsertOccurrence(occurrence)
                    // The derived view is written in the same transaction as its truth.
                    occurrenceDao.upsertView(
                        DailyOccurrenceViewEntity(
                            occurrenceId = occurrence.occurrenceId,
                            isCompleted = if (effect.occurrence.isCompleted) 1 else 0,
                            completedWallMs = effect.occurrence.completedWallMs,
                            derivedFromEventHighWater = effect.occurrence.derivedFromEventHighWater,
                        ),
                    )
                    applied++
                }

                is LedgerEffect.AppendOccurrenceCompletionEvent -> {
                    // Append-only, and deliberately not deduplicated: two completions at the same
                    // millisecond are two facts, and the event log is their only record. Replaying
                    // the *same* command cannot reach here twice, because the command id is the
                    // primary key of `command_log` and the core refuses a duplicate.
                    occurrenceDao.insertEvent(effect.event.toEntity())
                    applied++
                }

                is LedgerEffect.AppendTemporaryCompletionEvent -> {
                    occurrenceDao.insertTemporaryEvent(
                        TemporaryCompletionEventEntity(
                            taskId = effect.taskId,
                            action = effect.action,
                            occurredWallMs = effect.occurredWallMs,
                            appDate = effect.appDate,
                            titleSnapshot = effect.titleSnapshot,
                        ),
                    )
                    applied++
                }

                is LedgerEffect.OpenSession -> {
                    // Before condition: `active_session_slot.session_id` is a foreign key to
                    // `focus_session`, so the session row must exist first (see the effect-order
                    // contract). The session is upserted in place, which is also what makes a
                    // replayed `StartSession` safe: it cannot delete the segment it just wrote.
                    val session = effect.session.toEntity()
                    upsertSession(session)
                    sessionDao.upsertSegment(effect.segment.toEntity(derivedTag = false))
                    sessionDao.setActiveSlot(ActiveSessionSlotEntity(sessionId = session.sessionId))
                    applied++
                }

                is LedgerEffect.UpdateSession -> {
                    upsertSession(effect.session.toEntity())
                    effect.closedSegment?.let { sessionDao.upsertSegment(it.toEntity(derivedTag = false)) }
                    effect.openedSegment?.let { sessionDao.upsertSegment(it.toEntity(derivedTag = false)) }
                    applied++
                }

                is LedgerEffect.CloseSegment -> {
                    sessionDao.closeSegment(effect.segmentId, effect.endWallMs)
                    applied++
                }

                is LedgerEffect.SetActiveSession -> {
                    if (effect.sessionId == null) {
                        sessionDao.clearActiveSlot()
                    } else {
                        sessionDao.setActiveSlot(ActiveSessionSlotEntity(sessionId = effect.sessionId))
                    }
                    applied++
                }

                is LedgerEffect.SetHeartbeatAnchor -> {
                    sessionDao.upsertHeartbeat(
                        SessionHeartbeatEntity(
                            sessionId = effect.sessionId,
                            wallMs = effect.wallMs,
                            elapsedMs = effect.elapsedMs,
                            bootTag = effect.bootTag,
                            heartbeatKind = effect.heartbeatKind,
                        ),
                    )
                    applied++
                }

                is LedgerEffect.AppendLedgerEntry -> {
                    val row = effect.row.toEntity()
                    // kind=0 automatic slices are keyed by their deterministic span. Recovery
                    // adjustments also carry a deterministic source-slice key, so a replay can safely
                    // skip an already persisted adjustment without content-based deduplication.
                    // Other ledger rows rely on the command log and are never heuristically merged.
                    val duplicateSlice = row.refId != null && when (row.kind) {
                        0 -> ledgerDao.sliceByRef(row.refId) != null
                        3 -> ledgerDao.recoveryAdjustmentByRef(row.refId) != null
                        else -> false
                    }
                    if (!duplicateSlice) ledgerDao.insert(row)
                    applied++
                }

                is LedgerEffect.UpdateLedgerEntry -> {
                    ledgerDao.update(effect.row.toEntity())
                    applied++
                }

                is LedgerEffect.InsertLedgerEntryAudit -> {
                    // The pre-image of a change. Appended as emitted: an edit is one command, and a
                    // replayed command is refused before it gets here, so merging two audits would
                    // only be able to destroy evidence (规格 6.1:130).
                    ledgerDao.insertAudit(effect.audit.toEntity())
                    applied++
                }

                is LedgerEffect.SoftDeleteLedgerEntry -> {
                    ledgerDao.softDelete(effect.ledgerSeq, effect.deletedAtMs)
                    applied++
                }

                is LedgerEffect.UpsertZoneEpoch -> {
                    supportDao.upsertZoneEpoch(effect.epoch.toEntity())
                    // The pointer in `app_setting` is a cache of the newest epoch row, so it is
                    // written in the *same* transaction as the row it mirrors: a crash cannot leave
                    // the two disagreeing, and a manual zone change cannot leave a stale pointer
                    // behind (架构契约 §2.4, same pattern as the occurrence view next to its event).
                    supportDao.upsertSetting(
                        AppSettingEntity(key = AppSettings.KEY_APP_ZONE, value = effect.epoch.zoneId),
                    )
                    applied++
                }

                is LedgerEffect.AppendCommandLog -> {
                    val entry = effect.entry.toEntity()
                    // The primary key is the idempotency key: a replayed command id writes once.
                    supportDao.logCommand(entry)
                    applied++
                }
            }
        }
        Result(effectsApplied = applied)
    }

    /**
     * Update-first upserts for the three tables that have cascade children.
     *
     * `update` returns the number of rows changed, so `0` means the row is new. Neither branch can
     * delete an existing row, which is the whole point: a replace would cascade this row's history
     * away (see the class comment). They are intentionally identical and kept next to the `when`
     * rather than abstracted, because the three DAO pairs have different column sets.
     */
    private suspend fun upsertTask(task: TaskEntity) {
        if (taskDao.update(task) == 0) taskDao.insert(task)
    }

    private suspend fun upsertOccurrence(occurrence: DailyOccurrenceEntity) {
        if (occurrenceDao.update(occurrence) == 0) occurrenceDao.insert(occurrence)
    }

    private suspend fun upsertSession(session: FocusSessionEntity) {
        if (sessionDao.updateSession(session) == 0) sessionDao.insertSession(session)
    }
}
