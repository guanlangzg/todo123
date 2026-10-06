package app.arttodo.data

/**
 * Length and range limits the data layer refuses to persist.
 *
 * The domain core already validates the same numbers (`MAX_TITLE_LEN` / `MAX_NOTE_LEN` /
 * `MIN_COUNTDOWN_MINUTES` / `MAX_COUNTDOWN_MINUTES` in `rust/src/state.rs`); this is the second line
 * of defence required by 规格 §9.3: a value that reaches Room without passing the core — a future
 * caller, a restored backup, a migration — must be rejected at the write boundary rather than
 * stored as a fact the rest of the app cannot render.
 *
 * `DatabaseFieldLimitsTest` asserts these numbers agree with the core's *behaviour*, so the two
 * cannot drift apart silently.
 */
object FieldLimits {
    const val MAX_TITLE_CHARS = 120
    const val MAX_NOTE_CHARS = 2000
    const val MIN_COUNTDOWN_MINUTES = 1
    const val MAX_COUNTDOWN_MINUTES = 1440

    /** `null` when the title is acceptable, otherwise a stable code (a `DomainError` variant name). */
    fun titleViolation(title: String): String? = when {
        title.isBlank() -> "EmptyTitle"
        title.trim().codePointCount() > MAX_TITLE_CHARS -> "TitleTooLong"
        else -> null
    }

    fun noteViolation(note: String): String? =
        if (note.codePointCount() > MAX_NOTE_CHARS) "NoteTooLong" else null

    fun countdownMinutesViolation(minutes: Int): String? =
        if (minutes < MIN_COUNTDOWN_MINUTES || minutes > MAX_COUNTDOWN_MINUTES) {
            "CountdownMinutesOutOfRange"
        } else {
            null
        }

    private fun String.codePointCount(): Int = codePointCount(0, length)
}

/**
 * Thrown when an effect would persist a value outside the limits above.
 *
 * Thrown inside the Room transaction so the whole effect list rolls back: a rejected command must
 * leave the database exactly as it was. `CommandExecutor` turns it into a
 * `DomainFailure.Validation`, because the UI contract has no exception channel.
 */
class DataViolationException(val code: String) : IllegalStateException(code)
