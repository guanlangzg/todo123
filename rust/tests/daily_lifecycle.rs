//! AC-01 / AC-03 / AC-10: the daily lifecycle.
//!
//! "Every day the dailies come back" is *lazy*: nothing is generated until a day is actually asked
//! for, generating the same day again is a no-op, yesterday's unfinished day never piles into
//! today, and an archived task resumes under its original id with its cumulative time intact.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test daily_lifecycle`

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

/// Three consecutive civil days, taken from the domain core so the test derives nothing itself.
fn three_days() -> Vec<String> {
    let first = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut days = vec![first.clone()];
    let mut cursor = first;
    for _ in 0..2 {
        let window = day_window(cursor.clone(), ZONE.to_string(), 1).expect("window");
        let next = day_label_of(window.end_wall_ms, ZONE.to_string(), 1).expect("next day");
        days.push(next.clone());
        cursor = next;
    }
    days
}

fn create_daily(state: &DomainState, title: &str, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        DomainCommand::CreateTask {
            kind: TaskKind::Daily,
            title: title.to_string(),
            note: String::new(),
        },
        &envelope(id, wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn ensure(state: &DomainState, task_id: &str, app_date: &str, id: &str, wall_ms: i64) -> DomainOutcome {
    reduce(
        state,
        DomainCommand::EnsureOccurrence {
            task_id: task_id.to_string(),
            app_date: app_date.to_string(),
        },
        &envelope(id, wall_ms, state.revision),
    )
}

/// Nothing is generated ahead of time: a freshly created daily has no instance until a day is asked
/// for.
#[test]
fn generation_is_lazy() {
    let state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    assert!(
        state.occurrences.is_empty(),
        "creating a daily must not pre-generate its instances"
    );
    let days = three_days();
    let outcome = ensure(&state, &state.tasks[0].task_id, &days[0], "ensure", BASE + 1_000);
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    assert_eq!(outcome.next_state.occurrences.len(), 1);
    // Still only the day that was asked for.
    assert_eq!(outcome.next_state.occurrences[0].app_date, days[0]);
}

/// Re-asking for the same day three times leaves exactly one instance behind.
#[test]
fn regeneration_is_idempotent_over_three_calls() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();

    for step in 0..3 {
        let outcome = ensure(
            &state,
            &task_id,
            &days[0],
            &format!("ensure-{step}"),
            BASE + step * 1_000,
        );
        assert!(outcome.error.is_none(), "{:?}", outcome.error);
        state = outcome.next_state;
    }
    assert_eq!(state.occurrences.len(), 1);
    let id_after_first = state.occurrences[0].occurrence_id.clone();

    // Two more days: one instance each, distinct ids, and the first day's id is untouched.
    for (index, day) in days[1..].iter().enumerate() {
        state = ensure(
            &state,
            &task_id,
            day,
            &format!("ensure-day-{index}"),
            BASE + 10_000 + index as i64 * 1_000,
        )
        .next_state;
    }
    assert_eq!(state.occurrences.len(), 3);
    assert_eq!(state.occurrences[0].occurrence_id, id_after_first);
    let ids: std::collections::BTreeSet<&str> = state
        .occurrences
        .iter()
        .map(|item| item.occurrence_id.as_str())
        .collect();
    assert_eq!(ids.len(), 3);
}

/// Yesterday left unfinished does not pile into today: today starts pending.
#[test]
fn a_missed_day_does_not_pile_into_today() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();

    // Yesterday is generated and left pending — "missed" but not completed.
    state = ensure(&state, &task_id, &days[0], "ensure-yesterday", BASE + 1_000).next_state;
    assert!(!state.occurrences[0].is_completed);

    state = ensure(&state, &task_id, &days[1], "ensure-today", BASE + 2_000).next_state;
    let today = state
        .occurrences
        .iter()
        .find(|item| item.app_date == days[1])
        .expect("today");
    assert!(
        !today.is_completed,
        "today must start pending regardless of yesterday"
    );
    assert_eq!(
        state.occurrences.len(),
        2,
        "no carry-over row, no synthetic missed day"
    );
}

/// Completing yesterday does not complete today — the completion is per day.
#[test]
fn completing_yesterday_does_not_complete_today() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();

    for (index, day) in days[0..2].iter().enumerate() {
        state = ensure(
            &state,
            &task_id,
            day,
            &format!("ensure-{index}"),
            BASE + index as i64 * 1_000,
        )
        .next_state;
    }

    state = reduce(
        &state,
        DomainCommand::SetOccurrenceCompletion {
            task_id: task_id.clone(),
            app_date: days[0].clone(),
            completed: true,
        },
        &envelope("complete-yesterday", BASE + 5_000, state.revision),
    )
    .next_state;

    let yesterday = state.occurrences.iter().find(|i| i.app_date == days[0]).unwrap();
    let today = state.occurrences.iter().find(|i| i.app_date == days[1]).unwrap();
    assert!(yesterday.is_completed);
    assert!(!today.is_completed, "completing yesterday must not touch today");
    assert_eq!(yesterday.derived_from_event_high_water, 1);
    assert_eq!(today.derived_from_event_high_water, 0);

    // Each instance owns its own event stream.
    let events: Vec<OccurrenceCompletionEvent> = state
        .completion_event_high_water
        .iter()
        .map(|entry| OccurrenceCompletionEvent {
            event_seq: entry.high_water,
            occurrence_id: entry.occurrence_id.clone(),
            action: 0,
            occurred_wall_ms: BASE + 5_000,
            app_date: String::new(),
            zone_epoch_seq: 1,
        })
        .collect();
    assert_eq!(events.len(), 1);
}

/// Undoing yesterday's completion must not disturb today either.
#[test]
fn undoing_yesterday_leaves_today_alone() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();
    for (index, day) in days[0..2].iter().enumerate() {
        state = ensure(
            &state,
            &task_id,
            day,
            &format!("ensure-{index}"),
            BASE + index as i64 * 1_000,
        )
        .next_state;
    }
    for completed in [true, false] {
        state = reduce(
            &state,
            DomainCommand::SetOccurrenceCompletion {
                task_id: task_id.clone(),
                app_date: days[0].clone(),
                completed,
            },
            &envelope(&format!("set-{completed}"), BASE + 5_000, state.revision),
        )
        .next_state;
    }
    let yesterday = state.occurrences.iter().find(|i| i.app_date == days[0]).unwrap();
    let today = state.occurrences.iter().find(|i| i.app_date == days[1]).unwrap();
    assert!(!yesterday.is_completed);
    assert!(!today.is_completed);
    assert_eq!(
        yesterday.derived_from_event_high_water, 2,
        "two events, both replayed"
    );
    assert_eq!(today.derived_from_event_high_water, 0);
}

/// An archived task generates nothing and therefore creates no missed day; unarchiving resumes the
/// original task id and the cumulative investment continues.
#[test]
fn archive_then_unarchive_resumes_the_same_identity() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();

    state = reduce(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: task_id.clone(),
            app_date: days[0].clone(),
            seconds: 1_200,
        },
        &envelope("manual", BASE + 1_000, state.revision),
    )
    .next_state;
    let invested_before = replay_daily_total(state.ledger.clone());
    assert_eq!(invested_before, 1_200);

    // Archive, then try to generate during the archive window: nothing happens, no missed day.
    state = reduce(
        &state,
        DomainCommand::ArchiveTask {
            task_id: task_id.clone(),
        },
        &envelope("archive", BASE + 2_000, state.revision),
    )
    .next_state;
    let during_archive = ensure(&state, &task_id, &days[1], "ensure-archived", BASE + 3_000);
    assert!(during_archive.error.is_none(), "{:?}", during_archive.error);
    assert!(during_archive.next_state.occurrences.is_empty());
    assert!(during_archive
        .notices
        .iter()
        .any(|notice| notice.code == "ArchivedNoOccurrence"));
    state = during_archive.next_state;

    // Unarchive on the same day: the instance for that date is generated under the same task id.
    state = reduce(
        &state,
        DomainCommand::UnarchiveTask {
            task_id: task_id.clone(),
        },
        &envelope("unarchive", BASE + 4_000, state.revision),
    )
    .next_state;
    state = ensure(&state, &task_id, &days[1], "ensure-resumed", BASE + 5_000).next_state;

    assert_eq!(state.tasks.len(), 1, "the task id is preserved, not recreated");
    assert_eq!(state.tasks[0].task_id, task_id);
    assert_eq!(state.occurrences.len(), 1);
    assert_eq!(state.occurrences[0].task_id, task_id);
    assert_eq!(state.occurrences[0].app_date, days[1]);
    // The investment made before archiving is still there, under the same task id.
    assert_eq!(replay_daily_total(state.ledger.clone()), invested_before);
}

/// A brand-new task is not back-filled: creating a daily today leaves earlier days untouched.
#[test]
fn a_new_daily_is_not_back_filled() {
    let mut state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let task_id = state.tasks[0].task_id.clone();
    let days = three_days();
    state = ensure(&state, &task_id, &days[0], "ensure-day0", BASE + 1_000).next_state;
    let before = state.occurrences.len();

    state = create_daily(&state, "第二个日常", "create-2", BASE + 2_000);
    let second = state
        .tasks
        .iter()
        .find(|task| task.task_id != task_id)
        .expect("second task")
        .task_id
        .clone();
    assert_eq!(
        state.occurrences.len(),
        before,
        "creating a daily must not back-fill earlier days for it"
    );
    // Only an explicit request generates its instance for a given day.
    state = ensure(&state, &second, &days[0], "ensure-second", BASE + 3_000).next_state;
    assert_eq!(state.occurrences.len(), before + 1);
    assert_eq!(
        state
            .occurrences
            .iter()
            .filter(|item| item.task_id == second)
            .count(),
        1
    );
}

/// Completion before generation is refused rather than silently creating a day that was never asked
/// for.
#[test]
fn completing_an_ungenerated_day_is_refused() {
    let state = create_daily(&initial_state(ZONE, BASE), "晨间速写", "create", BASE);
    let days = three_days();
    let outcome = reduce(
        &state,
        DomainCommand::SetOccurrenceCompletion {
            task_id: state.tasks[0].task_id.clone(),
            app_date: days[0].clone(),
            completed: true,
        },
        &envelope("complete-ungenerated", BASE + 1_000, state.revision),
    );
    assert!(matches!(
        outcome.error,
        Some(DomainError::OccurrenceNotFound { .. })
    ));
    assert!(outcome.effects.is_empty());
    assert!(outcome.next_state.occurrences.is_empty());
}
