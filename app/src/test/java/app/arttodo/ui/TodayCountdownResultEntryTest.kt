package app.arttodo.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.DomainCommand
import app.arttodo.core.SessionMode
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import app.arttodo.data.WorkDomainMappers.toDomain
import app.arttodo.nav.ArtTodoNavHost
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.system.FocusReminder
import app.arttodo.ui.focus.FocusScreen
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioTheme
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
 * AC-07 / AC-17: a countdown that reached zero must stay reachable from the today page.
 *
 * The result screen lives on the focus route and only renders when that route is opened for the
 * session's own task. When the countdown ends in the background service — a notification arrived while
 * the app was away, or the screen was locked — nothing navigates anywhere: the session is finished and
 * the device marker says the result still awaits a decision, but the today page, which is the screen
 * the notification brings the user back to, showed nothing at all. The entry these tests pin down is
 * the way back to that result.
 *
 * The two rules that must not slip are AC-07's own: resting clears the marker and starts nothing, and
 * continuing starts nothing either — it returns to the picker where 开始 must be pressed again.
 *
 * Fixtures build the session through the shipped core and set the pending marker the way the
 * foreground service does, so both halves of the slice under test are the real ones.
 *
 * Gate target: `gradlew.bat testDebugUnitTest --tests "app.arttodo.ui.TodayCountdownResultEntryTest"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class TodayCountdownResultEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    private val container get() = application.container

    private val database get() = container.database

    /**
     * Hands the shared sandbox back as it was found.
     *
     * Tasks cascade to their sessions, segments, occurrences, events and ledger rows; the pending
     * markers live in the app's own preferences file, which no other class should inherit either.
     */
    @After
    fun removeTestTasks() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM task")
        application.getSharedPreferences(FocusReminder.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun clearTasks() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    /**
     * A daily task whose countdown ran to zero, with the pending marker still set.
     *
     * The session is aged before it is finished so the stored rows look exactly like a countdown that
     * really ran for [targetSeconds]; the marker is written by the same call the foreground service
     * makes when it stops the timer, because that marker is what makes the result pending (AC-17).
     */
    private suspend fun givenFinishedCountdown(title: String, targetSeconds: Long = 25 * 60): Pair<String, String> {
        clearTasks()
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNTDOWN, targetSeconds, null),
            "cmd-start:$title",
        )
        val sessionId = executor.readState().activeSessionId ?: error("the countdown did not start")
        backdate(sessionId, targetSeconds)
        executor.dispatch(DomainCommands.finishAfter(sessionId, targetSeconds), "cmd-finish:$title")
        FocusReminder.countdownFinished(application, sessionId, allowSound = false, allowVibration = false)
        assertThat(FocusReminder.isResultPending(application, sessionId)).isTrue()
        return taskId to sessionId
    }

    /** Moves the session's stored start instants back so it reads as work already invested. */
    private suspend fun backdate(sessionId: String, seconds: Long) {
        val dao = database.sessionDao()
        val backdatedBy = seconds * 1000
        val session = requireNotNull(dao.session(sessionId))
        dao.updateSession(session.copy(createdWallMs = session.createdWallMs - backdatedBy))
        dao.segments(sessionId)
            .filter { it.endWallMs == null }
            .forEach { segment -> dao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - backdatedBy)) }
    }

    private fun activeSessionId(): String? = runBlocking { container.commandExecutor.readState().activeSessionId }

    /** The session row's *domain* state: the stored column is a raw code, the enum is the contract. */
    private fun sessionState(sessionId: String) = sessionOf(sessionId)?.toDomain()?.state

    private fun sessionOf(sessionId: String) = runBlocking { database.sessionDao().session(sessionId) }

    private fun waitForAppState(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    /** The entry's own semantics node; waiting on it avoids clicking a stale tree. */
    private fun entryNodes() =
        compose.onAllNodesWithContentDescription("倒计时已结束", substring = true)

    private fun resultShown() = compose.onAllNodesWithText("这一段结束了").fetchSemanticsNodes().isNotEmpty()

    private fun renderGraph() {
        compose.setContent {
            StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) { ArtTodoNavHost() }
            }
        }
    }

    /**
     * The whole slice through the shipped graph: the notification's activity returns to the today page,
     * the entry is there, and pressing it opens the result for that very task.
     */
    @Test
    fun the_today_page_shows_the_pending_result_and_opens_it_for_that_task(): Unit = runBlocking {
        val title = "到点结果入口"
        val (taskId, sessionId) = givenFinishedCountdown(title)
        renderGraph()

        waitForAppState { entryNodes().fetchSemanticsNodes().isNotEmpty() }
        assertThat(entryNodes().fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithText("倒计时已结束").assertIsDisplayed()
        compose.onNodeWithText("$title · 有效投入 25 分钟").assertIsDisplayed()

        entryNodes()[0].performClick()

        waitForAppState { resultShown() }
        compose.onNodeWithText("休息").assertIsDisplayed()
        compose.onNodeWithText("继续专注").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        assertThat(sessionOf(sessionId)?.taskId).isEqualTo(taskId)
    }

    /**
     * Resting is a real answer: the marker is cleared, no timer starts, and the user lands back on the
     * today page with the entry gone (AC-07).
     */
    @Test
    fun resting_from_the_result_clears_the_marker_and_returns_to_today(): Unit = runBlocking {
        val title = "到点后选择休息"
        val (_, sessionId) = givenFinishedCountdown(title)
        renderGraph()

        waitForAppState { entryNodes().fetchSemanticsNodes().isNotEmpty() }
        entryNodes()[0].performClick()
        waitForAppState { resultShown() }

        compose.onNodeWithText("休息").performClick()

        waitForAppState {
            FocusReminder.isResultPending(application, sessionId).not() &&
                entryNodes().fetchSemanticsNodes().isEmpty()
        }
        // Back on the today page, and the choice started nothing.
        compose.onNodeWithContentDescription("新建任务").assertIsDisplayed()
        assertThat(FocusReminder.isResultPending(application, sessionId)).isFalse()
        assertThat(activeSessionId()).isNull()
        assertThat(sessionState(sessionId)).isEqualTo(SessionState.FINISHED)
    }

    /**
     * The empty-list guard: today's groups can all be empty (here: the task was archived after the
     * countdown ended) while a finished countdown still awaits an answer, so the entry must not sit
     * behind the empty state's early return — that would leave the result unreachable.
     */
    @Test
    fun the_result_entry_survives_an_empty_today_list(): Unit = runBlocking {
        val title = "空列表仍有结果"
        val (taskId, sessionId) = givenFinishedCountdown(title)
        container.commandExecutor.dispatch(DomainCommand.ArchiveTask(taskId = taskId), "cmd-archive:$title")

        val viewModel = AppViewModel(container)
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

        // Archiving empties every group on the page, so the empty state is what renders below the
        // entry; waiting happens after `setContent` because that is what pumps the looper here.
        waitForAppState {
            viewModel.state.value.tasks.isEmpty() &&
                viewModel.state.value.completedCountdownSession?.sessionId == sessionId
        }
        assertThat(viewModel.state.value.tasks).isEmpty()
        compose.onNodeWithText("今天还是一张空白的纸").assertIsDisplayed()

        waitForAppState { entryNodes().fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("倒计时已结束").assertIsDisplayed()
        compose.onNodeWithText("$title · 有效投入 25 分钟").assertIsDisplayed()
    }

    /**
     * Returning to the app re-reads the core.
     *
     * On a device the timer's service ends the countdown by writing straight to the core, and the
     * notification's tap resumes the existing activity instead of launching a new one, so nothing in
     * the ViewModel would notice without a resume hook (AC-17). The lifecycle owner here is driven by
     * the test — the real intent/`SINGLE_TOP` path is device-level and stays unverified.
     */
    @Test
    fun returning_to_the_app_re_reads_a_countdown_that_ended_in_the_background(): Unit = runBlocking {
        val title = "后台到点返回"
        clearTasks()
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId

        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) { ArtTodoNavHost() }
                }
            }
        }
        waitForAppState { compose.onAllNodesWithContentDescription("新建任务").fetchSemanticsNodes().isNotEmpty() }
        assertThat(entryNodes().fetchSemanticsNodes()).isEmpty()

        // The background service's work, done behind the graph's back: the countdown runs to zero.
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNTDOWN, 25 * 60L, null),
            "cmd-start:$title",
        )
        val sessionId = executor.readState().activeSessionId ?: error("the countdown did not start")
        backdate(sessionId, 25 * 60L)
        executor.dispatch(DomainCommands.finishAfter(sessionId, 25 * 60L), "cmd-finish:$title")
        FocusReminder.countdownFinished(application, sessionId, allowSound = false, allowVibration = false)
        assertThat(entryNodes().fetchSemanticsNodes()).isEmpty()

        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.RESUMED

        waitForAppState { entryNodes().fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("倒计时已结束").assertIsDisplayed()
    }

    /**
     * "Continue" is not "start again": it consumes the result, returns to the picker with the same
     * minutes preselected, and starts nothing. Only 开始 starts a timer (AC-07, Q20/Q35/Q36).
     */
    @Test
    fun continuing_from_the_result_requires_another_explicit_start(): Unit = runBlocking {
        val title = "继续须再按开始"
        val (taskId, sessionId) = givenFinishedCountdown(title)

        val viewModel = AppViewModel(container)
        var closed = 0
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    FocusScreen(taskId = taskId, onClose = { closed++ })
                }
            }
        }
        waitForAppState { resultShown() }

        compose.onNodeWithText("继续专注").performClick()

        // Nothing started, the result was consumed, and the mode picker is back in front of the user.
        waitForAppState {
            viewModel.state.value.completedCountdownSession == null &&
                compose.onAllNodesWithText("开始").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertThat(activeSessionId()).isNull()
        assertThat(FocusReminder.isResultPending(application, sessionId)).isFalse()
        assertThat(compose.onAllNodesWithText("这一段结束了").fetchSemanticsNodes()).isEmpty()
        assertThat(closed).isEqualTo(0)

        // 开始 is the one control that starts a timer, and it keeps the countdown length.
        compose.onNodeWithText("开始").performClick()
        waitForAppState { activeSessionId() != null }
        val restarted = runBlocking { container.commandExecutor.readState() }
        val started = restarted.sessions.first { it.sessionId == restarted.activeSessionId }
        assertThat(started.taskId).isEqualTo(taskId)
        assertThat(started.mode).isEqualTo(SessionMode.COUNTDOWN)
        assertThat(started.targetSeconds).isEqualTo(25 * 60L)
    }

    /** A lifecycle the test can resume, standing in for the activity the notification returns to. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
