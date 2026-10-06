package app.arttodo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.arttodo.system.rememberBackupTransferActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalSnackbarHostState
import app.arttodo.ui.common.BannerKind
import app.arttodo.ui.common.Format
import app.arttodo.ui.common.StudioBanner
import app.arttodo.ui.common.StudioIconButton
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioSettingRow
import app.arttodo.ui.common.StudioSwitch
import app.arttodo.ui.common.StudioTextAction
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space

/**
 * The settings screen: reminders, date and zone, backup, archive, about.
 *
 * Two things this screen must say plainly rather than bury:
 *
 * 1. The recovery points live on the same device, so they do not protect against losing the phone or
 *    uninstalling the app; changing devices means a manual export and import (架构契约 section 8.1).
 * 2. Changing the app zone appends a new epoch. History keeps the zone it was recorded under and is
 *    never recomputed, and switching back does not undo what already happened (规格 3, AC-15).
 *
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val state = LocalAppState.current
    val viewModel = LocalAppViewModel.current
    val snackbar = LocalSnackbarHostState.current
    var showZoneChange by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val backupActions = rememberBackupTransferActions(
        onMessage = { message -> transferMessage = message },
        onRestored = viewModel::refresh,
    )
    val notificationPermission = app.arttodo.system.rememberNotificationPermissionState { message -> transferMessage = message }

    LazyColumn(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Space.pageMargin),
        contentPadding = PaddingValues(top = Space.m, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.section),
    ) {
        item(key = "title") {
            Text(
                text = "设置",
                style = Studio.text.displayM,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }

        if (state.error != null && !state.coreAvailable) {
            item(key = "error") {
                StudioBanner(
                    text = "设置暂时读不到。数据仍在设备上，可以重试。",
                    kind = BannerKind.Danger,
                    action = { StudioTextAction("重试", onClick = viewModel::refresh) },
                )
            }
        }

        item(key = "reminders") {
            SettingsGroup(title = "专注提醒") {
                StudioSettingRow(
                    label = "到点震动",
                    description = "到点提醒使用一次短震动，与完成勾选的触觉可区分。",
                    trailing = {
                        StudioSwitch(
                            checked = state.vibrationEnabled,
                            onCheckedChange = viewModel::setVibrationEnabled,
                            contentDescription = "到点震动",
                        )
                    },
                )
                StudioSettingRow(
                    label = "到点声音",
                    description = "关闭后只保留震动与应用内结果页。",
                    trailing = {
                        StudioSwitch(
                            checked = state.soundEnabled,
                            onCheckedChange = viewModel::setSoundEnabled,
                            contentDescription = "到点声音",
                        )
                    },
                )
                StudioSettingRow(
                    label = "通知权限",
                    description = if (notificationPermission.granted) {
                        "应用内始终显示到点结果。声音与震动遵循系统静音、勿扰和通知渠道设置。"
                    } else {
                        "通知权限未开启时，到点结果仍保存在应用内；如需系统提醒可前往设置开启。"
                    },
                    trailing = {
                        StudioTextAction(
                            "通知设置",
                            onClick = if (notificationPermission.granted) notificationPermission.openSettings else notificationPermission.requestOnce,
                        )
                    },
                )
            }
        }

        item(key = "zone") {
            SettingsGroup(title = "日期与时区") {
                StudioSettingRow(
                    label = "应用时区",
                    description = "首次安装时固定。手机系统改时区不影响这里的口径。",
                    trailing = {
                        Text(
                            text = state.zoneId,
                            style = Studio.text.bodyM,
                            color = Studio.colors.inkTertiary,
                        )
                    },
                )
                StudioSettingRow(
                    label = "变更时区",
                    description = "会先显示旧时区与新时区的日期对比，再决定是否切换。",
                    labelColor = Studio.colors.warnInk,
                    trailing = {
                        StudioTextAction(
                            text = "变更",
                            color = Studio.colors.warnInk,
                            onClick = { showZoneChange = true },
                        )
                    },
                )
            }
        }

        item(key = "backup") {
            SettingsGroup(title = "备份") {
                StudioSettingRow(
                    label = "手动导出备份",
                    description = "通过系统文件选择器导出，可选择密码保护；未加密文件包含私人任务与历史。",
                    trailing = { StudioTextAction("导出", onClick = backupActions.export) },
                )
                StudioSettingRow(
                    label = "导入并完整替换",
                    description = "选择已有备份。恢复会完整替换当前数据，执行前先创建保护点。",
                    trailing = { StudioTextAction("选择文件", onClick = backupActions.import) },
                )
                StudioSettingRow(
                    label = "本机恢复点",
                    description = "最近：" + (state.lastBackupAtMs?.let { timestamp(it) } ?: "还没有恢复点") +
                        "。每天最多一个、保留七个；换机需手动导出。本机恢复点随卸载或设备损坏丢失。",
                    trailing = {
                        StudioTextAction("管理", onClick = backupActions.recoveryPoints)
                    },
                )
                StudioSettingRow(
                    label = "可选密码保护",
                    description = "使用 PBKDF2-HMAC-SHA256 与 AES-GCM；密码仅在操作时使用，不写入日志或设置。遗失后无法找回。",
                )
            }
        }

        item(key = "tasks") {
            SettingsGroup(title = "任务") {
                StudioSettingRow(
                    label = "已归档任务（${state.archivedTasks.size}）",
                    description = if (state.archivedTasks.isEmpty()) {
                        "没有被归档的任务。"
                    } else {
                        "归档不等于完成，历史与投入都保留。"
                    },
                    trailing = if (state.archivedTasks.isEmpty()) {
                        null
                    } else {
                        {
                            StudioIconButton(
                                icon = StudioIcon.ChevronDown,
                                contentDescription = "查看已归档任务",
                                tint = Studio.colors.inkTertiary,
                                onClick = { showArchive = true },
                            )
                        }
                    },
                )
            }
        }

        item(key = "about") {
            SettingsGroup(title = "关于") {
                StudioSettingRow(
                    label = "版本",
                    // The version block is read from the package manager because BuildConfig generation is
                    // switched off project-wide (`android.defaults.buildfeatures.buildconfig=false`).
                    trailing = {
                        Text(
                            text = appVersion(),
                            style = Studio.text.bodyM,
                            color = Studio.colors.inkTertiary,
                        )
                    },
                )
            }
        }
    }

    if (showZoneChange) {
        ZoneChangeDialog(
            currentZone = state.zoneId,
            deviceZone = viewModel.deviceZoneId(),
            today = state.today,
            hasActiveSession = state.activeSession != null,
            onDismiss = { showZoneChange = false },
            onConfirm = { target ->
                viewModel.appendZoneEpoch(
                    zoneId = target,
                    impactSummary = "手动变更：${state.zoneId} → $target",
                )
                showZoneChange = false
            },
        )
    }

    if (showArchive) {
        ArchiveDialog(
            tasks = state.archivedTasks.map { it.taskId to it.title },
            onRestore = { taskId ->
                viewModel.unarchiveTask(taskId)
                showArchive = false
            },
            onDismiss = { showArchive = false },
        )
    }

    transferMessage?.let { message ->
        androidx.compose.runtime.LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            transferMessage = null
        }
    }
}

/** `0.1.0 (1)` from the installed package, since BuildConfig is not generated in this project. */
@Composable
private fun appVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // `PackageInfoCompat` rather than `PackageInfo.longVersionCode`: the latter is API 28 and
            // minSdk here is 26, which lint flags as NewApi.
            val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            Format.version(info.versionName ?: "0.0.0", code.toInt())
        }.getOrDefault("0.0.0 (0)")
    }
}

/** Plain-time formatting for a stored instant; the zone label is the app's own, shown separately. */
private fun timestamp(wallMs: Long): String =
    java.time.Instant.ofEpochMilli(wallMs)
        .atZone(java.time.ZoneId.of("UTC"))
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = Studio.text.headlineS,
            color = Studio.colors.ink,
            modifier = Modifier
                .padding(bottom = Space.s)
                .semantics { heading() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Studio.colors.surfaceCard),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            content()
        }
    }
}

/**
 * The zone-change confirmation.
 *
 * It lists the three comparisons the design requires — zone, current date, next daily refresh — and
 * states that existing records are not recomputed and that switching back cannot undo them. It carries
 * no illustration: data-risk copy outranks decoration (规格 3, 设计系统 section 1).
 */
@Composable
private fun ZoneChangeDialog(
    currentZone: String,
    deviceZone: String,
    today: String,
    hasActiveSession: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "切换应用时区前请确认",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Comparison(label = "应用时区", before = currentZone, after = deviceZone)
                Comparison(label = "当前日期", before = today, after = "按新区重新判定")
                Comparison(label = "下一次日常刷新", before = "今天 00:00 已过", after = "明天 00:00")
                Text(
                    text = "已有的投入与完成记录不会重算，仍按记录发生时的时区归日。" +
                        "切回旧时区也不能撤销已经发生的历史。",
                    style = Studio.text.bodyM,
                    color = Studio.colors.inkSecondary,
                )
                if (hasActiveSession) {
                    StudioBanner(
                        text = "当前有进行中的计时，需要先结束并保存。",
                        kind = BannerKind.Warn,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !hasActiveSession, onClick = { onConfirm(deviceZone) }) {
                Text(
                    text = "确认切换",
                    style = Studio.text.labelL,
                    color = if (hasActiveSession) Studio.colors.inkTertiary else Studio.colors.warnInk,
                )
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

/** One old → new line, stacked so a large font scale cannot pinch either value. */
@Composable
private fun Comparison(label: String, before: String, after: String) {
    Column {
        Text(label, style = Studio.text.labelM, color = Studio.colors.inkTertiary)
        Text(
            text = "$before → $after",
            style = Studio.text.bodyL,
            color = Studio.colors.ink,
        )
    }
}

/** Archived tasks, with the "archiving is not completing" distinction stated on every row. */
@Composable
private fun ArchiveDialog(
    tasks: List<Pair<String, String>>,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "已归档任务",
                style = Studio.text.headlineS,
                color = Studio.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (tasks.isEmpty()) {
                    Text("没有被归档的任务。", style = Studio.text.bodyM, color = Studio.colors.inkSecondary)
                }
                tasks.forEach { (taskId, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = Studio.text.bodyL,
                            color = Studio.colors.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space.s))
                        StudioTextAction(
                            text = "重新启用",
                            onClick = { onRestore(taskId) },
                            contentDescription = "重新启用 $title，今天就会出现",
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = Studio.text.labelL, color = Studio.colors.inkSecondary)
            }
        },
        containerColor = Studio.colors.surfaceRaised,
    )
}
