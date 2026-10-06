//! Todo/done state of *temporary* tasks, derived by replaying their completion events.
//!
//! Temporary tasks have no daily instance: completing one moves it to the history list, reopening
//! it puts it back among the pending items, and both actions append an event rather than mutating a
//! flag (AC-11). Replaying the same event list always yields the same state, and reopening never
//! erases the earlier completion — the history stays complete.

use crate::types::TemporaryCompletionEvent;

pub const ACTION_COMPLETE: i32 = 0;
pub const ACTION_REOPEN: i32 = 1;

/// Deterministic event sequence number: one past the highest already recorded.
pub fn next_event_seq(events: &[TemporaryCompletionEvent], task_id: &str) -> i64 {
    events
        .iter()
        .filter(|event| event.task_id == task_id)
        .map(|event| event.event_seq)
        .max()
        .unwrap_or(0)
        + 1
}

/// Replay `task_id`'s events in `event_seq` order; the last action wins.
///
/// A task with no event at all is pending, which is the state a freshly created temporary task is
/// in.
pub fn derive_completed(events: &[TemporaryCompletionEvent], task_id: &str) -> bool {
    let mut completed = false;
    let mut ordered: Vec<&TemporaryCompletionEvent> =
        events.iter().filter(|event| event.task_id == task_id).collect();
    ordered.sort_by_key(|event| event.event_seq);
    for event in ordered {
        match event.action {
            ACTION_COMPLETE => completed = true,
            ACTION_REOPEN => completed = false,
            // Unknown actions are ignored rather than treated as a state change; the same
            // tolerance exists for the daily view so a future event type cannot corrupt history.
            _ => {}
        }
    }
    completed
}

/// Number of completion events kept for a task, so callers can tell "never touched" from
/// "completed and reopened".
pub fn event_count(events: &[TemporaryCompletionEvent], task_id: &str) -> usize {
    events.iter().filter(|event| event.task_id == task_id).count()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(seq: i64, task_id: &str, action: i32, at: i64) -> TemporaryCompletionEvent {
        TemporaryCompletionEvent {
            event_seq: seq,
            task_id: task_id.to_string(),
            action,
            occurred_wall_ms: at,
            app_date: "2026-03-10".to_string(),
            title_snapshot: "刻章".to_string(),
        }
    }

    #[test]
    fn a_task_without_events_is_pending() {
        assert!(!derive_completed(&[], "t1"));
        assert_eq!(event_count(&[], "t1"), 0);
    }

    #[test]
    fn completing_then_reopening_then_completing_ends_completed() {
        let events = vec![
            event(1, "t1", ACTION_COMPLETE, 1_000),
            event(2, "t1", ACTION_REOPEN, 2_000),
            event(3, "t1", ACTION_COMPLETE, 3_000),
        ];
        assert!(derive_completed(&events, "t1"));
        assert_eq!(event_count(&events, "t1"), 3);
    }

    #[test]
    fn reopening_keeps_the_earlier_completion_in_history() {
        let events = vec![
            event(1, "t1", ACTION_COMPLETE, 1_000),
            event(2, "t1", ACTION_REOPEN, 2_000),
        ];
        assert!(!derive_completed(&events, "t1"));
        // The first event is still there: reopening is not an erase.
        assert_eq!(events[0].action, ACTION_COMPLETE);
    }

    #[test]
    fn another_task_does_not_change_the_result() {
        let events = vec![
            event(1, "t1", ACTION_COMPLETE, 1_000),
            event(2, "t2", ACTION_REOPEN, 2_000),
        ];
        assert!(derive_completed(&events, "t1"));
        assert!(!derive_completed(&events, "t2"));
    }

    #[test]
    fn event_order_is_taken_from_event_seq_not_from_position() {
        let shuffled = vec![
            event(3, "t1", ACTION_COMPLETE, 3_000),
            event(1, "t1", ACTION_COMPLETE, 1_000),
            event(2, "t1", ACTION_REOPEN, 2_000),
        ];
        assert!(derive_completed(&shuffled, "t1"));
    }

    #[test]
    fn next_event_seq_continues_after_the_highest_recorded() {
        let events = vec![
            event(4, "t1", ACTION_COMPLETE, 1_000),
            event(9, "t2", ACTION_REOPEN, 2_000),
        ];
        assert_eq!(next_event_seq(&events, "t1"), 5);
        assert_eq!(next_event_seq(&events, "t3"), 1);
    }
}
