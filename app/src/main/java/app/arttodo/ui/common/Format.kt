package app.arttodo.ui.common

import androidx.compose.ui.text.TextStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Presentation-only formatting: turning numbers the domain produced into the strings the design
 * shows. No date is computed here — every ISO label arrives from the domain core or from Room.
 *
 * The one civil-calendar arithmetic in the app lives in [CalendarMath] below and is documented there.
 */
object Format {

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val weekdayShort = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val weekdayLong = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** `2026 年 10 月 5 日 周一` — the date label from 设计系统 section 3.3 rule 4. */
    fun dateLabel(iso: String, long: Boolean = false): String {
        val date = parseIso(iso) ?: return iso
        val names = if (long) weekdayLong else weekdayShort
        return "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日 ${names[date.dayOfWeek.value - 1]}"
    }

    /** TalkBack reads the full weekday: `2026 年 10 月 5 日 星期一`. */
    fun spokenDate(iso: String): String = dateLabel(iso, long = true)

    /** `2026 年 10 月` — the calendar month switch label. */
    fun monthLabel(year: Int, month: Int): String = "$year 年 $month 月"

    /** `01:24:36` / `24:36`; the timer never uses a space around the separators (section 3.3). */
    fun clock(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    /** `1:12:36` — the ledger header keeps the hour but drops the leading zero. */
    fun ledgerClock(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    /** `25 分钟` / `1 小时 30 分` / `2 小时 5 分` / `0 分钟`. */
    fun minutes(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }

    /** TalkBack duration: `1 小时 24 分 36 秒`. */
    fun spokenDuration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours 小时")
        if (minutes > 0) parts.add("$minutes 分")
        parts.add("$secs 秒")
        return parts.joinToString(" ")
    }

    /** `2026-03-04 23:50（Asia/Shanghai）` — used when a row predates a zone-epoch change. */
    fun zonedTimestamp(wallMs: Long, zoneId: String, currentEpoch: Int, rowEpoch: Int): String? {
        if (rowEpoch == currentEpoch) return null
        val stamp = java.time.Instant.ofEpochMilli(wallMs)
            .atZone(java.time.ZoneId.of(zoneId))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return "$stamp（$zoneId）"
    }

    fun isoOf(date: LocalDate): String = isoFormatter.format(date)

    fun parseIso(iso: String): LocalDate? = runCatching { LocalDate.parse(iso, isoFormatter) }.getOrNull()

    /** `0.1.0 (1)` from the build config's version name and code. */
    fun version(name: String, code: Int): String = "$name ($code)"
}

/**
 * Civil-calendar grid arithmetic.
 *
 * The domain core owns *bucketing* — which day an instant belongs to, day lengths, totals — and the
 * contract forbids the UI from doing any of that (架构契约 section 9). It exposes no export for "which
 * weekday is 2026-10-05" or "how many days does February have", which is what a calendar grid and a
 * 53-week heat map need in order to be laid out.
 *
 * So this object does exactly that and nothing else: ISO civil-date arithmetic on labels the domain
 * already produced. It never converts an instant to a date, never reads a time zone, and never sums
 * a duration. Keeping it in one place makes the boundary auditable.
 */
object CalendarMath {

    /** First day of the given month. */
    fun firstOfMonth(year: Int, month: Int): LocalDate = LocalDate.of(year, month, 1)

    /** Monday-anchored column index (0..6) of the given ISO label. */
    fun mondayColumn(iso: String): Int {
        val date = Format.parseIso(iso) ?: return 0
        return date.dayOfWeek.value - 1
    }

    /** All ISO labels of a month, ascending. */
    fun monthDates(year: Int, month: Int): List<String> {
        val first = firstOfMonth(year, month)
        return (0 until first.lengthOfMonth()).map { Format.isoOf(first.plusDays(it.toLong())) }
    }

    fun plusMonths(year: Int, month: Int, delta: Long): Pair<Int, Int> {
        val target = firstOfMonth(year, month).plusMonths(delta)
        return target.year to target.monthValue
    }

    fun plusDays(iso: String, days: Long): String? = Format.parseIso(iso)?.plusDays(days)?.let(Format::isoOf)

    /**
     * The full Monday-anchored grid of a year: 53 weeks x 7 days, i.e. every date from the Monday on
     * or before January 1 to the Sunday on or after December 31.
     */
    fun yearGrid(year: Int): List<List<String>> {
        val jan1 = LocalDate.of(year, 1, 1)
        val dec31 = LocalDate.of(year, 12, 31)
        var cursor = jan1.minusDays((jan1.dayOfWeek.value - 1).toLong())
        val last = dec31.plusDays((7 - dec31.dayOfWeek.value).toLong())
        val weeks = mutableListOf<List<String>>()
        while (!cursor.isAfter(last)) {
            weeks += (0 until 7).map { Format.isoOf(cursor.plusDays(it.toLong())) }
            cursor = cursor.plusDays(7)
        }
        return weeks
    }

    /** Days between two ISO labels (b - a). Used only for axis placement, never for totals. */
    fun daysBetween(a: String, b: String): Long {
        val from = Format.parseIso(a) ?: return 0
        val to = Format.parseIso(b) ?: return 0
        return java.time.temporal.ChronoUnit.DAYS.between(from, to)
    }
}

/**
 * Tabular digits.
 *
 * The design asks for equal-width digits so the seconds place does not shift the clock sideways every
 * tick (设计系统 section 3.1). The pinned Compose version exposes `fontFeatureSettings` as a CSS-style
 * feature string, so the numeral feature is requested by name.
 */
val MonoTabular: TextStyle = TextStyle(fontFeatureSettings = "tnum")
