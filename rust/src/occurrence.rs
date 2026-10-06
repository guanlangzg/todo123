//! Daily occurrence generation and the completion event log.
//!
//! Completion is event-sourced: `occurrence_completion_event` is the only truth, and the
//! `is_completed` flag on an occurrence is a rebuildable cache whose high-water mark is tracked
//! explicitly (架构契约 §4.1).

use crate::types::{EventHighWater, OccurrenceCompletionEvent, OccurrenceRecord};

pub const ACTION_COMPLETE: i32 = 0;
pub const ACTION_REOPEN: i32 = 1;

/// Deterministic occurrence id, so `EnsureOccurrence` is idempotent without a lookup table scan.
pub fn occurrence_id(task_id: &str, app_date: &str) -> String {
    format!("occ:{task_id}:{app_date}")
}

/// The derived value of `daily_occurrence_view` for one occurrence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DerivedCompletion {
    pub is_completed: bool,
    pub completed_wall_ms: Option<i64>,
    pub high_water: i64,
}

/// Rebuild the completion cache from the authoritative events (in `event_seq` order).
pub fn derive_completion(events: &[OccurrenceCompletionEvent]) -> DerivedCompletion {
    let mut ordered: Vec<&OccurrenceCompletionEvent> = events.iter().collect();
    ordered.sort_by_key(|event| event.event_seq);
    let mut derived = DerivedCompletion {
        is_completed: false,
        completed_wall_ms: None,
        high_water: 0,
    };
    for event in ordered {
        derived.high_water = derived.high_water.max(event.event_seq);
        if event.action == ACTION_COMPLETE {
            derived.is_completed = true;
            derived.completed_wall_ms = Some(event.occurred_wall_ms);
        } else {
            derived.is_completed = false;
            derived.completed_wall_ms = None;
        }
    }
    derived
}

/// Incremental view maintenance: apply only the events newer than the recorded high-water mark.
/// The result must equal `derive_completion` on the full event list — that equivalence is the
/// single read point AC-03 and AC-07 both rely on.
pub fn advance_completion(
    current: DerivedCompletion,
    events: &[OccurrenceCompletionEvent],
) -> DerivedCompletion {
    let mut fresh: Vec<OccurrenceCompletionEvent> = events
        .iter()
        .filter(|event| event.event_seq > current.high_water)
        .cloned()
        .collect();
    if fresh.is_empty() {
        return current;
    }
    fresh.sort_by_key(|event| event.event_seq);
    let mut derived = current;
    for event in fresh {
        derived.high_water = derived.high_water.max(event.event_seq);
        if event.action == ACTION_COMPLETE {
            derived.is_completed = true;
            derived.completed_wall_ms = Some(event.occurred_wall_ms);
        } else {
            derived.is_completed = false;
            derived.completed_wall_ms = None;
        }
    }
    derived
}

/// True when the derived cache already reflects every event.
pub fn view_is_current(view: &OccurrenceRecord, events: &[OccurrenceCompletionEvent]) -> bool {
    derive_completion(events).high_water == view.derived_from_event_high_water
}

/// Replay one occurrence's events out of a mixed event list. This is what `RebuildOccurrenceView`
/// uses, and it must return exactly what the incremental path already holds (架构契约 §4.1).
pub fn replay_view(events: &[OccurrenceCompletionEvent], occurrence_id: &str) -> DerivedCompletion {
    let own: Vec<OccurrenceCompletionEvent> = events
        .iter()
        .filter(|event| event.occurrence_id == occurrence_id)
        .cloned()
        .collect();
    derive_completion(&own)
}

pub fn high_water_entry(occurrence_id: &str, high_water: i64) -> EventHighWater {
    EventHighWater {
        occurrence_id: occurrence_id.to_string(),
        high_water,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(seq: i64, action: i32, at: i64) -> OccurrenceCompletionEvent {
        OccurrenceCompletionEvent {
            event_seq: seq,
            occurrence_id: "occ:t1:2026-03-10".to_string(),
            action,
            occurred_wall_ms: at,
            app_date: "2026-03-10".to_string(),
            zone_epoch_seq: 1,
        }
    }

    #[test]
    fn occurrence_ids_are_deterministic() {
        assert_eq!(
            occurrence_id("t1", "2026-03-10"),
            occurrence_id("t1", "2026-03-10")
        );
        assert_ne!(
            occurrence_id("t1", "2026-03-10"),
            occurrence_id("t1", "2026-03-11")
        );
    }

    #[test]
    fn undo_clears_completion_without_touching_other_facts() {
        let events = vec![event(1, ACTION_COMPLETE, 1_000), event(2, ACTION_REOPEN, 2_000)];
        let derived = derive_completion(&events);
        assert!(!derived.is_completed);
        assert_eq!(derived.completed_wall_ms, None);
        assert_eq!(derived.high_water, 2);
    }

    #[test]
    fn incremental_view_equals_full_replay() {
        let events = vec![
            event(1, ACTION_COMPLETE, 1_000),
            event(2, ACTION_REOPEN, 2_000),
            event(3, ACTION_COMPLETE, 3_000),
        ];
        let mut incremental = DerivedCompletion {
            is_completed: false,
            completed_wall_ms: None,
            high_water: 0,
        };
        for prefix in [&events[..1], &events[..2], &events[..3]] {
            incremental = advance_completion(incremental, prefix);
        }
        assert_eq!(incremental, derive_completion(&events));
    }

    #[test]
    fn replaying_the_same_events_is_idempotent() {
        let events = vec![event(1, ACTION_COMPLETE, 1_000)];
        let once = advance_completion(
            DerivedCompletion {
                is_completed: false,
                completed_wall_ms: None,
                high_water: 0,
            },
            &events,
        );
        let twice = advance_completion(once, &events);
        assert_eq!(once, twice);
    }

    #[test]
    fn stale_view_is_detected() {
        let events = vec![event(1, ACTION_COMPLETE, 1_000), event(2, ACTION_REOPEN, 2_000)];
        let stale = OccurrenceRecord {
            occurrence_id: "occ:t1:2026-03-10".to_string(),
            task_id: "t1".to_string(),
            app_date: "2026-03-10".to_string(),
            zone_epoch_seq: 1,
            display_title_snapshot: "t".to_string(),
            created_at_ms: 0,
            is_completed: true,
            completed_wall_ms: Some(1_000),
            derived_from_event_high_water: 1,
        };
        assert!(!view_is_current(&stale, &events));
        let mut fresh = stale.clone();
        fresh.derived_from_event_high_water = 2;
        assert!(view_is_current(&fresh, &events));
    }

    #[test]
    fn high_water_entries_round_trip() {
        let entry = high_water_entry("occ:a:2026-03-10", 4);
        assert_eq!(entry.high_water, 4);
    }
}
