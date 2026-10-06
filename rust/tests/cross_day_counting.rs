//! AC-08 / AC-15: a span that crosses midnight is split by the day windows, counted exactly once
//! in each day it touches, and never re-counted. The day length is whatever `day_window` says it is
//! (23 h / 24 h / 25 h) — never a hard-coded 86400.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test cross_day_counting`

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;

fn envelope(id: &str, wall_ms: i64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: String::new(),
        },
        expected_revision: 1,
    }
}

fn with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Daily,
        title: "夜景写生".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

/// Start before midnight, finish after it: the two days each get their own share, and the shares sum
/// to the work actually done.
#[test]
fn a_cross_midnight_span_is_counted_once_in_each_day() {
    let before = day_window("2026-03-09".to_string(), ZONE.to_string(), 1).expect("window");
    let after = day_window("2026-03-10".to_string(), ZONE.to_string(), 1).expect("window");

    let start = before.end_wall_ms - 10 * 60 * 1000; // 23:50
    let state = with_task();

    // The clock is injected, so the session is opened at 23:50 and finished at 00:20 by `AfterSeconds`.
    let mut envelope_at = envelope("start", start);
    envelope_at.expected_revision = state.revision;
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope_at,
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    let state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");

    let mut finish_at = envelope("finish", after.start_wall_ms + 20 * 60 * 1000);
    finish_at.expected_revision = state.revision;
    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 1800 },
        },
        &finish_at,
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    let state = finished.next_state;

    let per_day = project_day_totals(state.ledger.clone());
    let totals: Vec<(&str, i64)> = per_day
        .iter()
        .map(|total| (total.app_date.as_str(), total.seconds))
        .collect();
    assert_eq!(
        totals,
        vec![("2026-03-09", 600), ("2026-03-10", 1200)],
        "10 min before midnight, 20 min after it"
    );
    assert_eq!(
        per_day.iter().map(|total| total.seconds).sum::<i64>(),
        1800,
        "the two days together are exactly the interval"
    );
    assert_eq!(
        state.ledger.iter().filter(|row| row.kind == 0).count(),
        2,
        "one slice per day, not one per span"
    );
    // Distinct deterministic slice ids, so a replay cannot collide the two days.
    let ids: Vec<String> = state.ledger.iter().filter_map(|row| row.ref_id.clone()).collect();
    assert_eq!(ids.len(), 2);
    assert_ne!(ids[0], ids[1]);
}

/// A span that stays inside one day is one slice: splitting must not invent a boundary.
#[test]
fn a_same_day_span_stays_one_slice() {
    let window = day_window("2026-03-10".to_string(), ZONE.to_string(), 1).expect("window");
    let start = window.start_wall_ms + 3_600_000;
    let slices = slice_interval(
        "s".to_string(),
        1,
        start,
        start + 900_000,
        ZONE.to_string(),
        1,
        vec![TitleRevision {
            effective_wall_ms: start,
            title: "t".to_string(),
        }],
    )
    .expect("slices");
    assert_eq!(slices.len(), 1);
    assert_eq!(slices[0].app_date.iso, "2026-03-10");
}

/// Every instant belongs to exactly one day, and `SliceInterval` agrees with `DayWindow` about
/// which one. This is the §2.3 rule 4 correction: attribution follows the interval, not the label
/// that `app_date_of` would give for the same instant.
#[test]
fn instant_attribution_agrees_between_window_and_slice() {
    for zone in [
        "Asia/Shanghai",
        "America/Sao_Paulo",
        "America/Havana",
        "Europe/Berlin",
    ] {
        let window = day_window("2018-11-04".to_string(), zone.to_string(), 1).expect("window");
        let probes = [
            window.start_wall_ms,
            window.start_wall_ms + 1,
            window.start_wall_ms + (window.end_wall_ms - window.start_wall_ms) / 2,
            window.end_wall_ms - 1,
        ];
        for probe in probes {
            // A 1 ms interval: the probe must be attributed to the day it starts in, and a longer
            // interval straddling a boundary would (correctly) produce two slices.
            let slices = slice_interval("s".to_string(), 1, probe, probe + 1, zone.to_string(), 1, vec![])
                .expect("slices");
            assert_eq!(slices.len(), 1, "{zone}");
            assert_eq!(
                slices[0].app_date.iso, "2018-11-04",
                "{zone}: instant {probe} must be attributed to its window, not to a label"
            );
            assert!(probe >= slices[0].start_wall_ms && probe < slices[0].end_wall_ms);
        }
    }
}

/// The 23 h and 25 h days: the slice sum equals the day length the window reports.
#[test]
fn dst_days_slice_to_their_real_length() {
    let cases = [
        ("America/Sao_Paulo", "2018-11-04", 82_800_i64),
        ("America/Havana", "2018-11-04", 90_000_i64),
        ("America/Santiago", "2018-08-12", 82_800_i64),
        ("Asia/Beirut", "2018-03-25", 82_800_i64),
    ];
    for (zone, date, expected) in cases {
        let window = day_window(date.to_string(), zone.to_string(), 1).expect("window");
        assert_eq!(window.day_seconds, expected, "{zone} {date}");
        let slices = slice_interval(
            "s".to_string(),
            1,
            window.start_wall_ms,
            window.end_wall_ms,
            zone.to_string(),
            1,
            vec![],
        )
        .expect("slices");
        let total: i64 = slices
            .iter()
            .map(|slice| (slice.end_wall_ms - slice.start_wall_ms) / 1000)
            .sum();
        assert_eq!(total, window.day_seconds, "{zone} {date}");
    }
}

/// A span across a 25 h day is split at the real (not nominal) midnight, and the day it belongs to
/// gets the whole 25 h.
#[test]
fn a_span_across_a_25_hour_day_sums_to_the_real_span() {
    let zone = "America/Havana";
    let day = day_window("2018-11-04".to_string(), zone.to_string(), 1).expect("window");
    let previous = day_window("2018-11-03".to_string(), zone.to_string(), 1).expect("window");
    // Two hours before the long day starts, through two hours of it.
    let start = previous.end_wall_ms - 2 * 3_600_000;
    let end = day.start_wall_ms + 2 * 3_600_000;
    let slices = slice_interval("s".to_string(), 1, start, end, zone.to_string(), 1, vec![]).expect("slices");
    assert_eq!(slices.len(), 2);
    assert_eq!(slices[0].app_date.iso, "2018-11-03");
    assert_eq!((slices[0].end_wall_ms - slices[0].start_wall_ms) / 1000, 7_200);
    assert_eq!(slices[1].app_date.iso, "2018-11-04");
    assert_eq!((slices[1].end_wall_ms - slices[1].start_wall_ms) / 1000, 7_200);
    let total: i64 = slices
        .iter()
        .map(|slice| (slice.end_wall_ms - slice.start_wall_ms) / 1000)
        .sum();
    assert_eq!(total, (end - start) / 1000);
}

/// A zone change must not re-bucket history: the day totals keep the labels they were recorded
/// under, and the same label from two epochs sums together.
#[test]
fn a_zone_change_does_not_rebucket_history() {
    let state = with_task();
    let added = reduce(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: "2026-03-10".to_string(),
            seconds: 600,
        },
        &envelope("manual-1", BASE),
    );
    assert!(added.error.is_none(), "{:?}", added.error);
    let mut state = added.next_state;

    let mut zone_envelope = envelope("zone", BASE + 1_000);
    zone_envelope.expected_revision = state.revision;
    state = reduce(
        &state,
        DomainCommand::AppendZoneEpoch {
            zone_id: "Europe/Berlin".to_string(),
            impact_summary: "手动改区".to_string(),
        },
        &zone_envelope,
    )
    .next_state;
    assert_eq!(state.zone_epoch_seq, 2);

    // The same civil label recorded under the new epoch must sum into the same day, per §2.4.
    let mut second = envelope("manual-2", BASE + 2_000);
    second.expected_revision = state.revision;
    let state = reduce(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: "2026-03-10".to_string(),
            seconds: 900,
        },
        &second,
    )
    .next_state;

    let totals = project_day_totals(state.ledger.clone());
    assert_eq!(totals.len(), 1);
    assert_eq!(totals[0].app_date, "2026-03-10");
    assert_eq!(totals[0].seconds, 1500);
    assert_ne!(
        state.ledger[0].zone_epoch_seq, state.ledger[1].zone_epoch_seq,
        "the rows really are from two epochs"
    );
}
