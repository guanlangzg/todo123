//! AC-09 + 架构契约 §7.3: a session awaiting the user's recovery decision has exactly one exit —
//! `ResolveRecovery`. Every ordinary "the timer is over" command must refuse to touch it.
//!
//! Observed defect this file pins down: `MarkRecoveryPending` keeps the session in the active slot
//! (that is what makes the recovery prompt reappear), so the ordinary finish path still recognized
//! it as "the active session" and closed its open segment at the command instant, booking the whole
//! unverified span. Reported repro: start at BASE, one persisted heartbeat at +600 s, pending at
//! +600 s, then a plain 完成勾选 at +3600 s booked 3600 s although only 600 s was trusted.
//!
//! The three named bypass routes all funnel through the same helper (`finish_session`), which is
//! where the guard belongs: `CompleteTaskWhileRunning`, `FinishSession` (`Now` and `AfterSeconds`)
//! and `StartSession { replaces_session_id }`.
//!
//! What this file can and cannot prove: it is the *pure domain* half of AC-09. The device half —
//! a real `am kill` / force-stop / reboot, the foreground service, the recovery dialog on the today
//! page — needs a device and is out of reach here (docs/设计/AC覆盖表.md §4).
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test recovery_bypass`

use arttodo_core::*;

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
/// The last heartbeat the running process managed to persist before it died.
const HEARTBEAT_AT: i64 = BASE + 600_000;
/// Ten minutes in the process is already gone; the relaunch stamps the adjudication here.
const PENDING_AT: i64 = BASE + 600_000;
/// Fifty minutes later the user is back on the today page and taps the timer's task.
const BYPASS_AT: i64 = BASE + 3_600_000;

fn envelope(id: &str, wall_ms: i64, revision: u64, boot_tag: &str) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: boot_tag.to_string(),
        },
        expected_revision: revision,
    }
}

fn with_task() -> DomainState {
    let mut state = arttodo_core::initial_state(ZONE.to_string(), BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "写生".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

fn apply(state: &DomainState, command: DomainCommand, wall_ms: i64, boot_tag: &str) -> DomainState {
    let outcome = arttodo_core::reduce(
        state.clone(),
        command,
        envelope(
            &format!("c{wall_ms}:{}", state.revision),
            wall_ms,
            state.revision,
            boot_tag,
        ),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn dispatch(state: &DomainState, command: DomainCommand, wall_ms: i64) -> DomainOutcome {
    arttodo_core::reduce(
        state.clone(),
        command,
        envelope(
            &format!("bypass{wall_ms}:{}", state.revision),
            wall_ms,
            state.revision,
            "boot-2",
        ),
    )
}

/// The reported sequence, built from real commands only: a session starts, one more heartbeat
/// reaches disk ten minutes in, the process dies, and the relaunch marks the session as awaiting the
/// user's decision. Nothing has been booked yet — the ledger is still empty on purpose.
fn session_awaiting_recovery() -> (DomainState, String) {
    let mut state = apply(
        &with_task(),
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        BASE,
        "boot-1",
    );
    let session_id = state.active_session_id.clone().expect("the slot is taken");
    state = apply(
        &state,
        DomainCommand::Heartbeat {
            session_id: session_id.clone(),
        },
        HEARTBEAT_AT,
        "boot-1",
    );
    assert_eq!(
        state
            .heartbeats
            .iter()
            .find(|beat| beat.session_id == session_id)
            .map(|beat| beat.wall_ms),
        Some(HEARTBEAT_AT),
        "the anchor is the last thing the dying process wrote"
    );
    state = apply(
        &state,
        DomainCommand::MarkRecoveryPending {
            session_id: session_id.clone(),
        },
        PENDING_AT,
        "boot-2",
    );
    assert!(state.ledger.is_empty(), "a pending session books nothing");
    assert_eq!(state.sessions[0].state, SessionState::RecoveryPending);
    assert_eq!(
        state.active_session_id.as_deref(),
        Some(session_id.as_str()),
        "the prompt needs the session to still own the slot"
    );
    (state, session_id)
}

fn booked(state: &DomainState) -> i64 {
    replay_daily_total(state.ledger.clone())
}

/// What the command actually did, for the failure message: a passing bypass shows up here as a
/// non-zero booking with the session already finished.
fn describe(outcome: &DomainOutcome) -> String {
    format!(
        "booked={} session={:?} active={:?} ledger_rows={}",
        booked(&outcome.next_state),
        outcome.next_state.sessions.first().map(|record| record.state),
        outcome.next_state.active_session_id,
        outcome.next_state.ledger.len()
    )
}

/// 架构契约 §3.4: a failure comes back as `error` with the state unchanged. For the bypass routes
/// that must mean *nothing at all* moved — no ledger row, no closed segment, no session transition,
/// and the active slot still pointing at the session the user has to adjudicate.
#[track_caller]
fn assert_inert_refusal(state: &DomainState, outcome: &DomainOutcome, label: &str) {
    assert!(
        matches!(outcome.error, Some(DomainError::PreconditionFailed { .. })),
        "{label}: expected PreconditionFailed, got {:?} ({})",
        outcome.error,
        describe(outcome)
    );
    assert!(
        outcome.effects.is_empty(),
        "{label}: a refusal must write no effects: {:?}",
        outcome.effects
    );
    assert!(
        outcome.notices.is_empty(),
        "{label}: a refusal must emit no notices: {:?}",
        outcome.notices
    );
    assert_eq!(
        outcome.next_state.ledger, state.ledger,
        "{label}: the ledger must not move"
    );
    assert_eq!(
        outcome.next_state.active_session_id, state.active_session_id,
        "{label}: the active slot must not move"
    );
    assert_eq!(
        outcome.next_state, *state,
        "{label}: the whole state must be unchanged"
    );
}

#[track_caller]
fn assert_still_pending(state: &DomainState, session_id: &str, label: &str) {
    assert_eq!(
        state.sessions[0].state,
        SessionState::RecoveryPending,
        "{label}: the session still awaits the user"
    );
    let open = state
        .segments
        .iter()
        .find(|segment| segment.session_id == session_id)
        .expect("a segment");
    assert_eq!(
        open.end_wall_ms, None,
        "{label}: the open segment must not be closed by a refusal"
    );
    assert_eq!(booked(state), 0, "{label}: nothing may be booked");
}

/// 今天页勾选计时中的任务：`CompleteTaskWhileRunning` is the command behind 完成勾选, and it used to
/// close the open segment at `now` — 3600 s of which only 600 s had ever been confirmed on disk.
#[test]
fn completing_the_task_the_timer_runs_on_cannot_bypass_a_pending_recovery() {
    let (state, session_id) = session_awaiting_recovery();
    let outcome = dispatch(
        &state,
        DomainCommand::CompleteTaskWhileRunning {
            session_id: session_id.clone(),
            target_app_date: None,
        },
        BYPASS_AT,
    );
    assert_inert_refusal(&state, &outcome, "CompleteTaskWhileRunning");
    assert_still_pending(&outcome.next_state, &session_id, "CompleteTaskWhileRunning");
}

/// 结束计时 (`Now`) must go through the same door.
#[test]
fn finishing_a_pending_session_at_now_cannot_bypass_it() {
    let (state, session_id) = session_awaiting_recovery();
    let outcome = dispatch(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::Now,
        },
        BYPASS_AT,
    );
    assert_inert_refusal(&state, &outcome, "FinishSession{Now}");
    assert_still_pending(&outcome.next_state, &session_id, "FinishSession{Now}");
}

/// The screen and the foreground service send the number the timer shows, so `AfterSeconds{3600}`
/// maps onto `BASE + 3600 s` — the same instant as `Now`, and the same 3600 s of unverified span.
#[test]
fn finishing_a_pending_session_after_seconds_cannot_bypass_it() {
    let (state, session_id) = session_awaiting_recovery();
    let outcome = dispatch(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 3_600 },
        },
        BYPASS_AT,
    );
    assert_inert_refusal(&state, &outcome, "FinishSession{AfterSeconds}");
    assert_still_pending(&outcome.next_state, &session_id, "FinishSession{AfterSeconds}");
}

/// 换任务先结束旧计时：`StartSession { replaces_session_id }` closes the old session through the
/// same helper, so it cannot be the way around the prompt either — and it must not leave a second
/// session behind.
#[test]
fn starting_another_session_cannot_bypass_a_pending_recovery() {
    let (state, session_id) = session_awaiting_recovery();
    let outcome = dispatch(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: Some(session_id.clone()),
        },
        BYPASS_AT,
    );
    assert_inert_refusal(&state, &outcome, "StartSession{replaces}");
    assert_still_pending(&outcome.next_state, &session_id, "StartSession{replaces}");
    assert_eq!(
        outcome.next_state.sessions.len(),
        1,
        "no replacement session may be born out of a refusal"
    );
}

/// The whole ordinary timer vocabulary of a pending session refuses in one place. This is the
/// boundary test: it fails while *any* of these can still finish the session behind the prompt.
#[test]
fn every_ordinary_timer_command_refuses_a_pending_session() {
    let (state, session_id) = session_awaiting_recovery();
    let attempts: Vec<(&str, DomainCommand)> = vec![
        (
            "FinishSession{Now}",
            DomainCommand::FinishSession {
                session_id: session_id.clone(),
                completion: CompletionChoice::Now,
            },
        ),
        (
            "FinishSession{AfterSeconds}",
            DomainCommand::FinishSession {
                session_id: session_id.clone(),
                completion: CompletionChoice::AfterSeconds { seconds: 900 },
            },
        ),
        (
            "CompleteTaskWhileRunning",
            DomainCommand::CompleteTaskWhileRunning {
                session_id: session_id.clone(),
                target_app_date: None,
            },
        ),
        (
            "StartSession{replaces}",
            DomainCommand::StartSession {
                task_id: "task:t1".to_string(),
                mode: SessionMode::CountUp,
                target_seconds: None,
                replaces_session_id: Some(session_id.clone()),
            },
        ),
        (
            "PauseSession",
            DomainCommand::PauseSession {
                session_id: session_id.clone(),
            },
        ),
        (
            "ResumeSession",
            DomainCommand::ResumeSession {
                session_id: session_id.clone(),
            },
        ),
        (
            "Heartbeat",
            DomainCommand::Heartbeat {
                session_id: session_id.clone(),
            },
        ),
    ];
    for (label, command) in attempts {
        let outcome = dispatch(&state, command, BYPASS_AT);
        assert_inert_refusal(&state, &outcome, label);
        assert_still_pending(&outcome.next_state, &session_id, label);
    }
}

/// 改名 is the fourth command that reshapes a timing interval (`RenameTask` splits the running
/// segment so the piece before it keeps the old name, AC-14). It is already gated on the session
/// being `Running`, so it may rename the task of a pending session but must leave the segment open
/// and book nothing — this pins that gate in place instead of leaving it to the reader.
#[test]
fn renaming_the_task_of_a_pending_session_books_nothing() {
    let (state, session_id) = session_awaiting_recovery();
    let outcome = dispatch(
        &state,
        DomainCommand::RenameTask {
            task_id: "task:t1".to_string(),
            title: "新名字".to_string(),
        },
        BYPASS_AT,
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    assert_eq!(
        outcome.next_state.tasks[0].title, "新名字",
        "the rename itself still applies"
    );
    assert_eq!(
        outcome.next_state.segments.len(),
        state.segments.len(),
        "no split segment may be created for a session that is not running"
    );
    assert_still_pending(&outcome.next_state, &session_id, "RenameTask");
}

/// The one door that stays open: the user's own decision. It books exactly what the bypass would
/// have booked silently — 600 s trusted + 3000 s gap — which is why the refusal above is a refusal
/// and not a loss.
#[test]
fn resolving_the_recovery_is_still_the_only_way_to_book_the_pending_span() {
    let (state, session_id) = session_awaiting_recovery();
    let amounts = recovery_amounts(BASE, HEARTBEAT_AT, BYPASS_AT, RecoveryChoice::Accept, None)
        .expect("a running session has a bookable window");
    assert_eq!(amounts.trusted_seconds, 600, "only this much reached disk");
    assert_eq!(amounts.gap_seconds, 3_000, "this is the unverified span");
    assert_eq!(amounts.booked_seconds, 3_600);

    // A refusal first, so the rehearsal and the real decision are exercised on the same state.
    let refused = dispatch(
        &state,
        DomainCommand::CompleteTaskWhileRunning {
            session_id: session_id.clone(),
            target_app_date: None,
        },
        BYPASS_AT,
    );
    assert_inert_refusal(&state, &refused, "CompleteTaskWhileRunning");

    let discard = dispatch(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Discard,
        },
        BYPASS_AT,
    );
    assert!(discard.error.is_none(), "{:?}", discard.error);
    assert_eq!(
        booked(&discard.next_state),
        600,
        "discarding books the trusted span only"
    );
    assert!(discard.next_state.active_session_id.is_none());

    let accept = dispatch(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        BYPASS_AT,
    );
    assert!(accept.error.is_none(), "{:?}", accept.error);
    assert_eq!(
        booked(&accept.next_state),
        3_600,
        "accepting books the trusted span plus the gap the user just confirmed"
    );
    assert_eq!(accept.next_state.sessions[0].state, SessionState::Finished);
    assert!(accept.next_state.active_session_id.is_none());
}
