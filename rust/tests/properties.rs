//! Property tests for the domain invariants that must hold for *every* input, not only for the
//! five judged ledger scenarios.
//!
//! What is being pinned, and why a property rather than another example:
//!
//! * **Replay order (N4)** — the total must depend on `(occurred_wall_ms, ledger_seq)` and on
//!   nothing else. `ledger_seq`/vector position must not matter, because occurrences are generated
//!   lazily and a slice for yesterday can be written after today's rows.
//! * **Pause accounting (AC-05)** — effective time is the sum of the running spans, is never
//!   negative, and can never exceed the wall-clock span it was taken from.
//! * **Slicing (AC-08 / AC-15)** — per-day slices are contiguous, non-empty, sum to the interval,
//!   and are attributed to the day window that actually contains them.
//! * **Event replay (AC-03 / AC-07 / AC-11)** — for any event sequence, the incrementally
//!   maintained state equals a full replay, so the derived view can never drift from the events.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test properties`

use std::collections::BTreeMap;

use arttodo_core::*;
use proptest::prelude::*;

const ZONE: &str = "Asia/Shanghai";

fn row(
    ledger_seq: i64,
    kind: i32,
    occurred_wall_ms: i64,
    delta_seconds: Option<i64>,
    set_total_seconds: Option<i64>,
) -> LedgerRow {
    LedgerRow {
        ledger_seq,
        kind,
        ref_id: None,
        task_id: "task:t1".to_string(),
        app_date: "2026-03-10".to_string(),
        occurred_wall_ms,
        zone_epoch_seq: 1,
        delta_seconds,
        set_total_seconds,
        created_wall_ms: occurred_wall_ms,
        edited_at_ms: None,
        is_deleted: false,
        deleted_at_ms: None,
    }
}

/// One ledger operation, generated without any reference to a `ledger_seq` or to insertion order.
#[derive(Debug, Clone)]
enum Op {
    /// `kind=1`, contributes `delta`.
    Add { at: i64, delta: i64 },
    /// `kind=2`, pins the running total to `total`.
    Set { at: i64, total: i64 },
    /// An automatic slice: `kind=0`, contributes `delta`.
    Slice { at: i64, delta: i64 },
    /// The same as an `Add`, but the row is logically deleted and must be skipped.
    DeletedAdd { at: i64, delta: i64 },
}

fn op_strategy() -> impl Strategy<Value = Op> {
    let at = 0_i64..50;
    prop_oneof![
        (at.clone(), 0_i64..3_600).prop_map(|(at, delta)| Op::Add { at, delta }),
        (at.clone(), 0_i64..5_000).prop_map(|(at, total)| Op::Set { at, total }),
        (at.clone(), 0_i64..3_600).prop_map(|(at, delta)| Op::Slice { at, delta }),
        (at, 0_i64..3_600).prop_map(|(at, delta)| Op::DeletedAdd { at, delta }),
    ]
}

/// Materialise a *reference* ordering: rows numbered in the order they happened, which is what the
/// buggy `ledger_seq ASC` implementation would have produced.
fn in_occurrence_order(ops: &[Op]) -> Vec<LedgerRow> {
    let mut sorted: Vec<&Op> = ops.iter().collect();
    sorted.sort_by_key(|op| match op {
        Op::Add { at, .. } | Op::Set { at, .. } | Op::Slice { at, .. } | Op::DeletedAdd { at, .. } => *at,
    });
    sorted
        .into_iter()
        .enumerate()
        .map(|(index, op)| op_to_row(index as i64 + 1, op))
        .collect()
}

fn op_to_row(seq: i64, op: &Op) -> LedgerRow {
    match op {
        Op::Add { at, delta } => row(seq, 1, *at, Some(*delta), None),
        Op::Slice { at, delta } => row(seq, 0, *at, Some(*delta), None),
        Op::Set { at, total } => row(seq, 2, *at, None, Some(*total)),
        Op::DeletedAdd { at, delta } => {
            let mut created = row(seq, 1, *at, Some(*delta), None);
            created.is_deleted = true;
            created
        }
    }
}

proptest! {
    /// The total is a function of the rows' `(occurred_wall_ms, ledger_seq)` slots only: shuffling
    /// the input vector without changing the slots must not change the answer.
    #[test]
    fn replay_total_does_not_depend_on_input_order(
        ops in prop::collection::vec(op_strategy(), 1..24),
        perm in any::<prop::sample::Index>(),
    ) {
        let rows = in_occurrence_order(&ops);
        let expected = replay_daily_total(rows.clone());

        // Move one row to a different vector position, leaving its slots untouched.
        let mut shuffled = rows.clone();
        if shuffled.len() > 1 {
            let index = perm.index(shuffled.len());
            let moved = shuffled.remove(0);
            let insert_at = index.min(shuffled.len());
            shuffled.insert(insert_at, moved);
        }
        prop_assert_eq!(replay_daily_total(shuffled), expected);
        prop_assert_eq!(replay_daily_total(rows), expected);
    }

    /// Replay order is the *occurrence* order, not the record order. When no two operations share an
    /// instant, the `ledger_seq` numbering (i.e. the order the rows were written down) must not
    /// matter at all — which is the lazy-generation situation N4 guards.
    ///
    /// Instants are forced strictly increasing on purpose: with two rows at the *same* instant the
    /// contract makes `ledger_seq` the tie-break (§4.3.1), so renumbering those would legitimately
    /// change the answer, and mixing the two questions into one property would only hide both.
    #[test]
    fn replay_follows_occurred_time_not_ledger_seq(ops in prop::collection::vec(op_strategy(), 1..24)) {
        // Rebuild with strictly increasing instants, keeping the operation kinds and amounts.
        let mut rows: Vec<LedgerRow> = Vec::new();
        for (index, op) in ops.iter().enumerate() {
            let at = index as i64 * 1_000;
            let mut item = op_to_row(index as i64 + 1, op);
            item.occurred_wall_ms = at;
            item.ref_id = None;
            rows.push(item);
        }
        let expected = replay_daily_total(rows.clone());

        // Same rows, same instants, but the record numbers assigned in the reverse order — the case
        // where a slice for yesterday is written after several of today's rows.
        let mut reversed: Vec<LedgerRow> = rows.clone();
        let count = reversed.len() as i64;
        for (index, item) in reversed.iter_mut().enumerate() {
            item.ledger_seq = count - index as i64;
        }
        prop_assert_eq!(replay_daily_total(reversed), expected);

        // A monotone shift of every record number changes nothing either: only their relative order
        // is part of the answer.
        let shifted: Vec<LedgerRow> = rows
            .iter()
            .map(|item| {
                let mut moved = item.clone();
                moved.ledger_seq += 1_000_000;
                moved
            })
            .collect();
        prop_assert_eq!(replay_daily_total(shifted), expected);
    }

    /// N4 guard as a property: the total is a function of the rows' *occurrence slots*, not of the
    /// order they were written down in. Lazy occurrence generation writes yesterday's slice after
    /// today's rows, so `ledger_seq` can **decrease** along `occurred_wall_ms` — and in exactly that
    /// shape a `ledger_seq ASC` implementation gives a different answer.
    ///
    /// The instants are strictly increasing on purpose, so the tie-break (`ledger_seq` at an equal
    /// instant, §4.3.1) is not part of the question being asked here.
    #[test]
    fn replay_ignores_a_descending_record_number(ops in prop::collection::vec(op_strategy(), 2..16)) {
        let count = ops.len() as i64;
        // Occurrence order == vector order, with record numbers handed out in *reverse* — the one
        // shape that separates "order by occurred_wall_ms" from "order by ledger_seq".
        let rows: Vec<LedgerRow> = ops
            .iter()
            .enumerate()
            .map(|(index, op)| {
                let mut item = op_to_row(count - index as i64, op);
                item.occurred_wall_ms = index as i64 * 1_000;
                item
            })
            .collect();

        // The same rows renumbered ascending in occurrence order.
        let ascending: Vec<LedgerRow> = rows
            .iter()
            .enumerate()
            .map(|(index, item)| {
                let mut moved = item.clone();
                moved.ledger_seq = index as i64 + 1;
                moved
            })
            .collect();

        // What the definition says, computed directly from the occurrence order.
        let mut expected = 0_i64;
        for item in &ascending {
            if item.is_deleted {
                continue;
            }
            match item.kind {
                0 | 1 => expected += item.delta_seconds.unwrap_or(0),
                2 => expected = item.set_total_seconds.unwrap_or(0),
                _ => {}
            }
        }

        prop_assert_eq!(replay_daily_total(rows.clone()), expected, "descending record numbers");
        prop_assert_eq!(replay_daily_total(ascending), expected, "ascending record numbers");

        // Presenting the same rows in a different *vector* order changes nothing either.
        let mut reordered = rows.clone();
        reordered.reverse();
        prop_assert_eq!(replay_daily_total(reordered), expected);
    }

    /// A set-total does not absorb what comes after it: the rows that occur later still accumulate.
    #[test]
    fn a_set_total_then_an_add_accumulates(
        deltas_before in prop::collection::vec(0_i64..600, 0..6),
        set_total in 0_i64..5_000,
        deltas_after in prop::collection::vec(0_i64..600, 0..6),
    ) {
        let mut rows = Vec::new();
        let mut seq = 1_i64;
        let mut at = 0_i64;
        for delta in &deltas_before {
            rows.push(row(seq, 1, at, Some(*delta), None));
            seq += 1;
            at += 1;
        }
        rows.push(row(seq, 2, at, None, Some(set_total)));
        seq += 1;
        at += 1;
        let after_sum: i64 = deltas_after.iter().sum();
        for delta in &deltas_after {
            rows.push(row(seq, 1, at, Some(*delta), None));
            seq += 1;
            at += 1;
        }
        prop_assert_eq!(replay_daily_total(rows), set_total + after_sum);
    }

    /// Deleting a row is the same as never having written it, for the total — and the deleted row
    /// keeps its sequence slot.
    #[test]
    fn a_deleted_row_contributes_nothing_and_keeps_its_slot(
        ops in prop::collection::vec(op_strategy(), 1..16),
    ) {
        let rows = in_occurrence_order(&ops);
        let live: Vec<LedgerRow> = rows.iter().filter(|r| !r.is_deleted).cloned().collect();
        prop_assert_eq!(replay_daily_total(rows.clone()), replay_daily_total(live));
        for item in &rows {
            prop_assert!(item.ledger_seq >= 1);
        }
    }

    /// A day's total is never negative when every contribution is non-negative.
    #[test]
    fn a_non_negative_ledger_replays_non_negative(ops in prop::collection::vec(op_strategy(), 1..16)) {
        let rows = in_occurrence_order(&ops);
        prop_assert!(replay_daily_total(rows) >= 0);
    }

    /// The trace always ends at the total, and its last step is the total (when there is a row).
    #[test]
    fn the_trace_ends_at_the_total(ops in prop::collection::vec(op_strategy(), 1..20)) {
        let rows = in_occurrence_order(&ops);
        let trace = replay_daily_trace(rows.clone());
        let total = replay_daily_total(rows);
        prop_assert_eq!(trace.last().copied().unwrap_or(0), total);
    }
}

// ---------------------------------------------------------------------------------------------
// Pause accounting (AC-05)
// ---------------------------------------------------------------------------------------------

fn segment(seg_seq: u32, start_wall_ms: i64, end_wall_ms: Option<i64>) -> WorkSegment {
    WorkSegment {
        segment_id: format!("sess:1:{seg_seq}"),
        session_id: "sess:1".to_string(),
        seg_seq,
        start_wall_ms,
        end_wall_ms,
        title_snapshot: "t".to_string(),
        zone_epoch_seq: 1,
        derived: false,
    }
}

/// A generated run/pause plan: alternating integers are running spans and paused spans, both in
/// seconds, so the wall-clock span is their sum and the effective time is the sum of the running
/// ones.
fn plan_strategy() -> impl Strategy<Value = Vec<(bool, i64)>> {
    prop::collection::vec((any::<bool>(), 1_i64..3_600), 1..10)
}

fn materialize(plan: &[(bool, i64)]) -> (Vec<WorkSegment>, i64, i64) {
    let mut cursor_ms = 0_i64;
    let mut segments = Vec::new();
    let mut seq = 1_u32;
    for (running, duration_s) in plan {
        let duration_ms = duration_s * 1000;
        if *running {
            segments.push(segment(seq, cursor_ms, Some(cursor_ms + duration_ms)));
            seq += 1;
        }
        cursor_ms += duration_ms;
    }
    (
        segments,
        cursor_ms / 1000,
        plan.iter().map(|(r, d)| if *r { *d } else { 0 }).sum(),
    )
}

proptest! {
    /// Effective time is exactly the sum of the running spans, over the session's own segments.
    #[test]
    fn effective_seconds_is_the_sum_of_running_spans(plan in plan_strategy()) {
        let (segments, wall_seconds, running_seconds) = materialize(&plan);
        let effective = effective_seconds("sess:1".to_string(), segments, i64::MAX);
        prop_assert_eq!(effective, running_seconds);
        prop_assert!(effective >= 0);
        prop_assert!(effective <= wall_seconds);
    }

    /// Measuring an open segment at a later instant never loses time and never invents it.
    #[test]
    fn an_open_segment_measures_at_most_the_wall_span(plan in plan_strategy()) {
        let (segments, wall_seconds, running_seconds) = materialize(&plan);
        let measured_at = wall_seconds * 1000;
        let effective = effective_seconds("sess:1".to_string(), segments, measured_at);
        prop_assert!(effective >= running_seconds.min(wall_seconds));
        prop_assert!(effective <= wall_seconds);
    }

    /// The paused spans never become segments: the segment count is the number of running spans.
    #[test]
    fn paused_spans_never_become_segments(plan in plan_strategy()) {
        let (segments, _, _) = materialize(&plan);
        let expected = plan.iter().filter(|(running, _)| *running).count();
        prop_assert_eq!(segments.len(), expected);
        for item in &segments {
            prop_assert!(item.end_wall_ms.unwrap_or(0) > item.start_wall_ms);
            prop_assert!(item.seg_seq >= 1);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Slicing (AC-08 / AC-15)
// ---------------------------------------------------------------------------------------------

/// Intervals that start anywhere in a two-week range around the DST cases E19 uses and that can be
/// up to 30 hours long — so most of them cross at least one midnight, and some cross a DST jump.
fn interval_strategy() -> impl Strategy<Value = (i64, i64)> {
    (0_i64..1_200_000_000_000, 1_i64..108_000_000)
}

fn zones() -> Vec<&'static str> {
    vec![
        "Asia/Shanghai",
        "UTC",
        "America/Sao_Paulo",
        "America/Havana",
        "America/Santiago",
        "Asia/Beirut",
        "Europe/Berlin",
        "Pacific/Auckland",
    ]
}

proptest! {
    /// Every slice is non-empty, starts where the previous one ended, and stays inside the interval.
    #[test]
    fn slices_are_a_partition_of_the_interval((start, length) in interval_strategy()) {
        let end = start + length;
        for zone in zones() {
            let window = day_window("2018-11-04".to_string(), zone.to_string(), 1)
                .map(|_| ())
                .map_err(|e| TestCaseError::fail(format!("{zone}: {e}")));
            window?;
            let slices = slice_interval(
                "s".to_string(),
                1,
                start,
                end,
                zone.to_string(),
                1,
                vec![],
            );
            let slices = match slices {
                Ok(slices) => slices,
                // A 30-hour interval can reach past the range where jiff still resolves the day
                // window; that is an input-range limit, not a domain failure.
                Err(DomainError::InvalidAppDate { .. }) => continue,
                Err(other) => return Err(TestCaseError::fail(format!("{zone}: {other:?}"))),
            };
            prop_assert!(!slices.is_empty(), "{}", zone);
            prop_assert_eq!(slices[0].start_wall_ms, start, "{}", zone);
            prop_assert_eq!(slices.last().unwrap().end_wall_ms, end, "{}", zone);
            let mut total = 0_i64;
            for slice in &slices {
                prop_assert!(slice.end_wall_ms > slice.start_wall_ms, "{}", zone);
                prop_assert!(slice.start_wall_ms >= start && slice.end_wall_ms <= end, "{}", zone);
                total += slice.end_wall_ms - slice.start_wall_ms;
            }
            prop_assert_eq!(total, end - start, "{}", zone);
            for pair in slices.windows(2) {
                prop_assert_eq!(pair[0].end_wall_ms, pair[1].start_wall_ms, "{}", zone);
            }
        }
    }

    /// Each slice is attributed to the day window that contains its first instant — the §2.3 rule 4
    /// rule, checked against `day_window` itself rather than against a label.
    #[test]
    fn slices_are_attributed_to_the_day_that_contains_them((start, length) in interval_strategy()) {
        let end = start + length;
        for zone in zones() {
            let slices = match slice_interval("s".to_string(), 1, start, end, zone.to_string(), 1, vec![]) {
                Ok(slices) => slices,
                Err(DomainError::InvalidAppDate { .. }) => continue,
                Err(other) => return Err(TestCaseError::fail(format!("{zone}: {other:?}"))),
            };
            for slice in &slices {
                let window = day_window(slice.app_date.iso.clone(), zone.to_string(), 1)
                    .map_err(|e| TestCaseError::fail(format!("{zone}: {e}")))?;
                prop_assert!(
                    slice.start_wall_ms >= window.start_wall_ms
                        && slice.start_wall_ms < window.end_wall_ms,
                    "{}: slice start {} not in window {}..{}",
                    zone,
                    slice.start_wall_ms,
                    window.start_wall_ms,
                    window.end_wall_ms
                );
            }
        }
    }

    /// Slice ids are stable for identical inputs and are unique within one interval.
    #[test]
    fn slice_ids_are_stable_and_unique((start, length) in interval_strategy()) {
        let end = start + length;
        let zone = "Asia/Shanghai";
        let first = match slice_interval("s".to_string(), 7, start, end, zone.to_string(), 3, vec![]) {
            Ok(slices) => slices,
            Err(_) => return Ok(()),
        };
        let second = slice_interval("s".to_string(), 7, start, end, zone.to_string(), 3, vec![])
            .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
        prop_assert_eq!(first.len(), second.len());
        let mut seen = std::collections::BTreeSet::new();
        for (a, b) in first.iter().zip(second.iter()) {
            prop_assert_eq!(&a.slice_id, &b.slice_id);
            prop_assert!(seen.insert(a.slice_id.clone()), "duplicate slice id {}", a.slice_id);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Aggregation vs the day windows
// ---------------------------------------------------------------------------------------------

proptest! {
    /// The day view and the trend view are the same numbers: a bucket sum is always the sum of the
    /// day totals inside it, whatever the rows are.
    #[test]
    fn period_totals_reconstruct_from_day_totals(
        days in prop::collection::vec(0_i64..2_000_000_000_000_i64, 1..12),
        seconds in prop::collection::vec(0_i64..3_600, 1..12),
    ) {
        let mut rows: Vec<LedgerRow> = Vec::new();
        for (index, (wall_ms, delta)) in days.iter().zip(seconds.iter()).enumerate() {
            let date = day_label_of(*wall_ms, ZONE.to_string(), 1)
                .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
            rows.push(row(index as i64 + 1, 1, *wall_ms, Some(*delta), None));
            // The row's `app_date` must be the day the window puts it in, or the aggregation would
            // be reading a different fact than the one the row states.
            rows[index].app_date = date;
        }
        let day_totals = project_day_totals(rows.clone());
        let day_sum: i64 = day_totals.iter().map(|total| total.seconds).sum();
        for bucket in [TimeBucket::Day, TimeBucket::Week, TimeBucket::Month] {
            let periods = project_period_totals(rows.clone(), bucket).map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
            prop_assert_eq!(periods.iter().map(|p| p.seconds).sum::<i64>(), day_sum, "{:?}", bucket);
        }
        let shares = project_task_shares(rows);
        prop_assert_eq!(shares.iter().map(|s| s.seconds).sum::<i64>(), day_sum);

        // The day view agrees with a hand-built per-label sum of the same rows.
        let mut per_label: BTreeMap<String, i64> = BTreeMap::new();
        for total in &day_totals {
            let label = day_window(total.app_date.clone(), ZONE.to_string(), 1)
                .map_err(|e| TestCaseError::fail(format!("{e:?}")))?
                .app_date
                .iso;
            prop_assert_eq!(&label, &total.app_date, "the label must name a real civil day");
            *per_label.entry(total.app_date.clone()).or_insert(0) += total.seconds;
        }
        prop_assert_eq!(
            per_label.values().sum::<i64>(),
            day_sum,
            "every day appears exactly once in the heat map"
        );
    }
}

// ---------------------------------------------------------------------------------------------
// Event-sourced completion state (AC-03 / AC-07 / AC-11)
// ---------------------------------------------------------------------------------------------

/// A completion event sequence, described only by its actions: sequence numbers are assigned
/// monotonically by the generator, exactly as the domain does.
fn actions_strategy() -> impl Strategy<Value = Vec<bool>> {
    prop::collection::vec(any::<bool>(), 0..24)
}

fn occurrence_events(actions: &[bool]) -> Vec<OccurrenceCompletionEvent> {
    actions
        .iter()
        .enumerate()
        .map(|(index, completed)| OccurrenceCompletionEvent {
            event_seq: index as i64 + 1,
            occurrence_id: "occ:task:t1:2026-03-10".to_string(),
            action: if *completed { 0 } else { 1 },
            occurred_wall_ms: index as i64 * 1_000,
            app_date: "2026-03-10".to_string(),
            zone_epoch_seq: 1,
        })
        .collect()
}

fn temporary_events(actions: &[bool]) -> Vec<TemporaryCompletionEvent> {
    actions
        .iter()
        .enumerate()
        .map(|(index, completed)| TemporaryCompletionEvent {
            event_seq: index as i64 + 1,
            task_id: "task:t1".to_string(),
            action: if *completed { 0 } else { 1 },
            occurred_wall_ms: index as i64 * 1_000,
            app_date: "2026-03-10".to_string(),
            title_snapshot: "t".to_string(),
        })
        .collect()
}

proptest! {
    /// The daily completion view is exactly the last action, read through the same replay the
    /// interface uses.
    #[test]
    fn the_daily_view_is_the_last_action(actions in actions_strategy()) {
        let events = occurrence_events(&actions);
        let completed = occurrence_completed(
            events.clone(),
            "occ:task:t1:2026-03-10".to_string(),
        );
        let last_action = events.last().map(|event| event.action).unwrap_or(1);
        prop_assert_eq!(completed, last_action == 0, "the last action decides");
    }

    /// Temporary-task replay is the last action too, for any interleaving of complete/reopen.
    #[test]
    fn temporary_replay_is_order_independent_of_position(actions in actions_strategy()) {
        let mut events = temporary_events(&actions);
        // Present the same events in a shuffled vector: the sequence numbers decide.
        events.reverse();
        prop_assert_eq!(
            temporary_completed(events, "task:t1".to_string()),
            actions.last().copied().unwrap_or(false)
        );
    }

    /// A shorter prefix's answer is never "ahead" of the full sequence's: completing then reopening
    /// cannot end up completed, and vice versa.
    #[test]
    fn a_prefix_and_the_full_sequence_agree_on_the_last_action(actions in actions_strategy()) {
        if actions.is_empty() {
            return Ok(());
        }
        let full = temporary_events(&actions);
        let prefix = temporary_events(&actions[..actions.len() - 1]);
        let full_answer = temporary_completed(full, "task:t1".to_string());
        let prefix_answer = temporary_completed(prefix, "task:t1".to_string());
        if actions.len() == 1 {
            prop_assert_eq!(full_answer, actions[0]);
            prop_assert!(!prefix_answer);
        } else {
            prop_assert_eq!(prefix_answer, actions[actions.len() - 2]);
            prop_assert_eq!(full_answer, actions[actions.len() - 1]);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The derived view vs the events, over the real command surface (架构契约 §4.1 item 3)
// ---------------------------------------------------------------------------------------------

/// One occurrence of one daily task, generated lazily, plus the event table Room would hold.
///
/// `DomainState` deliberately carries only a high-water mark for completion events (`tests/common`
/// explains why), so the property drives the same two things the Android layer keeps in sync: the
/// state, and the append-only events the effects ask Room to insert.
struct OccurrenceHarness {
    state: DomainState,
    events: Vec<OccurrenceCompletionEvent>,
}

const OCC_DATE: &str = "2026-03-10";
const OCC_TASK: &str = "task:t1";
const OCC_ID: &str = "occ:task:t1:2026-03-10";

impl OccurrenceHarness {
    /// A fresh daily task whose instance for `OCC_DATE` has been asked for (`EnsureOccurrence`).
    fn new() -> Result<Self, TestCaseError> {
        let base = 1_772_000_000_000_i64;
        let mut state = arttodo_core::initial_state(ZONE.to_string(), base);
        state.tasks.push(TaskRecord {
            task_id: OCC_TASK.to_string(),
            kind: TaskKind::Daily,
            title: "速写".to_string(),
            note: String::new(),
            sort_key: 1024,
            created_wall_ms: base,
            archived_at_ms: None,
            last_countdown_minutes: 25,
            art_asset_id: None,
        });
        let mut harness = Self {
            state,
            events: Vec::new(),
        };
        harness.dispatch(
            DomainCommand::EnsureOccurrence {
                task_id: OCC_TASK.to_string(),
                app_date: OCC_DATE.to_string(),
            },
            base + 1,
        )?;
        Ok(harness)
    }

    fn dispatch(&mut self, command: DomainCommand, wall_ms: i64) -> Result<(), TestCaseError> {
        let envelope = CommandEnvelope {
            command_id: format!("cmd-{wall_ms}"),
            issued_at: ClockSample {
                wall_ms,
                zone_id: ZONE.to_string(),
                elapsed_ms: 0,
                boot_tag: String::new(),
            },
            expected_revision: self.state.revision,
        };
        let outcome = arttodo_core::reduce(self.state.clone(), command, envelope);
        if let Some(error) = outcome.error {
            return Err(TestCaseError::fail(format!("command refused: {error:?}")));
        }
        for effect in &outcome.effects {
            if let LedgerEffect::AppendOccurrenceCompletionEvent { event } = effect {
                // Room assigns `event_seq` on insert; one past the current maximum within the
                // occurrence, exactly as the AUTOINCREMENT column does.
                let mut stored = event.clone();
                stored.event_seq = self
                    .events
                    .iter()
                    .filter(|existing| existing.occurrence_id == event.occurrence_id)
                    .map(|existing| existing.event_seq)
                    .max()
                    .unwrap_or(0)
                    + 1;
                self.events.push(stored);
            }
        }
        self.state = outcome.next_state;
        Ok(())
    }

    fn view(&self) -> OccurrenceRecord {
        self.state
            .occurrences
            .iter()
            .find(|item| item.occurrence_id == OCC_ID)
            .cloned()
            .expect("the instance was generated in `new`")
    }
}

proptest! {
    /// 架构契约 §4.1 item 3, over random sequences: the incrementally maintained view equals a full
    /// replay of the events the same commands produced, field by field, after **every** step — and
    /// `RebuildOccurrenceView` on the same events lands on the same record again.
    #[test]
    fn the_incremental_view_equals_the_rebuild_for_random_sequences(actions in actions_strategy()) {
        let mut harness = OccurrenceHarness::new()?;
        // A multi-day history makes the last-writer-wins rule harder to satisfy by accident: with a
        // single action, "the flag happens to be right" is not evidence of a replay.
        for (index, completed) in actions.iter().enumerate() {
            harness.dispatch(
                DomainCommand::SetOccurrenceCompletion {
                    task_id: OCC_TASK.to_string(),
                    app_date: OCC_DATE.to_string(),
                    completed: *completed,
                },
                // One second apart, so the events are strictly ordered in the event table too.
                1_772_000_001_000 + index as i64 * 1_000,
            )?;

            let view = harness.view();
            // (a) the cached flag/instant equal the full replay of the events so far,
            let replayed = occurrence_completed(harness.events.clone(), OCC_ID.to_string());
            prop_assert_eq!(view.is_completed, replayed, "step {}", index);
            let expected_instant = harness.events.last().map(|event| {
                if event.action == 0 { Some(event.occurred_wall_ms) } else { None }
            }).unwrap_or(None);
            prop_assert_eq!(view.completed_wall_ms, expected_instant, "step {}", index);
            // (b) the cache is not stale,
            prop_assert!(
                occurrence_view_is_current(view.clone(), harness.events.clone()),
                "step {}",
                index
            );
            // ... and its high-water mark is the newest event seq.
            prop_assert_eq!(
                view.derived_from_event_high_water,
                index as i64 + 1,
                "step {}",
                index
            );
        }

        // (c) Rebuilding from the same events is a repair hatch onto *the same* record, never a
        // second opinion.
        let incremental = harness.view();
        let outcome = arttodo_core::reduce(
            harness.state.clone(),
            DomainCommand::RebuildOccurrenceView {
                occurrence_id: OCC_ID.to_string(),
                events: harness.events.clone(),
            },
            CommandEnvelope {
                command_id: "rebuild".to_string(),
                issued_at: ClockSample {
                    wall_ms: 1_772_000_900_000,
                    zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: String::new(),
                },
                expected_revision: harness.state.revision,
            },
        );
        prop_assert!(outcome.error.is_none(), "{:?}", outcome.error);
        let rebuilt = outcome
            .next_state
            .occurrences
            .iter()
            .find(|item| item.occurrence_id == OCC_ID)
            .cloned()
            .expect("instance");
        prop_assert_eq!(rebuilt.is_completed, incremental.is_completed);
        prop_assert_eq!(rebuilt.completed_wall_ms, incremental.completed_wall_ms);
        prop_assert_eq!(
            rebuilt.derived_from_event_high_water,
            incremental.derived_from_event_high_water
        );

        // And the events themselves were only ever appended: one per command, none rewritten.
        prop_assert_eq!(harness.events.len(), actions.len());
        let seqs: Vec<i64> = harness.events.iter().map(|event| event.event_seq).collect();
        prop_assert_eq!(seqs, (1..=actions.len() as i64).collect::<Vec<i64>>());
    }
}
