package app.arttodo.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit

@Composable
fun rememberNotificationPermissionState(onMessage: (String) -> Unit): NotificationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        if (!allowed) onMessage("通知权限未开启。到点结果仍会保存在应用内，不再重复请求。")
    }
    return NotificationPermissionState(
        granted = granted,
        requestOnce = request@{
            if (Build.VERSION.SDK_INT < 33 || granted) return@request
            val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
            if (prefs.getBoolean("notification_request_made", false)) {
                onMessage("可在系统应用设置中查看通知权限。到点结果仍在应用内保留。")
            } else {
                prefs.edit { putBoolean("notification_request_made", true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        openSettings = { context.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
    )
}

private fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

data class NotificationPermissionState(
    val granted: Boolean,
    val requestOnce: () -> Unit,
    val openSettings: () -> Unit,
)
