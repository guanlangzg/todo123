package app.arttodo.system

import androidx.test.core.app.ApplicationProvider
import app.arttodo.data.BackupManager
import app.arttodo.data.AppDatabase
import app.arttodo.data.BackupRecordEntity
import app.arttodo.data.BackupStructure
import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryPointCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var coordinator: RecoveryPointCoordinator

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.build(context, "recovery-coordinator.db")
        database.openHelper.writableDatabase
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) VALUES (1, 'UTC', 1, '', 1)",
        )
        coordinator = RecoveryPointCoordinator(
            database,
            BackupManager(
                context,
                database,
                wallClock = { 1_800_000_000_000L },
                // The manager never derives a date itself; the day comes from the caller (架构契约 §8.4).
                dayLabelOf = { "2026-03-10" },
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("recovery-coordinator.db")
    }

    @Test
    fun before_mutation_creates_a_single_daily_point_and_reuses_it(): Unit = runBlocking {
        coordinator.beforeMutation()
        coordinator.beforeMutation()
        assertThat(coordinator.list().filter { it.kind == 0 }).hasSize(1)
    }
}
