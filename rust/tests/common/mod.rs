//! Shared test helpers.
//!
//! `DomainState` deliberately does *not* carry the completion event logs: `occurrence_completion_event`
//! and `temporary_completion_event` are Room's append-only authoritative tables (架构契约 §4.1/§4.2),
//! and `event_seq` is assigned by SQLite's `AUTOINCREMENT`, so Rust cannot mint it. The domain
//! returns them as `LedgerEffect`s instead.
//!
//! These helpers let a test collect those effects into a list, which is exactly what Room would
//! hold, so tests can replay the same evidence Kotlin would read back.
//!
//! This module is in a subdirectory on purpose: `tests/*.rs` are test targets, `tests/common/mod.rs`
//! is not, so it is shared code rather than a target of its own.

#![allow(dead_code)]

use arttodo_core::{DomainOutcome, LedgerEffect, OccurrenceCompletionEvent, TemporaryCompletionEvent};

/// Authoritative event logs, in the shape Room stores them (append-only, sequence assigned on
/// insert).
#[derive(Debug, Clone, Default)]
pub struct EventLog {
    pub occurrence: Vec<OccurrenceCompletionEvent>,
    pub temporary: Vec<TemporaryCompletionEvent>,
}

impl EventLog {
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends whatever the outcome asked Room to insert, assigning the sequence number Room would
    /// assign (one past the current maximum), because the domain leaves `event_seq` at 0.
    pub fn absorb(&mut self, outcome: &DomainOutcome) -> &mut Self {
        for effect in &outcome.effects {
            match effect {
                LedgerEffect::AppendOccurrenceCompletionEvent { event } => {
                    let mut stored = event.clone();
                    stored.event_seq = self
                        .occurrence
                        .iter()
                        .filter(|existing| existing.occurrence_id == event.occurrence_id)
                        .map(|existing| existing.event_seq)
                        .max()
                        .unwrap_or(0)
                        + 1;
                    self.occurrence.push(stored);
                }
                LedgerEffect::AppendTemporaryCompletionEvent {
                    task_id,
                    action,
                    occurred_wall_ms,
                    app_date,
                    title_snapshot,
                } => {
                    let next = self
                        .temporary
                        .iter()
                        .filter(|existing| existing.task_id == *task_id)
                        .map(|existing| existing.event_seq)
                        .max()
                        .unwrap_or(0)
                        + 1;
                    self.temporary.push(TemporaryCompletionEvent {
                        event_seq: next,
                        task_id: task_id.clone(),
                        action: *action,
                        occurred_wall_ms: *occurred_wall_ms,
                        app_date: app_date.clone(),
                        title_snapshot: title_snapshot.clone(),
                    });
                }
                _ => {}
            }
        }
        self
    }

    /// Events belonging to one occurrence, in sequence order — what `RebuildOccurrenceView` is fed.
    pub fn events_for(&self, occurrence_id: &str) -> Vec<OccurrenceCompletionEvent> {
        let mut own: Vec<OccurrenceCompletionEvent> = self
            .occurrence
            .iter()
            .filter(|event| event.occurrence_id == occurrence_id)
            .cloned()
            .collect();
        own.sort_by_key(|event| event.event_seq);
        own
    }

    pub fn events_for_task(&self, task_id: &str) -> Vec<TemporaryCompletionEvent> {
        let mut own: Vec<TemporaryCompletionEvent> = self
            .temporary
            .iter()
            .filter(|event| event.task_id == task_id)
            .cloned()
            .collect();
        own.sort_by_key(|event| event.event_seq);
        own
    }
}
