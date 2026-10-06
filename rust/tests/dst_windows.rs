//! AC-08 / AC-15 property half: the day-window partition, 23 h / 25 h days, and slice sums.
//!
//! 架构契约 §2.3 rule 3 makes `[start, end)` an *exact partition* of the time axis and §2.3's N3
//! clause demands three assertions on the midnight-jump zones. `midnight_split.rs` pins the named
//! cases; this file generalises them, because the interesting failures (an off-by-one at a rolled
//! `00:00`, a slice that skips the last millisecond of a 25 h day) only show up on inputs nobody
//! wrote down.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test dst_windows`

use arttodo_core::*;
use proptest::prelude::*;

/// The zones E19 measured, plus a few ordinary ones so a passing property is not an artefact of
/// "all these zones have transitions".
const ZONES: [&str; 8] = [
    "America/Sao_Paulo",
    "America/Havana",
    "America/Santiago",
    "Asia/Beirut",
    "Europe/Berlin",
    "Pacific/Auckland",
    "Asia/Shanghai",
    "UTC",
];

/// The four 2018 transition days from E19/E20, with the length `day_window` must report. A 23 h day
/// is 82800 s, a 25 h day 90000 s.
const TRANSITION_DAYS: [(&str, &str, i64); 4] = [
    ("America/Sao_Paulo", "2018-11-04", 82_800),
    ("America/Havana", "2018-11-04", 90_000),
    ("America/Santiago", "2018-08-12", 82_800),
    ("Asia/Beirut", "2018-03-25", 82_800),
];

/// A civil day in the same neighbourhood as the measured transitions, plus an offset in seconds to
/// probe inside it.
fn date_and_offset() -> impl Strategy<Value = (usize, i64)> {
    (0_usize..4, 0_i64..90_000)
}

proptest! {
    /// (c) 任取一刻 `t`，恰有一个 `DayWindow` 满足 `start <= t < end`.
    ///
    /// The windows are the real ones (`day_window`), not a hand-built range, so this is the check
    /// that the roll-forward at a missing midnight cannot leave a hole or an overlap.
    #[test]
    fn every_instant_lies_in_exactly_one_window((zone_index, offset) in date_and_offset()) {
        let (zone, first, _) = TRANSITION_DAYS[zone_index];
        let first_date: jiff::civil::Date = first.parse().expect("date");
        let windows: Vec<DayWindow> = (0..4)
            .map(|step| {
                let date = first_date
                    .checked_add(jiff::Span::new().days(step - 1))
                    .expect("date shift");
                day_window(date.to_string(), zone.to_string(), 1).expect("window")
            })
            .collect();

        // A probe inside the middle window, taken from that window's own start.
        let probe = windows[1].start_wall_ms + offset * 1000;
        let hits: Vec<&DayWindow> = windows
            .iter()
            .filter(|w| probe >= w.start_wall_ms && probe < w.end_wall_ms)
            .collect();
        prop_assert_eq!(hits.len(), 1, "{}: instant {} hit {} windows", zone, probe, hits.len());

        // And the partition is dense: the probe lands inside the window whose computed day length
        // covers it.
        let hit = hits[0];
        prop_assert!(hit.day_seconds > 0, "{}: day length must be positive", zone);
    }

    /// (a) 连续两日 `DayWindow` 首尾相接: `end(d) == start(d + 1)` for a run of days around the
    /// transition, in every measured zone.
    #[test]
    fn consecutive_windows_are_contiguous(zone_index in 0_usize..TRANSITION_DAYS.len()) {
        let (zone, first, _) = TRANSITION_DAYS[zone_index];
        let first_date: jiff::civil::Date = first.parse().expect("date");
        let mut previous: Option<DayWindow> = None;
        for step in 0..5 {
            let date = first_date
                .checked_add(jiff::Span::new().days(step))
                .expect("date shift");
            let window = day_window(date.to_string(), zone.to_string(), 1).expect("window");
            if let Some(before) = &previous {
                prop_assert_eq!(
                    before.end_wall_ms,
                    window.start_wall_ms,
                    "{} {} is not contiguous with the previous day",
                    zone,
                    date
                );
            }
            prop_assert_eq!(window.day_seconds, (window.end_wall_ms - window.start_wall_ms) / 1000);
            previous = Some(window);
        }
    }

    /// (b) 跨该日的一段区间切分后合计秒数 == 区间长度 — for a span that starts before the transition
    /// day's window and ends inside the next one, and for a span that covers the whole day.
    #[test]
    fn a_span_crossing_the_transition_sums_to_its_own_length(
        (zone_index, before_seconds) in (0_usize..TRANSITION_DAYS.len(), 0_i64..3_600),
        (after_seconds, whole_day) in (0_i64..3_600, any::<bool>()),
    ) {
        let (zone, first, _) = TRANSITION_DAYS[zone_index];
        let day = day_window(first.to_string(), zone.to_string(), 1).expect("window");
        let previous = day_window(
            (first.parse::<jiff::civil::Date>().expect("date")
                .checked_sub(jiff::Span::new().days(1)).expect("previous")).to_string(),
            zone.to_string(),
            1,
        )
        .expect("previous window");

        let (start, end) = if whole_day {
            (day.start_wall_ms, day.end_wall_ms)
        } else {
            (
                previous.end_wall_ms - before_seconds * 1000,
                day.start_wall_ms + after_seconds * 1000,
            )
        };

        let slices = slice_interval("s".to_string(), 1, start, end, zone.to_string(), 1, vec![])
            .expect("slices");
        let total: i64 = slices
            .iter()
            .map(|slice| (slice.end_wall_ms - slice.start_wall_ms) / 1000)
            .sum();
        prop_assert_eq!(total, (end - start) / 1000, "{} span {}..{}", zone, start, end);

        // Contiguous pieces, each non-empty, and no piece straddles two windows.
        for pair in slices.windows(2) {
            prop_assert_eq!(pair[0].end_wall_ms, pair[1].start_wall_ms, "{}", zone);
        }
        for slice in &slices {
            prop_assert!(slice.end_wall_ms > slice.start_wall_ms, "{}", zone);
            if let Ok(window) = day_window(slice.app_date.iso.clone(), zone.to_string(), 1) {
                prop_assert!(slice.start_wall_ms >= window.start_wall_ms, "{}", zone);
                prop_assert!(slice.start_wall_ms < window.end_wall_ms, "{}", zone);
                prop_assert!(slice.end_wall_ms <= window.end_wall_ms, "{}", zone);
            }
        }
    }

    /// The day length is what the window says it is: the slices covering one whole day sum to
    /// `day_seconds`, on ordinary days, 23 h days and 25 h days alike. This is the rule that stops a
    /// future change from quietly reintroducing a hard-coded 86400.
    #[test]
    fn day_seconds_equals_the_whole_day_slice_sum(zone_index in 0_usize..ZONES.len(), day_offset in -3_i64..4) {
        let zone = ZONES[zone_index];
        let anchor = day_window("2018-11-04".to_string(), zone.to_string(), 1)
            .expect("anchor window")
            .app_date
            .iso;
        let date: jiff::civil::Date = anchor.parse().expect("date");
        let date = date
            .checked_add(jiff::Span::new().days(day_offset))
            .expect("date shift");
        let window = day_window(date.to_string(), zone.to_string(), 1).expect("window");

        let slices = slice_interval(
            "s".to_string(),
            1,
            window.start_wall_ms,
            window.end_wall_ms,
            zone.to_string(),
            1,
            vec![],
        )
        .expect("slices");
        let total: i64 = slices
            .iter()
            .map(|slice| (slice.end_wall_ms - slice.start_wall_ms) / 1000)
            .sum();
        prop_assert_eq!(total, window.day_seconds, "{} {}", zone, date);
        prop_assert!(!slices.is_empty(), "{} {}", zone, date);
    }

    /// The two named days are exactly the lengths E19 measured, so a `jiff` upgrade that changes the
    /// roll-forward policy cannot pass unnoticed (工程布局与版本锁定.md §5.5).
    #[test]
    fn the_measured_transition_days_keep_their_lengths(zone_index in 0_usize..TRANSITION_DAYS.len()) {
        let (zone, date, expected) = TRANSITION_DAYS[zone_index];
        let window = day_window(date.to_string(), zone.to_string(), 1).expect("window");
        prop_assert_eq!(window.day_seconds, expected, "{} {}", zone, date);
    }
}
