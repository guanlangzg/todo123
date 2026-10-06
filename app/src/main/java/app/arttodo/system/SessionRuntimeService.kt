package app.arttodo.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import app.arttodo.ArtTodoApplication
import app.arttodo.core.DomainCommand
import app.arttodo.core.SessionState
import app.arttodo.core.CompletionChoice
import app.arttodo.domain.Executed
import app.arttodo.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SessionRuntimeService : android.app.Service() {
    private val app get() = application as ArtTodoApplication
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val owner get() = app.container.sessionOwner
    private var loopJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        FocusReminder.ensureChannel(this)
        startForeground(FocusReminder.FOREGROUND_ID, FocusReminder.foreground(this, null))
        loopJob = serviceScope.launch {
            val state = app.container.commandExecutor.readState()
            val active = state.activeSessionId
            if (active != null && !owner.owns(active)) app.container.assessRecovery(state, active)
            if (active == null) {
                stopSelf()
            } else if (owner.owns(active)) {
                startTicker(active)
            } else {
                stopSelf()
            }
        }
    }

    private fun startTicker(sessionId: String) {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (true) {
                val state = app.container.commandExecutor.readState()
                val session = state.sessions.firstOrNull { it.sessionId == sessionId }
                if (state.activeSessionId != sessionId || session == null || session.state == SessionState.RECOVERY_PENDING) {
                    stopSelf()
                    return@launch
                }
                if (session.state == SessionState.PAUSED) {
                    getSystemService(android.app.NotificationManager::class.java)?.notify(
                        FocusReminder.FOREGROUND_ID,
                        FocusReminder.foreground(this@SessionRuntimeService, null),
                    )
                    kotlinx.coroutines.delay(TimeUnit.SECONDS.toMillis(15))
                    continue
                }
                val now = System.currentTimeMillis()
                val elapsed = state.segments.filter { it.sessionId == sessionId }.sumOf { segment ->
                    val end = segment.endWallMs ?: now
                    if (end <= segment.startWallMs) 0L else (end - segment.startWallMs) / 1000
                }
                val remaining = session.targetSeconds?.let { (it - elapsed).coerceAtLeast(0) }
                getSystemService(android.app.NotificationManager::class.java)?.notify(
                    FocusReminder.FOREGROUND_ID,
                    FocusReminder.foreground(this@SessionRuntimeService, remaining),
                )
                if (remaining == 0L) {
                    val result = app.container.commandExecutor.dispatch(
                        DomainCommand.FinishSession(
                            sessionId = sessionId,
                            completion = CompletionChoice.AfterSeconds(elapsed.coerceAtMost(session.targetSeconds ?: elapsed)),
                        ),
                    )
                    if (result is Executed.Applied) {
                        FocusReminder.countdownFinished(
                            this@SessionRuntimeService,
                            sessionId = sessionId,
                            allowSound = reminderFlag(AppViewModel.KEY_SOUND),
                            allowVibration = reminderFlag(AppViewModel.KEY_VIBRATION),
                        )
                        owner.release(sessionId)
                        stopSelf()
                        return@launch
                    }
                }
                app.container.commandExecutor.dispatch(DomainCommand.Heartbeat(sessionId))
                kotlinx.coroutines.delay(TimeUnit.SECONDS.toMillis(15))
            }
        }
    }

    private suspend fun reminderFlag(key: String): Boolean =
        app.container.database.supportDao().setting(key)?.let { it != "0" } ?: true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (requestedId != null && requestedId != owner.sessionId()) owner.claim(requestedId)
        if (loopJob?.isActive == true) return START_STICKY
        loopJob = serviceScope.launch {
            assessRecoveryAtStart()
            val state = app.container.commandExecutor.readState()
            val active = state.activeSessionId
            if (active != null && !owner.owns(active)) {
                owner.release(requestedId ?: active)
                stopSelf(startId)
                return@launch
            }
            if (active == null) {
                requestedId?.let(owner::release)
                stopSelf(startId)
                return@launch
            }
            owner.sessionId()?.let { startTicker(it) } ?: stopSelf(startId)
        }
        return START_STICKY
    }

    private suspend fun assessRecoveryAtStart() {
        val state = app.container.commandExecutor.readState()
        val active = state.activeSessionId ?: return
        if (!owner.owns(active)) app.container.assessRecovery(state, active)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

/** Schedules a one-shot deadline; the receiver is only a wake-up hint, DB state remains authoritative. */
object SessionDeadline {
    fun schedule(context: Context, sessionId: String, targetSeconds: Long, startedAtWallMs: Long) {
        val intent = Intent(context, SessionDeadlineReceiver::class.java).putExtra(SessionRuntimeService.EXTRA_SESSION_ID, sessionId)
        val pending = PendingIntent.getBroadcast(context, sessionId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = startedAtWallMs + TimeUnit.SECONDS.toMillis(targetSeconds)
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }
}

class SessionDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(SessionRuntimeService.EXTRA_SESSION_ID) ?: return
        val serviceIntent = Intent(context, SessionRuntimeService::class.java)
            .putExtra(SessionRuntimeService.EXTRA_SESSION_ID, sessionId)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}
