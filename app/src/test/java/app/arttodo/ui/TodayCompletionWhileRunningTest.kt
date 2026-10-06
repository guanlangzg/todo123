package app.arttodo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.SessionMode
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.ui.theme.StudioTheme
import app.arttodo.ui.today.COMPLETION_DAY_TAG_PREFIX
import app.arttodo.ui.today.TodayScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AC-07 / AC-08 on the today screen: ticking a task whose timer is running.
 *
 * 规格 5.1 requires the user to confirm first, the running session to be ended and saved in the same
 * step, and — when the session crossed midnight — a single choice of the day the completion belongs
 * to, with today highlighted. Before this test existed the tick called `SetOccurrenceCompletion`
 * directly: no confirmation appeared, the completion was recorded while the timer kept running (so
 * the invested time stayed unbooked), and a cross-midnight session silently completed *today*.
 *
 * The session row a device would hold is built here through the real core, and the assertions read
 * Room, so both halves of the slice are the shipped ones.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `TodayCompletionWhileRunningTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class TodayCompletionWhileRunningTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    private val container get() = application.container

    private val database get() = container.database

    /**
     * Empties the task list before a case builds its own.
     *
     * Robolectric shares one database file across test classes, so a page under test would otherwise
     * render whatever an earlier class left behind — and a card that sits below the fold of a long
     * list is never composed, which is not what these tests are about. Tasks cascade to their
     * sessions, segments, occurrences, events and ledger rows; the installation's zone epoch and
     * settings are deliberately left alone.
     */
    private fun clearTasks() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    /**
     * Leaves the shared sandbox as it was found.
     *
     * Robolectric reuses one database file for every class in the run, and several tests elsewhere
     * assume an empty page (they assert `tasks.size == 1` or wait for their own card to be the visible
     * one), so a case must not hand its data on.
     */
    @After
    fun removeTestTasks() {
        clearTasks()
    }

    /** A daily task with a running count-up session; returns the task id and the session id. */
    private suspend fun givenTimedDailyTask(title: String): Pair<String, String> {
        clearTasks()
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null),
            "cmd-start:$title",
        )
        val sessionId = executor.readState().activeSessionId ?: error("the session did not start")
        return taskId to sessionId
    }

    /**
     * Ages the session: its creation instant and its open segment both move back by [seconds], which
     * is indistinguishable from a session that has been running that long.
     */
    private suspend fun backdate(sessionId: String, seconds: Long) {
        val dao = database.sessionDao()
        val backdatedBy = seconds * 1000
        val session = requireNotNull(dao.session(sessionId))
        dao.updateSession(session.copy(createdWallMs = session.createdWallMs - backdatedBy))
        dao.segments(sessionId)
            .filter { it.endWallMs == null }
            .forEach { segment -> dao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - backdatedBy)) }
    }

    private fun renderToday(viewModel: AppViewModel): AppViewModel {
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    TodayScreen(onOpenFocus = {})
                }
            }
        }
        return viewModel
    }

    /**
     * Waits for the card's own completion control, not merely for the ViewModel state: a state flip
     * can land before the recomposition that renders it, and clicking then would hit a stale tree.
     */
    private fun waitForCompletionControl(title: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("完成 $title").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForAppState(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    /** The app date the domain assigns to an instant; the test never derives a date itself. */
    private fun appDateOf(wallMs: Long): String {
        val state = runBlocking { container.commandExecutor.readState() }
        return when (val date = container.domainBridge.appDateOf(wallMs, state.zoneId, state.zoneEpochSeq.toInt())) {
            is DomainResult.Success -> date.value.iso
            is DomainResult.Failure -> error("no app date: ${date.failure}")
        }
    }

    private fun sessionState(sessionId: String): Int =
        runBlocking { requireNotNull(database.sessionDao().session(sessionId)).state }

    private fun completedAppDates(taskId: String): List<String> = runBlocking {
        val occurrence = database.occurrenceDao().all().filter { it.taskId == taskId }
        occurrence.flatMap { row -> database.occurrenceDao().eventsFor(row.occurrenceId) }
            .filter { it.action == 0 }
            .map { it.appDate }
    }

    /** The simple case: confirm, then the session ends and the completion lands on today. */
    @Test
    fun completing_a_timed_task_confirms_first_then_ends_and_saves(): Unit = runBlocking {
        val title = "计时中先确认"
        val (taskId, sessionId) = givenTimedDailyTask(title)
        backdate(sessionId, 300)
        val today = appDateOf(System.currentTimeMillis())
        val viewModel = renderToday(AppViewModel(container))
        waitForCompletionControl(title)

        compose.onNodeWithContentDescription("完成 $title").performClick()
        compose.waitForIdle()

        // AC-07: the confirmation comes before anything is written.
        compose.onNodeWithText("这件任务正在计时").assertIsDisplayed()
        assertThat(sessionState(sessionId)).isEqualTo(0)
        assertThat(viewModel.state.value.activeSession?.sessionId).isEqualTo(sessionId)

        compose.onNodeWithText("结束并完成").performClick()
        waitForAppState { viewModel.state.value.activeSession == null }

        assertThat(sessionState(sessionId)).isEqualTo(2)
        assertThat(database.sessionDao().activeSlot()).isNull()
        assertThat(completedAppDates(taskId)).containsExactly(today)
        // The 5 minutes the timer had run are booked, not lost with the completion.
        val rows = database.ledgerDao().all().filter { it.taskId == taskId }
        assertThat(rows).isNotEmpty()
        assertThat(rows.sumOf { it.deltaSeconds ?: 0 }).isAtLeast(290L)
    }

    /** Cancelling keeps both the timer and the completion state exactly as they were. */
    @Test
    fun cancelling_the_confirmation_keeps_the_session_running(): Unit = runBlocking {
        val title = "取消保持计时"
        val (taskId, sessionId) = givenTimedDailyTask(title)
        val viewModel = renderToday(AppViewModel(container))
        waitForCompletionControl(title)

        compose.onNodeWithContentDescription("完成 $title").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        assertThat(sessionState(sessionId)).isEqualTo(0)
        assertThat(viewModel.state.value.activeSession?.sessionId).isEqualTo(sessionId)
        assertThat(completedAppDates(taskId)).isEmpty()
    }

    /**
     * AC-08: a session that crossed midnight must offer the two days, highlight today, and complete
     * only the chosen one.
     */
    @Test
    fun completing_a_session_that_crossed_midnight_offers_both_days_with_today_first(): Unit =
        runBlocking {
            val title = "跨午夜完成日期"
            val (taskId, sessionId) = givenTimedDailyTask(title)
            // Started one day ago: the session's own start date is yesterday's application date.
            backdate(sessionId, 86_400)
            val yesterday = appDateOf(System.currentTimeMillis() - 86_400_000)
            val today = appDateOf(System.currentTimeMillis())
            assertThat(yesterday).isNotEqualTo(today)

            val viewModel = renderToday(AppViewModel(container))
            waitForCompletionControl(title)

            compose.onNodeWithContentDescription("完成 $title").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("这一次投入跨了两天，今天完成的应该是哪一天？").assertIsDisplayed()
            // Today is highlighted by default; yesterday is offered but not preselected.
            compose.onNodeWithTag(COMPLETION_DAY_TAG_PREFIX + today).assertIsSelected()
            compose.onNodeWithTag(COMPLETION_DAY_TAG_PREFIX + yesterday).assertIsNotSelected()
            compose.onNodeWithTag(COMPLETION_DAY_TAG_PREFIX + yesterday).performClick()
            compose.onNodeWithTag(COMPLETION_DAY_TAG_PREFIX + yesterday).assertIsSelected()
            compose.onNodeWithText("就选这天").performClick()
            waitForAppState { viewModel.state.value.activeSession == null }

            assertThat(sessionState(sessionId)).isEqualTo(2)
            assertThat(completedAppDates(taskId)).containsExactly(yesterday)
            assertThat(completedAppDates(taskId)).doesNotContain(today)
        }

    /** Completing an *untimed* task keeps the one-tap path: no confirmation, no session involved. */
    @Test
    fun completing_an_untimed_task_needs_no_confirmation(): Unit = runBlocking {
        val title = "没有计时直接完成"
        clearTasks()
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId
        val viewModel = renderToday(AppViewModel(container))
        waitForCompletionControl(title)

        compose.onNodeWithContentDescription("完成 $title").performClick()
        waitForAppState { viewModel.state.value.temporaryCompletion[taskId] == true }

        assertThat(database.occurrenceDao().temporaryEventsFor(taskId).map { it.action }).containsExactly(0)
    }
}
