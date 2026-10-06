package app.arttodo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Task list row. Owner: data-room (工程布局与版本锁定.md §3).
 *
 * `archivedAtMs` is independent of completion: archiving keeps history and produces no completion
 * event (AC-10). `lastCountdownMinutes` is the per-task remembered countdown, a memory aid rather
 * than a goal.
 */
@Entity(
    tableName = "task",
    indices = [
        Index(value = ["kind", "archived_at_ms", "sort_key"]),
        // Covers the art_asset_id foreign key so a parent change does not scan this table.
        Index(value = ["art_asset_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArtAssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["art_asset_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    /** 0 = daily, 1 = temporary. */
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "sort_key") val sortKey: Int,
    @ColumnInfo(name = "created_wall_ms") val createdWallMs: Long,
    @ColumnInfo(name = "archived_at_ms") val archivedAtMs: Long? = null,
    @ColumnInfo(name = "last_countdown_minutes", defaultValue = "25") val lastCountdownMinutes: Int = 25,
    @ColumnInfo(name = "art_asset_id") val artAssetId: String? = null,
)

/**
 * Title history so a rename can cut a segment without rewriting the past (AC-14).
 *
 * `(task_id, effective_wall_ms)` is UNIQUE as of schema 2. Slicing reads the revisions of a segment
 * to label each piece, and two revisions carrying the same instant leave the label of the piece
 * that starts exactly then dependent on input order. One instant therefore holds exactly one title
 * (a same-instant rename overwrites rather than appends; see `TitleRevisionDao.insert`).
 */
@Entity(
    tableName = "task_title_revision",
    indices = [Index(value = ["task_id", "effective_wall_ms"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["task_id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TaskTitleRevisionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "revision_seq") val revisionSeq: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "effective_wall_ms") val effectiveWallMs: Long,
    @ColumnInfo(name = "effective_app_date") val effectiveAppDate: String,
    @ColumnInfo(name = "zone_epoch_seq") val zoneEpochSeq: Int,
)

/**
 * Generation fact for one (task, app_date). `(task_id, app_date)` is unique, so lazy generation is
 * idempotent (AC-01) and a re-enabled daily task reuses the same occurrence on the same day.
 */
@Entity(
    tableName = "daily_occurrence",
    indices = [Index(value = ["task_id", "app_date"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["task_id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class DailyOccurrenceEntity(
    @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "app_date") val appDate: String,
    @ColumnInfo(name = "zone_epoch_seq") val zoneEpochSeq: Int,
    @ColumnInfo(name = "display_title_snapshot") val displayTitleSnapshot: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/**
 * Authoritative completion truth: append-only, never updated (架构契约 §4.1).
 * 0 = completed, 1 = undone.
 */
@Entity(
    tableName = "occurrence_completion_event",
    indices = [Index(value = ["occurrence_id", "event_seq"])],
    foreignKeys = [
        ForeignKey(
            entity = DailyOccurrenceEntity::class,
            parentColumns = ["occurrence_id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OccurrenceCompletionEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "event_seq") val eventSeq: Long = 0,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "action") val action: Int,
    @ColumnInfo(name = "occurred_wall_ms") val occurredWallMs: Long,
    @ColumnInfo(name = "app_date") val appDate: String,
    @ColumnInfo(name = "zone_epoch_seq") val zoneEpochSeq: Int,
)

/**
 * Derived cache of the event log, written in the same transaction as the event.
 *
 * `occurrence_id` is simultaneously PK and FK, which makes the 1:1 relation a schema guarantee
 * (架构契约 §4.2 N6). `derived_from_event_high_water` records how much of the log is folded in.
 */
@Entity(
    tableName = "daily_occurrence_view",
    foreignKeys = [
        ForeignKey(
            entity = DailyOccurrenceEntity::class,
            parentColumns = ["occurrence_id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DailyOccurrenceViewEntity(
    @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "is_completed") val isCompleted: Int,
    @ColumnInfo(name = "completed_wall_ms") val completedWallMs: Long?,
    @ColumnInfo(name = "derived_from_event_high_water") val derivedFromEventHighWater: Long,
)

/** Temporary-task completion history: reopening appends an event instead of erasing one (AC-11). */
@Entity(
    tableName = "temporary_completion_event",
    indices = [Index(value = ["task_id", "event_seq"])],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["task_id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TemporaryCompletionEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "event_seq") val eventSeq: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "action") val action: Int,
    @ColumnInfo(name = "occurred_wall_ms") val occurredWallMs: Long,
    @ColumnInfo(name = "app_date") val appDate: String,
    @ColumnInfo(name = "title_snapshot") val titleSnapshot: String,
)

/** One timing session. At most one may be active; `active_session_slot` enforces that in SQL. */
@Entity(
    tableName = "focus_session",
    indices = [Index(value = ["task_id", "created_wall_ms"]), Index(value = ["state"])],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["task_id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FocusSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    /** 0 = count up, 1 = countdown. */
    @ColumnInfo(name = "mode") val mode: Int,
    @ColumnInfo(name = "target_seconds") val targetSeconds: Long?,
    /** 0 = running, 1 = paused, 2 = finished, 3 = recovery pending. */
    @ColumnInfo(name = "state") val state: Int,
    @ColumnInfo(name = "created_wall_ms") val createdWallMs: Long,
    @ColumnInfo(name = "finished_wall_ms") val finishedWallMs: Long?,
    @ColumnInfo(name = "source") val source: String,
)

/** A running span. Paused time has no row here, which is why pause cannot accumulate (AC-05). */
@Entity(
    tableName = "session_segment",
    indices = [Index(value = ["session_id", "seg_seq"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = FocusSessionEntity::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SessionSegmentEntity(
    @PrimaryKey @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seg_seq") val segSeq: Int,
    @ColumnInfo(name = "start_wall_ms") val startWallMs: Long,
    @ColumnInfo(name = "end_wall_ms") val endWallMs: Long?,
    @ColumnInfo(name = "start_elapsed_ms") val startElapsedMs: Long,
    @ColumnInfo(name = "end_elapsed_ms") val endElapsedMs: Long?,
    @ColumnInfo(name = "start_boot_tag") val startBootTag: String,
    @ColumnInfo(name = "end_boot_tag") val endBootTag: String?,
    @ColumnInfo(name = "title_snapshot") val titleSnapshot: String,
    @ColumnInfo(name = "zone_epoch_seq") val zoneEpochSeq: Int,
    @ColumnInfo(name = "derived") val derived: Int,
)

/**
 * Single-row slot: the fixed primary key makes a second active session physically impossible, which
 * is a database-level guarantee rather than a convention (架构契约 §4.2).
 */
@Entity(
    tableName = "active_session_slot",
    foreignKeys = [
        ForeignKey(entity = FocusSessionEntity::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["session_id"], unique = true)],
)
data class ActiveSessionSlotEntity(
    @PrimaryKey @ColumnInfo(name = "slot_id") val slotId: Int = SLOT_ID,
    @ColumnInfo(name = "session_id") val sessionId: String,
) {
    companion object {
        const val SLOT_ID = 1
    }
}
