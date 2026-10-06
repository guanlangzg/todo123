package app.arttodo.ui.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.arttodo.core.LedgerRow
import app.arttodo.core.LedgerRowTitle
import app.arttodo.core.TaskRecord
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalSnackbarHostState
import app.arttodo.ui.common.ArtAsset
import app.arttodo.ui.common.ArtFrameKind
import app.arttodo.ui.common.BannerKind
import app.arttodo.ui.common.CalendarMath
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.HeatLegend
import app.arttodo.ui.common.HeatStep
import app.arttodo.ui.common.MonoTabular
import app.arttodo.ui.common.StudioArt
import app.arttodo.ui.common.StudioBanner
import app.arttodo.ui.common.StudioIconButton
import app.arttodo.ui.common.StudioSecondaryButton
import app.arttodo.ui.common.StudioTextField
import app.arttodo.ui.common.heatColor
import app.arttodo.ui.common.heatStepOf
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.Space
import kotlinx.coroutines.launch

/** Scroll handle for the records list, used by the layout assertions. */
const val RECORD_LIST_TAG = "record_list"

/** Handle for the month grid, so a layout assertion does not have to depend on the current date. */
const val CALENDAR_GRID_TAG = "calendar_grid"

/**
 * The records screen: month calendar, per-task detail, manual entry and single-row deletion.
 *
 * Three structural decisions come from 关键页面说明 section 3:
 *
 * 1. A calendar cell is a **date number above a colour block**, never a number on a colour block: at
 *    14sp no text colour clears 4.5:1 on heat steps 2–4 (the token gate asserts those seven pairs), so
 *    the digits sit on plain canvas and the block carries only the step.
 * 2. Investment and completion are separate channels: the block shows investment, a corner dot shows
 *    that the day had a daily completion. Neither stands in for the other.
 * 3. The page uses a bottom action row rather than a floating button, which would permanently cover
 *    the scrolling text.
 *
 * Totals are the core's: the day header and the calendar blocks both read `dayTotals`, which is
 * `projectDayTotals` over the same ledger the insight screen aggregates (AC-12 / N10).
 */
@Composable
fun RecordScreen(modifier: Modifier = Modifier) {
    val state = LocalAppState.current
    val viewModel = LocalAppViewModel.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val today = state.today
    var selected by remember(today) { mutableStateOf(today) }
    var year by remember(today) { mutableIntStateOf(Format.parseIso(today)?.year ?: 2026) }
    var month by remember(today) { mutableIntStateOf(Format.parseIso(today)?.monthValue ?: 1) }

    var dayRows by remember(selected, state.revision) { mutableStateOf<List<LedgerRow>>(emptyList()) }
    var dayRowsLoaded by remember(selected) { mutableStateOf(false) }
    var rowTitles by remember(selected, state.revision) { mutableStateOf<Map<Long, LedgerRowTitle>>(emptyMap()) }
    var addSheetVisible by remember { mutableStateOf(false) }
    var pickDayVisible by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<LedgerRow?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerRow?>(null) }
    var deletePreview by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(selected, state.revision) {
        dayRowsLoaded = false
        dayRows = viewModel.ledgerForDay(selected)
        // The name each row was recorded under comes from the core, not from the task list: an
        // automatic slice keeps the title it was invested under, so a rename cannot rewrite the past
        // (AC-14, 关键页面说明 section 3.2 「长标题（历史名称）」).
        rowTitles = viewModel.ledgerRowTitles(dayRows)
        dayRowsLoaded = true
    }

    val monthTotals = remember(year, month, state.dayTotals) {
        state.dayTotals
            .filter { it.appDate.startsWith("%04d-%02d".format(year, month)) }
            .associate { it.appDate to it.seconds }
    }
    val completedDates = remember(state.occurrenceIds, state.occurrenceCompletion) {
        state.occurrenceIds
            .filterValues { state.occurrenceCompletion[it] == true }
            .keys.map { it.second }
            .toSet()
    }
    val monthHasAnyRecord = monthTotals.isNotEmpty() || state.ledger.any {
        !it.isDeleted && it.appDate.startsWith("%04d-%02d".format(year, month))
    }
    val liveRows = dayRows.filter { !it.isDeleted }
    var replayedTaskTotals by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    LaunchedEffect(selected, dayRows, state.tasks, dayRowsLoaded) {
        replayedTaskTotals = if (dayRowsLoaded) {
            // Every task that has rows on this day must be projected, not only the ones the today list
            // shows: an archived task keeps its history but is absent from `state.tasks`, and a task
            // with no projection would silently drop its minutes from the day total below.
            val taskIds = (state.tasks.map { it.taskId } + dayRows.map { it.taskId }).distinct()
            taskIds.associateWith { taskId ->
                val rows = dayRows.filter { !it.isDeleted && it.taskId == taskId }
                viewModel.replayDailyTotal(taskId, selected, rows)
            }
        } else emptyMap()
    }
    val taskTotals = replayedTaskTotals
    val dayTotal = remember(taskTotals) { taskTotals.values.filterNotNull().sum() }
    var setTotalTaskId by remember { mutableStateOf(state.tasks.firstOrNull()?.taskId) }
    var setTotalMinutes by remember { mutableStateOf("0") }
    var setTotalVisible by remember { mutableStateOf(false) }
    val selectedTaskTotal = taskTotals[setTotalTaskId] ?: 0L
    val setTotalParsed = setTotalMinutes.toLongOrNull()
    val proposedDayTotal = dayTotal - selectedTaskTotal + (setTotalParsed ?: 0L) * 60L

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Space.pageMargin)
                // A stable handle for scrolling assertions in tests; the list is the only scrollable
                // surface on this screen, so the tag names that fact rather than a visual.
                .testTag(RECORD_LIST_TAG),
            contentPadding = PaddingValues(top = Space.m, bottom = 96.dp),
        ) {
            item(key = "month") {
                MonthSwitcher(
                    label = Format.monthLabel(year, month),
                    onPrev = {
                        val (y, m) = CalendarMath.plusMonths(year, month, -1)
                        year = y
                        month = m
                    },
                    onNext = {
                        val (y, m) = CalendarMath.plusMonths(year, month, 1)
                        year = y
                        month = m
                    },
                    onPickDate = { pickDayVisible = true },
                )
            }
            item(key = "grid") {
                CalendarGrid(
                    year = year,
                    month = month,
                    today = today,
                    selected = selected,
                    totals = monthTotals,
                    completedDates = completedDates,
                    recordedDates = state.ledger.filterNot { it.isDeleted }.map { it.appDate }.toSet() + monthTotals.keys,
                    onSelect = { selected = it },
                )
            }
            item(key = "legend") {
                Spacer(Modifier.height(Space.m))
                // On canvas, not on surfaceTint: 4.64:1 versus 4.32:1 (关键页面说明 section 3.3).
                HeatLegend(includeCompletionMark = true, includeToday = true)
            }

            if (!monthHasAnyRecord && completedDates.none { it.startsWith("%04d-%02d".format(year, month)) }) {
                item(key = "month_empty") {
                    Spacer(Modifier.height(Space.xxl))
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StudioArt(
                            asset = ArtAsset.EmptyRecords,
                            frame = ArtFrameKind.Panel,
                            cornerRadius = Radius.xl,
                        )
                        Spacer(Modifier.height(Space.xxl))
                        Text("这个月还没有记录", style = Studio.text.headlineL, color = Studio.colors.ink)
                        Spacer(Modifier.height(Space.s))
                        Text(
                            text = "开始一段专注，或者补记已经投入的时间。",
                            style = Studio.text.bodyL,
                            color = Studio.colors.inkSecondary,
                        )
                        Spacer(Modifier.height(Space.xxl))
                        StudioSecondaryButton(
                            text = "补记投入",
                            onClick = { addSheetVisible = true },
                            height = 56.dp,
                        )
                    }
                }
            }

            item(key = "day_header") {
                Spacer(Modifier.height(Space.xxl))
                SelectedDayHeader(
                    date = selected,
                    totalSeconds = dayTotal,
                    taskCount = liveRows.map { it.taskId }.distinct().size,
                    segmentCount = liveRows.size,
                )
            }

            item(key = "set_total") {
                Spacer(Modifier.height(Space.m))
                StudioSecondaryButton(
                    text = "修正指定任务当日总量",
                    onClick = {
                        setTotalTaskId = state.tasks.firstOrNull()?.taskId
                        setTotalMinutes = "${((taskTotals[setTotalTaskId] ?: 0L) / 60L)}"
                        setTotalVisible = true
                    },
                    height = 56.dp,
                    color = Studio.colors.accentDeep,
                )
            }

            if (dayTotal > 24 * 3600) {
                item(key = "quality") {
                    Spacer(Modifier.height(Space.m))
                    StudioBanner(
                        text = "该日总投入 ${Format.minutes(dayTotal)}，超过自然日长度，请检查原记录。",
                        kind = BannerKind.Warn,
                    )
                }
            }

            if (liveRows.isEmpty()) {
                item(key = "day_empty") {
                    Spacer(Modifier.height(Space.l))
                    Column {
                        Text("这天没有记录", style = Studio.text.titleM, color = Studio.colors.ink)
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = "可以补记一段投入。",
                            style = Studio.text.bodyM,
                            color = Studio.colors.inkSecondary,
                        )
                        Spacer(Modifier.height(Space.m))
                        StudioSecondaryButton(
                            text = "为这天补记",
                            onClick = { addSheetVisible = true },
                            height = 48.dp,
                        )
                    }
                }
            } else {
                val grouped = liveRows.groupBy { it.taskId }
                grouped.forEach { (taskId, rows) ->
                    item(key = "task_$taskId") {
                        Spacer(Modifier.height(Space.l))
                        LedgerTaskGroup(
                            taskId = taskId,
                            // Names come from the page's own lookup so an archived task's history
                            // reads as its title rather than its raw id.
                            taskTitle = state.titleOf(taskId),
                            // The core's projection for this task-day, never a local sum of the rows:
                            // a set-total row replaces the earlier ones, so adding deltas hides it.
                            taskTotalSeconds = taskTotals[taskId],
                            rowTitles = rowTitles,
                            rows = rows,
                            onEdit = { editingRow = it },
                            onDelete = { row ->
                                pendingDelete = row
                                deletePreview = null
                                scope.launch {
                                    // The preview is what the core would produce without this row, so the
                                    // dialog states the real consequence rather than a guess (AC-13).
                                    deletePreview = viewModel.totalWithout(
                                        viewModel.ledgerForDay(selected),
                                        row.ledgerSeq,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item(key = "add") {
                Spacer(Modifier.height(Space.xxl))
                StudioSecondaryButton(
                    text = "补记投入",
                    onClick = { addSheetVisible = true },
                    height = 56.dp,
                    color = Studio.colors.accentDeep,
                )
                Spacer(Modifier.height(Space.m))
            }
        }
    }

    if (setTotalVisible) {
        SetTaskTotalDialog(
            tasks = state.tasks,
            selectedTaskId = setTotalTaskId,
            selectedMinutes = setTotalMinutes,
            currentDayTotalSeconds = dayTotal,
            selectedTaskTotalSeconds = selectedTaskTotal,
            resultingDayTotalSeconds = proposedDayTotal,
            onTaskSelected = {
                setTotalTaskId = it
                setTotalMinutes = "${((taskTotals[it] ?: 0L) / 60L)}"
            },
            onMinutesChanged = { setTotalMinutes = it },
            onDismiss = { setTotalVisible = false },
            onConfirm = {
                val taskId = setTotalTaskId ?: return@SetTaskTotalDialog
                viewModel.setDailyTotal(taskId, selected, (setTotalParsed ?: 0) * 60L)
                setTotalVisible = false
            },
        )
    }

    if (addSheetVisible) {
        AddLedgerSheet(
            appDate = selected,
            tasks = state.tasks,
            currentTotalSeconds = dayTotal,
            taskTotals = taskTotals.mapValues { it.value ?: 0L },
            onDismiss = { addSheetVisible = false },
            onSubmit = { taskId, asSetTotal, seconds ->
                if (asSetTotal) {
                    viewModel.setDailyTotal(taskId, selected, seconds)
                } else {
                    viewModel.addManualSeconds(taskId, selected, seconds)
                }
                addSheetVisible = false
            },
        )
    }

    if (pickDayVisible) {
        DayPickerDialog(
            year = year,
            month = month,
            today = today,
            selected = selected,
            recordedDates = state.ledger.filterNot { it.isDeleted }.map { it.appDate }.toSet(),
            onSelect = { date ->
                selected = date
                pickDayVisible = false
            },
            onDismiss = { pickDayVisible = false },
        )
    }

    pendingDelete?.let { row ->
        DeleteRowDialog(
            row = row,
            // The dialog names the task the row belongs to, archived or not.
            taskTitle = state.titleOf(row.taskId),
            currentTotal = dayTotal,
            resultingTotal = deletePreview,
            onConfirm = {
                val seq = row.ledgerSeq
                pendingDelete = null
                viewModel.deleteLedgerEntry(seq) { failure ->
                    if (failure != null) return@deleteLedgerEntry
                    scope.launch {
                        val result = snackbar.showSnackbar(
                            message = "已删除 1 条记录",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            // Undo is offered for this session only, and it restores the row in place so
                            // the day returns to its previous total (AppViewModel.restoreLedgerEntry).
                            viewModel.restoreLedgerEntry(seq)
                        }
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    editingRow?.let { row ->
        EditLedgerDialog(
            row = row,
            onDismiss = { editingRow = null },
            onConfirm = { newSeconds ->
                viewModel.editLedgerEntry(
                    ledgerSeq = row.ledgerSeq,
                    // Exactly one of the two must be set and it must match the row's kind, or the core
                    // returns a ledger invariant violation (架构契约 §4.3.2).
                    newDeltaSeconds = if (row.kind == 1) newSeconds else null,
                    newSetTotalSeconds = if (row.kind == 2) newSeconds else null,
                )
                editingRow = null
            },
        )
    }
}

/** Month switch: 48dp targets and a capped label so it never breaks mid-word. */
@Composable
internal fun MonthSwitcher(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioIconButton(
            icon = StudioIcon.ChevronUp,
            contentDescription = "上一个月",
            tint = Studio.colors.ink,
            onClick = onPrev,
        )
        Text(
            text = label,
            style = Studio.text.displayM,
            color = Studio.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(role = Role.Button, onClick = onPickDate)
                // After `clickable`: the action description must survive the node it installs.
                .semantics {
                    heading()
                    contentDescription = "$label，选择日期"
                },
        )
        StudioIconButton(
            icon = StudioIcon.ChevronDown,
            contentDescription = "下一个月",
            tint = Studio.colors.ink,
            onClick = onNext,
        )
    }
}

/**
 * The month grid.
 *
 * Seven fixed columns; the cell grows with the font scale up to a 64dp cap so the numbers stay legible
 * without breaking the columns or overflowing horizontally. Each cell is its own accessibility node
 * with the date, the investment and the completion fact in its description.
 */
@Composable
internal fun CalendarGrid(
    year: Int,
    month: Int,
    today: String,
    selected: String,
    totals: Map<String, Long>,
    completedDates: Set<String>,
    recordedDates: Set<String>,
    onSelect: (String) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val cellHeight = (44f * fontScale.coerceIn(1f, 1.45f)).dp
    val first = CalendarMath.firstOfMonth(year, month)
    val leading = first.dayOfWeek.value - 1
    val days = CalendarMath.monthDates(year, month)
    val weeks = buildList {
        var week = MutableList(7) { "" }
        var index = leading
        days.forEach { date ->
            week[index] = date
            index++
            if (index == 7) {
                add(week)
                week = MutableList(7) { "" }
                index = 0
            }
        }
        if (week.any { it.isNotEmpty() }) add(week)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .testTag(CALENDAR_GRID_TAG)
            // The container states what the grid is and how to move in it, while each cell keeps its
            // own node below (动效与无障碍说明 section 6.2).
            .semantics { contentDescription = "日历，$year 年 $month 月，可上下左右滑动选择日期" },
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(
                    text = weekday,
                    style = Studio.text.labelM,
                    color = Studio.colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date.isEmpty()) {
                        Box(Modifier.weight(1f).height(cellHeight))
                    } else {
                        CalendarCell(
                            date = date,
                            today = today,
                            selected = selected,
                            totalSeconds = totals[date] ?: 0,
                            hasRecord = date in recordedDates || date in completedDates,
                            hasCompletion = completedDates.contains(date),
                            isFuture = date > today,
                            height = cellHeight,
                            modifier = Modifier.weight(1f),
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(
    date: String,
    today: String,
    selected: String,
    totalSeconds: Long,
    hasRecord: Boolean,
    hasCompletion: Boolean,
    isFuture: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val dayNumber = Format.parseIso(date)?.dayOfMonth ?: 0
    val step = if (isFuture) HeatStep.None else heatStepOf(totalSeconds, hasRecord)
    val spoken = buildString {
        append(Format.spokenDate(date))
        append("，")
        append(
            when {
                isFuture -> "不可用"
                step == HeatStep.None -> "无记录"
                totalSeconds == 0L -> "投入 0 分钟"
                else -> "投入 ${Format.minutes(totalSeconds)}"
            },
        )
        if (hasCompletion) append("，有完成记录")
    }

    Column(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = !isFuture, role = Role.Button) { onSelect(date) }
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                this.selected = date == selected
                if (date == today) stateDescription = "今天"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Digits sit on plain canvas; the block below carries the step and never holds text.
        Text(
            text = "$dayNumber",
            style = Studio.text.bodyM,
            color = if (isFuture) Studio.colors.inkTertiary.copy(alpha = 0.38f) else Studio.colors.ink,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.xs)
                .height((14f * (height.value / 44f).coerceIn(1f, 1.45f)).dp)
                .border(
                    width = if (date == today) 2.dp else 0.dp,
                    color = if (date == today) Studio.colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(Radius.xs),
                ),
        ) {
            HeatBlock(step = step, hasCompletion = hasCompletion)
        }
    }
}

/** The colour block: filled for a step, dashed for "no records", empty for a future date. */
@Composable
private fun HeatBlock(step: HeatStep, hasCompletion: Boolean) {
    val colors = Studio.colors
    val dash = colors.heatDash
    Box(Modifier.fillMaxSize()) {
        if (step != HeatStep.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(colors.heatColor(step)),
            )
        }
        // Dashed outline is the non-colour cue separating "no records" from a zero-value day; it is
        // the same cue the heat map uses (设计系统 section 2.5).
        if (step == HeatStep.None) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(2f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
                    ),
                )
            }
        }
        if (hasCompletion) {
            val dot = colors.doneInk
            Canvas(Modifier.fillMaxSize()) {
                val radius = 3.dp.toPx()
                drawCircle(color = dot, radius = radius, center = Offset(size.width - radius, radius))
            }
        }
    }
}

@Composable
private fun SelectedDayHeader(
    date: String,
    totalSeconds: Long,
    taskCount: Int,
    segmentCount: Int,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(Studio.colors.surfaceCard)
            .padding(Space.l),
    ) {
        Text(
            text = Format.dateLabel(date),
            style = Studio.text.headlineS,
            color = Studio.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.s))
        Text(
            text = Format.ledgerClock(totalSeconds),
            style = Studio.text.displayXL.merge(MonoTabular),
            color = Studio.colors.ink,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "当日总投入 ${Format.spokenDuration(totalSeconds)}"
            },
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = "$taskCount 个任务 · $segmentCount 段记录",
            style = Studio.text.bodyM,
            color = Studio.colors.inkSecondary,
        )
    }
}

@Composable
private fun LedgerTaskGroup(
    taskId: String,
    taskTitle: String,
    /** The core's projection for this task-day; null means it has not arrived (or failed) yet. */
    taskTotalSeconds: Long?,
    /** Per-row recorded names, keyed by `ledgerSeq`; a missing entry means "no name established". */
    rowTitles: Map<Long, LedgerRowTitle>,
    rows: List<LedgerRow>,
    onEdit: (LedgerRow) -> Unit,
    onDelete: (LedgerRow) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The whole title is kept even when it wraps to two lines: a truncated name cannot be
                // matched against the duration beside it.
                text = taskTitle,
                style = Studio.text.titleM,
                color = Studio.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (taskTotalSeconds != null) {
                Text(
                    text = Format.minutes(taskTotalSeconds),
                    style = Studio.text.bodyM,
                    color = Studio.colors.inkSecondary,
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        rows.forEach { row ->
            LedgerRowItem(
                row = row,
                recordedTitle = rowTitles[row.ledgerSeq],
                currentTitle = taskTitle,
                onEdit = { onEdit(row) },
                onDelete = { onDelete(row) },
            )
            Spacer(Modifier.height(Space.xs))
        }
    }
}

/**
 * One ledger row with its source label; tapping it opens the editor, the menu deletes.
 *
 * An automatic slice shows the name it was recorded under, with 「当前名称：…」 added above it when the
 * task has been renamed since — the past is never overwritten (AC-14, 关键页面说明 section 3.2). A
 * manual add or a set-total row has no span of time of its own, so it keeps the kind label and shows no
 * past name at all: the core only hands out a snapshot where one exists, and this row must not invent
 * one.
 */
@Composable
private fun LedgerRowItem(
    row: LedgerRow,
    recordedTitle: LedgerRowTitle?,
    currentTitle: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val seconds = row.setTotalSeconds ?: row.deltaSeconds ?: 0
    val label = when (row.kind) {
        0 -> "自动计时"
        1 -> "手动增加"
        else -> "设为当日总量"
    }
    val source = when (row.kind) {
        0 -> "自动"
        1 -> "手动"
        else -> "修正"
    }
    val snapshot = recordedTitle?.takeIf { it.isTitleSnapshot }?.title?.takeIf { it.isNotBlank() }
    val renamed = snapshot != null && snapshot != currentTitle
    val sourceLine = if (row.editedAtMs != null) "已修改 · 来源：$source" else "来源：$source"

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(Studio.colors.surfaceCard)
            .clickable(role = Role.Button, onClick = onEdit)
            // After `clickable`: the merged description must survive the node it installs.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    if (snapshot != null) {
                        append("当时名称：").append(snapshot).append('，')
                        if (renamed) append("当前名称：").append(currentTitle).append('，')
                    }
                    append(label).append('，').append(Format.spokenDuration(seconds))
                    append("，来源：").append(source)
                }
                role = Role.Button
            }
            .padding(start = Space.m, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (renamed) {
                // 正上方: the correspondence to today's task, without replacing the old name.
                Text(
                    text = "当前名称：$currentTitle",
                    style = Studio.text.labelM,
                    color = Studio.colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                // The recorded name takes the row's title line; without one the kind label stays where
                // it has always been, so a row with no history is unchanged.
                text = snapshot ?: label,
                style = Studio.text.bodyL,
                color = Studio.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (snapshot == null) sourceLine else "$label · $sourceLine",
                style = Studio.text.labelM,
                color = Studio.colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Format.clock(seconds),
            style = Studio.text.titleM.merge(MonoTabular),
            color = Studio.colors.ink,
        )
        StudioIconButton(
            icon = StudioIcon.More,
            contentDescription = "删除这一条记录",
            tint = Studio.colors.inkSecondary,
            onClick = onDelete,
        )
    }
}

@Composable
private fun SetTaskTotalDialog(
    tasks: List<TaskRecord>,
    selectedTaskId: String?,
    selectedMinutes: String,
    currentDayTotalSeconds: Long,
    selectedTaskTotalSeconds: Long,
    resultingDayTotalSeconds: Long,
    onTaskSelected: (String) -> Unit,
    onMinutesChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val selectedTask = tasks.firstOrNull { it.taskId == selectedTaskId }
    val parsed = selectedMinutes.toLongOrNull()
    val valid = selectedTask != null && parsed != null && parsed in 0..1440
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("修正指定任务当日总量", style = Studio.text.headlineS, color = Studio.colors.ink)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("操作前 ${Format.minutes(currentDayTotalSeconds)}", style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
                if (tasks.isEmpty()) {
                    Text("还没有任务可以修正。", style = Studio.text.bodyL, color = Studio.colors.ink)
                } else {
                    Text("选择任务", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        tasks.forEach { task ->
                            StudioSecondaryButton(
                                text = task.title,
                                onClick = { onTaskSelected(task.taskId) },
                                enabled = task.taskId != selectedTaskId,
                                height = 48.dp,
                            )
                        }
                    }
                    StudioTextField(
                        value = selectedMinutes,
                        onValueChange = { raw -> onMinutesChanged(raw.filter(Char::isDigit).take(4)) },
                        label = "该任务投入总量（分钟）",
                        numeric = true,
                        error = if (!valid) "请输入 0–1440 之间的整数分钟" else null,
                    )
                    Text(
                        "该任务当前 ${Format.minutes(selectedTaskTotalSeconds)} → 修正后 ${Format.minutes((parsed ?: 0L) * 60L)}。",
                        style = Studio.text.bodyM,
                        color = Studio.colors.inkSecondary,
                    )
                    Text(
                        "全日合计：${Format.minutes(currentDayTotalSeconds)} → 操作后 ${Format.minutes(resultingDayTotalSeconds)}。",
                        style = Studio.text.bodyM,
                        color = Studio.colors.ink,
                    )
                    Text("之后新增投入会在修正值之上继续累加。", style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = onConfirm) {
                Text("保存修正", style = Studio.text.labelL, color = if (valid) Studio.colors.accentDeep else Studio.colors.inkTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", style = Studio.text.labelL, color = Studio.colors.inkSecondary) }
        },
        containerColor = Studio.colors.surfaceRaised,
    )
}

@Composable
private fun DeleteRowDialog(
    row: LedgerRow,
    taskTitle: String,
    currentTotal: Long,
    resultingTotal: Long?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = if (resultingTotal != null) {
        "将影响 ${Format.dateLabel(row.appDate)} 对「$taskTitle」的合计：" +
            "${Format.minutes(currentTotal)} → ${Format.minutes(resultingTotal)}。" +
            "原始自动记录仍保留在审计数据中。"
    } else {
        "将影响 ${Format.dateLabel(row.appDate)} 对「$taskTitle」的合计。原始自动记录仍保留在审计数据中。"
    }
    val extra = if (row.kind == 2) "该日有一条总量修正，删除后合计会按剩余记录重放。" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除这条记录？",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(body, style = Studio.text.bodyL, color = Studio.colors.ink)
                if (extra != null) {
                    Text(extra, style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
                }
            }
        },
        confirmButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Studio.colors.dangerInk)
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = Space.l, vertical = Space.m),
            ) {
                Text("删除", style = Studio.text.labelL, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = Studio.text.labelL, color = Studio.colors.inkSecondary)
            }
        },
        containerColor = Studio.colors.surfaceRaised,
    )
}

/** Editing changes the value in place and keeps the row's ordering slot. */
@Composable
private fun EditLedgerDialog(
    row: LedgerRow,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val currentMinutes = (row.setTotalSeconds ?: row.deltaSeconds ?: 0) / 60
    var input by remember(row.ledgerSeq) { mutableStateOf(currentMinutes.toString()) }
    val parsed = input.toLongOrNull()
    val valid = parsed != null && parsed in 0..1440
    val label = if (row.kind == 2) "设为当日总量（分钟）" else "时长（分钟）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (row.kind == 2) "修改总量修正" else "修改这条记录",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                StudioTextField(
                    value = input,
                    onValueChange = { raw -> input = raw.filter { it.isDigit() }.take(4) },
                    label = label,
                    numeric = true,
                    error = if (!valid) "请输入 0–1440 之间的整数分钟" else null,
                )
                Text(
                    text = if (row.kind == 2) {
                        "修改后该日按新的总量重放，之后的投入会在此基础上继续累加。"
                    } else {
                        "修改是原地改值，保留这条记录原有的顺序位置，并留下修改审计。"
                    },
                    style = Studio.text.bodyM,
                    color = Studio.colors.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm((parsed ?: 0) * 60) }) {
                Text("保存", style = Studio.text.labelL, color = Studio.colors.accentDeep)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = Studio.text.labelL, color = Studio.colors.inkSecondary)
            }
        },
        containerColor = Studio.colors.surfaceRaised,
    )
}

/**
 * The date list behind the month label.
 *
 * 关键页面说明 section 7 records the calendar cell as a known deviation: seven columns on a 360dp screen
 * cap a cell at ~44dp, below the 48dp floor. The documented compensation is this secondary entry, where
 * every date is a real button on a row of at least 56dp, so a date can be chosen without aiming at a
 * small cell.
 */
@Composable
private fun DayPickerDialog(
    year: Int,
    month: Int,
    today: String,
    selected: String,
    recordedDates: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dates = remember(year, month) { CalendarMath.monthDates(year, month) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Studio.colors.surfaceRaised)
                .padding(Space.l),
        ) {
            Text(
                text = "选择日期 · ${Format.monthLabel(year, month)}",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.m))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                items(dates, key = { it }) { date ->
                    DayPickerRow(
                        date = date,
                        today = today,
                        selected = selected,
                        hasRecord = date in recordedDates,
                        onSelect = onSelect,
                    )
                }
            }
            Spacer(Modifier.height(Space.m))
            StudioSecondaryButton(text = "关闭", onClick = onDismiss, height = 48.dp)
        }
    }
}

/** One date in the picker: at least 56dp tall, with the day's meaning stated in words. */
@Composable
private fun DayPickerRow(
    date: String,
    today: String,
    selected: String,
    hasRecord: Boolean,
    onSelect: (String) -> Unit,
) {
    val future = date > today
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (date == selected) Studio.colors.accentTint else Studio.colors.surfaceCard)
            .clickable(enabled = !future, role = Role.Button) { onSelect(date) }
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(Format.spokenDate(date))
                    if (date == today) append("，今天")
                    if (date == selected) append("，已选中")
                    if (future) append("，不可用") else if (hasRecord) append("，有记录")
                }
            }
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Format.dateLabel(date),
            style = Studio.text.bodyL,
            color = if (future) Studio.colors.inkTertiary.copy(alpha = 0.38f) else Studio.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            date == selected -> Text("已选", style = Studio.text.labelM, color = Studio.colors.accentDeep)
            date == today -> Text("今天", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
            hasRecord -> Text("有记录", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        }
    }
}
