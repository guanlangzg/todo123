package app.arttodo.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.arttodo.core.TaskRecord
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.MonoTabular
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioSegmented
import app.arttodo.ui.common.StudioTextField
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.Space

/**
 * Manual entry sheet.
 *
 * It offers exactly what the spec allows: a task, a date, a duration in minutes, and a mode that
 * either adds to the day or sets the day's total. It deliberately does **not** ask for a start/end
 * time, because 规格 6.1 forbids fabricating a slot the user did not record; the sheet says so instead
 * of implying it can place the entry on the clock.
 *
 * The preview shows the before and after totals for the chosen task so the effect of the operation is
 * visible before it is dispatched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLedgerSheet(
    appDate: String,
    tasks: List<TaskRecord>,
    currentTotalSeconds: Long,
    taskTotals: Map<String, Long>,
    onDismiss: () -> Unit,
    onSubmit: (taskId: String, asSetTotal: Boolean, seconds: Long) -> Unit,
) {
    var selectedTaskId by remember(tasks) { mutableStateOf(tasks.firstOrNull()?.taskId) }
    var minutes by remember { mutableLongStateOf(15L) }
    var input by remember { mutableStateOf("15") }
    var asSetTotal by remember { mutableStateOf(false) }
    val parsed = input.toLongOrNull()
    val valid = parsed != null && parsed in 0..1440 && selectedTaskId != null
    val seconds = (parsed ?: 0) * 60
    val selectedTaskTotal = taskTotals[selectedTaskId] ?: 0L
    val previewBefore = if (asSetTotal) selectedTaskTotal else currentTotalSeconds
    val previewAfter = if (asSetTotal) {
        currentTotalSeconds - selectedTaskTotal + seconds
    } else {
        currentTotalSeconds + seconds
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Studio.colors.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(
                text = "补记投入",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "日期：${Format.dateLabel(appDate)}",
                style = Studio.text.bodyM,
                color = Studio.colors.inkSecondary,
            )

            Text("任务", style = Studio.text.labelM, color = Studio.colors.inkTertiary)
            TaskPicker(
                tasks = tasks,
                selectedTaskId = selectedTaskId,
                onSelect = { selectedTaskId = it },
            )

            StudioTextField(
                value = input,
                onValueChange = { raw ->
                    input = raw.filter { it.isDigit() }.take(4)
                    raw.filter { it.isDigit() }.toLongOrNull()?.let { minutes = it }
                },
                label = "时长（分钟）",
                numeric = true,
                error = if (!valid) "请输入 0–1440 之间的整数分钟" else null,
            )

            StudioSegmented(
                options = listOf(false to "额外增加", true to "设为当日总量"),
                selected = asSetTotal,
                onSelect = { asSetTotal = it },
            )

            // Before/after preview: the headline numbers come from the core; only the arithmetic on the
            // *proposed* change is done here, and only for the preview.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(Studio.colors.surfaceCard)
                    .padding(Space.m),
            ) {
                Text(
                    text = if (asSetTotal) {
                        "该任务 ${Format.minutes(previewBefore)} → 修正后 ${Format.minutes(seconds)}；全日合计 ${Format.minutes(currentTotalSeconds)} → ${Format.minutes(previewAfter)}"
                    } else {
                        "当前 ${Format.minutes(previewBefore)} → 操作后 ${Format.minutes(previewAfter)}"
                    },
                    style = Studio.text.bodyL.merge(MonoTabular),
                    color = Studio.colors.ink,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = if (asSetTotal) {
                        "后续新增投入会在此任务的修正值之上继续累加。"
                    } else {
                        "无法自动判断这段是否与已有记录重叠，请自行确认。"
                    },
                    style = Studio.text.bodyM,
                    color = Studio.colors.inkSecondary,
                )
            }

            StudioPrimaryButton(
                text = "添加",
                enabled = valid,
                onClick = { onSubmit(selectedTaskId!!, asSetTotal, seconds) },
            )
        }
    }
}

/**
 * Horizontal task chooser.
 *
 * A horizontally scrolling chip row rather than a dropdown: every option is a 48dp target and the
 * current title is readable without opening anything, which keeps the flow usable at large font scales.
 */
@Composable
private fun TaskPicker(
    tasks: List<TaskRecord>,
    selectedTaskId: String?,
    onSelect: (String) -> Unit,
) {
    if (tasks.isEmpty()) {
        Text(
            text = "还没有任务可以记。先去今天页新建一件事。",
            style = Studio.text.bodyM,
            color = Studio.colors.inkSecondary,
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        tasks.forEach { task ->
            val selected = task.taskId == selectedTaskId
            Box(
                Modifier
                    .heightIn(min = Space.minTouch)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (selected) Studio.colors.accentTint else Studio.colors.surfaceCard)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Studio.colors.accent else Studio.colors.lineControl,
                        shape = RoundedCornerShape(Radius.sm),
                    )
                    .clickable(role = Role.RadioButton) { onSelect(task.taskId) }
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = task.title
                        stateDescription = if (selected) "已选中" else "未选中"
                    }
                    .padding(horizontal = Space.m, vertical = Space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = task.title,
                    style = Studio.text.labelL,
                    color = Studio.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
