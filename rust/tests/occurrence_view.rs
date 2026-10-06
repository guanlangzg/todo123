//! AC-03 / AC-07 / AC-11: the completion view is a *rebuildable cache* over the events.
//!
//! 架构契约 §4.1 makes three demandable claims, and this file demands all three:
//!  1. `RebuildOccurrenceView` equals the incrementally maintained value, field by field;
//!  2. undoing a completion changes only the derived view — never a session or a ledger row;
//!  3. reaching a countdown target changes neither (AC-07 reads the same single place).
//!
//! The events themselves live in Room, so the test threads them alongside the state exactly as
//! Kotlin does (`tests/common/mod.rs`).
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test occurrence_view`

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
const DATE: &str = "2026-03-10";
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

fn with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: TASK.to_string(),
        kind: TaskKind::Daily,
        title: "晨间速写".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

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

fn generated() -> DomainState {
    let state = with_task();
    apply(
        &state,
        &mut EventLog::new(),
        DomainCommand::EnsureOccurrence {
            task_id: TASK.to_string(),
            app_date: DATE.to_string(),
        },
        "ensure",
        BASE + 1_000,
    )
}

fn set_completed(
    state: &DomainState,
    log: &mut EventLog,
    completed: bool,
    id: &str,
    wall_ms: i64,
) -> DomainState {
    apply(
        state,
        log,
        DomainCommand::SetOccurrenceCompletion {
            task_id: TASK.to_string(),
            app_date: DATE.to_string(),
            completed,
        },
        id,
        wall_ms,
    )
}

fn occurrence(state: &DomainState) -> OccurrenceRecord {
    state
        .occurrences
        .iter()
        .find(|item| item.app_date == DATE)
        .cloned()
        .expect("occurrence")
}

/// There is exactly one instance per (task, day), and its identity is stable across the whole
/// complete/undo cycle.
#[test]
fn the_instance_identity_is_stable_across_complete_and_undo() {
    let mut log = EventLog::new();
    let mut state = generated();
    let original = occurrence(&state).occurrence_id.clone();

    state = set_completed(&state, &mut log, true, "complete", BASE + 2_000);
    assert_eq!(occurrence(&state).occurrence_id, original);
    state = set_completed(&state, &mut log, false, "undo", BASE + 3_000);
    assert_eq!(occurrence(&state).occurrence_id, original);
    assert_eq!(occurrence(&state).task_id, TASK);
    assert_eq!(occurrence(&state).app_date, DATE);
    assert_eq!(state.occurrences.len(), 1, "undoing must not create a row");
}

/// The cached flag equals the event replay, at every step of a longer cycle.
#[test]
fn the_view_equals_the_event_replay_at_every_step() {
    let mut log = EventLog::new();
    let mut state = generated();

    let cycle = [
        (true, "c1"),
        (false, "c2"),
        (true, "c3"),
        (false, "c4"),
        (true, "c5"),
    ];
    for (index, (completed, id)) in cycle.iter().enumerate() {
        state = set_completed(
            &state,
            &mut log,
            *completed,
            id,
            BASE + 2_000 + index as i64 * 1_000,
        );
        let view = occurrence(&state);
        let events = log.events_for(&view.occurrence_id);
        assert_eq!(
            view.is_completed,
            occurrence_completed(events.clone(), view.occurrence_id.clone()),
            "step {index}: the cached flag must equal the replay"
        );

        // The cache is not stale: its high-water mark is the newest event.
        assert!(
            occurrence_view_is_current(view.clone(), events.clone()),
            "step {index}: the view must reflect every event"
        );
        assert_eq!(
            view.derived_from_event_high_water,
            events.iter().map(|event| event.event_seq).max().unwrap_or(0),
            "step {index}"
        );
        // Each step appended exactly one event, and none were removed.
        assert_eq!(events.len(), index + 1);
    }
    assert!(occurrence(&state).is_completed);
    assert_eq!(log.occurrence.len(), 5);
}

/// `RebuildOccurrenceView` reproduces the value the events imply, even after the cache is
/// deliberately corrupted — that is what makes it a repair hatch rather than a second opinion.
#[test]
fn rebuild_reproduces_the_replayed_value_after_corruption() {
    let mut log = EventLog::new();
    let mut state = generated();
    state = set_completed(&state, &mut log, true, "c1", BASE + 2_000);
    state = set_completed(&state, &mut log, false, "c2", BASE + 3_000);
    let occurrence_id = occurrence(&state).occurrence_id.clone();
    let expected = occurrence(&state);
    assert!(!expected.is_completed, "the events say reopened");

    // Corrupt the cache the way a half-written transaction could.
    for item in state.occurrences.iter_mut() {
        item.is_completed = true;
        item.completed_wall_ms = Some(BASE);
        item.derived_from_event_high_water = 0;
    }
    let corrupted = occurrence(&state);
    assert!(!occurrence_view_is_current(
        corrupted,
        log.events_for(&occurrence_id)
    ));

    let outcome = reduce(
        &state,
        DomainCommand::RebuildOccurrenceView {
            occurrence_id: occurrence_id.clone(),
            events: log.events_for(&occurrence_id),
        },
        &envelope("rebuild", BASE + 4_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let rebuilt = outcome
        .next_state
        .occurrences
        .iter()
        .find(|item| item.occurrence_id == occurrence_id)
        .cloned()
        .expect("occurrence");
    assert_eq!(rebuilt.is_completed, expected.is_completed);
    assert_eq!(rebuilt.completed_wall_ms, expected.completed_wall_ms);
    assert_eq!(
        rebuilt.derived_from_event_high_water,
        expected.derived_from_event_high_water
    );
    assert!(
        occurrence_view_is_current(rebuilt, log.events_for(&occurrence_id)),
        "after the rebuild the cache must be current"
    );
}

/// A rebuild must not invent facts: a cache claiming completion with no events behind it is
/// corrected back to pending.
#[test]
fn rebuilding_a_fresh_instance_yields_pending() {
    let mut state = generated();
    // A cache that claims completion while the event table is empty. The high-water mark is *not*
    // inflated here: lowering a high-water mark is refused (see the partial-list test), and this
    // case is about the flag the events cannot support.
    for item in state.occurrences.iter_mut() {
        item.is_completed = true;
        item.completed_wall_ms = Some(BASE + 1);
    }
    let occurrence_id = occurrence(&state).occurrence_id.clone();
    let outcome = reduce(
        &state,
        DomainCommand::RebuildOccurrenceView {
            occurrence_id,
            events: Vec::new(),
        },
        &envelope("rebuild", BASE + 2_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let rebuilt = occurrence(&outcome.next_state);
    assert!(!rebuilt.is_completed);
    assert_eq!(rebuilt.completed_wall_ms, None);
    assert_eq!(rebuilt.derived_from_event_high_water, 0);
}

/// A partial event list must never erase a newer fact: the rebuild only adopts the replayed flag
/// when the replay covers everything already accounted for.
#[test]
fn a_partial_event_list_cannot_erase_a_newer_fact() {
    let mut log = EventLog::new();
    let mut state = generated();
    state = set_completed(&state, &mut log, true, "c1", BASE + 2_000);
    state = set_completed(&state, &mut log, false, "c2", BASE + 3_000);
    let occurrence_id = occurrence(&state).occurrence_id.clone();
    assert_eq!(occurrence(&state).derived_from_event_high_water, 2);

    let mut partial = log.events_for(&occurrence_id);
    partial.truncate(1); // only the completion, not the reopen

    let outcome = reduce(
        &state,
        DomainCommand::RebuildOccurrenceView {
            occurrence_id: occurrence_id.clone(),
            events: partial,
        },
        &envelope("rebuild-partial", BASE + 4_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let rebuilt = occurrence(&outcome.next_state);
    assert_eq!(
        rebuilt.derived_from_event_high_water, 2,
        "the high-water mark must not go backwards"
    );
    assert!(
        occurrence_view_is_current(rebuilt, log.events_for(&occurrence_id)),
        "the cache must still reflect the newest known event"
    );
}

/// Rebuilding an unknown instance is a `NotFound`, not a panic and not a silent row.
#[test]
fn rebuilding_an_unknown_instance_is_refused() {
    let state = generated();
    let outcome = reduce(
        &state,
        DomainCommand::RebuildOccurrenceView {
            occurrence_id: "occ:nope:2026-03-10".to_string(),
            events: Vec::new(),
        },
        &envelope("rebuild-unknown", BASE + 2_000, state.revision),
    );
    assert!(matches!(
        outcome.error,
        Some(DomainError::OccurrenceNotFound { .. })
    ));
    assert!(outcome.effects.is_empty());
    assert_eq!(outcome.next_state.occurrences, state.occurrences);
}

/// AC-03: undoing a completion leaves every time record byte-identical.
#[test]
fn undo_changes_only_the_derived_view() {
    let mut log = EventLog::new();
    let mut state = generated();
    let session_start = BASE + 10_000;
    state = apply(
        &state,
        &mut log,
        DomainCommand::StartSession {
            task_id: TASK.to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        "start",
        session_start,
    );
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        &mut log,
        DomainCommand::CompleteTaskWhileRunning {
            session_id,
            target_app_date: Some(DATE.to_string()),
        },
        "complete-running",
        session_start + 600_000,
    );

    let ledger_before = state.ledger.clone();
    let segments_before = state.segments.clone();
    let sessions_before = state.sessions.clone();
    let events_before = log.occurrence.len();
    assert!(occurrence(&state).is_completed);
    assert_eq!(replay_daily_total(ledger_before.clone()), 600);

    state = set_completed(&state, &mut log, false, "undo", session_start + 700_000);

    assert!(!occurrence(&state).is_completed);
    assert_eq!(occurrence(&state).completed_wall_ms, None);
    assert_eq!(state.ledger, ledger_before, "undo must not touch the ledger");
    assert_eq!(state.segments, segments_before, "undo must not touch segments");
    assert_eq!(state.sessions, sessions_before, "undo must not touch sessions");
    assert_eq!(
        log.occurrence.len(),
        events_before + 1,
        "undo appends; it never deletes the completion event"
    );
    assert_eq!(log.occurrence[0].action, 0);
    assert_eq!(log.occurrence[1].action, 1);
    assert_eq!(replay_daily_total(state.ledger.clone()), 600);
}

/// AC-07: reaching the countdown target neither completes nor appends a completion event.
#[test]
fn reaching_the_target_writes_no_completion_event() {
    let mut log = EventLog::new();
    let state = generated();
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: TASK.to_string(),
            mode: SessionMode::Countdown,
            target_seconds: Some(600),
            replaces_session_id: None,
        },
        &envelope("start", BASE + 2_000, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    assert!(!started
        .effects
        .iter()
        .any(|effect| matches!(effect, LedgerEffect::AppendOccurrenceCompletionEvent { .. })));
    log.absorb(&started);
    let state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");

    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        &envelope("finish-at-target", BASE + 602_000, state.revision),
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    assert!(
        !finished
            .effects
            .iter()
            .any(|effect| matches!(effect, LedgerEffect::AppendOccurrenceCompletionEvent { .. })),
        "the target is an end, not a completion"
    );
    log.absorb(&finished);
    let state = finished.next_state;

    assert!(log.occurrence.is_empty());
    let view = occurrence(&state);
    assert!(!view.is_completed);
    assert_eq!(view.completed_wall_ms, None);
    assert_eq!(view.derived_from_event_high_water, 0);
    assert!(!occurrence_completed(
        log.events_for(&view.occurrence_id),
        view.occurrence_id.clone()
    ));
    // The work itself is still booked.
    assert_eq!(replay_daily_total(state.ledger.clone()), 600);
}
