package app.arttodo.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.DomainCommand
import app.arttodo.core.DomainOutcome
import app.arttodo.core.DomainState
import app.arttodo.core.LedgerEffect
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.data.AppDatabase
import app.arttodo.data.BackupException
import app.arttodo.data.DomainCommands
import app.arttodo.data.FieldLimits
import app.arttodo.domain.bridge.DomainBridge
import app.arttodo.domain.bridge.DomainFailure
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.domain.bridge.UniffiDomainBridge
import app.arttodo.ui.describe
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The failure channel of the single writer (④ and the second half of ③).
 *
 * `CommandExecutor` promises two things the UI depends on: a value the write boundary refuses must
 * arrive as `DomainFailure.Validation` (so the screen can point at the field), and a recovery point
 * that could not be captured must arrive as its own condition with its own sentence — never as the
 * generic "内部错误" catch-all. Both properties come from the *order* of the catch clauses, which is
 * exactly the kind of thing that silently regresses, so each one is pinned by a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandFailureChannelTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.build(context, "command-failure-channel.db")
        database.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("command-failure-channel.db")
    }

    /**
     * A core that always answers with one prepared outcome.
     *
     * Every other bridge member is the production one, so the envelope, the clock and the date
     * helpers stay real; only `reduce` is scripted. That is what lets a value which the core would
     * normally refuse reach the write boundary, which is the layer under test.
     */
    private class ScriptedBridge(private val outcome: DomainOutcome) : DomainBridge by UniffiDomainBridge() {
        override fun reduce(
            state: DomainState,
            command: DomainCommand,
            envelope: app.arttodo.core.CommandEnvelope,
        ): DomainResult<DomainOutcome> = DomainResult.Success(outcome)
    }

    private fun executor(
        bridge: DomainBridge = UniffiDomainBridge(),
        beforeMutation: suspend () -> Unit = {},
    ): CommandExecutor = CommandExecutor(
        beforeMutation = beforeMutation,
        database = database,
        bridge = bridge,
        zoneIdProvider = { "Asia/Shanghai" },
        wallClock = { now },
    )

    private fun oversizedTitleOutcome(state: DomainState): DomainOutcome = DomainOutcome(
        nextState = state,
        effects = listOf(
            LedgerEffect.UpsertTask(
                TaskRecord(
                    taskId = "task:oversized",
                    kind = TaskKind.TEMPORARY,
                    title = "x".repeat(FieldLimits.MAX_TITLE_CHARS + 1),
                    note = "",
                    sortKey = 1024,
                    createdWallMs = now,
                    archivedAtMs = null,
                    lastCountdownMinutes = 25u,
                    artAssetId = null,
                ),
            ),
        ),
        notices = emptyList(),
        error = null,
    )

    @Test
    fun a_value_the_write_boundary_refuses_is_reported_as_validation_not_an_internal_error(): Unit = runBlocking {
        val state = executor().readState()
        val executed = executor(ScriptedBridge(oversizedTitleOutcome(state)))
            .dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "x".repeat(200), ""))

        val failure = (executed as Executed.Rejected).failure
        assertThat(failure).isInstanceOf(DomainFailure.Validation::class.java)
        assertThat((failure as DomainFailure.Validation).code).isEqualTo("TitleTooLong")
        assertThat(failure.describe()).isEqualTo("输入无效（TitleTooLong）")
        // The refusal rolled the whole effect list back: nothing reached the file.
        assertThat(database.taskDao().all()).isEmpty()
        assertThat(database.supportDao().allCommands()).isEmpty()
    }

    @Test
    fun a_recovery_point_failure_has_its_own_sentence_instead_of_the_internal_catch_all(): Unit = runBlocking {
        val executed = executor(beforeMutation = { throw BackupException("RecoveryPointDayUnavailable") })
            .dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "任务", ""))

        val failure = (executed as Executed.Rejected).failure
        assertThat(failure).isInstanceOf(DomainFailure.RecoveryPointUnavailable::class.java)
        assertThat((failure as DomainFailure.RecoveryPointUnavailable).code).isEqualTo("RecoveryPointDayUnavailable")
        assertThat(failure.describe())
            .isEqualTo("无法创建本机恢复点，本次变更没有写入（RecoveryPointDayUnavailable）")
        assertThat(database.taskDao().all()).isEmpty()
        assertThat(database.supportDao().allCommands()).isEmpty()
    }

    @Test
    fun an_unexpected_writer_failure_still_arrives_as_an_internal_error(): Unit = runBlocking {
        val executed = executor(beforeMutation = { error("writer exploded") })
            .dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "任务", ""))

        val failure = (executed as Executed.Rejected).failure
        assertThat(failure).isInstanceOf(DomainFailure.Internal::class.java)
        assertThat(failure.describe()).startsWith("内部错误")
        assertThat(database.taskDao().all()).isEmpty()
    }
}
