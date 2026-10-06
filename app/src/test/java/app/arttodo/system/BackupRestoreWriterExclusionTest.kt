package app.arttodo.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.TaskKind
import app.arttodo.data.AppDatabase
import app.arttodo.data.BackupManager
import app.arttodo.data.DomainCommands
import app.arttodo.data.TaskEntity
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.Executed
import app.arttodo.domain.bridge.UniffiDomainBridge
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ② A whole-database replacement must not interleave with a command.
 *
 * 架构契约 §5.2 rule 1 makes `CommandExecutor` the single writer of the database: a command loads
 * state, asks the core, and commits — all inside one critical section. A restore that starts from a
 * UI coroutine breaks that: a command that read the *old* timeline can commit its effects into the
 * freshly restored file, so the restored database silently gains a row it never contained.
 *
 * The test recreates exactly that interleaving: the command is held inside its critical section
 * (before the effects are applied), the restore is started, and only then is the command allowed to
 * finish. The restored content may not grow a task that came from the previous timeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreWriterExclusionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var coordinator: RecoveryPointCoordinator

    /** Wired by the test before the coordinator, exactly as the application container wires them. */
    private var commandExecutor: CommandExecutor? = null

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.build(context, "backup-writer-exclusion.db")
        database.openHelper.writableDatabase
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO zone_epoch (epoch_seq, zone_id, effective_wall_ms, impact_summary, created_wall_ms) " +
                "VALUES (1, 'Asia/Shanghai', 1, 'initial', 1)",
        )
        manager = BackupManager(
            context,
            database,
            wallClock = { 1_800_000_000_000L },
            // This class is about the writer gate, not the day boundary, so the day is stated.
            dayLabelOf = { "2026-03-10" },
        )
        coordinator = RecoveryPointCoordinator(
            database,
            manager,
            // The production wiring: the coordinator's writes take the command writer's lock.
            exclusive = { block -> requireNotNull(commandExecutor).exclusive(block) },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("backup-writer-exclusion.db")
    }

    @Test
    fun a_restore_cannot_land_in_the_middle_of_a_command_and_keep_its_stale_write(): Unit = runBlocking {
        // The timeline the user is about to restore: one task, captured as a guard point.
        database.taskDao().insert(TaskEntity("task:live", 0, "恢复点里的任务", "", 1024, 1, null, 25, null))
        val guard = manager.capturePreRestore()
        // The newer timeline also has a task, but a restore replaces everything anyway.
        database.taskDao().insert(TaskEntity("task:newer", 1, "更新的一条", "", 2048, 2, null, 25, null))

        val held = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val executor = CommandExecutor(
            beforeMutation = { entered.complete(Unit); held.await() },
            database = database,
            bridge = UniffiDomainBridge(),
            zoneIdProvider = { "Asia/Shanghai" },
            wallClock = { 1_800_000_000_000L },
        )
        commandExecutor = executor

        val command = launch { executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "旧时间线任务", "")) }
        entered.await()

        var restoreFinished = false
        val restore = launch { coordinator.restore(guard); restoreFinished = true }
        // While a command holds the writer, the restore must not be able to run at all.
        val finishedWhileCommandHeld = withTimeoutOrNull(3_000) { restore.join(); true } ?: false

        held.complete(Unit)
        command.join()
        restore.join()

        assertThat(finishedWhileCommandHeld).isFalse()
        assertThat(restoreFinished).isTrue()
        // The restored file contains the guard point's rows and nothing the old timeline wrote into it.
        assertThat(database.taskDao().all().map { it.taskId }).containsExactly("task:live")
    }

    @Test
    fun a_command_that_arrives_after_a_restore_reads_the_restored_state(): Unit = runBlocking {
        database.taskDao().insert(TaskEntity("task:live", 0, "恢复点里的任务", "", 1024, 1, null, 25, null))
        val guard = manager.capturePreRestore()
        database.taskDao().insert(TaskEntity("task:newer", 1, "更新的一条", "", 2048, 2, null, 25, null))

        val executor = CommandExecutor(
            beforeMutation = { coordinator.beforeMutation() },
            database = database,
            bridge = UniffiDomainBridge(),
            zoneIdProvider = { "Asia/Shanghai" },
            wallClock = { 1_800_000_000_000L },
        )
        commandExecutor = executor
        coordinator.restore(guard)

        val executed = executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "恢复之后", ""))
        assertThat(executed).isInstanceOf(Executed.Applied::class.java)
        assertThat(database.taskDao().all().map { it.taskId }).contains("task:live")
        assertThat(executor.readState().tasks.map { it.title }).contains("恢复之后")
    }
}
