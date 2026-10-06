//! AC-06 / AC-07: one active session, atomic switching, precondition enforcement, and "reaching
//! the target never completes the task by itself".
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test single_session`

use arttodo_core::*;

/// Convenience wrapper: the exported `reduce` takes owned values because it crosses the UniFFI
/// boundary, while the tests borrow. Keeping one wrapper per test file avoids cloning at each call.
fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

/// Same reason as `reduce`: the exported entry point owns its arguments.
fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;

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

fn state_with_two_tasks() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    for (index, task_id) in ["task:a", "task:b"].iter().enumerate() {
        state.tasks.push(TaskRecord {
            task_id: (*task_id).to_string(),
            kind: TaskKind::Daily,
            title: format!("日常{}", index + 1),
            note: String::new(),
            sort_key: (index as i64 + 1) * 1024,
            created_wall_ms: BASE,
            archived_at_ms: None,
            last_countdown_minutes: 25,
            art_asset_id: None,
        });
    }
    state
}

fn start(state: &DomainState, task_id: &str, wall_ms: i64, replaces: Option<&str>) -> DomainOutcome {
    reduce(
        state,
        DomainCommand::StartSession {
            task_id: task_id.to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: replaces.map(str::to_string),
        },
        &envelope(&format!("start-{task_id}-{wall_ms}"), wall_ms, state.revision),
    )
}

/// A second `StartSession` while one is active is a `ConcurrentSession`, and it changes nothing.
#[test]
fn a_second_session_is_rejected_with_concurrent_session() {
    let state = state_with_two_tasks();
    let first = start(&state, "task:a", BASE, None);
    assert!(first.error.is_none(), "{:?}", first.error);
    let state = first.next_state;
    let active = state.active_session_id.clone().expect("active");

    let second = start(&state, "task:b", BASE + 1_000, None);
    assert!(matches!(
        second.error,
        Some(DomainError::ConcurrentSession { .. })
    ));
    assert!(second.effects.is_empty());
    assert_eq!(
        second.next_state.active_session_id.as_deref(),
        Some(active.as_str())
    );
}

/// Confirming the switch atomically finishes the old session and opens the new one, leaving exactly
/// one active slot.
#[test]
fn switching_with_confirmation_finishes_the_old_session() {
    let state = state_with_two_tasks();
    let state = start(&state, "task:a", BASE, None).next_state;
    let previous = state.active_session_id.clone().expect("active");

    let switched = start(&state, "task:b", BASE + 300_000, Some(&previous));
    assert!(switched.error.is_none(), "{:?}", switched.error);
    let state = switched.next_state;

    assert_ne!(state.active_session_id.as_deref(), Some(previous.as_str()));
    let old = state
        .sessions
        .iter()
        .find(|s| s.session_id == previous)
        .expect("old session");
    assert_eq!(old.state, SessionState::Finished);
    assert_eq!(old.finished_wall_ms, Some(BASE + 300_000));
    let running: Vec<_> = state
        .sessions
        .iter()
        .filter(|s| s.state == SessionState::Running)
        .collect();
    assert_eq!(running.len(), 1);
}

/// A stale revision must be refused instead of being replayed blindly (架构契约 §5.2 rule 2).
#[test]
fn a_stale_expected_revision_is_refused() {
    let state = state_with_two_tasks();
    let outcome = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:a".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("stale", BASE, state.revision + 5),
    );
    assert!(matches!(
        outcome.error,
        Some(DomainError::RevisionConflict { .. })
    ));
    assert!(outcome.effects.is_empty());
    assert!(outcome.next_state.active_session_id.is_none());
}

/// Re-submitting the identical command id is reported as `DuplicateCommand`, not applied twice.
#[test]
fn the_same_command_id_is_not_applied_twice() {
    let state = state_with_two_tasks();
    let command = DomainCommand::AddManualSeconds {
        task_id: "task:a".to_string(),
        app_date: "2026-03-10".to_string(),
        seconds: 600,
    };
    let envelope = CommandEnvelope {
        command_id: "same-id".to_string(),
        issued_at: ClockSample {
            wall_ms: BASE,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: String::new(),
        },
        expected_revision: state.revision,
    };
    let first = reduce(&state, command.clone(), &envelope);
    assert!(first.error.is_none(), "{:?}", first.error);
    let second = reduce(&first.next_state, command.clone(), &envelope);
    assert!(matches!(second.error, Some(DomainError::DuplicateCommand { .. })));
    assert_eq!(second.next_state.ledger.len(), 1);
}

/// Only one session may be live at a time, and the domain exposes no way to reach a second one: a
/// second `StartSession` is refused and a *finished* session cannot be resumed.
#[test]
fn a_finished_session_cannot_be_resumed_or_restarted() {
    let state = state_with_two_tasks();
    let state = start(&state, "task:a", BASE, None).next_state;
    let previous = state.active_session_id.clone().expect("active");
    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id: previous.clone(),
            completion: CompletionChoice::Now,
        },
        &envelope("finish", BASE + 600_000, state.revision),
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    let state = finished.next_state;
    assert!(state.active_session_id.is_none());

    // Resuming a finished session is a precondition failure, not a silent restart.
    for command in [
        DomainCommand::ResumeSession {
            session_id: previous.clone(),
        },
        DomainCommand::FinishSession {
            session_id: previous.clone(),
            completion: CompletionChoice::Now,
        },
    ] {
        let outcome = reduce(
            &state,
            command,
            &envelope("after-finish", BASE + 700_000, state.revision),
        );
        assert!(outcome.error.is_some(), "a finished session must not move again");
        assert!(outcome.effects.is_empty());
        assert!(outcome.next_state.active_session_id.is_none());
    }

    // Starting a *new* session is allowed, and leaves exactly one active slot.
    let restarted = start(&state, "task:a", BASE + 800_000, None);
    assert!(restarted.error.is_none(), "{:?}", restarted.error);
    let restarted = restarted.next_state;
    let running: Vec<_> = restarted
        .sessions
        .iter()
        .filter(|session| session.state == SessionState::Running)
        .collect();
    assert_eq!(running.len(), 1);
    assert_eq!(
        restarted.active_session_id.as_deref(),
        Some(running[0].session_id.as_str())
    );
}

/// AC-07: reaching the countdown target ends the session but must not complete the occurrence.
#[test]
fn reaching_the_target_does_not_complete_the_task() {
    let mut state = state_with_two_tasks();
    let occurrence = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: "task:a".to_string(),
            app_date: "2026-03-10".to_string(),
        },
        &envelope("ensure", BASE, state.revision),
    );
    assert!(occurrence.error.is_none(), "{:?}", occurrence.error);
    state = occurrence.next_state;

    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:a".to_string(),
            mode: SessionMode::Countdown,
            target_seconds: Some(600),
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");

    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        &envelope("finish-at-target", BASE + 601_000, state.revision),
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    let state = finished.next_state;

    assert!(
        !state.occurrences.iter().any(|item| item.is_completed),
        "reaching the target must not complete"
    );
    assert!(!finished
        .effects
        .iter()
        .any(|effect| matches!(effect, LedgerEffect::AppendOccurrenceCompletionEvent { .. })));
}

/// AC-07: completing while running finishes the session and completes the occurrence in one command.
#[test]
fn completing_while_running_finishes_and_completes_atomically() {
    let mut state = state_with_two_tasks();
    state = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: "task:a".to_string(),
            app_date: "2026-03-10".to_string(),
        },
        &envelope("ensure", BASE, state.revision),
    )
    .next_state;
    state = start(&state, "task:a", BASE + 1_000, None).next_state;
    let session_id = state.active_session_id.clone().expect("active");

    let done = reduce(
        &state,
        DomainCommand::CompleteTaskWhileRunning {
            session_id,
            target_app_date: Some("2026-03-10".to_string()),
        },
        &envelope("complete-while-running", BASE + 300_000, state.revision),
    );
    assert!(done.error.is_none(), "{:?}", done.error);
    let state = done.next_state;

    assert!(state.active_session_id.is_none());
    let occurrence = state
        .occurrences
        .iter()
        .find(|item| item.app_date == "2026-03-10")
        .expect("occurrence");
    assert!(occurrence.is_completed);
    assert!(state
        .sessions
        .iter()
        .all(|session| session.state == SessionState::Finished));
}

/// AC-03: undoing a completion clears the derived flag and leaves time records untouched.
#[test]
fn undo_completion_leaves_time_records_alone() {
    let mut state = state_with_two_tasks();
    state = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: "task:a".to_string(),
            app_date: "2026-03-10".to_string(),
        },
        &envelope("ensure", BASE, state.revision),
    )
    .next_state;
    state = reduce(
        &state,
        DomainCommand::SetOccurrenceCompletion {
            task_id: "task:a".to_string(),
            app_date: "2026-03-10".to_string(),
            completed: true,
        },
        &envelope("complete", BASE + 1_000, state.revision),
    )
    .next_state;
    let completed = state
        .occurrences
        .iter()
        .find(|item| item.app_date == "2026-03-10")
        .cloned()
        .expect("occurrence");
    assert!(completed.is_completed);
    let ledger_before = state.ledger.len();
    let sessions_before = state.sessions.len();

    let undone = reduce(
        &state,
        DomainCommand::SetOccurrenceCompletion {
            task_id: "task:a".to_string(),
            app_date: "2026-03-10".to_string(),
            completed: false,
        },
        &envelope("undo", BASE + 2_000, state.revision),
    );
    assert!(undone.error.is_none(), "{:?}", undone.error);
    let state = undone.next_state;

    let after = state
        .occurrences
        .iter()
        .find(|item| item.app_date == "2026-03-10")
        .expect("occurrence");
    assert!(!after.is_completed);
    assert_eq!(after.completed_wall_ms, None);
    assert_eq!(after.derived_from_event_high_water, 2);
    assert_eq!(state.ledger.len(), ledger_before);
    assert_eq!(state.sessions.len(), sessions_before);
    assert_eq!(
        state
            .completion_event_high_water
            .iter()
            .map(|e| e.high_water)
            .max(),
        Some(2)
    );
}

/// Guard for a real defect found during the Android integration run: `active_session_slot.session_id`
/// is a foreign key onto `focus_session`, so the session row must be persisted *before* the slot is
/// written. Emitting `SetActiveSession` first made every `StartSession` fail with a foreign-key
/// violation inside the Room transaction.
///
/// The ordering is part of the effect contract, because Kotlin applies the list in order.
#[test]
fn opening_a_session_persists_the_session_before_the_active_slot() {
    let state = state_with_two_tasks();
    let outcome = start(&state, "task:a", BASE, None);
    assert!(outcome.error.is_none(), "{:?}", outcome.error);

    let open_at = outcome
        .effects
        .iter()
        .position(|effect| matches!(effect, LedgerEffect::OpenSession { .. }))
        .expect("OpenSession must be emitted");
    let slot_at = outcome
        .effects
        .iter()
        .position(|effect| matches!(effect, LedgerEffect::SetActiveSession { session_id: Some(_) }))
        .expect("a slot write must be emitted");
    assert!(
        open_at < slot_at,
        "OpenSession (index {open_at}) must precede the active-slot write (index {slot_at})"
    );
}
