package app.arttodo.ui

import app.arttodo.ui.common.CalendarMath
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.HeatStep
import app.arttodo.ui.common.heatStepOf
import app.arttodo.ui.theme.TimerLadder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The presentation helpers the five screens rely on.
 *
 * These are the only pieces of Kotlin in the UI layer that compute anything — the string forms of a
 * duration and the civil-calendar arithmetic behind the calendar grid and the 53-week heat map — so
 * they are the pieces worth pinning down. Nothing here touches Android, so the test runs as plain
 * JUnit.
 *
 * The weekday assertions exist because a wrong weekday label was a real defect in the prototype
 * (设计系统 section 12.5 item 5), where 2026-10-05 was shown as Sunday instead of Monday. The dates below
 * are checked against `java.time` itself rather than against a table someone typed by hand.
 */
class UiFormattingTest {

    // ---- Duration formatting (设计系统 section 3.3: digits and units separated by a space) ----------

    @Test
    fun clock_switches_between_mm_ss_and_hh_mm_ss_at_the_hour() {
        assertThat(Format.clock(0)).isEqualTo("00:00")
        assertThat(Format.clock(59)).isEqualTo("00:59")
        assertThat(Format.clock(60)).isEqualTo("01:00")
        assertThat(Format.clock(3_599)).isEqualTo("59:59")
        // Exactly at one hour the hours field appears rather than wrapping to 00:00.
        assertThat(Format.clock(3_600)).isEqualTo("01:00:00")
        assertThat(Format.clock(5_076)).isEqualTo("01:24:36")
    }

    @Test
    fun clock_never_renders_a_negative_duration() {
        assertThat(Format.clock(-1)).isEqualTo("00:00")
        assertThat(Format.clock(-99_999)).isEqualTo("00:00")
    }

    @Test
    fun ledger_clock_drops_the_leading_hour_zero_but_keeps_the_hour() {
        assertThat(Format.ledgerClock(4_356)).isEqualTo("1:12:36")
        assertThat(Format.ledgerClock(2_700)).isEqualTo("45:00")
    }

    @Test
    fun minutes_prefers_hours_above_an_hour_and_never_prints_a_bare_hour_count() {
        assertThat(Format.minutes(0)).isEqualTo("0 分钟")
        assertThat(Format.minutes(25 * 60)).isEqualTo("25 分钟")
        assertThat(Format.minutes(30 * 60)).isEqualTo("30 分钟")
        assertThat(Format.minutes(90 * 60)).isEqualTo("1 小时 30 分")
        assertThat(Format.minutes(2 * 3600)).isEqualTo("2 小时")
    }

    @Test
    fun spoken_duration_keeps_seconds_because_the_timer_is_second_accurate() {
        assertThat(Format.spokenDuration(24 * 60 + 36)).isEqualTo("24 分 36 秒")
        assertThat(Format.spokenDuration(3_600 + 24 * 60 + 36)).isEqualTo("1 小时 24 分 36 秒")
        // Below a minute the minutes field is omitted rather than read as a flat "0 分".
        assertThat(Format.spokenDuration(45)).isEqualTo("45 秒")
    }

    // ---- Date labels (关键页面说明 section 3.1: year is always written out) --------------------------

    @Test
    fun date_label_writes_the_year_and_the_weekday_from_the_civil_calendar() {
        // 2026-10-05 is a Monday; the prototype once labelled it Sunday.
        assertThat(Format.dateLabel("2026-10-05")).isEqualTo("2026 年 10 月 5 日 周一")
        assertThat(Format.spokenDate("2026-10-05")).isEqualTo("2026 年 10 月 5 日 星期一")
        assertThat(Format.dateLabel("2026-10-04")).isEqualTo("2026 年 10 月 4 日 周日")
        assertThat(Format.dateLabel("2026-03-04")).isEqualTo("2026 年 3 月 4 日 周三")
    }

    @Test
    fun every_weekday_label_matches_java_time_for_a_whole_week() {
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        // 2026-10-05 is a Monday; walk seven days and compare each rendering with the calendar.
        val monday = LocalDate.of(2026, 10, 5)
        for (offset in 0..6) {
            val date = monday.plusDays(offset.toLong())
            val expected = names[date.dayOfWeek.value - 1]
            assertThat(Format.dateLabel(date.toString())).endsWith(expected)
        }
    }

    @Test
    fun an_unparseable_label_is_returned_unchanged_rather_than_guessed_at() {
        assertThat(Format.dateLabel("not-a-date")).isEqualTo("not-a-date")
    }

    // ---- Heat steps (设计系统 section 2.5) ----------------------------------------------------------

    @Test
    fun heat_steps_follow_the_documented_minute_boundaries() {
        assertThat(heatStepOf(0, hasRecord = false)).isEqualTo(HeatStep.None)
        assertThat(heatStepOf(0, hasRecord = true)).isEqualTo(HeatStep.Zero)
        assertThat(heatStepOf(29 * 60 + 59, hasRecord = true)).isEqualTo(HeatStep.One)
        assertThat(heatStepOf(30 * 60, hasRecord = true)).isEqualTo(HeatStep.Two)
        assertThat(heatStepOf(59 * 60 + 59, hasRecord = true)).isEqualTo(HeatStep.Two)
        assertThat(heatStepOf(60 * 60, hasRecord = true)).isEqualTo(HeatStep.Three)
        assertThat(heatStepOf(119 * 60 + 59, hasRecord = true)).isEqualTo(HeatStep.Three)
        assertThat(heatStepOf(120 * 60, hasRecord = true)).isEqualTo(HeatStep.Four)
        assertThat(heatStepOf(10 * 3600, hasRecord = true)).isEqualTo(HeatStep.Four)
    }

    @Test
    fun a_day_with_no_record_is_distinct_from_a_zero_value_day() {
        // These two must never collapse into the same step: without a record the cell is dashed, with a
        // zero-value record it is filled, and the legend describes both separately.
        assertThat(heatStepOf(0, hasRecord = false)).isNotEqualTo(heatStepOf(0, hasRecord = true))
    }

    // ---- Timer ladder (设计系统 section 3.5) --------------------------------------------------------

    @Test
    fun timer_ladder_picks_the_largest_step_that_fits() {
        assertThat(TimerLadder.pickSp(fontScale = 1.0f, availableWidthDp = 328f, hasHours = true)).isEqualTo(52)
        // At 2.0 the 52sp `HH:MM:SS` needs about 409dp, which does not fit; 40sp (about 314dp) does.
        assertThat(TimerLadder.pickSp(fontScale = 2.0f, availableWidthDp = 328f, hasHours = true)).isEqualTo(40)
        // The short form never overflows: 52sp `MM:SS` is about 263dp even at 2.0.
        assertThat(TimerLadder.pickSp(fontScale = 2.0f, availableWidthDp = 328f, hasHours = false)).isEqualTo(52)
    }

    @Test
    fun timer_ladder_never_returns_a_step_that_overflows_the_given_width() {
        for (fontScale in listOf(1.0f, 1.3f, 1.5f, 1.85f, 2.0f)) {
            for (available in listOf(328f, 300f, 280f, 240f)) {
                for (hasHours in listOf(true, false)) {
                    val step = TimerLadder.pickSp(fontScale, available, hasHours)
                    val used = TimerLadder.widthDp(step, fontScale, hasHours)
                    // Either it fits, or the smallest step was chosen because nothing fits.
                    assertThat(used <= available || step == TimerLadder.stepsSp.last()).isTrue()
                }
            }
        }
    }

    @Test
    fun the_short_clock_form_matches_the_published_geometry() {
        // 设计系统 section 3.5: `MM:SS` at 52sp is at most 262.7dp even at fontScale 2.0, so it can stay
        // at the largest step instead of stepping down.
        assertThat(TimerLadder.widthDp(52, fontScale = 2.0f, hasHours = false)).isWithin(1f).of(262.7f)
        // And the long form at the same step is the documented 408.6dp.
        assertThat(TimerLadder.widthDp(52, fontScale = 2.0f, hasHours = true)).isWithin(1f).of(408.6f)
    }

    @Test
    fun timer_ladder_wraps_only_beyond_the_largest_supported_scale() {
        assertThat(TimerLadder.wrapsAt(2.0f)).isFalse()
        assertThat(TimerLadder.wrapsAt(2.01f)).isTrue()
    }

    // ---- Calendar arithmetic (the UI's only civil-date helper) --------------------------------------

    @Test
    fun month_grid_starts_on_the_correct_weekday_column() {
        // 2026-10-01 is a Thursday, so four leading blanks in a Monday-anchored row.
        assertThat(CalendarMath.firstOfMonth(2026, 10).dayOfWeek.value).isEqualTo(4)
        assertThat(CalendarMath.mondayColumn("2026-10-01")).isEqualTo(3)
        assertThat(CalendarMath.mondayColumn("2026-10-05")).isEqualTo(0)
        assertThat(CalendarMath.mondayColumn("2026-10-11")).isEqualTo(6)
    }

    @Test
    fun month_dates_cover_exactly_the_civil_month_including_leap_february() {
        assertThat(CalendarMath.monthDates(2026, 10)).hasSize(31)
        assertThat(CalendarMath.monthDates(2026, 2)).hasSize(28)
        assertThat(CalendarMath.monthDates(2024, 2)).hasSize(29)
        assertThat(CalendarMath.monthDates(2026, 10).first()).isEqualTo("2026-10-01")
        assertThat(CalendarMath.monthDates(2026, 10).last()).isEqualTo("2026-10-31")
    }

    @Test
    fun month_stepping_crosses_the_year_boundary_in_both_directions() {
        assertThat(CalendarMath.plusMonths(2026, 12, 1)).isEqualTo(2027 to 1)
        assertThat(CalendarMath.plusMonths(2026, 1, -1)).isEqualTo(2025 to 12)
        assertThat(CalendarMath.plusMonths(2026, 6, -6)).isEqualTo(2025 to 12)
        assertThat(CalendarMath.plusMonths(2026, 6, 6)).isEqualTo(2026 to 12)
    }

    @Test
    fun year_grid_is_monday_aligned_and_covers_the_whole_year() {
        for (year in listOf(2024, 2025, 2026, 2028)) {
            val weeks = CalendarMath.yearGrid(year)
            val flat = weeks.flatten()
            // Every week has seven days and every week starts on a Monday.
            assertThat(weeks.all { it.size == 7 }).isTrue()
            assertThat(weeks.all { CalendarMath.mondayColumn(it.first()) == 0 }).isTrue()
            // The grid contains the whole year.
            assertThat(flat).contains("$year-01-01")
            assertThat(flat).contains("$year-12-31")
            // And no date appears twice.
            assertThat(flat.toSet()).hasSize(flat.size)
            // A GitHub-style year is 53 weeks; a leap year that starts late can still only need 53.
            assertThat(weeks.size).isAtLeast(53)
            assertThat(weeks.size).isAtMost(53)
        }
    }

    @Test
    fun year_grid_pads_outside_the_year_with_neighbouring_dates() {
        val weeks = CalendarMath.yearGrid(2026)
        // 2026-01-01 is a Thursday, so the first cell is the Monday before it, 2025-12-29.
        assertThat(weeks.first().first()).isEqualTo("2025-12-29")
        // 2026-12-31 is a Thursday, so the last cell is the Sunday after it, 2027-01-03.
        assertThat(weeks.last().last()).isEqualTo("2027-01-03")
    }

    @Test
    fun days_between_counts_civil_days_and_spans_month_ends() {
        assertThat(CalendarMath.daysBetween("2026-10-01", "2026-10-05")).isEqualTo(4)
        assertThat(CalendarMath.daysBetween("2026-10-05", "2026-10-01")).isEqualTo(-4)
        // A month boundary and a leap day, so the helper is not merely subtracting day numbers.
        assertThat(CalendarMath.daysBetween("2026-01-31", "2026-02-01")).isEqualTo(1)
        assertThat(CalendarMath.daysBetween("2024-02-28", "2024-03-01")).isEqualTo(2)
    }

    // ---- Cover selection (设计系统 section 7.3) ------------------------------------------------------

    @Test
    fun the_cover_is_fixed_per_day_and_cycles_through_the_four_authored_variants() {
        val covers = (1..4).map { day ->
            app.arttodo.ui.common.ArtAsset.coverFor("2026-01-%02d".format(day))
        }
        assertThat(covers.toSet()).hasSize(4)
        // The same date always maps to the same cover, so returning to the app does not reshuffle it.
        assertThat(app.arttodo.ui.common.ArtAsset.coverFor("2026-10-05"))
            .isEqualTo(app.arttodo.ui.common.ArtAsset.coverFor("2026-10-05"))
        // A neighbouring day maps to a different cover.
        assertThat(app.arttodo.ui.common.ArtAsset.coverFor("2026-10-05"))
            .isNotEqualTo(app.arttodo.ui.common.ArtAsset.coverFor("2026-10-06"))
    }

    // ---- Version block -------------------------------------------------------------------------------

    @Test
    fun version_renders_name_and_code() {
        assertThat(Format.version("0.1.0", 1)).isEqualTo("0.1.0 (1)")
    }

    @Test
    fun zone_epoch_timestamp_is_only_shown_for_a_row_from_a_different_epoch() {
        assertThat(Format.zonedTimestamp(0, "Asia/Shanghai", currentEpoch = 2, rowEpoch = 2)).isNull()
        assertThat(Format.zonedTimestamp(1_772_000_000_000, "Asia/Shanghai", 2, 1))
            .contains("Asia/Shanghai")
    }
}
