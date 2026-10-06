//! AC-09: an interrupted session is adjudicated once, and repeating the adjudication books
//! nothing extra. The `slice_id` is a pure function of the recovered span (架构契约 §6), so the
//! same recovery replayed three times must leave one ledger row per day and one total.
//!
//! This file covers the *pure domain* half of AC-09. The device half (real process kill, the N7
//! negative case, notification behaviour) needs a device and is out of reach here — see
//! docs/设计/AC覆盖表.md §2 "设备条目".
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test recovery_idempotency`

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

fn with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
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

/// A session that was killed mid-run: the state is `RecoveryPending` with an open segment and a
/// heartbeat that stopped *before* the crash, which is what makes the gap non-empty.
fn killed_session() -> DomainState {
    let mut state = with_task();
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");
    // Simulate the lost process: a heartbeat persisted at +300 s, the process gone afterwards.
    state.sessions.iter_mut().for_each(|record| {
        if record.session_id == session_id {
            record.state = SessionState::RecoveryPending;
        }
    });
    state.heartbeats.push(HeartbeatRecord {
        session_id,
        wall_ms: BASE + 300_000,
        elapsed_ms: 300_000,
        boot_tag: "old-boot".to_string(),
        heartbeat_kind: 0,
    });
    state
}

fn session_id_of(state: &DomainState) -> String {
    state.active_session_id.clone().expect("active session")
}

/// Ledger/segment writes only. Every accepted command also appends a `command_log` row, so "nothing
/// happened" means "no ledger row, no segment change, no session change".
fn has_domain_write(effects: &[LedgerEffect]) -> bool {
    effects.iter().any(|effect| {
        !matches!(
            effect,
            LedgerEffect::AppendCommandLog { .. }
                | LedgerEffect::SetActiveSession { .. }
                | LedgerEffect::SetHeartbeatAnchor { .. }
        )
    })
}

/// Accepting a recovery three times books the span once and yields the same total.
#[test]
fn accepting_a_recovery_three_times_does_not_double_the_total() {
    let mut state = killed_session();
    let session_id = session_id_of(&state);
    let recovered_at = BASE + 900_000;

    let first = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        &envelope("resolve-1", recovered_at, state.revision),
    );
    assert!(first.error.is_none(), "{:?}", first.error);
    state = first.next_state;
    let after_first = replay_daily_total(state.ledger.clone());
    assert_eq!(after_first, 900, "accept books the trusted span plus the gap");
    assert_eq!(
        state.ledger.iter().filter(|row| row.kind == 0).count(),
        1,
        "one day, one slice"
    );

    // Re-submitting the *same command id* is a duplicate, not a second booking.
    let duplicate = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Accept,
        },
        &envelope("resolve-1", recovered_at, state.revision),
    );
    assert!(matches!(
        duplicate.error,
        Some(DomainError::DuplicateCommand { .. })
    ));
    assert_eq!(
        replay_daily_total(duplicate.next_state.ledger.clone()),
        after_first
    );

    // A *new* command id for the same adjudication is a no-op too: the recovery is already done.
    for attempt in 0..2 {
        let again = reduce(
            &state,
            DomainCommand::ResolveRecovery {
                session_id: session_id.clone(),
                choice: RecoveryChoice::Accept,
            },
            &envelope(
                &format!("resolve-repeat-{attempt}"),
                recovered_at + 1_000,
                state.revision,
            ),
        );
        assert!(again.error.is_none(), "{:?}", again.error);
        assert!(
            !has_domain_write(&again.effects),
            "a repeated resolution must not re-book: {:?}",
            again.effects
        );
        assert!(again
            .notices
            .iter()
            .any(|notice| notice.code == "RecoveryAlreadyResolved"));
        state = again.next_state;
        assert_eq!(
            replay_daily_total(state.ledger.clone()),
            after_first,
            "the total must not grow"
        );
        assert_eq!(state.ledger.iter().filter(|row| row.kind == 0).count(), 1);
    }
}

/// Discarding books only the trusted span; the gap is never booked.
#[test]
fn discard_books_only_the_trusted_span() {
    let state = killed_session();
    let session_id = session_id_of(&state);
    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id,
            choice: RecoveryChoice::Discard,
        },
        &envelope("discard", BASE + 900_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    assert_eq!(replay_daily_total(outcome.next_state.ledger.clone()), 300);
}

/// Modifying books exactly the seconds the user typed, and repeating it is inert.
#[test]
fn modify_books_the_user_value_and_is_idempotent() {
    let mut state = killed_session();
    let session_id = session_id_of(&state);
    let outcome = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id: session_id.clone(),
            choice: RecoveryChoice::Modify { seconds: 420 },
        },
        &envelope("modify", BASE + 900_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    state = outcome.next_state;
    assert_eq!(replay_daily_total(state.ledger.clone()), 420);

    let repeat = reduce(
        &state,
        DomainCommand::ResolveRecovery {
            session_id,
            choice: RecoveryChoice::Modify { seconds: 600 },
        },
        &envelope("modify-again", BASE + 901_000, state.revision),
    );
    assert!(repeat.error.is_none(), "{:?}", repeat.error);
    assert!(
        !has_domain_write(&repeat.effects),
        "a later modify must not silently re-book: {:?}",
        repeat.effects
    );
    assert_eq!(replay_daily_total(repeat.next_state.ledger.clone()), 420);
}

/// The user's choice never writes before it: an unresolved session books nothing at all.
#[test]
fn nothing_is_booked_before_the_user_decides() {
    let state = killed_session();
    assert!(
        state.ledger.is_empty(),
        "a recovery-pending session must not have booked anything"
    );
}
