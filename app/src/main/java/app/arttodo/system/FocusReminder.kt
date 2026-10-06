package app.arttodo.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.arttodo.R
import app.arttodo.MainActivity

object FocusReminder {
    const val CHANNEL_ID = "focus_timer"
    private const val ALERT_CHANNEL_BASE = "focus_alert"
    const val FOREGROUND_ID = 2001
    const val RESULT_ID = 2002
    const val PREFS = "focus_runtime"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "专注计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示正在进行的专注计时"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun foreground(context: Context, remainingSeconds: Long?): android.app.Notification {
        ensureChannel(context)
        val text = remainingSeconds?.let { "剩余时间 ${format(it)}" } ?: "专注进行中"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("专注进行中")
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("专注进行中")
                    .setContentText(text)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    FOREGROUND_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    fun countdownFinished(context: Context, sessionId: String, allowSound: Boolean, allowVibration: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(resultKey(sessionId), false)) return false
        prefs.edit { putBoolean(resultKey(sessionId), true) }
        val sent = sendCountdownResult(context, allowSound, allowVibration)
        return sent
    }

    fun isResultPending(context: Context, sessionId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(resultKey(sessionId), false)

    fun clearResultPending(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(resultKey(sessionId))
        }
    }

    private fun resultKey(sessionId: String) = "countdown_result_$sessionId"

    private fun sendCountdownResult(context: Context, allowSound: Boolean, allowVibration: Boolean): Boolean {
        val channelId = alertChannelId(allowSound, allowVibration)
        ensureAlertChannel(context, channelId, allowSound, allowVibration)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("专注计时结束")
            .setContentText("到点结果已保存，可回到应用查看并选择下一步。")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    RESULT_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        return runCatching {
            NotificationManagerCompat.from(context).notify(RESULT_ID, builder.build())
            true
        }.getOrDefault(false)
    }

    fun alertChannelId(sound: Boolean, vibration: Boolean): String =
        "$ALERT_CHANNEL_BASE-${if (sound) 1 else 0}-${if (vibration) 1 else 0}"

    private fun ensureAlertChannel(context: Context, id: String, sound: Boolean, vibration: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, "专注到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "专注倒计时结束时提醒"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            enableVibration(vibration)
            vibrationPattern = if (vibration) longArrayOf(0, 200) else longArrayOf(0)
            if (sound) {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                )
            } else setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun format(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "%d:%02d".format(minutes, remaining) else "%d 秒".format(remaining)
    }
}
