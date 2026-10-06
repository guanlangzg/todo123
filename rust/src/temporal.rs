//! Application date, day windows and interval slicing — the single source of truth for time
//! bucketing (架构契约 §2). Kotlin must never compute a date or a day length itself.

use jiff::{civil::Date, tz::TimeZone, Timestamp};

use crate::error::DomainError;
use crate::types::{AppDate, DayWindow, IntervalSlice, TimeBucket, TitleRevision};

const DAY_MS: i64 = 86_400_000;

pub fn parse_app_date(iso: &str) -> Result<Date, DomainError> {
    iso.parse::<Date>().map_err(|e| DomainError::InvalidAppDate {
        detail: format!("'{iso}': {e}"),
    })
}

pub fn zone_of(zone_id: &str) -> Result<TimeZone, DomainError> {
    TimeZone::get(zone_id).map_err(|e| DomainError::InvalidZoneId {
        detail: format!("'{zone_id}': {e}"),
    })
}

fn timestamp_of(wall_ms: i64) -> Result<Timestamp, DomainError> {
    Timestamp::from_millisecond(wall_ms).map_err(|e| DomainError::InvalidAppDate {
        detail: format!("wall_ms {wall_ms}: {e}"),
    })
}

/// First instant of the civil day: `Date(d).at(00:00).to_zoned(zone)`.
///
/// When the local `00:00` does not exist (a midnight DST jump), jiff rolls forward to the first
/// existing instant — e.g. `01:00`. 架构契约 §2.3 defines that rolled-forward value to *be*
/// `start_wall_ms`, which keeps consecutive day windows exactly contiguous.
fn day_start_ms(date: Date, zone: &TimeZone) -> Result<i64, DomainError> {
    date.at(0, 0, 0, 0)
        .to_zoned(zone.clone())
        .map(|z| z.timestamp().as_millisecond())
        .map_err(|e| DomainError::InvalidAppDate {
            detail: format!("{date}: {e}"),
        })
}

pub fn app_date_of(wall_ms: i64, zone_id: &str, zone_epoch_seq: u32) -> Result<AppDate, DomainError> {
    let zone = zone_of(zone_id)?;
    // `Timestamp::to_zoned` is infallible in jiff; only the timestamp construction can fail.
    let zoned = timestamp_of(wall_ms)?.to_zoned(zone);
    Ok(AppDate {
        iso: zoned.date().to_string(),
        zone_epoch_seq,
    })
}

/// The `[start, end)` window of one civil day. `end` is always the *next* day's start, never an
/// independently computed value, so `end(d) == start(d + 1)` holds across DST transitions.
pub fn day_window(iso: &str, zone_id: &str, zone_epoch_seq: u32) -> Result<DayWindow, DomainError> {
    let zone = zone_of(zone_id)?;
    let date = parse_app_date(iso)?;
    let next = date.tomorrow().map_err(|e| DomainError::InvalidAppDate {
        detail: e.to_string(),
    })?;
    let start = day_start_ms(date, &zone)?;
    let end = day_start_ms(next, &zone)?;
    if end <= start {
        return Err(DomainError::internal(format!(
            "non-positive day window for {iso}: {start}..{end}"
        )));
    }
    Ok(DayWindow {
        app_date: AppDate {
            iso: date.to_string(),
            zone_epoch_seq,
        },
        start_wall_ms: start,
        end_wall_ms: end,
        day_seconds: (end - start) / 1000,
    })
}

/// The day window that actually contains `wall_ms`, correcting for the mismatch between
/// `app_date_of` and the `[start, end)` partition that §2.3 rule 4 warns about.
fn window_for_instant(wall_ms: i64, zone_id: &str, zone_epoch_seq: u32) -> Result<DayWindow, DomainError> {
    let mut window = day_window(
        &app_date_of(wall_ms, zone_id, zone_epoch_seq)?.iso,
        zone_id,
        zone_epoch_seq,
    )?;
    // Bounded: a day is at most 25 h, so a couple of steps always suffice.
    for _ in 0..3 {
        if wall_ms >= window.end_wall_ms {
            let next = parse_app_date(&window.app_date.iso)?.tomorrow().map_err(|e| {
                DomainError::InvalidAppDate {
                    detail: e.to_string(),
                }
            })?;
            window = day_window(&next.to_string(), zone_id, zone_epoch_seq)?;
        } else if wall_ms < window.start_wall_ms {
            let prev = parse_app_date(&window.app_date.iso)?.yesterday().map_err(|e| {
                DomainError::InvalidAppDate {
                    detail: e.to_string(),
                }
            })?;
            window = day_window(&prev.to_string(), zone_id, zone_epoch_seq)?;
        } else {
            return Ok(window);
        }
    }
    Err(DomainError::internal(format!(
        "could not place instant {wall_ms} into a day window"
    )))
}

/// Deterministic slice key (架构契约 §6): no clock, no randomness, no command id.
fn slice_id(session_id: &str, seg_seq: u32, slice_start_wall_ms: i64, zone_epoch_seq: u32) -> String {
    format!("{session_id}:{seg_seq}:{slice_start_wall_ms}:{zone_epoch_seq}")
}

/// Title in force at `at_wall_ms`: the latest revision at or before that instant, else the
/// earliest known revision.
fn title_at(revisions: &[TitleRevision], at_wall_ms: i64) -> String {
    let mut chosen: Option<&TitleRevision> = None;
    for revision in revisions {
        if revision.effective_wall_ms <= at_wall_ms {
            chosen = Some(revision);
        }
    }
    match chosen.or_else(|| revisions.first()) {
        Some(revision) => revision.title.clone(),
        None => String::new(),
    }
}

/// Cut `[start_wall_ms, end_wall_ms)` into slices.
///
/// Two things create a boundary, and only these two:
/// 1. the end of a civil day, taken from `day_window` — so slice durations always sum to the
///    interval length, including on 23-hour and 25-hour days;
/// 2. a title revision inside the interval (AC-14) — so a rename splits the segment and the
///    earlier piece keeps the old title.
pub fn slice_interval(
    session_id: &str,
    seg_seq: u32,
    start_wall_ms: i64,
    end_wall_ms: i64,
    zone_id: &str,
    zone_epoch_seq: u32,
    title_revisions: Vec<TitleRevision>,
) -> Result<Vec<IntervalSlice>, DomainError> {
    if end_wall_ms < start_wall_ms {
        return Err(DomainError::PreconditionFailed {
            reason: format!("interval end {end_wall_ms} precedes start {start_wall_ms}"),
        });
    }
    if end_wall_ms == start_wall_ms {
        return Ok(Vec::new());
    }
    let mut revisions = title_revisions;
    revisions.sort_by_key(|r| r.effective_wall_ms);
    let revision_cuts: Vec<i64> = revisions
        .iter()
        .map(|revision| revision.effective_wall_ms)
        .filter(|cut| *cut > start_wall_ms && *cut < end_wall_ms)
        .collect();

    let mut slices = Vec::new();
    let mut cursor = start_wall_ms;
    // Guard bound: 25 h days mean a multi-year interval would still be < 10^5 iterations; the
    // interval itself is a single work segment, so this never trips in practice.
    let mut guard = 0;
    while cursor < end_wall_ms {
        guard += 1;
        if guard > 10_000 {
            return Err(DomainError::internal(
                "slice_interval exceeded its iteration guard",
            ));
        }
        let window = window_for_instant(cursor, zone_id, zone_epoch_seq)?;
        let day_end = window.end_wall_ms.min(end_wall_ms);
        let revision_end = revision_cuts
            .iter()
            .copied()
            .find(|cut| *cut > cursor)
            .unwrap_or(end_wall_ms);
        let slice_end = day_end.min(revision_end);
        slices.push(IntervalSlice {
            slice_id: slice_id(session_id, seg_seq, cursor, zone_epoch_seq),
            app_date: window.app_date.clone(),
            start_wall_ms: cursor,
            end_wall_ms: slice_end,
            title_snapshot: title_at(&revisions, cursor),
        });
        cursor = slice_end;
    }
    Ok(slices)
}

/// Guard used by tests and by `FinishSession` when a completion target is derived.
pub fn is_full_day(seconds: i64) -> bool {
    seconds == DAY_MS / 1000
}

/// Monday of the week containing `date`. Weeks are ISO-8601 (Monday-anchored) because that is the
/// convention the week trend in the interface is built on; nothing else in the domain decides it.
fn monday_of(date: Date) -> Result<Date, DomainError> {
    let mut current = date;
    for _ in 0..date.weekday().to_monday_zero_offset() {
        current = current.yesterday().map_err(|e| DomainError::InvalidAppDate {
            detail: e.to_string(),
        })?;
    }
    Ok(current)
}

/// Label of the statistical bucket that `iso` falls into: the day itself, its Monday, or
/// `YYYY-MM`. This is the only place the day/week/month boundary is decided, so the day view, the
/// trend and the heat map can never bucket the same row differently (stats.rs).
pub fn bucket_label(iso: &str, bucket: TimeBucket) -> Result<String, DomainError> {
    let date = parse_app_date(iso)?;
    match bucket {
        TimeBucket::Day => Ok(date.to_string()),
        TimeBucket::Week => Ok(monday_of(date)?.to_string()),
        TimeBucket::Month => Ok(date.first_of_month().to_string()[..7].to_string()),
    }
}

/// The civil day that contains `wall_ms`, as an ISO label. Unlike `app_date_of` this consults the
/// day windows, so it agrees with how slicing assigns instants (架构契约 §2.3 rule 4).
pub fn day_label_of(wall_ms: i64, zone_id: &str, zone_epoch_seq: u32) -> Result<String, DomainError> {
    Ok(window_for_instant(wall_ms, zone_id, zone_epoch_seq)?.app_date.iso)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rev(wall_ms: i64, title: &str) -> TitleRevision {
        TitleRevision {
            effective_wall_ms: wall_ms,
            title: title.to_string(),
        }
    }

    #[test]
    fn sao_paulo_midnight_skip_day_is_contiguous() {
        // 2018-11-04 in Sao Paulo has no local 00:00 (E19): the day starts at 01:00 and is 23 h.
        let day = day_window("2018-11-04", "America/Sao_Paulo", 1).expect("window");
        assert_eq!(day.day_seconds, 82_800);
        let next = day_window("2018-11-05", "America/Sao_Paulo", 1).expect("window");
        assert_eq!(day.end_wall_ms, next.start_wall_ms);
    }

    #[test]
    fn havana_long_day_is_contiguous() {
        let day = day_window("2018-11-04", "America/Havana", 1).expect("window");
        assert_eq!(day.day_seconds, 90_000);
        let next = day_window("2018-11-05", "America/Havana", 1).expect("window");
        assert_eq!(day.end_wall_ms, next.start_wall_ms);
    }

    #[test]
    fn cross_midnight_splits_10_and_20_minutes() {
        let zone = "Asia/Shanghai";
        let day_before = day_window("2026-03-09", zone, 1).expect("window");
        let day_after = day_window("2026-03-10", zone, 1).expect("window");
        let start = day_before.end_wall_ms - 10 * 60 * 1000;
        let end = day_after.start_wall_ms + 20 * 60 * 1000;
        let slices = slice_interval("s1", 1, start, end, zone, 1, vec![rev(start, "写生")]).expect("slices");
        assert_eq!(slices.len(), 2);
        assert_eq!(slices[0].app_date.iso, "2026-03-09");
        assert_eq!(slices[0].end_wall_ms - slices[0].start_wall_ms, 600_000);
        assert_eq!(slices[1].app_date.iso, "2026-03-10");
        assert_eq!(slices[1].end_wall_ms - slices[1].start_wall_ms, 1_200_000);
    }

    #[test]
    fn slices_sum_to_interval_length_on_dst_days() {
        for zone in [
            "America/Sao_Paulo",
            "America/Havana",
            "America/Santiago",
            "Asia/Beirut",
        ] {
            let start = day_window("2018-11-03", zone, 1).expect("window").start_wall_ms;
            let end = day_window("2018-11-06", zone, 1).expect("window").start_wall_ms;
            let slices = slice_interval("s2", 1, start, end, zone, 1, vec![rev(start, "t")]).expect("slices");
            let total: i64 = slices.iter().map(|s| s.end_wall_ms - s.start_wall_ms).sum();
            assert_eq!(total, end - start, "{zone}");
            for pair in slices.windows(2) {
                assert_eq!(pair[0].end_wall_ms, pair[1].start_wall_ms, "{zone}");
            }
        }
    }

    #[test]
    fn every_instant_belongs_to_exactly_one_day() {
        let zone = "America/Sao_Paulo";
        let mut windows = Vec::new();
        for iso in ["2018-11-02", "2018-11-03", "2018-11-04", "2018-11-05"] {
            windows.push(day_window(iso, zone, 1).expect("window"));
        }
        for window in &windows {
            let probe = window.start_wall_ms;
            let hits = windows
                .iter()
                .filter(|w| probe >= w.start_wall_ms && probe < w.end_wall_ms)
                .count();
            assert_eq!(hits, 1, "{}", window.app_date.iso);
        }
    }

    #[test]
    fn day_length_matches_slice_sum_for_one_day() {
        let zone = "America/Havana";
        let window = day_window("2018-11-04", zone, 1).expect("window");
        let slices = slice_interval("s3", 1, window.start_wall_ms, window.end_wall_ms, zone, 1, vec![])
            .expect("slices");
        let total: i64 = slices
            .iter()
            .map(|s| s.end_wall_ms - s.start_wall_ms)
            .sum::<i64>()
            / 1000;
        assert_eq!(total, window.day_seconds);
    }

    #[test]
    fn title_revision_cuts_the_segment() {
        let zone = "Asia/Shanghai";
        let window = day_window("2026-03-10", zone, 1).expect("window");
        let start = window.start_wall_ms + 3_600_000;
        let rename_at = start + 1_800_000;
        let slices = slice_interval(
            "s4",
            1,
            start,
            start + 3_600_000,
            zone,
            1,
            vec![rev(start, "旧名"), rev(rename_at, "新名")],
        )
        .expect("slices");
        assert_eq!(slices.len(), 2);
        assert_eq!(slices[0].title_snapshot, "旧名");
        assert_eq!(slices[1].title_snapshot, "新名");
        assert!(slices[0].slice_id.ends_with(":1"));
    }

    #[test]
    fn slice_ids_are_deterministic() {
        let zone = "Asia/Shanghai";
        let day = day_window("2026-03-10", zone, 1).expect("window");
        let args = (day.start_wall_ms, day.start_wall_ms + 60_000);
        let a = slice_interval("s", 2, args.0, args.1, zone, 7, vec![]).expect("a");
        let b = slice_interval("s", 2, args.0, args.1, zone, 7, vec![]).expect("b");
        assert_eq!(a[0].slice_id, b[0].slice_id);
        assert!(!is_full_day(a[0].end_wall_ms - a[0].start_wall_ms));
    }
}
