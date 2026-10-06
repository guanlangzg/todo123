package app.arttodo.data

import org.json.JSONObject

/**
 * Validation of an exported backup's structure, before anything is allowed to touch the live
 * database (规格 §9.5 / 架构契约 §8.2).
 *
 * This layer only decides whether a candidate file is *structurally* usable: magic, format version
 * band, required keys and their types/lengths. Cryptographic verification (sha256 or the GCM tag)
 * and the row-level comparison against the manifest belong to the backup layer; this class exists so
 * the data layer never accepts a file it cannot describe.
 *
 * Everything is bounded, so a hostile file cannot make the app allocate without limit:
 * [MAX_MANIFEST_CHARS], [MAX_TABLE_NAME_CHARS], [MAX_TABLES], [MAX_ROW_COUNT], [MAX_FORMAT_VERSION].
 */
object BackupStructure {

    const val MAGIC = "arttodo-backup"
    const val MIN_FORMAT_VERSION = 1
    const val MAX_FORMAT_VERSION = 1

    /** A personal task database is small; a manifest beyond this is not a real export. */
    const val MAX_MANIFEST_CHARS = 262_144
    const val MAX_TABLE_NAME_CHARS = 64
    const val MAX_TABLES = 64
    const val MAX_ROW_COUNT = 10_000_000L

    /** Required top-level keys and the JSON shape each must have, per 规格 §9.5's content list. */
    private val REQUIRED_FIELDS = mapOf(
        "magic" to "string",
        "backup_format_version" to "integral",
        "created_wall_ms" to "integral",
        "app_db_version" to "integral",
        "zone_epoch_history" to "array",
        "tables" to "object",
        "integrity" to "object",
    )

    /** One row-count/摘要 entry, so a restore can be compared against the manifest afterwards. */
    private val REQUIRED_TABLE_FIELDS = setOf("rows", "digest")

    /** `null` when the structure is acceptable, otherwise a stable machine-readable code. */
    fun validate(manifestJson: String): String? {
        if (manifestJson.isEmpty()) return "EmptyManifest"
        if (manifestJson.length > MAX_MANIFEST_CHARS) return "ManifestTooLong"
        val root = runCatching { JSONObject(manifestJson) }.getOrNull()
            ?: return "MalformedManifest"
        for ((field, type) in REQUIRED_FIELDS) {
            if (!root.has(field)) return "MissingField:$field"
            if (!typeMatches(root.get(field), type)) return "WrongType:$field"
        }
        if (root.getString("magic") != MAGIC) return "BadMagic"
        val version = integerOf(root.get("backup_format_version")) ?: return "WrongType:backup_format_version"
        // A *newer* format is refused rather than guessed at; so is a version below the band.
        if (version < MIN_FORMAT_VERSION || version > MAX_FORMAT_VERSION) {
            return "UnsupportedFormatVersion:$version"
        }
        val dbVersion = integerOf(root.get("app_db_version")) ?: return "WrongType:app_db_version"
        if (dbVersion < AppMigrations.BASE_VERSION || dbVersion > AppDatabase.SCHEMA_VERSION) {
            return "UnsupportedDatabaseVersion:$dbVersion"
        }
        if ((integerOf(root.get("created_wall_ms")) ?: 0L) < 0) return "InvalidCreatedAt"

        val tables = root.getJSONObject("tables")
        if (tables.length() == 0) return "NoTables"
        if (tables.length() > MAX_TABLES) return "TooManyTables"
        for (table in tables.keys()) {
            if (table.isEmpty() || table.length > MAX_TABLE_NAME_CHARS) return "BadTableName:$table"
            val entry = tables.optJSONObject(table) ?: return "BadTableEntry:$table"
            for (field in REQUIRED_TABLE_FIELDS) {
                if (!entry.has(field)) return "MissingField:$table.$field"
            }
            val count = integerOf(entry.opt("rows")) ?: return "WrongType:$table.rows"
            if (count < 0 || count > MAX_ROW_COUNT) return "RowCountOutOfRange:$table"
            val digest = entry.opt("digest")
            if (typeOf(digest) != "string") return "WrongType:$table.digest"
            val digestText = digest as String
            if (digestText.isEmpty() || digestText.length > 128) return "BadDigestLength:$table"
        }
        if (typeOf(root.getJSONArray("zone_epoch_history")) != "array") return "WrongType:zone_epoch_history"
        return null
    }

    /**
     * Whether a parsed value has the shape a field requires.
     *
     * `integral` accepts any whole number: `org.json` decides between `Integer`, `Long` and
     * `BigInteger` from the literal's magnitude, so pinning one of those would reject a valid file
     * that happened to carry a large instant with a different Java type. A fractional number is
     * refused, because every integral field here is a count or an epoch instant.
     */
    private fun typeMatches(value: Any?, required: String): Boolean =
        if (required == "integral") integerOf(value) != null else typeOf(value) == required

    private fun integerOf(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is java.math.BigInteger ->
            if (value.bitLength() <= 63) value.toLong() else null
        else -> null
    }

    /** JSON type of a value as produced by `JSONObject`, which is what a manifest is written with. */
    private fun typeOf(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> "object"
        is org.json.JSONArray -> "array"
        is String -> "string"
        is Boolean -> "bool"
        is java.math.BigInteger, is Long -> "long"
        is Int -> "int"
        else -> "other"
    }
}
