package app.arttodo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreSafetyTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var manager: BackupManager
    private var now = 1_780_000_000_000L

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.build(context, "backup-restore-safety.db")
        manager = BackupManager(context, database, wallClock = { now })
        database.openHelper.writableDatabase
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) " +
                "VALUES (1, 'Asia/Shanghai', 1, 'initial', 1)",
        )
        database.taskDao().insert(
            TaskEntity("task:live", 0, "现有的私人标题", "私密备注", 1024, 1, null, 25, null),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("backup-restore-safety.db")
    }

    @Test
    fun a_successful_restore_replaces_all_task_data_and_creates_protection_point(): Unit = runBlocking {
        val archive = manager.export(password = null)
        database.taskDao().insert(TaskEntity("task:new", 1, "备份任务", "", 2048, 2, null, 25, null))

        manager.restore(archive, password = null)

        assertThat(database.taskDao().all().map { it.taskId }).containsExactly("task:live")
        assertThat(database.supportDao().backupRecords().filter { it.kind == 1 }).hasSize(1)
    }

    @Test
    fun wrong_password_and_corrupt_archive_leave_live_rows_exactly_unchanged(): Unit = runBlocking {
        val encrypted = manager.export("correct horse battery staple".toCharArray())
        val before = snapshot()

        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(encrypted, "incorrect".toCharArray()) }
        }.code).isEqualTo("AuthenticationFailed")
        assertThat(snapshot()).isEqualTo(before)

        val damaged = encrypted.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() }
        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(damaged, "correct horse battery staple".toCharArray()) }
        }.code).isEqualTo("AuthenticationFailed")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun unsupported_archive_version_and_truncation_leave_live_rows_unchanged(): Unit = runBlocking {
        val archive = manager.export(password = null)
        val before = snapshot()
        val unknown = archive.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(unknown, null) }
        }.code).isEqualTo("UnsupportedVersion")
        assertThat(snapshot()).isEqualTo(before)

        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(archive.copyOf(archive.size - 20), null) }
        }.code).isEqualTo("TruncatedArchive")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun an_encrypted_import_with_wrong_password_leaves_all_live_rows_unchanged(): Unit = runBlocking {
        val encrypted = manager.export("correct horse battery staple".toCharArray())
        val before = snapshot()

        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(encrypted, "incorrect".toCharArray()) }
        }.code).isEqualTo("AuthenticationFailed")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun a_database_with_a_broken_reference_is_rejected_without_touching_live_data(): Unit = runBlocking {
        val archive = manager.export(password = null)
        val before = snapshot()
        val damaged = corruptDatabaseInsideArchive(archive)

        assertThat(assertThrows(BackupException::class.java) {
            runBlocking { manager.restore(damaged, null) }
        }.code).isEqualTo("ReferenceIntegrityFailed")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun restoring_a_legacy_database_migrates_the_file_that_is_used_for_replacement(): Unit = runBlocking {
        val target = File(context.cacheDir, "legacy-candidate.db")
        createVersionOneDatabase(target)
        val legacyArchive = archiveOf(target)
        database.taskDao().insert(TaskEntity("task:discarded", 0, "替换前额外数据", "", 2048, 3, null, 25, null))

        manager.restore(legacyArchive, password = null)

        assertThat(database.taskDao().all().map { it.taskId }).containsExactly("task:live")
        assertThat(database.openHelper.readableDatabase.version).isEqualTo(AppDatabase.SCHEMA_VERSION)
    }

    @Test
    fun a_write_failure_rolls_back_every_live_row_and_removes_the_temporary_guard_point(): Unit = runBlocking {
        val archive = manager.export(password = null)
        database.taskDao().insert(TaskEntity("task:new", 1, "恢复候选", "", 2048, 2, null, 25, null))
        val before = snapshot()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON task BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            assertThat(assertThrows(BackupException::class.java) {
                runBlocking { manager.restore(archive, null) }
            }.code).isEqualTo("RestoreTransactionFailed")
            assertThat(snapshot()).isEqualTo(before)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        }
    }

    @Test
    fun a_failed_restore_preserves_a_previously_created_guard_point(): Unit = runBlocking {
        val archive = manager.export(null)
        val existingGuard = manager.capturePreRestore()
        val before = snapshot()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON task BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )
        try {
            assertThat(assertThrows(BackupException::class.java) {
                runBlocking { manager.restore(archive, null) }
            }.code).isEqualTo("RestoreTransactionFailed")
            assertThat(snapshot()).isEqualTo(before)
            assertThat(File(existingGuard.filePath).isFile).isTrue()
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        }
    }

    @Test
    fun a_daily_point_is_unique_per_day_and_prunes_only_points_beyond_seven(): Unit = runBlocking {
        // The day boundary is injected (架构契约 §8.4): the manager itself never derives a date, so
        // this test states which day it is talking about instead of relying on a UTC calendar.
        val dailyManager = BackupManager(context, database, wallClock = { now }, dayLabelOf = { wallMs ->
            java.time.Instant.ofEpochMilli(wallMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        })
        for (day in 1..8) {
            now = 1_780_000_000_000L + day * 86_400_000L
            assertThat(dailyManager.captureDailyIfNeeded()).isTrue()
            assertThat(dailyManager.captureDailyIfNeeded()).isFalse()
        }
        assertThat(database.supportDao().backupRecords().filter { it.kind == 0 }).hasSize(7)
    }

    private suspend fun snapshot(): List<String> = buildList {
        database.taskDao().all().sortedBy { it.taskId }.forEach { add("task:${it.taskId}:${it.title}:${it.note}") }
        database.supportDao().zoneEpochs().forEach { add("zone:${it.epochSeq}:${it.zoneId}:${it.createdWallMs}") }
        database.supportDao().backupRecords().forEach { add("backup:${it.recordId}:${it.createdWallMs}") }
    }

    private suspend fun createVersionOneDatabase(file: java.io.File) {
        val payload = BackupArchive.decode(manager.export(null), null)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(payload))
        val manifestLength = input.readInt()
        input.skipBytes(manifestLength)
        val dbLength = input.readLong()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(dbLength.toInt()).also(input::readFully))
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { legacy ->
            legacy.execSQL("DROP INDEX index_task_title_revision_task_id_effective_wall_ms")
            legacy.execSQL(
                "CREATE INDEX index_task_title_revision_task_id_effective_wall_ms " +
                    "ON task_title_revision (task_id, effective_wall_ms)",
            )
            legacy.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf("c8bf8d235a9da2755a5322dddad596cf"),
            )
            legacy.version = AppMigrations.BASE_VERSION
        }
    }

    private suspend fun archiveOf(file: java.io.File): ByteArray {
        val databaseBytes = file.readBytes()
        val payload = BackupArchive.decode(manager.export(null), null)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(payload))
        val length = input.readInt()
        val manifest = org.json.JSONObject(ByteArray(length).also(input::readFully).toString(Charsets.UTF_8))
            .put("app_db_version", AppMigrations.BASE_VERSION)
            .put("integrity", org.json.JSONObject().put("algorithm", "sha256").put("value", sha256(databaseBytes)))
        val bundle = java.io.ByteArrayOutputStream().use { bytes ->
            java.io.DataOutputStream(bytes).use { output ->
                val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
                output.writeInt(manifestBytes.size)
                output.write(manifestBytes)
                output.writeLong(databaseBytes.size.toLong())
                output.write(databaseBytes)
            }
            bytes.toByteArray()
        }
        return BackupArchive.encode(bundle, null)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun corruptDatabaseInsideArchive(archive: ByteArray): ByteArray {
        val payload = BackupArchive.decode(archive, null)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(payload))
        val manifestLength = input.readInt()
        val manifest = ByteArray(manifestLength).also(input::readFully)
        val dbLength = input.readLong()
        val dbBytes = ByteArray(dbLength.toInt()).also(input::readFully)
        val malformedFile = context.getDatabasePath("malformed-copy.db").apply { parentFile?.mkdirs() }
        val written = malformedFile.outputStream().use { it.write(dbBytes); it.toString() }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            malformedFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.execSQL(
                "INSERT INTO ledger_entry (kind, ref_id, task_id, app_date, occurred_wall_ms, zone_epoch_seq, " +
                    "delta_seconds, set_total_seconds, created_wall_ms, edited_at_ms, is_deleted, deleted_at_ms) " +
                    "VALUES (1, NULL, 'missing-task', '2026-01-01', 1, 1, 30, NULL, 1, NULL, 0, NULL)",
            )
        }
        val changedDb = malformedFile.readBytes()
        malformedFile.delete()
        return java.io.ByteArrayOutputStream().use { bytes ->
            java.io.DataOutputStream(bytes).use { output ->
                output.writeInt(manifestLength)
                output.write(manifest)
                output.writeLong(changedDb.size.toLong())
                output.write(changedDb)
            }
            BackupArchive.encode(bytes.toByteArray(), null)
        }
    }
}
