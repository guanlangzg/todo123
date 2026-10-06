package app.arttodo.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.arttodo.ArtTodoApplication
import app.arttodo.data.BackupException
import app.arttodo.data.BackupArchive
import app.arttodo.data.BackupRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF transfer and on-device restore points, surfaced through actions consumed by SettingsScreen. */
@Composable
fun rememberBackupTransferActions(
    onMessage: (String) -> Unit,
    onRestored: () -> Unit,
): BackupTransferActions {
    val context = LocalContext.current
    val app = context.applicationContext as ArtTodoApplication
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var recoveryDialog by remember { mutableStateOf(false) }
    var recoveryPoints by remember { mutableStateOf<List<BackupRecordEntity>>(emptyList()) }
    var restorePoint by remember { mutableStateOf<BackupRecordEntity?>(null) }
    var showImportPassword by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val secret = exportPassword
        exportPassword = null
        if (uri == null) {
            secret?.fill('\u0000')
        } else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        app.container.backupManager.exportTo(output, secret)
                    } ?: throw BackupException("ExportDestinationUnavailable")
                }
                onMessage(if (secret == null) {
                    "备份已导出。未加密文件含私人任务与历史，请妥善保存。"
                } else {
                    "加密备份已导出。请自行保管密码，应用无法找回。"
                })
            } catch (_: Exception) {
                onMessage("导出失败，未生成可用备份。")
            } finally {
                secret?.fill('\u0000')
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(BackupArchive::readBounded)
                        ?: throw BackupException("ImportSourceUnavailable")
                }
            }.onSuccess {
                pendingImport = it
                importDialog = true
            }.onFailure {
                onMessage("无法读取备份文件；现有数据没有改变。")
            }
        }
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false; password = "" },
            title = { Text("导出备份") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("可选密码保护；留空为未加密") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exportPassword = password.takeIf { it.isNotEmpty() }?.toCharArray()
                    password = ""
                    exportDialog = false
                    exportLauncher.launch("arttodo-backup.atb")
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false; password = "" }) { Text("取消") } },
        )
    }
    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false; pendingImport = null },
            title = { Text("完整替换现有数据？") },
            text = { Text("将以备份完整替换当前任务与历史，并先创建本机保护点。密码、完整性、版本和迁移校验通过前不会替换当前数据。") },
            confirmButton = {
                TextButton(onClick = { importDialog = false; password = ""; restorePoint = null; showImportPassword = true }) {
                    Text("继续")
                }
            },
            dismissButton = { TextButton(onClick = { importDialog = false; pendingImport = null }) { Text("取消") } },
        )
    }
    if (recoveryDialog) {
        AlertDialog(
            onDismissRequest = { recoveryDialog = false },
            title = { Text("本机恢复点") },
            text = {
                LazyColumn {
                    if (recoveryPoints.isEmpty()) item {
                        Text("还没有恢复点。下一次数据变更前会自动尝试创建；恢复点会随卸载或设备损坏丢失。")
                    }
                    items(recoveryPoints.size) { index ->
                        val record = recoveryPoints[index]
                        val label = if (record.kind == 1) "恢复前保护点" else "每日恢复点"
                        TextButton(onClick = { restorePoint = record; recoveryDialog = false }) {
                            Text("$label · ${record.createdWallMs} · 恢复")
                        }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.container.recoveryPoints.delete(record) } }
                                    .onSuccess { recoveryPoints = recoveryPoints - record; onMessage("恢复点已删除。") }
                                    .onFailure { onMessage("删除恢复点失败。") }
                            }
                        }) { Text("删除 $label") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { recoveryDialog = false }) { Text("关闭") } },
        )
    }
    if (restorePoint != null) {
        val selected = restorePoint!!
        AlertDialog(
            onDismissRequest = { restorePoint = null },
            title = { Text("从本机恢复点完整替换？") },
            text = { Text("将恢复 ${if (selected.kind == 1) "恢复前保护点" else "每日恢复点"}，覆盖当前全部任务和历史，并先创建当前数据保护点。") },
            confirmButton = {
                TextButton(onClick = {
                    restorePoint = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { app.container.recoveryPoints.restore(selected) } }
                            .onSuccess { onMessage("恢复点已完整恢复。"); onRestored() }
                            .onFailure { onMessage("恢复点无效或恢复失败；现有数据保持不变。") }
                    }
                }) { Text("完整替换") }
            },
            dismissButton = { TextButton(onClick = { restorePoint = null }) { Text("取消") } },
        )
    }
    if (showImportPassword) {
        AlertDialog(
            onDismissRequest = { showImportPassword = false; pendingImport = null; password = "" },
            title = { Text("备份密码") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("加密备份密码；未加密文件留空") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportPassword = false
                    val candidate = pendingImport
                    pendingImport = null
                    val secret = password.takeIf { it.isNotEmpty() }?.toCharArray()
                    password = ""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val bytes = candidate ?: throw BackupException("MissingImport")
                                // The replacement runs on the command writer's own critical section, so
                                // it cannot land between a command's load and its commit.
                                app.container.recoveryPoints.restoreArchive(bytes, secret)
                            }
                            onMessage("备份已完整恢复。")
                            onRestored()
                        } catch (_: Exception) {
                            onMessage("恢复失败（密码、文件、版本、迁移或写入无效）；现有数据保持不变。")
                        } finally {
                            secret?.fill('\u0000')
                            candidate?.fill(0)
                        }
                    }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { showImportPassword = false; pendingImport = null; password = "" }) { Text("取消") } },
        )
    }

    return BackupTransferActions(
        export = { exportDialog = true },
        import = { importLauncher.launch(arrayOf("application/octet-stream", "application/x-arttodo-backup")) },
        recoveryPoints = {
            scope.launch {
                recoveryPoints = withContext(Dispatchers.IO) { app.container.recoveryPoints.list() }
                recoveryDialog = true
            }
        },
    )
}

data class BackupTransferActions(
    val export: () -> Unit,
    val import: () -> Unit,
    val recoveryPoints: () -> Unit,
)
