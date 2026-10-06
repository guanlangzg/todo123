//! `reduce(state, command) -> outcome` — the only way the domain changes.
//!
//! Rust is a pure function over state: it never reads the clock, the filesystem or a database
//! (架构契约 §1). Kotlin persists the returned `effects` inside one Room transaction.

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

use crate::error::DomainError;
use crate::ledger;
use crate::occurrence::{self, ACTION_COMPLETE, ACTION_REOPEN};
use crate::session;
use crate::temporal;
use crate::temporary;
use crate::types::*;

pub const MAX_TITLE_LEN: u32 = 120;
pub const MAX_NOTE_LEN: u32 = 2000;
pub const MIN_COUNTDOWN_MINUTES: u32 = 1;
pub const MAX_COUNTDOWN_MINUTES: u32 = 1440;
pub const DEFAULT_COUNTDOWN_MINUTES: u32 = 25;
pub const SORT_KEY_STEP: i64 = 1024;
/// 架构契约 §7.2: the periodic heartbeat cadence, driven by the foreground service.
pub const HEARTBEAT_KIND_PERIODIC: i32 = 0;
pub const HEARTBEAT_KIND_TRANSITION: i32 = 1;

/// Empty state for a freshly installed app: one zone epoch, no tasks.
pub fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    DomainState {
        revision: 1,
        zone_epoch_seq: 1,
        zone_id: zone_id.to_string(),
        tasks: Vec::new(),
        occurrences: Vec::new(),
        sessions: Vec::new(),
        active_session_id: None,
        segments: Vec::new(),
        heartbeats: Vec::new(),
        ledger: Vec::new(),
        audits: Vec::new(),
        zone_epochs: vec![ZoneEpoch {
            epoch_seq: 1,
            zone_id: zone_id.to_string(),
            effective_wall_ms: created_wall_ms,
            impact_summary: String::new(),
            created_wall_ms,
        }],
        command_log: Vec::new(),
        completion_event_high_water: Vec::new(),
    }
}

fn command_kind(command: &DomainCommand) -> &'static str {
    match command {
        DomainCommand::CreateTask { .. } => "CreateTask",
        DomainCommand::RenameTask { .. } => "RenameTask",
        DomainCommand::EditNote { .. } => "EditNote",
        DomainCommand::ArchiveTask { .. } => "ArchiveTask",
        DomainCommand::UnarchiveTask { .. } => "UnarchiveTask",
        DomainCommand::ReorderTasks { .. } => "ReorderTasks",
        DomainCommand::SetTaskRecentCountdown { .. } => "SetTaskRecentCountdown",
        DomainCommand::EnsureOccurrence { .. } => "EnsureOccurrence",
        DomainCommand::SetOccurrenceCompletion { .. } => "SetOccurrenceCompletion",
        DomainCommand::RebuildOccurrenceView { .. } => "RebuildOccurrenceView",
        DomainCommand::CompleteTemporary { .. } => "CompleteTemporary",
        DomainCommand::ReopenTemporary { .. } => "ReopenTemporary",
        DomainCommand::StartSession { .. } => "StartSession",
        DomainCommand::PauseSession { .. } => "PauseSession",
        DomainCommand::ResumeSession { .. } => "ResumeSession",
        DomainCommand::FinishSession { .. } => "FinishSession",
        DomainCommand::CompleteTaskWhileRunning { .. } => "CompleteTaskWhileRunning",
        DomainCommand::AddManualSeconds { .. } => "AddManualSeconds",
        DomainCommand::SetDailyTotalSeconds { .. } => "SetDailyTotalSeconds",
        DomainCommand::EditLedgerEntry { .. } => "EditLedgerEntry",
        DomainCommand::DeleteLedgerEntry { .. } => "DeleteLedgerEntry",
        DomainCommand::RestoreLedgerEntry { .. } => "RestoreLedgerEntry",
        DomainCommand::AppendZoneEpoch { .. } => "AppendZoneEpoch",
        DomainCommand::MarkRecoveryPending { .. } => "MarkRecoveryPending",
        DomainCommand::Heartbeat { .. } => "Heartbeat",
        DomainCommand::ResolveRecovery { .. } => "ResolveRecovery",
    }
}

fn digest<T: Hash>(value: &T) -> String {
    let mut hasher = DefaultHasher::new();
    value.hash(&mut hasher);
    format!("{:016x}", hasher.finish())
}

pub fn validate_title(title: &str) -> Result<String, DomainError> {
    let trimmed = title.trim();
    if trimmed.is_empty() {
        return Err(DomainError::EmptyTitle);
    }
    if trimmed.chars().count() as u32 > MAX_TITLE_LEN {
        return Err(DomainError::TitleTooLong { max: MAX_TITLE_LEN });
    }
    Ok(trimmed.to_string())
}

pub fn validate_note(note: &str) -> Result<(), DomainError> {
    if note.chars().count() as u32 > MAX_NOTE_LEN {
        return Err(DomainError::NoteTooLong { max: MAX_NOTE_LEN });
    }
    Ok(())
}

fn find_task<'a>(state: &'a DomainState, task_id: &str) -> Result<&'a TaskRecord, DomainError> {
    state
        .tasks
        .iter()
        .find(|task| task.task_id == task_id)
        .ok_or_else(|| DomainError::TaskNotFound {
            task_id: task_id.to_string(),
        })
}

fn find_session<'a>(state: &'a DomainState, session_id: &str) -> Result<&'a SessionRecord, DomainError> {
    state
        .sessions
        .iter()
        .find(|record| record.session_id == session_id)
        .ok_or_else(|| DomainError::SessionNotFound {
            session_id: session_id.to_string(),
        })
}

fn find_row(state: &DomainState, ledger_seq: i64) -> Result<&LedgerRow, DomainError> {
    state
        .ledger
        .iter()
        .find(|row| row.ledger_seq == ledger_seq && !row.is_deleted)
        .ok_or_else(|| DomainError::PreconditionFailed {
            reason: format!("ledger row {ledger_seq} not found or deleted"),
        })
}

/// Temporary tasks are the only ones with a todo/done history; a daily task's completion is a
/// per-day fact (`SetOccurrenceCompletion`), so asking for it here is a precondition failure rather
/// than a silently created history.
fn require_temporary_task(state: &DomainState, task_id: &str) -> Result<String, DomainError> {
    let task = find_task(state, task_id)?;
    if task.kind != TaskKind::Temporary {
        return Err(DomainError::PreconditionFailed {
            reason: format!("task {task_id} is not temporary"),
        });
    }
    Ok(task.title.clone())
}

fn upsert_occurrence(state: &mut DomainState, occurrence: OccurrenceRecord) {
    if let Some(slot) = state
        .occurrences
        .iter_mut()
        .find(|existing| existing.occurrence_id == occurrence.occurrence_id)
    {
        *slot = occurrence;
    } else {
        state.occurrences.push(occurrence);
    }
}

fn upsert_session(state: &mut DomainState, record: SessionRecord) {
    if let Some(slot) = state
        .sessions
        .iter_mut()
        .find(|existing| existing.session_id == record.session_id)
    {
        *slot = record;
    } else {
        state.sessions.push(record);
    }
}

fn set_high_water(state: &mut DomainState, occurrence_id: &str, high_water: i64) {
    if let Some(entry) = state
        .completion_event_high_water
        .iter_mut()
        .find(|e| e.occurrence_id == occurrence_id)
    {
        entry.high_water = entry.high_water.max(high_water);
    } else {
        state.completion_event_high_water.push(EventHighWater {
            occurrence_id: occurrence_id.to_string(),
            high_water,
        });
    }
}

fn next_ledger_seq(state: &DomainState) -> i64 {
    state.ledger.iter().map(|row| row.ledger_seq).max().unwrap_or(0) + 1
}

fn next_event_seq(state: &DomainState) -> i64 {
    state
        .completion_event_high_water
        .iter()
        .map(|entry| entry.high_water)
        .max()
        .unwrap_or(0)
        + 1
}

fn next_audit_seq(state: &DomainState) -> i64 {
    state.audits.iter().map(|row| row.audit_seq).max().unwrap_or(0) + 1
}

fn heartbeat_effect(
    session_id: &str,
    wall_ms: i64,
    elapsed_ms: i64,
    boot_tag: &str,
    kind: i32,
) -> LedgerEffect {
    LedgerEffect::SetHeartbeatAnchor {
        session_id: session_id.to_string(),
        wall_ms,
        elapsed_ms,
        boot_tag: boot_tag.to_string(),
        heartbeat_kind: kind,
    }
}

/// Turns a closed session's segments into per-day ledger rows. Each slice carries a deterministic
/// `slice_id`, so replaying the same finish can never double-book the same span (架构契约 §6).
fn slice_into_ledger(
    state: &DomainState,
    session: &SessionRecord,
    segment: &WorkSegment,
    effects: &mut Vec<LedgerEffect>,
    notices: &mut Vec<Notice>,
) -> Result<(), DomainError> {
    let Some(end_wall_ms) = segment.end_wall_ms else {
        return Ok(());
    };
    let title = if segment.title_snapshot.is_empty() {
        state
            .tasks
            .iter()
            .find(|task| task.task_id == session.task_id)
            .map(|task| task.title.clone())
            .unwrap_or_default()
    } else {
        segment.title_snapshot.clone()
    };
    let revisions = vec![TitleRevision {
        effective_wall_ms: segment.start_wall_ms,
        title,
    }];
    let slices = temporal::slice_interval(
        &segment.session_id,
        segment.seg_seq,
        segment.start_wall_ms,
        end_wall_ms,
        &state.zone_id,
        segment.zone_epoch_seq,
        revisions,
    )?;
    let mut seq = next_ledger_seq(state);
    for slice in slices {
        let seconds = (slice.end_wall_ms - slice.start_wall_ms) / 1000;
        if seconds <= 0 {
            continue;
        }
        let row = LedgerRow {
            ledger_seq: seq,
            kind: ledger::KIND_SLICE,
            ref_id: Some(slice.slice_id.clone()),
            task_id: session.task_id.clone(),
            app_date: slice.app_date.iso.clone(),
            occurred_wall_ms: slice.start_wall_ms,
            zone_epoch_seq: slice.app_date.zone_epoch_seq,
            delta_seconds: Some(seconds),
            set_total_seconds: None,
            created_wall_ms: end_wall_ms,
            edited_at_ms: None,
            is_deleted: false,
            deleted_at_ms: None,
        };
        seq += 1;
        effects.push(LedgerEffect::AppendLedgerEntry { row });
    }
    notices.push(Notice {
        code: "SessionBooked".to_string(),
        affected_app_date: None,
        resulting_total_seconds: Some(session::effective_seconds(
            &session.session_id,
            &state.segments_for(&session.session_id),
            end_wall_ms,
        )),
        detail: segment.segment_id.clone(),
    });
    Ok(())
}

impl DomainState {
    fn segments_for(&self, session_id: &str) -> Vec<WorkSegment> {
        self.segments
            .iter()
            .filter(|segment| segment.session_id == session_id)
            .cloned()
            .collect()
    }

    fn open_segment(&self, session_id: &str) -> Option<&WorkSegment> {
        self.segments
            .iter()
            .find(|segment| segment.session_id == session_id && segment.end_wall_ms.is_none())
    }

    fn session_segments(&self, session_id: &str) -> Vec<WorkSegment> {
        let mut segments: Vec<WorkSegment> = self
            .segments
            .iter()
            .filter(|segment| segment.session_id == session_id)
            .cloned()
            .collect();
        segments.sort_by_key(|segment| segment.seg_seq);
        segments
    }
}

/// Cuts the running segment at `at_wall_ms`, books the piece that just ended with its own title
/// snapshot, and opens the next piece with `new_title` (AC-14).
///
/// The rename is what creates this boundary, so the split is *not* a pause: the session keeps
/// running, `seg_seq` advances, and the interval is preserved end to end because the old piece ends
/// exactly where the new one starts.
fn split_running_segment(
    state: &DomainState,
    session_id: &str,
    at_wall_ms: i64,
    new_title: &str,
    effects: &mut Vec<LedgerEffect>,
    notices: &mut Vec<Notice>,
) -> Result<(), DomainError> {
    let record = find_session(state, session_id)?;
    let Some(segment) = state.open_segment(session_id).cloned() else {
        return Err(DomainError::PreconditionFailed {
            reason: "no open segment to split".to_string(),
        });
    };
    if at_wall_ms <= segment.start_wall_ms {
        // A rename stamped at or before the segment start needs no cut: the segment has not
        // invested any time under the old name yet, so relabelling it is lossless.
        if at_wall_ms < segment.start_wall_ms {
            return Ok(());
        }
        let mut relabelled = segment.clone();
        relabelled.title_snapshot = new_title.to_string();
        effects.push(LedgerEffect::UpdateSession {
            session: record.clone(),
            closed_segment: Some(relabelled),
            opened_segment: None,
        });
        return Ok(());
    }

    let mut closed = segment.clone();
    closed.end_wall_ms = Some(at_wall_ms);
    effects.push(LedgerEffect::CloseSegment {
        segment_id: closed.segment_id.clone(),
        end_wall_ms: at_wall_ms,
    });
    let next_seq = state
        .session_segments(session_id)
        .iter()
        .map(|item| item.seg_seq)
        .max()
        .unwrap_or(0)
        + 1;
    let opened = WorkSegment {
        segment_id: format!("{session_id}:{next_seq}"),
        session_id: session_id.to_string(),
        seg_seq: next_seq,
        start_wall_ms: at_wall_ms,
        end_wall_ms: None,
        title_snapshot: new_title.to_string(),
        zone_epoch_seq: state.zone_epoch_seq,
        derived: false,
    };
    effects.push(LedgerEffect::UpdateSession {
        session: record.clone(),
        closed_segment: Some(closed.clone()),
        opened_segment: Some(opened),
    });
    // The closed piece is booked with the title it actually ran under.
    let mut with_segment = state.clone();
    if let Some(slot) = with_segment
        .segments
        .iter_mut()
        .find(|item| item.segment_id == closed.segment_id)
    {
        slot.end_wall_ms = Some(at_wall_ms);
    }
    slice_into_ledger(&with_segment, record, &closed, effects, notices)?;
    Ok(())
}

fn finish_session(
    state: &DomainState,
    session_id: &str,
    end_wall_ms: i64,
    elapsed_ms: i64,
    boot_tag: &str,
    effects: &mut Vec<LedgerEffect>,
    notices: &mut Vec<Notice>,
) -> Result<SessionRecord, DomainError> {
    let record = find_session(state, session_id)?;
    // AC-09 / 架构契约 §7.3: a session the user has not yet adjudicated has exactly one exit,
    // `ResolveRecovery`, which is where the trusted/gap arithmetic lives. `MarkRecoveryPending` keeps
    // the session in the active slot (that is what makes the prompt reappear), so the ordinary finish
    // paths still recognize it as "the active session" and would close its open segment at the command
    // instant — booking the entire unverified span as investment. All three of them
    // (`CompleteTaskWhileRunning`, `FinishSession` and `StartSession { replaces_session_id }`) funnel
    // through this helper, so the refusal belongs here: no form of finishing that is not the user's
    // recovery decision may reach the segment.
    if record.state == SessionState::RecoveryPending {
        return Err(DomainError::PreconditionFailed {
            reason: format!("session {session_id} awaits the recovery decision"),
        });
    }
    if record.state == SessionState::Finished {
        return Err(DomainError::SessionAlreadyFinished {
            session_id: session_id.to_string(),
        });
    }
    let mut updated = record.clone();
    let open = state.open_segment(session_id).cloned();
    let mut closed = None;
    if let Some(mut segment) = open {
        segment.end_wall_ms = Some(end_wall_ms.max(segment.start_wall_ms));
        effects.push(LedgerEffect::CloseSegment {
            segment_id: segment.segment_id.clone(),
            end_wall_ms: segment.end_wall_ms.unwrap_or(end_wall_ms),
        });
        closed = Some(segment);
    }
    updated.state = SessionState::Finished;
    updated.finished_wall_ms = Some(end_wall_ms);
    effects.push(LedgerEffect::UpdateSession {
        session: updated.clone(),
        closed_segment: closed.clone(),
        opened_segment: None,
    });
    effects.push(heartbeat_effect(
        session_id,
        end_wall_ms,
        elapsed_ms,
        boot_tag,
        HEARTBEAT_KIND_TRANSITION,
    ));
    if let Some(segment) = &closed {
        let mut with_segment = state.clone();
        if let Some(slot) = with_segment
            .segments
            .iter_mut()
            .find(|s| s.segment_id == segment.segment_id)
        {
            slot.end_wall_ms = segment.end_wall_ms;
        }
        slice_into_ledger(&with_segment, &updated, segment, effects, notices)?;
    }
    Ok(updated)
}

#[allow(clippy::too_many_lines)]
fn execute(
    state: &DomainState,
    command: &DomainCommand,
    envelope: &CommandEnvelope,
) -> Result<(DomainState, Vec<LedgerEffect>, Vec<Notice>), DomainError> {
    let now = envelope.issued_at.wall_ms;
    let mut next = state.clone();
    let mut effects: Vec<LedgerEffect> = Vec::new();
    let mut notices: Vec<Notice> = Vec::new();

    match command {
        DomainCommand::CreateTask { kind, title, note } => {
            let title = validate_title(title)?;
            validate_note(note)?;
            let sort_key = next
                .tasks
                .iter()
                .filter(|task| task.kind == *kind && task.archived_at_ms.is_none())
                .map(|task| task.sort_key)
                .max()
                .unwrap_or(0)
                + SORT_KEY_STEP;
            let task = TaskRecord {
                task_id: format!("task:{}", envelope.command_id),
                kind: *kind,
                title,
                note: note.clone(),
                sort_key,
                created_wall_ms: now,
                archived_at_ms: None,
                last_countdown_minutes: DEFAULT_COUNTDOWN_MINUTES,
                art_asset_id: None,
            };
            next.tasks.push(task.clone());
            effects.push(LedgerEffect::UpsertTask { task });
        }
        DomainCommand::RenameTask { task_id, title } => {
            let title = validate_title(title)?;
            find_task(state, task_id)?;
            if let Some(task) = next.tasks.iter_mut().find(|task| task.task_id == *task_id) {
                task.title = title.clone();
            }
            // The task row carries the *current* title (规格 5: 「当前标题与备注」) and every reader —
            // the today card, the records header, the ledger projection's fallback — loads it from
            // there. Without this effect the new name would live only in this in-memory state and the
            // next load would show the old one again (AC-14 「今天新标题」).
            if let Some(task) = next.tasks.iter().find(|task| task.task_id == *task_id) {
                effects.push(LedgerEffect::UpsertTask { task: task.clone() });
            }
            effects.push(LedgerEffect::RenameTitleRevision {
                task_id: task_id.clone(),
                revision: TitleRevision {
                    effective_wall_ms: now,
                    title: title.clone(),
                },
                zone_epoch_seq: state.zone_epoch_seq,
            });
            // AC-14: the instance for *today* shows the new name from now on, while the instances of
            // earlier days keep the name they had on their own day. The task identity and its
            // cumulative time are untouched — only a display label of one day changes.
            let today = temporal::app_date_of(now, &state.zone_id, state.zone_epoch_seq)?.iso;
            for occurrence in next
                .occurrences
                .iter_mut()
                .filter(|item| item.task_id == *task_id && item.app_date == today)
            {
                occurrence.display_title_snapshot = title.clone();
                effects.push(LedgerEffect::UpsertOccurrence {
                    occurrence: occurrence.clone(),
                });
            }
            // AC-14: a rename *splits the timing interval at this instant*, so the piece before it is
            // snapshotted with the old name and the piece after it starts with the new one.
            if let Some(active) = state.active_session_id.clone() {
                let running = state.sessions.iter().any(|record| {
                    record.session_id == active
                        && record.task_id == *task_id
                        && record.state == SessionState::Running
                });
                if running {
                    split_running_segment(state, &active, now, &title, &mut effects, &mut notices)?;
                }
            }
        }
        DomainCommand::EditNote { task_id, note } => {
            validate_note(note)?;
            find_task(state, task_id)?;
            if let Some(task) = next.tasks.iter_mut().find(|task| task.task_id == *task_id) {
                task.note = note.clone();
            }
            if let Some(task) = next.tasks.iter().find(|task| task.task_id == *task_id) {
                effects.push(LedgerEffect::UpsertTask { task: task.clone() });
            }
        }
        DomainCommand::ArchiveTask { task_id } | DomainCommand::UnarchiveTask { task_id } => {
            find_task(state, task_id)?;
            let archived = matches!(command, DomainCommand::ArchiveTask { .. });
            if let Some(task) = next.tasks.iter_mut().find(|task| task.task_id == *task_id) {
                task.archived_at_ms = if archived { Some(now) } else { None };
                effects.push(LedgerEffect::UpsertTask { task: task.clone() });
            }
            notices.push(Notice {
                code: if archived {
                    "TaskArchived"
                } else {
                    "TaskUnarchived"
                }
                .to_string(),
                affected_app_date: None,
                resulting_total_seconds: None,
                detail: task_id.clone(),
            });
        }
        DomainCommand::ReorderTasks {
            kind,
            ordered_task_ids,
        } => {
            for task_id in ordered_task_ids {
                let task = find_task(state, task_id)?;
                if task.kind != *kind {
                    return Err(DomainError::PreconditionFailed {
                        reason: format!("task {task_id} is not in the reordered group"),
                    });
                }
            }
            for (index, task_id) in ordered_task_ids.iter().enumerate() {
                if let Some(task) = next.tasks.iter_mut().find(|task| task.task_id == *task_id) {
                    task.sort_key = (index as i64 + 1) * SORT_KEY_STEP;
                    effects.push(LedgerEffect::UpsertTask { task: task.clone() });
                }
            }
        }
        DomainCommand::SetTaskRecentCountdown { task_id, minutes } => {
            if *minutes < MIN_COUNTDOWN_MINUTES || *minutes > MAX_COUNTDOWN_MINUTES {
                return Err(DomainError::CountdownMinutesOutOfRange {
                    min: MIN_COUNTDOWN_MINUTES,
                    max: MAX_COUNTDOWN_MINUTES,
                });
            }
            find_task(state, task_id)?;
            if let Some(task) = next.tasks.iter_mut().find(|task| task.task_id == *task_id) {
                task.last_countdown_minutes = *minutes;
                effects.push(LedgerEffect::UpsertTask { task: task.clone() });
            }
        }
        DomainCommand::EnsureOccurrence { task_id, app_date } => {
            let task = find_task(state, task_id)?;
            temporal::parse_app_date(app_date)?;
            let occurrence_id = occurrence::occurrence_id(task_id, app_date);
            if state
                .occurrences
                .iter()
                .any(|item| item.occurrence_id == occurrence_id)
            {
                notices.push(Notice {
                    code: "OccurrenceExists".to_string(),
                    affected_app_date: Some(app_date.clone()),
                    resulting_total_seconds: None,
                    detail: occurrence_id,
                });
                return Ok((next, effects, notices));
            }
            if task.archived_at_ms.is_some() {
                notices.push(Notice {
                    code: "ArchivedNoOccurrence".to_string(),
                    affected_app_date: Some(app_date.clone()),
                    resulting_total_seconds: None,
                    detail: task_id.clone(),
                });
                return Ok((next, effects, notices));
            }
            let created = OccurrenceRecord {
                occurrence_id: occurrence_id.clone(),
                task_id: task_id.clone(),
                app_date: app_date.clone(),
                zone_epoch_seq: state.zone_epoch_seq,
                display_title_snapshot: task.title.clone(),
                created_at_ms: now,
                is_completed: false,
                completed_wall_ms: None,
                derived_from_event_high_water: 0,
            };
            upsert_occurrence(&mut next, created.clone());
            effects.push(LedgerEffect::UpsertOccurrence { occurrence: created });
        }
        DomainCommand::SetOccurrenceCompletion {
            task_id,
            app_date,
            completed,
        } => {
            let occurrence_id = occurrence::occurrence_id(task_id, app_date);
            let Some(existing) = state
                .occurrences
                .iter()
                .find(|item| item.occurrence_id == occurrence_id)
                .cloned()
            else {
                return Err(DomainError::OccurrenceNotFound {
                    task_id: task_id.clone(),
                    app_date: app_date.clone(),
                });
            };
            let action = if *completed {
                ACTION_COMPLETE
            } else {
                ACTION_REOPEN
            };
            let event_seq = next_event_seq(state);
            let event = OccurrenceCompletionEvent {
                event_seq,
                occurrence_id: occurrence_id.clone(),
                action,
                occurred_wall_ms: now,
                app_date: app_date.clone(),
                zone_epoch_seq: state.zone_epoch_seq,
            };
            effects.push(LedgerEffect::AppendOccurrenceCompletionEvent { event: event.clone() });
            let mut updated = existing;
            updated.is_completed = *completed;
            updated.completed_wall_ms = if *completed { Some(now) } else { None };
            updated.derived_from_event_high_water = event_seq;
            upsert_occurrence(&mut next, updated.clone());
            set_high_water(&mut next, &occurrence_id, event_seq);
            effects.push(LedgerEffect::UpsertOccurrence { occurrence: updated });
        }
        DomainCommand::RebuildOccurrenceView {
            occurrence_id,
            events,
        } => {
            let Some(existing) = state
                .occurrences
                .iter()
                .find(|item| item.occurrence_id == *occurrence_id)
                .cloned()
            else {
                return Err(DomainError::OccurrenceNotFound {
                    task_id: String::new(),
                    app_date: String::new(),
                });
            };
            // A derivation, not a new fact: replay the authoritative events the caller read from
            // Room and write back the same columns the incremental path maintains, so the two can
            // be compared. The replayed flag is adopted only when the replay covers every event
            // already accounted for, so a partial event list can never erase a newer fact.
            let derived = occurrence::replay_view(events, occurrence_id);
            let authoritative = derived.high_water >= existing.derived_from_event_high_water;
            let mut rebuilt = existing;
            if authoritative {
                rebuilt.is_completed = derived.is_completed;
                rebuilt.completed_wall_ms = derived.completed_wall_ms;
            }
            rebuilt.derived_from_event_high_water =
                rebuilt.derived_from_event_high_water.max(derived.high_water);
            upsert_occurrence(&mut next, rebuilt.clone());
            set_high_water(&mut next, occurrence_id, rebuilt.derived_from_event_high_water);
            effects.push(LedgerEffect::UpsertOccurrence { occurrence: rebuilt });
        }
        DomainCommand::CompleteTemporary { task_id } => {
            let task_title = require_temporary_task(state, task_id)?;
            // The event carries a title snapshot of this moment. `event_seq` is left at 0: the
            // sequence number is assigned by the room table's AUTOINCREMENT (架构契约 §4.2), and
            // Room returns the authoritative value to the next state load.
            effects.push(LedgerEffect::AppendTemporaryCompletionEvent {
                task_id: task_id.clone(),
                action: temporary::ACTION_COMPLETE,
                occurred_wall_ms: now,
                app_date: temporal::day_label_of(now, &state.zone_id, state.zone_epoch_seq)?,
                title_snapshot: task_title,
            });
        }
        DomainCommand::ReopenTemporary { task_id } => {
            let task_title = require_temporary_task(state, task_id)?;
            effects.push(LedgerEffect::AppendTemporaryCompletionEvent {
                task_id: task_id.clone(),
                action: temporary::ACTION_REOPEN,
                occurred_wall_ms: now,
                app_date: temporal::day_label_of(now, &state.zone_id, state.zone_epoch_seq)?,
                title_snapshot: task_title,
            });
        }
        DomainCommand::StartSession {
            task_id,
            mode,
            target_seconds,
            replaces_session_id,
        } => {
            find_task(state, task_id)?;
            if let Some(active) = &state.active_session_id {
                match replaces_session_id {
                    Some(replaces) if replaces == active => {
                        let mut closed_effects: Vec<LedgerEffect> = Vec::new();
                        let mut closed_notices: Vec<Notice> = Vec::new();
                        let finished = finish_session(
                            state,
                            active,
                            now,
                            envelope.issued_at.elapsed_ms,
                            &envelope.issued_at.boot_tag,
                            &mut closed_effects,
                            &mut closed_notices,
                        )?;
                        upsert_session(&mut next, finished);
                        if let Some(segment) = state.open_segment(active).cloned() {
                            if let Some(slot) = next
                                .segments
                                .iter_mut()
                                .find(|s| s.segment_id == segment.segment_id)
                            {
                                slot.end_wall_ms = Some(now);
                            }
                        }
                        effects.extend(closed_effects);
                        notices.extend(closed_notices);
                        next.active_session_id = None;
                        effects.push(LedgerEffect::SetActiveSession { session_id: None });
                    }
                    _ => {
                        return Err(DomainError::ConcurrentSession {
                            active_session_id: active.clone(),
                        });
                    }
                }
            }
            if matches!(mode, SessionMode::Countdown) && target_seconds.unwrap_or(0) <= 0 {
                return Err(DomainError::PreconditionFailed {
                    reason: "countdown requires target_seconds > 0".to_string(),
                });
            }
            let session_id = format!("sess:{}", envelope.command_id);
            let record = SessionRecord {
                session_id: session_id.clone(),
                task_id: task_id.clone(),
                mode: *mode,
                target_seconds: *target_seconds,
                state: SessionState::Running,
                created_wall_ms: now,
                finished_wall_ms: None,
                source: "ui".to_string(),
            };
            let title = find_task(state, task_id)?.title.clone();
            let segment = WorkSegment {
                segment_id: format!("{session_id}:1"),
                session_id: session_id.clone(),
                seg_seq: 1,
                start_wall_ms: now,
                end_wall_ms: None,
                title_snapshot: title,
                zone_epoch_seq: state.zone_epoch_seq,
                derived: false,
            };
            next.active_session_id = Some(session_id.clone());
            next.segments.push(segment.clone());
            upsert_session(&mut next, record.clone());
            // Order matters: `active_session_slot.session_id` is a foreign key onto
            // `focus_session`, so the session row must be persisted before the slot points at it.
            // Kotlin applies these in list order inside one transaction.
            effects.push(LedgerEffect::OpenSession {
                session: record,
                segment,
            });
            effects.push(LedgerEffect::SetActiveSession {
                session_id: Some(session_id.clone()),
            });
            effects.push(heartbeat_effect(
                &session_id,
                now,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                HEARTBEAT_KIND_TRANSITION,
            ));
        }
        DomainCommand::PauseSession { session_id } => {
            let record = find_session(state, session_id)?;
            if record.state != SessionState::Running {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not running".to_string(),
                });
            }
            let Some(segment) = state.open_segment(session_id).cloned() else {
                return Err(DomainError::PreconditionFailed {
                    reason: "no open segment".to_string(),
                });
            };
            let mut closed = segment.clone();
            closed.end_wall_ms = Some(now.max(closed.start_wall_ms));
            let mut updated = record.clone();
            updated.state = SessionState::Paused;
            if let Some(slot) = next
                .segments
                .iter_mut()
                .find(|s| s.segment_id == closed.segment_id)
            {
                slot.end_wall_ms = closed.end_wall_ms;
            }
            upsert_session(&mut next, updated.clone());
            effects.push(LedgerEffect::CloseSegment {
                segment_id: closed.segment_id.clone(),
                end_wall_ms: now,
            });
            effects.push(LedgerEffect::UpdateSession {
                session: updated,
                closed_segment: Some(closed.clone()),
                opened_segment: None,
            });
            effects.push(heartbeat_effect(
                session_id,
                now,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                HEARTBEAT_KIND_TRANSITION,
            ));
            // A segment that closes is booked immediately, so a pause never drops the span that
            // just ended (AC-05). Segments closed later are booked by the same helper at finish.
            slice_into_ledger(state, record, &closed, &mut effects, &mut notices)?;
        }
        DomainCommand::ResumeSession { session_id } => {
            let record = find_session(state, session_id)?;
            if record.state != SessionState::Paused {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not paused".to_string(),
                });
            }
            let seg_seq = state
                .session_segments(session_id)
                .iter()
                .map(|segment| segment.seg_seq)
                .max()
                .unwrap_or(0)
                + 1;
            let title = find_task(state, &record.task_id)?.title.clone();
            let segment = WorkSegment {
                segment_id: format!("{session_id}:{seg_seq}"),
                session_id: session_id.clone(),
                seg_seq,
                start_wall_ms: now,
                end_wall_ms: None,
                title_snapshot: title,
                zone_epoch_seq: state.zone_epoch_seq,
                derived: false,
            };
            let mut updated = record.clone();
            updated.state = SessionState::Running;
            next.segments.push(segment.clone());
            upsert_session(&mut next, updated.clone());
            effects.push(LedgerEffect::UpdateSession {
                session: updated,
                closed_segment: None,
                opened_segment: Some(segment),
            });
            effects.push(heartbeat_effect(
                session_id,
                now,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                HEARTBEAT_KIND_TRANSITION,
            ));
        }
        DomainCommand::FinishSession {
            session_id,
            completion,
        } => {
            if state.active_session_id.as_deref() != Some(session_id.as_str()) {
                return Err(DomainError::SessionNotFound {
                    session_id: session_id.clone(),
                });
            }
            let end = match completion {
                CompletionChoice::Now => now,
                CompletionChoice::AfterSeconds { seconds } => {
                    if *seconds < 0 {
                        return Err(DomainError::NegativeSeconds);
                    }
                    // `seconds` is the session's invested total — the number the screen or the
                    // service is showing — so the end instant is derived from the segments rather
                    // than applied to whichever segment happens to be open (see
                    // `session::total_end_wall_ms`). A session with no open segment (paused) has
                    // nothing left to close and simply ends now, which is what makes 提前结束 work
                    // while paused.
                    session::total_end_wall_ms(&state.segments_for(session_id), session_id, *seconds)
                        .unwrap_or(now)
                }
            };
            let finished = finish_session(
                state,
                session_id,
                end,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                &mut effects,
                &mut notices,
            )?;
            upsert_session(&mut next, finished);
            if let Some(segment) = state.open_segment(session_id).cloned() {
                if let Some(slot) = next
                    .segments
                    .iter_mut()
                    .find(|s| s.segment_id == segment.segment_id)
                {
                    slot.end_wall_ms = Some(end);
                }
            }
            next.active_session_id = None;
            effects.push(LedgerEffect::SetActiveSession { session_id: None });
        }
        DomainCommand::CompleteTaskWhileRunning {
            session_id,
            target_app_date,
        } => {
            if state.active_session_id.as_deref() != Some(session_id.as_str()) {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not the active one".to_string(),
                });
            }
            let record = find_session(state, session_id)?;
            let task_id = record.task_id.clone();
            let app_date = match target_app_date {
                Some(iso) => iso.clone(),
                None => temporal::app_date_of(now, &state.zone_id, state.zone_epoch_seq)?.iso,
            };
            let finished = finish_session(
                state,
                session_id,
                now,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                &mut effects,
                &mut notices,
            )?;
            upsert_session(&mut next, finished);
            if let Some(segment) = state.open_segment(session_id).cloned() {
                if let Some(slot) = next
                    .segments
                    .iter_mut()
                    .find(|s| s.segment_id == segment.segment_id)
                {
                    slot.end_wall_ms = Some(now);
                }
            }
            next.active_session_id = None;
            effects.push(LedgerEffect::SetActiveSession { session_id: None });
            // "Complete the task the timer is running on" means different things per kind, and both
            // are event-sourced: a temporary task gets a todo/done event (AC-11), a daily task gets a
            // per-day completion event (AC-03/AC-07).
            let task = find_task(state, &task_id)?;
            if task.kind == TaskKind::Temporary {
                effects.push(LedgerEffect::AppendTemporaryCompletionEvent {
                    task_id: task_id.clone(),
                    action: temporary::ACTION_COMPLETE,
                    occurred_wall_ms: now,
                    app_date: app_date.clone(),
                    title_snapshot: task.title.clone(),
                });
            } else {
                let occurrence_id = occurrence::occurrence_id(&task_id, &app_date);
                // A lazy instance may not exist yet; create it in the same command so the completion
                // event never dangles without its occurrence row.
                if !next
                    .occurrences
                    .iter()
                    .any(|item| item.occurrence_id == occurrence_id)
                {
                    if task.archived_at_ms.is_some() {
                        return Err(DomainError::PreconditionFailed {
                            reason: format!("task {task_id} is archived"),
                        });
                    }
                    let created = OccurrenceRecord {
                        occurrence_id: occurrence_id.clone(),
                        task_id: task_id.clone(),
                        app_date: app_date.clone(),
                        zone_epoch_seq: state.zone_epoch_seq,
                        display_title_snapshot: task.title.clone(),
                        created_at_ms: now,
                        is_completed: false,
                        completed_wall_ms: None,
                        derived_from_event_high_water: 0,
                    };
                    upsert_occurrence(&mut next, created.clone());
                    effects.push(LedgerEffect::UpsertOccurrence { occurrence: created });
                }
                let event_seq = next_event_seq(state);
                let event = OccurrenceCompletionEvent {
                    event_seq,
                    occurrence_id: occurrence_id.clone(),
                    action: ACTION_COMPLETE,
                    occurred_wall_ms: now,
                    app_date: app_date.clone(),
                    zone_epoch_seq: state.zone_epoch_seq,
                };
                effects.push(LedgerEffect::AppendOccurrenceCompletionEvent { event });
                set_high_water(&mut next, &occurrence_id, event_seq);
                if let Some(existing) = next
                    .occurrences
                    .iter_mut()
                    .find(|item| item.occurrence_id == occurrence_id)
                {
                    existing.is_completed = true;
                    existing.completed_wall_ms = Some(now);
                    existing.derived_from_event_high_water = event_seq;
                    effects.push(LedgerEffect::UpsertOccurrence {
                        occurrence: existing.clone(),
                    });
                }
            }
        }
        DomainCommand::AddManualSeconds {
            task_id,
            app_date,
            seconds,
        } => {
            find_task(state, task_id)?;
            temporal::parse_app_date(app_date)?;
            if *seconds <= 0 {
                return Err(DomainError::NegativeSeconds);
            }
            let seq = next_ledger_seq(state);
            let row = LedgerRow {
                ledger_seq: seq,
                kind: ledger::KIND_MANUAL_ADD,
                ref_id: None,
                task_id: task_id.clone(),
                app_date: app_date.clone(),
                occurred_wall_ms: now,
                zone_epoch_seq: state.zone_epoch_seq,
                delta_seconds: Some(*seconds),
                set_total_seconds: None,
                created_wall_ms: now,
                edited_at_ms: None,
                is_deleted: false,
                deleted_at_ms: None,
            };
            next.ledger.push(row.clone());
            effects.push(LedgerEffect::AppendLedgerEntry { row });
        }
        DomainCommand::SetDailyTotalSeconds {
            task_id,
            app_date,
            total_seconds,
        } => {
            find_task(state, task_id)?;
            temporal::parse_app_date(app_date)?;
            if *total_seconds < 0 {
                return Err(DomainError::NegativeSeconds);
            }
            let seq = next_ledger_seq(state);
            let row = LedgerRow {
                ledger_seq: seq,
                kind: ledger::KIND_SET_TOTAL,
                ref_id: None,
                task_id: task_id.clone(),
                app_date: app_date.clone(),
                occurred_wall_ms: now,
                zone_epoch_seq: state.zone_epoch_seq,
                delta_seconds: None,
                set_total_seconds: Some(*total_seconds),
                created_wall_ms: now,
                edited_at_ms: None,
                is_deleted: false,
                deleted_at_ms: None,
            };
            next.ledger.push(row.clone());
            effects.push(LedgerEffect::AppendLedgerEntry { row });
        }
        DomainCommand::EditLedgerEntry {
            ledger_seq,
            new_delta_seconds,
            new_set_total_seconds,
        } => {
            let row = find_row(state, *ledger_seq)?.clone();
            ledger::validate_edit(row.kind, *new_delta_seconds, *new_set_total_seconds)?;
            let audit = ledger::edit_audit(
                next_audit_seq(state),
                &row,
                *new_delta_seconds,
                *new_set_total_seconds,
                now,
            );
            next.audits.push(audit.clone());
            effects.push(LedgerEffect::InsertLedgerEntryAudit { audit });
            let mut updated = row.clone();
            updated.delta_seconds = *new_delta_seconds;
            updated.set_total_seconds = *new_set_total_seconds;
            updated.edited_at_ms = Some(now);
            if let Some(slot) = next.ledger.iter_mut().find(|item| item.ledger_seq == *ledger_seq) {
                *slot = updated.clone();
            }
            effects.push(LedgerEffect::UpdateLedgerEntry { row: updated });
        }
        DomainCommand::DeleteLedgerEntry { ledger_seq } => {
            let row = find_row(state, *ledger_seq)?.clone();
            let audit = ledger::delete_audit(next_audit_seq(state), &row, now);
            next.audits.push(audit.clone());
            effects.push(LedgerEffect::InsertLedgerEntryAudit { audit });
            if let Some(slot) = next.ledger.iter_mut().find(|item| item.ledger_seq == *ledger_seq) {
                slot.is_deleted = true;
                slot.deleted_at_ms = Some(now);
            }
            effects.push(LedgerEffect::SoftDeleteLedgerEntry {
                ledger_seq: *ledger_seq,
                deleted_at_ms: now,
            });
            if row.kind == ledger::KIND_SET_TOTAL {
                notices.push(Notice {
                    code: "SetTotalDeleted".to_string(),
                    affected_app_date: Some(row.app_date.clone()),
                    resulting_total_seconds: Some(ledger::total_without(&state.ledger, *ledger_seq)),
                    detail: format!("ledger_seq={ledger_seq}"),
                });
            }
        }
        DomainCommand::RestoreLedgerEntry { ledger_seq } => {
            // A deleted row is invisible to `find_row` by design (every other command must not see
            // it), so the undo looks it up directly.
            let row = state
                .ledger
                .iter()
                .find(|item| item.ledger_seq == *ledger_seq)
                .cloned()
                .ok_or_else(|| DomainError::PreconditionFailed {
                    reason: format!("ledger row {ledger_seq} not found"),
                })?;
            // Undoing a delete only makes sense on a row that is currently deleted. Anything else is
            // a stale UI action, not a domain operation.
            if !row.is_deleted {
                return Err(DomainError::PreconditionFailed {
                    reason: format!("ledger row {ledger_seq} is not deleted"),
                });
            }
            let audit = ledger::restore_audit(next_audit_seq(state), &row, now);
            next.audits.push(audit.clone());
            effects.push(LedgerEffect::InsertLedgerEntryAudit { audit });
            if let Some(slot) = next.ledger.iter_mut().find(|item| item.ledger_seq == *ledger_seq) {
                slot.is_deleted = false;
                slot.deleted_at_ms = None;
            }
            // In place, so the restored row keeps its original replay slot. Restoring also means
            // writing back the pre-delete values, because a delete cleared nothing but a restore
            // must be safe against a row that had been edited after (or before) it was deleted.
            effects.push(LedgerEffect::UpdateLedgerEntry {
                row: LedgerRow {
                    is_deleted: false,
                    deleted_at_ms: None,
                    ..row
                },
            });
        }
        DomainCommand::AppendZoneEpoch {
            zone_id,
            impact_summary,
        } => {
            temporal::zone_of(zone_id)?;
            let epoch_seq = state.zone_epoch_seq + 1;
            let epoch = ZoneEpoch {
                epoch_seq,
                zone_id: zone_id.clone(),
                effective_wall_ms: now,
                impact_summary: impact_summary.clone(),
                created_wall_ms: now,
            };
            next.zone_epoch_seq = epoch_seq;
            next.zone_id = zone_id.clone();
            next.zone_epochs.push(epoch.clone());
            effects.push(LedgerEffect::UpsertZoneEpoch { epoch });
        }
        DomainCommand::MarkRecoveryPending { session_id } => {
            let record = find_session(state, session_id)?;
            if state.active_session_id.as_deref() != Some(session_id)
                || (record.state != SessionState::Running && record.state != SessionState::Paused)
            {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not recoverable".to_string(),
                });
            }
            let mut updated = record.clone();
            updated.state = SessionState::RecoveryPending;
            upsert_session(&mut next, updated.clone());
            effects.push(LedgerEffect::UpdateSession {
                session: updated,
                closed_segment: None,
                opened_segment: None,
            });
        }
        DomainCommand::Heartbeat { session_id } => {
            let record = find_session(state, session_id)?;
            if record.state != SessionState::Running || state.active_session_id.as_deref() != Some(session_id)
            {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not running".to_string(),
                });
            }
            effects.push(heartbeat_effect(
                session_id,
                now,
                envelope.issued_at.elapsed_ms,
                &envelope.issued_at.boot_tag,
                HEARTBEAT_KIND_PERIODIC,
            ));
        }
        DomainCommand::ResolveRecovery { session_id, choice } => {
            let record = find_session(state, session_id)?;
            // 架构契约 §6: the recovery action is idempotent because `slice_id` is a pure function of
            // the recovered span. Re-running an already-resolved recovery must therefore change
            // nothing at all — no second segment close, no second ledger row — so the day's total
            // cannot double when the user's retry lands twice.
            let already_resolved = record.state == SessionState::Finished
                && record.source == "recovery"
                && state.open_segment(session_id).is_none();
            if already_resolved {
                notices.push(Notice {
                    code: "RecoveryAlreadyResolved".to_string(),
                    affected_app_date: None,
                    resulting_total_seconds: None,
                    detail: session_id.clone(),
                });
                return Ok((next, effects, notices));
            }
            if record.state != SessionState::RecoveryPending
                && record.state != SessionState::Running
                && record.state != SessionState::Paused
            {
                return Err(DomainError::PreconditionFailed {
                    reason: "session is not pending recovery".to_string(),
                });
            }
            let segments = state.segments_for(session_id);
            let heartbeat_of = |fallback: i64| {
                state
                    .heartbeats
                    .iter()
                    .filter(|beat| beat.session_id == *session_id)
                    .map(|beat| beat.wall_ms)
                    .max()
                    .unwrap_or(fallback)
            };
            // A session that was *paused* when it was lost has no open interval left: the pause
            // already closed and booked its segment, and `[heartbeat, now]` is paused time, which
            // AC-05 never lets become investment. Resolving it therefore only closes the session —
            // no segment is stretched and no second slice is appended. Accept and Discard book
            // nothing new, and a Modify cannot invent a running span after the pause either.
            let Some(open) = state.open_segment(session_id).cloned() else {
                let already_booked = session::closed_seconds_total(&segments, session_id);
                let mut updated = record.clone();
                updated.state = SessionState::Finished;
                updated.finished_wall_ms = Some(now);
                updated.source = "recovery".to_string();
                upsert_session(&mut next, updated.clone());
                effects.push(LedgerEffect::UpdateSession {
                    session: updated,
                    closed_segment: None,
                    opened_segment: None,
                });
                next.active_session_id = None;
                effects.push(LedgerEffect::SetActiveSession { session_id: None });
                notices.push(Notice {
                    code: "RecoveryPausedNoGap".to_string(),
                    affected_app_date: None,
                    resulting_total_seconds: Some(already_booked),
                    detail: format!("booked={already_booked} chosen={choice:?}"),
                });
                return Ok((next, effects, notices));
            };
            let heartbeat = heartbeat_of(open.start_wall_ms);
            let amounts =
                session::recovery_amounts(open.start_wall_ms, heartbeat, now, choice, record.target_seconds)?;
            // `Accept`/`Discard` count the open segment's own span (what the prompt offers to book);
            // `Modify` carries the user's authoritative *session total*, which is what its field is
            // labelled with. Either way the end instant comes from the same mapping, so the closed
            // segments are never counted twice.
            let booked_total = match choice {
                RecoveryChoice::Modify { seconds } => *seconds,
                _ => session::closed_seconds_total(&segments, session_id) + amounts.booked_seconds,
            };
            let end_wall_ms =
                session::total_end_wall_ms(&segments, session_id, booked_total).unwrap_or(open.start_wall_ms);
            let mut closed = open;
            closed.derived = matches!(choice, RecoveryChoice::Accept | RecoveryChoice::Modify { .. });
            closed.end_wall_ms = Some(end_wall_ms);
            let mut updated = record.clone();
            updated.state = SessionState::Finished;
            updated.finished_wall_ms = Some(now);
            updated.source = "recovery".to_string();
            if let Some(slot) = next
                .segments
                .iter_mut()
                .find(|s| s.segment_id == closed.segment_id)
            {
                *slot = closed.clone();
            }
            upsert_session(&mut next, updated.clone());
            effects.push(LedgerEffect::UpdateSession {
                session: updated,
                closed_segment: Some(closed.clone()),
                opened_segment: None,
            });
            next.active_session_id = None;
            effects.push(LedgerEffect::SetActiveSession { session_id: None });
            slice_into_ledger(state, record, &closed, &mut effects, &mut notices)?;
            notices.push(Notice {
                code: "RecoveryResolved".to_string(),
                affected_app_date: None,
                resulting_total_seconds: Some(amounts.booked_seconds),
                detail: format!("trusted={} gap={}", amounts.trusted_seconds, amounts.gap_seconds),
            });
        }
    }

    Ok((next, effects, notices))
}

/// Pure entry point. Failures come back as `error` with the state unchanged, so Kotlin can map
/// them onto `DomainFailure` without an exception crossing the boundary (架构契约 §3.4).
pub fn reduce(state: &DomainState, command: &DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    match apply(state, command, envelope) {
        Ok((next_state, effects, notices)) => DomainOutcome {
            next_state,
            effects,
            notices,
            error: None,
        },
        Err(error) => DomainOutcome {
            next_state: state.clone(),
            effects: Vec::new(),
            notices: Vec::new(),
            error: Some(error),
        },
    }
}

fn apply(
    state: &DomainState,
    command: &DomainCommand,
    envelope: &CommandEnvelope,
) -> Result<(DomainState, Vec<LedgerEffect>, Vec<Notice>), DomainError> {
    if state
        .command_log
        .iter()
        .any(|entry| entry.command_id == envelope.command_id)
    {
        return Err(DomainError::DuplicateCommand {
            command_id: envelope.command_id.clone(),
        });
    }
    if envelope.expected_revision != state.revision {
        return Err(DomainError::RevisionConflict {
            expected: envelope.expected_revision,
            actual: state.revision,
        });
    }
    let kind = command_kind(command);
    let (mut next, mut effects, notices) = execute(state, command, envelope)?;
    next.revision = state.revision + 1;
    let result_digest = digest(&(envelope.command_id.as_str(), kind, next.revision));
    next.command_log.push(CommandLogEntry {
        command_id: envelope.command_id.clone(),
        applied_wall_ms: envelope.issued_at.wall_ms,
        command_kind: kind.to_string(),
        result_digest: result_digest.clone(),
        expected_revision: envelope.expected_revision,
        actual_revision: state.revision,
    });
    effects.push(LedgerEffect::AppendCommandLog {
        entry: CommandLogEntry {
            command_id: envelope.command_id.clone(),
            applied_wall_ms: envelope.issued_at.wall_ms,
            command_kind: kind.to_string(),
            result_digest,
            expected_revision: envelope.expected_revision,
            actual_revision: state.revision,
        },
    });
    // `next_state` and `effects` are two views of one change: the effects are the persistence
    // intent Kotlin writes in a single transaction, and the state is what the next command in the
    // same Rust session sees. They must never disagree, so the effects are folded back in here
    // rather than being maintained separately by every command arm above.
    Ok((project_effects(next, &effects), effects, notices))
}

/// Folds `effects` into `state` so the returned state is exactly what Kotlin will persist.
///
/// Only *durable* effects are projected. Pure transportation effects (`SetActiveSession`,
/// `SetHeartbeatAnchor`) carry no persisted payload here; the caller persists those from the
/// effect list, and the domain's view of the active session is already set by the command arm.
fn project_effects(mut state: DomainState, effects: &[LedgerEffect]) -> DomainState {
    for effect in effects {
        match effect {
            LedgerEffect::AppendLedgerEntry { row } => {
                if !state
                    .ledger
                    .iter()
                    .any(|existing| existing.ledger_seq == row.ledger_seq)
                {
                    state.ledger.push(row.clone());
                }
            }
            LedgerEffect::UpdateLedgerEntry { row } => {
                upsert_row(&mut state.ledger, row);
            }
            LedgerEffect::SoftDeleteLedgerEntry {
                ledger_seq,
                deleted_at_ms,
            } => {
                if let Some(row) = state.ledger.iter_mut().find(|row| row.ledger_seq == *ledger_seq) {
                    row.is_deleted = true;
                    row.deleted_at_ms = Some(*deleted_at_ms);
                }
            }
            LedgerEffect::InsertLedgerEntryAudit { audit } => {
                if !state
                    .audits
                    .iter()
                    .any(|existing| existing.audit_seq == audit.audit_seq)
                {
                    state.audits.push(audit.clone());
                }
            }
            LedgerEffect::UpsertTask { task } => {
                if let Some(slot) = state
                    .tasks
                    .iter_mut()
                    .find(|existing| existing.task_id == task.task_id)
                {
                    *slot = task.clone();
                } else {
                    state.tasks.push(task.clone());
                }
            }
            LedgerEffect::UpsertOccurrence { occurrence } => {
                upsert_occurrence(&mut state, occurrence.clone())
            }
            LedgerEffect::AppendOccurrenceCompletionEvent { event } => {
                // Only the high-water mark is kept in memory: the events themselves are Room's
                // authoritative append-only table, not part of `DomainState` (架构契约 §4.1).
                set_high_water(&mut state, &event.occurrence_id, event.event_seq);
            }
            LedgerEffect::OpenSession { session, segment } => {
                upsert_session(&mut state, session.clone());
                if !state
                    .segments
                    .iter()
                    .any(|existing| existing.segment_id == segment.segment_id)
                {
                    state.segments.push(segment.clone());
                }
                state.active_session_id = Some(session.session_id.clone());
            }
            LedgerEffect::UpdateSession {
                session,
                closed_segment,
                opened_segment,
            } => {
                upsert_session(&mut state, session.clone());
                if let Some(closed) = closed_segment {
                    if let Some(slot) = state
                        .segments
                        .iter_mut()
                        .find(|s| s.segment_id == closed.segment_id)
                    {
                        *slot = closed.clone();
                    }
                }
                if let Some(opened) = opened_segment {
                    if !state
                        .segments
                        .iter()
                        .any(|existing| existing.segment_id == opened.segment_id)
                    {
                        state.segments.push(opened.clone());
                    }
                }
            }
            LedgerEffect::CloseSegment {
                segment_id,
                end_wall_ms,
            } => {
                if let Some(slot) = state.segments.iter_mut().find(|s| s.segment_id == *segment_id) {
                    slot.end_wall_ms = Some(*end_wall_ms);
                }
            }
            LedgerEffect::SetActiveSession { session_id } => state.active_session_id = session_id.clone(),
            LedgerEffect::SetHeartbeatAnchor {
                session_id,
                wall_ms,
                elapsed_ms,
                boot_tag,
                heartbeat_kind,
            } => {
                if let Some(slot) = state
                    .heartbeats
                    .iter_mut()
                    .find(|beat| beat.session_id == *session_id)
                {
                    slot.wall_ms = *wall_ms;
                    slot.elapsed_ms = *elapsed_ms;
                    slot.boot_tag = boot_tag.clone();
                    slot.heartbeat_kind = *heartbeat_kind;
                } else {
                    state.heartbeats.push(HeartbeatRecord {
                        session_id: session_id.clone(),
                        wall_ms: *wall_ms,
                        elapsed_ms: *elapsed_ms,
                        boot_tag: boot_tag.clone(),
                        heartbeat_kind: *heartbeat_kind,
                    });
                }
            }
            LedgerEffect::UpsertZoneEpoch { epoch } => {
                if !state
                    .zone_epochs
                    .iter()
                    .any(|existing| existing.epoch_seq == epoch.epoch_seq)
                {
                    state.zone_epochs.push(epoch.clone());
                }
            }
            LedgerEffect::RenameTitleRevision { .. }
            | LedgerEffect::AppendCommandLog { .. }
            | LedgerEffect::AppendTemporaryCompletionEvent { .. } => {
                // No in-memory counterpart beyond what the command arm already applied: title
                // revisions, the command log and the temporary completion events are Room-side
                // tables whose authoritative copy is the persisted effect itself.
            }
        }
    }
    state
}

fn upsert_row(rows: &mut Vec<LedgerRow>, row: &LedgerRow) {
    if let Some(slot) = rows
        .iter_mut()
        .find(|existing| existing.ledger_seq == row.ledger_seq)
    {
        *slot = row.clone();
    } else {
        rows.push(row.clone());
    }
}
