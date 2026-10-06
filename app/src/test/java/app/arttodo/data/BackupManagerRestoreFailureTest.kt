package app.arttodo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.Executed
import app.arttodo.domain.bridge.UniffiDomainBridge
import app.arttodo.system.RecoveryPointCoordinator
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
class BackupManagerRestoreFailureTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var executor: CommandExecutor
    private val now = 1_800_000_000_000L

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.build(context, "backup-prewrite.db")
        db.openHelper.writableDatabase
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) VALUES (1, 'UTC', 1, '', 1)",
        )
        db.taskDao().insert(TaskEntity("task:a", 0, "原数据", "原备注", 1024, now, null, 25, null))
        manager = BackupManager(context, db, wallClock = { now })
        val points = RecoveryPointCoordinator(db, manager)
        executor = CommandExecutor(
            beforeMutation = { points.beforeMutation() },
            database = db,
            bridge = UniffiDomainBridge(),
            zoneIdProvider = { "UTC" },
            wallClock = { now },
            elapsedClock = { 123_000 },
            bootTag = { "test-boot" },
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("backup-prewrite.db")
    }

    @Test
    fun a_failed_daily_snapshot_prevents_the_mutation_from_touching_live_data(): Unit = runBlocking {
        val taskBefore = db.taskDao().all().single()
        val pointsDir = java.io.File(context.filesDir, "recovery-points")
        pointsDir.deleteRecursively()
        pointsDir.mkdirs()
        // Create a filesystem obstruction that makes creating the target directory impossible.
        pointsDir.delete()
        pointsDir.writeText("not-a-directory")

        val result = executor.dispatch(
            app.arttodo.core.DomainCommand.RenameTask("task:a", "不应写入"),
        )

        assertThat(result).isInstanceOf(Executed.Rejected::class.java)
        assertThat(db.taskDao().all().single().title).isEqualTo(taskBefore.title)
        assertThat(db.taskDao().all().single().note).isEqualTo(taskBefore.note)
        pointsDir.delete()
    }
}
