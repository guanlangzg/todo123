package app.arttodo.domain.bridge

import app.arttodo.core.DomainException

/**
 * Normalised failure surface for the UI (架构契约 §3.4).
 *
 * The domain core never produces user-facing prose, so screens map these codes onto localised copy.
 */
sealed interface DomainFailure {
    data class Validation(val code: String) : DomainFailure
    data class Conflict(val code: String, val activeSessionId: String?) : DomainFailure
    data class PreconditionChanged(val reason: String) : DomainFailure
    data class NotFound(val code: String) : DomainFailure

    /**
     * The daily recovery point could not be captured, so the change was **not** written (架构契约 §8.4).
     *
     * Deliberately not folded into [Internal]: a storage problem must reach the user as a storage
     * problem ("空间不足时告警，不假称已备份"), and the retry is safe because nothing was committed.
     * This is a Kotlin-side condition — the domain core knows nothing about backup files — which is
     * why it has no counterpart in the Rust error mapping.
     */
    data class RecoveryPointUnavailable(val code: String) : DomainFailure

    data class Internal(val detail: String) : DomainFailure
}

sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val failure: DomainFailure) : DomainResult<Nothing>
}

/**
 * Maps a Rust error variant onto the normalised failure surface.
 *
 * The variant name is taken from the generated subclass name, which UniFFI derives from the Rust
 * enum variant; the contract deliberately does not hard-code a package-level class name
 * (架构契约 §3.4 note 4).
 */
fun DomainException.toFailure(): DomainFailure = when (this) {
    is DomainException.EmptyTitle,
    is DomainException.TitleTooLong,
    is DomainException.NoteTooLong,
    is DomainException.NegativeSeconds,
    is DomainException.CountdownMinutesOutOfRange,
    is DomainException.InvalidAppDate,
    is DomainException.InvalidZoneId,
    -> DomainFailure.Validation(variantName())

    // AC-06: the UI needs the blocking session id so it can offer "end it and start the new one".
    is DomainException.ConcurrentSession -> DomainFailure.Conflict(variantName(), activeSessionId)

    is DomainException.RevisionConflict,
    is DomainException.PreconditionFailed,
    -> DomainFailure.PreconditionChanged(describe())

    is DomainException.TaskNotFound,
    is DomainException.SessionNotFound,
    is DomainException.SessionAlreadyFinished,
    is DomainException.OccurrenceNotFound,
    -> DomainFailure.NotFound(variantName())

    is DomainException.DuplicateCommand -> DomainFailure.Conflict(variantName(), null)

    is DomainException.LedgerInvariantViolation,
    is DomainException.Internal,
    -> DomainFailure.Internal(describe())
}

/** Stable machine name of the error variant, used as the failure `code`. */
fun DomainException.variantName(): String = this::class.java.simpleName

private fun DomainException.describe(): String = when (this) {
    is DomainException.RevisionConflict -> "expected=$expected actual=$actual"
    is DomainException.PreconditionFailed -> reason
    is DomainException.LedgerInvariantViolation -> detail
    is DomainException.Internal -> detail
    else -> variantName()
}
