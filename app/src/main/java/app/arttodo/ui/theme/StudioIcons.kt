package app.arttodo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The product icon set, drawn as Compose paths (设计系统 section 6.1).
 *
 * Deliberately not Heroicons/Lucide/Phosphor and not Material's default icons used as-is: 设计系统
 * section 6.1 requires self-drawn 24x24 paths with 1.75dp round strokes, and section 6.2 fixes the
 * meaning of each one. Only the fill variants of `check` and the selected navigation icons exist.
 */
enum class StudioIcon {
    Today,
    Records,
    Insights,
    Settings,
    Check,
    Play,
    Pause,
    Stop,
    TimerForward,
    TimerCountdown,
    Add,
    Drag,
    ChevronUp,
    ChevronDown,
    Expand,
    Undo,
    Archive,
    Unarchive,
    Edit,
    Note,
    ImageBroken,
    Warning,
    Recovery,
    Zone,
    Export,
    Import,
    BackupPoint,
    Lock,
    Notification,
    More,
}

@Composable
fun StudioIconView(
    icon: StudioIcon,
    modifier: Modifier = Modifier,
    tint: Color = Studio.colors.iconLine,
    size: Dp = 24.dp,
    filled: Boolean = false,
) {
    // The tick's glyph colour depends on the tint it sits on, so it is resolved in the composition
    // rather than read inside the drawing pass.
    val checkContrast = Studio.colors.surfaceCard
    Canvas(modifier = modifier.size(size)) {
        // 24x24 grid, 20x20 content box, 2dp bleed on every side (设计系统 section 6.1).
        val unit = this.size.minDimension / 24f
        val stroke = Stroke(
            width = 1.75f * unit,
            cap = StrokeCap.Round,
        )
        drawIcon(icon, tint, unit, stroke, filled, checkContrast)
    }
}

/**
 * Draws one icon on a 24x24 grid scaled by [u] (one grid unit in pixels).
 *
 * Kept as one exhaustive `when` so adding an enum case is a compile error rather than a blank icon.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawIcon(
    icon: StudioIcon,
    tint: Color,
    u: Float,
    stroke: Stroke,
    filled: Boolean,
    checkContrast: Color,
) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    fun polyline(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
        }
        drawPath(path, tint, style = stroke)
    }

    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) {
        drawCircle(tint, radius = r * u, center = p(cx, cy), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    }

    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, fill: Boolean = false) {
        drawRoundRect(
            color = tint,
            topLeft = p(left, top),
            size = Size((right - left) * u, (bottom - top) * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * u),
            style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke,
        )
    }

    when (icon) {
        // A framed date with a marked "today" dot: the app's identity is a dated working notebook.
        StudioIcon.Today -> {
            roundRect(3.5f, 5f, 20.5f, 20.5f, 3f, filled)
            line(3.5f, 9.5f, 20.5f, 9.5f)
            line(8f, 3.5f, 8f, 6.5f)
            line(16f, 3.5f, 16f, 6.5f)
            if (!filled) circle(12f, 15f, 2.2f, fill = true)
        }
        // A stack of ledger pages with a bound edge.
        StudioIcon.Records -> {
            roundRect(4.5f, 3.5f, 19.5f, 20.5f, 3f, filled)
            line(8.5f, 9f, 15.5f, 9f)
            line(8.5f, 13f, 15.5f, 13f)
            if (filled) line(8.5f, 17f, 12.5f, 17f)
        }
        // A grid matrix: the heat map, drawn as a 3x3 of squares.
        StudioIcon.Insights -> {
            for (col in 0..2) {
                for (row in 0..2) {
                    val x = 4f + col * 6f
                    val y = 4f + row * 6f
                    if (filled && (col + row) % 2 == 0) {
                        roundRect(x, y, x + 4f, y + 4f, 1f, fill = true)
                    } else {
                        roundRect(x, y, x + 4f, y + 4f, 1f)
                    }
                }
            }
        }
        // Sliders, not a cog: quieter and closer to the "manual settings" language of the app.
        StudioIcon.Settings -> {
            line(4f, 8f, 20f, 8f)
            line(4f, 16f, 20f, 16f)
            circle(9f, 8f, 2.4f, fill = filled)
            circle(15f, 16f, 2.4f, fill = filled)
        }
        StudioIcon.Check -> {
            if (filled) {
                circle(12f, 12f, 9.5f, fill = true)
                val path = Path().apply {
                    moveTo(7.5f * u, 12.4f * u)
                    lineTo(10.6f * u, 15.6f * u)
                    lineTo(16.6f * u, 8.6f * u)
                }
                drawPath(path, checkContrast, style = Stroke(stroke.width * 1.1f, cap = StrokeCap.Round))
            } else {
                polyline(7.5f to 12.4f, 10.6f to 15.6f, 16.6f to 8.6f)
            }
        }
        StudioIcon.Play -> {
            val path = Path().apply {
                moveTo(9f * u, 7f * u)
                lineTo(18f * u, 12f * u)
                lineTo(9f * u, 17f * u)
                close()
            }
            if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
        }
        StudioIcon.Pause -> {
            roundRect(8.5f, 6.5f, 11f, 17.5f, 1.2f, fill = true)
            roundRect(13f, 6.5f, 15.5f, 17.5f, 1.2f, fill = true)
        }
        StudioIcon.Stop -> roundRect(6.5f, 6.5f, 17.5f, 17.5f, 2.5f, fill = filled)
        StudioIcon.TimerForward -> {
            circle(12f, 13f, 7.5f)
            line(12f, 13f, 12f, 8.5f)
            line(12f, 13f, 15.5f, 13f)
            line(10f, 3.2f, 14f, 3.2f)
        }
        StudioIcon.TimerCountdown -> {
            circle(12f, 13f, 7.5f)
            line(12f, 13f, 12f, 8f)
            line(12f, 13f, 16f, 15.5f)
            line(9.5f, 2.8f, 14.5f, 4.6f)
        }
        StudioIcon.Add -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        StudioIcon.Drag -> {
            for (row in 0..2) {
                for (col in 0..1) {
                    circle(10f + col * 4f, 8f + row * 4f, 1.1f, fill = true)
                }
            }
        }
        StudioIcon.ChevronUp -> polyline(7f to 14.5f, 12f to 9.5f, 17f to 14.5f)
        StudioIcon.ChevronDown -> polyline(7f to 9.5f, 12f to 14.5f, 17f to 9.5f)
        StudioIcon.Expand -> polyline(6.5f to 9.5f, 12f to 15f, 17.5f to 9.5f)
        StudioIcon.Undo -> {
            val path = Path().apply {
                moveTo(8.5f * u, 9f * u)
                lineTo(14f * u, 9f * u)
                cubicTo(18f * u, 9f * u, 18f * u, 16.5f * u, 14f * u, 16.5f * u)
                lineTo(9f * u, 16.5f * u)
            }
            drawPath(path, tint, style = stroke)
            polyline(11f to 6f, 8f to 9f, 11f to 12f)
        }
        StudioIcon.Archive -> {
            roundRect(3.5f, 4.5f, 20.5f, 9f, 1.5f)
            roundRect(5.5f, 9f, 18.5f, 20f, 2f, fill = filled)
            line(9.5f, 13.5f, 14.5f, 13.5f)
        }
        StudioIcon.Unarchive -> {
            roundRect(3.5f, 9f, 20.5f, 13.5f, 1.5f)
            roundRect(5.5f, 13.5f, 18.5f, 20f, 2f, fill = filled)
            polyline(9.5f to 8f, 12f to 5.5f, 14.5f to 8f)
        }
        StudioIcon.Edit -> {
            val path = Path().apply {
                moveTo(5f * u, 19f * u)
                lineTo(5.8f * u, 15.2f * u)
                lineTo(16f * u, 5f * u)
                lineTo(19.5f * u, 8.5f * u)
                lineTo(9.3f * u, 18.7f * u)
                close()
            }
            drawPath(path, tint, style = stroke)
        }
        StudioIcon.Note -> {
            roundRect(4.5f, 4f, 19.5f, 20f, 3f, fill = filled)
            line(8.5f, 9.5f, 15.5f, 9.5f)
            line(8.5f, 13.5f, 15.5f, 13.5f)
            line(8.5f, 17f, 12.5f, 17f)
        }
        StudioIcon.ImageBroken -> {
            roundRect(3.5f, 5f, 20.5f, 19f, 2.5f)
            line(8f, 11f, 16f, 19f)
            line(16f, 11f, 8f, 19f)
            circle(9f, 9.5f, 1.4f, fill = true)
        }
        StudioIcon.Warning -> {
            val path = Path().apply {
                moveTo(12f * u, 4f * u)
                lineTo(21f * u, 19.5f * u)
                lineTo(3f * u, 19.5f * u)
                close()
            }
            drawPath(path, tint, style = stroke)
            line(12f, 10f, 12f, 14.2f)
            circle(12f, 16.8f, 1f, fill = true)
        }
        StudioIcon.Recovery -> {
            val path = Path().apply {
                moveTo(4f * u, 14.5f * u)
                lineTo(9f * u, 9.5f * u)
                lineTo(13f * u, 13.5f * u)
                lineTo(20f * u, 6.5f * u)
            }
            drawPath(path, tint, style = stroke)
            val gap = PathEffect.dashPathEffect(floatArrayOf(1.6f * u, 1.6f * u))
            drawLine(
                tint,
                p(9f, 9.5f),
                p(9.6f, 10.1f),
                strokeWidth = stroke.width * 1.6f,
                cap = StrokeCap.Round,
                pathEffect = gap,
            )
        }
        StudioIcon.Zone -> {
            circle(12f, 12f, 8.5f)
            drawOval(
                tint,
                topLeft = p(8f, 3.5f),
                size = Size(8f * u, 17f * u),
                style = stroke,
            )
            line(3.5f, 12f, 20.5f, 12f)
        }
        StudioIcon.Export -> {
            roundRect(4.5f, 11f, 19.5f, 20f, 2.5f, fill = filled)
            line(12f, 4f, 12f, 14f)
            polyline(8.5f to 7.5f, 12f to 4f, 15.5f to 7.5f)
        }
        StudioIcon.Import -> {
            roundRect(4.5f, 11f, 19.5f, 20f, 2.5f, fill = filled)
            line(12f, 3.5f, 12f, 13.5f)
            polyline(8.5f to 10f, 12f to 13.5f, 15.5f to 10f)
        }
        StudioIcon.BackupPoint -> {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 3f, fill = true)
        }
        StudioIcon.Lock -> {
            roundRect(5f, 10.5f, 19f, 20.5f, 2.5f, fill = filled)
            val shackle = Path().apply {
                moveTo(8.5f * u, 10.5f * u)
                lineTo(8.5f * u, 8f * u)
                cubicTo(8.5f * u, 4.6f * u, 15.5f * u, 4.6f * u, 15.5f * u, 8f * u)
                lineTo(15.5f * u, 10.5f * u)
            }
            drawPath(shackle, tint, style = stroke)
        }
        StudioIcon.Notification -> {
            val path = Path().apply {
                moveTo(6f * u, 16.5f * u)
                lineTo(6f * u, 11f * u)
                cubicTo(6f * u, 6.6f * u, 18f * u, 6.6f * u, 18f * u, 11f * u)
                lineTo(18f * u, 16.5f * u)
                close()
            }
            drawPath(path, tint, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke)
            line(10f, 19.5f, 14f, 19.5f)
        }
        StudioIcon.More -> {
            circle(6f, 12f, 1.6f, fill = true)
            circle(12f, 12f, 1.6f, fill = true)
            circle(18f, 12f, 1.6f, fill = true)
        }
    }
}

/** The dashed outline that marks "no records" — the same cue on the calendar and the heat map. */
@Composable
fun DashedCellOutline(
    modifier: Modifier = Modifier,
    color: Color = Studio.colors.heatDash,
    dash: Float = 3f,
    gap: Float = 3f,
) {
    Canvas(modifier) {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
            style = Stroke(
                width = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap)),
            ),
        )
    }
}
