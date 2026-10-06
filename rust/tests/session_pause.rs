//! AC-05: pause must not accumulate.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test session_pause`

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

fn state_with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "版画".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

fn apply(state: &DomainState, command: DomainCommand, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        command,
        &envelope(&format!("c{wall_ms}"), wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

/// 10 min running + 3 min paused + 5 min running = 900 s.
#[test]
fn ten_plus_three_paused_plus_five_is_fifteen_minutes() {
    let mut state = state_with_task();
    state = apply(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        BASE,
    );
    let session_id = state.active_session_id.clone().expect("active");

    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        BASE + 600_000,
    );
    state = apply(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        BASE + 780_000,
    );
    let closed_at = BASE + 1_080_000;
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::Now,
        },
        closed_at,
    );

    let segments: Vec<WorkSegment> = state
        .segments
        .iter()
        .filter(|s| s.session_id == session_id)
        .cloned()
        .collect();
    assert_eq!(segments.len(), 2, "the paused span must not become a segment");
    assert_eq!(effective_seconds(session_id.clone(), segments, closed_at), 900);

    let booked: i64 = state
        .ledger
        .iter()
        .filter(|row| row.task_id == "task:t1" && row.kind == 0)
        .map(|row| row.delta_seconds.unwrap_or(0))
        .sum();
    assert_eq!(booked, 900);
}

/// The paused span is bounded: an open segment measured while paused stops at its close instant.
#[test]
fn effective_seconds_ignores_a_paused_gap() {
    let segments = vec![
        WorkSegment {
            segment_id: "s:1".to_string(),
            session_id: "s".to_string(),
            seg_seq: 1,
            start_wall_ms: 0,
            end_wall_ms: Some(600_000),
            title_snapshot: "t".to_string(),
            zone_epoch_seq: 1,
            derived: false,
        },
        WorkSegment {
            segment_id: "s:2".to_string(),
            session_id: "s".to_string(),
            seg_seq: 2,
            start_wall_ms: 900_000,
            end_wall_ms: None,
            title_snapshot: "t".to_string(),
            zone_epoch_seq: 1,
            derived: false,
        },
    ];
    // Paused for 5 minutes, then 2 minutes of work so far.
    assert_eq!(effective_seconds("s".to_string(), segments, 1_020_000), 720);
}

/// A finish before the target stores less than the target (AC-07 half, asserted here too).
#[test]
fn early_finish_saves_less_than_the_target() {
    let mut state = state_with_task();
    state = apply(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::Countdown,
            target_seconds: Some(1500),
            replaces_session_id: None,
        },
        BASE,
    );
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 400 },
        },
        BASE + 1_000_000,
    );
    let booked: i64 = state
        .ledger
        .iter()
        .filter(|row| row.kind == 0)
        .map(|row| row.delta_seconds.unwrap_or(0))
        .sum();
    assert_eq!(booked, 400);
    assert!(booked < 1500);
}
