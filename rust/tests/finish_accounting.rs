//! AC-05 / AC-07 / AC-14: what a finished session books must equal what the screen showed, once.
//!
//! The screens and the foreground service pass `CompletionChoice::AfterSeconds` with the number the
//! user is looking at, and that number is the *session's* invested seconds: the focus screen's
//! `elapsedSeconds()` sums every segment, the stop button's label reads 「已投入 X」, and the
//! background service shows the same running total. After a pause and a resume — or after a rename,
//! which splits the running segment (AC-14) — the session has more than one segment, so the number
//! the UI supplies and the span the open segment has actually run are two different quantities. A
//! comparison here may never double count the closed part.
//!
//! These are regression tests for the observed double count:
//!   * 10 min run + 3 min paused + 5 min run showed 15:00 and booked 25:00.
//!   * finishing a *paused* session failed outright ("no open segment to measure from").
//!   * the countdown service's zero-crossing booked target + the pre-pause span.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test finish_accounting`

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

fn state_with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "速写".to_string(),
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

fn start(state: &DomainState, mode: SessionMode, target_seconds: Option<i64>, at: i64) -> DomainState {
    apply(
        state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode,
            target_seconds,
            replaces_session_id: None,
        },
        at,
    )
}

fn active(state: &DomainState) -> String {
    state.active_session_id.clone().expect("active session")
}

/// Every booked slice of the task, summed the way the calendar and the heat map do.
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

fn session_segments(state: &DomainState, session_id: &str) -> Vec<WorkSegment> {
    let mut segments: Vec<WorkSegment> = state
        .segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .cloned()
        .collect();
    segments.sort_by_key(|segment| segment.seg_seq);
    segments
}

/// AC-05: 10 min running, 3 min paused, 5 min running. The focus screen shows 15:00 when the user
/// presses 结束, and that is exactly what the ledger may hold.
#[test]
fn stopping_after_a_pause_books_the_displayed_investment_once() {
    let mut state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
    let session_id = active(&state);

    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        BASE + 600_000,
    );
    // The pause books the span it closed, so it is already in the ledger.
    assert_eq!(
        booked(&state),
        600,
        "the closed span is booked by the pause itself"
    );

    state = apply(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        BASE + 780_000,
    );
    let stopped_at = BASE + 1_080_000;
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            // What `FocusScreen` passes: the accumulated 600 + 300 = 900 the clock displays.
            completion: CompletionChoice::AfterSeconds { seconds: 900 },
        },
        stopped_at,
    );

    assert_eq!(booked(&state), 900, "15 minutes, not 25");
    assert_eq!(
        effective_seconds(
            session_id.clone(),
            session_segments(&state, &session_id),
            stopped_at
        ),
        900
    );
    let segments = session_segments(&state, &session_id);
    assert_eq!(segments.len(), 2, "the pause is not a segment");
    // The resumed segment ends where it really ran to, not one full display later.
    assert_eq!(segments[1].start_wall_ms, BASE + 780_000);
    assert_eq!(segments[1].end_wall_ms, Some(stopped_at));
}

/// AC-07: 提前结束 while paused must still save what was invested. The stop button is reachable in
/// the paused state, so the command has to mean "book the session total", not "extend the open
/// segment" — a paused session has none.
#[test]
fn stopping_a_paused_session_saves_the_investment_instead_of_failing() {
    let mut state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
    let session_id = active(&state);
    state = apply(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        BASE + 600_000,
    );

    // Five minutes later the user presses 结束; the frozen clock still reads 10:00.
    let outcome = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        &envelope("stop-paused", BASE + 900_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);

    state = outcome.next_state;
    assert_eq!(state.sessions[0].state, SessionState::Finished);
    assert!(state.active_session_id.is_none());
    assert_eq!(booked(&state), 600, "the paused 5 minutes are not investment");
    assert_eq!(
        session_segments(&state, &session_id)[0].end_wall_ms,
        Some(BASE + 600_000),
        "the pause instant stays the segment's end"
    );
}

/// AC-14 + AC-05: a rename splits the running segment at the rename instant. Finishing right after
/// must book the two pieces the clock showed (600), never the displayed total applied to the open
/// piece alone (300 + 600).
#[test]
fn stopping_after_a_rename_books_each_piece_once() {
    let mut state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
    let session_id = active(&state);

    state = apply(
        &state,
        DomainCommand::RenameTask {
            task_id: "task:t1".to_string(),
            title: "重命名后的速写".to_string(),
        },
        BASE + 300_000,
    );
    assert_eq!(
        booked(&state),
        300,
        "the piece before the rename is booked with the old name"
    );
    assert_eq!(session_segments(&state, &session_id).len(), 2);

    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        BASE + 600_000,
    );

    assert_eq!(booked(&state), 600);
    let segments = session_segments(&state, &session_id);
    assert_eq!(segments.len(), 2);
    assert_eq!(segments[1].end_wall_ms, Some(BASE + 600_000));
    assert_eq!(segments[0].title_snapshot, "速写");
    assert_eq!(segments[1].title_snapshot, "重命名后的速写");
}

/// AC-07 + the background service path: the countdown reaches zero after a pause, so the zero
/// crossing happens 3 minutes later than the target would suggest. `SessionRuntimeService` passes
/// the target (its remaining-time loop floors at zero); the ledger must hold the target once — not
/// the target plus the span that had already been booked before the pause.
#[test]
fn reaching_the_countdown_target_after_a_pause_books_the_target_once() {
    let mut state = start(&state_with_task(), SessionMode::Countdown, Some(1_500), BASE);
    let session_id = active(&state);

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

    // 900 s of the resumed segment bring the session to its 1500 s target: 00:25 after a 3 min pause.
    let zero_crossed_at = BASE + 1_680_000;
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 1_500 },
        },
        zero_crossed_at,
    );

    assert_eq!(
        booked(&state),
        1_500,
        "the countdown books its target, not target + pause-span"
    );
    assert_eq!(
        session_segments(&state, &session_id)[1].end_wall_ms,
        Some(zero_crossed_at),
        "the end instant is the real zero crossing"
    );
}

/// The booked end is a pure function of the total the caller displayed, not of when the command ran:
/// the number was derived from the very segments the core is looking at, so a frozen or stale command
/// clock must not shrink it.
#[test]
fn the_booked_total_does_not_depend_on_the_command_clock() {
    for command_at in [BASE + 600_000, BASE + 601_000, BASE] {
        let state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
        let session_id = active(&state);
        let state = apply(
            &state,
            DomainCommand::FinishSession {
                session_id: session_id.clone(),
                completion: CompletionChoice::AfterSeconds { seconds: 600 },
            },
            command_at,
        );
        assert_eq!(booked(&state), 600, "command at {command_at}");
        assert_eq!(
            session_segments(&state, &session_id)[0].end_wall_ms,
            Some(BASE + 600_000)
        );
    }
}

/// A stale display that is already spent by the closed segments books nothing further, and never moves
/// the open segment's end before its own start.
#[test]
fn a_total_below_the_closed_segments_books_nothing_more() {
    let mut state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
    let session_id = active(&state);
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
        BASE + 900_000,
    );
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 300 },
        },
        BASE + 1_200_000,
    );

    assert_eq!(booked(&state), 600, "the closed span is not rewritten downwards");
    let segments = session_segments(&state, &session_id);
    assert_eq!(segments[1].end_wall_ms, Some(BASE + 900_000));
}

/// Negative input is still a validation error, not a silent clamp.
#[test]
fn a_negative_total_is_refused_without_writing() {
    let state = start(&state_with_task(), SessionMode::CountUp, None, BASE);
    let session_id = active(&state);
    let outcome = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: -1 },
        },
        &envelope("negative", BASE + 1_000, state.revision),
    );
    assert!(matches!(outcome.error, Some(DomainError::NegativeSeconds)));
    assert!(outcome.effects.is_empty());
}
