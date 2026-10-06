package app.arttodo.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Creates authenticated, self-verifying snapshots and transactionally replaces live Room rows. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /**
     * The **application natural day** of an instant, decided by the domain core (架构契约 §2 / §8.4).
     *
     * It is injected rather than computed here: Kotlin may not derive a date, and a UTC calendar date
     * is not this app's day — a day boundary drawn in UTC would put two "daily" points inside one
     * local day and none in another. `null` means the day could not be resolved, which must stop the
     * capture rather than key a point with a guessed day.
     */
    private val dayLabelOf: suspend (Long) -> String? = { null },
) {
    private val support = database.supportDao()

    suspend fun export(password: CharArray?): ByteArray = BackupArchive.encode(buildPayload(), password)

    suspend fun exportTo(output: java.io.OutputStream, password: CharArray?) {
        output.use { it.write(export(password)) }
    }

    /**
     * One immutable daily point per application day; a second change on the same day reuses it.
     *
     * A failure never claims a point exists: the caller sees an exception, and no record row is left
     * behind (the file and the row are removed together).
     */
    suspend fun captureDailyIfNeeded(): Boolean {
        val appDate = dayLabelOf(wallClock()) ?: throw BackupException("RecoveryPointDayUnavailable")
        val id = "daily:$appDate"
        if (support.backupRecords().any { it.recordId == id }) return false
        capture(id, kind = 0)
        pruneDailyPoints()
        return true
    }

    /** A restore guard is independent of the seven rolling daily points. */
    suspend fun capturePreRestore(): BackupRecordEntity = capture(
        recordId = "pre:${UUID.randomUUID()}",
        kind = 1,
    )

    suspend fun restoreRecoveryPoint(record: BackupRecordEntity) {
        if (record.kind !in 0..1) throw BackupException("InvalidRecoveryPoint")
        val stored = support.backupRecords().firstOrNull { it.recordId == record.recordId }
            ?: throw BackupException("RecoveryPointNotFound")
        if (stored.filePath != record.filePath || stored.sha256 != record.sha256 || stored.sizeBytes != record.sizeBytes || stored.kind != record.kind) {
            throw BackupException("RecoveryPointMismatch")
        }
        val file = File(stored.filePath)
        if (!file.isFile || file.length() != stored.sizeBytes || sha256(file.readBytes()) != stored.sha256) {
            throw BackupException("RecoveryPointIntegrityFailed")
        }
        restore(file.readBytes(), password = null)
    }

    suspend fun removeRecoveryPoint(record: BackupRecordEntity) {
        val stored = support.backupRecords().firstOrNull { it.recordId == record.recordId }
            ?: throw BackupException("RecoveryPointNotFound")
        if (stored.filePath != record.filePath || stored.sha256 != record.sha256 || stored.kind != record.kind) {
            throw BackupException("RecoveryPointMismatch")
        }
        File(stored.filePath).delete()
        support.deleteBackupRecord(stored.recordId)
    }

    suspend fun recoveryPoints(): List<BackupRecordEntity> = support.backupRecords()

    suspend fun restore(archive: ByteArray, password: CharArray?) {
        val candidateBytes = BackupArchive.decode(archive, password)
        val unpacked = unpack(candidateBytes)
        val importDir = File(context.cacheDir, "import").apply { mkdirs() }
        val staging = File(importDir, "restore-${UUID.randomUUID()}.db")
        if (!importDir.isDirectory || importDir.canonicalPath != File(context.cacheDir, "import").canonicalPath) {
            throw BackupException("ImportStagingUnavailable")
        }
        var protection: BackupRecordEntity? = null
        try {
            staging.writeBytes(unpacked.databaseBytes)
            val manifestError = BackupStructure.validate(unpacked.manifest.toString())
            if (manifestError != null) throw BackupException(manifestError)
            val verified = inspect(staging)
            verifyReferences(staging)
            verifyManifest(unpacked.manifest, verified)
            migrateStaging(staging)
            val migrated = inspect(staging)
            if (migrated.version != AppDatabase.SCHEMA_VERSION) throw BackupException("UnsupportedDatabaseVersion")
            verifyReferences(staging)
            verifyMigratedRows(unpacked.manifest, migrated)

            protection = capturePreRestore()
            replaceRowsFrom(staging)
        } catch (error: BackupException) {
            protection?.let { removeProtection(it) }
            throw error
        } catch (error: Exception) {
            protection?.let { removeProtection(it) }
            throw BackupException("RestoreFailed", error)
        } finally {
            staging.delete()
            File(staging.path + "-wal").delete()
            File(staging.path + "-shm").delete()
        }
    }

    private suspend fun capture(recordId: String, kind: Int): BackupRecordEntity {
        val dir = File(context.filesDir, "recovery-points").apply { mkdirs() }
        val fileToken = UUID.randomUUID().toString()
        val target = File(dir, "$fileToken.atb")
        val temporary = File(dir, "$fileToken.tmp")
        try {
            val payload = buildPayload()
            val archive = BackupArchive.encode(payload, password = null)
            temporary.outputStream().use { it.write(archive) }
            if (!temporary.renameTo(target)) throw BackupException("RecoveryPointWriteFailed")
            val manifest = unpack(payload).manifest.toString()
            val entity = BackupRecordEntity(
                recordId = recordId,
                kind = kind,
                createdWallMs = wallClock(),
                filePath = target.absolutePath,
                sizeBytes = target.length(),
                sha256 = sha256(target.readBytes()),
                manifestJson = manifest,
            )
            support.insertBackupRecord(entity)
            return entity
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            if (error is BackupException) throw error
            throw BackupException("RecoveryPointWriteFailed", error)
        }
    }

    private suspend fun removeProtection(record: BackupRecordEntity) {
        support.deleteBackupRecord(record.recordId)
        File(record.filePath).delete()
    }

    private suspend fun pruneDailyPoints() {
        val daily = support.backupRecords().filter { it.kind == 0 }.sortedByDescending { it.createdWallMs }
        for (expired in daily.drop(7)) {
            support.deleteBackupRecord(expired.recordId)
            File(expired.filePath).delete()
        }
    }

    private suspend fun buildPayload(): ByteArray {
        val live = database.openHelper.writableDatabase
        checkpoint(live)
        val source = File(requireNotNull(live.path))
        if (!source.isFile) throw BackupException("DatabaseUnavailable")
        val copy = File(context.cacheDir, "export-${UUID.randomUUID()}.db")
        try {
            source.copyTo(copy, overwrite = true)
            val facts = inspect(copy)
            verifyReferences(copy)
            val manifest = createManifest(facts)
            val manifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)
            if (manifestBytes.size > BackupStructure.MAX_MANIFEST_CHARS) throw BackupException("ManifestTooLong")
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(manifestBytes.size)
                    data.write(manifestBytes)
                    data.writeLong(copy.length())
                    copy.inputStream().use { input -> input.copyTo(data) }
                }
                bytes.toByteArray()
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException("ExportFailed", error)
        } finally {
            copy.delete()
        }
    }

    private fun checkpoint(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) throw BackupException("DatabaseBusy")
        }
    }

    private fun inspect(file: File): DatabaseFacts = openReadOnly(file).use { db ->
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst()) "" else cursor.getString(0)
        }
        if (integrity != "ok") throw BackupException("DatabaseIntegrityFailed")
        val version = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
        val tables = JSONObject()
        TABLES.forEach { table ->
            val details = tableFacts(db, table)
            tables.put(table, JSONObject().put("rows", details.first).put("digest", details.second))
        }
        val zoneHistory = JSONArray()
        db.rawQuery("SELECT epoch_seq, zone_id FROM zone_epoch ORDER BY epoch_seq", null).use { cursor ->
            while (cursor.moveToNext()) {
                zoneHistory.put(JSONObject().put("epoch_seq", cursor.getInt(0)).put("zone_id", cursor.getString(1)))
            }
        }
        DatabaseFacts(version, tables, zoneHistory, sha256(file.readBytes()))
    }

    private fun createManifest(facts: DatabaseFacts): JSONObject = JSONObject()
        .put("magic", BackupStructure.MAGIC)
        .put("backup_format_version", BackupStructure.MAX_FORMAT_VERSION)
        .put("created_wall_ms", wallClock())
        .put("app_db_version", facts.version)
        .put("zone_epoch_history", facts.zoneHistory)
        .put("tables", facts.tables)
        .put("integrity", JSONObject().put("algorithm", "sha256").put("value", facts.databaseDigest))

    private fun verifyManifest(manifest: JSONObject, facts: DatabaseFacts) {
        if (facts.version != manifest.getInt("app_db_version")) throw BackupException("DatabaseVersionMismatch")
        if (facts.databaseDigest != manifest.getJSONObject("integrity").getString("value")) {
            throw BackupException("DatabaseDigestMismatch")
        }
        val expected = manifest.getJSONObject("tables")
        for (table in TABLES) {
            val saved = expected.optJSONObject(table) ?: throw BackupException("MissingTable:$table")
            val actual = facts.tables.getJSONObject(table)
            if (saved.getLong("rows") != actual.getLong("rows") || saved.getString("digest") != actual.getString("digest")) {
                throw BackupException("TableDigestMismatch:$table")
            }
        }
    }

    private fun verifyReferences(file: File) = openReadOnly(file).use { db ->
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            if (cursor.moveToFirst()) throw BackupException("ReferenceIntegrityFailed")
        }
    }

    private fun verifyMigratedRows(manifest: JSONObject, migrated: DatabaseFacts) {
        val expected = manifest.getJSONObject("tables")
        for (table in TABLES) {
            val saved = expected.optJSONObject(table) ?: throw BackupException("MissingTable:$table")
            val actual = migrated.tables.getJSONObject(table)
            val beforeRows = saved.getLong("rows")
            val afterRows = actual.getLong("rows")
            if (table == "task_title_revision") {
                if (afterRows > beforeRows) throw BackupException("MigrationRowCountMismatch:$table")
            } else if (beforeRows != afterRows) {
                throw BackupException("MigrationRowCountMismatch:$table")
            }
        }
    }

    private fun migrateStaging(databaseFile: File) {
        val roomFile = context.getDatabasePath("restore-${UUID.randomUUID()}.db").apply { parentFile?.mkdirs() }
        try {
            databaseFile.copyTo(roomFile, overwrite = true)
            val stagingDatabase = AppDatabase.build(context, roomFile.name)
            try {
                val db = stagingDatabase.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    if (cursor.moveToFirst() && cursor.getInt(0) != 0) throw BackupException("DatabaseBusy")
                }
            } finally {
                stagingDatabase.close()
            }
            if (!roomFile.isFile) throw BackupException("StagingMigrationFailed")
            roomFile.copyTo(databaseFile, overwrite = true)
        } finally {
            File(roomFile.path + "-wal").delete()
            File(roomFile.path + "-shm").delete()
            roomFile.delete()
        }
    }

    private suspend fun replaceRowsFrom(staging: File) {
        val live = database.openHelper.writableDatabase
        checkpoint(live)
        attachDatabase(live, staging.absolutePath)
        try {
            verifyAttachedSchema(live)
            database.withTransaction {
                for (table in DELETE_ORDER) live.execSQL("DELETE FROM main.${quote(table)}")
                for (table in TABLES) {
                    live.execSQL("INSERT INTO main.${quote(table)} SELECT * FROM restore_source.${quote(table)}")
                }
                val check = live.query("PRAGMA foreign_key_check")
                check.use { if (it.moveToFirst()) throw BackupException("ReferenceIntegrityFailed") }
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException("RestoreTransactionFailed", error)
        } finally {
            runCatching { detachDatabase(live) }
        }
    }

    private fun attachDatabase(db: SupportSQLiteDatabase, path: String) {
        val escapedPath = path.replace("'", "''")
        db.execSQL("ATTACH DATABASE '$escapedPath' AS restore_source")
    }

    private fun detachDatabase(db: SupportSQLiteDatabase) {
        db.execSQL("DETACH DATABASE restore_source")
    }

    private fun verifyAttachedSchema(db: SupportSQLiteDatabase) {
        for (table in TABLES) {
            val liveColumns = columns(db.query("PRAGMA main.table_info(${quote(table)})"))
            val sourceColumns = columns(db.query("PRAGMA restore_source.table_info(${quote(table)})"))
            if (liveColumns.isEmpty() || liveColumns != sourceColumns) throw BackupException("SchemaMismatch:$table")
        }
    }

    private fun columns(cursor: Cursor): List<String> = cursor.use {
        buildList { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name"))) }
    }

    private fun tableFacts(db: SQLiteDatabase, table: String): Pair<Long, String> {
        val columns = db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")) to cursor.getInt(cursor.getColumnIndexOrThrow("pk")))
            }
        }
        if (columns.isEmpty()) throw BackupException("MissingTable:$table")
        val order = columns.filter { it.second > 0 }.sortedBy { it.second }.joinToString(",") { quote(it.first) }
            .ifBlank { quote(columns.first().first) }
        val digest = MessageDigest.getInstance("SHA-256")
        var rows = 0L
        db.rawQuery("SELECT * FROM ${quote(table)} ORDER BY $order", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows++
                updateRowDigest(digest, cursor)
            }
        }
        return rows to digest.digest().toHex()
    }

    private fun updateRowDigest(digest: MessageDigest, cursor: Cursor) {
        for (column in 0 until cursor.columnCount) {
            digest.update(cursor.getType(column).toByte())
            when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> Unit
                Cursor.FIELD_TYPE_INTEGER -> digest.update(cursor.getLong(column).toString().toByteArray())
                Cursor.FIELD_TYPE_FLOAT -> digest.update(cursor.getDouble(column).toString().toByteArray())
                Cursor.FIELD_TYPE_STRING -> digest.update(cursor.getString(column).toByteArray(StandardCharsets.UTF_8))
                Cursor.FIELD_TYPE_BLOB -> digest.update(cursor.getBlob(column))
            }
            digest.update(0)
        }
        digest.update(0xff.toByte())
    }

    private fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    private fun unpack(payload: ByteArray): Bundle {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val manifestLength = input.readInt()
                if (manifestLength !in 1..BackupStructure.MAX_MANIFEST_CHARS) throw BackupException("MalformedBundle")
                val manifestBytes = ByteArray(manifestLength)
                input.readFully(manifestBytes)
                val dbLength = input.readLong()
                if (dbLength < 1 || dbLength > BackupArchive.MAX_ARCHIVE_BYTES || dbLength != input.available().toLong()) {
                    throw BackupException("MalformedBundle")
                }
                val manifest = JSONObject(String(manifestBytes, StandardCharsets.UTF_8))
                val databaseBytes = ByteArray(dbLength.toInt())
                input.readFully(databaseBytes)
                return Bundle(manifest, databaseBytes)
            }
        } catch (error: BackupException) {
            throw error
        } catch (_: Exception) {
            throw BackupException("MalformedBundle")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun quote(identifier: String): String = "`$identifier`"

    private data class Bundle(val manifest: JSONObject, val databaseBytes: ByteArray)
    private data class DatabaseFacts(
        val version: Int,
        val tables: JSONObject,
        val zoneHistory: JSONArray,
        val databaseDigest: String,
    )

    private companion object {
        val TABLES = listOf(
            "task", "task_title_revision", "daily_occurrence", "occurrence_completion_event",
            "daily_occurrence_view", "temporary_completion_event", "focus_session", "session_segment",
            "active_session_slot", "session_heartbeat", "ledger_entry", "ledger_entry_audit",
            "zone_epoch", "command_log", "art_asset", "app_setting",
        )
        val DELETE_ORDER = listOf(
            "ledger_entry_audit", "command_log", "ledger_entry", "occurrence_completion_event",
            "daily_occurrence_view", "temporary_completion_event", "session_segment", "active_session_slot",
            "session_heartbeat", "task_title_revision", "daily_occurrence", "focus_session", "task",
                "zone_epoch", "art_asset", "app_setting",
        )
    }
}
