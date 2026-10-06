package app.arttodo.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class SessionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val start = Intent(context, SessionRuntimeService::class.java).setAction(ACTION_ASSESS_RECOVERY)
        runCatching { ContextCompat.startForegroundService(context, start) }
    }

    companion object {
        const val ACTION_ASSESS_RECOVERY = "app.arttodo.action.ASSESS_RECOVERY"
    }
}
