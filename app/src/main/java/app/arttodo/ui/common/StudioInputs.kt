package app.arttodo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.Space

/**
 * Labeled text field with the design's visible label and 3:1 control outline.
 *
 * Errors are announced through `semantics { error(...) }` rather than only coloured, so the message is
 * not the single channel (设计系统 section 6.3).
 */
@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    numeric: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val colors = Studio.colors
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = Studio.text.bodyM) },
            isError = error != null,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = Studio.text.bodyL,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
            shape = RoundedCornerShape(Radius.sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.lineControl,
                errorBorderColor = colors.dangerInk,
                focusedLabelColor = colors.accentDeep,
                unfocusedLabelColor = colors.inkSecondary,
                cursorColor = colors.accent,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                focusedContainerColor = colors.surfaceCard,
                unfocusedContainerColor = colors.surfaceCard,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { error?.let { this.error(it) } },
        )
        if (error != null) {
            Text(
                text = error,
                style = Studio.text.bodyM,
                color = colors.dangerInk,
                modifier = Modifier.padding(top = Space.xs, start = Space.xs),
            )
        }
    }
}

/** Two-or-more option segmented control; selection is accent fill + bold label + state description. */
@Composable
fun <T> StudioSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = Space.minTouch,
) {
    val colors = Studio.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = minHeight)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (isSelected) colors.accentTint else colors.surfaceCard)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) colors.accent else colors.lineControl,
                        shape = RoundedCornerShape(Radius.sm),
                    )
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        this.selected = isSelected
                        stateDescription = if (isSelected) "已选中" else "未选中"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Studio.text.labelL, color = colors.ink)
            }
        }
    }
}

/**
 * A settings row: label plus value/control.
 *
 * At large font scales the label, the value and the control each take a full line (设计系统 section
 * 12.5, fix 4): sharing one line squeezed the label, the value and a 48dp button into ~270dp, which
 * broke Chinese into vertical single characters. This is a container-level rule so no row has to patch
 * itself, and it stays a single semantic node per fragment for TalkBack.
 */
@Composable
fun StudioSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLabelClick: (() -> Unit)? = null,
    labelColor: androidx.compose.ui.graphics.Color = Studio.colors.ink,
) {
    val stacked = LocalDensity.current.fontScale >= 1.6f
    val labelText: @Composable (Modifier) -> Unit = { width ->
        Text(
            text = label,
            style = Studio.text.bodyL,
            color = labelColor,
            modifier = width.then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier),
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        when {
            trailing == null -> labelText(Modifier.fillMaxWidth())

            stacked -> {
                labelText(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { trailing() }
            }

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                labelText(Modifier.weight(1f))
                trailing()
            }
        }
        if (description != null) {
            Text(text = description, style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
        }
    }
}

/** A switch with the design's track colours and an explicit state description for TalkBack. */
@Composable
fun StudioSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Studio.colors.onAccent,
            checkedTrackColor = Studio.colors.accent,
            checkedBorderColor = Studio.colors.accent,
            uncheckedThumbColor = Studio.colors.surfaceCard,
            uncheckedTrackColor = Studio.colors.lineControl,
            uncheckedBorderColor = Studio.colors.lineControl,
        ),
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
            stateDescription = if (checked) "已开启" else "已关闭"
        },
    )
}

/**
 * Confirmation dialog.
 *
 * Irreversible actions get a filled danger button and focus starts on the title, not on the button,
 * so a stray double-tap cannot confirm (动效与无障碍说明 section 6.3).
 */
@Composable
fun StudioConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dangerTintTitle: Boolean = true,
    extraBody: String? = null,
) {
    val colors = Studio.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = Studio.text.headlineS,
                color = if (destructive && dangerTintTitle) colors.dangerInk else colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(body, style = Studio.text.bodyL, color = colors.ink)
                if (extraBody != null) {
                    Text(extraBody, style = Studio.text.bodyM, color = colors.inkSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    style = Studio.text.labelL,
                    color = if (destructive) colors.dangerInk else colors.accentDeep,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = Studio.text.labelL, color = colors.inkSecondary)
            }
        },
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
    )
}
