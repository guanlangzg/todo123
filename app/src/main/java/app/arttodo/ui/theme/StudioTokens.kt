package app.arttodo.ui.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The design-system palette. Values are copied verbatim from docs/设计/设计系统.md section 2.1 / 2.2 /
 * 2.5 / 2.6; nothing here is invented and nothing is derived by hand.
 *
 * The contrast gate (scripts/test/verify_design_tokens.py) checks these exact hex strings, so a value
 * edited here without editing the gate would silently break the palette promise.
 */
@Immutable
data class StudioColors(
    val canvas: Color,
    val surfaceCard: Color,
    val surfaceTint: Color,
    val surfaceRaised: Color,
    val lineHairline: Color,
    val lineControl: Color,
    val iconLine: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    /** Solid accent button fill. Equals [accent] in the day theme, a deeper tone at night. */
    val accentFill: Color,
    val accentDeep: Color,
    val accentTint: Color,
    val onAccent: Color,
    val running: Color,
    val doneInk: Color,
    val doneTint: Color,
    val warnInk: Color,
    val warnTint: Color,
    val dangerInk: Color,
    val dangerTint: Color,
    /** Outline of a "no records" heat cell; the dash carries the meaning, not the colour alone. */
    val heatDash: Color,
    /** Zero-value cell, then the four investment steps (1-29 / 30-59 / 60-119 / >=120 min). */
    val heat: List<Color>,
    val scrim: Color,
    /** Six chart series colours; every series also carries a shape or line-style difference. */
    val series: List<Color>,
    val isDark: Boolean,
) {
    companion object {
        /** 画廊白昼 — the default, light-first theme. */
        val Day = StudioColors(
            canvas = Color(0xFFF5F0E8),
            surfaceCard = Color(0xFFFFFFFF),
            surfaceTint = Color(0xFFEFE8DB),
            surfaceRaised = Color(0xFFFBF8F2),
            lineHairline = Color(0xFFDDD3C2),
            lineControl = Color(0xFF7F8A92),
            iconLine = Color(0xFF7F8A92),
            ink = Color(0xFF192C3B),
            inkSecondary = Color(0xFF596771),
            inkTertiary = Color(0xFF5F6E78),
            accent = Color(0xFFB64931),
            accentFill = Color(0xFFB64931),
            accentDeep = Color(0xFF953C28),
            accentTint = Color(0xFFFBEFEA),
            onAccent = Color(0xFFFFFFFF),
            running = Color(0xFF1F6B63),
            doneInk = Color(0xFF2E6B4F),
            doneTint = Color(0xFFE7EFE6),
            warnInk = Color(0xFF7A5410),
            warnTint = Color(0xFFF7E9C9),
            dangerInk = Color(0xFFA3241C),
            dangerTint = Color(0xFFF7DFDA),
            heatDash = Color(0xFF8C7C63),
            heat = listOf(
                Color(0xFFE6DED0),
                Color(0xFFA2C8C2),
                Color(0xFF5E9993),
                Color(0xFF256560),
                Color(0xFF093E3B),
            ),
            scrim = Color(0xFF192C3B),
            series = listOf(
                Color(0xFF2F5D8C),
                Color(0xFF1F6B63),
                Color(0xFFB64931),
                Color(0xFF7A5410),
                Color(0xFF5F6E78),
                Color(0xFF6B4A7A),
            ),
            isDark = false,
        )

        /** 工作室夜幕 — a dark theme designed separately, not an inversion of [Day]. */
        val Night = StudioColors(
            canvas = Color(0xFF0F1720),
            surfaceCard = Color(0xFF1B2733),
            surfaceTint = Color(0xFF16202B),
            surfaceRaised = Color(0xFF22303D),
            lineHairline = Color(0xFF3B4A57),
            lineControl = Color(0xFF6E7C89),
            iconLine = Color(0xFF6E7C89),
            ink = Color(0xFFF2EDE4),
            inkSecondary = Color(0xFFB9C4CC),
            inkTertiary = Color(0xFF9AA7B2),
            accent = Color(0xFFE0705A),
            accentFill = Color(0xFFC0553C),
            accentDeep = Color(0xFFE0705A),
            accentTint = Color(0xFF3A2018),
            onAccent = Color(0xFFFFFFFF),
            running = Color(0xFF7FC4BA),
            doneInk = Color(0xFF8ACBA5),
            doneTint = Color(0xFF15271E),
            warnInk = Color(0xFFE0BC72),
            warnTint = Color(0xFF2B2415),
            dangerInk = Color(0xFFF59A90),
            dangerTint = Color(0xFF301A18),
            heatDash = Color(0xFF8A9AA4),
            heat = listOf(
                Color(0xFF1B3F39),
                Color(0xFF286056),
                Color(0xFF387F74),
                Color(0xFF5AA398),
                Color(0xFF97CEC2),
            ),
            scrim = Color(0xFF000000),
            series = listOf(
                Color(0xFF8FB4DC),
                Color(0xFF7FC4BA),
                Color(0xFFE0705A),
                Color(0xFFD8B267),
                Color(0xFFB9C4CC),
                Color(0xFFBFA0D0),
            ),
            isDark = true,
        )
    }
}

/**
 * The 8dp rhythm plus the allowed 4dp half-step (设计系统 section 4.1).
 *
 * There is deliberately no 6/10/14/18dp here: `Arrangement.spacedBy` and explicit spacers both read
 * from this object, so a stray number cannot slip into a screen.
 */
@Immutable
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val section: Dp = 32.dp
    val big: Dp = 40.dp
    val huge: Dp = 56.dp
    val giant: Dp = 72.dp

    /** Screen side margin; 24dp once the window is wide (设计系统 section 4.2). */
    val pageMargin: Dp = 16.dp

    /** The one true minimum touch target (规格 section 7, 设计系统 section 9). */
    val minTouch: Dp = 48.dp

    /** Content max width on tablets/foldables; screens centre inside it. */
    val maxContentWidth: Dp = 640.dp
}

/** Corner ladder, 设计系统 section 4.3. */
@Immutable
object Radius {
    /** No rounding: a full-bleed surface such as the focus screen's background wash. */
    val none: Dp = 0.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp

    /** The controlled "paper sticker" exception on an illustration card's illustration corner. */
    val sticker: Dp = 3.dp
}

/**
 * Motion tokens (设计系统 section 8). These are starting proposals that real-device perception tests
 * may revise; no screen hard-codes a duration or curve.
 */
@Immutable
data class StudioMotion(
    val reduced: Boolean,
) {
    /** Press feedback, ripples, the tick. */
    val tapMs: Int = 120
    val stateFastMs: Int = 180
    val stateMediumMs: Int = 240
    val pageMs: Int = 300
    /** The only looping animation in the app: the running timer's breathing dot. */
    val timerPulseMs: Int = 1200

    /** Returns 0 when the system asks for reduced motion, which every caller must handle. */
    fun duration(baseMs: Int): Int = if (reduced) 0 else baseMs

    fun <T> tweenOf(baseMs: Int, easing: Easing = FastOutSlowInEasing) = tween<T>(
        durationMillis = duration(baseMs),
        easing = easing,
    )

    val tapEasing: Easing = LinearOutSlowInEasing
    val pageInEasing: Easing = FastOutLinearInEasing
    val pageOutEasing: Easing = LinearOutSlowInEasing
    val pulseEasing: Easing = EaseInOut

    val stretchSpec = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow / 5f,
    )
}

/** Illustration frame sizes. These equal the values asserted by the scrim JSON contract. */
@Immutable
object ArtFrame {
    val coverWidth: Dp = 328.dp
    val coverHeight: Dp = 200.dp
    val panelWidth: Dp = 328.dp
    val panelHeight: Dp = 160.dp
    val card: Dp = 72.dp

    /** Today-page cover gate (关键页面说明 section 1.4): the cover narrows as the font grows. */
    fun coverHeightFor(fontScale: Float): Dp = when {
        fontScale >= 2.0f -> 0.dp
        fontScale >= 1.6f -> 112.dp
        fontScale >= 1.3f -> 152.dp
        else -> coverHeight
    }

    /** Cover summary moves off the artwork from gate 3 on and disappears entirely at gate 4. */
    fun coverSummaryOnArt(fontScale: Float): Boolean = fontScale < 1.6f
}

/**
 * Scrim stops from the authored scrim JSON (assets/art/scrim 的 cover 与 panel 两种帧).
 *
 * Each entry is offset -> alpha; the mask colour is the theme scrim so a night theme uses black.
 */
@Immutable
object ScrimStops {
    val cover: List<Pair<Float, Float>> =
        listOf(0.0f to 0f, 0.18f to 0f, 0.38f to 0.52f, 0.54f to 0.72f, 0.66f to 0.84f, 1.0f to 0.86f)
    val panel: List<Pair<Float, Float>> =
        listOf(0.0f to 0f, 0.20f to 0f, 0.40f to 0.86f, 1.0f to 0.86f)

    /** Night keeps a black overlay at the same stops; the authored night values are 0.72 / 0.82. */
    val nightCover: List<Pair<Float, Float>> =
        listOf(0.0f to 0f, 0.18f to 0f, 0.38f to 0.62f, 0.54f to 0.72f, 0.66f to 0.78f, 1.0f to 0.82f)
    val nightPanel: List<Pair<Float, Float>> =
        listOf(0.0f to 0f, 0.20f to 0f, 0.40f to 0.72f, 1.0f to 0.82f)
}
