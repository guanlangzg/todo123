//! Domain error surface. Kotlin normalises every variant into `DomainFailure`
//! (docs/设计/架构契约.md §3.4). No variant carries free-form display text that the UI shows
//! verbatim: the UI maps `code` onto localised copy.

/// Every failure the domain core can report across the UniFFI boundary.
///
/// The generated Kotlin type is a sealed class derived from this name
/// (`DomainError` -> `DomainException`); `DomainBridge` must catch it together with
/// UniFFI's own `InternalException` (架构契约 §3.4).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum DomainError {
    #[error("title must not be empty")]
    EmptyTitle,
    #[error("title exceeds {max} characters")]
    TitleTooLong { max: u32 },
    #[error("note exceeds {max} characters")]
    NoteTooLong { max: u32 },
    #[error("seconds must not be negative")]
    NegativeSeconds,
    #[error("countdown minutes must be within {min}..={max}")]
    CountdownMinutesOutOfRange { min: u32, max: u32 },
    #[error("task not found: {task_id}")]
    TaskNotFound { task_id: String },
    #[error("session not found: {session_id}")]
    SessionNotFound { session_id: String },
    #[error("session already finished: {session_id}")]
    SessionAlreadyFinished { session_id: String },
    #[error("another session is active: {active_session_id}")]
    ConcurrentSession { active_session_id: String },
    #[error("revision conflict: expected {expected}, actual {actual}")]
    RevisionConflict { expected: u64, actual: u64 },
    #[error("precondition failed: {reason}")]
    PreconditionFailed { reason: String },
    #[error("occurrence not found for task {task_id} on {app_date}")]
    OccurrenceNotFound { task_id: String, app_date: String },
    #[error("invalid app date: {detail}")]
    InvalidAppDate { detail: String },
    #[error("invalid zone id: {detail}")]
    InvalidZoneId { detail: String },
    #[error("duplicate command: {command_id}")]
    DuplicateCommand { command_id: String },
    #[error("ledger invariant violated: {detail}")]
    LedgerInvariantViolation { detail: String },
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

impl DomainError {
    /// Stable machine-readable code, mirroring the variant name for Kotlin-side mapping.
    pub fn code(&self) -> &'static str {
        match self {
            Self::EmptyTitle => "EmptyTitle",
            Self::TitleTooLong { .. } => "TitleTooLong",
            Self::NoteTooLong { .. } => "NoteTooLong",
            Self::NegativeSeconds => "NegativeSeconds",
            Self::CountdownMinutesOutOfRange { .. } => "CountdownMinutesOutOfRange",
            Self::TaskNotFound { .. } => "TaskNotFound",
            Self::SessionNotFound { .. } => "SessionNotFound",
            Self::SessionAlreadyFinished { .. } => "SessionAlreadyFinished",
            Self::ConcurrentSession { .. } => "ConcurrentSession",
            Self::RevisionConflict { .. } => "RevisionConflict",
            Self::PreconditionFailed { .. } => "PreconditionFailed",
            Self::OccurrenceNotFound { .. } => "OccurrenceNotFound",
            Self::InvalidAppDate { .. } => "InvalidAppDate",
            Self::InvalidZoneId { .. } => "InvalidZoneId",
            Self::DuplicateCommand { .. } => "DuplicateCommand",
            Self::LedgerInvariantViolation { .. } => "LedgerInvariantViolation",
            Self::Internal { .. } => "Internal",
        }
    }

    pub fn internal(detail: impl Into<String>) -> Self {
        Self::Internal {
            detail: detail.into(),
        }
    }
}
