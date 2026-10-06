package app.arttodo.data

/**
 * Keys and value conventions of the `app_setting` table (架构契约 §4.2).
 *
 * They live in the data layer because more than one writer touches them: the settings screen writes
 * the reminder flags, and the effects applier writes the zone pointer next to the `zone_epoch` row it
 * mirrors.
 */
object AppSettings {
    /** The fixed application zone pointer; the authoritative value is `zone_epoch`, this is its cache. */
    const val KEY_APP_ZONE = "app_zone_id"

    const val KEY_SOUND = "focus_sound_enabled"
    const val KEY_VIBRATION = "focus_vibration_enabled"
}
