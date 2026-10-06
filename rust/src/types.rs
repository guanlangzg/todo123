//! Cross-FFI types. Every type the boundary returns is a named `uniffi::Record` / `uniffi::Enum`:
//! UniFFI cannot lower Rust tuples (架构契约 E20), so nothing here uses `(T, U)`.

/// Which list a task belongs to. `Temporary` rows render above `Daily` rows on the today screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TaskKind {
    Daily,
    Temporary,
}

impl TaskKind {
    pub fn as_code(self) -> i32 {
        match self {
            Self::Daily => 0,
            Self::Temporary => 1,
        }
    }
}

/// The name to show for one ledger row (AC-14).
///
/// Only an automatic slice has a name of its own: it points at the work segment whose `title_snapshot`
/// was written while that time was actually invested. A manual add or a set-total correction describes
/// no span of time, so it carries the task's *current* title with [`Self::is_title_snapshot`] false —
/// the interface must not present that as a past name, and nothing here invents one.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LedgerRowTitle {
    pub ledger_seq: i64,
    pub title: String,
    /// True only when `title` was read from a segment's stored snapshot.
    pub is_title_snapshot: bool,
}

/// A single point-in-time reading supplied by Kotlin. Rust never reads the system clock
/// (架构契约 §1 禁止清单); `zone_id` is the fixed application time zone in force right now.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClockSample {
    pub wall_ms: i64,
    pub zone_id: String,
    /// Android elapsedRealtime at sampling time, used to detect wall-clock jumps.
    pub elapsed_ms: i64,
    /// Boot identifier; changes after reboot. It is opaque to the domain core.
    pub boot_tag: String,
}

/// Civil date label in the application time zone, plus the zone epoch that produced it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppDate {
    /// `YYYY-MM-DD`
    pub iso: String,
    pub zone_epoch_seq: u32,
}

/// `[start_wall_ms, end_wall_ms)` is the exact partition of the time axis for one civil day
/// (架构契约 §2.3). `day_seconds` is always `(end - start) / 1000`; it is never hard-coded 86400.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DayWindow {
    pub app_date: AppDate,
    pub start_wall_ms: i64,
    pub end_wall_ms: i64,
    pub day_seconds: i64,
}

/// A title that became effective at a wall-clock instant (AC-14 slicing).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TitleRevision {
    pub effective_wall_ms: i64,
    pub title: String,
}

/// One day's share of a longer interval, tagged with the title that applied at its start.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IntervalSlice {
    /// Deterministic, per 架构契约 §6: `session_id:seg_seq:slice_start_wall_ms:zone_epoch_seq`.
    pub slice_id: String,
    pub app_date: AppDate,
    pub start_wall_ms: i64,
    pub end_wall_ms: i64,
    pub title_snapshot: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TaskRecord {
    pub task_id: String,
    pub kind: TaskKind,
    pub title: String,
    pub note: String,
    /// Spacing is 1024 within a group; `ReorderTasks` rewrites the whole group in one command.
    pub sort_key: i64,
    pub created_wall_ms: i64,
    pub archived_at_ms: Option<i64>,
    pub last_countdown_minutes: u32,
    pub art_asset_id: Option<String>,
}

/// The generation fact for one (task, app_date) pair. Completion state is *not* stored here.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OccurrenceRecord {
    pub occurrence_id: String,
    pub task_id: String,
    pub app_date: String,
    pub zone_epoch_seq: u32,
    pub display_title_snapshot: String,
    pub created_at_ms: i64,
    /// Derived view value; it is a cache over `occurrence_completion_event` (架构契约 §4.1).
    pub is_completed: bool,
    pub completed_wall_ms: Option<i64>,
    pub derived_from_event_high_water: i64,
}

/// Append-only completion event: the authoritative truth for "was this day completed".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OccurrenceCompletionEvent {
    pub event_seq: i64,
    pub occurrence_id: String,
    /// 0 = completed, 1 = un-completed.
    pub action: i32,
    pub occurred_wall_ms: i64,
    pub app_date: String,
    pub zone_epoch_seq: u32,
}

/// Append-only completion event for a *temporary* task. Same event-sourcing rule as
/// `OccurrenceCompletionEvent`: the todo/done state is derived by replaying these, and reopening
/// appends a new event instead of erasing history (AC-11, 架构契约 §4.2
/// `temporary_completion_event`).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TemporaryCompletionEvent {
    pub event_seq: i64,
    pub task_id: String,
    /// 0 = completed, 1 = reopened.
    pub action: i32,
    pub occurred_wall_ms: i64,
    pub app_date: String,
    pub title_snapshot: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionMode {
    CountUp,
    Countdown,
}

impl SessionMode {
    pub fn as_code(self) -> i32 {
        match self {
            Self::CountUp => 0,
            Self::Countdown => 1,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionState {
    Running,
    Paused,
    Finished,
    RecoveryPending,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SessionRecord {
    pub session_id: String,
    pub task_id: String,
    pub mode: SessionMode,
    pub target_seconds: Option<i64>,
    pub state: SessionState,
    pub created_wall_ms: i64,
    pub finished_wall_ms: Option<i64>,
    /// Provenance tag, e.g. `ui` or `recovery`.
    pub source: String,
}

/// A closed or open work interval. `end_wall_ms == None` means the segment is still open.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WorkSegment {
    pub segment_id: String,
    pub session_id: String,
    pub seg_seq: u32,
    pub start_wall_ms: i64,
    pub end_wall_ms: Option<i64>,
    pub title_snapshot: String,
    pub zone_epoch_seq: u32,
    pub derived: bool,
}

/// One ledger row. Ordering for replay is `(occurred_wall_ms, ledger_seq)`, never `ledger_seq`
/// alone (架构契约 §4.3.1 / N4).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LedgerRow {
    pub ledger_seq: i64,
    /// 0 = automatic slice, 1 = manual add, 2 = set-total.
    pub kind: i32,
    pub ref_id: Option<String>,
    pub task_id: String,
    pub app_date: String,
    /// Trusted occurrence instant; drives replay order.
    pub occurred_wall_ms: i64,
    pub zone_epoch_seq: u32,
    pub delta_seconds: Option<i64>,
    pub set_total_seconds: Option<i64>,
    pub created_wall_ms: i64,
    pub edited_at_ms: Option<i64>,
    pub is_deleted: bool,
    pub deleted_at_ms: Option<i64>,
}

/// Pre/post image of one ledger edit or delete, written before the mutation (架构契约 §4.3.2).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LedgerAuditRow {
    pub audit_seq: i64,
    pub ledger_seq: i64,
    pub prev_delta_seconds: Option<i64>,
    pub prev_set_total_seconds: Option<i64>,
    pub new_delta_seconds: Option<i64>,
    pub new_set_total_seconds: Option<i64>,
    pub changed_at_ms: i64,
    /// 0 = edit, 1 = delete, 2 = restore.
    pub change_kind: i32,
}

/// One day's total investment, summed over every task. This is the aggregation the calendar, the
/// week/month trend and the heat map all read (AC-12 / N10).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DayTotal {
    pub app_date: String,
    pub seconds: i64,
}

/// A day, a Monday-anchored week or a calendar month, aggregated from `DayTotal`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TimeBucket {
    Day,
    Week,
    Month,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeriodTotal {
    /// `YYYY-MM-DD` for a day, the Monday's `YYYY-MM-DD` for a week, `YYYY-MM` for a month.
    pub label: String,
    pub seconds: i64,
}

/// One task's share of the investment over the given rows.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TaskShare {
    pub task_id: String,
    pub seconds: i64,
}

/// Last successfully persisted progress anchor for a session (架构契约 §7.2). It defines how far
/// the *trusted* span of an interrupted session reaches; it is never a liveness test.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HeartbeatRecord {
    pub session_id: String,
    pub wall_ms: i64,
    pub elapsed_ms: i64,
    pub boot_tag: String,
    /// 0 = periodic (15 s), 1 = written on a state transition.
    pub heartbeat_kind: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ZoneEpoch {
    pub epoch_seq: u32,
    pub zone_id: String,
    pub effective_wall_ms: i64,
    pub impact_summary: String,
    pub created_wall_ms: i64,
}

/// Idempotency key plus the sampled clock for one user command. Kotlin generates `command_id`
/// once and reuses it across every retry of that same user action.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CommandEnvelope {
    pub command_id: String,
    pub issued_at: ClockSample,
    pub expected_revision: u64,
}

/// Structured, code-based hint for the UI. Rust never produces user-facing sentences.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Notice {
    pub code: String,
    pub affected_app_date: Option<String>,
    pub resulting_total_seconds: Option<i64>,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CommandLogEntry {
    pub command_id: String,
    pub applied_wall_ms: i64,
    pub command_kind: String,
    pub result_digest: String,
    pub expected_revision: u64,
    pub actual_revision: u64,
}

/// Persistence intent produced by a command. Kotlin writes every effect and the `command_log`
/// row inside one Room `@Transaction` (架构契约 §3.3); this is an intent, not SQL.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum LedgerEffect {
    UpsertTask {
        task: TaskRecord,
    },
    RenameTitleRevision {
        task_id: String,
        revision: TitleRevision,
        zone_epoch_seq: u32,
    },
    UpsertOccurrence {
        occurrence: OccurrenceRecord,
    },
    AppendOccurrenceCompletionEvent {
        event: OccurrenceCompletionEvent,
    },
    AppendTemporaryCompletionEvent {
        task_id: String,
        action: i32,
        occurred_wall_ms: i64,
        app_date: String,
        title_snapshot: String,
    },
    OpenSession {
        session: SessionRecord,
        segment: WorkSegment,
    },
    UpdateSession {
        session: SessionRecord,
        closed_segment: Option<WorkSegment>,
        opened_segment: Option<WorkSegment>,
    },
    SetActiveSession {
        session_id: Option<String>,
    },
    CloseSegment {
        segment_id: String,
        end_wall_ms: i64,
    },
    AppendLedgerEntry {
        row: LedgerRow,
    },
    UpdateLedgerEntry {
        row: LedgerRow,
    },
    InsertLedgerEntryAudit {
        audit: LedgerAuditRow,
    },
    SoftDeleteLedgerEntry {
        ledger_seq: i64,
        deleted_at_ms: i64,
    },
    UpsertZoneEpoch {
        epoch: ZoneEpoch,
    },
    SetHeartbeatAnchor {
        session_id: String,
        wall_ms: i64,
        elapsed_ms: i64,
        boot_tag: String,
        heartbeat_kind: i32,
    },
    AppendCommandLog {
        entry: CommandLogEntry,
    },
}

/// The full domain state as held by Kotlin (Room) and passed back into `reduce`.
/// Unit tests can build one directly with `DomainState::empty()`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DomainState {
    pub revision: u64,
    pub zone_epoch_seq: u32,
    pub zone_id: String,
    pub tasks: Vec<TaskRecord>,
    pub occurrences: Vec<OccurrenceRecord>,
    pub sessions: Vec<SessionRecord>,
    pub active_session_id: Option<String>,
    pub segments: Vec<WorkSegment>,
    pub heartbeats: Vec<HeartbeatRecord>,
    pub ledger: Vec<LedgerRow>,
    pub audits: Vec<LedgerAuditRow>,
    pub zone_epochs: Vec<ZoneEpoch>,
    pub command_log: Vec<CommandLogEntry>,
    /// High-water `event_seq` per occurrence, so the derived view stays a cache, not a second truth.
    pub completion_event_high_water: Vec<EventHighWater>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EventHighWater {
    pub occurrence_id: String,
    pub high_water: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DomainOutcome {
    pub next_state: DomainState,
    pub effects: Vec<LedgerEffect>,
    pub notices: Vec<Notice>,
    pub error: Option<crate::error::DomainError>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CompletionChoice {
    /// Stop at the sampled clock.
    Now,
    /// Stop once the session has invested exactly this many seconds **in total**.
    ///
    /// The number is what the caller's clock shows, and that display is a session total: it sums every
    /// segment, so it keeps growing across pauses and renames. The core turns it into an end instant
    /// with `session::total_end_wall_ms` — the segments already closed count as spent and only the
    /// remainder lands in the open segment — which is what makes "book what the screen showed" true
    /// after a pause, after a rename and for a paused session (AC-05 / AC-07 / AC-14).
    AfterSeconds { seconds: i64 },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RecoveryChoice {
    Accept,
    Modify { seconds: i64 },
    Discard,
}

/// The command set. Every user action arrives here; Kotlin never mutates the domain itself.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum DomainCommand {
    CreateTask {
        kind: TaskKind,
        title: String,
        note: String,
    },
    RenameTask {
        task_id: String,
        title: String,
    },
    EditNote {
        task_id: String,
        note: String,
    },
    ArchiveTask {
        task_id: String,
    },
    UnarchiveTask {
        task_id: String,
    },
    ReorderTasks {
        kind: TaskKind,
        ordered_task_ids: Vec<String>,
    },
    SetTaskRecentCountdown {
        task_id: String,
        minutes: u32,
    },
    EnsureOccurrence {
        task_id: String,
        app_date: String,
    },
    SetOccurrenceCompletion {
        task_id: String,
        app_date: String,
        completed: bool,
    },
    /// Recompute one instance's derived completion from its authoritative events. The events come
    /// from Room (the authority) and are supplied by the caller, so this stays a pure function; it
    /// must land on the same value the incremental path holds (架构契约 §4.1).
    RebuildOccurrenceView {
        occurrence_id: String,
        events: Vec<OccurrenceCompletionEvent>,
    },
    CompleteTemporary {
        task_id: String,
    },
    ReopenTemporary {
        task_id: String,
    },
    StartSession {
        task_id: String,
        mode: SessionMode,
        target_seconds: Option<i64>,
        replaces_session_id: Option<String>,
    },
    PauseSession {
        session_id: String,
    },
    ResumeSession {
        session_id: String,
    },
    FinishSession {
        session_id: String,
        completion: CompletionChoice,
    },
    CompleteTaskWhileRunning {
        session_id: String,
        target_app_date: Option<String>,
    },
    AddManualSeconds {
        task_id: String,
        app_date: String,
        seconds: i64,
    },
    SetDailyTotalSeconds {
        task_id: String,
        app_date: String,
        total_seconds: i64,
    },
    EditLedgerEntry {
        ledger_seq: i64,
        new_delta_seconds: Option<i64>,
        new_set_total_seconds: Option<i64>,
    },
    DeleteLedgerEntry {
        ledger_seq: i64,
    },
    /// Undo of a logical delete: clears `is_deleted` in place, so the row keeps its original
    /// `ledger_seq` and `occurred_wall_ms` slot.
    ///
    /// 规格 6.1:130 ("保留原始有效区间与修改前值") and the interface requirement for an in-session undo
    /// of a deleted contribution both need this. Re-appending an equal value instead would put the row
    /// at the end of the replay order, where a later set-total event swallows it — undo has to restore
    /// the row in place to actually return the day to its previous total.
    RestoreLedgerEntry {
        ledger_seq: i64,
    },
    AppendZoneEpoch {
        zone_id: String,
        impact_summary: String,
    },
    MarkRecoveryPending {
        session_id: String,
    },
    Heartbeat {
        session_id: String,
    },
    ResolveRecovery {
        session_id: String,
        choice: RecoveryChoice,
    },
}
