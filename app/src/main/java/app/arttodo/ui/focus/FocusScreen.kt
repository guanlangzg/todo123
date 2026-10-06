package app.arttodo.ui.focus

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Intent
import app.arttodo.system.SessionDeadline
import app.arttodo.system.SessionRuntimeService
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.arttodo.core.SessionMode
import app.arttodo.core.RecoveryChoice
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.core.WorkSegment
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalSnackbarHostState
import app.arttodo.ui.AppViewModel
import app.arttodo.domain.bridge.DomainFailure
import app.arttodo.ui.describe
import app.arttodo.ui.common.ArtAsset
import app.arttodo.ui.common.ArtFrameKind
import app.arttodo.ui.common.BannerKind
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.MonoTabular
import app.arttodo.ui.common.StudioArt
import app.arttodo.ui.common.StudioBanner
import app.arttodo.ui.common.StudioConfirmDialog
import app.arttodo.ui.common.StudioIconButton
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioSecondaryButton
import app.arttodo.ui.common.StudioTextAction
import app.arttodo.ui.common.countdownReached
import app.arttodo.ui.common.elapsedSeconds
import app.arttodo.ui.common.remainingSeconds
import app.arttodo.ui.common.rememberSessionNow
import app.arttodo.ui.theme.LocalPowerSave
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space
import app.arttodo.ui.theme.TimerLadder

/** What the result screen needs after the session has been closed. */
private data class ResultData(val taskTitle: String, val elapsedSeconds: Long, val targetSeconds: Long?)

/**
 * The focus screen: mode picker, live clock, countdown result.
 *
 * Four spec rules shape it:
 *
 * 1. Picking a mode starts nothing; only Start creates a session (规格 5.1, AC-04).
 * 2. The remembered countdown minutes are written back **only** after Start, so editing the field and
 *    cancelling leaves the stored default alone.
 * 3. Reaching zero stops the clock with the real invested seconds, never the target, and shows the
 *    result. Rest starts no timer, and Continue returns to the picker where Start must be pressed
 *    again (Q20/Q35/Q36, AC-07).
 * 4. The task's completion is never touched here; the screen says so in words.
 */
@Composable
fun FocusScreen(
    taskId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = LocalAppState.current
    val viewModel = LocalAppViewModel.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val task = state.taskById(taskId)
    val active = state.activeSession?.takeIf { it.taskId == taskId }
    val recovery = state.recoveryAmounts?.takeIf { state.recoverySessionId == active?.sessionId && active?.state == SessionState.RECOVERY_PENDING }
    var recoverySeconds by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ResultData?>(null) }
    // Continue preselects the same countdown length but still requires an explicit Start.
    var continueMinutes by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .background(Studio.colors.canvas)
            .statusBarsPadding(),
    ) {
        val currentTask = task
        when {
            currentTask == null -> MissingTask(onClose = onClose)

            result != null -> CountdownResult(
                data = result!!,
                onRest = {
                    state.completedCountdownSession?.let { viewModel.consumeCountdownResult(it.sessionId) }
                    onClose()
                },
                onContinue = {
                    state.completedCountdownSession?.let { viewModel.consumeCountdownResult(it.sessionId) }
                    continueMinutes = result!!.targetSeconds?.let { (it / 60).toInt() }
                    result = null
                },
            )

            state.completedCountdownSession?.sessionId != null && state.completedCountdownSession?.taskId == taskId -> CountdownResult(
                data = ResultData(state.completedCountdownTitle, state.completedCountdownSeconds, state.completedCountdownSession.targetSeconds),
                onRest = {
                    viewModel.consumeCountdownResult(state.completedCountdownSession.sessionId)
                    onClose()
                },
                onContinue = {
                    // Consuming the result re-reads the projection, which is what lets the picker take
                    // over: the result branch above would otherwise match again on every recomposition.
                    viewModel.consumeCountdownResult(state.completedCountdownSession.sessionId)
                    continueMinutes = state.completedCountdownSession.targetSeconds?.let { (it / 60).toInt() }
                },
            )

            active?.state == SessionState.RECOVERY_PENDING && recovery != null -> RecoveryPrompt(
                session = requireNotNull(active),
                trustedSeconds = recovery.trustedSeconds,
                gapSeconds = recovery.gapSeconds,
                recoverySeconds = recoverySeconds,
                onRecoverySecondsChange = { recoverySeconds = it.filter(Char::isDigit).take(9) },
                onAccept = { viewModel.resolveRecovery(requireNotNull(active).sessionId, RecoveryChoice.Accept) },
                onModify = { seconds -> viewModel.resolveRecovery(requireNotNull(active).sessionId, RecoveryChoice.Modify(seconds)) },
                onDiscard = { viewModel.resolveRecovery(requireNotNull(active).sessionId, RecoveryChoice.Discard) },
            )

            active != null -> RunningFocus(
                taskId = taskId,
                session = active,
                segments = state.activeSegments,
                onClose = onClose,
                onReached = { elapsed, target ->
                    // Reaching zero books the seconds the countdown was asked to run: the tick that
                    // notices can be up to a second late, and the target is the boundary the user
                    // chose (the service applies the same cap).
                    val booked = target?.let { minOf(elapsed, it) } ?: elapsed
                    viewModel.finishAt(sessionId = active.sessionId, seconds = booked) { failure ->
                        if (failure == null) {
                            if (target != null) {
                                result = ResultData(currentTask.title, booked, target)
                                viewModel.consumeCountdownResult(active.sessionId)
                            } else {
                                onClose()
                                scope.launch { snackbar.showSnackbar("已记录 ${Format.spokenDuration(booked)}") }
                            }
                        } else errorMessage = failure.describe()
                    }
                },
                onFinished = { elapsed, _ ->
                    // The only dispatch of the stop path: the button reports what the clock showed
                    // and this closes the session once. Dispatching here *and* in the button used to
                    // run the command twice, so the second one failed with "session not found" and
                    // the close/snackbar never ran.
                    viewModel.finishAt(active.sessionId, elapsed) { failure ->
                        if (failure == null) {
                            viewModel.consumeCountdownResult(active.sessionId)
                            onClose()
                            scope.launch { snackbar.showSnackbar("已记录 ${Format.spokenDuration(elapsed)}") }
                        } else errorMessage = failure.describe()
                    }
                },
            )

            else -> ModePicker(
                taskTitle = currentTask.title,
                note = currentTask.note,
                kindLabel = if (currentTask.kind == TaskKind.DAILY) "日常任务" else "临时任务",
                rememberedMinutes = currentTask.lastCountdownMinutes.toInt(),
                presetMode = if (continueMinutes != null) SessionMode.COUNTDOWN else null,
                presetMinutes = continueMinutes,
                anotherSessionTaskId = state.activeSession?.taskId,
                onBack = onClose,
                onStart = { mode, minutes ->
                viewModel.startSession(
                    taskId = taskId,
                    mode = mode,
                    targetSeconds = if (mode == SessionMode.COUNTDOWN) minutes * 60L else null,
                    replacesSessionId = state.activeSession?.sessionId,
                    rememberMinutes = if (mode == SessionMode.COUNTDOWN) minutes else null,
                ) { failure ->
                    if (failure != null) errorMessage = failure.describe()
                }
                },
            )
        }
    }
}

@Composable
private fun MissingTask(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        StudioTextAction(text = "返回", onClick = onClose, color = Studio.colors.inkSecondary)
        StudioBanner(
            text = "这个任务已经不在列表里了，可能已被归档。可以回到今天页继续。",
            kind = BannerKind.Warn,
        )
        StudioPrimaryButton(text = "回到今天", onClick = onClose)
    }
}

/**
 * The mode picker.
 *
 * [presetMode] is only set when the user came back from a result screen; the mode is still a choice,
 * and Start is still required.
 */
@Composable
private fun ModePicker(
    taskTitle: String,
    note: String,
    kindLabel: String,
    rememberedMinutes: Int,
    presetMode: SessionMode?,
    presetMinutes: Int?,
    anotherSessionTaskId: String?,
    onBack: () -> Unit,
    onStart: (SessionMode, Int) -> Unit,
) {
    val initialMinutes = (presetMinutes ?: rememberedMinutes).coerceIn(1, 1440)
    var mode by remember(taskTitle) { mutableStateOf(presetMode) }
    var minutes by remember(taskTitle, initialMinutes) { mutableIntStateOf(initialMinutes) }
    var input by remember(taskTitle, initialMinutes) { mutableStateOf(initialMinutes.toString()) }
    var confirmSwitch by remember { mutableStateOf(false) }
    // Which mode the confirmation is about: any new session replaces the running one, so the dialog is
    // not specific to the countdown mode (规格 5.1, and the banner above promises it for both).
    var pendingMode by remember { mutableStateOf<SessionMode?>(null) }
    val parsed = input.toIntOrNull()
    val minutesValid = parsed != null && parsed in 1..1440
    val anotherSession = anotherSessionTaskId != null
    val fontScale = LocalDensity.current.fontScale

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.pageMargin),
    ) {
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioIconButton(
                icon = StudioIcon.ChevronDown,
                contentDescription = "收起，返回今天",
                onClick = onBack,
                tint = Studio.colors.inkSecondary,
            )
            Spacer(Modifier.width(Space.s))
            Text(kindLabel, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        }

        Spacer(Modifier.height(Space.xxl))
        Text(
            text = taskTitle,
            style = Studio.text.titleL,
            color = Studio.colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (note.isNotBlank()) {
            Spacer(Modifier.height(Space.s))
            Text(note, style = Studio.text.bodyL, color = Studio.colors.inkSecondary)
        }

        Spacer(Modifier.height(Space.section))
        if (anotherSession) {
            StudioBanner(
                text = "还有一段计时在进行。开始新计时前会先确认结束它。",
                kind = BannerKind.Warn,
                icon = StudioIcon.Warning,
            )
            Spacer(Modifier.height(Space.l))
        }

        ModeCard(
            title = "正向计时",
            description = "从此刻开始累计，不设终点",
            icon = StudioIcon.TimerForward,
            selected = mode == SessionMode.COUNT_UP,
            onSelect = { mode = SessionMode.COUNT_UP },
        )
        Spacer(Modifier.height(Space.s))
        ModeCard(
            title = "倒计时",
            description = "到点会停下，由你决定休息还是继续",
            icon = StudioIcon.TimerCountdown,
            selected = mode == SessionMode.COUNTDOWN,
            onSelect = { mode = SessionMode.COUNTDOWN },
        ) {
            Spacer(Modifier.height(Space.m))
            // The design asks for a visible label on the field, not a placeholder-only affordance
            // (关键页面说明 section 2.1); the unit is stated in words as well.
            Text("分钟", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(88.dp)
                        .heightIn(min = Space.minTouch)
                        .clip(RoundedCornerShape(Radius.sm))
                        .border(
                            width = 1.dp,
                            color = if (minutesValid) Studio.colors.lineControl else Studio.colors.dangerInk,
                            shape = RoundedCornerShape(Radius.sm),
                        )
                        .clickable { mode = SessionMode.COUNTDOWN },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(4)
                            input = digits
                            digits.toIntOrNull()?.let { minutes = it }
                            mode = SessionMode.COUNTDOWN
                        },
                        singleLine = true,
                        textStyle = Studio.text.titleM.merge(MonoTabular).copy(
                            color = Studio.colors.ink,
                            textAlign = TextAlign.Center,
                        ),
                        cursorBrush = SolidColor(Studio.colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.s)
                            .semantics {
                                contentDescription = "倒计时分钟数，当前 $input"
                                stateDescription = "分钟"
                                // Announced as an error as well as coloured, so the invalid state is
                                // never carried by the border colour alone (设计系统 section 6.3).
                                if (!minutesValid) error("请输入 1–1440 之间的整数分钟")
                            },
                    )
                }
                Spacer(Modifier.width(Space.s))
                StepButton(StudioIcon.ChevronDown, "减少一分钟") {
                    mode = SessionMode.COUNTDOWN
                    val next = (parseMinutes(input, minutes) - 1).coerceAtLeast(1)
                    minutes = next
                    input = next.toString()
                }
                Spacer(Modifier.width(Space.s))
                StepButton(StudioIcon.ChevronUp, "增加一分钟") {
                    mode = SessionMode.COUNTDOWN
                    val next = (parseMinutes(input, minutes) + 1).coerceAtMost(1440)
                    minutes = next
                    input = next.toString()
                }
            }
            if (!minutesValid) {
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "请输入 1–1440 之间的整数分钟",
                    style = Studio.text.bodyM,
                    color = Studio.colors.dangerInk,
                )
            }
            Spacer(Modifier.height(Space.m))
            Text("快捷档", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
            Spacer(Modifier.height(Space.xs))
            val presets = listOf(15, 25, 45, 60)
            // 大字体下换成 2×2 网格：四个 48dp 目标挤在一行会把文字压成竖排（关键页面说明 section 2.5）。
            val rows = if (fontScale >= 2f) presets.chunked(2) else listOf(presets)
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { preset ->
                            PresetChip(
                                minutes = preset,
                                selected = parseMinutes(input, minutes) == preset && minutesValid,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    mode = SessionMode.COUNTDOWN
                                    minutes = preset
                                    input = preset.toString()
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.section))
        StudioPrimaryButton(
            text = "开始",
            enabled = when (mode) {
                null -> false
                SessionMode.COUNT_UP -> true
                SessionMode.COUNTDOWN -> minutesValid
            },
            onClick = {
                val chosen = mode ?: return@StudioPrimaryButton
                // Either mode replaces the running session, so either mode confirms first — the
                // countdown-only check used to start a forward session by silently ending the old one.
                if (anotherSession) {
                    pendingMode = chosen
                    confirmSwitch = true
                } else {
                    onStart(chosen, minutes)
                }
            },
        )
        Spacer(Modifier.height(Space.xxl))
    }

    if (confirmSwitch) {
        StudioConfirmDialog(
            title = "结束当前的计时并开始新的一段？",
            body = "当前的这一段会先保存，然后开始「$taskTitle」。",
            confirmLabel = "结束并开始",
            onConfirm = {
                val chosen = pendingMode
                confirmSwitch = false
                pendingMode = null
                if (chosen != null) onStart(chosen, minutes)
            },
            onDismiss = {
                confirmSwitch = false
                pendingMode = null
            },
        )
    }
}

private fun parseMinutes(input: String, fallback: Int): Int = input.toIntOrNull() ?: fallback

@Composable
private fun StepButton(icon: StudioIcon, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Space.minTouch)
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, Studio.colors.lineControl, RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        StudioIconView(icon = icon, tint = Studio.colors.inkSecondary)
    }
}

@Composable
private fun PresetChip(minutes: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Space.minTouch)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) Studio.colors.accentTint else Studio.colors.surfaceCard)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Studio.colors.accent else Studio.colors.lineControl,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                contentDescription = "$minutes 分钟"
                stateDescription = if (selected) "已选中" else "未选中"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("$minutes", style = Studio.text.labelL, color = Studio.colors.ink)
    }
}

/** One selectable mode card: radio role, selection shown by ring + fill, not colour alone. */
@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: StudioIcon,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(if (selected) Studio.colors.accentTint else Studio.colors.surfaceCard)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Studio.colors.accent else Studio.colors.lineHairline,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .semantics {
                role = Role.RadioButton
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioIconView(icon = icon, tint = if (selected) Studio.colors.accent else Studio.colors.iconLine)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = Studio.text.headlineS, color = Studio.colors.ink)
                if (description.isNotBlank()) {
                    Text(description, style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
                }
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = 2.dp,
                        color = if (selected) Studio.colors.accent else Studio.colors.lineControl,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Studio.colors.accent))
                }
            }
        }
        content()
    }
}

/**
 * The live clock.
 *
 * The ticker runs only while the session runs, so pausing freezes the digits at their exact value
 * rather than letting an animation drift past it (动效与无障碍说明 section 2.3). The background is the
 * focusing illustration at alpha 0.18 under a 74% canvas wash, so every character still sits on a
 * solid colour and the contrast does not depend on the artwork (设计系统 section 7.4).
 */
@Composable
private fun RunningFocus(
    taskId: String,
    session: app.arttodo.core.SessionRecord,
    segments: List<WorkSegment>,
    onClose: () -> Unit,
    onReached: (Long, Long?) -> Unit,
    onFinished: (Long, Long?) -> Unit,
) {
    val state = LocalAppState.current
    val viewModel = LocalAppViewModel.current
    val context = LocalContext.current
    val task = state.taskById(taskId)
    val fontScale = LocalDensity.current.fontScale
    val powerSave = LocalPowerSave.current
    val paused = session.state != SessionState.RUNNING

    val now by rememberSessionNow(
        running = !paused,
        powerSave = powerSave,
        initialNowMs = { System.currentTimeMillis() },
    )
    val elapsed = elapsedSeconds(segments, session.sessionId, now)
    val remaining = remainingSeconds(session.targetSeconds, elapsed)
    val reached = countdownReached(session.targetSeconds, elapsed)

    // The single automatic transition: reaching zero books the real seconds and opens the result.
    // It does not complete the task and does not start a break (规格 5.1, AC-07). The vibration is
    // haptic feedback, so it is unaffected by the reduced-motion preference.
    LaunchedEffect(reached, session.sessionId) {
        if (reached) {
            vibrateOnce(context, state.vibrationEnabled)
            onReached(elapsed, session.targetSeconds)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Background: illustration at 18% under a canvas wash; text never sits on the artwork. The
        // frame is `Fill` so the art covers the screen instead of keeping its 72dp thumbnail box, and
        // a missing background degrades to plain canvas rather than a placeholder that would
        // interrupt the session (设计系统 section 7.4, 关键页面说明 section 2.5).
        Box(Modifier.fillMaxSize().alpha(0.18f)) {
            StudioArt(
                asset = if (paused) ArtAsset.StatePaused else ArtAsset.StateFocusing,
                frame = ArtFrameKind.Fill,
                cornerRadius = Radius.none,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                showDegradation = false,
            )
        }
        Box(Modifier.fillMaxSize().background(Studio.colors.canvas.copy(alpha = 0.74f)))

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.pageMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton(
                    icon = StudioIcon.ChevronDown,
                    contentDescription = "收起，计时继续",
                    onClick = onClose,
                    tint = Studio.colors.inkSecondary,
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    text = if (session.mode == SessionMode.COUNTDOWN) {
                        "倒计时 · 目标 ${(session.targetSeconds ?: 0) / 60} 分钟"
                    } else {
                        "正向计时"
                    },
                    style = Studio.text.labelM,
                    color = Studio.colors.inkSecondary,
                )
            }

            Spacer(Modifier.height(Space.big))
            Text(
                text = task?.title ?: "计时中的任务",
                style = Studio.text.titleL,
                color = Studio.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            )

            Spacer(Modifier.height(Space.big))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val step = TimerLadder.pickSp(
                    fontScale = fontScale,
                    availableWidthDp = maxWidth.value,
                    hasHours = elapsed >= 3600,
                )
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Format.clock(elapsed),
                        style = Studio.text.displayXL
                            .copy(fontSize = step.sp, lineHeight = (step * 1.2f).sp)
                            .merge(MonoTabular),
                        color = if (paused) Studio.colors.inkTertiary else Studio.colors.ink,
                        maxLines = if (TimerLadder.wrapsAt(fontScale)) 2 else 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = "已投入 ${Format.spokenDuration(elapsed)}"
                                if (remaining != null) {
                                    stateDescription = "剩余 ${Format.spokenDuration(remaining)}"
                                }
                            },
                    )
                    if (remaining != null) {
                        Text(
                            text = "剩余 ${Format.clock(remaining)}",
                            style = Studio.text.bodyM,
                            color = Studio.colors.inkSecondary,
                        )
                    }
                    if (paused) {
                        Spacer(Modifier.height(Space.s))
                        Text(
                            text = "已暂停 · 暂停期间不计入投入",
                            style = Studio.text.labelM,
                            color = Studio.colors.inkTertiary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }

            // The ring is decoration; at the largest font scale it yields so the buttons stay reachable
            // without scrolling (设计系统 section 3.5).
            if (session.mode == SessionMode.COUNTDOWN && session.targetSeconds != null && fontScale < 2f) {
                Spacer(Modifier.height(Space.big))
                ProgressRing(
                    progress = (remaining ?: 0).toFloat() / session.targetSeconds.toFloat(),
                    paused = paused,
                    diameter = if (fontScale >= 1.5f) 140.dp else 200.dp,
                )
            }

            Spacer(Modifier.weight(1f))
            val stacked = fontScale >= 2f
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    ControlButton(
                        icon = if (paused) StudioIcon.Play else StudioIcon.Pause,
                        label = if (paused) "继续计时" else "暂停计时",
                        primary = true,
                        fillWidth = true,
                        onClick = {
                            if (paused) viewModel.resumeSession(session.sessionId)
                            else viewModel.pauseSession(session.sessionId)
                        },
                    )
                    ControlButton(
                        icon = StudioIcon.Stop,
                        label = "结束本次专注，已投入 ${Format.spokenDuration(elapsed)}",
                        primary = false,
                        fillWidth = true,
                        // The label is `elapsed`, so the accumulated seconds are what the screen shows
                        // and what `onFinished` books; the button itself dispatches nothing.
                        onClick = { onFinished(elapsed, session.targetSeconds) },
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ControlButton(
                        icon = if (paused) StudioIcon.Play else StudioIcon.Pause,
                        label = if (paused) "继续计时" else "暂停计时",
                        primary = true,
                        fillWidth = false,
                        onClick = {
                            if (paused) viewModel.resumeSession(session.sessionId)
                            else viewModel.pauseSession(session.sessionId)
                        },
                    )
                    Spacer(Modifier.width(Space.section))
                    ControlButton(
                        icon = StudioIcon.Stop,
                        label = "结束本次专注，已投入 ${Format.spokenDuration(elapsed)}",
                        primary = false,
                        fillWidth = false,
                        // See the stacked branch: one dispatch, in `onFinished`.
                        onClick = { onFinished(elapsed, session.targetSeconds) },
                    )
                }
            }
            Spacer(Modifier.height(Space.xxl))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** One round control: 64dp visual and hit area, icon plus a full spoken sentence. */
@Composable
private fun ControlButton(
    icon: StudioIcon,
    label: String,
    primary: Boolean,
    fillWidth: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (fillWidth) Radius.lg else 32.dp)
    Box(
        Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(64.dp))
            .height(64.dp)
            .clip(shape)
            .background(if (primary) Studio.colors.running else Color.Transparent)
            .border(2.dp, if (primary) Studio.colors.running else Studio.colors.inkSecondary, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        StudioIconView(
            icon = icon,
            tint = if (primary) Studio.colors.onAccent else Studio.colors.inkSecondary,
            size = 28.dp,
            filled = primary,
        )
    }
}

/** Countdown progress ring: 6dp stroke drawn from the remaining fraction. */
@Composable
private fun ProgressRing(progress: Float, paused: Boolean, diameter: Dp) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "ring")
    // Colours are read during composition: the palette lives in a composition local, so the drawing
    // pass must not resolve it.
    val track = Studio.colors.lineControl
    val fill = if (paused) Studio.colors.inkTertiary else Studio.colors.running
    Canvas(Modifier.size(diameter)) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track.copy(alpha = 0.4f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = fill,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * The countdown result.
 *
 * Rest starts no timer and Continue starts none either: it returns to the picker where Start must be
 * pressed again (Q20/Q35/Q36). The task's completion is untouched and the screen states that in words
 * rather than leaving the user to infer it.
 */
@Composable
private fun CountdownResult(
    data: ResultData,
    onRest: () -> Unit,
    onContinue: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.pageMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Space.giant))
        Text(
            text = "这一段结束了",
            style = Studio.text.headlineM,
            color = Studio.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.l))
        Text(
            text = Format.clock(data.targetSeconds ?: data.elapsedSeconds),
            style = Studio.text.displayXL
                .copy(fontSize = (if (fontScale >= 1.6f) 40 else 52).sp)
                .merge(MonoTabular),
            color = Studio.colors.accent,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            text = "有效投入 ${Format.spokenDuration(data.elapsedSeconds)}",
            style = Studio.text.bodyM,
            color = Studio.colors.inkSecondary,
        )
        Spacer(Modifier.height(Space.l))
        Text(
            text = data.taskTitle,
            style = Studio.text.titleM,
            color = Studio.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            StudioSecondaryButton(
                text = "休息",
                onClick = onRest,
                modifier = Modifier.weight(1f),
                height = 56.dp,
            )
            StudioPrimaryButton(
                text = "继续专注",
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                height = 56.dp,
            )
        }
        Spacer(Modifier.height(Space.m))
        Text(
            text = "休息不会开始倒计时；继续专注需要再选一次模式并点击开始。任务完成状态没有变化，需要的话请在今天页勾选。",
            style = Studio.text.bodyM,
            color = Studio.colors.inkTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xxl))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * The interrupted-session prompt (AC-09).
 *
 * The three amounts come from the core: [trustedSeconds] is `[start, last heartbeat]` and
 * [gapSeconds] is the unverified span, which is **not** booked until the user chooses. Accept books
 * both, Modify books what the user types, Discard books only the trusted part.
 *
 * A session that was paused when it was lost has no unverified span: paused time is never investment,
 * so the core reports a zero gap and the correction field is not offered — there is nothing left that
 * a typed number could correct.
 */
@Composable
fun RecoveryPrompt(
    session: app.arttodo.core.SessionRecord,
    trustedSeconds: Long,
    gapSeconds: Long,
    recoverySeconds: String,
    onRecoverySecondsChange: (String) -> Unit,
    onAccept: () -> Unit,
    onModify: (Long) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasUnverifiedSpan = gapSeconds > 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        StudioBanner(
            text = "上次的计时可能没有正常结束（${if (session.mode == SessionMode.COUNTDOWN) "倒计时 ${session.targetSeconds ?: 0} 秒" else "正向计时"}）。" +
                if (hasUnverifiedSpan) {
                    "已确认 ${Format.minutes(trustedSeconds)}，另有 ${Format.minutes(gapSeconds)} 推算区间需要你确认。"
                } else {
                    "已确认 ${Format.minutes(trustedSeconds)}；这段计时在暂停中结束，暂停期间不计入投入。"
                },
            kind = BannerKind.Warn,
            icon = StudioIcon.Recovery,
        )
        Text(
            text = if (hasUnverifiedSpan) "未确认的部分不计入投入。" else "确认后这一段就结束了，已投入的时间不会变化。",
            style = Studio.text.labelM,
            color = Studio.colors.inkTertiary,
        )
        if (hasUnverifiedSpan) {
            OutlinedTextField(
                value = recoverySeconds,
                onValueChange = onRecoverySecondsChange,
                label = { Text("修正后的总投入秒数") },
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StudioSecondaryButton(text = "接受", onClick = onAccept, modifier = Modifier.weight(1f), height = 48.dp)
            if (hasUnverifiedSpan) {
                StudioSecondaryButton(
                    text = "修正",
                    onClick = { recoverySeconds.toLongOrNull()?.let(onModify) },
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    enabled = recoverySeconds.toLongOrNull() != null,
                )
            }
            StudioSecondaryButton(text = "舍弃", onClick = onDiscard, modifier = Modifier.weight(1f), height = 48.dp)
        }
    }
}

/** One short vibration at the countdown boundary, distinct from the completion tick. */
private fun vibrateOnce(context: Context, enabled: Boolean) {
    if (!enabled) return
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
}
