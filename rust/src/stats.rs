//! Statistical projection: one aggregation, four readers.
//!
//! Every number the interface shows — the calendar's daily total, the week/month trend, the
//! per-task share and the GitHub-style heat map — is derived from `(task_id, app_date)` groups that
//! are themselves replayed by `ledger::replay_total`. There is deliberately no second summation
//! path: if the day total and the heat map could disagree, AC-12/N10 ("两模式合并单一总量，四处同值")
//! would be untestable.
//!
//! Grouping is by the `app_date` *label* together with `task_id`, never by `zone_epoch_seq`. That is
//! what makes 架构契约 §2.4's "跨纪元产生相同日期标签的两段投入在该日期统一求和" hold: a zone change
//! must not split one civil day into two rows.
//!
//! Rust still does not know what a week starts on for the *user*; the contract fixes Monday
//! (ISO-8601), and `temporal::bucket_label` is the only place that decides it.

use std::collections::BTreeMap;

use crate::error::DomainError;
use crate::ledger;
use crate::temporal;
use crate::types::{DayTotal, LedgerRow, PeriodTotal, TaskShare, TimeBucket};

/// The single aggregation step: group rows by `(task_id, app_date)` and replay each group.
///
/// Returned as a `BTreeMap` so both the iteration order and every derived projection are
/// deterministic — the UI must not show a different ordering on every recomputation.
pub fn group_by_task_day(rows: &[LedgerRow]) -> BTreeMap<(String, String), Vec<&LedgerRow>> {
    let mut groups: BTreeMap<(String, String), Vec<&LedgerRow>> = BTreeMap::new();
    for row in rows {
        groups
            .entry((row.task_id.clone(), row.app_date.clone()))
            .or_default()
            .push(row);
    }
    groups
}

/// Daily totals, ascending by `app_date`. The heat map renders exactly this vector.
pub fn day_totals(rows: &[LedgerRow]) -> Vec<DayTotal> {
    let mut per_day: BTreeMap<String, i64> = BTreeMap::new();
    for ((_task_id, app_date), group) in group_by_task_day(rows) {
        *per_day.entry(app_date).or_insert(0) += ledger::replay_total_refs(&group);
    }
    per_day
        .into_iter()
        .map(|(app_date, seconds)| DayTotal { app_date, seconds })
        .collect()
}

/// The same totals bucketed into days, Monday-anchored weeks or calendar months.
///
/// Buckets are built from `day_totals`, so a week is never the sum of a different set of rows than
/// the day view shows.
pub fn period_totals(rows: &[LedgerRow], bucket: TimeBucket) -> Result<Vec<PeriodTotal>, DomainError> {
    let mut per_bucket: BTreeMap<String, i64> = BTreeMap::new();
    for total in day_totals(rows) {
        let label = temporal::bucket_label(&total.app_date, bucket)?;
        *per_bucket.entry(label).or_insert(0) += total.seconds;
    }
    Ok(per_bucket
        .into_iter()
        .map(|(label, seconds)| PeriodTotal { label, seconds })
        .collect())
}

/// Each task's investment over the given rows, ascending by `task_id`.
pub fn task_shares(rows: &[LedgerRow]) -> Vec<TaskShare> {
    let mut per_task: BTreeMap<String, i64> = BTreeMap::new();
    for ((task_id, _app_date), group) in group_by_task_day(rows) {
        *per_task.entry(task_id).or_insert(0) += ledger::replay_total_refs(&group);
    }
    per_task
        .into_iter()
        .map(|(task_id, seconds)| TaskShare { task_id, seconds })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(
        seq: i64,
        task_id: &str,
        app_date: &str,
        kind: i32,
        delta: Option<i64>,
        set: Option<i64>,
    ) -> LedgerRow {
        LedgerRow {
            ledger_seq: seq,
            kind,
            ref_id: None,
            task_id: task_id.to_string(),
            app_date: app_date.to_string(),
            occurred_wall_ms: seq * 1_000,
            zone_epoch_seq: 1,
            delta_seconds: delta,
            set_total_seconds: set,
            created_wall_ms: seq * 1_000,
            edited_at_ms: None,
            is_deleted: false,
            deleted_at_ms: None,
        }
    }

    /// 20 + 30 -> set 35 -> +10 = 45 on the day, and the same 45 in every other reader.
    #[test]
    fn every_reader_agrees_on_the_same_aggregation() {
        let rows = vec![
            row(1, "t1", "2026-03-10", 1, Some(1200), None),
            row(2, "t1", "2026-03-10", 1, Some(1800), None),
            row(3, "t1", "2026-03-10", 2, None, Some(2100)),
            row(4, "t1", "2026-03-10", 1, Some(600), None),
            row(5, "t2", "2026-03-11", 1, Some(300), None),
        ];

        let days = day_totals(&rows);
        assert_eq!(
            days,
            vec![
                DayTotal {
                    app_date: "2026-03-10".to_string(),
                    seconds: 2700
                },
                DayTotal {
                    app_date: "2026-03-11".to_string(),
                    seconds: 300
                },
            ]
        );
        let day_sum: i64 = days.iter().map(|d| d.seconds).sum();
        let shares = task_shares(&rows);
        assert_eq!(
            shares,
            vec![
                TaskShare {
                    task_id: "t1".to_string(),
                    seconds: 2700
                },
                TaskShare {
                    task_id: "t2".to_string(),
                    seconds: 300
                },
            ]
        );
        assert_eq!(shares.iter().map(|s| s.seconds).sum::<i64>(), day_sum);
        for bucket in [TimeBucket::Day, TimeBucket::Week, TimeBucket::Month] {
            let periods = period_totals(&rows, bucket).expect("periods");
            assert_eq!(
                periods.iter().map(|p| p.seconds).sum::<i64>(),
                day_sum,
                "{bucket:?}"
            );
        }
    }

    /// 架构契约 §2.4: the same date label produced under two zone epochs is one day, not two.
    #[test]
    fn total_is_summed_per_date_label_across_zone_epochs() {
        let mut older = row(1, "t1", "2026-03-10", 1, Some(600), None);
        older.zone_epoch_seq = 1;
        let mut newer = row(2, "t1", "2026-03-10", 1, Some(900), None);
        newer.zone_epoch_seq = 2;
        assert_eq!(
            day_totals(&[older, newer]),
            vec![DayTotal {
                app_date: "2026-03-10".to_string(),
                seconds: 1500
            }]
        );
    }

    /// A week bucket must be the sum of the seven-ish days inside it, not of all rows.
    #[test]
    fn week_and_month_buckets_are_sums_of_their_days() {
        let rows = vec![
            row(1, "t1", "2026-03-09", 1, Some(600), None), // Monday
            row(2, "t1", "2026-03-10", 1, Some(900), None), // Tuesday
            row(3, "t1", "2026-03-16", 1, Some(300), None), // next Monday
        ];
        let weeks = period_totals(&rows, TimeBucket::Week).expect("weeks");
        assert_eq!(
            weeks,
            vec![
                PeriodTotal {
                    label: "2026-03-09".to_string(),
                    seconds: 1500
                },
                PeriodTotal {
                    label: "2026-03-16".to_string(),
                    seconds: 300
                },
            ]
        );
        let months = period_totals(&rows, TimeBucket::Month).expect("months");
        assert_eq!(
            months,
            vec![PeriodTotal {
                label: "2026-03".to_string(),
                seconds: 1800
            }]
        );
    }

    /// Deleted rows are skipped by the aggregation just like they are by the replay itself.
    #[test]
    fn deleted_rows_are_excluded_from_every_reader() {
        let mut rows = vec![
            row(1, "t1", "2026-03-10", 1, Some(600), None),
            row(2, "t1", "2026-03-10", 1, Some(900), None),
        ];
        rows[1].is_deleted = true;
        assert_eq!(
            day_totals(&rows),
            vec![DayTotal {
                app_date: "2026-03-10".to_string(),
                seconds: 600
            }]
        );
        assert_eq!(
            task_shares(&rows),
            vec![TaskShare {
                task_id: "t1".to_string(),
                seconds: 600
            }]
        );
    }

    /// A set-total is per task and per day: it must never pin another task's or another day's total.
    #[test]
    fn a_set_total_only_pins_its_own_task_day() {
        let rows = vec![
            row(1, "t1", "2026-03-10", 1, Some(1200), None),
            row(2, "t1", "2026-03-10", 2, None, Some(300)),
            row(3, "t2", "2026-03-10", 1, Some(1200), None),
            row(4, "t1", "2026-03-11", 1, Some(1200), None),
        ];
        assert_eq!(
            day_totals(&rows),
            vec![
                DayTotal {
                    app_date: "2026-03-10".to_string(),
                    seconds: 1500
                },
                DayTotal {
                    app_date: "2026-03-11".to_string(),
                    seconds: 1200
                },
            ]
        );
    }

    /// Bad date labels surface as a `DomainError`, never as a panic (架构契约 §10).
    #[test]
    fn a_bad_date_label_is_an_error() {
        let rows = vec![row(1, "t1", "not-a-date", 1, Some(60), None)];
        assert!(matches!(
            period_totals(&rows, TimeBucket::Week),
            Err(DomainError::InvalidAppDate { .. })
        ));
    }
}
