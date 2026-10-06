//! AC-12 item 6: the day total, the week/month trend, the per-task share and the heat map all come
//! out of *one* aggregation. This file asserts that at the command level — the same ledger, read
//! through every projector, gives the same numbers — and pins the ask's headline case
//! `20 + 30 -> set 35 -> +10 = 45`.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test stats_consistency`

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

fn with_task(task_id: &str, title: &str) -> TaskRecord {
    TaskRecord {
        task_id: task_id.to_string(),
        kind: TaskKind::Temporary,
        title: title.to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    }
}

fn state_with(tasks: &[(&str, &str)]) -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    for (task_id, title) in tasks {
        state.tasks.push(with_task(task_id, title));
    }
    state
}

fn apply(state: &DomainState, command: DomainCommand, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(state, command, &envelope(id, wall_ms, state.revision));
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

/// The ask's headline case: minutes 20, 30, set 35, then 10 more -> trace 20/50/35/45, total 45.
#[test]
fn twenty_then_thirty_then_set_thirty_five_then_ten_is_forty_five() {
    let date = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut state = state_with(&[("task:t1", "木刻")]);

    let plan: [(i64, DomainCommand); 4] = [
        (
            1_000,
            DomainCommand::AddManualSeconds {
                task_id: "task:t1".to_string(),
                app_date: date.clone(),
                seconds: 20 * 60,
            },
        ),
        (
            2_000,
            DomainCommand::AddManualSeconds {
                task_id: "task:t1".to_string(),
                app_date: date.clone(),
                seconds: 30 * 60,
            },
        ),
        (
            3_000,
            DomainCommand::SetDailyTotalSeconds {
                task_id: "task:t1".to_string(),
                app_date: date.clone(),
                total_seconds: 35 * 60,
            },
        ),
        (
            4_000,
            DomainCommand::AddManualSeconds {
                task_id: "task:t1".to_string(),
                app_date: date.clone(),
                seconds: 10 * 60,
            },
        ),
    ];
    for (offset, command) in plan {
        state = apply(&state, command, &format!("c{offset}"), BASE + offset);
    }

    let rows: Vec<LedgerRow> = state
        .ledger
        .iter()
        .filter(|row| row.task_id == "task:t1" && row.app_date == date)
        .cloned()
        .collect();
    assert_eq!(
        replay_daily_trace(rows.clone()),
        vec![20 * 60, 50 * 60, 35 * 60, 45 * 60],
        "20 -> 50 -> 35 -> 45"
    );
    assert_eq!(replay_daily_total(rows.clone()), 45 * 60);
}

/// All four readings of the same ledger agree, for a two-task, two-day history.
#[test]
fn every_projection_reads_the_same_aggregation() {
    let day_one = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut state = state_with(&[("task:t1", "速写"), ("task:t2", "版画")]);

    // task:t1 on day one: 20 + 30 -> set 35 -> +10 = 45 minutes.
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: day_one.clone(),
            seconds: 1_200,
        },
        "m1",
        BASE + 1_000,
    );
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: day_one.clone(),
            seconds: 1_800,
        },
        "m2",
        BASE + 2_000,
    );
    state = apply(
        &state,
        DomainCommand::SetDailyTotalSeconds {
            task_id: "task:t1".to_string(),
            app_date: day_one.clone(),
            total_seconds: 2_100,
        },
        "s1",
        BASE + 3_000,
    );
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: day_one.clone(),
            seconds: 600,
        },
        "m3",
        BASE + 4_000,
    );
    // task:t2 on a later day: two 15-minute blocks, one of which is then deleted.
    let day_two = day_label_of(BASE + 3 * 86_400_000, ZONE.to_string(), 1).expect("date");
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t2".to_string(),
            app_date: day_two.clone(),
            seconds: 900,
        },
        "m4",
        BASE + 5_000,
    );
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t2".to_string(),
            app_date: day_two.clone(),
            seconds: 900,
        },
        "m5",
        BASE + 6_000,
    );
    let doomed = state
        .ledger
        .iter()
        .find(|row| row.task_id == "task:t2")
        .expect("row")
        .ledger_seq;
    state = apply(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: doomed },
        "del",
        BASE + 7_000,
    );

    let rows = state.ledger.clone();
    let days = project_day_totals(rows.clone());
    let day_map: std::collections::BTreeMap<&str, i64> = days
        .iter()
        .map(|total| (total.app_date.as_str(), total.seconds))
        .collect();
    assert_eq!(day_map.get(day_one.as_str()), Some(&2_700));
    assert_eq!(day_map.get(day_two.as_str()), Some(&900));

    // 1. Calendar / heat map: the day totals themselves.
    assert_eq!(days.iter().map(|total| total.seconds).sum::<i64>(), 3_600);

    // 2. Trend: every bucket is the sum of the day totals it contains.
    for bucket in [TimeBucket::Day, TimeBucket::Week, TimeBucket::Month] {
        let periods = project_period_totals(rows.clone(), bucket).expect("periods");
        assert_eq!(
            periods.iter().map(|period| period.seconds).sum::<i64>(),
            3_600,
            "{bucket:?}"
        );
    }
    // The two days fall in the same week? Then the week must be one bucket; otherwise two.
    let weeks = project_period_totals(rows.clone(), TimeBucket::Week).expect("weeks");
    let week_total: i64 = weeks.iter().map(|week| week.seconds).sum();
    assert_eq!(week_total, 3_600);

    // 3. Per-task shares sum to the same grand total.
    let shares = project_task_shares(rows.clone());
    let share_map: std::collections::BTreeMap<&str, i64> = shares
        .iter()
        .map(|share| (share.task_id.as_str(), share.seconds))
        .collect();
    assert_eq!(share_map.get("task:t1"), Some(&2_700));
    assert_eq!(share_map.get("task:t2"), Some(&900));
    assert_eq!(shares.iter().map(|share| share.seconds).sum::<i64>(), 3_600);

    // ... and the deleted row is excluded everywhere, not just from the day view.
    assert_eq!(
        rows.iter().filter(|row| row.is_deleted).count(),
        1,
        "the ledger still holds the deleted row"
    );
    assert_eq!(
        weeks.iter().map(|week| week.seconds).sum::<i64>(),
        days.iter().map(|total| total.seconds).sum::<i64>(),
        "trend and heat map must not diverge on the deleted row"
    );
}

/// Both timing modes feed one shared ledger and therefore one total for the day (AC-12 / N10).
#[test]
fn count_up_and_countdown_land_in_one_daily_total() {
    let date = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut state = state_with(&[("task:t1", "混合计时")]);

    for (index, (mode, target, seconds)) in [
        (SessionMode::CountUp, None, 600_i64),
        (SessionMode::Countdown, Some(1_500_i64), 300_i64),
    ]
    .into_iter()
    .enumerate()
    {
        let start_at = BASE + index as i64 * 1_000_000;
        let mut start_envelope = envelope(&format!("start-{index}"), start_at, state.revision);
        start_envelope.expected_revision = state.revision;
        let started = reduce(
            &state,
            DomainCommand::StartSession {
                task_id: "task:t1".to_string(),
                mode,
                target_seconds: target,
                replaces_session_id: None,
            },
            &start_envelope,
        );
        assert!(started.error.is_none(), "{:?}", started.error);
        state = started.next_state;
        let session_id = state.active_session_id.clone().expect("active");
        let mut finish_envelope = envelope(&format!("finish-{index}"), start_at + 900_000, state.revision);
        finish_envelope.expected_revision = state.revision;
        let finished = reduce(
            &state,
            DomainCommand::FinishSession {
                session_id,
                completion: CompletionChoice::AfterSeconds { seconds },
            },
            &finish_envelope,
        );
        assert!(finished.error.is_none(), "{:?}", finished.error);
        state = finished.next_state;
    }
    assert_eq!(state.sessions.len(), 2);
    assert_ne!(state.sessions[0].mode, state.sessions[1].mode);

    let totals = project_day_totals(state.ledger.clone());
    assert_eq!(totals.len(), 1, "one task, one day, one number");
    assert_eq!(totals[0].app_date, date);
    assert_eq!(totals[0].seconds, 900);

    let shares = project_task_shares(state.ledger.clone());
    assert_eq!(shares.len(), 1);
    assert_eq!(shares[0].seconds, 900);

    // Each session's own effective time is also right, and the two sum to the day.
    let mut sum_of_sessions = 0;
    for session in &state.sessions {
        let segments: Vec<WorkSegment> = state
            .segments
            .iter()
            .filter(|segment| segment.session_id == session.session_id)
            .cloned()
            .collect();
        let effective = effective_seconds(
            session.session_id.clone(),
            segments,
            session.finished_wall_ms.unwrap_or(BASE),
        );
        sum_of_sessions += effective;
    }
    assert_eq!(sum_of_sessions, totals[0].seconds);
}

/// A set-total is an absolute value for one task-day, so a manual add afterwards accumulates on top
/// of it — and the *day total* keeps both tasks separate.
#[test]
fn a_set_total_is_scoped_to_its_own_task_day() {
    let day = app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso;
    let mut state = state_with(&[("task:t1", "A"), ("task:t2", "B")]);
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: day.clone(),
            seconds: 1_200,
        },
        "m1",
        BASE + 1_000,
    );
    state = apply(
        &state,
        DomainCommand::SetDailyTotalSeconds {
            task_id: "task:t1".to_string(),
            app_date: day.clone(),
            total_seconds: 300,
        },
        "s1",
        BASE + 2_000,
    );
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t2".to_string(),
            app_date: day.clone(),
            seconds: 900,
        },
        "m2",
        BASE + 3_000,
    );

    let shares = project_task_shares(state.ledger.clone());
    let by_task: std::collections::BTreeMap<&str, i64> = shares
        .iter()
        .map(|share| (share.task_id.as_str(), share.seconds))
        .collect();
    assert_eq!(by_task.get("task:t1"), Some(&300), "the set pins task t1");
    assert_eq!(by_task.get("task:t2"), Some(&900), "task t2 is untouched");
    let totals = project_day_totals(state.ledger.clone());
    assert_eq!(totals[0].seconds, 1_200, "the day is the sum of the two tasks");
}

/// An empty ledger projects to empty vectors rather than erroring, so the UI's empty state is a
/// normal value (AC-18's data side).
#[test]
fn empty_ledger_projects_to_empty() {
    assert!(project_day_totals(Vec::new()).is_empty());
    assert!(project_task_shares(Vec::new()).is_empty());
    for bucket in [TimeBucket::Day, TimeBucket::Week, TimeBucket::Month] {
        assert!(project_period_totals(Vec::new(), bucket).expect("ok").is_empty());
    }
}
