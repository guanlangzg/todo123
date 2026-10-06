package app.arttodo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Progress anchor for a session (架构契约 §7.2).
 *
 * This is a confirmation boundary, not a liveness signal: it marks how far the *trusted* span of an
 * interrupted session reaches, and it is never used to decide whether a session needs recovery.
 */
@Entity(
    tableName = "session_heartbeat",
    foreignKeys = [
        ForeignKey(entity = FocusSessionEntity::class, parentColumns = ["session_id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SessionHeartbeatEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "wall_ms") val wallMs: Long,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "boot_tag") val bootTag: String,
    /** 0 = periodic (15 s), 1 = written on a state transition. */
    @ColumnInfo(name = "heartbeat_kind") val heartbeatKind: Int,
)

/**
 * Time ledger. Three kinds: 0 automatic slice, 1 manual addition, 2 set-total.
 *
 * Replay order is `(occurred_wall_ms, ledger_seq)` — the trusted instant first, the record number
 * only as a tie-break — because a lazily generated slice can be written after later rows
 * (架构契约 §4.3.1 / N4). `ledger_seq` never changes, so an edit keeps the row's ordering slot;
 * deletion is logical and keeps the slot too.
 */
@Entity(
    tableName = "ledger_entry",
    indices = [
        Index(value = ["task_id", "app_date", "occurred_wall_ms", "ledger_seq"]),
        Index(value = ["kind", "ref_id"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["task_id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "ledger_seq") val ledgerSeq: Long = 0,
    @ColumnInfo(name = "kind") val kind: Int,
    /** Deterministic slice key for kind = 0, which is what makes double booking impossible. */
    @ColumnInfo(name = "ref_id") val refId: String?,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "app_date") val appDate: String,
    @ColumnInfo(name = "occurred_wall_ms") val occurredWallMs: Long,
    @ColumnInfo(name = "zone_epoch_seq") val zoneEpochSeq: Int,
    @ColumnInfo(name = "delta_seconds") val deltaSeconds: Long?,
    @ColumnInfo(name = "set_total_seconds") val setTotalSeconds: Long?,
    /** Audit only: when the row was written, deliberately not part of replay ordering. */
    @ColumnInfo(name = "created_wall_ms") val createdWallMs: Long,
    @ColumnInfo(name = "edited_at_ms") val editedAtMs: Long?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Int,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
)

/**
 * Pre-image of every ledger edit and deletion, so the original values survive a logical delete.
 *
 * Deliberately no foreign key to `ledger_entry`: the referenced row may be logically deleted while
 * the audit must persist (架构契约 §4.2 N6).
 */
@Entity(
    tableName = "ledger_entry_audit",
    indices = [Index(value = ["ledger_seq", "audit_seq"])],
)
data class LedgerEntryAuditEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "audit_seq") val auditSeq: Long = 0,
    @ColumnInfo(name = "ledger_seq") val ledgerSeq: Long,
    @ColumnInfo(name = "prev_delta_seconds") val prevDeltaSeconds: Long?,
    @ColumnInfo(name = "prev_set_total_seconds") val prevSetTotalSeconds: Long?,
    @ColumnInfo(name = "new_delta_seconds") val newDeltaSeconds: Long?,
    @ColumnInfo(name = "new_set_total_seconds") val newSetTotalSeconds: Long?,
    @ColumnInfo(name = "changed_at_ms") val changedAtMs: Long,
    /** 0 = edit, 1 = delete, 2 = restore. */
    @ColumnInfo(name = "change_kind") val changeKind: Int,
)

/** Zone epoch history. A system zone change writes nothing here; only a manual change does (AC-15). */
@Entity(tableName = "zone_epoch")
data class ZoneEpochEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "epoch_seq") val epochSeq: Int = 0,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "effective_wall_ms") val effectiveWallMs: Long,
    @ColumnInfo(name = "impact_summary") val impactSummary: String,
    @ColumnInfo(name = "created_wall_ms") val createdWallMs: Long,
)

/** Idempotency record for one user command and its retry chain. */
@Entity(tableName = "command_log")
data class CommandLogEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "applied_wall_ms") val appliedWallMs: Long,
    @ColumnInfo(name = "command_kind") val commandKind: String,
    @ColumnInfo(name = "result_digest") val resultDigest: String,
    @ColumnInfo(name = "expected_revision") val expectedRevision: Long,
    @ColumnInfo(name = "actual_revision") val actualRevision: Long,
)

/** Art provenance. Every bundled illustration has to be traceable to its licence. */
@Entity(tableName = "art_asset")
data class ArtAssetEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "license") val license: String,
    @ColumnInfo(name = "license_scope") val licenseScope: String,
    @ColumnInfo(name = "attribution") val attribution: String,
    @ColumnInfo(name = "evidence_path") val evidencePath: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "color_space") val colorSpace: String,
    @ColumnInfo(name = "crop_safe_area") val cropSafeArea: String,
)

/** Small key/value store: sound, vibration, fixed zone pointer, backup format version. */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** Local recovery points. Daily points roll over seven days; a PRE_RESTORE point does not. */
@Entity(tableName = "backup_record")
data class BackupRecordEntity(
    @PrimaryKey @ColumnInfo(name = "record_id") val recordId: String,
    /** 0 = daily, 1 = pre-restore. */
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "created_wall_ms") val createdWallMs: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
)
