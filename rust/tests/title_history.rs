//! AC-14: title history. A rename
//!   1. is versioned by the instant of the operation (`task_title_revision`),
//!   2. updates the display name of *today's* instance only — past instances keep the name they
//!      had on their own day,
//!   3. splits a running timing interval at the rename instant, so the earlier piece is
//!      snapshotted with the old title and the later piece starts with the new one,
//!   4. never changes the task id or its cumulative investment,
//!   5. is readable row by row: the records page labels every automatic slice with the title it was
//!      recorded under, and a manual row carries no past name at all.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test title_history`

use std::collections::HashMap;

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
const PAST_DATE: &str = "2025-01-05";

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

fn today() -> String {
    app_date_of(BASE, ZONE.to_string(), 1).expect("app date").iso
}

fn create_daily(wall_ms: i64) -> DomainState {
    let state = initial_state(ZONE, BASE);
    let outcome = reduce(
        &state,
        DomainCommand::CreateTask {
            kind: TaskKind::Daily,
            title: "晨间速写".to_string(),
            note: "备注：铅笔".to_string(),
        },
        &envelope("create", wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn ensure(state: &DomainState, app_date: &str, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        DomainCommand::EnsureOccurrence {
            task_id: state.tasks[0].task_id.clone(),
            app_date: app_date.to_string(),
        },
        &envelope(id, wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn occurrence(state: &DomainState, app_date: &str) -> OccurrenceRecord {
    state
        .occurrences
        .iter()
        .find(|item| item.app_date == app_date)
        .cloned()
        .unwrap_or_else(|| panic!("occurrence for {app_date}"))
}

/// Renaming updates today's instance display name and leaves the past one alone.
#[test]
fn rename_updates_today_only() {
    let mut state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();
    state = ensure(&state, PAST_DATE, "ensure-past", BASE + 1_000);
    state = ensure(&state, &today(), "ensure-today", BASE + 2_000);

    // The past instance keeps whatever it was created with.
    let past_before = occurrence(&state, PAST_DATE).display_title_snapshot;
    let today_id = occurrence(&state, &today()).occurrence_id.clone();

    let renamed = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: task_id.clone(),
            title: "晨间版画".to_string(),
        },
        &envelope("rename", BASE + 3_000, state.revision),
    );
    assert!(renamed.error.is_none(), "{:?}", renamed.error);
    state = renamed.next_state;

    assert_eq!(occurrence(&state, PAST_DATE).display_title_snapshot, past_before);
    assert_eq!(occurrence(&state, &today()).display_title_snapshot, "晨间版画");
    // Same instance identity: a rename is not a regeneration.
    assert_eq!(occurrence(&state, &today()).occurrence_id, today_id);
    assert_eq!(state.tasks.len(), 1, "a rename must not split the task");
    assert_eq!(state.tasks[0].task_id, task_id);

    // The revision is versioned by the operation instant and the notes carry no history.
    let revisions: Vec<&LedgerEffect> = renamed
        .effects
        .iter()
        .filter(|effect| matches!(effect, LedgerEffect::RenameTitleRevision { .. }))
        .collect();
    assert_eq!(revisions.len(), 1);
    assert_eq!(state.tasks[0].note, "备注：铅笔");
}

/// Renaming while an interval is running cuts it, and each piece keeps the title it ran under.
#[test]
fn rename_splits_the_running_interval() {
    let state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();

    let started = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: task_id.clone(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    );
    assert!(started.error.is_none(), "{:?}", started.error);
    let mut state = started.next_state;
    let session_id = state.active_session_id.clone().expect("active");

    let rename_at = BASE + 301_000;
    let renamed = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: task_id.clone(),
            title: "木刻练习".to_string(),
        },
        &envelope("rename", rename_at, state.revision),
    );
    assert!(renamed.error.is_none(), "{:?}", renamed.error);
    state = renamed.next_state;

    let segments: Vec<WorkSegment> = {
        let mut list: Vec<WorkSegment> = state
            .segments
            .iter()
            .filter(|segment| segment.session_id == session_id)
            .cloned()
            .collect();
        list.sort_by_key(|segment| segment.seg_seq);
        list
    };
    assert_eq!(segments.len(), 2, "the rename must cut the interval in two");
    assert_eq!(segments[0].title_snapshot, "晨间速写");
    assert_eq!(segments[0].end_wall_ms, Some(rename_at));
    assert_eq!(segments[1].title_snapshot, "木刻练习");
    assert_eq!(segments[1].start_wall_ms, rename_at);
    assert!(
        segments[1].end_wall_ms.is_none(),
        "the session keeps running after the rename"
    );
    // Contiguous: the cut does not lose or duplicate a single millisecond.
    assert_eq!(segments[0].end_wall_ms, Some(segments[1].start_wall_ms));

    // The finished piece was booked at the moment of the rename with its own title.
    let booked: Vec<&LedgerRow> = state.ledger.iter().filter(|row| row.kind == 0).collect();
    assert_eq!(booked.len(), 1);
    assert_eq!(booked[0].delta_seconds, Some(300));
    assert_eq!(booked[0].occurred_wall_ms, BASE + 1_000);

    // Finishing later books the second piece; the session total is the whole span.
    let finished = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id: session_id.clone(),
            completion: CompletionChoice::Now,
        },
        &envelope("finish", BASE + 901_000, state.revision),
    );
    assert!(finished.error.is_none(), "{:?}", finished.error);
    state = finished.next_state;

    let total: i64 = state
        .ledger
        .iter()
        .filter(|row| row.kind == 0)
        .map(|row| row.delta_seconds.unwrap_or(0))
        .sum();
    assert_eq!(total, 900, "300 s under the old name + 600 s under the new one");
    let segments = state
        .segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .cloned()
        .collect::<Vec<_>>();
    assert_eq!(effective_seconds(session_id, segments, BASE + 901_000), 900);
}

/// A rename while paused does not cut anything; the next resume simply starts with the new name.
#[test]
fn rename_while_paused_starts_the_next_segment_with_the_new_title() {
    let state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();
    let mut state = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: task_id.clone(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    )
    .next_state;
    let session_id = state.active_session_id.clone().expect("active");
    state = reduce(
        &state,
        DomainCommand::PauseSession {
            session_id: session_id.clone(),
        },
        &envelope("pause", BASE + 601_000, state.revision),
    )
    .next_state;
    assert_eq!(state.segments.len(), 1, "pause closes, it does not cut");

    state = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id,
            title: "石版画".to_string(),
        },
        &envelope("rename", BASE + 700_000, state.revision),
    )
    .next_state;
    assert_eq!(state.segments.len(), 1, "a paused rename has nothing to cut");

    state = reduce(
        &state,
        DomainCommand::ResumeSession {
            session_id: session_id.clone(),
        },
        &envelope("resume", BASE + 800_000, state.revision),
    )
    .next_state;
    let mut segments: Vec<WorkSegment> = state
        .segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .cloned()
        .collect();
    segments.sort_by_key(|segment| segment.seg_seq);
    assert_eq!(segments.len(), 2);
    assert_eq!(segments[0].title_snapshot, "晨间速写");
    assert_eq!(segments[1].title_snapshot, "石版画");
}

/// A rename of one task must not touch another task's running interval.
#[test]
fn renaming_another_task_leaves_the_running_interval_alone() {
    let mut state = create_daily(BASE);
    let first = state.tasks[0].task_id.clone();
    // A second daily task, created through the command so the id stays inside the domain.
    state = reduce(
        &state,
        DomainCommand::CreateTask {
            kind: TaskKind::Daily,
            title: "晚间素描".to_string(),
            note: String::new(),
        },
        &envelope("create-2", BASE + 100, state.revision),
    )
    .next_state;
    let second = state
        .tasks
        .iter()
        .find(|task| task.task_id != first)
        .expect("second task")
        .task_id
        .clone();

    state = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: second.clone(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    )
    .next_state;
    let session_id = state.active_session_id.clone().expect("active");

    state = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: first,
            title: "改了别的任务".to_string(),
        },
        &envelope("rename", BASE + 2_000, state.revision),
    )
    .next_state;
    let segments: Vec<&WorkSegment> = state
        .segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .collect();
    assert_eq!(segments.len(), 1);
    assert!(segments[0].end_wall_ms.is_none());
}

/// A rename must not touch the cumulative investment or the ledger of earlier days.
#[test]
fn rename_keeps_the_cumulative_investment() {
    let mut state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();
    state = reduce(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: task_id.clone(),
            app_date: PAST_DATE.to_string(),
            seconds: 1_200,
        },
        &envelope("manual-past", BASE + 1_000, state.revision),
    )
    .next_state;
    let before = replay_daily_total(
        state
            .ledger
            .iter()
            .filter(|row| row.task_id == task_id)
            .cloned()
            .collect(),
    );

    state = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: task_id.clone(),
            title: "改名后".to_string(),
        },
        &envelope("rename", BASE + 2_000, state.revision),
    )
    .next_state;

    let after = replay_daily_total(
        state
            .ledger
            .iter()
            .filter(|row| row.task_id == task_id)
            .cloned()
            .collect(),
    );
    assert_eq!(before, after);
    assert_eq!(after, 1_200);
}

/// The records page's per-row name: an automatic slice keeps the title it was recorded under, and a
/// manual row carries the current one without pretending to be history.
///
/// This is the read behind 规格 3 (「过去计时明细和临时任务完成事件按当时的标题版本呈现」) and
/// 关键页面说明 section 3.2 「长标题（历史名称）」. Before this projection existed the page could only
/// show `TaskRecord.title`, so a rename silently rewrote what every earlier row appeared to say.
///
/// The fixture is one task-day with a rename in the middle of a running interval, so the day holds
/// two automatic rows — 300 s under the old name and 600 s under the new one — plus one manual row.
#[test]
fn automatic_rows_keep_the_title_they_were_recorded_under() {
    let state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();
    let app_date = today();

    let mut state = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: task_id.clone(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    )
    .next_state;
    let session_id = state.active_session_id.clone().expect("active");

    // The rename cuts the running interval; the earlier piece is booked under the old name.
    let renamed = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: task_id.clone(),
            title: "木刻练习".to_string(),
        },
        &envelope("rename", BASE + 301_000, state.revision),
    );
    assert!(renamed.error.is_none(), "{:?}", renamed.error);
    // The *current* title is the one on the task row — the today card, the records header and this
    // projection's fallback all read it. A rename that only wrote the revision row left the new name
    // in the core's memory alone, so the next load showed the old name again (AC-14 「今天新标题」).
    let persisted: Vec<&TaskRecord> = renamed
        .effects
        .iter()
        .filter_map(|effect| match effect {
            LedgerEffect::UpsertTask { task } if task.task_id == task_id => Some(task),
            _ => None,
        })
        .collect();
    assert_eq!(persisted.len(), 1, "a rename must persist the task's new title");
    assert_eq!(persisted[0].title, "木刻练习");
    state = renamed.next_state;
    // Finishing books the piece that ran under the new name.
    state = reduce(
        &state,
        DomainCommand::FinishSession {
            session_id,
            completion: CompletionChoice::Now,
        },
        &envelope("finish", BASE + 901_000, state.revision),
    )
    .next_state;
    // A manual addition on the same day, so the projection is asked about both kinds at once.
    state = reduce(
        &state,
        DomainCommand::AddManualSeconds {
            task_id: task_id.clone(),
            app_date: app_date.clone(),
            seconds: 600,
        },
        &envelope("manual", BASE + 902_000, state.revision),
    )
    .next_state;

    let titles: HashMap<i64, LedgerRowTitle> =
        project_ledger_row_titles(state.ledger.clone(), state.segments.clone(), state.tasks.clone())
            .into_iter()
            .map(|entry| (entry.ledger_seq, entry))
            .collect();

    // Every row of the day is labelled exactly once: no row falls through to a caller-side guess.
    assert_eq!(titles.len(), state.ledger.len());
    assert_eq!(
        state.tasks[0].title, "木刻练习",
        "the task itself carries the new name"
    );

    let slice_of = |seconds: i64| {
        state
            .ledger
            .iter()
            .find(|row| row.kind == 0 && row.delta_seconds == Some(seconds))
            .cloned()
            .unwrap_or_else(|| panic!("automatic row of {seconds} s"))
    };
    let old_piece = slice_of(300);
    let new_piece = slice_of(600);
    let old_title = &titles[&old_piece.ledger_seq];
    let new_title = &titles[&new_piece.ledger_seq];
    assert_eq!(
        old_title.title, "晨间速写",
        "the piece before the rename keeps its name"
    );
    assert!(old_title.is_title_snapshot);
    assert_eq!(new_title.title, "木刻练习");
    assert!(new_title.is_title_snapshot);

    // A manual add describes no span of time, so it has no name of its own: the current title with
    // the snapshot flag cleared, never a fabricated past name.
    let manual = state
        .ledger
        .iter()
        .find(|row| row.kind == 1)
        .cloned()
        .expect("manual row");
    let manual_title = &titles[&manual.ledger_seq];
    assert_eq!(manual_title.title, "木刻练习");
    assert!(
        !manual_title.is_title_snapshot,
        "a manual add must not be dressed up as a historical name"
    );
}

/// A slice whose segment is gone falls back to the current title *without* claiming to be a snapshot.
///
/// The projection may never invent a past name: an unresolvable row is reported as "not a snapshot",
/// which is what lets the interface show the current name there without presenting it as history.
#[test]
fn an_unresolvable_slice_is_never_reported_as_a_snapshot() {
    let state = create_daily(BASE);
    let task_id = state.tasks[0].task_id.clone();
    let mut state = reduce(
        &state,
        DomainCommand::StartSession {
            task_id: task_id.clone(),
            mode: SessionMode::CountUp,
            target_seconds: None,
            replaces_session_id: None,
        },
        &envelope("start", BASE + 1_000, state.revision),
    )
    .next_state;

    state = reduce(
        &state,
        DomainCommand::RenameTask {
            task_id: task_id.clone(),
            title: "石版画".to_string(),
        },
        &envelope("rename", BASE + 301_000, state.revision),
    )
    .next_state;
    let slice = state
        .ledger
        .iter()
        .find(|row| row.kind == 0)
        .cloned()
        .expect("the piece before the rename was booked");

    // The segment list is what the caller read back; a row whose segment it cannot supply must not
    // be guessed at.
    let titles = project_ledger_row_titles(vec![slice], vec![], state.tasks.clone());
    assert_eq!(titles.len(), 1);
    assert_eq!(titles[0].title, "石版画");
    assert!(!titles[0].is_title_snapshot);
}
