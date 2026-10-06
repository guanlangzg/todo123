package app.arttodo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.SessionMode
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import app.arttodo.data.WorkDomainMappers.toDomain
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.ui.focus.FocusScreen
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
 * AC-05 / AC-07 at the UI: the focus screen's stop control, driven through the shipped ViewModel into
 * the real Rust core and Room.
 *
 * The screen's clock and the stop button's label both show the session's *accumulated* seconds
 * (`elapsedSeconds` sums every segment, and the label reads 「已投入 …」), so that is the number the
 * command has to book. Two regressions live here:
 *
 *  * after a pause + resume the accumulated 10:00 was applied to the resumed segment alone, so the
 *    ledger stored 20:00 for a 10-minute session;
 *  * pressing 结束 while paused failed outright — Rust had no open segment to measure from — so a
 *    paused session could not be ended at all.
 *
 * The fixtures backdate the running segment (a stored start instant, exactly what a real session
 * would hold) instead of waiting on a clock; no Kotlin code computes the dates that are asserted.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `FocusFinishWiringTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class FocusFinishWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    private val container get() = application.container

    private val database get() = container.database

    /** A temporary task with a running count-up session, both created through the real core. */
    private suspend fun givenRunningSession(title: String): Pair<String, String> {
        val executor = container.commandExecutor
        // Leftovers from another test in the same sandbox would refuse a second session.
        executor.readState().activeSessionId?.let { executor.dispatch(DomainCommands.finishNow(it)) }
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null),
            "cmd-start:$title",
        )
        val sessionId = executor.readState().activeSessionId ?: error("the session did not start")
        return taskId to sessionId
    }

    /**
     * Moves the running segment's start back by [seconds] so the session reads as work already done.
     *
     * Only the stored instants change: the same rows the app writes on start, with an older start
     * time. That is what a session that has been running for ten minutes actually looks like on disk.
     */
    private suspend fun backdate(sessionId: String, seconds: Long) {
        val dao = container.database.sessionDao()
        val backdatedBy = seconds * 1000
        val session = requireNotNull(dao.session(sessionId))
        dao.updateSession(session.copy(createdWallMs = session.createdWallMs - backdatedBy))
        dao.segments(sessionId)
            .filter { it.endWallMs == null }
            .forEach { segment -> dao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - backdatedBy)) }
    }

    /** The investment the day's projections see, replayed by the core, not summed here. */
    private suspend fun bookedSeconds(taskId: String): Long {
        val rows = container.database.ledgerDao().all().filter { it.taskId == taskId }.map { it.toDomain() }
        return rows.groupBy { it.appDate }.values.sumOf { dayRows ->
            when (val total = container.domainBridge.replayDailyTotal(taskId, dayRows.first().appDate, dayRows)) {
                is DomainResult.Success -> total.value
                is DomainResult.Failure -> error("replay failed: ${total.failure}")
            }
        }
    }

    /** The sandbox database is shared with every other class in the run; hand it back empty. */
    @After
    fun removeTestTasks() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    private suspend fun sessionState(sessionId: String): Int =
        requireNotNull(container.database.sessionDao().session(sessionId)).state

    private fun renderFocus(taskId: String, viewModel: AppViewModel = AppViewModel(container)): AppViewModel {
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    FocusScreen(taskId = taskId, onClose = {})
                }
            }
        }
        return viewModel
    }

    private fun waitForAppState(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    /** Stop while paused: what the frozen clock shows is what must be saved (AC-07). */
    @Test
    fun stopping_a_paused_session_saves_what_the_clock_shows(): Unit = runBlocking {
        val (taskId, sessionId) = givenRunningSession("暂停后结束")
        backdate(sessionId, 600)
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.pauseSession(sessionId), "cmd-pause:$sessionId")
        val paused = bookedSeconds(taskId)
        assertThat(paused).isAtLeast(590L)

        val viewModel = renderFocus(taskId)
        waitForAppState { viewModel.state.value.activeSession != null }
        compose.onNode(hasContentDescription("结束本次专注", substring = true)).performClick()
        waitForAppState { viewModel.state.value.activeSession == null || viewModel.state.value.error != null }

        assertThat(viewModel.state.value.error).isNull()
        assertThat(sessionState(sessionId)).isEqualTo(2)
        assertThat(bookedSeconds(taskId)).isEqualTo(paused)
    }

    /** Stop after a resume: the closed span is never booked a second time (AC-05). */
    @Test
    fun stopping_after_a_resume_does_not_book_the_first_span_twice(): Unit = runBlocking {
        val (taskId, sessionId) = givenRunningSession("暂停再继续")
        backdate(sessionId, 600)
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.pauseSession(sessionId), "cmd-pause:$sessionId")
        executor.dispatch(DomainCommands.resumeSession(sessionId), "cmd-resume:$sessionId")
        val afterResume = bookedSeconds(taskId)
        assertThat(afterResume).isAtLeast(590L)

        val viewModel = renderFocus(taskId)
        waitForAppState { viewModel.state.value.activeSession != null }
        compose.onNode(hasContentDescription("结束本次专注", substring = true)).performClick()
        waitForAppState { viewModel.state.value.activeSession == null || viewModel.state.value.error != null }

        assertThat(viewModel.state.value.error).isNull()
        assertThat(sessionState(sessionId)).isEqualTo(2)
        // 600 s already booked + the few seconds of the resumed segment; the double count was ~1200.
        val total = bookedSeconds(taskId)
        assertThat(total).isAtLeast(afterResume)
        assertThat(total).isLessThan(afterResume + 120L)
    }
}
