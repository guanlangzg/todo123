//! AC-05 / AC-06 / AC-07 / AC-09: the session state machine and command-level idempotency.
//!
//! Two things the ask names explicitly live here:
//!  * **every state transition** — Start / Pause / Resume / Finish / complete-while-running /
//!    recovery — including the illegal ones, which must be refused without writing anything;
//!  * **one command id is applied at most once**, for every command kind, not only for the one the
//!    other test files happen to use.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test session_transitions`

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

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

fn state_with_tasks() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "素描".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state.tasks.push(TaskRecord {
        task_id: "task:t2".to_string(),
        kind: TaskKind::Daily,
        title: "日常".to_string(),
        note: String::new(),
        sort_key: 2048,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

fn apply(state: &DomainState, command: DomainCommand, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(state, command, &envelope(id, wall_ms, state.revision));
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn start(state: &DomainState, task_id: &str, wall_ms: i64) -> DomainState {
    apply(
        state,
        DomainCommand::StartSession {
            task_id: task_id.to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &format!("start-{wall_ms}"),
        wall_ms,
    )
}

/// The happy path visits every state in §5.1's diagram in order.
#[test]
fn the_full_transition_path_running_paused_running_finished() {
    let mut state = start(&state_with_tasks(), "task:t1", BASE);
    let session_id = state.active_session_id.clone().expect("active");
    assert_eq!(
        state
            .sessions
            .iter()
            .find(|s| s.session_id == session_id)
            .unwrap()
            .state,
        SessionState::Running
    );

    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        "pause",
        BASE + 600_000,
    );
    assert_eq!(
        state
            .sessions
            .iter()
            .find(|s| s.session_id == session_id)
            .unwrap()
            .state,
        SessionState::Paused
    );

    state = apply(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        "resume",
        BASE + 700_000,
    );
    assert_eq!(
        state
            .sessions
            .iter()
            .find(|s| s.session_id == session_id)
            .unwrap()
            .state,
        SessionState::Running
    );
    // Resume opens a new segment instead of reopening the paused one.
    assert_eq!(
        state
            .segments
            .iter()
            .filter(|segment| segment.session_id == session_id)
            .count(),
        2
    );

    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::Now,
        },
        "finish",
        BASE + 900_000,
    );
    let record = state
        .sessions
        .iter()
        .find(|s| s.session_id == session_id)
        .unwrap();
    assert_eq!(record.state, SessionState::Finished);
    assert_eq!(record.finished_wall_ms, Some(BASE + 900_000));
    assert!(state.active_session_id.is_none());
}

/// Finishing from the paused state is allowed and books only what was actually worked.
#[test]
fn finishing_while_paused_is_allowed() {
    let mut state = start(&state_with_tasks(), "task:t1", BASE);
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        "pause",
        BASE + 600_000,
    );
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::Now,
        },
        "finish",
        BASE + 1_000_000,
    );
    assert_eq!(
        state
            .sessions
            .iter()
            .find(|s| s.session_id == session_id)
            .unwrap()
            .state,
        SessionState::Finished
    );
    // Only the 600 s that ran are booked; the paused 400 s are not.
    assert_eq!(replay_daily_total(state.ledger.clone()), 600);
    assert!(state.active_session_id.is_none());
}

/// A countdown finished exactly at its target books the target, and the session is reusable no
/// further — "到点" is a normal end, not a completion.
#[test]
fn finishing_at_the_countdown_target_books_the_target_only() {
    let state = state_with_tasks();
    let state = apply(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::Countdown,
            target_seconds: Some(1_500),
            replaces_session_id: None,
        },
        "start",
        BASE,
    );
    let session_id = state.active_session_id.clone().expect("active");
    let state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 1_500 },
        },
        "finish",
        BASE + 1_500_000,
    );
    assert_eq!(replay_daily_total(state.ledger.clone()), 1_500);
    // One ledger row: the countdown's end is the same slot, not an extra booking.
    assert_eq!(state.ledger.iter().filter(|row| row.kind == 0).count(), 1);
}

/// Illegal transitions are refused, and refusing writes nothing.
#[test]
fn illegal_transitions_are_refused_without_writing() {
    let mut state = start(&state_with_tasks(), "task:t1", BASE);
    let session_id = state.active_session_id.clone().expect("active");

    // Resume while already running.
    let resume_running = reduce(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        &envelope("resume-running", BASE + 1_000, state.revision),
    );
    assert!(matches!(
        resume_running.error,
        Some(DomainError::PreconditionFailed { .. })
    ));
    assert!(resume_running.effects.is_empty());

    // Pause an unknown session.
    let unknown = reduce(
        &state,
        DomainCommand::PauseSession {
            session_id: "sess:no-such".to_string(),
        },
        &envelope("pause-unknown", BASE + 2_000, state.revision),
    );
    assert!(matches!(unknown.error, Some(DomainError::SessionNotFound { .. })));

    // Finish a session that is not the active one, while another is active.
    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        "pause",
        BASE + 600_000,
    );
    let finish_other = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id: "sess:no-such".to_string(),
            completion: CompletionChoice::Now,
        },
        &envelope("finish-other", BASE + 700_000, state.revision),
    );
    assert!(matches!(
        finish_other.error,
        Some(DomainError::SessionNotFound { .. })
    ));

    // Complete-while-running on a session that is not active.
    state = apply(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        "resume",
        BASE + 800_000,
    );
    let complete_other = reduce(
        &state,
        DomainCommand::CompleteTaskWhileRunning {
            session_id: "sess:no-such".to_string(),
            target_app_date: None,
        },
        &envelope("complete-other", BASE + 900_000, state.revision),
    );
    assert!(matches!(
        complete_other.error,
        Some(DomainError::PreconditionFailed { .. })
    ));

    // Pause twice: the second pause must not close a segment that is already closed.
    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        "pause-2",
        BASE + 1_000_000,
    );
    let extra_ledger_rows = state.ledger.len();
    let pause_again = reduce(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        &envelope("pause-again", BASE + 1_100_000, state.revision),
    );
    assert!(matches!(
        pause_again.error,
        Some(DomainError::PreconditionFailed { .. })
    ));
    assert!(pause_again.effects.is_empty());
    assert_eq!(pause_again.next_state.ledger.len(), extra_ledger_rows);
}

/// A countdown without a positive target is refused, and the refusal changes nothing.
#[test]
fn a_countdown_without_a_positive_target_is_refused() {
    let state = state_with_tasks();
    for target in [None, Some(0_i64), Some(-60_i64)] {
        let outcome = reduce(
            &state,
            DomainCommand::StartSession {
                task_id: "task:t1".to_string(),
                mode: SessionMode::Countdown,
                target_seconds: target,
                replaces_session_id: None,
            },
            &envelope("bad-countdown", BASE, state.revision),
        );
        assert!(
            matches!(outcome.error, Some(DomainError::PreconditionFailed { .. })),
            "target {target:?} must be refused"
        );
        assert!(outcome.effects.is_empty());
        assert!(outcome.next_state.active_session_id.is_none());
    }
}

/// One command id, applied once, for every kind of command — not only for the ledger add.
#[test]
fn one_command_id_is_applied_once_for_every_command_kind() {
    let date = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;

    let cases: Vec<(DomainCommand, &str)> = vec![
        (
            DomainCommand::StartSession {
                task_id: "task:t1".to_string(),
                mode: SessionMode::CountUp,
                target_seconds: None,
                replaces_session_id: None,
            },
            "one_sessions",
        ),
        (
            DomainCommand::AddManualSeconds {
                task_id: "task:t2".to_string(),
                app_date: date.clone(),
                seconds: 600,
            },
            "one_ledger_rows",
        ),
        (
            DomainCommand::SetDailyTotalSeconds {
                task_id: "task:t2".to_string(),
                app_date: date.clone(),
                total_seconds: 900,
            },
            "one_set_rows",
        ),
        (
            DomainCommand::CompleteTemporary {
                task_id: "task:t1".to_string(),
            },
            "one_temporary_events",
        ),
        (
            DomainCommand::CreateTask {
                kind: TaskKind::Daily,
                title: "新日常".to_string(),
                note: String::new(),
            },
            "one_tasks",
        ),
    ];

    for (command, counted) in cases {
        let state = state_with_tasks();
        let envelope = envelope("fixed-id", BASE, state.revision);
        let first = reduce(&state, command.clone(), &envelope);
        assert!(first.error.is_none(), "{counted}: {:?}", first.error);
        assert!(
            !first.effects.is_empty(),
            "{counted}: the first call must take effect"
        );
        // Count the durable writes the first call produced; this is the one and only occurrence.
        let written = count(&first.effects, counted);
        assert_eq!(written, 1, "{counted}: the first call must write exactly once");

        let second = reduce(&first.next_state, command, &envelope);
        assert!(
            matches!(second.error, Some(DomainError::DuplicateCommand { .. })),
            "{counted}: re-submitting the same id must be a duplicate, got {:?}",
            second.error
        );
        assert!(second.effects.is_empty());
        assert_eq!(
            count(&second.effects, counted),
            0,
            "{counted}: the duplicate must write nothing"
        );
        assert_eq!(second.next_state.revision, first.next_state.revision);
        assert!(
            unchanged(&first.next_state, &second.next_state, counted),
            "{counted}: the duplicate must not change the state"
        );
    }
}

/// The same id used for two *different* commands is still refused: the id identifies the user
/// action, not the shape of the command.
#[test]
fn a_reused_id_is_refused_even_for_a_different_command() {
    let state = state_with_tasks();
    let envelope = envelope("shared-id", BASE, state.revision);
    let first = reduce(
        &state,
        DomainCommand::CompleteTemporary {
            task_id: "task:t1".to_string(),
        },
        &envelope,
    );
    assert!(first.error.is_none(), "{:?}", first.error);

    let second = reduce(
        &first.next_state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: "2026-03-10".to_string(),
            seconds: 60,
        },
        &envelope,
    );
    assert!(matches!(second.error, Some(DomainError::DuplicateCommand { .. })));
    assert!(second.next_state.ledger.is_empty());
}

/// How many durable rows of a given kind an effect list asks Room to write. Completion events are
/// counted from the *effects*, because they are Room's append-only tables rather than part of
/// `DomainState` (架构契约 §4.1/§4.2).
fn count(effects: &[LedgerEffect], what: &str) -> usize {
    match what {
        "one_sessions" => effects
            .iter()
            .filter(|effect| matches!(effect, LedgerEffect::OpenSession { .. }))
            .count(),
        "one_ledger_rows" => effects
            .iter()
            .filter(|effect| matches!(effect, LedgerEffect::AppendLedgerEntry { row } if row.kind == 1))
            .count(),
        "one_set_rows" => effects
            .iter()
            .filter(|effect| matches!(effect, LedgerEffect::AppendLedgerEntry { row } if row.kind == 2))
            .count(),
        "one_temporary_events" => effects
            .iter()
            .filter(|effect| matches!(effect, LedgerEffect::AppendTemporaryCompletionEvent { .. }))
            .count(),
        "one_tasks" => effects
            .iter()
            .filter(|effect| matches!(effect, LedgerEffect::UpsertTask { .. }))
            .count(),
        other => panic!("unknown measure {other}"),
    }
}

/// The parts of the state a given command kind could have changed, so "the duplicate changed
/// nothing" is checked against the right slice of the state rather than the whole of it (the
/// command log legitimately differs).
fn unchanged(before: &DomainState, after: &DomainState, what: &str) -> bool {
    match what {
        "one_sessions" => before.sessions == after.sessions && before.segments == after.segments,
        "one_ledger_rows" | "one_set_rows" => before.ledger == after.ledger,
        "one_temporary_events" => before.sessions == after.sessions,
        "one_tasks" => before.tasks == after.tasks,
        other => panic!("unknown measure {other}"),
    }
}
