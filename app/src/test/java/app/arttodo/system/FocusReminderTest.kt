package app.arttodo.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusReminderTest {
    @Test
    fun public_lock_screen_content_contains_only_minimal_session_state() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = FocusReminder.foreground(context, 125)
        val publicVersion = notification.publicVersion
        assertThat(publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo("专注进行中")
        assertThat(publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            .isEqualTo("剩余时间 2:05")
        assertThat(publicVersion.visibility).isEqualTo(android.app.Notification.VISIBILITY_PUBLIC)
    }

    @Test
    fun deadline_uses_the_remaining_running_time_and_can_be_cancelled_by_session() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        val sessionId = "deadline-session"
        val before = System.currentTimeMillis()

        SessionDeadline.schedule(context, sessionId, targetSeconds = 900, elapsedSeconds = 600, nowWallMs = before)
        val scheduled = shadowOf(alarmManager).nextScheduledAlarm
        assertThat(scheduled).isNotNull()
        assertThat(scheduled!!.triggerAtTime).isAtLeast(before + 299_000)
        assertThat(scheduled.triggerAtTime).isAtMost(before + 301_000)

        SessionDeadline.cancel(context, sessionId)
        assertThat(shadowOf(alarmManager).nextScheduledAlarm).isNull()
    }

    @Test
    fun countdown_result_sets_a_durable_session_scoped_pending_marker_without_private_extras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(FocusReminder.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertThat(FocusReminder.countdownFinished(context, "session:pending", false, false)).isFalse()

        assertThat(FocusReminder.isResultPending(context, "session:pending")).isTrue()
        assertThat(FocusReminder.isResultPending(context, "session:other")).isFalse()
        FocusReminder.clearResultPending(context, "session:pending")
        assertThat(FocusReminder.isResultPending(context, "session:pending")).isFalse()
    }

    @Test
    fun countdown_result_is_recorded_as_pending_even_when_notifications_are_disabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(FocusReminder.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.cancel(FocusReminder.RESULT_ID)

        val posted = FocusReminder.countdownFinished(context, "session:disabled", allowSound = false, allowVibration = true)

        assertThat(FocusReminder.isResultPending(context, "session:disabled")).isTrue()
        assertThat(posted).isFalse()
    }
}
