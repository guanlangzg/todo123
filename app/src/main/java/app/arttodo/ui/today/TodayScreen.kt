package app.arttodo.ui.today

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalSnackbarHostState
import app.arttodo.nav.LocalSnackbarHeight
import app.arttodo.ui.AppViewModel
import app.arttodo.ui.common.ArtAsset
import app.arttodo.ui.common.ArtFrameKind
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.elapsedSeconds
import app.arttodo.ui.common.remainingSeconds
import app.arttodo.ui.common.rememberSessionNow
import app.arttodo.ui.common.StudioArt
import app.arttodo.ui.common.StudioBanner
import app.arttodo.ui.common.StudioIconButton
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioRunningBar
import app.arttodo.ui.common.StudioSectionHeader
import app.arttodo.ui.common.StudioTaskCard
import app.arttodo.ui.common.StudioTextAction
import app.arttodo.ui.theme.ArtFrame
import app.arttodo.ui.theme.LocalPowerSave
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.ScrimStops
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space

/**
 * The default home screen (规格 section 1, 关键页面说明 section 1).
 *
 * Order is fixed by the design: date block, mood anchor, today's cover, the running-session card, the
 * temporary group (which always precedes the daily group), the daily group, then the collapsed
 * completed band. Every count and total comes from the domain state; the screen only formats them.
 */
@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    onOpenFocus: (String) -> Unit,
    /** Today's cover opens the record page for the day; it is the page's largest hit area. */
    onOpenCover: () -> Unit = {},
) {
    val state = LocalAppState.current
    val viewModel = LocalAppViewModel.current
    val snackbar = LocalSnackbarHostState.current
    val snackbarHeight = LocalSnackbarHeight.current
    val fontScale = LocalDensity.current.fontScale

    var showCreateSheet by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskRecord?>(null) }
    var showZoneInfo by remember { mutableStateOf(false) }

    val temporary = remember(state.tasks, state.occurrenceCompletion, state.temporaryCompletion) {
        state.tasks.filter { it.kind == TaskKind.TEMPORARY && !state.isCompleted(it) }
            .sortedBy { it.sortKey }
    }
    val daily = remember(state.tasks, state.occurrenceCompletion, state.temporaryCompletion) {
        state.tasks.filter { it.kind == TaskKind.DAILY && !state.isCompleted(it) }
            .sortedBy { it.sortKey }
    }
    val completed = remember(state.tasks, state.occurrenceCompletion, state.temporaryCompletion) {
        state.tasks.filter { state.isCompleted(it) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val completedMaxHeight = maxHeight * 0.6f
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.pageMargin),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = Space.m,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            item(key = "header") {
                DateBlock(moodAnchor = moodAnchor(temporary.size + daily.size), onZoneClick = { showZoneInfo = true })
            }

            // A countdown that stopped while the user was elsewhere still owes an answer (AC-07): the
            // result screen only renders on the focus route, and nothing navigates there by itself, so
            // the way back has to live here. It sits before the early returns below — a finished
            // countdown must not disappear behind the empty-list state (AC-17).
            state.completedCountdownSession?.let { session ->
                item(key = "countdown_result") {
                    CountdownResultEntry(
                        title = state.completedCountdownTitle,
                        investedSeconds = state.completedCountdownSeconds,
                        onOpen = { onOpenFocus(session.taskId) },
                    )
                }
            }

            if (state.error != null && !state.coreAvailable) {
                item(key = "error") {
                    StudioBanner(
                        text = "暂时读不到任务。数据仍在设备上，没有丢失。可以重试。",
                        kind = app.arttodo.ui.common.BannerKind.Warn,
                        icon = StudioIcon.Warning,
                        action = {
                            StudioTextAction(text = "重试", onClick = viewModel::refresh)
                        },
                    )
                }
                return@LazyColumn
            }

            if (temporary.isEmpty() && daily.isEmpty() && completed.isEmpty()) {
                item(key = "empty") { TodayEmptyState(onCreate = { showCreateSheet = true }) }
                return@LazyColumn
            }

            item(key = "cover") {
                TodayCover(
                    appDate = state.today,
                    temporaryCount = temporary.size,
                    dailyCount = daily.size,
                    fontScale = fontScale,
                    onOpen = onOpenCover,
                )
            }

            state.activeSession?.let { session ->
                val task = state.taskById(session.taskId)
                item(key = "running") {
                    val paused = session.state != SessionState.RUNNING
                    val now by rememberSessionNow(
                        running = !paused,
                        powerSave = LocalPowerSave.current,
                        initialNowMs = { System.currentTimeMillis() },
                    )
                    val elapsed = elapsedSeconds(state.activeSegments, session.sessionId, now)
                    StudioRunningBar(
                        taskTitle = task?.title ?: "计时中的任务",
                        elapsedSeconds = elapsed,
                        remainingSeconds = remainingSeconds(session.targetSeconds, elapsed),
                        paused = paused,
                        onReturn = { onOpenFocus(session.taskId) },
                    )
                }
            }

            item(key = "temp_header") {
                Spacer(Modifier.height(Space.l))
                StudioSectionHeader(
                    title = "临时任务",
                    modifier = Modifier.semantics { heading() },
                    trailing = "${temporary.size} 项",
                )
            }
            if (temporary.isEmpty()) {
                item(key = "temp_empty") { GroupEmpty(text = "没有临时任务。想到什么就先记下来。") }
            }
            items(temporary, key = { "t_${it.taskId}" }) { task ->
                TaskCardRow(
                    task = task,
                    state = state,
                    sortMode = sortMode,
                    completed = false,
                    isFirst = temporary.first().taskId == task.taskId,
                    isLast = temporary.last().taskId == task.taskId,
                    onOpen = { onOpenFocus(task.taskId) },
                    onEdit = { editing = task },
                    onMove = { delta -> viewModel.moveTask(task.taskId, delta) },
                    onMoveEdge = { end -> viewModel.moveTaskToEdge(task.taskId, end) },
                    snackbar = snackbar,
                )
            }

            item(key = "daily_header") {
                Spacer(Modifier.height(Space.xxl))
                StudioSectionHeader(
                    title = "日常任务",
                    modifier = Modifier.semantics { heading() },
                    trailing = "${daily.size} 项 · 已投 ${Format.minutes(state.todayTotalSeconds)}",
                )
            }
            if (daily.isEmpty()) {
                item(key = "daily_empty") { GroupEmpty(text = "还没有日常任务。它会每天出现，等你来勾。") }
            }
            items(daily, key = { "d_${it.taskId}" }) { task ->
                TaskCardRow(
                    task = task,
                    state = state,
                    sortMode = sortMode,
                    completed = false,
                    isFirst = daily.first().taskId == task.taskId,
                    isLast = daily.last().taskId == task.taskId,
                    onOpen = { onOpenFocus(task.taskId) },
                    onEdit = { editing = task },
                    onMove = { delta -> viewModel.moveTask(task.taskId, delta) },
                    onMoveEdge = { end -> viewModel.moveTaskToEdge(task.taskId, end) },
                    snackbar = snackbar,
                )
            }

            if (temporary.isNotEmpty() || daily.isNotEmpty()) {
                item(key = "sort_toggle") {
                    Spacer(Modifier.height(Space.m))
                    StudioTextAction(
                        text = if (sortMode) "结束调整顺序" else "调整顺序",
                        color = Studio.colors.accentDeep,
                        onClick = { sortMode = !sortMode },
                        contentDescription = if (sortMode) "结束调整顺序" else "调整顺序，可用上移下移按钮改变任务在组内的位置",
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "completed") {
                    Spacer(Modifier.height(Space.section))
                    CompletedBand(
                        tasks = completed,
                        state = state,
                        maxHeight = completedMaxHeight,
                        snackbar = snackbar,
                        onEdit = { editing = it },
                    )
                }
            }
        }

        // The single solid accent element on the page is the FAB (关键页面说明 section 1.2).
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = Space.l, bottom = Space.l)
                .offset(y = if (snackbarHeight > 0.dp) -(snackbarHeight + Space.s) else 0.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Studio.colors.accentFill)
                .clickable(role = Role.Button) { showCreateSheet = true }
                .semantics { contentDescription = "新建任务" },
            contentAlignment = Alignment.Center,
        ) {
            StudioIconView(icon = StudioIcon.Add, tint = Studio.colors.onAccent, size = 28.dp)
        }
    }

    if (showCreateSheet) {
        CreateTaskSheet(
            onDismiss = { showCreateSheet = false },
            onConfirm = { kind, title, note ->
                viewModel.createTask(kind, title, note)
                showCreateSheet = false
            },
        )
    }
    editing?.let { task ->
        EditTaskSheet(
            task = task,
            onDismiss = { editing = null },
            onConfirm = { title, note ->
                if (title != task.title) viewModel.renameTask(task.taskId, title)
                if (note != task.note) viewModel.editNote(task.taskId, note)
                editing = null
            },
        )
    }
    if (showZoneInfo) {
        ZoneInfoDialog(zoneId = state.zoneId, onDismiss = { showZoneInfo = false })
    }
}

/** `今天有 3 件事在等你` — factual, no unconfirmed praise (原则 P6). */
private fun moodAnchor(taskCount: Int): String =
    if (taskCount == 0) "今天从一张白纸开始" else "今天有 $taskCount 件事在等你"

/**
 * The pending countdown result (AC-07 / AC-17).
 *
 * A countdown that reaches zero closes the session and leaves a marker on the device; resting or
 * starting another segment is still the user's decision. The result screen lives on the focus route
 * and only renders when that route is opened for the session's own task, so after a countdown ends
 * behind a notification, a locked screen or another page, this entry is the only way back to it.
 *
 * The whole card is one button that opens that task's result; it states the outcome and the two
 * choices in words, so it does not depend on colour or on the user remembering what happened.
 */
@Composable
private fun CountdownResultEntry(
    title: String,
    investedSeconds: Long,
    onOpen: () -> Unit,
) {
    val colors = Studio.colors
    val shape = RoundedCornerShape(Radius.lg)
    val invested = Format.minutes(investedSeconds)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(colors.accentTint)
            .border(2.dp, colors.accent, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            // Installed after `clickable`, which replaces semantics declared before it on this node.
            .semantics(mergeDescendants = true) {
                contentDescription = "倒计时已结束：$title · 有效投入 $invested。查看结果，选择休息或继续"
                role = Role.Button
            }
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioIconView(icon = StudioIcon.TimerCountdown, tint = colors.accentDeep, size = 24.dp)
            Spacer(Modifier.width(Space.m))
            Text("倒计时已结束", style = Studio.text.headlineS, color = colors.ink)
        }
        Spacer(Modifier.height(Space.s))
        Text(
            text = "$title · 有效投入 $invested",
            style = Studio.text.bodyL,
            color = colors.inkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.s))
        Text("查看结果，选择休息或继续", style = Studio.text.labelL, color = colors.accentDeep)
    }
}

/** Date block, mood anchor and the zone control. The date label itself is never computed here. */
@Composable
private fun DateBlock(moodAnchor: String, onZoneClick: () -> Unit) {
    val today = LocalAppState.current.today
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Format.dateLabel(today),
                style = Studio.text.displayM,
                color = Studio.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = Format.spokenDate(today)
                    },
            )
            StudioIconButton(
                icon = StudioIcon.Zone,
                contentDescription = "应用时区 ${LocalAppState.current.zoneId}，点按查看说明",
                tint = Studio.colors.inkTertiary,
                onClick = onZoneClick,
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(
            text = moodAnchor,
            style = Studio.text.headlineM,
            color = Studio.colors.ink,
        )
    }
}

/**
 * Today's cover with the authored scrim.
 *
 * The frame follows the four-step font-scale gate (关键页面说明 section 1.4): the cover narrows from
 * 200dp to 152dp to 112dp and then yields entirely, and the summary moves off the artwork from step 3
 * so a large-font summary can never be clipped by the mask. The art is drawn at the gated height rather
 * than at its authored 200dp, so narrowing the frame fits the illustration instead of cropping it.
 *
 * The whole block is one tappable node — artwork plus, when it has moved off the art, the summary line.
 * That is what turns the cover's large area into a real action instead of decoration (关键页面说明
 * section 1.5).
 */
@Composable
private fun TodayCover(
    appDate: String,
    temporaryCount: Int,
    dailyCount: Int,
    fontScale: Float,
    onOpen: () -> Unit,
) {
    val height = ArtFrame.coverHeightFor(fontScale)
    val asset = remember(appDate) { ArtAsset.coverFor(appDate) }
    val summary = "临时 $temporaryCount 项 · 日常 $dailyCount 项"
    val stamp = remember(appDate) { appDate.takeLast(2).trimStart('0') }
    val summaryOnArt = ArtFrame.coverSummaryOnArt(fontScale)

    if (height == 0.dp) return
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            // Installed after `clickable`, which replaces semantics declared before it on this node.
            .semantics(mergeDescendants = true) {
                contentDescription = "今日封面：$summary"
                onClick(label = "查看今天的记录") { onOpen(); true }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Radius.xl)),
        ) {
            StudioArt(
                asset = asset,
                frame = ArtFrameKind.Cover,
                frameSize = DpSize(ArtFrame.coverWidth, height),
                scrimStops = ScrimStops.cover,
                cornerRadius = Radius.xl,
                contentDescription = null,
            )
            if (summaryOnArt) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary,
                        style = Studio.text.titleL,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Spacer(Modifier.width(Space.m))
                    // The stamp belongs to the cover's sentence; read on its own it is a bare number.
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(Studio.colors.accent)
                            .clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stamp, style = Studio.text.labelL, color = Studio.colors.onAccent)
                    }
                }
            }
        }
        if (!summaryOnArt) {
            Spacer(Modifier.height(Space.s))
            Text(text = summary, style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
        }
    }
}

/** Empty state: illustration above, copy and the single primary action below, no scrim on the art. */
@Composable
private fun TodayEmptyState(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StudioArt(
            asset = ArtAsset.EmptyToday,
            frame = ArtFrameKind.Panel,
            cornerRadius = Radius.xl,
            contentDescription = null,
        )
        Spacer(Modifier.height(Space.xxl))
        Text("今天还是一张空白的纸", style = Studio.text.headlineL, color = Studio.colors.ink)
        Spacer(Modifier.height(Space.s))
        Text(
            text = "加入第一件事，或者先给自己定一段专注。",
            style = Studio.text.bodyL,
            color = Studio.colors.inkSecondary,
        )
        Spacer(Modifier.height(Space.xxl))
        StudioPrimaryButton(text = "新建第一件事", onClick = onCreate)
        Spacer(Modifier.height(Space.m))
        Text(
            text = "也可以从下面的日常任务开始专注",
            style = Studio.text.labelM,
            color = Studio.colors.inkTertiary,
        )
    }
}

@Composable
private fun GroupEmpty(text: String) {
    Text(
        text = text,
        style = Studio.text.bodyM,
        color = Studio.colors.inkTertiary,
        modifier = Modifier.padding(vertical = Space.m),
    )
}

/** The active session if it belongs to [taskId]; the card and the today list must agree on this. */
private fun sessionFor(state: app.arttodo.ui.UiState, taskId: String): app.arttodo.core.SessionRecord? =
    state.activeSession?.takeIf { it.taskId == taskId }

/**
 * One task row.
 *
 * Sort mode replaces the card's long-press affordance with explicit up/down buttons, which is the
 * single-pointer equivalent required by WCAG 2.2 SC 2.5.7. The same moves are also first-class menu
 * items and TalkBack custom actions, so no path depends on dragging.
 */
@Composable
private fun TaskCardRow(
    task: TaskRecord,
    state: app.arttodo.ui.UiState,
    sortMode: Boolean,
    completed: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onMoveEdge: (Boolean) -> Unit,
    snackbar: androidx.compose.material3.SnackbarHostState,
) {
    val viewModel = LocalAppViewModel.current
    val taskTitle = task.title
    val scope = rememberCoroutineScope()
    val completedNow = state.isCompleted(task)
    /** The session this task is timing, running or paused; `null` when the task is not timed. */
    val runningSession = sessionFor(state, task.taskId)
    var confirmCompletion by remember { mutableStateOf(false) }

    // One tap back for a few seconds: completing is the action that moves a card between regions.
    // Undoing only removes the completion — the time a session booked stays in the ledger (AC-03).
    val announceUndo: () -> Unit = {
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已完成「$taskTitle」",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                uncompleteTask(viewModel, state, task)
            }
        }
        Unit
    }

    val toggle: () -> Unit = {
        when {
            completedNow -> uncompleteTask(viewModel, state, task)
            // AC-07: the card is timing, so completing it must end that session and save what it
            // invested — as one step, after the user confirms, and with the day of the completion
            // chosen when the session crossed midnight (AC-08). Ticking straight through used to
            // leave the timer running and the investment unbooked.
            runningSession != null -> confirmCompletion = true
            else -> {
                if (task.kind == TaskKind.DAILY) {
                    viewModel.setDailyCompletion(task.taskId, state.today, true)
                } else {
                    viewModel.setTemporaryCompletion(task.taskId, true)
                }
                announceUndo()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        StudioTaskCard(
            title = task.title,
            note = task.note,
            art = ArtAsset.forTask(task.kind == TaskKind.DAILY, completed),
            completed = completed,
            kindLabel = if (task.kind == TaskKind.DAILY) "日常任务" else "临时任务",
            running = sessionFor(state, task.taskId)?.state == SessionState.RUNNING,
            paused = sessionFor(state, task.taskId)?.state == SessionState.PAUSED,
            onOpen = onOpen,
            onToggleComplete = toggle,
            menuActions = buildList {
                add("上移" to { onMove(-1) })
                add("下移" to { onMove(1) })
                add("移到组首" to { onMoveEdge(false) })
                add("移到组末" to { onMoveEdge(true) })
                add("编辑标题与备注" to onEdit)
                add("归档" to { viewModel.archiveTask(task.taskId) })
            },
            accessibilityActions = listOf(
                CustomAccessibilityAction("上移") { onMove(-1); true },
                CustomAccessibilityAction("下移") { onMove(1); true },
                CustomAccessibilityAction("移到组首") { onMoveEdge(false); true },
                CustomAccessibilityAction("移到组末") { onMoveEdge(true); true },
                CustomAccessibilityAction("开始计时") { onOpen(); true },
                CustomAccessibilityAction(if (completed) "撤销完成" else "完成") { toggle(); true },
                CustomAccessibilityAction("编辑标题与备注") { onEdit(); true },
                CustomAccessibilityAction("归档") { viewModel.archiveTask(task.taskId); true },
            ),
        )
        if (sortMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                SortStepButton(
                    icon = StudioIcon.ChevronUp,
                    label = "上移 $taskTitle",
                    enabled = !isFirst,
                    onClick = { onMove(-1) },
                )
                SortStepButton(
                    icon = StudioIcon.ChevronDown,
                    label = "下移 $taskTitle",
                    enabled = !isLast,
                    onClick = { onMove(1) },
                )
            }
        }
    }

    if (confirmCompletion) {
        CompleteWhileRunningDialog(
            taskTitle = taskTitle,
            sessionStartDate = state.activeSessionStartDate,
            today = state.today,
            onConfirm = { appDate ->
                confirmCompletion = false
                runningSession?.let { viewModel.completeWhileRunning(it.sessionId, appDate) }
            },
            onDismiss = { confirmCompletion = false },
        )
    }
}

/** Removes a completion; the time a session booked in it stays in the ledger (AC-03). */
private fun uncompleteTask(viewModel: AppViewModel, state: app.arttodo.ui.UiState, task: TaskRecord) {
    if (task.kind == TaskKind.DAILY) {
        viewModel.setDailyCompletion(task.taskId, state.today, false)
    } else {
        viewModel.setTemporaryCompletion(task.taskId, false)
    }
}

@Composable
private fun SortStepButton(
    icon: StudioIcon,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 64.dp, height = Space.minTouch)
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, Studio.colors.lineControl, RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        StudioIconView(
            icon = icon,
            tint = if (enabled) Studio.colors.ink else Studio.colors.inkTertiary.copy(alpha = 0.38f),
        )
    }
}

/**
 * The completed band.
 *
 * Collapsed by default; when it holds dozens of items it scrolls inside itself at 60% of the viewport
 * instead of expanding the whole page (关键页面说明 section 1.3). Completion is replayed from the event
 * log, so opening it can never disagree with the today list.
 */
@Composable
private fun CompletedBand(
    tasks: List<TaskRecord>,
    state: app.arttodo.ui.UiState,
    maxHeight: androidx.compose.ui.unit.Dp,
    snackbar: androidx.compose.material3.SnackbarHostState,
    onEdit: (TaskRecord) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val viewModel = LocalAppViewModel.current
    val motion = Studio.motion
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.tweenOf(motion.stateMediumMs),
        label = "bandChevron",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(Studio.colors.surfaceTint)
                .clickable { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = "已完成 ${tasks.size} 项，已${if (expanded) "展开" else "收起"}"
                    heading()
                    role = Role.Button
                    stateDescription = if (expanded) "已展开" else "已收起"
                }
                .padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "收起已完成 ${tasks.size} 项" else "已完成 ${tasks.size} 项",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.weight(1f),
            )
            // Reduced motion swaps the图形 rather than rotating it (动效与无障碍说明 section 4.2).
            StudioIconView(
                icon = if (motion.reduced && expanded) StudioIcon.ChevronUp else StudioIcon.ChevronDown,
                tint = Studio.colors.inkSecondary,
                modifier = Modifier.rotate(if (motion.reduced) 0f else rotation),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = motion.tweenOf(motion.stateMediumMs))
                .heightIn(max = if (expanded) maxHeight else 0.dp)
                .clip(RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md)),
        ) {
            if (expanded) {
                if (tasks.size > 20) {
                    Text(
                        text = "共 ${tasks.size} 项，可向上滑动",
                        style = Studio.text.labelM,
                        color = Studio.colors.inkTertiary,
                        modifier = Modifier.padding(vertical = Space.s),
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    items(tasks, key = { it.taskId }) { task ->
                        StudioTaskCard(
                            title = task.title,
                            note = task.note,
                            art = ArtAsset.forTask(task.kind == TaskKind.DAILY, true),
                            completed = true,
                            kindLabel = if (task.kind == TaskKind.DAILY) "日常任务" else "临时任务",
                            onOpen = {
                                if (task.kind == TaskKind.DAILY) {
                                    viewModel.setDailyCompletion(task.taskId, state.today, false)
                                } else {
                                    viewModel.setTemporaryCompletion(task.taskId, false)
                                }
                            },
                            onToggleComplete = {
                                if (task.kind == TaskKind.DAILY) {
                                    viewModel.setDailyCompletion(task.taskId, state.today, false)
                                } else {
                                    viewModel.setTemporaryCompletion(task.taskId, false)
                                }
                            },
                            menuActions = listOf(
                                "编辑标题与备注" to { onEdit(task) },
                                "归档" to { viewModel.archiveTask(task.taskId) },
                            ),
                            accessibilityActions = listOf(
                                CustomAccessibilityAction("撤销完成") {
                                    if (task.kind == TaskKind.DAILY) {
                                        viewModel.setDailyCompletion(task.taskId, state.today, false)
                                    } else {
                                        viewModel.setTemporaryCompletion(task.taskId, false)
                                    }
                                    true
                                },
                                CustomAccessibilityAction("归档") { viewModel.archiveTask(task.taskId); true },
                            ),
                        )
                    }
                }
            }
        }
    }
}
