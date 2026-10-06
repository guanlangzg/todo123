package app.arttodo.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.arttodo.data.FieldLimits
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.StudioConfirmDialog
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioSecondaryButton
import app.arttodo.ui.common.StudioSegmented
import app.arttodo.ui.common.StudioTextField
import app.arttodo.ui.common.StudioTextAction
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.Space

/**
 * Test-tag prefix for the two dates of the cross-midnight completion dialog, keyed by the application
 * date the option completes. Screens and tests must not match on the rendered date text: the same
 * label also appears in the page header, so a text lookup would be ambiguous.
 */
const val COMPLETION_DAY_TAG_PREFIX = "complete-day:"

/**
 * New-task sheet.
 *
 * The Add button stays disabled until the title is non-empty, and the reason is stated in text rather
 * than by a colour change alone. Cancelling keeps the draft, as the design asks (关键页面说明 1.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskSheet(
    onDismiss: () -> Unit,
    onConfirm: (TaskKind, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TaskKind.TEMPORARY) }
    val titleTooLong = title.length > FieldLimits.MAX_TITLE_CHARS
    val noteTooLong = note.length > FieldLimits.MAX_NOTE_CHARS
    val valid = title.isNotBlank() && !titleTooLong && !noteTooLong

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Studio.colors.surfaceRaised,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = Radius.xl,
            topEnd = Radius.xl,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(
                text = "新建任务",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            StudioTextField(
                value = title,
                onValueChange = { title = it },
                label = "任务名称",
                error = when {
                    titleTooLong -> "名称最多 $FieldLimits.MAX_TITLE_CHARS 个字符"
                    (title.isBlank() && title.isNotEmpty()) -> "请填写任务名称"
                    else -> null
                },
            )
            StudioTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可留空）",
                singleLine = false,
                minLines = 2,
                error = if (noteTooLong) "备注最多 $FieldLimits.MAX_NOTE_CHARS 个字符" else null,
            )
            StudioSegmented(
                options = listOf(
                    TaskKind.TEMPORARY to "临时任务",
                    TaskKind.DAILY to "日常任务",
                ),
                selected = kind,
                onSelect = { kind = it },
            )
            Text(
                text = when (kind) {
                    TaskKind.TEMPORARY -> "临时任务排在日常任务之前，完成后进入历史。"
                    TaskKind.DAILY -> "日常任务每天都会出现，不会因为漏做而积压。"
                },
                style = Studio.text.bodyM,
                color = Studio.colors.inkSecondary,
            )
            StudioPrimaryButton(
                text = "添加",
                enabled = valid,
                onClick = { onConfirm(kind, title.trim(), note.trim()) },
            )
        }
    }
}

/** Edit sheet for the title and note; renames are effective now and later, history keeps old names. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskSheet(
    task: TaskRecord,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember(task.taskId) { mutableStateOf(task.title) }
    var note by remember(task.taskId) { mutableStateOf(task.note) }
    val titleTooLong = title.length > FieldLimits.MAX_TITLE_CHARS
    val noteTooLong = note.length > FieldLimits.MAX_NOTE_CHARS
    val valid = title.isNotBlank() && !titleTooLong && !noteTooLong

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Studio.colors.surfaceRaised,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(
                text = "编辑任务",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            StudioTextField(
                value = title,
                onValueChange = { title = it },
                label = "任务名称",
                error = if (titleTooLong) "名称最多 $FieldLimits.MAX_TITLE_CHARS 个字符" else null,
            )
            StudioTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注",
                singleLine = false,
                minLines = 3,
                error = if (noteTooLong) "备注最多 $FieldLimits.MAX_NOTE_CHARS 个字符" else null,
            )
            Text(
                text = "改名影响当前及以后；历史明细保留当时的名称，累计不会因改名而拆分。",
                style = Studio.text.bodyM,
                color = Studio.colors.inkSecondary,
            )
            StudioPrimaryButton(text = "保存", enabled = valid, onClick = { onConfirm(title.trim(), note.trim()) })
            StudioSecondaryButton(text = "取消", onClick = onDismiss)
        }
    }
}

/**
 * The fixed-zone explanation.
 *
 * The copy states plainly that the zone is captured at first install and that a phone-side zone change
 * does not move the app's dates; the only way to change it is the explicit action in settings, which
 * appends a new epoch without recomputing history (规格 3, AC-15).
 */
@Composable
fun ZoneInfoDialog(zoneId: String, onDismiss: () -> Unit) {
    StudioConfirmDialog(
        title = "应用时区",
        body = "$zoneId\n\n应用时区在首次安装时确定并固定。之后手机系统改时区不会改变这里的口径，" +
            "也不会重算已有的投入与完成记录。",
        confirmLabel = "知道了",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        destructive = false,
    )
}

/**
 * Confirmation for ticking a task whose timer is running (规格 5.1, AC-07 / AC-08).
 *
 * Completing is not a plain toggle here: the running session must end and save its investment, and
 * that has to be one atomic step, so the user confirms first and cancelling changes nothing.
 *
 * When the session started on an earlier application date the completion must not silently land on
 * today: the two dates are offered as big options with **today highlighted**, and exactly one of them
 * is completed — never both (规格 5.3, 关键页面说明 section 2.4).
 */
@Composable
fun CompleteWhileRunningDialog(
    taskTitle: String,
    /** Application date the running session started on; `null` while it is unknown. */
    sessionStartDate: String?,
    today: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val crossedMidnight = sessionStartDate != null && today.isNotBlank() && sessionStartDate != today
    if (!crossedMidnight) {
        StudioConfirmDialog(
            title = "这件任务正在计时",
            body = "先结束正在进行的这一段并保存已投入的时间，然后完成「$taskTitle」。",
            confirmLabel = "结束并完成",
            onConfirm = { onConfirm(today) },
            onDismiss = onDismiss,
            dangerTintTitle = false,
        )
        return
    }

    var selectedDate by remember(sessionStartDate, today) { mutableStateOf(today) }
    val colors = Studio.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "这一次投入跨了两天，今天完成的应该是哪一天？",
                style = Studio.text.headlineS,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    text = "投入时间按实际发生的时刻分归两天，与你选择哪一天完成是两件事。",
                    style = Studio.text.bodyL,
                    color = colors.ink,
                )
                Spacer(Modifier.height(Space.xs))
                CompletionDayOption(
                    isoDate = requireNotNull(sessionStartDate),
                    selected = selectedDate == sessionStartDate,
                    onSelect = { selectedDate = requireNotNull(sessionStartDate) },
                )
                CompletionDayOption(
                    isoDate = today,
                    selected = selectedDate == today,
                    onSelect = { selectedDate = today },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedDate) }) {
                Text("就选这天", style = Studio.text.labelL, color = colors.accentDeep)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = Studio.text.labelL, color = colors.inkSecondary)
            }
        },
    )
}

/** One date of the cross-midnight choice: a full-width 64dp option, never colour-only. */
@Composable
private fun CompletionDayOption(isoDate: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = Studio.colors
    val shape = RoundedCornerShape(Radius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) colors.accentTint else colors.surfaceCard)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.lineControl,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .testTag(COMPLETION_DAY_TAG_PREFIX + isoDate)
            // The semantics go after `clickable`, which installs its own node and would drop a
            // description written before it.
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "完成日期 ${Format.spokenDate(isoDate)}"
                stateDescription = if (selected) "已选中" else "未选中"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = Format.dateLabel(isoDate), style = Studio.text.titleM, color = colors.ink)
    }
}
