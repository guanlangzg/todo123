package app.arttodo.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.arttodo.R
import app.arttodo.ui.theme.ArtFrame
import app.arttodo.ui.theme.LocalPowerSave
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.ScrimStops
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space
import app.arttodo.ui.theme.rememberPaperGrain

/**
 * The illustrated asset set delivered under the assets/art tree (设计系统 section 7.3).
 *
 * The rendered PNGs are used rather than the SVGs: four covers contain `radialGradient`, and
 * 设计系统 section 13 records that the SVG -> VectorDrawable conversion was never done or verified.
 * The PNGs are the authored 2x rasters, so no scaling guesswork is involved.
 */
enum class ArtAsset(val assetName: String) {
    CoverMorning("cover_01_morning"),
    CoverDesk("cover_02_desk"),
    CoverWindow("cover_03_window"),
    CoverLamp("cover_04_lamp"),
    TaskTemporary("task_temporary"),
    TaskDaily("task_daily"),
    StateCompleted("state_completed"),
    StateFocusing("state_focusing"),
    StatePaused("state_paused"),
    ChartHeatmap("chart_heatmap"),
    EmptyToday("empty_today"),
    EmptyRecords("empty_records"),
    EmptyInsights("empty_insights"),
    RecoveryAbnormal("recovery_abnormal"),
    PlaceholderMissing("placeholder_missing");

    /**
     * Today's cover, chosen by the ISO day number mod 4 and fixed for the whole day (section 7.3).
     * The day number comes from the domain's date label; nothing is randomised.
     */
    companion object {
        fun coverFor(appDateIso: String): ArtAsset {
            val day = Format.parseIso(appDateIso)?.dayOfYear ?: 1
            return when (day % 4) {
                0 -> CoverMorning
                1 -> CoverDesk
                2 -> CoverWindow
                else -> CoverLamp
            }
        }

        fun forTask(isDaily: Boolean, completed: Boolean): ArtAsset = when {
            completed -> StateCompleted
            isDaily -> TaskDaily
            else -> TaskTemporary
        }

        fun cardOf(asset: ArtAsset): Int = when (asset) {
            CoverMorning -> R.drawable.art_cover_01_morning
            CoverDesk -> R.drawable.art_cover_02_desk
            CoverWindow -> R.drawable.art_cover_03_window
            CoverLamp -> R.drawable.art_cover_04_lamp
            TaskTemporary -> R.drawable.art_task_temporary
            TaskDaily -> R.drawable.art_task_daily
            StateCompleted -> R.drawable.art_state_completed
            StateFocusing -> R.drawable.art_state_focusing
            StatePaused -> R.drawable.art_state_paused
            ChartHeatmap -> R.drawable.art_chart_heatmap
            EmptyToday -> R.drawable.art_empty_today
            EmptyRecords -> R.drawable.art_empty_records
            EmptyInsights -> R.drawable.art_empty_insights
            RecoveryAbnormal -> R.drawable.art_recovery_abnormal
            PlaceholderMissing -> R.drawable.art_placeholder_missing
        }
    }
}

/**
 * The frame contracts. Container sizes must equal the authored canvas (设计系统 7.2).
 *
 * [Fill] is the fourth case: the caller owns the box, which is how the focus screen's full-bleed wash
 * is drawn (设计系统 7.4). It declares no size of its own.
 */
enum class ArtFrameKind { Cover, Panel, Card, Fill }

/** Decodes one art resource once per process; a decode failure (not a missing resource) degrades. */
private object ArtCache {
    private val cache = mutableMapOf<Int, ImageBitmap?>()

    fun load(resId: Int, context: android.content.Context): ImageBitmap? = synchronized(cache) {
        if (cache.containsKey(resId)) return cache[resId]
        val bitmap: Bitmap? = runCatching {
            BitmapFactory.decodeResource(context.resources, resId)
        }.getOrNull()
        val image = bitmap?.asImageBitmap()
        cache[resId] = image
        image
    }
}

/**
 * Renders one illustration with the three-level degradation of 设计系统 section 7.6.
 *
 * The container is a fixed size in every level, so a failure never re-flows the page and never blanks
 * the screen: level 1 paints `surfaceTint` + paper grain, level 2 paints `placeholder_missing`, and
 * level 3 adds the "插图暂不可用" caption. Title, note, completion control and start button keep
 * their positions and stay operable in all three.
 *
 * [frameSize] overrides the frame's default box for the one place where the design resizes a contract
 * frame: the today cover narrows with the font scale (关键页面说明 section 1.4). Without it the authored
 * 200dp canvas would be drawn inside a 112-152dp box and cropped rather than fitted.
 *
 * [showDegradation] is false only for a decorative background, where the design asks for plain canvas
 * rather than a placeholder that interrupts a running session (关键页面说明 section 2.5).
 */
@Composable
fun StudioArt(
    asset: ArtAsset,
    frame: ArtFrameKind,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Radius.xl,
    scrimStops: List<Pair<Float, Float>> = emptyList(),
    contentDescription: String? = null,
    frameSize: DpSize? = null,
    showDegradation: Boolean = true,
) {
    val context = LocalContext.current
    val image = remember(asset) { ArtCache.load(ArtAsset.cardOf(asset), context) }
    val shape = RoundedCornerShape(cornerRadius)
    val base = when {
        frameSize != null -> modifier.size(frameSize)
        frame == ArtFrameKind.Fill -> modifier
        frame == ArtFrameKind.Cover -> modifier.width(ArtFrame.coverWidth).height(ArtFrame.coverHeight)
        frame == ArtFrameKind.Panel -> modifier.width(ArtFrame.panelWidth).height(ArtFrame.panelHeight)
        else -> modifier.size(ArtFrame.card)
    }

    when {
        image != null -> Box(base.clip(shape)) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
            )
            if (scrimStops.isNotEmpty()) {
                ScrimOverlay(scrimStops, Modifier.fillMaxSize())
            }
        }

        // Decoration with nothing left to draw: the page keeps its layout and its colours.
        !showDegradation -> Box(base.clearAndSetSemantics { })

        else -> Column(base, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            // An overridden frame is fixed: the caption takes its line and the
                            // illustration keeps the rest, so nothing is clipped or overlapped.
                            frameSize != null -> Modifier.weight(1f)
                            frame == ArtFrameKind.Card || frame == ArtFrameKind.Fill -> Modifier.fillMaxSize()
                            frame == ArtFrameKind.Cover -> Modifier.aspectRatio(328f / 200f)
                            else -> Modifier.aspectRatio(328f / 160f)
                        },
                    )
                    .clip(shape)
                    .background(Studio.colors.surfaceTint)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                val fallback = remember { ArtCache.load(ArtAsset.cardOf(ArtAsset.PlaceholderMissing), context) }
                if (fallback != null) {
                    Image(
                        bitmap = fallback,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
                    )
                } else {
                    // Last resort: the missing-image glyph, so the slot is never blank.
                    StudioIconView(
                        icon = StudioIcon.ImageBroken,
                        size = 40.dp,
                        tint = Studio.colors.inkTertiary,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            if (frame != ArtFrameKind.Card) {
                Text(
                    text = "插图暂不可用",
                    style = Studio.text.labelM,
                    color = Studio.colors.inkTertiary,
                )
            }
        }
    }
}

/** Linear scrim built from the authored stop list; night uses the black overlay stops. */
@Composable
fun ScrimOverlay(stops: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    val color = Studio.colors.scrim
    val night = Studio.colors.isDark
    val effective = if (night && stops === ScrimStops.cover) ScrimStops.nightCover
    else if (night && stops === ScrimStops.panel) ScrimStops.nightPanel else stops
    Canvas(modifier.clearAndSetSemantics { }) {
        drawScrim(effective, color)
    }
}

internal fun DrawScope.drawScrim(stops: List<Pair<Float, Float>>, color: Color) {
    if (stops.isEmpty()) return
    val positions = stops.map { it.first }
    val alphas = stops.map { it.second }
    val brush = Brush.verticalGradient(
        colorStops = stops.mapIndexed { index, _ -> positions[index] to color.copy(alpha = alphas[index]) }
            .toTypedArray(),
    )
    drawRect(brush)
}

/** Faint paper grain: the deterministic 64x64 tile, repeated over the page background. */
@Composable
fun PaperBackground(modifier: Modifier = Modifier) {
    // Power-save drops the full-screen texture composite (动效与无障碍说明 section 4.4).
    if (LocalPowerSave.current) return
    val grain = rememberPaperGrain()
    val image = remember(grain) { grain.asImageBitmap() }
    val brush = remember(image) { ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)) }
    Box(modifier.background(brush).clearAndSetSemantics { })
}
