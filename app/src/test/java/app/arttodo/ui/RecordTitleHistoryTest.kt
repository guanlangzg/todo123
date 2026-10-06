package app.arttodo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.SessionMode
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.ui.record.RECORD_LIST_TAG
import app.arttodo.ui.record.RecordScreen
import app.arttodo.ui.theme.StudioTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-14 on the records page: the automatic rows keep the name the time was actually recorded under.
 *
 * 规格 3 requires past timing detail to be presented under the title version of the time it happened
 * (「过去计时明细和临时任务完成事件按当时的标题版本呈现」), and 关键页面说明 section 3.2 states the
 * rule for the page: the detail's task name is the snapshot, with 「当前名称：…」 added next to it so the
 * user can still match an old row to today's task. Before this slice existed the page could only show
 * the task's current title, so a rename silently rewrote what every earlier row appeared to say —
 * exactly the masquerade this test rules out.
 *
 * The whole slice runs for real: `AppViewModel` -> `CommandExecutor` -> the Rust core -> Room -> the
 * record page. The rename happens through the ViewModel while the timer runs, so it is the shipped
 * `RenameTask` path (which cuts the running interval) that produces the two names.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `RecordTitleHistoryTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class RecordTitleHistoryTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    private val container get() = application.container

    private val database get() = container.database

    /**
     * Empties the task list before a case builds its own and hands the sandbox back afterwards.
     *
     * Robolectric shares one database file across every class in the run, and other classes assert on
     * an empty page or on their own single card, so a case must not leave its tasks behind. Tasks
     * cascade to their sessions, segments, occurrences, events and ledger rows.
     */
    private fun clearTasks() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    @After
    fun removeTestTasks() = clearTasks()

    private suspend fun backdateOpenSegment(sessionId: String, seconds: Long) {
        val dao = database.sessionDao()
        dao.segments(sessionId)
            .filter { it.endWallMs == null }
            .forEach { segment -> dao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - seconds * 1000)) }
    }

    private fun renderRecord(viewModel: AppViewModel) {
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    RecordScreen()
                }
            }
        }
    }

    private fun waitForAppState(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    /**
     * One task-day that spans a rename: 10 minutes were invested under 「晨间速写」, then the task is
     * renamed to 「木刻练习」 while the timer keeps running, and 5 more minutes are invested.
     *
     * The command ids are left to the writer (each one is generated per call) because the sandbox's
     * `command_log` outlives `DELETE FROM task`: a literal id would be deduplicated on a second run
     * and the fixture would silently build nothing.
     */
    private suspend fun givenARenamedTaskDay(viewModel: AppViewModel): String {
        clearTasks()
        val executor = container.commandExecutor
        val oldName = "晨间速写"
        val newName = "木刻练习"
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, oldName, ""))
        val taskId = executor.readState().tasks.single { it.title == oldName }.taskId
        executor.dispatch(DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null))
        val sessionId = executor.readState().activeSessionId ?: error("the session did not start")
        backdateOpenSegment(sessionId, 600)

        // The rename is dispatched exactly as the interface does it, while the timer is running.
        viewModel.renameTask(taskId, newName)
        waitForAppState { viewModel.state.value.tasks.any { it.title == newName } }
        backdateOpenSegment(sessionId, 300)
        viewModel.finishSession(sessionId)
        waitForAppState { viewModel.state.value.activeSession == null }
        return taskId
    }

    @Test
    fun an_automatic_row_keeps_the_name_it_was_recorded_under(): Unit = runBlocking {
        val viewModel = AppViewModel(container)
        renderRecord(viewModel)
        waitForAppState { viewModel.state.value.today.isNotBlank() }
        val today = viewModel.state.value.today

        val taskId = givenARenamedTaskDay(viewModel)

        // The core booked two automatic rows, one per name, and the task itself carries the new name.
        val rows = database.ledgerDao().forTaskDay(taskId, today)
        assertThat(rows.map { it.kind }).containsExactly(0, 0)
        assertThat(rows.first().deltaSeconds ?: 0L).isAtLeast(600L)
        assertThat(rows.last().deltaSeconds ?: 0L).isAtLeast(300L)
        assertThat(viewModel.state.value.tasks.single().title).isEqualTo("木刻练习")

        // The detail row still shows the old name; the current name is shown beside it rather than
        // overwriting it (关键页面说明 section 3.2 「不覆盖旧名」).
        compose.onNodeWithTag(RECORD_LIST_TAG).performScrollToNode(hasText("晨间速写"))
        compose.onNodeWithText("晨间速写").assertIsDisplayed()
        compose.onNodeWithText("当前名称：木刻练习").assertIsDisplayed()
        // The task group still carries today's name, so an old row remains matchable to its task.
        assertThat(compose.onAllNodesWithText("木刻练习").fetchSemanticsNodes()).isNotEmpty()
    }

    /**
     * A manual add or a set-total row describes no span of time, so it has no name of its own: the
     * page must not grow a 「当前名称：…」 line — or any other historical name — for it (规格 3 设计默认:
     * only the automatic pieces are versioned by the rename).
     */
    @Test
    fun a_manual_row_never_borrows_a_historical_name(): Unit = runBlocking {
        val viewModel = AppViewModel(container)
        renderRecord(viewModel)
        waitForAppState { viewModel.state.value.today.isNotBlank() }
        val today = viewModel.state.value.today

        val taskId = givenARenamedTaskDay(viewModel)
        viewModel.addManualSeconds(taskId, today, 300)
        waitForAppState { viewModel.state.value.ledger.count { it.taskId == taskId } == 3 }
        viewModel.setDailyTotal(taskId, today, 1_500)
        waitForAppState { viewModel.state.value.ledger.count { it.taskId == taskId } == 4 }

        compose.onNodeWithTag(RECORD_LIST_TAG).performScrollToNode(hasText("手动增加"))
        compose.onNodeWithText("手动增加").assertIsDisplayed()
        compose.onNodeWithText("设为当日总量").assertIsDisplayed()
        // Exactly one row holds a past name — the renamed automatic one. The manual and set-total rows
        // produced none, so nothing on this page presents today's title as their history.
        compose.onAllNodesWithText("当前名称：木刻练习").assertCountEquals(1)
        compose.onNodeWithText("晨间速写").assertIsDisplayed()
    }

    /**
     * An archived task keeps its history on the page, and the history must read as names rather than
     * as `task:…` ids: `UiState.tasks` deliberately holds only the live list, so an archived task is
     * resolved from the archived rows and the core's own task list (AC-10 / AC-14).
     */
    @Test
    fun an_archived_task_keeps_its_title_instead_of_its_raw_id(): Unit = runBlocking {
        val viewModel = AppViewModel(container)
        renderRecord(viewModel)
        waitForAppState { viewModel.state.value.today.isNotBlank() }

        val taskId = givenARenamedTaskDay(viewModel)
        viewModel.archiveTask(taskId)
        waitForAppState {
            viewModel.state.value.tasks.none { it.taskId == taskId } &&
                viewModel.state.value.archivedTasks.any { it.taskId == taskId }
        }

        compose.onNodeWithTag(RECORD_LIST_TAG).performScrollToNode(hasText("晨间速写"))
        compose.onNodeWithText("晨间速写").assertIsDisplayed()
        // The current name is still resolved for an archived task, which is what keeps the row's
        // 「当前名称：…」 line and the group header from degrading to the raw id.
        compose.onNodeWithText("当前名称：木刻练习").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(taskId).fetchSemanticsNodes()).isEmpty()
    }
}
