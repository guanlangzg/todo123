package app.arttodo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration coverage for the room schema (规格 §9.5 "旧版升级提供显式迁移测试样本").
 *
 * The version-1 database used here is **built from the committed schema artifact**
 * `app/schemas/app.arttodo.data.AppDatabase/1.json` rather than from a hand-copied `CREATE TABLE`
 * list, so the test cannot drift from the frozen DDL it is migrating. The upgraded file is then
 * opened through the production [AppDatabase.build], which makes Room itself validate the result
 * against the entity declarations — an incorrect migration fails here rather than at the user.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `DatabaseMigrationTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private val dbFile: File get() = context.getDatabasePath(dbName)

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    // ---------------------------------------------------------------------------------------
    // 1. Every version has a step and a committed schema
    // ---------------------------------------------------------------------------------------

    @Test
    fun the_migration_chain_covers_every_version_and_each_schema_is_committed() {
        val versions = AppMigrations.ALL.map { it.endVersion }.sorted()
        assertThat(versions)
            .containsExactlyElementsIn((AppMigrations.BASE_VERSION + 1..AppDatabase.SCHEMA_VERSION).toList())
            .inOrder()
        assertThat(AppMigrations.ALL.map { it.startVersion }.sorted())
            .containsExactlyElementsIn((AppMigrations.BASE_VERSION until AppDatabase.SCHEMA_VERSION).toList())
            .inOrder()

        for (version in AppMigrations.BASE_VERSION..AppDatabase.SCHEMA_VERSION) {
            val file = schemaDir().resolve("$version.json")
            assertThat(file.isFile).isTrue()
            assertThat(file.readText()).contains("\"version\": $version")
        }
    }

    @Test
    fun the_current_schema_declares_the_tables_the_code_uses(): Unit = runBlocking {
        val declared = schemaTableNames(AppDatabase.SCHEMA_VERSION)
        val database = AppDatabase.build(context, "fresh-${System.nanoTime()}.db")
        try {
            val actual = database.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            assertThat(actual).containsAtLeastElementsIn(declared)
            assertThat(declared).containsAtLeast(
                "task",
                "task_title_revision",
                "daily_occurrence",
                "occurrence_completion_event",
                "daily_occurrence_view",
                "temporary_completion_event",
                "focus_session",
                "session_segment",
                "active_session_slot",
                "session_heartbeat",
                "ledger_entry",
                "ledger_entry_audit",
                "zone_epoch",
                "command_log",
                "art_asset",
                "app_setting",
                "backup_record",
            )
        } finally {
            database.close()
            context.deleteDatabase(database.openHelper.databaseName)
        }
    }

    /** Every entity index in the current schema exists in a freshly created file. */
    @Test
    fun every_declared_index_exists_in_a_freshly_created_database() {
        val declared = schemaIndexNames(AppDatabase.SCHEMA_VERSION)
        assertThat(declared).isNotEmpty()
        val name = "index-${System.nanoTime()}.db"
        val database = AppDatabase.build(context, name)
        try {
            val actual = database.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'")
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            assertThat(actual).containsAtLeastElementsIn(declared)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 2. The 1 -> 2 sample: rows survive, and same-instant revisions are repaired
    // ---------------------------------------------------------------------------------------

    /**
     * A version-1 file containing two title revisions for the same instant (legal in schema 1) is
     * upgraded without losing anything except that duplicate, and the resulting index is unique.
     */
    @Test
    fun migrating_v1_to_v2_keeps_every_row_and_repairs_duplicate_title_instants() {
        createVersionOneDatabase()

        // Sanity: the v1 file really does hold two revisions at one instant.
        rawQuery("SELECT COUNT(*) FROM task_title_revision").let { assertThat(it).isEqualTo(2) }
        // Sanity: the v1 index is not unique, which is the defect being migrated away.
        assertThat(indexIsUnique("index_task_title_revision_task_id_effective_wall_ms")).isFalse()

        val database = AppDatabase.build(context, dbName)
        try {
            val titleDao = database.titleRevisionDao()
            val revisions = runBlocking { titleDao.forTask(TASK_ID) }
            assertThat(revisions).hasSize(1)
            // The newest revision for that instant is the one that survives.
            assertThat(revisions.single().title).isEqualTo("第二次改名")

            // Nothing else moved.
            runBlocking {
                assertThat(database.taskDao().count()).isEqualTo(1)
                assertThat(database.taskDao().find(TASK_ID)?.title).isEqualTo("历史保全")
                assertThat(database.occurrenceDao().count()).isEqualTo(1)
                assertThat(database.ledgerDao().count()).isEqualTo(1)
                assertThat(database.sessionDao().count()).isEqualTo(1)
                assertThat(database.sessionDao().segmentCount()).isEqualTo(1)
                assertThat(database.supportDao().zoneEpochCount()).isEqualTo(1)
                assertThat(database.supportDao().commandCount()).isEqualTo(1)
                assertThat(database.supportDao().setting("app_zone_id")).isEqualTo(ZONE)
            }
        } finally {
            database.close()
        }

        // The migration recreated the index as unique.
        assertThat(readUserVersion()).isEqualTo(AppDatabase.SCHEMA_VERSION)
        assertThat(indexIsUnique("index_task_title_revision_task_id_effective_wall_ms")).isTrue()
    }

    /** Writing through the *migrated* file behaves like a fresh one: the unique index really bites. */
    @Test
    fun the_unique_index_created_by_the_migration_is_enforced() {
        createVersionOneDatabase()
        val database = AppDatabase.build(context, dbName)
        try {
            val thrown = runCatching {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO task_title_revision " +
                        "(task_id, title, effective_wall_ms, effective_app_date, zone_epoch_seq) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(TASK_ID, "第三次改名", INSTANT, DATE, 1),
                )
            }.exceptionOrNull()
            assertThat(thrown).isNotNull()
        } finally {
            database.close()
        }
    }

    /**
     * The repair step must be *surgical*: a version-1 file whose revisions are already one per
     * instant — the overwhelmingly common case — loses nothing when the unique index is added.
     *
     * Without this sample, a migration whose `DELETE` was written slightly too broadly (for example
     * grouping by `task_id` alone) would still pass the duplicate-repair test while silently deleting
     * a user's rename history on every real upgrade.
     */
    @Test
    fun migrating_a_v1_file_without_duplicates_keeps_every_distinct_revision() {
        createVersionOneDatabase()
        // A second, distinct instant for the same task: schema 1 allowed it and it must survive.
        rawExec(
            "INSERT INTO task_title_revision (task_id, title, effective_wall_ms, effective_app_date, " +
                "zone_epoch_seq) VALUES (?, ?, ?, ?, 1)",
            arrayOf<Any?>(TASK_ID, "第三次改名", INSTANT + 60_000, DATE),
        )
        // And a second task's revision at the *same* instant, which is not a duplicate of the first.
        rawExec(
            "INSERT INTO task (task_id, kind, title, note, sort_key, created_wall_ms, archived_at_ms, " +
                "last_countdown_minutes, art_asset_id) VALUES (?, 1, ?, '', 2048, ?, NULL, 25, NULL)",
            arrayOf<Any?>("task:legacy-2", "另一个任务", INSTANT),
        )
        rawExec(
            "INSERT INTO task_title_revision (task_id, title, effective_wall_ms, effective_app_date, " +
                "zone_epoch_seq) VALUES (?, ?, ?, ?, 1)",
            arrayOf<Any?>("task:legacy-2", "另一个任务", INSTANT, DATE),
        )
        assertThat(rawQuery("SELECT COUNT(*) FROM task_title_revision")).isEqualTo(4)

        val database = AppDatabase.build(context, dbName)
        try {
            val dao = database.titleRevisionDao()
            runBlocking {
                // Only the same-instant pair for the first task collapsed; the cross-task same-instant
                // row and the later instant are both still there.
                assertThat(dao.forTask(TASK_ID)).hasSize(2)
                assertThat(dao.forTask(TASK_ID).map { it.title })
                    .containsExactly("第二次改名", "第三次改名").inOrder()
                assertThat(dao.forTask("task:legacy-2")).hasSize(1)
                assertThat(dao.count()).isEqualTo(3)
                assertThat(database.taskDao().count()).isEqualTo(2)
            }
        } finally {
            database.close()
        }
        assertThat(readUserVersion()).isEqualTo(AppDatabase.SCHEMA_VERSION)
        assertThat(indexIsUnique("index_task_title_revision_task_id_effective_wall_ms")).isTrue()
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Builds a version-1 database file from the frozen schema JSON. */
    private fun createVersionOneDatabase() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val versionOne = JSONObject(schemaDir().resolve("1.json").readText()).getJSONObject("database")
        assertThat(versionOne.getInt("version")).isEqualTo(AppMigrations.BASE_VERSION)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = versionOne.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("`\${TABLE_NAME}`", "`$table`"))
            }
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                val indices = entity.optJSONArray("indices") ?: continue
                for (position in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(position).getString("createSql")
                            .replace("`\${TABLE_NAME}`", "`$table`"),
                    )
                }
            }
            for (query in versionOne.getJSONArray("setupQueries").let { setup ->
                buildList { for (i in 0 until setup.length()) add(setup.getString(i)) }
            }) {
                db.execSQL(query)
            }
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf<Any?>(versionOne.getString("identityHash")),
            )
            seedVersionOneRows(db)
            db.version = AppMigrations.BASE_VERSION
        }
        assertThat(readUserVersion()).isEqualTo(AppMigrations.BASE_VERSION)
    }

    /** Rows a schema-1 install could hold, including the same-instant revision pair. */
    private fun seedVersionOneRows(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO task (task_id, kind, title, note, sort_key, created_wall_ms, archived_at_ms, " +
                "last_countdown_minutes, art_asset_id) VALUES (?, 1, ?, ?, 1024, ?, NULL, 25, NULL)",
            arrayOf<Any?>(TASK_ID, "历史保全", "备注", INSTANT),
        )
        db.execSQL(
            "INSERT INTO task_title_revision (task_id, title, effective_wall_ms, effective_app_date, " +
                "zone_epoch_seq) VALUES (?, ?, ?, ?, 1)",
            arrayOf<Any?>(TASK_ID, "第一次改名", INSTANT, DATE),
        )
        db.execSQL(
            "INSERT INTO task_title_revision (task_id, title, effective_wall_ms, effective_app_date, " +
                "zone_epoch_seq) VALUES (?, ?, ?, ?, 1)",
            arrayOf<Any?>(TASK_ID, "第二次改名", INSTANT, DATE),
        )
        db.execSQL(
            "INSERT INTO daily_occurrence (occurrence_id, task_id, app_date, zone_epoch_seq, " +
                "display_title_snapshot, created_at_ms) VALUES (?, ?, ?, 1, ?, ?)",
            arrayOf<Any?>("occ:$TASK_ID:$DATE", TASK_ID, DATE, "历史保全", INSTANT),
        )
        db.execSQL(
            "INSERT INTO ledger_entry (kind, ref_id, task_id, app_date, occurred_wall_ms, zone_epoch_seq, " +
                "delta_seconds, set_total_seconds, created_wall_ms, edited_at_ms, is_deleted, deleted_at_ms) " +
                "VALUES (1, NULL, ?, ?, ?, 1, 600, NULL, ?, NULL, 0, NULL)",
            arrayOf<Any?>(TASK_ID, DATE, INSTANT, INSTANT),
        )
        db.execSQL(
            "INSERT INTO focus_session (session_id, task_id, mode, target_seconds, state, created_wall_ms, " +
                "finished_wall_ms, source) VALUES (?, ?, 0, NULL, 2, ?, ?, 'ui')",
            arrayOf<Any?>("session:legacy", TASK_ID, INSTANT, INSTANT),
        )
        db.execSQL(
            "INSERT INTO session_segment (segment_id, session_id, seg_seq, start_wall_ms, end_wall_ms, " +
                "start_elapsed_ms, end_elapsed_ms, start_boot_tag, end_boot_tag, title_snapshot, " +
                "zone_epoch_seq, derived) VALUES (?, ?, 1, ?, ?, 0, NULL, 'boot', NULL, ?, 1, 0)",
            arrayOf<Any?>("session:legacy:1", "session:legacy", INSTANT, INSTANT, "历史保全"),
        )
        db.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) " +
                "VALUES (1, ?, ?, '首次安装', ?)",
            arrayOf<Any?>(ZONE, INSTANT, INSTANT),
        )
        db.execSQL(
            "INSERT INTO command_log (command_id, applied_wall_ms, command_kind, result_digest, " +
                "expected_revision, actual_revision) VALUES ('cmd:legacy', ?, 'CreateTask', 'digest', 1, 1)",
            arrayOf<Any?>(INSTANT),
        )
        db.execSQL("INSERT INTO app_setting (key, value) VALUES ('app_zone_id', ?)", arrayOf<Any?>(ZONE))
    }

    private fun readUserVersion(): Int {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            return db.version
        }
    }

    private fun indexIsUnique(indexName: String): Boolean {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA index_list(task_title_revision)", null).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == indexName) {
                        return cursor.getInt(uniqueColumn) == 1
                    }
                }
            }
        }
        throw AssertionError("index $indexName not found")
    }

    private fun rawQuery(sql: String): Int {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0)
            }
        }
    }

    /** Writes to the version-1 sample file before it is opened by Room. */
    private fun rawExec(sql: String, args: Array<Any?>) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(sql, args)
        }
    }

    private fun schemaDir(): File {
        val candidates = listOf(
            File("schemas/app.arttodo.data.AppDatabase"),
            File("app/schemas/app.arttodo.data.AppDatabase"),
            File(System.getProperty("user.dir"), "schemas/app.arttodo.data.AppDatabase"),
            File(System.getProperty("user.dir"), "app/schemas/app.arttodo.data.AppDatabase"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("exported Room schemas not found; tried ${candidates.joinToString()}")
    }

    private fun schemaTableNames(version: Int): List<String> =
        entityList(version).map { it.getString("tableName") }

    private fun schemaIndexNames(version: Int): List<String> = entityList(version).flatMap { entity ->
        val indices = entity.optJSONArray("indices") ?: return@flatMap emptyList()
        buildList { for (i in 0 until indices.length()) add(indices.getJSONObject(i).getString("name")) }
    }

    private fun entityList(version: Int) =
        JSONObject(schemaDir().resolve("$version.json").readText())
            .getJSONObject("database")
            .getJSONArray("entities")
            .let { entities -> buildList { for (i in 0 until entities.length()) add(entities.getJSONObject(i)) } }

    private companion object {
        const val TASK_ID = "task:legacy"
        const val DATE = "2026-02-25"
        const val INSTANT = 1_772_000_000_000L
        const val ZONE = "Asia/Shanghai"
    }
}
