package app.arttodo.data

import org.json.JSONObject
import app.arttodo.data.FieldLimits.noteViolation
import app.arttodo.data.FieldLimits.titleViolation
import app.arttodo.data.FieldLimits.countdownMinutesViolation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structure validation for an exported backup, and the backup record table that indexes local
 * recovery points (规格 §9.5 / 架构契约 §8.2, §8.4).
 *
 * Only structure is decided here; the crypto and the row-by-row comparison are other layers'. What
 * this test pins is that a manifest which is truncated, of an unknown future version, missing a
 * field, or over-long is refused with a stable code *before* anything touches the live database.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `BackupStructureTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupStructureTest {

    @Test
    fun a_well_formed_manifest_is_accepted() {
        assertThat(BackupStructure.validate(validManifest())).isNull()
    }

    @Test
    fun the_accepted_boundaries_are_exact() {
        assertThat(BackupStructure.validate(manifest(version = BackupStructure.MIN_FORMAT_VERSION))).isNull()
        assertThat(BackupStructure.validate(manifest(version = BackupStructure.MAX_FORMAT_VERSION))).isNull()
        assertThat(BackupStructure.validate(manifest(dbVersion = AppMigrations.BASE_VERSION))).isNull()
        assertThat(BackupStructure.validate(manifest(dbVersion = AppDatabase.SCHEMA_VERSION))).isNull()
    }

    @Test
    fun malformed_and_empty_manifests_are_refused() {
        assertThat(BackupStructure.validate("")).isEqualTo("EmptyManifest")
        assertThat(BackupStructure.validate("{not json")).isEqualTo("MalformedManifest")
        assertThat(BackupStructure.validate("[]")).isEqualTo("MalformedManifest")
        assertThat(BackupStructure.validate("x".repeat(BackupStructure.MAX_MANIFEST_CHARS + 1)))
            .isEqualTo("ManifestTooLong")
    }

    @Test
    fun the_wrong_magic_is_refused() {
        assertThat(BackupStructure.validate(manifest(magic = "other-app"))).isEqualTo("BadMagic")
    }

    /** A file from a *newer* app version is refused rather than guessed at (规格 §9.5). */
    @Test
    fun an_unknown_future_format_or_database_version_is_refused() {
        assertThat(BackupStructure.validate(manifest(version = BackupStructure.MAX_FORMAT_VERSION + 1)))
            .isEqualTo("UnsupportedFormatVersion:${BackupStructure.MAX_FORMAT_VERSION + 1}")
        assertThat(BackupStructure.validate(manifest(version = 0))).isEqualTo("UnsupportedFormatVersion:0")
        assertThat(BackupStructure.validate(manifest(dbVersion = AppDatabase.SCHEMA_VERSION + 1)))
            .isEqualTo("UnsupportedDatabaseVersion:${AppDatabase.SCHEMA_VERSION + 1}")
        assertThat(BackupStructure.validate(manifest(dbVersion = 0)))
            .isEqualTo("UnsupportedDatabaseVersion:0")
    }

    @Test
    fun a_missing_required_field_is_refused_by_name() {
        for (field in listOf("magic", "backup_format_version", "created_wall_ms", "app_db_version", "tables")) {
            val broken = manifest().let { org.json.JSONObject(it).apply { remove(field) }.toString() }
            assertThat(BackupStructure.validate(broken)).isEqualTo("MissingField:$field")
        }
    }

    @Test
    fun wrong_field_types_are_refused() {
        val broken = org.json.JSONObject(manifest()).apply { put("created_wall_ms", "yesterday") }.toString()
        assertThat(BackupStructure.validate(broken)).isEqualTo("WrongType:created_wall_ms")
        val brokenTables = org.json.JSONObject(manifest()).apply { put("tables", listOf(1, 2)) }.toString()
        assertThat(BackupStructure.validate(brokenTables)).isEqualTo("WrongType:tables")
    }

    @Test
    fun an_empty_or_absurd_table_list_is_refused() {
        assertThat(BackupStructure.validate(manifest(tables = "{}"))).isEqualTo("NoTables")
        val many = (0..BackupStructure.MAX_TABLES).joinToString(",") { "\"t$it\":{\"rows\":0,\"digest\":\"d\"}" }
        assertThat(BackupStructure.validate(manifest(tables = "{$many}"))).isEqualTo("TooManyTables")
    }

    @Test
    fun per_table_entries_are_checked() {
        assertThat(BackupStructure.validate(manifest(tables = "{\"task\":{\"rows\":3}}")))
            .isEqualTo("MissingField:task.digest")
        assertThat(BackupStructure.validate(manifest(tables = "{\"task\":{\"rows\":\"3\",\"digest\":\"d\"}}")))
            .isEqualTo("WrongType:task.rows")
        assertThat(BackupStructure.validate(manifest(tables = "{\"task\":{\"rows\":-1,\"digest\":\"d\"}}")))
            .isEqualTo("RowCountOutOfRange:task")
        assertThat(
            BackupStructure.validate(
                manifest(tables = "{\"task\":{\"rows\":${BackupStructure.MAX_ROW_COUNT + 1},\"digest\":\"d\"}}"),
            ),
        ).isEqualTo("RowCountOutOfRange:task")
        assertThat(BackupStructure.validate(manifest(tables = "{\"task\":{\"rows\":1,\"digest\":\"\"}}")))
            .isEqualTo("BadDigestLength:task")
        assertThat(BackupStructure.validate(manifest(tables = "{\"\":{\"rows\":1,\"digest\":\"d\"}}")))
            .isEqualTo("BadTableName:")
        val longName = "t".repeat(BackupStructure.MAX_TABLE_NAME_CHARS + 1)
        assertThat(BackupStructure.validate(manifest(tables = "{\"$longName\":{\"rows\":1,\"digest\":\"d\"}}")))
            .isEqualTo("BadTableName:$longName")
    }

    /** Local recovery points are recorded with their sizes and digests (架构契约 §8.4). */
    @Test
    fun local_recovery_points_are_recorded_with_size_and_digest() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val name = "backup-test.db"
        val database = AppDatabase.build(context, name)
        try {
            val record = BackupRecordEntity(
                recordId = "backup:1",
                kind = 1,
                createdWallMs = 1_772_000_000_000L,
                filePath = "/data/user/0/app.arttodo/cache/backup/1.zip",
                sizeBytes = 4096,
                sha256 = "0".repeat(64),
                manifestJson = validManifest(),
            )
            kotlinx.coroutines.runBlocking {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO backup_record (record_id, kind, created_wall_ms, file_path, size_bytes, " +
                        "sha256, manifest_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        record.recordId,
                        record.kind,
                        record.createdWallMs,
                        record.filePath,
                        record.sizeBytes,
                        record.sha256,
                        record.manifestJson,
                    ),
                )
                assertThat(database.supportDao().backupRecordCount()).isEqualTo(1)
                val stored = database.supportDao().backupRecords().single()
                assertThat(stored.sizeBytes).isEqualTo(4096)
                assertThat(stored.sha256).hasLength(64)
                assertThat(BackupStructure.validate(stored.manifestJson)).isNull()
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * The limits enforced at the write boundary also hold for data arriving from a backup: the
     * applier refuses an over-long title regardless of where it came from.
     */
    @Test
    fun a_restored_row_still_passes_the_write_boundary() {
        val violation = FieldLimits.titleViolation("标".repeat(FieldLimits.MAX_TITLE_CHARS + 1))
        assertThat(violation).isEqualTo("TitleTooLong")
        assertThat(FieldLimits.noteViolation("备".repeat(FieldLimits.MAX_NOTE_CHARS + 1)))
            .isEqualTo("NoteTooLong")
        assertThat(FieldLimits.countdownMinutesViolation(-1)).isEqualTo("CountdownMinutesOutOfRange")
        assertThat(titleViolation("合法标题")).isNull()
        assertThat(noteViolation("合法备注")).isNull()
        assertThat(countdownMinutesViolation(25)).isNull()
    }

    private fun validManifest(): String = manifest()

    private fun manifest(
        magic: String = BackupStructure.MAGIC,
        version: Int = BackupStructure.MAX_FORMAT_VERSION,
        dbVersion: Int = AppDatabase.SCHEMA_VERSION,
        tables: String = "{\"task\":{\"rows\":1,\"digest\":\"${"a".repeat(64)}\"}}",
    ): String = """
        {
          "magic": "$magic",
          "backup_format_version": $version,
          "created_wall_ms": 1772000000000,
          "app_db_version": $dbVersion,
          "zone_epoch_history": [{"epoch_seq": 1, "zone_id": "Asia/Shanghai"}],
          "tables": $tables,
          "integrity": {"algorithm": "sha256", "value": "${"b".repeat(64)}"}
        }
    """.trimIndent()
}
