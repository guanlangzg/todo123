//! AC-09 + AC-05: recovering a session that was *paused* when the process died.
//!
//! 架构契约 §7.1 splits an interrupted session into a trusted span `[segment.start, heartbeat]` and a
//! gap `[heartbeat, recovery]`, and lets the user book the gap. That model describes a session that
//! was still *running* when it was lost. A paused session is different in one decisive way: the pause
//! already closed and booked its segment, and everything after the pause heartbeat is paused time,
//! which AC-05 forbids from ever becoming investment. Recovery may therefore only close the session —
//! it must not stretch the closed segment and must not add a second slice.
//!
//! Observed defect this file pins down: leaving a session paused for three hours, then accepting the
//! recovery, booked 10 min + 3 h and moved the segment's end past the pause.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test recovery_paused`

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
const PAUSED_AT: i64 = BASE + 600_000;
const THREE_HOURS_LATER: i64 = PAUSED_AT + 10_800_000;

fn envelope(id: &str, wall_ms: i64, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: "boot-1".to_string(),
        },
        expected_revision: revision,
    }
}

fn state_with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "水彩".to_string(),
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
        &envelope(&format!("c{wall_ms}:{}", state.revision), wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

/// A session started at BASE, paused at PAUSED_AT, whose process then died while it was paused: the
/// app reopens with the session still in the active slot, marked as awaiting adjudication.
fn paused_session_lost_to_a_process_death() -> (DomainState, String) {
    let mut state = apply(
        &state_with_task(),
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
        PAUSED_AT,
    );
    assert_eq!(booked(&state), 600, "the pause books the span it closed");
    state.sessions.iter_mut().for_each(|record| {
        if record.session_id == session_id {
            record.state = SessionState::RecoveryPending;
        }
    });
    (state, session_id)
}

fn booked(state: &DomainState) -> i64 {
    replay_daily_total(
        state
            .ledger
            .iter()
            .filter(|row| row.task_id == "task:t1")
            .cloned()
            .collect(),
    )
}

fn last_segment(state: &DomainState, session_id: &str) -> WorkSegment {
    state
        .segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .max_by_key(|segment| segment.seg_seq)
        .cloned()
        .expect("a segment")
}

/// Accepting a paused recovery books no paused time and leaves the segment where the pause put it.
#[test]
fn accepting_a_paused_recovery_does_not_book_the_paused_span() {
    let (state, session_id) = paused_session_lost_to_a_process_death();
    let before = last_segment(&state, &session_id);
    assert_eq!(before.end_wall_ms, Some(PAUSED_AT));

    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        &envelope("accept", THREE_HOURS_LATER, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let next = outcome.next_state;

    assert_eq!(
        booked(&next),
        600,
        "three paused hours are not investment (AC-05)"
    );
    assert_eq!(
        next.ledger.iter().filter(|row| row.kind == 0).count(),
        1,
        "the paused span must not append a second, longer slice"
    );
    let after = last_segment(&next, &session_id);
    assert_eq!(
        after.end_wall_ms,
        Some(PAUSED_AT),
        "the segment end is the pause instant"
    );
    assert_eq!(
        effective_seconds(session_id.clone(), next.segments.clone(), THREE_HOURS_LATER),
        600
    );
    assert_eq!(next.sessions[0].state, SessionState::Finished);
    assert!(next.active_session_id.is_none(), "the slot is released");

    // A retry with a fresh command id is inert too: the resolution is already done.
    let retry = reduce(
        &next,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        &envelope("accept-again", THREE_HOURS_LATER + 1_000, next.revision),
    );
    assert!(retry.error.is_none(), "{:?}", retry.error);
    assert!(retry
        .notices
        .iter()
        .any(|notice| notice.code == "RecoveryAlreadyResolved"));
    assert_eq!(booked(&retry.next_state), 600);
}

/// Discard is the same story: the paused time was never bookable in the first place.
#[test]
fn discarding_a_paused_recovery_keeps_only_the_closed_span() {
    let (state, session_id) = paused_session_lost_to_a_process_death();
    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id,
            choice: RecoveryChoice::Discard,
        },
        &envelope("discard", THREE_HOURS_LATER, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    assert_eq!(booked(&outcome.next_state), 600);
    assert_eq!(
        outcome
            .next_state
            .ledger
            .iter()
            .filter(|row| row.kind == 0)
            .count(),
        1
    );
}

/// A modification can never invent a running span that never existed: with no open segment there is
/// nothing after the pause to attribute seconds to, so the number is bounded by the real investment.
#[test]
fn modifying_a_paused_recovery_cannot_stretch_the_segment() {
    let (state, session_id) = paused_session_lost_to_a_process_death();
    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Modify { seconds: 7_200 },
        },
        &envelope("modify", THREE_HOURS_LATER, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let next = outcome.next_state;
    assert_eq!(booked(&next), 600, "no invented two hours");
    assert_eq!(
        last_segment(&next, &session_id).end_wall_ms,
        Some(PAUSED_AT),
        "the segment is not stretched into the pause"
    );
    assert_eq!(next.sessions[0].state, SessionState::Finished);
}

/// The amount a *paused* session can still recover is zero, and the projection must say so: the
/// prompt's gap is what the resolution will book, so a paused session's window ends at its pause
/// heartbeat. A still-running session passes the current instant and keeps the old arithmetic.
#[test]
fn the_recovery_window_of_a_paused_session_ends_at_its_heartbeat() {
    let paused =
        recovery_amounts(BASE, PAUSED_AT, PAUSED_AT, RecoveryChoice::Accept, None).expect("paused window");
    assert_eq!(paused.trusted_seconds, 600);
    assert_eq!(paused.gap_seconds, 0);
    assert_eq!(paused.booked_seconds, 600);

    let running = recovery_amounts(BASE, PAUSED_AT, THREE_HOURS_LATER, RecoveryChoice::Accept, None)
        .expect("running window");
    assert_eq!(running.gap_seconds, 10_800);
    assert_eq!(running.booked_seconds, 11_400);
}

/// A session that was *running* when it was lost still books trusted + gap: the fix must not narrow
/// the intended AC-09 path.
#[test]
fn accepting_a_running_recovery_still_books_the_gap() {
    let mut state = apply(
        &state_with_task(),
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        BASE,
    );
    let session_id = state.active_session_id.clone().expect("active");
    // The last heartbeat that reached disk was 5 minutes in; the process died right after.
    state.heartbeats.iter_mut().for_each(|beat| {
        if beat.session_id == session_id {
            beat.wall_ms = BASE + 300_000;
        }
    });
    state.sessions.iter_mut().for_each(|record| {
        if record.session_id == session_id {
            record.state = SessionState::RecoveryPending;
        }
    });

    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        &envelope("accept-running", BASE + 900_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let next = outcome.next_state;
    assert_eq!(booked(&next), 900, "trusted 300 + gap 600");
    assert_eq!(last_segment(&next, &session_id).end_wall_ms, Some(BASE + 900_000));
}
