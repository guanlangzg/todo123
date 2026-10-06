package app.arttodo.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.arttodo.ui.theme.ArtFrame
import app.arttodo.ui.theme.DashedCellOutline
import app.arttodo.ui.theme.LocalPowerSave
import app.arttodo.ui.theme.LocalStudioMotion
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioColors
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space

/** The one solid accent button, 56dp tall (关键页面说明 section 7). */
@Composable
fun StudioPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    contentDescription: String? = null,
) {
    val colors = Studio.colors
    val background = if (enabled) colors.accentFill else colors.inkTertiary.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Radius.lg))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription?.let { this.contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Studio.text.labelL, color = colors.onAccent)
    }
}

/** Outlined secondary action; the label colour carries the meaning, the border the affordance. */
@Composable
fun StudioSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    color: Color = Studio.colors.ink,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, if (enabled) Studio.colors.lineControl else Studio.colors.lineHairline, RoundedCornerShape(Radius.lg))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Studio.text.labelL, color = if (enabled) color else Studio.colors.inkTertiary)
    }
}

/** A word-style action, always padded to a 48dp touch target. */
@Composable
fun StudioTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Studio.colors.accentDeep,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = Space.minTouch, minHeight = Space.minTouch)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m)
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Studio.text.labelL, color = if (enabled) color else Studio.colors.inkTertiary)
    }
}

/** An icon-only control whose visual size is small but whose hit area is 48dp. */
@Composable
fun StudioIconButton(
    icon: StudioIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Studio.colors.iconLine,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(Space.minTouch)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        StudioIconView(
            icon = icon,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            size = iconSize,
            filled = filled,
        )
    }
}

/** Section heading with a heading() role so TalkBack can jump between blocks. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StudioSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = Studio.text.headlineS,
            color = Studio.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { heading() },
        )
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            Text(
                text = trailing,
                style = Studio.text.labelM,
                color = Studio.colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** State banner: warn (non-blocking data-quality notice) or danger (a failure that needs retry). */
@Composable
fun StudioBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.Warn,
    icon: StudioIcon = StudioIcon.Warning,
    action: (@Composable () -> Unit)? = null,
) {
    val background = if (kind == BannerKind.Warn) Studio.colors.warnTint else Studio.colors.dangerTint
    val ink = if (kind == BannerKind.Warn) Studio.colors.warnInk else Studio.colors.dangerInk
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(background)
            .drawBehind {
                drawRoundRect(
                    color = ink,
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            .padding(start = Space.m, top = Space.m, bottom = Space.m, end = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioIconView(icon = icon, tint = ink, size = 24.dp)
        Spacer(Modifier.width(Space.m))
        Text(
            text = text,
            style = Studio.text.bodyM,
            color = ink,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Spacer(Modifier.width(Space.s))
            action()
        }
    }
}

enum class BannerKind { Warn, Danger }

/**
 * The task card (关键页面说明 section 0.3).
 *
 * The whole card opens the mode picker; the completion control is a separate semantic node with its
 * own 48dp hit area, so opening a card can never be mistaken for completing it.
 */
@Composable
fun StudioTaskCard(
    title: String,
    note: String,
    art: ArtAsset,
    completed: Boolean,
    modifier: Modifier = Modifier,
    /** `临时任务` / `日常任务`; the card announces which group it belongs to. */
    kindLabel: String = "",
    running: Boolean = false,
    paused: Boolean = false,
    archived: Boolean = false,
    onOpen: () -> Unit,
    onToggleComplete: (() -> Unit)? = null,
    menuActions: List<Pair<String, () -> Unit>> = emptyList(),
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    child: (@Composable () -> Unit)? = null,
) {
    val colors = Studio.colors
    val motion = LocalStudioMotion.current
    var menuOpen by remember { mutableStateOf(false) }

    val titleColor by animateColorAsState(
        targetValue = if (completed) colors.inkSecondary else colors.ink,
        animationSpec = motion.tweenOf(motion.stateFastMs),
        label = "cardTitle",
    )

    val cardShape = RoundedCornerShape(Radius.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(cardShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.lineHairline, cardShape)
            .clickable(role = Role.Button, onClick = onOpen)
            // The description is set after `clickable` so it survives: `clickable` installs its own
            // semantics node, and a description installed before it would be replaced rather than
            // merged. The card stays one focusable node whose sentence covers title, note and state.
            .semantics(mergeDescendants = true) {
                // The sentence form is fixed by 动效与无障碍说明 section 6.2: title, note, group, state.
                contentDescription = buildString {
                    append(title)
                    if (note.isNotBlank()) append("。备注：$note")
                    if (kindLabel.isNotBlank()) append("。$kindLabel")
                    append("。")
                    if (completed) append("已完成。")
                    if (running) append("进行中。")
                    if (paused) append("已暂停。")
                    if (archived) append("已归档。")
                    if (!completed && !running && !paused && !archived) append("未完成。")
                }
                customActions = accessibilityActions
            }
            .padding(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The 4dp status bar is a shape cue, not a colour-only cue: solid while running, dashed while
        // paused, absent otherwise (设计系统 section 6.3).
        if (running || paused) {
            StatusBar(paused = paused, modifier = Modifier.height(ArtFrame.card.plus(Space.xs * 2)))
            Spacer(Modifier.width(Space.xs))
        }
        Box(
            Modifier
                .size(ArtFrame.card)
                .clip(RoundedCornerShape(topStart = Radius.sticker, topEnd = Radius.md, bottomEnd = Radius.md, bottomStart = Radius.md)),
        ) {
            StudioArt(asset = art, frame = ArtFrameKind.Card, cornerRadius = Radius.md)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Studio.text.titleM,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.isNotBlank()) {
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note,
                        style = Studio.text.bodyM,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Space.xs))
                    StudioIconView(icon = StudioIcon.Note, tint = colors.inkTertiary, size = 16.dp)
                }
            }
            if (completed || running || paused || archived) {
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (completed) {
                        StudioIconView(icon = StudioIcon.Check, tint = colors.doneInk, size = 16.dp, filled = true)
                        Spacer(Modifier.width(Space.xs))
                        Text("已完成", style = Studio.text.labelM, color = colors.doneInk)
                    } else if (running || paused) {
                        StudioIconView(
                            icon = if (paused) StudioIcon.Pause else StudioIcon.Play,
                            tint = if (paused) colors.inkTertiary else colors.running,
                            size = 16.dp,
                        )
                        Spacer(Modifier.width(Space.xs))
                        Text(
                            text = if (paused) "已暂停" else "进行中",
                            style = Studio.text.labelM,
                            color = if (paused) colors.inkTertiary else colors.running,
                        )
                    }
                    if (archived) {
                        StudioIconView(icon = StudioIcon.Archive, tint = colors.inkTertiary, size = 16.dp)
                        Spacer(Modifier.width(Space.xs))
                        Text("已归档", style = Studio.text.labelM, color = colors.inkTertiary)
                    }
                }
            }
            if (child != null) child()
        }
        if (onToggleComplete != null) {
            CompletionControl(
                taskTitle = title,
                completed = completed,
                onToggle = onToggleComplete,
            )
        }
        if (menuActions.isNotEmpty()) {
            Box {
                StudioIconButton(
                    icon = StudioIcon.More,
                    contentDescription = "更多操作 $title",
                    tint = colors.inkSecondary,
                    onClick = { menuOpen = true },
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    menuActions.forEach { (label, action) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, style = Studio.text.bodyL) },
                            onClick = {
                                menuOpen = false
                                action()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Circular completion control: 24dp visual, 48x48dp target, a checkbox role for TalkBack. */
@Composable
private fun CompletionControl(
    taskTitle: String,
    completed: Boolean,
    onToggle: () -> Unit,
) {
    val colors = Studio.colors
    Box(
        modifier = Modifier
            .size(Space.minTouch)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                stateDescription = if (completed) "已完成" else "未完成"
                contentDescription = if (completed) "撤销完成 $taskTitle" else "完成 $taskTitle"
                onClick(label = if (completed) "撤销完成" else "完成") { onToggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        StudioIconView(
            icon = StudioIcon.Check,
            tint = if (completed) colors.doneInk else colors.lineControl,
            size = 24.dp,
            filled = completed,
        )
    }
}

/** The 4dp left status bar: solid for running, dashed for paused. */
@Composable
private fun StatusBar(paused: Boolean, modifier: Modifier = Modifier) {
    val color = if (paused) Studio.colors.inkTertiary else Studio.colors.running
    androidx.compose.foundation.Canvas(modifier.width(4.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(size.width / 2),
            style = Stroke(
                width = size.width,
                pathEffect = if (paused) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
            ),
        )
    }
}

/**
 * The running-session card (关键页面说明 section 0.4): one semantic node that reports the investment
 * and returns to the focus screen without creating a second timer.
 */
@Composable
fun StudioRunningBar(
    taskTitle: String,
    elapsedSeconds: Long,
    remainingSeconds: Long?,
    paused: Boolean,
    modifier: Modifier = Modifier,
    onReturn: () -> Unit,
) {
    val colors = Studio.colors
    val motion = LocalStudioMotion.current
    val description = buildString {
        append(taskTitle)
        append("，")
        append(if (remainingSeconds != null) "倒计时" else "正向计时")
        append(if (paused) "已暂停" else "进行中")
        append("，已投入 ")
        append(Format.spokenDuration(elapsedSeconds))
        if (remainingSeconds != null) append("，剩余 ${Format.spokenDuration(remainingSeconds)}")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(Radius.lg))
            .background(colors.surfaceCard)
            .border(1.dp, colors.lineHairline, RoundedCornerShape(Radius.lg))
            .clickable(role = Role.Button, onClick = onReturn)
            .semantics(mergeDescendants = true) {
                contentDescription = "$description，点按返回专注"
                onClick(label = "回到专注") { onReturn(); true }
            }
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBar(paused = paused, modifier = Modifier.height(40.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = taskTitle,
                style = Studio.text.titleM,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (paused) "已暂停" else "进行中",
                style = Studio.text.labelM,
                color = if (paused) colors.inkTertiary else colors.running,
            )
        }
        Text(
            text = Format.clock(elapsedSeconds),
            style = Studio.text.displayL.merge(MonoTabular),
            color = if (paused) colors.inkTertiary else colors.running,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.s))
        BreathingDot(paused = paused, reducedMotion = motion.reduced)
    }
}

/**
 * The app's only looping animation: an 8dp dot breathing over 1200ms.
 *
 * With reduced motion or power-save it stays a static solid dot; running versus paused is still
 * distinguishable through the dot colour, the timer colour, the bar style and the label text.
 */
@Composable
fun BreathingDot(paused: Boolean, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val color = if (paused) Studio.colors.inkTertiary else Studio.colors.running
    if (reducedMotion || LocalPowerSave.current) {
        Box(modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Studio.motion.timerPulseMs, easing = Studio.motion.pulseEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathAlpha",
    )
    Box(modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = alpha)))
}

/** The six-step heat ramp, defined once so the calendar and the heat map cannot drift apart. */
enum class HeatStep { None, Zero, One, Two, Three, Four }

fun heatStepOf(seconds: Long, hasRecord: Boolean): HeatStep = when {
    !hasRecord -> HeatStep.None
    seconds <= 0 -> HeatStep.Zero
    seconds < 30 * 60 -> HeatStep.One
    seconds < 60 * 60 -> HeatStep.Two
    seconds < 120 * 60 -> HeatStep.Three
    else -> HeatStep.Four
}

fun StudioColors.heatColor(step: HeatStep): Color = when (step) {
    HeatStep.None -> Color.Transparent
    HeatStep.Zero -> heat[0]
    HeatStep.One -> heat[1]
    HeatStep.Two -> heat[2]
    HeatStep.Three -> heat[3]
    HeatStep.Four -> heat[4]
}

/** The legend text is the same on both screens; the ramp is ordered None, Zero, 1..4. */
val HEAT_LEGEND_LABELS = listOf("无记录", "0 分钟", "1–29", "30–59", "1–2 小时", "≥2 小时")

/**
 * Shared six-step legend.
 *
 * It sits on `canvas`, not on `surfaceTint`: `inkTertiary` on `surfaceTint` measures 4.32:1, below the
 * 4.5:1 small-text minimum, while on `canvas` it measures 4.64:1 (关键页面说明 section 3.3).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HeatLegend(
    modifier: Modifier = Modifier,
    includeCompletionMark: Boolean = false,
    includeToday: Boolean = false,
) {
    val colors = Studio.colors
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        HeatStep.entries.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeatChip(step = step, size = 12.dp)
                Spacer(Modifier.width(Space.xs))
                Text(HEAT_LEGEND_LABELS[index], style = Studio.text.labelM, color = colors.inkTertiary)
            }
        }
        if (includeCompletionMark) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.doneInk))
                }
                Spacer(Modifier.width(Space.xs))
                Text("有完成", style = Studio.text.labelM, color = colors.inkTertiary)
            }
        }
        if (includeToday) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .border(2.dp, colors.accent, RoundedCornerShape(Radius.xs)),
                )
                Spacer(Modifier.width(Space.xs))
                Text("今天", style = Studio.text.labelM, color = colors.inkTertiary)
            }
        }
    }
}

/** One legend/cell chip: filled for zero and the ramp, dashed for "no records". */
@Composable
fun HeatChip(step: HeatStep, size: Dp, modifier: Modifier = Modifier) {
    val colors = Studio.colors
    if (step == HeatStep.None) {
        DashedCellOutline(modifier.size(size), color = colors.heatDash)
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(Radius.xs))
                .background(colors.heatColor(step)),
        )
    }
}

/** The whole-legend TalkBack sentence: the ramp is read out, not just looked at. */
val HEAT_LEGEND_DESCRIPTION =
    "颜色越深表示投入越多：无记录（虚线框）、0 分钟、1 至 29 分钟、30 至 59 分钟、1 至 2 小时、2 小时以上"
