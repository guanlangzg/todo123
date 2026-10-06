//! Process restart classification: an active session awaits user adjudication and books no gap.

use arttodo_core::*;

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;

fn envelope(id: &str, wall_ms: i64, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 10_000,
            boot_tag: "boot-1".to_string(),
        },
        expected_revision: revision,
    }
}

#[test]
fn process_start_marks_the_active_session_pending_without_booking_the_gap() {
    let mut state = arttodo_core::initial_state(ZONE.to_string(), BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:1".to_string(),
        kind: TaskKind::Temporary,
        title: "私人任务".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    let started = arttodo_core::reduce(
        state,
        DomainCommand::StartSession {
            task_id: "task:1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        envelope("start", BASE, 1),
    );
    assert!(started.error.is_none());
    let session_id = started.next_state.active_session_id.clone().unwrap();
    let pending = arttodo_core::reduce(
        started.next_state,
        DomainCommand::MarkRecoveryPending {
            session_id: session_id.clone(),
        },
        envelope("recovery-assess", BASE + 30_000, 2),
    );
    assert!(pending.error.is_none(), "{:?}", pending.error);
    assert_eq!(
        pending.next_state.sessions[0].state,
        SessionState::RecoveryPending
    );
    assert_eq!(
        pending.next_state.active_session_id.as_deref(),
        Some(session_id.as_str())
    );
    assert!(pending.next_state.ledger.is_empty());
}
