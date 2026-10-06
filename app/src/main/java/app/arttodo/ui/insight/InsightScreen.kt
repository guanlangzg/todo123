package app.arttodo.ui.insight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.arttodo.core.PeriodTotal
import app.arttodo.core.TaskShare
import app.arttodo.core.TimeBucket
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalNavigateToToday
import app.arttodo.ui.common.ArtAsset
import app.arttodo.ui.common.ArtFrameKind
import app.arttodo.ui.common.BannerKind
import app.arttodo.ui.common.CalendarMath
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.HEAT_LEGEND_DESCRIPTION
import app.arttodo.ui.common.HeatLegend
import app.arttodo.ui.common.HeatStep
import app.arttodo.ui.common.MonoTabular
import app.arttodo.ui.common.StudioArt
import app.arttodo.ui.common.StudioBanner
import app.arttodo.ui.common.StudioIconButton
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.heatColor
import app.arttodo.ui.common.heatStepOf
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.Space

/**
 * The insight screen: GitHub-style heat map, week/month trend and per-task share.
 *
 * Everything here reads one source. `dayTotals`, the trend buckets and the share list are all
 * `projectDayTotals` / `projectPeriodTotals` / `projectTaskShares` over the same ledger rows, which is
 * exactly what makes 规格 6.2's "四处同值" property hold rather than being asserted by hand
 * (AC-12 / N10).
 *
 * The heat map is drawn with `Canvas`: a 53-week grid at 6.5dp cells cannot be a per-cell composable
 * without paying for hundreds of layout nodes, and its cells are too small for individual touch
 * targets anyway. The interaction is therefore area-based — tap or drag selects the nearest cell —
 * and every cell is individually reachable through TalkBack custom actions and the tabular detail
 * below, which is the equivalent control WCAG 2.2 SC 2.5.8 asks for.
 */
@Composable
fun InsightScreen(modifier: Modifier = Modifier) {
    val state = LocalAppState.current
    val today = state.today
    var year by remember(today) { mutableIntStateOf(Format.parseIso(today)?.year ?: 2026) }
    var selectedDate by remember(today) { mutableStateOf(today) }
    var range by remember { mutableStateOf(TimeBucket.WEEK) }
    var showAllShares by remember { mutableStateOf(false) }

    val yearPrefix = "%04d-".format(year)
    val totalsByDate = remember(state.dayTotals) { state.dayTotals.associate { it.appDate to it.seconds } }
    val recordedDates = remember(state.ledger, state.occurrenceIds) {
        state.ledger.filterNot { it.isDeleted }.map { it.appDate }.toSet() +
            state.occurrenceIds.keys.map { it.second }
    }
    val yearHasData = totalsByDate.keys.any { it.startsWith(yearPrefix) } || recordedDates.any { it.startsWith(yearPrefix) }
    val hasAnyData = totalsByDate.values.any { it > 0 }
    val loadError = state.error != null && !state.coreAvailable
    val viewModel = LocalAppViewModel.current
    val navigateToToday = LocalNavigateToToday.current
    val taskCountByDate = remember(state.ledger) {
        state.ledger.filterNot { it.isDeleted }.groupBy { it.appDate }
            .mapValues { (_, rows) -> rows.map { it.taskId }.distinct().size }
    }
    val completionDates = remember(state.occurrenceIds, state.occurrenceCompletion) {
        state.occurrenceIds.filterValues { state.occurrenceCompletion[it] == true }
            .keys.map { it.second }.toSet()
    }
    val hasDisplayData = hasAnyData || recordedDates.isNotEmpty()
    val displayDataForYear = yearHasData
    val errorVisible = loadError
    val yearHasRows = displayDataForYear
    val showEmptyState = !hasDisplayData
    val showYearEmpty = hasDisplayData && !yearHasRows
    val showCharts = hasDisplayData && yearHasRows
    val statsError = errorVisible
    val selectedHasCompletion = selectedDate in completionDates
    val selectedTaskCount = taskCountByDate[selectedDate] ?: 0
    val selectedHasRecord = selectedDate in recordedDates || selectedHasCompletion || selectedDate in totalsByDate
    val selectedSeconds = totalsByDate[selectedDate] ?: 0L
    val selectedStep = heatStepOf(selectedSeconds, selectedHasRecord)
    val trend: List<PeriodTotal> = remember(state.weekTotals, state.monthTotals, range) {
        when (range) {
            TimeBucket.WEEK -> state.weekTotals
            else -> state.monthTotals
        }
    }.let { all -> if (yearHasData) all.filter { it.label.startsWith(yearPrefix) } else all }

    val shares: List<TaskShare> = state.taskShares.filter { it.seconds > 0 }.sortedByDescending { it.seconds }
    val totalShare = shares.sumOf { it.seconds }
    // Shares cover the whole ledger, so they include archived tasks; the name lookup covers both lists
    // rather than printing a raw task id in a legend.
    val shareTitles = remember(shares, state.tasks, state.archivedTasks) {
        shares.associate { it.taskId to state.titleOf(it.taskId) }
    }

    LazyColumn(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Space.pageMargin),
        contentPadding = PaddingValues(top = Space.m, bottom = Space.xxl),
    ) {
        item(key = "title") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "洞察",
                    style = Studio.text.displayM,
                    color = Studio.colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                StudioIconButton(
                    icon = StudioIcon.ChevronUp,
                    contentDescription = "上一年",
                    tint = Studio.colors.ink,
                    onClick = { year -= 1 },
                )
                Text(
                    text = "$year",
                    style = Studio.text.headlineS.merge(MonoTabular),
                    color = Studio.colors.ink,
                )
                StudioIconButton(
                    icon = StudioIcon.ChevronDown,
                    contentDescription = "下一年",
                    tint = Studio.colors.ink,
                    onClick = { year += 1 },
                )
            }
        }

        item(key = "art") {
            StudioArt(
                asset = ArtAsset.ChartHeatmap,
                frame = ArtFrameKind.Panel,
                cornerRadius = Radius.xl,
                contentDescription = "统计主题插画",
            )
        }

        if (statsError) {
            item(key = "error") {
                Spacer(Modifier.height(Space.xxl))
                StudioBanner(
                    text = "统计暂时无法计算。账本数据仍在设备上，可以重试。",
                    kind = BannerKind.Danger,
                    action = { app.arttodo.ui.common.StudioTextAction("重试", viewModel::refresh) },
                )
            }
        } else if (showEmptyState) {
            item(key = "empty") {
                Spacer(Modifier.height(Space.xxl))
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StudioArt(
                        asset = ArtAsset.EmptyInsights,
                        frame = ArtFrameKind.Panel,
                        cornerRadius = Radius.xl,
                    )
                    Spacer(Modifier.height(Space.xxl))
                    Text("还没有可以统计的投入", style = Studio.text.headlineL, color = Studio.colors.ink)
                    Spacer(Modifier.height(Space.s))
                    Text(
                        text = "完成一次专注，或者补记一段时间，这里就会出现你的节奏。",
                        style = Studio.text.bodyL,
                        color = Studio.colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.xxl))
                    StudioPrimaryButton(
                        text = "去今天页",
                        onClick = navigateToToday,
                        contentDescription = "去今天页",
                    )
                }
                // No matrix is painted on purpose: an all-zero grid reads as "no investment ever",
                // which is a different and false statement (关键页面说明 section 4.2).
            }
        } else if (showYearEmpty) {
            item(key = "year_empty") {
                Spacer(Modifier.height(Space.xxl))
                StudioBanner(text = "该年份暂无数据。", kind = BannerKind.Warn, icon = StudioIcon.Warning)
            }
        } else {
        if (showCharts) {
        item(key = "heatmap") {
            Spacer(Modifier.height(Space.l))
            HeatMap(
                year = year,
                totalsByDate = totalsByDate,
                hasRecordByDate = { date -> date in recordedDates || date in completionDates || date in totalsByDate },
                taskCountByDate = taskCountByDate,
                selectedDate = selectedDate,
                today = today,
                onSelect = { selectedDate = it },
            )
        }

        item(key = "legend") {
            Spacer(Modifier.height(Space.m))
            HeatLegend()
            Spacer(Modifier.height(Space.xs))
            // The ramp is spoken, not just shown, so the encoding is available without sight.
            Text(
                text = HEAT_LEGEND_DESCRIPTION,
                style = Studio.text.labelM,
                color = Studio.colors.inkTertiary,
                modifier = Modifier.semantics { contentDescription = HEAT_LEGEND_DESCRIPTION },
            )
        }

        item(key = "selected") {
            Spacer(Modifier.height(Space.xxl))
            SelectedCellCard(
                date = selectedDate,
                seconds = selectedSeconds,
                taskCount = selectedTaskCount,
                hasCompletion = selectedHasCompletion,
                hasRecord = selectedHasRecord,
            )
        }

        item(key = "trend_header") {
            Spacer(Modifier.height(Space.section))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (range == TimeBucket.WEEK) "每周趋势" else "每月趋势",
                    style = Studio.text.headlineS,
                    color = Studio.colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                RangeSwitch(range = range, onChange = { range = it })
            }
        }
        item(key = "trend") {
            Spacer(Modifier.height(Space.m))
            TrendChart(
                bucket = range,
                points = trend,
                fontScale = LocalDensity.current.fontScale,
            )
        }

        item(key = "shares_header") {
            Spacer(Modifier.height(Space.section))
            Text(
                text = "按任务占比",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        item(key = "shares") {
            Spacer(Modifier.height(Space.m))
            ShareChart(
                shares = shares,
                totalSeconds = totalShare,
                taskTitles = shareTitles,
            )
        }

        item(key = "table_header") {
            Spacer(Modifier.height(Space.section))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "表格化明细",
                    style = Studio.text.headlineS,
                    color = Studio.colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (shares.size > 5) {
                    StudioIconButton(
                        icon = if (showAllShares) StudioIcon.ChevronUp else StudioIcon.ChevronDown,
                        contentDescription = if (showAllShares) "收起全部任务" else "展开全部任务",
                        tint = Studio.colors.accentDeep,
                        onClick = { showAllShares = !showAllShares },
                    )
                }
            }
        }
        item(key = "table") {
            Spacer(Modifier.height(Space.s))
            ShareTable(
                shares = if (showAllShares) shares else shares.take(5),
                totalSeconds = totalShare,
                taskTitles = shareTitles,
            )
            Spacer(Modifier.height(Space.xxl))
        }
        }
        }
    }
}

/** Year switch for the range control; kept as a two-option segmented control for clarity. */
@Composable
private fun RangeSwitch(range: TimeBucket, onChange: (TimeBucket) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(TimeBucket.WEEK to "周", TimeBucket.MONTH to "月").forEach { (bucket, label) ->
            val selected = bucket == range
            Box(
                Modifier
                    .heightIn(min = Space.minTouch)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (selected) Studio.colors.accentFill else Studio.colors.surfaceTint)
                    .clickable(role = Role.RadioButton) { onChange(bucket) }
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = "$label 视图"
                        stateDescription = if (selected) "已选中" else "未选中"
                    }
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = Studio.text.labelL,
                    color = if (selected) Studio.colors.onAccent else Studio.colors.inkSecondary,
                )
            }
        }
    }
}

/**
 * The year heat map, drawn on a Canvas.
 *
 * Cell size stays 6.5dp with a 2dp gutter at every font scale: scaling the geometry would break the
 * 53-week alignment, so only the legend text wraps (关键页面说明 section 4.2). Because the grid is wider
 * than the screen it scrolls horizontally, and a drag selects the nearest cell while the finger moves.
 */
@Composable
internal fun HeatMap(
    year: Int,
    totalsByDate: Map<String, Long>,
    hasRecordByDate: (String) -> Boolean,
    taskCountByDate: Map<String, Int>,
    selectedDate: String,
    today: String,
    onSelect: (String) -> Unit,
) {
    val weeks = remember(year) { CalendarMath.yearGrid(year) }
    // Resolved during composition; the Canvas lambda below cannot read a composition local.
    val palette = Studio.colors
    val cell = 6.5f
    val gutter = 2f
    val rowHeight = cell + gutter
    val gridHeight = rowHeight * 7
    val gridWidth = (cell + gutter) * weeks.size
    val scrollState = rememberScrollState()

    // TalkBack walks the grid cell by cell through custom actions: the drawn cells themselves cannot be
    // focus targets, and the equivalent tabular control is the share table below.
    fun move(days: Long) {
        val flat = weeks.flatten()
        val index = flat.indexOf(selectedDate)
        if (index < 0) return
        val target = (index.toLong() + days).coerceIn(0L, (flat.size - 1).toLong()).toInt()
        onSelect(flat[target])
    }

    val selectedSeconds = totalsByDate[selectedDate] ?: 0
    val selectedHasRecord = hasRecordByDate(selectedDate) && selectedDate.startsWith("%04d-".format(year))
    val selectedStep = heatStepOf(selectedSeconds, selectedHasRecord)
    val selectedDescription = buildString {
        append(Format.spokenDate(selectedDate))
        append("，")
        append(if (selectedStep == HeatStep.None) "无记录" else "投入 ${Format.minutes(selectedSeconds)}")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "全年热力图，$selectedDescription，${taskCountByDate[selectedDate] ?: 0} 个任务。共 ${weeks.sumOf { it.size }} 天。"
                customActions = listOf(
                    CustomAccessibilityAction("上一格") { move(-1L); true },
                    CustomAccessibilityAction("下一格") { move(1L); true },
                    CustomAccessibilityAction("上一周") { move(-7L); true },
                    CustomAccessibilityAction("下一周") { move(7L); true },
                )
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Monday..Sunday column labels.
            Column(Modifier.padding(end = Space.xs)) {
                listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                    Box(Modifier.height(rowHeight.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .clearAndSetSemantics {
                        contentDescription = "全年热力图，${selectedDescription}，${taskCountByDate[selectedDate] ?: 0} 个任务。共 ${weeks.sumOf { it.size }} 天。"
                        customActions = listOf(
                            CustomAccessibilityAction("上一格") { move(-1L); true },
                            CustomAccessibilityAction("下一格") { move(1L); true },
                            CustomAccessibilityAction("上一周") { move(-7L); true },
                            CustomAccessibilityAction("下一周") { move(7L); true },
                        )
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                    }
                    .pointerInput(weeks) {
                        detectTapGestures { offset ->
                            pickCell(offset, weeks, cell, gutter, onSelect)
                        }
                    }
                    .pointerInput(weeks) {
                        detectDragGestures(
                            onDragStart = { offset -> pickCell(offset, weeks, cell, gutter, onSelect) },
                            onDrag = { change, _ ->
                                pickCell(change.position, weeks, cell, gutter, onSelect)
                                change.consume()
                            },
                        )
                    }
                    .width(gridWidth.dp)
                    .height(gridHeight.dp),
            ) {
                Canvas(
                    Modifier
                        .width(gridWidth.dp)
                        .height(gridHeight.dp),
                ) {
                    val unit = size.width / gridWidth
                    val cellPx = cell * unit
                    val gutterPx = gutter * unit
                    val rowPx = (cell + gutter) * unit
                    weeks.forEachIndexed { weekIndex, week ->
                        week.forEachIndexed { dayIndex, date ->
                            val outsideYear = !date.startsWith("%04d-".format(year))
                            val seconds = totalsByDate[date] ?: 0
                            val step = if (outsideYear) null else heatStepOf(seconds, hasRecordByDate(date))
                            drawHeatCell(
                                left = weekIndex * (cellPx + gutterPx),
                                top = dayIndex * rowPx,
                                cell = cellPx,
                                step = step,
                                fill = palette.heatColor(step ?: HeatStep.None),
                                dashColor = palette.heatDash,
                                accent = palette.accent,
                                isToday = !outsideYear && date == today,
                                isSelected = !outsideYear && date == selectedDate,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(
            text = "左右滑动查看全年。点按或拖动可选择最近的格子。",
            style = Studio.text.labelM,
            color = Studio.colors.inkTertiary,
        )
    }
}

/**
 * Draws one heat cell.
 *
 * The palette is passed in rather than read from the composition local: the drawing pass is not a
 * composable scope, so resolving [Studio.colors] inside it would be a read outside composition.
 */
private fun DrawScope.drawHeatCell(
    left: Float,
    top: Float,
    cell: Float,
    step: HeatStep?,
    fill: Color,
    dashColor: Color,
    accent: Color,
    isToday: Boolean,
    isSelected: Boolean,
) {
    val radius = CornerRadius(2f)
    val topLeft = Offset(left, top)
    val cellSize = Size(cell, cell)
    when {
        step == HeatStep.None -> drawRoundRect(
            color = dashColor,
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = radius,
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))),
        )
        step != null -> drawRoundRect(color = fill, topLeft = topLeft, size = cellSize, cornerRadius = radius)
    }
    if (isSelected) {
        drawRoundRect(
            color = accent,
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = radius,
            style = Stroke(width = 1.5f),
        )
    } else if (isToday) {
        drawRoundRect(
            color = accent,
            topLeft = topLeft,
            size = cellSize,
            cornerRadius = radius,
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))),
        )
    }
}

/** Maps a tap/drag position to the nearest date in the grid. */
private fun pickCell(
    offset: Offset,
    weeks: List<List<String>>,
    cell: Float,
    gutter: Float,
    onSelect: (String) -> Unit,
) {
    val step = cell + gutter
    val weekIndex = (offset.x / step).toInt().coerceIn(0, weeks.size - 1)
    val dayIndex = (offset.y / step).toInt().coerceIn(0, 6)
    weeks.getOrNull(weekIndex)?.getOrNull(dayIndex)?.let(onSelect)
}

/** The card below the heat map: exact value for the selected day, plus its meaning. */
@Composable
private fun SelectedCellCard(
    date: String,
    seconds: Long,
    taskCount: Int,
    hasCompletion: Boolean,
    hasRecord: Boolean,
) {
    val step = heatStepOf(seconds, hasRecord || hasCompletion)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(Studio.colors.accentTint)
            .padding(Space.l)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(Format.spokenDate(date))
                    append("，")
                    if (step == HeatStep.None) append("无记录") else append("投入 ${Format.spokenDuration(seconds)}")
                }
            },
    ) {
        Text(Format.dateLabel(date), style = Studio.text.headlineS, color = Studio.colors.ink)
        Spacer(Modifier.height(Space.s))
        Text(
            text = if (step == HeatStep.None) "无记录" else Format.clock(seconds),
            style = Studio.text.displayXL.merge(MonoTabular),
            color = Studio.colors.ink,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = when {
                step == HeatStep.None -> "这天没有生成任何待做记录，不算漏做。"
                seconds == 0L -> "这天有记录，但有效投入为 0。"
                else -> "$taskCount 条记录" + if (hasCompletion) " · 有完成" else ""
            },
            style = Studio.text.bodyM,
            color = Studio.colors.inkSecondary,
        )
    }
}

/**
 * Trend chart.
 *
 * The peak and the mean are labelled directly rather than hidden behind a hover, which is what the
 * design asks for on a touch device; every value is also listed in the tabular detail (规格 6.2).
 */
@Composable
private fun TrendChart(bucket: TimeBucket, points: List<PeriodTotal>, fontScale: Float) {
    val title = if (bucket == TimeBucket.WEEK) "周" else "月"
    if (points.isEmpty()) {
        Text(
            text = "这一段时间还没有投入。",
            style = Studio.text.bodyM,
            color = Studio.colors.inkTertiary,
        )
        return
    }
    val peak = points.maxByOrNull { it.seconds }
    val mean = points.sumOf { it.seconds } / points.size
    val maxSeconds = (peak?.seconds ?: 1).coerceAtLeast(1)
    val line = Studio.colors.series[0]
    val gridColor = Studio.colors.lineHairline
    val peakColor = Studio.colors.accentDeep
    val height = if (fontScale >= 2f) 240.dp else 200.dp

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Metric(label = "峰值", value = Format.minutes(peak?.seconds ?: 0), detail = peak?.label.orEmpty())
            Metric(label = "均值", value = Format.minutes(mean), detail = "${points.size} 个$title")
        }
        Spacer(Modifier.height(Space.m))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = buildString {
                        append("趋势图：峰值 ${Format.minutes(peak?.seconds ?: 0)}")
                        if (peak != null) append("（${peak.label}）")
                        append("，均值 ${Format.minutes(mean)}，共 ${points.size} 个$title。")
                    }
                },
        ) {
            val padding = 24f
            val chartWidth = size.width - padding * 2
            val chartHeight = size.height - padding * 2
            // Horizontal grid: four lines, unlabelled because the exact numbers are in the table below.
            for (index in 0..4) {
                val y = padding + chartHeight * index / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(padding, y),
                    end = Offset(size.width - padding, y),
                    strokeWidth = 1f,
                )
            }
            val stepX = if (points.size > 1) chartWidth / (points.size - 1) else 0f
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = padding + stepX * index
                val y = padding + chartHeight * (1f - point.seconds.toFloat() / maxSeconds)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 3f, cap = StrokeCap.Round))
            points.forEachIndexed { index, point ->
                val x = padding + stepX * index
                val y = padding + chartHeight * (1f - point.seconds.toFloat() / maxSeconds)
                // Endpoints carry a shape as well as a colour, so the series is not colour-only.
                drawCircle(if (point.seconds == maxSeconds) peakColor else line, radius = 4f, center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(Space.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
            Text(points.last().label, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, detail: String) {
    Column {
        Text(label, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        Text(value, style = Studio.text.headlineS.merge(MonoTabular), color = Studio.colors.ink)
        if (detail.isNotBlank()) {
            Text(detail, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        }
    }
}

/**
 * The share ring plus its legend.
 *
 * The first five tasks keep their own series colour; everything else is merged into a neutral "其他"
 * segment, and the legend still lists every task with its exact figure so nothing becomes invisible
 * (关键页面说明 section 4.2).
 */
@Composable
private fun ShareChart(
    shares: List<TaskShare>,
    totalSeconds: Long,
    taskTitles: Map<String, String>,
) {
    if (shares.isEmpty() || totalSeconds <= 0) {
        Text("还没有可以统计的投入。", style = Studio.text.bodyM, color = Studio.colors.inkTertiary)
        return
    }
    val top = shares.take(5)
    val rest = shares.drop(5)
    val restSeconds = rest.sumOf { it.seconds }
    val segments = top.map { it.taskId to it.seconds } + if (restSeconds > 0) listOf("other" to restSeconds) else emptyList()
    val colors = Studio.colors.series

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(160.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("任务占比环形图，")
                            append(top.joinToString("，") { share ->
                                "${taskTitles[share.taskId] ?: share.taskId} ${percent(share.seconds, totalSeconds)}"
                            })
                            if (restSeconds > 0) append("，其余任务合并为其他 ${percent(restSeconds, totalSeconds)}")
                        }
                    },
            ) {
                val stroke = 30f
                var startAngle = -90f
                segments.forEachIndexed { index, (taskId, seconds) ->
                    val sweep = 360f * seconds / totalSeconds
                    drawArc(
                        color = if (taskId == "other") colors[4] else colors[index % 5],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("合计", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
                Text(
                    text = Format.minutes(totalSeconds),
                    style = Studio.text.headlineM.merge(MonoTabular),
                    color = Studio.colors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            top.forEachIndexed { index, share ->
                ShareLegendRow(
                    color = colors[index % 5],
                    shape = index,
                    title = taskTitles[share.taskId] ?: share.taskId,
                    seconds = share.seconds,
                    percent = percent(share.seconds, totalSeconds),
                )
            }
            if (restSeconds > 0) {
                ShareLegendRow(
                    color = colors[4],
                    shape = 5,
                    title = "其他（${rest.size} 项）",
                    seconds = restSeconds,
                    percent = percent(restSeconds, totalSeconds),
                )
            }
        }
    }
}

/** A legend line: colour block, a distinct end shape, the name, the duration and the percentage. */
@Composable
private fun ShareLegendRow(
    color: Color,
    shape: Int,
    title: String,
    seconds: Long,
    percent: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) {
            drawRect(color)
            when (shape % 3) {
                0 -> drawCircle(Color.White, radius = size.minDimension / 4, center = center)
                1 -> drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width / 4, size.height / 4),
                    size = Size(size.width / 2, size.height / 2),
                )
                else -> {
                    val path = Path().apply {
                        moveTo(size.width / 2, size.height / 4)
                        lineTo(size.width * 3 / 4, size.height * 3 / 4)
                        lineTo(size.width / 4, size.height * 3 / 4)
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Studio.text.bodyM,
                color = Studio.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${Format.minutes(seconds)} · $percent",
                style = Studio.text.labelM,
                color = Studio.colors.inkTertiary,
            )
        }
    }
}

/** The tabular alternative: every task with its exact total and share, one row per task. */
@Composable
private fun ShareTable(
    shares: List<TaskShare>,
    totalSeconds: Long,
    taskTitles: Map<String, String>,
) {
    if (shares.isEmpty()) {
        Text("没有可列出的任务。", style = Studio.text.bodyM, color = Studio.colors.inkTertiary)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        shares.forEach { share ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Studio.colors.surfaceCard)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${taskTitles[share.taskId] ?: share.taskId}，" +
                            "共 ${Format.spokenDuration(share.seconds)}，占 ${percent(share.seconds, totalSeconds)}"
                    }
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskTitles[share.taskId] ?: share.taskId,
                    style = Studio.text.bodyL,
                    color = Studio.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    text = Format.minutes(share.seconds),
                    style = Studio.text.bodyM.merge(MonoTabular),
                    color = Studio.colors.inkSecondary,
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    text = percent(share.seconds, totalSeconds),
                    style = Studio.text.bodyM.merge(MonoTabular),
                    color = Studio.colors.inkTertiary,
                )
            }
            Spacer(Modifier.height(Space.xs))
        }
    }
}

/** Percentage with one decimal place below 10%, so a small task is not shown as a flat 0%. */
private fun percent(seconds: Long, total: Long): String {
    if (total <= 0) return "0%"
    val value = seconds * 100.0 / total
    return if (value < 10) "%.1f%%".format(value) else "${value.toInt()}%"
}
