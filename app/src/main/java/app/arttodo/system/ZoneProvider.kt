package app.arttodo.system

import app.arttodo.data.AppDatabase
import app.arttodo.data.AppSettingEntity
import app.arttodo.data.AppSettings
import java.util.TimeZone

/**
 * The fixed application time zone (AC-15, 架构契约 §2.4).
 *
 * The zone is captured on first install and then **persisted**. A later change to the phone's zone
 * must not move it: the app's dates keep the same meaning, and no zone epoch is written. Only an
 * explicit user action in settings appends a new epoch.
 *
 * **The authority is the `zone_epoch` table, not this class.** `appZoneId()` exists only as a
 * synchronous bootstrap value (the command writer cannot block on a database read inside its critical
 * section), and [ensureInitialised] aligns it with the newest epoch row on every process start. The
 * persisted `app_setting` pointer is written in the same transaction as the epoch row itself, so the
 * cache can only ever be behind by "nothing has been read yet".
 */
class ZoneProvider(private val database: AppDatabase) {

    @Volatile
    private var cachedZoneId: String = TimeZone.getDefault().id

    /**
     * Aligns the pointer with the newest epoch row, or captures the device zone on a fresh install.
     *
     * Call before any command is dispatched. Idempotent.
     */
    suspend fun ensureInitialised() {
        val recorded = database.supportDao().zoneEpochs().lastOrNull()?.zoneId
        val stored = database.supportDao().setting(AppSettings.KEY_APP_ZONE)
        when {
            // A recorded epoch outranks both the pointer and the device: the zone is what the user
            // chose, and the pointer is only its denormalised copy (架构契约 §2.4).
            recorded != null -> {
                cachedZoneId = recorded
                if (stored != recorded) writePointer(recorded)
            }

            stored != null -> cachedZoneId = stored

            else -> {
                val now = TimeZone.getDefault().id
                writePointer(now)
                cachedZoneId = now
            }
        }
    }

    /** The application zone as last observed; the epoch table remains the authority. */
    fun appZoneId(): String = cachedZoneId

    /** The device zone right now, offered in settings so the user can align the app zone. */
    fun deviceZoneId(): String = TimeZone.getDefault().id

    /**
     * Memory-only update for a zone the command writer just read back from the core.
     *
     * The persisted pointer needs no separate write: the applier stores it in the same transaction as
     * the epoch row it belongs to.
     */
    fun observeZone(zoneId: String) {
        if (zoneId.isNotBlank()) cachedZoneId = zoneId
    }

    private suspend fun writePointer(zoneId: String) {
        database.supportDao().upsertSetting(AppSettingEntity(key = AppSettings.KEY_APP_ZONE, value = zoneId))
    }

    companion object {
        /** Kept for call sites that only need the key; the definition lives with the schema. */
        const val KEY_APP_ZONE: String = AppSettings.KEY_APP_ZONE
    }
}
