package app.arttodo.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.data.AppDatabase
import app.arttodo.data.AppSettings
import app.arttodo.data.BackupException
import app.arttodo.data.BackupManager
import app.arttodo.domain.bridge.UniffiDomainBridge
import app.arttodo.ui.AppViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ③ The daily recovery point belongs to the *application* natural day, and every data change of the
 * day — a settings toggle included — crosses that boundary (架构契约 §8.4).
 *
 * §2 defines what a day is: the civil day of the fixed application zone, named by the core. Keying
 * the point by a UTC calendar date instead draws the boundary at a moment the user never sees and can
 * produce two "daily" points inside one application day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryPointDayBoundaryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var coordinator: RecoveryPointCoordinator
    private var now = 0L

    /**
     * In Asia/Shanghai both instants are 2026-03-10 (01:00 and 18:00), while their UTC dates are
     * 2026-03-09 and 2026-03-10 — so the two definitions of "day" disagree.
     */
    private val firstChangeOfAppDay = 1_773_075_600_000L
    private val laterOnSameAppDay = 1_773_136_800_000L

    /** 2026-03-10T16:00Z = 2026-03-11T00:00 in Asia/Shanghai: the next application day. */
    private val startOfNextAppDay = 1_773_158_400_000L

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.build(context, "recovery-point-day.db")
        database.openHelper.writableDatabase
        manager = BackupManager(
            context,
            database,
            wallClock = { now },
            // The production resolver: the epoch table first, the core deciding the label.
            dayLabelOf = { wallMs -> AppDayLabel(database, UniffiDomainBridge()) { "Asia/Shanghai" }.of(wallMs) },
        )
        coordinator = RecoveryPointCoordinator(database, manager)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("recovery-point-day.db")
    }

    @Test
    fun the_daily_point_is_keyed_by_the_application_natural_day_not_a_utc_day(): Unit = runBlocking {
        now = firstChangeOfAppDay
        coordinator.beforeMutation()
        now = laterOnSameAppDay
        coordinator.beforeMutation()

        val daily = coordinator.list().filter { it.kind == 0 }
        assertThat(daily).hasSize(1)
        assertThat(daily.single().recordId).isEqualTo("daily:2026-03-10")
    }

    @Test
    fun the_next_application_day_gets_its_own_point(): Unit = runBlocking {
        now = firstChangeOfAppDay
        coordinator.beforeMutation()
        now = startOfNextAppDay
        coordinator.beforeMutation()

        val daily = coordinator.list().filter { it.kind == 0 }
        assertThat(daily).hasSize(2)
        assertThat(daily.map { it.recordId })
            .containsExactly("daily:2026-03-10", "daily:2026-03-11")
    }

    @Test
    fun the_daily_boundary_follows_a_manual_zone_change(): Unit = runBlocking {
        // 2026-03-10T17:00Z: Shanghai already reads 2026-03-11 01:00, New York still 2026-03-10 13:00.
        now = 1_773_162_000_000L
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) " +
                "VALUES (2, 'America/New_York', $now, 'manual', $now)",
        )
        coordinator.beforeMutation()
        coordinator.beforeMutation()
        assertThat(coordinator.list().filter { it.kind == 0 }).hasSize(1)
        // The newest epoch's zone decides the day, not the installation zone the pointer started with.
        assertThat(coordinator.list().first { it.kind == 0 }.recordId).isEqualTo("daily:2026-03-10")
    }

    @Test
    fun the_first_settings_change_of_the_day_lands_behind_a_recovery_point(): Unit = runBlocking {
        now = firstChangeOfAppDay

        coordinator.writeSetting(AppViewModel.KEY_SOUND, "0")

        assertThat(coordinator.list().filter { it.kind == 0 }).hasSize(1)
        assertThat(database.supportDao().setting(AppViewModel.KEY_SOUND)).isEqualTo("0")
        // The boundary is not reset by the change itself: the same day still has exactly one point.
        coordinator.writeSetting(AppViewModel.KEY_VIBRATION, "0")
        assertThat(coordinator.list().filter { it.kind == 0 }).hasSize(1)
    }

    @Test
    fun a_settings_change_whose_recovery_point_fails_is_not_written(): Unit = runBlocking {
        now = firstChangeOfAppDay
        // The "cannot capture" situation is injected at the day resolver: whatever the cause (no
        // storage, no room, a busy file), the boundary must refuse *before* the setting is written.
        val failing = BackupManager(
            context,
            database,
            wallClock = { now },
            dayLabelOf = { throw BackupException("RecoveryPointWriteFailed") },
        )
        val failingCoordinator = RecoveryPointCoordinator(database, failing)
        val thrown = assertThrows(BackupException::class.java) {
            runBlocking { failingCoordinator.writeSetting(AppViewModel.KEY_SOUND, "0") }
        }
        assertThat(thrown.code).isEqualTo("RecoveryPointWriteFailed")
        assertThat(database.supportDao().setting(AppViewModel.KEY_SOUND)).isNull()
        assertThat(failingCoordinator.list().filter { it.kind == 0 }).isEmpty()
    }

    @Test
    fun a_recovery_point_failure_is_never_silent_about_the_day(): Unit = runBlocking {
        now = firstChangeOfAppDay
        val unable = BackupManager(context, database, wallClock = { now }, dayLabelOf = { null })
        val thrown = assertThrows(BackupException::class.java) {
            runBlocking { unable.captureDailyIfNeeded() }
        }
        assertThat(thrown.code).isEqualTo("RecoveryPointDayUnavailable")
        assertThat(unable.recoveryPoints()).isEmpty()
    }

    @Test
    fun the_settings_keys_are_the_ones_the_reminder_service_reads(): Unit {
        assertThat(AppSettings.KEY_SOUND).isEqualTo("focus_sound_enabled")
        assertThat(AppSettings.KEY_VIBRATION).isEqualTo("focus_vibration_enabled")
        assertThat(AppSettings.KEY_APP_ZONE).isEqualTo("app_zone_id")
    }
}
