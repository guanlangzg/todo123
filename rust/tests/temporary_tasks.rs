//! AC-11 (+AC-10 boundary): a temporary task's todo/done state is *replayed* from its completion
//! events, archiving is independent of completion, and a missing event list means "pending".
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test temporary_tasks`

mod common;

use arttodo_core::*;
use common::EventLog;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
const TASK: &str = "task:t1";

fn envelope(id: &str, wall_ms: i64, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: String::new(),
        },
        expected_revision: revision,
    }
}

fn with_task(kind: TaskKind) -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: TASK.to_string(),
        kind,
        title: "刻章".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

/// One command plus the event log it produced, so the test always knows the evidence Room holds.
fn apply(
    state: &DomainState,
    log: &mut EventLog,
    command: DomainCommand,
    id: &str,
    wall_ms: i64,
) -> DomainState {
    let outcome = reduce(state, command, &envelope(id, wall_ms, state.revision));
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    log.absorb(&outcome);
    outcome.next_state
}

fn completed(state: &EventLog) -> bool {
    temporary_completed(state.events_for_task(TASK), TASK.to_string())
}

/// Completing then reopening then completing ends completed — replaying, not flag flipping.
#[test]
fn temporary_completion_is_replayed_from_events() {
    let mut state = with_task(TaskKind::Temporary);
    let mut log = EventLog::new();
    assert!(!completed(&log));

    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        "c1",
        BASE + 1_000,
    );
    assert!(completed(&log));
    assert_eq!(log.temporary.len(), 1);

    state = apply(
        &state,
        &mut log,
        DomainCommand::ReopenTemporary {
            task_id: TASK.to_string(),
        },
        "c2",
        BASE + 2_000,
    );
    assert!(!completed(&log));
    // Reopening appends; it never removes the earlier completion from history.
    assert_eq!(log.temporary.len(), 2);
    assert_eq!(log.temporary[0].action, 0, "the completion event is kept");
    assert_eq!(log.temporary[1].action, 1);

    let _state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        "c3",
        BASE + 3_000,
    );
    assert!(completed(&log));
    assert_eq!(log.temporary.len(), 3);
    // Room assigns the sequence numbers: monotonic within the task, starting at 1.
    let seqs: Vec<i64> = log.temporary.iter().map(|event| event.event_seq).collect();
    assert_eq!(seqs, vec![1, 2, 3]);
    let _ = state;
}

/// The domain leaves `event_seq` to the database: the effect does not invent one.
#[test]
fn the_effect_does_not_mint_an_event_sequence() {
    let state = with_task(TaskKind::Temporary);
    let outcome = reduce(
        &state,
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        &envelope("c1", BASE + 1_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let appended = outcome
        .effects
        .iter()
        .find_map(|effect| match effect {
            LedgerEffect::AppendTemporaryCompletionEvent {
                task_id,
                action,
                occurred_wall_ms,
                app_date,
                title_snapshot,
            } => Some((
                task_id.clone(),
                *action,
                *occurred_wall_ms,
                app_date.clone(),
                title_snapshot.clone(),
            )),
            _ => None,
        })
        .expect("the command must append a temporary event");
    assert_eq!(appended.0, TASK);
    assert_eq!(appended.1, 0, "action 0 = completed");
    assert_eq!(appended.2, BASE + 1_000);
    assert_eq!(
        appended.3,
        day_label_of(BASE + 1_000, ZONE.to_string(), 1).expect("label")
    );
    assert_eq!(appended.4, "刻章", "the event carries the title at that moment");
}

/// A temporary completion event carries the title snapshot of the moment it happened.
#[test]
fn temporary_events_snapshot_the_title_at_that_moment() {
    let mut state = with_task(TaskKind::Temporary);
    let mut log = EventLog::new();
    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        "c1",
        BASE + 1_000,
    );
    let mut state = apply(
        &state,
        &mut log,
        DomainCommand::RenameTask {
            task_id: TASK.to_string(),
            title: "刻章（新）".to_string(),
        },
        "rename",
        BASE + 2_000,
    );
    state = apply(
        &state,
        &mut log,
        DomainCommand::ReopenTemporary {
            task_id: TASK.to_string(),
        },
        "c2",
        BASE + 3_000,
    );
    assert_eq!(log.temporary[0].title_snapshot, "刻章");
    assert_eq!(log.temporary[1].title_snapshot, "刻章（新）");
    let _ = state;
}

/// AC-10: archiving is *not* completing. It produces no completion event at all.
#[test]
fn archiving_never_creates_a_completion_event() {
    for kind in [TaskKind::Daily, TaskKind::Temporary] {
        let state = with_task(kind);
        let mut log = EventLog::new();
        let archived = reduce(
            &state,
            DomainCommand::ArchiveTask {
                task_id: TASK.to_string(),
            },
            &envelope("archive", BASE + 1_000, state.revision),
        );
        assert!(archived.error.is_none(), "{:?}", archived.error);
        log.absorb(&archived);
        let state = archived.next_state;

        assert!(state.tasks[0].archived_at_ms.is_some());
        assert!(
            log.temporary.is_empty(),
            "{kind:?}: archiving must not append a completion event"
        );
        assert!(!completed(&log), "{kind:?}");
        assert!(
            !state.occurrences.iter().any(|item| item.is_completed),
            "{kind:?}: archiving must not mark a day complete"
        );
    }
}

/// AC-10: a temporary task that was completed stays completed after archiving — the two facts are
/// independent, and unarchiving does not resurrect it as pending.
#[test]
fn archiving_does_not_change_a_temporary_completion() {
    let mut state = with_task(TaskKind::Temporary);
    let mut log = EventLog::new();
    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        "c1",
        BASE + 1_000,
    );
    state = apply(
        &state,
        &mut log,
        DomainCommand::ArchiveTask {
            task_id: TASK.to_string(),
        },
        "archive",
        BASE + 2_000,
    );
    assert!(completed(&log));
    let state = apply(
        &state,
        &mut log,
        DomainCommand::UnarchiveTask {
            task_id: TASK.to_string(),
        },
        "unarchive",
        BASE + 3_000,
    );
    assert!(completed(&log));
    assert_eq!(log.temporary.len(), 1);
    assert!(state.tasks[0].archived_at_ms.is_none());
}

/// Completing the task a timer is running on must finish the session *and* complete the task, and
/// for a temporary task the completion is a todo/done event, not a daily occurrence event.
#[test]
fn completing_while_running_routes_by_task_kind() {
    let state = with_task(TaskKind::Temporary);
    let mut log = EventLog::new();
    let mut state = apply(
        &state,
        &mut log,
        DomainCommand::StartSession {
            task_id: TASK.to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        "start",
        BASE + 1_000,
    );
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTaskWhileRunning {
            session_id,
            target_app_date: None,
        },
        "complete",
        BASE + 301_000,
    );
    assert!(state.active_session_id.is_none());
    assert!(state
        .sessions
        .iter()
        .all(|session| session.state == SessionState::Finished));
    assert!(completed(&log));
    assert!(
        log.occurrence.is_empty(),
        "a temporary task must not write a daily occurrence event"
    );
    // 300 s of work was booked on the way out.
    assert_eq!(
        state
            .ledger
            .iter()
            .filter(|row| row.kind == 0)
            .map(|row| row.delta_seconds.unwrap_or(0))
            .sum::<i64>(),
        300
    );
}

/// Completing the task a timer is running on for a *daily* task creates the lazy instance if the day
/// had not been touched yet, so the completion event never dangles.
#[test]
fn completing_while_running_creates_today_instance_for_a_daily_task() {
    let state = with_task(TaskKind::Daily);
    let today = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut log = EventLog::new();
    let mut state = apply(
        &state,
        &mut log,
        DomainCommand::StartSession {
            task_id: TASK.to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        "start",
        BASE + 1_000,
    );
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTaskWhileRunning {
            session_id,
            target_app_date: None,
        },
        "complete",
        BASE + 301_000,
    );
    let occurrence = state
        .occurrences
        .iter()
        .find(|item| item.app_date == today)
        .expect("an instance must exist for the completion event");
    assert!(occurrence.is_completed);
    assert_eq!(log.occurrence.len(), 1);
    assert!(
        log.temporary.is_empty(),
        "a daily task must not write a temporary event"
    );
}

/// The temporary event log belongs to temporary tasks only.
#[test]
fn daily_tasks_cannot_use_the_temporary_commands() {
    let state = with_task(TaskKind::Daily);
    for command in [
        DomainCommand::CompleteTemporary {
            task_id: TASK.to_string(),
        },
        DomainCommand::ReopenTemporary {
            task_id: TASK.to_string(),
        },
    ] {
        let outcome = reduce(&state, command, &envelope("x", BASE + 1_000, state.revision));
        assert!(
            matches!(outcome.error, Some(DomainError::PreconditionFailed { .. })),
            "{:?}",
            outcome.error
        );
        assert!(outcome.effects.is_empty());
        assert_eq!(outcome.next_state.revision, state.revision);
    }
}

/// An unknown task is a `NotFound`-style precondition failure, not a silent event.
#[test]
fn an_unknown_task_cannot_be_completed() {
    let state = with_task(TaskKind::Temporary);
    let outcome = reduce(
        &state,
        DomainCommand::CompleteTemporary {
            task_id: "task:nope".to_string(),
        },
        &envelope("c1", BASE + 1_000, state.revision),
    );
    assert!(matches!(outcome.error, Some(DomainError::TaskNotFound { .. })));
    assert!(outcome.effects.is_empty());
}

/// The completion event's day label comes from the day windows, the same borders slicing uses.
#[test]
fn the_event_day_label_matches_the_day_window() {
    let at = BASE + 1_000;
    let label = day_label_of(at, ZONE.to_string(), 1).expect("label");
    assert_eq!(label, app_date_of(at, ZONE.to_string(), 1).expect("app date").iso);
    let window = day_window(label.clone(), ZONE.to_string(), 1).expect("window");
    assert!(at >= window.start_wall_ms && at < window.end_wall_ms);
}
