//! AC-04 (the `rust-domain` half): per-task countdown memory.
//!
//! The card remembers one countdown length *per task*; a new task starts at the default 25 minutes,
//! and `SetTaskRecentCountdown` is the only thing that changes it — and only within `1..=1440`
//! (架构契约 §3.4 `CountdownMinutesOutOfRange { min, max }`).
//!
//! The Kotlin half of AC-04 ("cancelling the mode sheet dispatches no command, so the remembered
//! minutes are not overwritten") is a *call discipline* on the UI side and is proven by the Kotlin
//! tests; what Rust owes the contract is that nothing else in the domain can move this value, which
//! is what the last test here pins.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test countdown`

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
/// 架构契约 §3.4 / 规格: the countdown input's allowed range and the new-task default.
const MIN_MINUTES: u32 = 1;
const MAX_MINUTES: u32 = 1440;
const DEFAULT_MINUTES: u32 = 25;

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

fn create(state: &DomainState, kind: TaskKind, title: &str, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        DomainCommand::CreateTask {
            kind,
            title: title.to_string(),
            note: String::new(),
        },
        &envelope(id, wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn minutes_of<'a>(state: &'a DomainState, task_id: &str) -> &'a u32 {
    &state
        .tasks
        .iter()
        .find(|task| task.task_id == task_id)
        .unwrap_or_else(|| panic!("task {task_id}"))
        .last_countdown_minutes
}

/// A brand-new task starts at the default; the value is a countdown memory, not a time goal.
#[test]
fn a_new_task_remembers_the_default_25_minutes() {
    let state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    assert_eq!(*minutes_of(&state, "task:create"), DEFAULT_MINUTES);
}

/// Setting the memory changes this task only; the other task keeps its own value.
#[test]
fn setting_the_countdown_only_touches_one_task() {
    let mut state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "A",
        "create-a",
        BASE,
    );
    state = create(&state, TaskKind::Temporary, "B", "create-b", BASE + 1_000);
    state = create(&state, TaskKind::Daily, "C", "create-c", BASE + 2_000);

    let outcome = reduce(
        &state,
        DomainCommand::SetTaskRecentCountdown {
            task_id: "task:create-a".to_string(),
            minutes: 35,
        },
        &envelope("set-35", BASE + 3_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    // Exactly one task row is rewritten.
    let writes = outcome
        .effects
        .iter()
        .filter(|effect| matches!(effect, LedgerEffect::UpsertTask { .. }))
        .count();
    assert_eq!(writes, 1);

    let state = outcome.next_state;
    assert_eq!(*minutes_of(&state, "task:create-a"), 35);
    assert_eq!(*minutes_of(&state, "task:create-b"), DEFAULT_MINUTES);
    assert_eq!(*minutes_of(&state, "task:create-c"), DEFAULT_MINUTES);
    // It is a memory, not a booking: no ledger row was created by changing it.
    assert!(state.ledger.is_empty());
}

/// The value survives later commands unchanged: it is only the user's chosen length.
#[test]
fn changing_the_countdown_twice_keeps_the_last_value() {
    let mut state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    for (index, minutes) in [35_u32, 5].into_iter().enumerate() {
        let outcome = reduce(
            &state,
            DomainCommand::SetTaskRecentCountdown {
                task_id: "task:create".to_string(),
                minutes,
            },
            &envelope(
                &format!("set-{index}"),
                BASE + 1_000 + index as i64 * 1_000,
                state.revision,
            ),
        );
        assert!(outcome.error.is_none(), "{:?}", outcome.error);
        state = outcome.next_state;
        assert_eq!(*minutes_of(&state, "task:create"), minutes);
    }
}

/// Out-of-range minutes are refused with the structured range error, and the refusal writes
/// nothing — including for the boundaries themselves.
#[test]
fn minutes_outside_the_allowed_range_are_refused() {
    let state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    let after_set = reduce(
        &state,
        DomainCommand::SetTaskRecentCountdown {
            task_id: "task:create".to_string(),
            minutes: 35,
        },
        &envelope("set-35", BASE + 1_000, state.revision),
    )
    .next_state;
    assert_eq!(*minutes_of(&after_set, "task:create"), 35);

    for minutes in [0_u32, MAX_MINUTES + 1, u32::MAX] {
        let outcome = reduce(
            &after_set,
            DomainCommand::SetTaskRecentCountdown {
                task_id: "task:create".to_string(),
                minutes,
            },
            &envelope(&format!("bad-{minutes}"), BASE + 2_000, after_set.revision),
        );
        assert!(
            matches!(
                outcome.error,
                Some(DomainError::CountdownMinutesOutOfRange { min, max })
                    if min == MIN_MINUTES && max == MAX_MINUTES
            ),
            "minutes {minutes} must be refused with the declared range, got {:?}",
            outcome.error
        );
        assert!(outcome.effects.is_empty(), "a refused setting must write nothing");
        assert_eq!(outcome.next_state.revision, after_set.revision);
        assert_eq!(
            *minutes_of(&outcome.next_state, "task:create"),
            35,
            "a refused setting must not disturb the remembered value"
        );
    }

    // Both boundaries are accepted.
    for minutes in [MIN_MINUTES, MAX_MINUTES] {
        let outcome = reduce(
            &after_set,
            DomainCommand::SetTaskRecentCountdown {
                task_id: "task:create".to_string(),
                minutes,
            },
            &envelope(
                &format!("ok-{minutes}"),
                BASE + 3_000 + minutes as i64,
                after_set.revision,
            ),
        );
        assert!(outcome.error.is_none(), "minutes {minutes}: {:?}", outcome.error);
    }
}

/// An unknown task is a `TaskNotFound`, not a silent row.
#[test]
fn an_unknown_task_cannot_set_a_countdown() {
    let state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    let outcome = reduce(
        &state,
        DomainCommand::SetTaskRecentCountdown {
            task_id: "task:nope".to_string(),
            minutes: 30,
        },
        &envelope("unknown", BASE + 1_000, state.revision),
    );
    assert!(matches!(outcome.error, Some(DomainError::TaskNotFound { .. })));
    assert!(outcome.effects.is_empty());
}

/// The countdown memory is moved by nothing else in the domain: opening, pausing, resuming and
/// finishing a timer on the task leaves it where the user put it. This is the Rust-side guarantee
/// behind AC-04's "cancelling the sheet does not overwrite the remembered minutes".
#[test]
fn running_a_timer_does_not_change_the_remembered_minutes() {
    let mut state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    state = reduce(
        &state,
        DomainCommand::SetTaskRecentCountdown {
            task_id: "task:create".to_string(),
            minutes: 40,
        },
        &envelope("set-40", BASE + 1_000, state.revision),
    )
    .next_state;
    assert_eq!(*minutes_of(&state, "task:create"), 40);

    // Starting a countdown with a *different* target must not overwrite the memory: the target is
    // the length of this one run, the memory is what the card offers next time.
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:create".to_string(),
            mode: SessionMode::Countdown,
            target_seconds: Some(600),
            replaces_session_id: None,
        },
        &envelope("session-start", BASE + 2_000, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    state = started.next_state;
    assert_eq!(*minutes_of(&state, "task:create"), 40);
    assert_eq!(state.sessions[0].target_seconds, Some(600));

    // Pausing, resuming and finishing likewise leave the memory alone.
    let session_id = state.active_session_id.clone().expect("active session");
    let steps: Vec<(DomainCommand, i64)> = vec![
        (
            DomainCommand::PauseSession {
                session_id: session_id.clone(),
            },
            BASE + 300_000,
        ),
        (
            DomainCommand::ResumeSession {
                session_id: session_id.clone(),
            },
            BASE + 400_000,
        ),
        (
            DomainCommand::FinishSession {
                session_id,
                completion: CompletionChoice::Now,
            },
            BASE + 900_000,
        ),
    ];
    for (index, (command, wall_ms)) in steps.into_iter().enumerate() {
        let outcome = reduce(
            &state,
            command,
            &envelope(&format!("session-{index}"), wall_ms, state.revision),
        );
        assert!(outcome.error.is_none(), "step {index}: {:?}", outcome.error);
        state = outcome.next_state;
        assert_eq!(
            *minutes_of(&state, "task:create"),
            40,
            "step {index} must not move the remembered minutes"
        );
    }
    assert!(state.active_session_id.is_none());
    // The run really was booked while the memory stayed put: the session opened at +2 s, paused at
    // +300 s (298 s worked), resumed at +400 s and finished at +900 s (500 s worked).
    assert_eq!(replay_daily_total(state.ledger.clone()), 298 + 500);
}

/// One user action, one command id: re-submitting the same setting is a duplicate.
#[test]
fn repeating_the_same_setting_id_is_a_duplicate() {
    let state = create(
        &initial_state(ZONE, BASE),
        TaskKind::Temporary,
        "速写",
        "create",
        BASE,
    );
    let envelope = envelope("set-once", BASE + 1_000, state.revision);
    let command = DomainCommand::SetTaskRecentCountdown {
        task_id: "task:create".to_string(),
        minutes: 30,
    };
    let first = reduce(&state, command.clone(), &envelope);
    assert!(first.error.is_none(), "{:?}", first.error);
    let second = reduce(&first.next_state, command, &envelope);
    assert!(matches!(second.error, Some(DomainError::DuplicateCommand { .. })));
    assert!(second.effects.is_empty());
    assert_eq!(second.next_state.tasks, first.next_state.tasks);
}
