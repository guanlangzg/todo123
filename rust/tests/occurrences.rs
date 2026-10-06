//! AC-01 / AC-10 / AC-15: lazy daily occurrence generation, archiving, zone epochs.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test occurrences`

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

fn clock(wall_ms: i64, zone: &str) -> ClockSample {
    ClockSample {
        wall_ms,
        zone_id: zone.to_string(),
        elapsed_ms: 0,
        boot_tag: String::new(),
    }
}

fn envelope(id: &str, wall_ms: i64, zone: &str, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: clock(wall_ms, zone),
        expected_revision: revision,
    }
}

fn create_daily(state: &DomainState, zone: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        DomainCommand::CreateTask {
            kind: TaskKind::Daily,
            title: "晨间速写".to_string(),
            note: String::new(),
        },
        &envelope(&format!("create-{wall_ms}"), wall_ms, zone, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

/// AC-01: three `EnsureOccurrence` calls for one (task, date) leave exactly one occurrence.
#[test]
fn ensure_occurrence_is_idempotent() {
    let zone = "Asia/Shanghai";
    let base = 1_772_000_000_000_i64;
    let mut state = create_daily(&initial_state(zone, base), zone, base);
    let task_id = state.tasks[0].task_id.clone();
    let app_date = app_date_of(base, zone.to_string(), 1).expect("app date").iso;

    for step in 0..3 {
        let outcome = reduce(
            &state,
            DomainCommand::EnsureOccurrence {
                task_id: task_id.clone(),
                app_date: app_date.clone(),
            },
            &envelope(
                &format!("ensure-{step}"),
                base + step * 1000,
                zone,
                state.revision,
            ),
        );
        assert!(outcome.error.is_none(), "{:?}", outcome.error);
        state = outcome.next_state;
    }

    let matching: Vec<_> = state
        .occurrences
        .iter()
        .filter(|item| item.task_id == task_id)
        .collect();
    assert_eq!(matching.len(), 1, "occurrence must be reused, not duplicated");
    assert_eq!(matching[0].app_date, app_date);
    assert!(!matching[0].is_completed);
}

/// AC-10: an archived task produces no occurrence and therefore no missed work.
#[test]
fn archived_task_creates_no_occurrence() {
    let zone = "Asia/Shanghai";
    let base = 1_772_000_000_000_i64;
    let mut state = create_daily(&initial_state(zone, base), zone, base);
    let task_id = state.tasks[0].task_id.clone();
    let app_date = app_date_of(base, zone.to_string(), 1).expect("app date").iso;

    state = reduce(
        &state,
        DomainCommand::ArchiveTask {
            task_id: task_id.clone(),
        },
        &envelope("archive", base + 1000, zone, state.revision),
    )
    .next_state;

    let outcome = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: task_id.clone(),
            app_date: app_date.clone(),
        },
        &envelope("ensure-archived", base + 2000, zone, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    assert!(
        outcome.next_state.occurrences.is_empty(),
        "archived task must not generate"
    );
    assert!(outcome
        .notices
        .iter()
        .any(|notice| notice.code == "ArchivedNoOccurrence"));
}

/// AC-10: unarchiving on the same day reuses the existing occurrence identity.
#[test]
fn unarchive_on_the_same_day_reuses_the_same_occurrence() {
    let zone = "Asia/Shanghai";
    let base = 1_772_000_000_000_i64;
    let mut state = create_daily(&initial_state(zone, base), zone, base);
    let task_id = state.tasks[0].task_id.clone();
    let app_date = app_date_of(base, zone.to_string(), 1).expect("app date").iso;

    state = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: task_id.clone(),
            app_date: app_date.clone(),
        },
        &envelope("ensure-before", base + 1000, zone, state.revision),
    )
    .next_state;
    let original_id = state.occurrences[0].occurrence_id.clone();

    state = reduce(
        &state,
        DomainCommand::ArchiveTask {
            task_id: task_id.clone(),
        },
        &envelope("archive", base + 2000, zone, state.revision),
    )
    .next_state;
    state = reduce(
        &state,
        DomainCommand::UnarchiveTask {
            task_id: task_id.clone(),
        },
        &envelope("unarchive", base + 3000, zone, state.revision),
    )
    .next_state;
    state = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: task_id.clone(),
            app_date: app_date.clone(),
        },
        &envelope("ensure-after", base + 4000, zone, state.revision),
    )
    .next_state;

    assert_eq!(state.occurrences.len(), 1);
    assert_eq!(state.occurrences[0].occurrence_id, original_id);
}

/// AC-15: changing the device zone adds no zone epoch; only an explicit command does.
#[test]
fn system_zone_change_does_not_append_an_epoch() {
    let zone = "Asia/Shanghai";
    let base = 1_772_000_000_000_i64;
    let state = create_daily(&initial_state(zone, base), zone, base);
    // Kotlin keeps sending the same app zone; nothing in this path appends an epoch.
    let outcome = reduce(
        &state,
        DomainCommand::EnsureOccurrence {
            task_id: state.tasks[0].task_id.clone(),
            app_date: "2026-03-10".to_string(),
        },
        &envelope("ensure-zoned", base, zone, state.revision),
    );
    assert_eq!(outcome.next_state.zone_epochs.len(), 1);

    let appended = reduce(
        &state,
        DomainCommand::AppendZoneEpoch {
            zone_id: "Europe/Berlin".to_string(),
            impact_summary: "用户手动改区".to_string(),
        },
        &envelope("append-zone", base + 1000, zone, state.revision),
    );
    assert!(appended.error.is_none(), "{:?}", appended.error);
    assert_eq!(appended.next_state.zone_epochs.len(), 2);
    assert_eq!(appended.next_state.zone_epoch_seq, 2);
    assert_eq!(appended.next_state.zone_id, "Europe/Berlin");
}

/// AC-01: the same task on two different dates gets two distinct occurrences.
#[test]
fn distinct_dates_get_distinct_occurrences() {
    let zone = "Asia/Shanghai";
    let base = 1_772_000_000_000_i64;
    let mut state = create_daily(&initial_state(zone, base), zone, base);
    let task_id = state.tasks[0].task_id.clone();
    for (index, date) in ["2026-03-10", "2026-03-11"].iter().enumerate() {
        state = reduce(
            &state,
            DomainCommand::EnsureOccurrence {
                task_id: task_id.clone(),
                app_date: (*date).to_string(),
            },
            &envelope(
                &format!("ensure-{index}"),
                base + index as i64 * 1000,
                zone,
                state.revision,
            ),
        )
        .next_state;
    }
    assert_eq!(state.occurrences.len(), 2);
    assert_ne!(
        state.occurrences[0].occurrence_id,
        state.occurrences[1].occurrence_id
    );
}

/// A bad date or zone must come back as a `DomainError`, never as a panic (架构契约 §10).
#[test]
fn invalid_inputs_return_errors_instead_of_panicking() {
    let state = initial_state("Asia/Shanghai", 0);
    let bad_date = app_date_of(0, "Asia/Shanghai".to_string(), 1);
    assert!(bad_date.is_ok());
    assert!(day_window("not-a-date".to_string(), "Asia/Shanghai".to_string(), 1).is_err());
    assert!(day_window("2026-03-10".to_string(), "Not/AZone".to_string(), 1).is_err());
    assert!(app_date_of(i64::MAX, "Asia/Shanghai".to_string(), 1).is_err());

    let outcome = reduce(
        &state,
        DomainCommand::CreateTask {
            kind: TaskKind::Daily,
            title: "  ".to_string(),
            note: String::new(),
        },
        &envelope("empty", 0, "Asia/Shanghai", 1),
    );
    assert!(matches!(outcome.error, Some(DomainError::EmptyTitle)));
}
