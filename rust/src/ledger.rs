//! The time ledger: replay order, edit/delete invariants, audit pre-images.
//!
//! Replay order is `(occurred_wall_ms, ledger_seq)` — the trusted instant first, the stable
//! record number only as a tie-break (架构契约 §4.3.1 / N4). Ledger rows are *lazy*: a slice for
//! yesterday can be written after today's rows, so `ledger_seq` alone would replay in the wrong
//! order.

use crate::error::DomainError;
use crate::types::{LedgerAuditRow, LedgerRow, LedgerRowTitle, TaskRecord, WorkSegment};

pub const KIND_SLICE: i32 = 0;
pub const KIND_MANUAL_ADD: i32 = 1;
pub const KIND_SET_TOTAL: i32 = 2;
pub const KIND_RECOVERY_ADJUSTMENT: i32 = 3;

pub const CHANGE_EDIT: i32 = 0;
pub const CHANGE_DELETE: i32 = 1;
pub const CHANGE_RESTORE: i32 = 2;

/// Reads `ref_id` of an automatic slice back into the segment it came from.
///
/// The key is the deterministic slice id of 架构契约 §6
/// (`session_id:seg_seq:slice_start_wall_ms:zone_epoch_seq`). It is split from the **right** because a
/// session id is itself colon-bearing (`sess:<command id>`); splitting from the left would read
/// `sess` as the session and the rest as a sequence number.
fn slice_key(ref_id: &str) -> Option<(&str, u32, i64)> {
    let mut fields = ref_id.rsplitn(4, ':');
    let zone_epoch_seq = fields.next()?;
    let slice_start_wall_ms = fields.next()?.parse::<i64>().ok()?;
    let seg_seq = fields.next()?.parse::<u32>().ok()?;
    let session_id = fields.next()?;
    if session_id.is_empty() || zone_epoch_seq.is_empty() {
        return None;
    }
    Some((session_id, seg_seq, slice_start_wall_ms))
}

pub fn is_session_slice(ref_id: &str, session_id: &str) -> bool {
    slice_key(ref_id).is_some_and(|(source_session, _, _)| source_session == session_id)
}

fn current_title(tasks: &[TaskRecord], task_id: &str) -> String {
    tasks
        .iter()
        .find(|task| task.task_id == task_id)
        .map(|task| task.title.clone())
        .unwrap_or_default()
}

/// The title each row was recorded under, row by row (AC-14).
///
/// An automatic slice is booked with the segment it came from (the row's `ref_id`, whose slice start is
/// also the row's `occurred_wall_ms`), and that segment's `title_snapshot` is the title in force while
/// the time was invested. A rename cuts the running segment
/// (`state::split_running_segment`), so a day that spans a rename holds one slice per piece and each
/// one keeps its own name — which is what makes 「任务改名后旧明细仍显示旧名」 true rather than a
/// rendering trick.
///
/// A row whose slice cannot be matched to a segment is labelled with the task's current title and
/// flagged `is_title_snapshot = false`. Nothing here fabricates a past name, and a manual add or a
/// set-total row never even looks for one.
pub fn row_titles(rows: &[LedgerRow], segments: &[WorkSegment], tasks: &[TaskRecord]) -> Vec<LedgerRowTitle> {
    rows.iter()
        .map(|row| {
            let snapshot = if row.kind == KIND_SLICE {
                row.ref_id
                    .as_deref()
                    .and_then(slice_key)
                    .and_then(|(session_id, seg_seq, slice_start)| {
                        // The start in the key must be this row's own trusted instant, or the row is
                        // not the slice that key describes and no name may be claimed for it.
                        if slice_start != row.occurred_wall_ms {
                            return None;
                        }
                        segments
                            .iter()
                            .find(|segment| segment.session_id == session_id && segment.seg_seq == seg_seq)
                            .map(|segment| segment.title_snapshot.clone())
                            .filter(|title| !title.is_empty())
                    })
            } else {
                None
            };
            match snapshot {
                Some(title) => LedgerRowTitle {
                    ledger_seq: row.ledger_seq,
                    title,
                    is_title_snapshot: true,
                },
                None => LedgerRowTitle {
                    ledger_seq: row.ledger_seq,
                    title: current_title(tasks, &row.task_id),
                    is_title_snapshot: false,
                },
            }
        })
        .collect()
}

/// Seconds contributed by one row when replaying.
///
/// `kind = 0` (automatic slice), `kind = 1` (manual add), and `kind = 3` (recovery adjustment)
/// contribute `delta_seconds`; `kind = 2` (set-total) replaces the running total.
pub fn contribution(row: &LedgerRow) -> i64 {
    row.delta_seconds.unwrap_or(0)
}

/// Replay `(task_id, app_date)` rows into the day's total.
///
/// Deleted rows are skipped but keep their `ledger_seq` and `occurred_wall_ms` slot, so deleting a
/// row never reorders the remaining ones.
pub fn replay_total(rows: &[LedgerRow]) -> i64 {
    let refs: Vec<&LedgerRow> = rows.iter().collect();
    replay_total_refs(&refs)
}

/// Same as `replay_total`, for callers that already hold borrowed rows (the statistics projection
/// groups borrowed rows so it never clones the ledger).
pub fn replay_total_refs(rows: &[&LedgerRow]) -> i64 {
    let mut ordered: Vec<&LedgerRow> = rows.iter().copied().filter(|row| !row.is_deleted).collect();
    ordered.sort_by_key(|row| (row.occurred_wall_ms, row.ledger_seq));
    let mut total: i64 = 0;
    for row in ordered {
        match row.kind {
            KIND_SLICE | KIND_MANUAL_ADD | KIND_RECOVERY_ADJUSTMENT => total += contribution(row),
            KIND_SET_TOTAL => total = row.set_total_seconds.unwrap_or(0),
            _ => {}
        }
    }
    total
}

/// Running totals after each row, for UI previews. Mirrors `replay_total` exactly.
pub fn replay_trace(rows: &[LedgerRow]) -> Vec<i64> {
    let mut ordered: Vec<&LedgerRow> = rows.iter().filter(|row| !row.is_deleted).collect();
    ordered.sort_by_key(|row| (row.occurred_wall_ms, row.ledger_seq));
    let mut total: i64 = 0;
    let mut trace = Vec::with_capacity(ordered.len());
    for row in ordered {
        match row.kind {
            KIND_SLICE | KIND_MANUAL_ADD | KIND_RECOVERY_ADJUSTMENT => total += contribution(row),
            KIND_SET_TOTAL => total = row.set_total_seconds.unwrap_or(0),
            _ => {}
        }
        trace.push(total);
    }
    trace
}

/// `EditLedgerEntry` precondition (N5): exactly one new value, and it must match the row kind.
pub fn validate_edit(
    row_kind: i32,
    new_delta_seconds: Option<i64>,
    new_set_total_seconds: Option<i64>,
) -> Result<(), DomainError> {
    if row_kind == KIND_RECOVERY_ADJUSTMENT {
        return Err(DomainError::LedgerInvariantViolation {
            detail: "recovery adjustments are immutable".to_string(),
        });
    }
    match (new_delta_seconds, new_set_total_seconds) {
        (Some(_), Some(_)) => Err(DomainError::LedgerInvariantViolation {
            detail: "exactly one of new_delta_seconds/new_set_total_seconds must be set".to_string(),
        }),
        (None, None) => Err(DomainError::LedgerInvariantViolation {
            detail: "one of new_delta_seconds/new_set_total_seconds must be set".to_string(),
        }),
        (Some(seconds), None) if row_kind == KIND_SET_TOTAL => Err(DomainError::LedgerInvariantViolation {
            detail: "kind=2 row requires new_set_total_seconds".to_string(),
        }),
        (None, Some(_)) if row_kind != KIND_SET_TOTAL => Err(DomainError::LedgerInvariantViolation {
            detail: "kind=1 row requires new_delta_seconds".to_string(),
        }),
        (Some(seconds), None) => {
            if seconds < 0 {
                Err(DomainError::NegativeSeconds)
            } else {
                Ok(())
            }
        }
        (None, Some(total)) => {
            if total < 0 {
                Err(DomainError::NegativeSeconds)
            } else {
                Ok(())
            }
        }
    }
}

/// Builds the audit pre-image for an edit. The caller must insert this *before* mutating the row.
pub fn edit_audit(
    audit_seq: i64,
    row: &LedgerRow,
    new_delta_seconds: Option<i64>,
    new_set_total_seconds: Option<i64>,
    changed_at_ms: i64,
) -> LedgerAuditRow {
    LedgerAuditRow {
        audit_seq,
        ledger_seq: row.ledger_seq,
        prev_delta_seconds: row.delta_seconds,
        prev_set_total_seconds: row.set_total_seconds,
        new_delta_seconds,
        new_set_total_seconds,
        changed_at_ms,
        change_kind: CHANGE_EDIT,
    }
}

/// Builds the audit pre-image for a logical delete.
pub fn delete_audit(audit_seq: i64, row: &LedgerRow, changed_at_ms: i64) -> LedgerAuditRow {
    LedgerAuditRow {
        audit_seq,
        ledger_seq: row.ledger_seq,
        prev_delta_seconds: row.delta_seconds,
        prev_set_total_seconds: row.set_total_seconds,
        new_delta_seconds: None,
        new_set_total_seconds: None,
        changed_at_ms,
        change_kind: CHANGE_DELETE,
    }
}

/// Builds the audit pre-image for an undo of a logical delete. The values are unchanged — only the
/// deleted flag is cleared — so `prev_*` and `new_*` agree, and the audit's job is to record *that* a
/// restore happened and when.
pub fn restore_audit(audit_seq: i64, row: &LedgerRow, changed_at_ms: i64) -> LedgerAuditRow {
    LedgerAuditRow {
        audit_seq,
        ledger_seq: row.ledger_seq,
        prev_delta_seconds: row.delta_seconds,
        prev_set_total_seconds: row.set_total_seconds,
        new_delta_seconds: row.delta_seconds,
        new_set_total_seconds: row.set_total_seconds,
        changed_at_ms,
        change_kind: CHANGE_RESTORE,
    }
}

/// Total the day would end at if `ledger_seq` were deleted. Drives the `kind = 2` notice
/// required by 规格 6.1 and 架构契约 §4.3.2 rule 3.
pub fn total_without(rows: &[LedgerRow], ledger_seq: i64) -> i64 {
    let row = rows.iter().find(|row| row.ledger_seq == ledger_seq);
    let deleted_adjustment = row
        .filter(|row| row.kind == KIND_SLICE)
        .and_then(|row| row.ref_id.as_deref())
        .map(|source| format!("recovery:{source}"));
    let filtered: Vec<LedgerRow> = rows
        .iter()
        .filter(|candidate| {
            candidate.ledger_seq != ledger_seq
                && !(candidate.kind == KIND_RECOVERY_ADJUSTMENT
                    && deleted_adjustment.as_deref() == candidate.ref_id.as_deref())
        })
        .cloned()
        .collect();
    replay_total(&filtered)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(seq: i64, kind: i32, occurred: i64, delta: Option<i64>, set: Option<i64>) -> LedgerRow {
        LedgerRow {
            ledger_seq: seq,
            kind,
            ref_id: None,
            task_id: "t1".to_string(),
            app_date: "2026-03-10".to_string(),
            occurred_wall_ms: occurred,
            zone_epoch_seq: 1,
            delta_seconds: delta,
            set_total_seconds: set,
            created_wall_ms: occurred,
            edited_at_ms: None,
            is_deleted: false,
            deleted_at_ms: None,
        }
    }

    fn add(seq: i64, at: i64, seconds: i64) -> LedgerRow {
        row(seq, KIND_MANUAL_ADD, at, Some(seconds), None)
    }

    /// S1 (AC-12): +20, +30, set 35, +10 -> 20, 50, 35, 45.
    #[test]
    fn s1_replay_matches_the_five_judged_scenarios() {
        let rows = vec![
            add(1, 1_000, 1200),
            add(2, 2_000, 1800),
            row(3, KIND_SET_TOTAL, 3_000, None, Some(2100)),
            add(4, 4_000, 600),
        ];
        assert_eq!(replay_trace(&rows), vec![1200, 3000, 2100, 2700]);
        assert_eq!(replay_total(&rows), 2700);
    }

    /// S3 (AC-13): deleting the set row restores the running total that preceded it.
    #[test]
    fn s3_delete_set_row_restores_prior_accumulation() {
        let mut rows = vec![
            add(1, 1_000, 2400),
            add(2, 2_000, 1800),
            row(3, KIND_SET_TOTAL, 3_000, None, Some(2100)),
            add(4, 4_000, 600),
        ];
        assert_eq!(replay_total(&rows), 2700);
        rows[2].is_deleted = true;
        assert_eq!(replay_total(&rows), 4800);
    }

    /// S5 (N5): editing a kind=2 row in place.
    #[test]
    fn s5_editing_set_row_takes_effect() {
        let mut rows = vec![
            add(1, 1_000, 1200),
            add(2, 2_000, 1800),
            row(3, KIND_SET_TOTAL, 3_000, None, Some(2100)),
            add(4, 4_000, 600),
        ];
        rows[2].set_total_seconds = Some(3000);
        assert_eq!(replay_total(&rows), 3600);
    }

    /// N4 guard: generation order differs from trusted occurrence order.
    #[test]
    fn replay_follows_occurred_time_not_insertion_order() {
        // Yesterday's slice is written last (lazy occurrence generation) but happened first.
        let lazy = vec![
            row(10, KIND_SLICE, 5_000, Some(1800), None), // written late, happened last
            row(11, KIND_SET_TOTAL, 3_000, None, Some(1200)), // happened in the middle
            row(12, KIND_SLICE, 1_000, Some(600), None),  // written last, happened first
        ];
        let chronological = vec![
            row(1, KIND_SLICE, 1_000, Some(600), None),
            row(2, KIND_SET_TOTAL, 3_000, None, Some(1200)),
            row(3, KIND_SLICE, 5_000, Some(1800), None),
        ];
        assert_eq!(replay_total(&lazy), replay_total(&chronological));
        assert_eq!(replay_total(&lazy), 3000);
    }

    #[test]
    fn same_instant_is_broken_by_ledger_seq() {
        let rows = vec![add(2, 1_000, 600), row(3, KIND_SET_TOTAL, 1_000, None, Some(0))];
        assert_eq!(replay_total(&rows), 0);
        let rows = vec![row(1, KIND_SET_TOTAL, 1_000, None, Some(0)), add(2, 1_000, 600)];
        assert_eq!(replay_total(&rows), 600);
    }

    #[test]
    fn deleted_rows_keep_their_ordering_slot() {
        let mut rows = vec![add(1, 1_000, 600), add(2, 2_000, 600), add(3, 3_000, 600)];
        rows[1].is_deleted = true;
        assert_eq!(replay_total(&rows), 1200);
        assert_eq!(rows[1].ledger_seq, 2);
    }

    #[test]
    fn deleting_a_recovery_adjusted_source_preview_removes_its_adjustment() {
        let mut source = row(1, KIND_SLICE, 1_000, Some(600), None);
        source.ref_id = Some("sess:x:1:1000:1".to_string());
        let adjustment = LedgerRow {
            ledger_seq: 2,
            kind: KIND_RECOVERY_ADJUSTMENT,
            ref_id: Some(format!("recovery:{}", source.ref_id.as_deref().unwrap())),
            task_id: source.task_id.clone(),
            app_date: source.app_date.clone(),
            occurred_wall_ms: 2_000,
            zone_epoch_seq: 1,
            delta_seconds: Some(-200),
            set_total_seconds: None,
            created_wall_ms: 2_000,
            edited_at_ms: None,
            is_deleted: false,
            deleted_at_ms: None,
        };
        assert_eq!(replay_total(&[source.clone(), adjustment.clone()]), 400);
        assert_eq!(total_without(&[source, adjustment], 1), 0);
    }

    #[test]
    fn edit_validation_matches_kind() {
        assert!(validate_edit(KIND_MANUAL_ADD, Some(60), None).is_ok());
        assert!(validate_edit(KIND_SET_TOTAL, None, Some(60)).is_ok());
        assert!(matches!(
            validate_edit(KIND_RECOVERY_ADJUSTMENT, Some(-60), None),
            Err(DomainError::LedgerInvariantViolation { .. })
        ));
        assert!(matches!(
            validate_edit(KIND_MANUAL_ADD, None, Some(60)),
            Err(DomainError::LedgerInvariantViolation { .. })
        ));
        assert!(matches!(
            validate_edit(KIND_SET_TOTAL, Some(60), None),
            Err(DomainError::LedgerInvariantViolation { .. })
        ));
        assert!(matches!(
            validate_edit(KIND_MANUAL_ADD, Some(1), Some(2)),
            Err(DomainError::LedgerInvariantViolation { .. })
        ));
        assert!(matches!(
            validate_edit(KIND_MANUAL_ADD, None, None),
            Err(DomainError::LedgerInvariantViolation { .. })
        ));
        assert!(matches!(
            validate_edit(KIND_MANUAL_ADD, Some(-1), None),
            Err(DomainError::NegativeSeconds)
        ));
    }

    #[test]
    fn delete_notice_reports_resulting_total() {
        let rows = vec![
            add(1, 1_000, 1200),
            row(2, KIND_SET_TOTAL, 2_000, None, Some(300)),
            add(3, 3_000, 600),
        ];
        assert_eq!(replay_total(&rows), 900);
        assert_eq!(total_without(&rows, 2), 1800);
    }

    #[test]
    fn audit_keeps_the_pre_image() {
        let original = row(7, KIND_SET_TOTAL, 1_000, None, Some(2100));
        let audit = edit_audit(1, &original, None, Some(3000), 9_000);
        assert_eq!(audit.prev_set_total_seconds, Some(2100));
        assert_eq!(audit.new_set_total_seconds, Some(3000));
        assert_eq!(audit.change_kind, CHANGE_EDIT);
        let removed = delete_audit(2, &original, 9_500);
        assert_eq!(removed.prev_set_total_seconds, Some(2100));
        assert_eq!(removed.change_kind, CHANGE_DELETE);
    }

    fn segment(session_id: &str, seg_seq: u32, start_wall_ms: i64, title: &str) -> WorkSegment {
        WorkSegment {
            segment_id: format!("{session_id}:{seg_seq}"),
            session_id: session_id.to_string(),
            seg_seq,
            start_wall_ms,
            end_wall_ms: Some(start_wall_ms + 60_000),
            title_snapshot: title.to_string(),
            zone_epoch_seq: 1,
            derived: false,
        }
    }

    fn task(task_id: &str, title: &str) -> TaskRecord {
        TaskRecord {
            task_id: task_id.to_string(),
            kind: crate::types::TaskKind::Daily,
            title: title.to_string(),
            note: String::new(),
            sort_key: 1024,
            created_wall_ms: 0,
            archived_at_ms: None,
            last_countdown_minutes: 25,
            art_asset_id: None,
        }
    }

    fn slice_row(seq: i64, ref_id: &str, occurred: i64) -> LedgerRow {
        LedgerRow {
            ref_id: Some(ref_id.to_string()),
            ..row(seq, KIND_SLICE, occurred, Some(60), None)
        }
    }

    /// The row of a piece that ran under the old name still reads as the old name.
    #[test]
    fn a_slice_row_reads_the_segment_it_was_booked_from() {
        // `sess:<command id>` is colon-bearing, so the key has to be split from the right.
        let ref_id = "sess:cmd:start:9:1:1000:1";
        let rows = vec![slice_row(1, ref_id, 1_000)];
        let segments = vec![segment("sess:cmd:start:9", 1, 1_000, "旧名")];
        let titles = row_titles(&rows, &segments, &[task("t1", "新名")]);
        assert_eq!(titles[0].title, "旧名");
        assert!(titles[0].is_title_snapshot);
    }

    /// A manual add describes no time span, so it can only ever carry the current title.
    #[test]
    fn a_manual_row_carries_the_current_title_without_a_snapshot_flag() {
        let rows = vec![add(1, 1_000, 600)];
        let titles = row_titles(&rows, &[], &[task("t1", "现在的名字")]);
        assert_eq!(titles[0].title, "现在的名字");
        assert!(!titles[0].is_title_snapshot);
    }

    /// A key that does not describe this row (or a segment the caller cannot supply, or a key that is
    /// not a slice key at all) is never guessed at: the row is reported as "current title, not a
    /// snapshot" instead of inventing history.
    #[test]
    fn an_unmatched_slice_is_reported_as_not_a_snapshot() {
        let segments = vec![segment("sess:a", 1, 1_000, "旧名")];
        let wrong_instant = vec![slice_row(1, "sess:a:1:1000:1", 2_000)];
        let no_segment = vec![slice_row(2, "sess:missing:1:1000:1", 1_000)];
        let malformed = vec![slice_row(3, "sess:a:not-a-sequence", 1_000)];
        for rows in [wrong_instant, no_segment, malformed] {
            let titles = row_titles(&rows, &segments, &[task("t1", "当前名")]);
            assert_eq!(titles[0].title, "当前名", "{rows:?}");
            assert!(!titles[0].is_title_snapshot, "{rows:?}");
        }
    }

    /// A deleted row is still labelled: the interface keeps showing it until the list filters it out,
    /// and the label of a row must not depend on whether it is currently deleted.
    #[test]
    fn deleted_rows_are_labelled_too() {
        let mut removed = slice_row(1, "sess:a:2:1000:1", 1_000);
        removed.is_deleted = true;
        let titles = row_titles(&[removed], &[segment("sess:a", 2, 1_000, "旧名")], &[]);
        assert_eq!(titles.len(), 1);
        assert_eq!(titles[0].title, "旧名");
    }
}
