//! AC-12 / AC-13 at the command level: the ledger's three event kinds replay in the order they
//! actually happened, editing an old row keeps that row's ordering slot, deleting a `set` row
//! reports what it changed, and the same span can never be booked twice.
//!
//! `ledger_replay.rs` covers the five judged scenarios S1–S5; this file covers the parts the ask
//! calls out separately: editing an *old* record, replaying one command id, and the deterministic
//! uniqueness of slices.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test ledger_semantics`

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

fn date() -> String {
    app_date_of(BASE, ZONE.to_string(), 1).expect("date").iso
}

fn with_task() -> DomainState {
    let mut state = initial_state(ZONE, BASE);
    state.tasks.push(TaskRecord {
        task_id: "task:t1".to_string(),
        kind: TaskKind::Temporary,
        title: "木刻".to_string(),
        note: String::new(),
        sort_key: 1024,
        created_wall_ms: BASE,
        archived_at_ms: None,
        last_countdown_minutes: 25,
        art_asset_id: None,
    });
    state
}

fn apply(state: &DomainState, command: DomainCommand, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(state, command, &envelope(id, wall_ms, state.revision));
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn rows(state: &DomainState) -> Vec<LedgerRow> {
    state
        .ledger
        .iter()
        .filter(|row| row.task_id == "task:t1" && row.app_date == date())
        .cloned()
        .collect()
}

/// Editing an *old* row keeps its slot: it does not get moved after the `set` that followed it.
#[test]
fn editing_an_old_row_keeps_its_ordering_slot() {
    let mut state = with_task();
    // +20, set 35, +30, +10
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 1_200,
        },
        "a1",
        BASE + 1_000,
    );
    state = apply(
        &state,
        DomainCommand::SetDailyTotalSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            total_seconds: 2_100,
        },
        "s1",
        BASE + 2_000,
    );
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 1_800,
        },
        "a2",
        BASE + 3_000,
    );
    let oldest = rows(&state)[0].ledger_seq;
    let seqs_before: Vec<i64> = rows(&state).iter().map(|row| row.ledger_seq).collect();
    assert_eq!(replay_daily_total(rows(&state)), 2_100 + 1_800);

    // Editing the *first* row must not move it after the set row.
    state = apply(
        &state,
        DomainCommand::EditLedgerEntry {
            ledger_seq: oldest,
            new_delta_seconds: Some(2_400),
            new_set_total_seconds: None,
        },
        "edit",
        BASE + 4_000,
    );

    let seqs_after: Vec<i64> = rows(&state).iter().map(|row| row.ledger_seq).collect();
    assert_eq!(seqs_before, seqs_after, "an edit must not append or renumber");
    // The inserted row is still pinning the running total to 35 min, so the edit is swallowed.
    assert_eq!(replay_daily_total(rows(&state)), 2_100 + 1_800);
    // But the row's own value really did change, and the change is audited.
    let edited = state
        .ledger
        .iter()
        .find(|row| row.ledger_seq == oldest)
        .expect("row");
    assert_eq!(edited.delta_seconds, Some(2_400));
    assert_eq!(edited.edited_at_ms, Some(BASE + 4_000));
    assert_eq!(state.audits.len(), 1);
    assert_eq!(state.audits[0].prev_delta_seconds, Some(1_200));
    assert_eq!(state.audits[0].new_delta_seconds, Some(2_400));
}

/// A deleted row keeps its slot too, so the remaining rows do not shuffle.
#[test]
fn deleting_an_old_row_keeps_the_others_in_place() {
    let mut state = with_task();
    for (index, seconds) in [600_i64, 1_200, 1_800].into_iter().enumerate() {
        state = apply(
            &state,
            DomainCommand::AddManualSeconds {
                task_id: "task:t1".to_string(),
                app_date: date(),
                seconds,
            },
            &format!("a{index}"),
            BASE + 1_000 + index as i64 * 1_000,
        );
    }
    let seqs_before: Vec<i64> = rows(&state).iter().map(|row| row.ledger_seq).collect();
    let middle = seqs_before[1];

    state = apply(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: middle },
        "delete",
        BASE + 10_000,
    );

    let remaining: Vec<i64> = rows(&state)
        .iter()
        .filter(|row| !row.is_deleted)
        .map(|row| row.ledger_seq)
        .collect();
    assert_eq!(remaining, vec![seqs_before[0], seqs_before[2]]);
    assert_eq!(replay_daily_total(rows(&state)), 2_400);
    // The deleted row is still physically present, with its slot and its pre-image audited.
    let deleted = state
        .ledger
        .iter()
        .find(|row| row.ledger_seq == middle)
        .expect("row stays");
    assert!(deleted.is_deleted);
    assert_eq!(deleted.deleted_at_ms, Some(BASE + 10_000));
    assert_eq!(state.audits[0].change_kind, 1);
    assert_eq!(state.audits[0].prev_delta_seconds, Some(1_200));
}

/// Deleting a row twice is refused: the slot is kept, but a deleted row cannot be deleted again.
#[test]
fn deleting_the_same_row_twice_is_refused() {
    let mut state = with_task();
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 600,
        },
        "a1",
        BASE + 1_000,
    );
    let seq = rows(&state)[0].ledger_seq;
    state = apply(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: seq },
        "delete",
        BASE + 2_000,
    );
    let audits_after_first = state.audits.len();

    let second = reduce(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: seq },
        &envelope("delete-again", BASE + 3_000, state.revision),
    );
    assert!(matches!(
        second.error,
        Some(DomainError::PreconditionFailed { .. })
    ));
    assert!(second.effects.is_empty());
    assert_eq!(second.next_state.audits.len(), audits_after_first);
}

/// Editing a deleted row is refused as well: the row is gone from the live set.
#[test]
fn editing_a_deleted_row_is_refused() {
    let mut state = with_task();
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 600,
        },
        "a1",
        BASE + 1_000,
    );
    let seq = rows(&state)[0].ledger_seq;
    state = apply(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: seq },
        "delete",
        BASE + 2_000,
    );

    let outcome = reduce(
        &state,
        DomainCommand::EditLedgerEntry {
            ledger_seq: seq,
            new_delta_seconds: Some(900),
            new_set_total_seconds: None,
        },
        &envelope("edit-deleted", BASE + 3_000, state.revision),
    );
    assert!(matches!(
        outcome.error,
        Some(DomainError::PreconditionFailed { .. })
    ));
    assert!(outcome.effects.is_empty());
}

/// Editing does not change the number of rows: it is an in-place value change, never an append.
#[test]
fn editing_never_appends_a_row() {
    let mut state = with_task();
    for (index, (kind, seconds)) in [(1_i32, 600_i64), (2, 900), (1, 300)].into_iter().enumerate() {
        state = apply(
            &state,
            if kind == 1 {
                DomainCommand::AddManualSeconds {
                    task_id: "task:t1".to_string(),
                    app_date: date(),
                    seconds,
                }
            } else {
                DomainCommand::SetDailyTotalSeconds {
                    task_id: "task:t1".to_string(),
                    app_date: date(),
                    total_seconds: seconds,
                }
            },
            &format!("c{index}"),
            BASE + 1_000 + index as i64 * 1_000,
        );
    }
    assert_eq!(state.ledger.len(), 3);

    for (index, row) in state.ledger.clone().iter().enumerate() {
        let (delta, set) = if row.kind == 2 {
            (None, Some(1_200_i64))
        } else {
            (Some(999_i64), None)
        };
        state = apply(
            &state,
            DomainCommand::EditLedgerEntry {
                ledger_seq: row.ledger_seq,
                new_delta_seconds: delta,
                new_set_total_seconds: set,
            },
            &format!("edit{index}"),
            BASE + 10_000 + index as i64 * 1_000,
        );
        assert_eq!(state.ledger.len(), 3, "an edit must not append");
    }
    assert_eq!(state.audits.len(), 3);
}

/// Two rows written at the same instant are ordered by their record number; a row that happened
/// earlier is not affected by a later write.
#[test]
fn the_record_number_breaks_ties_at_the_same_instant() {
    // Same `Base`, so both rows carry the same `occurred_wall_ms`.
    let make = |seq: i64, occurred: i64, kind: i32, delta: Option<i64>, set: Option<i64>| LedgerRow {
        ledger_seq: seq,
        kind,
        ref_id: None,
        task_id: "task:t1".to_string(),
        app_date: date(),
        occurred_wall_ms: occurred,
        zone_epoch_seq: 1,
        delta_seconds: delta,
        set_total_seconds: set,
        created_wall_ms: occurred,
        edited_at_ms: None,
        is_deleted: false,
        deleted_at_ms: None,
    };

    let add_then_set = vec![make(1, BASE, 1, Some(600), None), make(2, BASE, 2, None, Some(0))];
    assert_eq!(replay_daily_total(add_then_set), 0);

    let set_then_add = vec![make(1, BASE, 2, None, Some(0)), make(2, BASE, 1, Some(600), None)];
    assert_eq!(replay_daily_total(set_then_add), 600);
}

/// A later row with a *smaller* record number still replays after an earlier row, if it happened
/// after it (the lazy-generation case, end to end through the command surface).
#[test]
fn occurrence_order_beats_the_record_number_across_days() {
    let mut state = with_task();
    let before = day_window("2026-03-09".to_string(), ZONE.to_string(), 1).expect("window");
    let after = day_window("2026-03-10".to_string(), ZONE.to_string(), 1).expect("window");

    // A session that starts before midnight and runs across it. The slice for 03-09 is written by
    // the finish command, i.e. *after* anything already recorded for 03-10.
    let start = before.end_wall_ms - 600_000;
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", start, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");
    state = apply(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 1_800 },
        },
        "finish",
        after.start_wall_ms + 1_200_000,
    );

    // 03-10 already carries a manual add that "happened" later in the day.
    let later = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: "2026-03-10".to_string(),
            seconds: 300,
        },
        "manual",
        after.start_wall_ms + 1_300_000,
    );

    let day_ten: Vec<LedgerRow> = later
        .ledger
        .iter()
        .filter(|row| row.app_date == "2026-03-10")
        .cloned()
        .collect();
    // The slice row was written first but its `occurred_wall_ms` is midnight; the manual add is
    // later in the day. Their sum is order-independent, and the day total is their sum.
    assert_eq!(replay_daily_total(day_ten.clone()), 1_200 + 300);
    let day_ten_total = project_day_totals(later.ledger.clone())
        .into_iter()
        .find(|total| total.app_date == "2026-03-10")
        .expect("day total");
    assert_eq!(day_ten_total.seconds, 1_500);

    // And the slice itself is a single deterministic row for the day.
    let slice_rows: Vec<&LedgerRow> = day_ten.iter().filter(|row| row.kind == 0).collect();
    assert_eq!(slice_rows.len(), 1);
    assert_eq!(slice_rows[0].delta_seconds, Some(1_200));
}

/// The same deterministic span key cannot produce a second ledger row.
#[test]
fn a_span_cannot_be_booked_twice() {
    let mut state = with_task();
    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");
    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        &envelope("finish", BASE + 601_000, state.revision),
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    state = finished.next_state;

    let slice_ids: Vec<String> = rows(&state).iter().filter_map(|row| row.ref_id.clone()).collect();
    assert_eq!(slice_ids.len(), 1, "one day, one slice");

    // Re-submitting the same finish (same command id) changes nothing.
    let again = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id: state.sessions[0].session_id.clone(),
            completion: CompletionChoice::AfterSeconds { seconds: 600 },
        },
        &envelope("finish", BASE + 601_000, state.revision),
    );
    assert!(matches!(again.error, Some(DomainError::DuplicateCommand { .. })));
    assert_eq!(again.next_state.ledger.len(), state.ledger.len());
    assert_eq!(replay_daily_total(state.ledger.clone()), 600);

    // A *new* session on the same span gets a different slice id, because its id differs.
    let restarted = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: "task:t1".to_string(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start-2", BASE + 700_000, state.revision),
    );
    assert!(restarted.error.is_none(), "{:?}", restarted.error);
    let second_session = restarted.next_state.active_session_id.clone().expect("active");
    let second_finish = reduce(
        &restarted.next_state,
        DomainCommand::FinishSession {
            session_id: second_session,
            completion: CompletionChoice::AfterSeconds { seconds: 300 },
        },
        &envelope("finish-2", BASE + 1_000_000, restarted.next_state.revision),
    );
    assert!(second_finish.error.is_none(), "{:?}", second_finish.error);
    let all_ids: Vec<String> = second_finish
        .next_state
        .ledger
        .iter()
        .filter_map(|row| row.ref_id.clone())
        .collect();
    assert_eq!(all_ids.len(), 2);
    assert_ne!(all_ids[0], all_ids[1]);
    assert_eq!(replay_daily_total(second_finish.next_state.ledger.clone()), 900);
}

/// Undoing a delete restores the row **in place**, so the day returns to the total it had before.
///
/// This is the guard for the `RestoreLedgerEntry` decision: re-appending an equal value would put the
/// row after the set-total event and the day would stay at the post-delete total instead. The S3 shape
/// (+40, +30, set 35, +10) is used because deleting the set row is exactly the case where "restore"
/// and "re-add" diverge.
#[test]
fn undoing_a_delete_restores_the_row_in_its_original_slot() {
    let mut state = with_task();
    let mut seqs = Vec::new();
    for (index, seconds) in [2_400_i64, 1_800].into_iter().enumerate() {
        state = apply(
            &state,
            DomainCommand::AddManualSeconds {
                task_id: "task:t1".to_string(),
                app_date: date(),
                seconds,
            },
            &format!("m{index}"),
            BASE + 1_000 + index as i64 * 1_000,
        );
        seqs.push(rows(&state).last().expect("row").ledger_seq);
    }
    state = apply(
        &state,
        DomainCommand::SetDailyTotalSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            total_seconds: 2_100,
        },
        "set",
        BASE + 3_000,
    );
    let set_seq = rows(&state).last().expect("set row").ledger_seq;
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 600,
        },
        "tail",
        BASE + 4_000,
    );
    let with_set = replay_daily_total(rows(&state));
    assert_eq!(with_set, 2_700);

    state = apply(
        &state,
        DomainCommand::DeleteLedgerEntry { ledger_seq: set_seq },
        "delete",
        BASE + 5_000,
    );
    assert_eq!(replay_daily_total(rows(&state)), 4_800);

    state = apply(
        &state,
        DomainCommand::RestoreLedgerEntry { ledger_seq: set_seq },
        "restore",
        BASE + 6_000,
    );

    // Back to the pre-delete total, not to "post-delete total + 2_100".
    assert_eq!(replay_daily_total(rows(&state)), with_set);
    let restored = state
        .ledger
        .iter()
        .find(|row| row.ledger_seq == set_seq)
        .expect("row stays");
    assert!(!restored.is_deleted);
    assert_eq!(restored.deleted_at_ms, None);
    // The row kept the slot it was created with: no new ledger_seq was appended.
    assert_eq!(rows(&state).len(), 4);
    assert_eq!(rows(&state)[2].ledger_seq, set_seq);
    // The undo is itself auditable.
    assert_eq!(state.audits[1].change_kind, 2);
    assert_eq!(state.audits[1].ledger_seq, set_seq);
    assert_eq!(state.audits[1].prev_set_total_seconds, Some(2_100));
    assert_eq!(state.audits[1].new_set_total_seconds, Some(2_100));
}

/// Restoring a row that is not deleted is a precondition failure, not a silent no-op.
#[test]
fn restoring_a_live_row_is_refused() {
    let mut state = with_task();
    state = apply(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: "task:t1".to_string(),
            app_date: date(),
            seconds: 600,
        },
        "m1",
        BASE + 1_000,
    );
    let seq = rows(&state)[0].ledger_seq;
    let outcome = reduce(
        &state,
        DomainCommand::RestoreLedgerEntry { ledger_seq: seq },
        &envelope("restore", BASE + 2_000, state.revision),
    );
    assert!(outcome.error.is_some());
}
