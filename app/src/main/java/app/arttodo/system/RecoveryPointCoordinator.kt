package app.arttodo.system

import app.arttodo.data.AppDatabase
import app.arttodo.data.AppSettingEntity
import app.arttodo.data.BackupException
import app.arttodo.data.BackupRecordEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The boundary every data change of the day crosses.
 *
 * Two responsibilities, and both belong on the *same* serial path as the command writer
 * (架构契约 §5.2 rule 1):
 *
 * 1. **The daily recovery point.** The first change of an application day — of any kind, including a
 *    settings toggle, because `app_setting` is part of a backup — captures at most one immutable
 *    point before it is written (架构契约 §8.4).
 * 2. **Whole-database replacement.** A restore takes the file away from under the writer, so it must
 *    not run while a command holds it: otherwise a command that read the previous timeline commits
 *    its effects into the restored file.
 *
 * [exclusive] is the single-writer gate: the default runs the block inline, which is what unit tests
 * that do not exercise concurrency want; the application container passes the command writer's own
 * critical section.
 */
class RecoveryPointCoordinator(
    private val database: AppDatabase,
    private val manager: app.arttodo.data.BackupManager,
    private val exclusive: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {
    suspend fun beforeMutation() = withContext(Dispatchers.IO) {
        try {
            manager.captureDailyIfNeeded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A backup failure keeps its own code ("RecoveryPointDayUnavailable", "DatabaseBusy",
            // "RecoveryPointWriteFailed"): the UI sentence is generic, the diagnosis must not be.
            throw if (error is BackupException) error else BackupException("RecoveryPointUnavailable", error)
        }
    }

    suspend fun list(): List<BackupRecordEntity> = manager.recoveryPoints()

    /** Restores one on-device recovery point; serialised with every command. */
    suspend fun restore(record: BackupRecordEntity) = exclusive { manager.restoreRecoveryPoint(record) }

    /** Restores an imported archive; serialised with every command. */
    suspend fun restoreArchive(archive: ByteArray, password: CharArray?) =
        exclusive { manager.restore(archive, password) }

    suspend fun delete(record: BackupRecordEntity) = exclusive { manager.removeRecoveryPoint(record) }

    /**
     * One settings change.
     *
     * It is a data change like any other, so it goes through the writer gate and behind the daily
     * boundary: bypassing either would let the day's first change land with no snapshot to return to,
     * or race the checkpoint a capture performs on the shared connection. When the capture fails the
     * setting is **not** written and the failure propagates — the UI must not report success.
     */
    suspend fun writeSetting(key: String, value: String) = exclusive {
        beforeMutation()
        database.supportDao().upsertSetting(AppSettingEntity(key = key, value = value))
    }
}
