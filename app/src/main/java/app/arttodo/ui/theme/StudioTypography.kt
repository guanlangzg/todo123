package app.arttodo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The 13-step type scale (设计系统 section 3.2). Slot names match the token names on purpose: Material's
 * `displayLarge`-style names would hide the fact that the sizes differ from Material's defaults.
 *
 * No `fontFamily` is set anywhere: the design resolves CJK through `FontFamily.Default` (the device
 * system family) and Latin/digits through the platform Roboto, so no font file is bundled or
 * downloaded.
 */
@Immutable
data class StudioTextStyles(
    val displayXL: TextStyle,
    val displayL: TextStyle,
    val displayM: TextStyle,
    val headlineL: TextStyle,
    val headlineM: TextStyle,
    val headlineS: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val labelL: TextStyle,
    val labelM: TextStyle,
    val overline: TextStyle,
)

private fun style(sp: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontSize = sp.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

@Suppress("LongParameterList")
private fun TextStyle.capped(maxPx: Float, fontScale: Float): TextStyle {
    val limit: TextUnit = (maxPx / fontScale).sp
    return if (fontSize > limit) copy(fontSize = limit) else this
}

/**
 * Builds the scale for the current system font scale.
 *
 * Decorative typography is capped (设计系统 section 3.6) so a 28sp date block cannot push the task list
 * off the first screen; the caps are on structure only — user content (task titles at 16sp, notes at
 * 16sp) scales without limit.
 */
fun studioTextStyles(fontScale: Float): StudioTextStyles = StudioTextStyles(
    displayXL = style(40, 48, FontWeight.Bold).capped(56f, fontScale),
    displayL = style(34, 42, FontWeight.Bold),
    displayM = style(28, 36, FontWeight.Bold).capped(44f, fontScale),
    headlineL = style(26, 34, FontWeight.Bold).capped(40f, fontScale),
    headlineM = style(22, 30, FontWeight.Bold).capped(36f, fontScale),
    headlineS = style(20, 28, FontWeight.SemiBold),
    titleL = style(18, 26, FontWeight.SemiBold),
    titleM = style(16, 24, FontWeight.SemiBold),
    bodyL = style(16, 26, FontWeight.Normal),
    bodyM = style(14, 22, FontWeight.Normal),
    labelL = style(14, 20, FontWeight.SemiBold),
    labelM = style(12, 18, FontWeight.SemiBold),
    overline = style(11, 16, FontWeight.SemiBold),
)

val LocalStudioTextStyles = staticCompositionLocalOf { studioTextStyles(1.0f) }

/**
 * Timer-digit ladder (设计系统 section 3.5): prefer large, step down only when the chosen step would
 * overflow the available width. Steps are discrete so the digits never jitter between sizes while the
 * clock ticks; the step is recomputed when the font scale or the width changes, never per second.
 *
 * Every stored width is the **font-scale-1.0** width in dp; the design table's numbers (204.3dp at
 * 52sp, 408.6dp at fontScale 2.0) are that base multiplied by the scale, which is why the base is what
 * lives here and why `pickSp` multiplies before comparing.
 *
 * The short `MM:SS` form is derived from the long form with the same glyph metrics the design table was
 * computed from — CJK-free digit runs measure 0.5615 em per digit and 0.280 em per colon, so
 * `MM:SS` is `(4 × 0.5615 + 0.280) / (6 × 0.5615 + 2 × 0.280) = 0.6429` of `HH:MM:SS`. Deriving it
 * keeps the two forms from drifting apart if a step is ever added.
 */
object TimerLadder {
    val stepsSp: List<Int> = listOf(52, 44, 40, 36, 32)

    /** `HH:MM:SS` width in dp at fontScale 1.0, per the geometry table in the design system. */
    private val longWidthDpPerSp: Map<Int, Float> = mapOf(
        52 to 204.3f,
        44 to 172.9f,
        40 to 157.2f,
        36 to 141.4f,
        32 to 125.7f,
    )

    /** `MM:SS` width at fontScale 1.0: 0.6429 of the long form (see the class comment). */
    private const val SHORT_LONG_RATIO = 0.6429f

    /** The width a rendering occupies at the given scale, or [Float.MAX_VALUE] for an unknown step. */
    fun widthDp(stepSp: Int, fontScale: Float, hasHours: Boolean): Float {
        val base = longWidthDpPerSp[stepSp] ?: return Float.MAX_VALUE
        val width = if (hasHours) base else base * SHORT_LONG_RATIO
        return width * fontScale
    }

    fun pickSp(
        fontScale: Float,
        availableWidthDp: Float,
        hasHours: Boolean,
    ): Int {
        for (step in stepsSp) {
            if (widthDp(step, fontScale, hasHours) <= availableWidthDp) return step
        }
        return stepsSp.last()
    }

    /** Above font scale 2.0 the digits wrap into two lines instead of scrolling sideways. */
    fun wrapsAt(fontScale: Float): Boolean = fontScale > 2.0f
}
