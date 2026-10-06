package app.arttodo.ui.theme

import android.graphics.Bitmap
import android.os.PowerManager
import android.provider.Settings
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity



val LocalStudioColors = staticCompositionLocalOf { StudioColors.Day }
val LocalStudioMotion = staticCompositionLocalOf { StudioMotion(reduced = false) }
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalPowerSave = staticCompositionLocalOf { false }

/** Everything a screen needs that Material's own slots do not carry. */
@Immutable
object Studio {
    val colors: StudioColors
        @Composable get() = LocalStudioColors.current

    val motion: StudioMotion
        @Composable get() = LocalStudioMotion.current

    val text: StudioTextStyles
        @Composable get() = LocalStudioTextStyles.current

    val tokens: StudioTokens
        @Composable get() = StudioTokens(LocalStudioColors.current, LocalStudioMotion.current)
}

/** A single object to pass tokens into non-composable helpers such as a Canvas `drawWithCache`. */
@Immutable
data class StudioTokens(
    val colors: StudioColors,
    val motion: StudioMotion,
)

/**
 * The one theme entry point (设计系统 section 10.1).
 *
 * No Material default palette, no dynamic colour: either would replace the values the contrast gate
 * measures. Elevation overlay is switched off for the same reason — M3 would otherwise tint `surface`
 * with `primary` and move the measured ratios.
 */
@Composable
fun StudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = rememberReducedMotion(),
    powerSave: Boolean = rememberPowerSave(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) StudioColors.Night else StudioColors.Day
    val motion = StudioMotion(reduced = reducedMotion)
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val textStyles = remember(fontScale) { studioTextStyles(fontScale) }

    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioMotion provides motion,
        LocalReducedMotion provides reducedMotion,
        LocalPowerSave provides powerSave,
        LocalStudioTextStyles provides textStyles,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = textStyles.toTypography(),
            shapes = StudioShapes,
            content = content,
        )
    }
}

/** Paper grain: a deterministic 64x64 tiling texture generated once at startup, kept in memory. */
@Composable
fun rememberPaperGrain(): Bitmap = remember {
    val size = 64
    val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    var seed = 0x5EED1234
    fun next(): Int {
        seed = seed * 1_664_525 + 1_013_904_223
        return (seed ushr 8) and 0xFF
    }
    for (y in 0 until size) {
        for (x in 0 until size) {
            val v = next()
            // alpha stays inside the 0.03 (light) / 0.05 (night) band the design fixes.
            val alpha = if (v and 1 == 0) 0x08 else 0x0D
            bitmap[x, y] = (alpha shl 24) or 0x00192C3B
        }
    }
    bitmap
}

/**
 * Reads the system's animation scales.
 *
 * `ANIMATOR_DURATION_SCALE == 0` (or either transition scale) means the user asked for reduced
 * motion. The Compose-side `MotionDurationScale` is the documented alternative; the design fixes
 * this system-settings read, and 动效与无障碍说明 section 4.1 records that neither path has been
 * checked on a device.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        fun scale(key: String) = Settings.Global.getFloat(context.contentResolver, key, 1f)
        scale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
            scale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f ||
            scale(Settings.Global.WINDOW_ANIMATION_SCALE) == 0f
    }
}

/** Power-save mode drops the paper grain and the looping animations (动效与无障碍说明 section 4.4). */
@Composable
fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }
}

/** The six shape steps; pages never write a bare `RoundedCornerShape`. */
val StudioShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/**
 * Maps the studio palette onto Material slots (设计系统 section 10.2).
 *
 * `surfaceTint` is transparent and `surface`/`background` are separate: M3's tonal overlay would
 * change the measured contrast of everything drawn on a surface.
 */
fun StudioColors.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accentFill,
        onPrimary = onAccent,
        primaryContainer = accentTint,
        onPrimaryContainer = accent,
        secondary = running,
        onSecondary = canvas,
        tertiary = accentDeep,
        onTertiary = onAccent,
        background = canvas,
        onBackground = ink,
        surface = surfaceCard,
        onSurface = ink,
        surfaceVariant = surfaceTint,
        onSurfaceVariant = inkSecondary,
        surfaceContainer = surfaceCard,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        surfaceContainerLow = canvas,
        outline = lineHairline,
        outlineVariant = lineControl,
        error = dangerInk,
        onError = canvas,
        errorContainer = dangerTint,
        onErrorContainer = dangerInk,
        scrim = scrim,
        surfaceTint = androidx.compose.ui.graphics.Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = canvas,
    )
} else {
    lightColorScheme(
        primary = accentFill,
        onPrimary = onAccent,
        primaryContainer = accentTint,
        onPrimaryContainer = accentDeep,
        secondary = running,
        onSecondary = onAccent,
        tertiary = accentDeep,
        onTertiary = onAccent,
        background = canvas,
        onBackground = ink,
        surface = surfaceCard,
        onSurface = ink,
        surfaceVariant = surfaceTint,
        onSurfaceVariant = inkSecondary,
        surfaceContainer = surfaceCard,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        surfaceContainerLow = canvas,
        outline = lineHairline,
        outlineVariant = lineControl,
        error = dangerInk,
        onError = accentFill.let { androidx.compose.ui.graphics.Color.White },
        errorContainer = dangerTint,
        onErrorContainer = dangerInk,
        scrim = scrim,
        surfaceTint = androidx.compose.ui.graphics.Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = canvas,
    )
}

/** Studio names are kept, but Material still needs its slots filled; the mapping is 1:1. */
@Suppress("LongParameterList")
private fun StudioTextStyles.toTypography(): Typography =
    Typography(
        displayLarge = displayXL,
        displayMedium = displayL,
        displaySmall = displayM,
        headlineLarge = headlineL,
        headlineMedium = headlineM,
        headlineSmall = headlineS,
        titleLarge = titleL,
        titleMedium = titleM,
        bodyLarge = bodyL,
        bodyMedium = bodyM,
        labelLarge = labelL,
        labelMedium = labelM,
        labelSmall = overline,
    )
