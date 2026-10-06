package app.arttodo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Query("SELECT * FROM task WHERE task_id = :taskId")
    suspend fun find(taskId: String): TaskEntity?

    @Query("SELECT * FROM task")
    suspend fun all(): List<TaskEntity>

    /**
     * Update-first upsert. `INSERT OR REPLACE` is deliberately NOT used: SQLite implements it as
     * delete-then-insert, and `task` is the parent of `daily_occurrence`, `focus_session`,
     * `ledger_entry`, `task_title_revision` and `temporary_completion_event` with ON DELETE
     * CASCADE, so a replace would erase the task's whole history (see `DatabaseWriteTest`).
     */
    @Update
    suspend fun update(task: TaskEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity): Long

    @Query("SELECT COUNT(*) FROM task")
    suspend fun count(): Int
}

@Dao
interface TitleRevisionDao {
    /**
     * REPLACE (not ABORT) because `(task_id, effective_wall_ms)` is unique: a rename stamped at an
     * instant that already has a revision describes the same instant, so the newest title wins
     * instead of aborting the whole command. The table has no children, so replacing a row cannot
     * cascade anything away.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(revision: TaskTitleRevisionEntity)

    @Query("SELECT * FROM task_title_revision WHERE task_id = :taskId ORDER BY effective_wall_ms ASC")
    suspend fun forTask(taskId: String): List<TaskTitleRevisionEntity>

    @Query("SELECT COUNT(*) FROM task_title_revision")
    suspend fun count(): Int
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM daily_occurrence")
    suspend fun all(): List<DailyOccurrenceEntity>

    @Query("SELECT * FROM daily_occurrence WHERE task_id = :taskId AND app_date = :appDate")
    suspend fun find(taskId: String, appDate: String): DailyOccurrenceEntity?

    @Query("SELECT * FROM daily_occurrence WHERE occurrence_id = :occurrenceId")
    suspend fun byId(occurrenceId: String): DailyOccurrenceEntity?

    /**
     * Update-first upsert: `daily_occurrence` is the parent of the authoritative
     * `occurrence_completion_event` log and of `daily_occurrence_view`, both ON DELETE CASCADE, so
     * `INSERT OR REPLACE` here would delete the only record of whether a day was completed.
     */
    @Update
    suspend fun update(occurrence: DailyOccurrenceEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(occurrence: DailyOccurrenceEntity): Long

    @Query("SELECT COUNT(*) FROM daily_occurrence")
    suspend fun count(): Int

    @Query("SELECT * FROM occurrence_completion_event WHERE occurrence_id = :occurrenceId ORDER BY event_seq ASC")
    suspend fun eventsFor(occurrenceId: String): List<OccurrenceCompletionEventEntity>

    @Query("SELECT COUNT(*) FROM occurrence_completion_event")
    suspend fun eventCount(): Int

    @Insert
    suspend fun insertEvent(event: OccurrenceCompletionEventEntity): Long

    @Query("SELECT COALESCE(MAX(event_seq), 0) FROM occurrence_completion_event")
    suspend fun maxEventSeq(): Long

    /** Safe as a replace: the view is a leaf table, nothing references it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertView(view: DailyOccurrenceViewEntity)

    @Query("SELECT * FROM daily_occurrence_view WHERE occurrence_id = :occurrenceId")
    suspend fun view(occurrenceId: String): DailyOccurrenceViewEntity?

    @Insert
    suspend fun insertTemporaryEvent(event: TemporaryCompletionEventEntity)

    @Query("SELECT COUNT(*) FROM temporary_completion_event")
    suspend fun temporaryEventCount(): Int

    @Query("SELECT * FROM temporary_completion_event WHERE task_id = :taskId ORDER BY event_seq ASC")
    suspend fun temporaryEventsFor(taskId: String): List<TemporaryCompletionEventEntity>
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM focus_session")
    suspend fun all(): List<FocusSessionEntity>

    @Query("SELECT * FROM session_segment")
    suspend fun allSegments(): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_heartbeat")
    suspend fun allHeartbeats(): List<SessionHeartbeatEntity>

    /**
     * Update-first upsert: `focus_session` is the parent of `session_segment`,
     * `active_session_slot` and `session_heartbeat` with ON DELETE CASCADE, so a replace while
     * pausing, resuming or finishing would delete the session's other segments and its heartbeat.
     */
    @Update
    suspend fun updateSession(session: FocusSessionEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: FocusSessionEntity): Long

    @Query("SELECT COUNT(*) FROM focus_session")
    suspend fun count(): Int

    @Query("SELECT * FROM focus_session WHERE session_id = :sessionId")
    suspend fun session(sessionId: String): FocusSessionEntity?

    @Query("SELECT * FROM active_session_slot WHERE slot_id = 1")
    suspend fun activeSlot(): ActiveSessionSlotEntity?

    /** Replacing the single slot is what keeps "at most one active session" true in SQL. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActiveSlot(slot: ActiveSessionSlotEntity)

    @Query("DELETE FROM active_session_slot WHERE slot_id = 1")
    suspend fun clearActiveSlot()

    /** Safe as a replace: `session_segment` is a leaf table. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegment(segment: SessionSegmentEntity)

    @Query("SELECT * FROM session_segment WHERE session_id = :sessionId ORDER BY seg_seq ASC")
    suspend fun segments(sessionId: String): List<SessionSegmentEntity>

    @Query("SELECT COUNT(*) FROM session_segment")
    suspend fun segmentCount(): Int

    @Query("UPDATE session_segment SET end_wall_ms = :endWallMs WHERE segment_id = :segmentId")
    suspend fun closeSegment(segmentId: String, endWallMs: Long)

    /** Safe as a replace: one heartbeat row per session, nothing references it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeartbeat(heartbeat: SessionHeartbeatEntity)

    @Query("SELECT MAX(wall_ms) FROM session_heartbeat WHERE session_id = :sessionId")
    suspend fun latestHeartbeat(sessionId: String): Long?
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entry")
    suspend fun all(): List<LedgerEntryEntity>

    /**
     * Ledger rows whose application date is inside `[fromIso, toIso]`, in replay order.
     *
     * Order is `(occurred_wall_ms, ledger_seq)` — the contract's replay order, written once here so
     * no screen can invent a different one (架构契约 §4.3.1).
     */
    @Query(
        "SELECT * FROM ledger_entry WHERE app_date >= :fromIso AND app_date <= :toIso " +
            "ORDER BY occurred_wall_ms ASC, ledger_seq ASC",
    )
    suspend fun range(fromIso: String, toIso: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entry_audit")
    suspend fun allAudits(): List<LedgerEntryAuditEntity>

    @Query(
        "SELECT * FROM ledger_entry WHERE task_id = :taskId AND app_date = :appDate " +
            "ORDER BY occurred_wall_ms ASC, ledger_seq ASC",
    )
    suspend fun forTaskDay(taskId: String, appDate: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entry WHERE ledger_seq = :ledgerSeq")
    suspend fun row(ledgerSeq: Long): LedgerEntryEntity?

    @Query("SELECT * FROM ledger_entry WHERE kind = 0 AND ref_id = :refId")
    suspend fun sliceByRef(refId: String): LedgerEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: LedgerEntryEntity): Long

    @Update
    suspend fun update(row: LedgerEntryEntity)

    @Query("UPDATE ledger_entry SET is_deleted = 1, deleted_at_ms = :deletedAtMs WHERE ledger_seq = :ledgerSeq")
    suspend fun softDelete(ledgerSeq: Long, deletedAtMs: Long)

    @Query("SELECT COUNT(*) FROM ledger_entry")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM ledger_entry WHERE is_deleted = 0")
    suspend fun liveCount(): Int

    @Insert
    suspend fun insertAudit(audit: LedgerEntryAuditEntity)

    @Query("SELECT * FROM ledger_entry_audit WHERE ledger_seq = :ledgerSeq ORDER BY audit_seq ASC")
    suspend fun audits(ledgerSeq: Long): List<LedgerEntryAuditEntity>

    @Query("SELECT COUNT(*) FROM ledger_entry_audit")
    suspend fun auditCount(): Int
}

@Dao
interface SupportDao {
    @Query("SELECT * FROM command_log")
    suspend fun allCommands(): List<CommandLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZoneEpoch(epoch: ZoneEpochEntity)

    @Query("SELECT * FROM zone_epoch ORDER BY epoch_seq ASC")
    suspend fun zoneEpochs(): List<ZoneEpochEntity>

    @Query("SELECT COUNT(*) FROM zone_epoch")
    suspend fun zoneEpochCount(): Int

    /** Used by [DomainStateLoader.initialiseIfEmpty] to decide whether epoch 1 already exists. */
    @Query("SELECT COALESCE(MAX(epoch_seq), 0) FROM zone_epoch")
    suspend fun maxEpochSeq(): Int

    /** IGNORE: the primary key is the idempotency key, so a replayed command writes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun logCommand(entry: CommandLogEntity): Long

    @Query("SELECT * FROM command_log WHERE command_id = :commandId")
    suspend fun command(commandId: String): CommandLogEntity?

    @Query("SELECT COUNT(*) FROM command_log")
    suspend fun commandCount(): Int

    /** Safe as a replace: a two-column leaf table. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: AppSettingEntity)

    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun setting(key: String): String?

    @Query("SELECT COUNT(*) FROM app_setting")
    suspend fun settingCount(): Int

    @Query("SELECT * FROM backup_record ORDER BY created_wall_ms ASC")
    suspend fun backupRecords(): List<BackupRecordEntity>

    @Query("SELECT COUNT(*) FROM backup_record")
    suspend fun backupRecordCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBackupRecord(record: BackupRecordEntity)

    @Query("DELETE FROM backup_record WHERE record_id = :recordId")
    suspend fun deleteBackupRecord(recordId: String)
}
