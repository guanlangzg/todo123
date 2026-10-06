//! `arttodo_core` — the domain core behind the narrow UniFFI interface.
//!
//! Scope rules (docs/设计/架构契约.md §1): no database, no files, no system clock, no threads, no
//! Android types, no user-facing sentences. Everything is a pure function of the supplied state
//! and the caller's `ClockSample`. Kotlin owns Room, Android lifecycle, notifications and clock
//! sampling.
//!
//! The exported functions below are the entire IDL. Adding or removing one is an interface change
//! and belongs to the `ffi-contract` owner (工程布局与版本锁定.md §3).

uniffi::setup_scaffolding!();

pub mod error;
pub mod ledger;
pub mod occurrence;
pub mod session;
pub mod state;
pub mod stats;
pub mod temporal;
pub mod temporary;
pub mod types;

pub use error::DomainError;
pub use types::*;

use crate::session::RecoveryAmounts;

/// Empty state for a fresh install: revision 1 and zone epoch 1.
#[uniffi::export]
pub fn initial_state(zone_id: String, created_wall_ms: i64) -> DomainState {
    state::initial_state(&zone_id, created_wall_ms)
}

/// The single mutation entry point. See `state::reduce`.
#[uniffi::export]
pub fn reduce(state: DomainState, command: DomainCommand, envelope: CommandEnvelope) -> DomainOutcome {
    crate::state::reduce(&state, &command, &envelope)
}

/// Instant -> application date. `CurrentAppDate` is the same call with the sampled clock; Kotlin
/// must re-invoke it after launch, a day rollover and a zone change — never `+1 day` itself.
#[uniffi::export]
pub fn app_date_of(wall_ms: i64, zone_id: String, zone_epoch_seq: u32) -> Result<AppDate, DomainError> {
    temporal::app_date_of(wall_ms, &zone_id, zone_epoch_seq)
}

/// The `[start, end)` window of one civil day, plus its true length in seconds (23 h / 24 h / 25 h).
#[uniffi::export]
pub fn day_window(app_date: String, zone_id: String, zone_epoch_seq: u32) -> Result<DayWindow, DomainError> {
    temporal::day_window(&app_date, &zone_id, zone_epoch_seq)
}

/// Cut one work interval into per-day slices. Day boundaries come only from `day_window`, so the
/// slice durations always sum to the interval length.
#[uniffi::export]
pub fn slice_interval(
    session_id: String,
    seg_seq: u32,
    start_wall_ms: i64,
    end_wall_ms: i64,
    zone_id: String,
    zone_epoch_seq: u32,
    title_revisions: Vec<TitleRevision>,
) -> Result<Vec<IntervalSlice>, DomainError> {
    temporal::slice_interval(
        &session_id,
        seg_seq,
        start_wall_ms,
        end_wall_ms,
        &zone_id,
        zone_epoch_seq,
        title_revisions,
    )
}

/// Replay one task-day's ledger rows into its total. Order is `(occurred_wall_ms, ledger_seq)`.
#[uniffi::export]
pub fn replay_daily_total(rows: Vec<LedgerRow>) -> i64 {
    ledger::replay_total(&rows)
}

/// Running total after each row, for the "before/after" preview in the ledger editor.
#[uniffi::export]
pub fn replay_daily_trace(rows: Vec<LedgerRow>) -> Vec<i64> {
    ledger::replay_trace(&rows)
}

/// Investment seconds of a session: every closed segment plus the open one measured up to `now`.
/// Paused spans are not segments at all, so they never accumulate (AC-05).
#[uniffi::export]
pub fn effective_seconds(session_id: String, segments: Vec<WorkSegment>, now_wall_ms: i64) -> i64 {
    session::effective_seconds(&session_id, &segments, now_wall_ms)
}

/// Trusted / gap / booked seconds for an interrupted session, per recovery choice (AC-09).
#[uniffi::export]
pub fn recovery_amounts(
    start_wall_ms: i64,
    heartbeat_wall_ms: i64,
    recovery_wall_ms: i64,
    choice: RecoveryChoice,
    target_seconds: Option<i64>,
) -> Result<RecoveryAmounts, DomainError> {
    session::recovery_amounts(
        start_wall_ms,
        heartbeat_wall_ms,
        recovery_wall_ms,
        &choice,
        target_seconds,
    )
}

/// `EditLedgerEntry` precondition check, exposed so a UI can validate before dispatching.
#[uniffi::export]
pub fn validate_edit_ledger_entry(
    row_kind: i32,
    new_delta_seconds: Option<i64>,
    new_set_total_seconds: Option<i64>,
) -> Result<bool, DomainError> {
    ledger::validate_edit(row_kind, new_delta_seconds, new_set_total_seconds)?;
    Ok(true)
}

/// Total the day would end at if the given row were deleted — drives the kind=2 notice.
#[uniffi::export]
pub fn total_without_ledger_entry(rows: Vec<LedgerRow>, ledger_seq: i64) -> i64 {
    ledger::total_without(&rows, ledger_seq)
}

/// Todo/done state of a temporary task, replayed from its completion events (AC-11).
#[uniffi::export]
pub fn temporary_completed(events: Vec<TemporaryCompletionEvent>, task_id: String) -> bool {
    temporary::derive_completed(&events, &task_id)
}

/// Completion of one daily instance, replayed from the authoritative events (AC-03 / AC-07).
/// `is_completed` on the record is only a cache; this is the read that decides.
#[uniffi::export]
pub fn occurrence_completed(events: Vec<OccurrenceCompletionEvent>, occurrence_id: String) -> bool {
    occurrence::replay_view(&events, &occurrence_id).is_completed
}

/// Per-day investment totals — the calendar and the heat map read exactly this (AC-12 / N10).
#[uniffi::export]
pub fn project_day_totals(rows: Vec<LedgerRow>) -> Vec<DayTotal> {
    stats::day_totals(&rows)
}

/// Day/week/month trend; every bucket is a sum of `project_day_totals` buckets.
#[uniffi::export]
pub fn project_period_totals(
    rows: Vec<LedgerRow>,
    bucket: TimeBucket,
) -> Result<Vec<PeriodTotal>, DomainError> {
    stats::period_totals(&rows, bucket)
}

/// Per-task share of the investment.
#[uniffi::export]
pub fn project_task_shares(rows: Vec<LedgerRow>) -> Vec<TaskShare> {
    stats::task_shares(&rows)
}

/// The name to show for each ledger row (AC-14).
///
/// An automatic slice is labelled with the segment snapshot of the time it was actually invested, so a
/// later rename never rewrites an earlier row; a manual add or a set-total correction carries the
/// task's current title and is flagged as *not* a snapshot, because it has no past name to restore.
#[uniffi::export]
pub fn project_ledger_row_titles(
    rows: Vec<LedgerRow>,
    segments: Vec<WorkSegment>,
    tasks: Vec<TaskRecord>,
) -> Vec<LedgerRowTitle> {
    ledger::row_titles(&rows, &segments, &tasks)
}

/// The civil day label of an instant, using the day windows — the same borders slicing uses.
#[uniffi::export]
pub fn day_label_of(wall_ms: i64, zone_id: String, zone_epoch_seq: u32) -> Result<String, DomainError> {
    temporal::day_label_of(wall_ms, &zone_id, zone_epoch_seq)
}

/// True when the cached instance already reflects every one of its events (架构契约 §4.1).
#[uniffi::export]
pub fn occurrence_view_is_current(
    occurrence: OccurrenceRecord,
    events: Vec<OccurrenceCompletionEvent>,
) -> bool {
    occurrence::view_is_current(&occurrence, &events)
}
