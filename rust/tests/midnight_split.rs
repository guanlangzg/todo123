//! AC-08 / AC-15: midnight splitting, 23 h / 25 h days, and day-window contiguity.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test midnight_split`

use arttodo_core::*;

fn revision(wall_ms: i64, title: &str) -> TitleRevision {
    TitleRevision {
        effective_wall_ms: wall_ms,
        title: title.to_string(),
    }
}

/// AC-08: 23:50 -> 00:20 books 600 s before midnight and 1200 s after.
#[test]
fn midnight_split_books_ten_and_twenty_minutes() {
    let zone = "Asia/Shanghai";
    let before = day_window("2026-03-09".to_string(), zone.to_string(), 1).expect("window");
    let after = day_window("2026-03-10".to_string(), zone.to_string(), 1).expect("window");
    assert_eq!(
        before.end_wall_ms, after.start_wall_ms,
        "windows must be contiguous"
    );

    let start = before.end_wall_ms - 10 * 60 * 1000;
    let end = after.start_wall_ms + 20 * 60 * 1000;
    let slices = slice_interval(
        "s1".to_string(),
        1,
        start,
        end,
        zone.to_string(),
        1,
        vec![revision(start, "夜描")],
    )
    .expect("slices");

    assert_eq!(slices.len(), 2);
    assert_eq!(slices[0].app_date.iso, "2026-03-09");
    assert_eq!((slices[0].end_wall_ms - slices[0].start_wall_ms) / 1000, 600);
    assert_eq!(slices[1].app_date.iso, "2026-03-10");
    assert_eq!((slices[1].end_wall_ms - slices[1].start_wall_ms) / 1000, 1200);
    let total: i64 = slices
        .iter()
        .map(|s| (s.end_wall_ms - s.start_wall_ms) / 1000)
        .sum();
    assert_eq!(total, 1800);
}

/// AC-08 / N3: consecutive windows stay contiguous across a midnight DST jump, and the split of
/// an interval crossing it sums to the interval length.
#[test]
fn midnight_dst_transitions_keep_windows_contiguous() {
    let cases = [
        ("America/Sao_Paulo", "2018-11-04", "2018-11-05"),
        ("America/Havana", "2018-11-04", "2018-11-05"),
        ("America/Santiago", "2018-08-12", "2018-08-13"),
        ("Asia/Beirut", "2018-03-25", "2018-03-26"),
    ];
    for (zone, first, second) in cases {
        let a = day_window(first.to_string(), zone.to_string(), 1).expect("window a");
        let b = day_window(second.to_string(), zone.to_string(), 1).expect("window b");
        assert_eq!(
            a.end_wall_ms, b.start_wall_ms,
            "{zone} windows must be contiguous"
        );

        // An interval spanning both days, split by day boundaries only.
        let start = a.end_wall_ms - 600_000;
        let end = b.start_wall_ms + 600_000;
        let slices = slice_interval(
            "s".to_string(),
            1,
            start,
            end,
            zone.to_string(),
            1,
            vec![revision(start, "t")],
        )
        .expect("slices");
        let total: i64 = slices.iter().map(|s| s.end_wall_ms - s.start_wall_ms).sum();
        assert_eq!(total, end - start, "{zone} split must sum to the interval length");
        for pair in slices.windows(2) {
            assert_eq!(pair[0].end_wall_ms, pair[1].start_wall_ms, "{zone}");
        }
    }
}

/// N3: on a day whose local 00:00 does not exist, the window starts at the rolled-forward instant
/// and is 23 h long; the 25 h day is likewise a real window length.
#[test]
fn day_lengths_reflect_dst() {
    let sao_paulo = day_window("2018-11-04".to_string(), "America/Sao_Paulo".to_string(), 1).expect("window");
    assert_eq!(sao_paulo.day_seconds, 82_800);
    let havana = day_window("2018-11-04".to_string(), "America/Havana".to_string(), 1).expect("window");
    assert_eq!(havana.day_seconds, 90_000);
}

/// N3 (c): any instant falls in exactly one day window.
#[test]
fn every_instant_belongs_to_exactly_one_day() {
    let zone = "America/Sao_Paulo";
    let windows: Vec<DayWindow> = ["2018-11-02", "2018-11-03", "2018-11-04", "2018-11-05"]
        .iter()
        .map(|iso| day_window((*iso).to_string(), zone.to_string(), 1).expect("window"))
        .collect();
    for window in &windows {
        for probe in [
            window.start_wall_ms,
            window.start_wall_ms + 1,
            window.end_wall_ms - 1,
        ] {
            let hits = windows
                .iter()
                .filter(|w| probe >= w.start_wall_ms && probe < w.end_wall_ms)
                .count();
            assert_eq!(hits, 1, "instant {probe} must belong to one day");
        }
    }
}

/// AC-08 / AC-15: slice seconds and the day length come from the same source.
#[test]
fn day_seconds_equals_slice_sum_for_that_day() {
    let zone = "America/Havana";
    let window = day_window("2018-11-04".to_string(), zone.to_string(), 1).expect("window");
    let slices = slice_interval(
        "s".to_string(),
        1,
        window.start_wall_ms,
        window.end_wall_ms,
        zone.to_string(),
        1,
        vec![revision(window.start_wall_ms, "t")],
    )
    .expect("slices");
    let total: i64 = slices
        .iter()
        .map(|s| (s.end_wall_ms - s.start_wall_ms) / 1000)
        .sum();
    assert_eq!(total, window.day_seconds);
}

/// AC-14: a rename inside the interval cuts the segment; the earlier piece keeps the old title.
#[test]
fn rename_cuts_the_segment() {
    let zone = "Asia/Shanghai";
    let window = day_window("2026-03-10".to_string(), zone.to_string(), 1).expect("window");
    let start = window.start_wall_ms + 3_600_000;
    let slices = slice_interval(
        "s".to_string(),
        1,
        start,
        start + 3_600_000,
        zone.to_string(),
        1,
        vec![
            revision(start, "清晨草稿"),
            revision(start + 1_800_000, "清晨成稿"),
        ],
    )
    .expect("slices");
    assert_eq!(slices.len(), 2);
    assert_eq!(slices[0].title_snapshot, "清晨草稿");
    assert_eq!(slices[1].title_snapshot, "清晨成稿");
    assert_eq!(slices[0].app_date.iso, slices[1].app_date.iso);
}

/// 架构契约 §6: the slice key depends only on deterministic inputs.
#[test]
fn slice_ids_are_stable_and_distinct() {
    let zone = "Asia/Shanghai";
    let window = day_window("2026-03-10".to_string(), zone.to_string(), 1).expect("window");
    let start = window.start_wall_ms + 60_000;
    let first = slice_interval(
        "s".to_string(),
        3,
        start,
        start + 120_000,
        zone.to_string(),
        5,
        vec![],
    )
    .expect("a");
    let second = slice_interval(
        "s".to_string(),
        3,
        start,
        start + 120_000,
        zone.to_string(),
        5,
        vec![],
    )
    .expect("b");
    assert_eq!(first[0].slice_id, second[0].slice_id);
    let other_zone_epoch = slice_interval(
        "s".to_string(),
        3,
        start,
        start + 120_000,
        zone.to_string(),
        6,
        vec![],
    )
    .expect("c");
    assert_ne!(first[0].slice_id, other_zone_epoch[0].slice_id);
}
