package app.arttodo.system

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.SessionMode
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

/**
 * AC-09 / 架构契约 §7.3: what makes an active session "awaiting adjudication" after a process restart.
 *
 * The entry condition has exactly three clauses: the row says `Running`/`Paused`, the active slot
 * still points at it, and **this process** does not hold it in its in-memory `SessionOwner`. The
 * heartbeat's age, and whether the wall clock and the elapsed anchor agree, decide only how long the
 * gap is once the session is pending — they are never the test (架构契约 §7.3, AC覆盖表 N7).
 *
 * The observed defect: a process death and reopen inside one boot left the heartbeat looking exactly
 * as plausible as a live session's, so the app claimed ownership again, never prompted, and the
 * segment kept running — the entire dead span was then booked as investment on the next finish.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `SessionRecoveryEntryTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecoveryEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val container
        get() = (ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication).container

    /** A running session whose heartbeat is exactly what the previous process wrote to disk. */
    private suspend fun runningSession(title: String): String {
        val executor = container.commandExecutor
        executor.readState().activeSessionId?.let { executor.dispatch(DomainCommands.finishNow(it)) }
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, title, ""), "cmd-task:$title")
        val taskId = executor.readState().tasks.first { it.title == title }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null),
            "cmd-start:$title",
        )
        return executor.readState().activeSessionId ?: error("the session did not start")
    }

    /** The Robolectric sandbox shares one database file with every other class in the run. */
    @After
    fun removeTestTasks() {
        container.database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    /**
     * A restart inside the same boot: the disk state is a plausible live session, but no in-memory
     * owner exists, so the user must adjudicate before anything is booked.
     */
    @Test
    fun a_restart_inside_the_same_boot_awaits_the_user_instead_of_reclaiming_the_session(): Unit =
        runBlocking {
            val executor = container.commandExecutor
            val sessionId = runningSession("同机重启判据")

            val state = executor.readState()
            val beat = state.heartbeats.single { it.sessionId == sessionId }
            // Nothing about the stored heartbeat looks wrong: same boot tag, same anchor instants the
            // dead process wrote. Only the process-local owner is empty.
            assertThat(beat.bootTag).isEqualTo(android.os.Build.FINGERPRINT)
            container.sessionOwner.release(sessionId)
            assertThat(container.sessionOwner.owns(sessionId)).isFalse()

            container.assessRecovery(state, sessionId)

            val after = executor.readState()
            assertThat(after.sessions.single { it.sessionId == sessionId }.state)
                .isEqualTo(SessionState.RECOVERY_PENDING)
            // The slot is kept so the prompt can resolve this very session, and no seconds have been
            // booked by the assessment itself.
            assertThat(after.activeSessionId).isEqualTo(sessionId)
            assertThat(after.ledger.count { it.taskId == state.sessions.single { s -> s.sessionId == sessionId }.taskId })
                .isEqualTo(0)
            assertThat(container.sessionOwner.owns(sessionId)).isFalse()
        }

    /**
     * The N7 negative case (same process, session still held): nothing may prompt, however old the
     * last heartbeat is. Here the heartbeat is old on disk and the session keeps running.
     */
    @Test
    fun a_session_this_process_holds_never_becomes_pending(): Unit = runBlocking {
        val executor = container.commandExecutor
        val sessionId = runningSession("本进程持有判据")
        assertThat(container.sessionOwner.owns(sessionId)).isTrue()

        val state = executor.readState()
        container.assessRecovery(state, sessionId)

        assertThat(executor.readState().sessions.single { it.sessionId == sessionId }.state)
            .isEqualTo(SessionState.RUNNING)
        assertThat(container.sessionOwner.owns(sessionId)).isTrue()
    }

    @Test
    fun a_paused_countdown_recovery_projection_does_not_double_count_its_closed_segment(): Unit = runBlocking {
        val executor = container.commandExecutor
        executor.readState().activeSessionId?.let { executor.dispatch(DomainCommands.finishNow(it)) }
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "暂停恢复预览", ""), "cmd-paused-projection-task")
        val taskId = executor.readState().tasks.first { it.title == "暂停恢复预览" }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNTDOWN, 600, null),
            "cmd-paused-projection-start",
        )
        val sessionId = requireNotNull(executor.readState().activeSessionId)
        val sessionDao = container.database.sessionDao()
        val session = requireNotNull(sessionDao.session(sessionId))
        sessionDao.segments(sessionId).single().let { segment ->
            sessionDao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - 300_000))
        }
        executor.dispatch(DomainCommands.pauseSession(sessionId), "cmd-paused-projection-pause")
        container.sessionOwner.release(sessionId)
        val state = executor.readState()
        container.assessRecovery(state, sessionId)

        val viewModel = app.arttodo.ui.AppViewModel(container)
        compose.setContent {
            val uiState by viewModel.state.collectAsState()
            androidx.compose.material3.Text("trusted=${uiState.recoveryAmounts?.trustedSeconds}")
            androidx.compose.material3.Text("gap=${uiState.recoveryAmounts?.gapSeconds}")
            androidx.compose.material3.Text("booked=${uiState.recoveryAmounts?.bookedSeconds}")
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("trusted=300").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("trusted=300").assertIsDisplayed()
        compose.onNodeWithText("gap=0").assertIsDisplayed()
        compose.onNodeWithText("booked=300").assertIsDisplayed()
    }

    @Test
    fun an_old_deadline_service_request_does_not_steal_the_active_session_owner(): Unit = runBlocking {
        val executor = container.commandExecutor
        val sessionId = runningSession("旧闹钟隔离")
        container.sessionOwner.claim(sessionId)
        val staleId = "session:stale-deadline"
        val controller = Robolectric.buildService(SessionRuntimeService::class.java)
        controller.create()

        controller.get().onStartCommand(
            android.content.Intent(ApplicationProvider.getApplicationContext<Context>(), SessionRuntimeService::class.java)
                .putExtra(SessionRuntimeService.EXTRA_SESSION_ID, staleId),
            0,
            41,
        )
        kotlinx.coroutines.delay(100)

        assertThat(container.sessionOwner.sessionId()).isEqualTo(sessionId)
        assertThat(executor.readState().activeSessionId).isEqualTo(sessionId)
        assertThat(executor.readState().sessions.single { it.sessionId == sessionId }.state)
            .isEqualTo(SessionState.RUNNING)
        controller.destroy()
    }

    @Test
    fun a_due_deadline_finishes_the_session_without_waiting_for_the_ticker(): Unit = runBlocking {
        val executor = container.commandExecutor
        executor.readState().activeSessionId?.let { executor.dispatch(DomainCommands.finishNow(it)) }
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "到点立即结束", ""), "cmd-deadline-task")
        val taskId = executor.readState().tasks.first { it.title == "到点立即结束" }.taskId
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNTDOWN, 60, null),
            "cmd-deadline-start",
        )
        val sessionId = requireNotNull(executor.readState().activeSessionId)
        val dao = container.database.sessionDao()
        val session = requireNotNull(dao.session(sessionId))
        dao.updateSession(session.copy(createdWallMs = session.createdWallMs - 60_000))
        dao.segments(sessionId).single().let { segment ->
            dao.upsertSegment(segment.copy(startWallMs = segment.startWallMs - 60_000))
        }
        container.sessionOwner.claim(sessionId)
        val controller = Robolectric.buildService(SessionRuntimeService::class.java)
        controller.create()

        controller.get().onStartCommand(
            android.content.Intent(ApplicationProvider.getApplicationContext<Context>(), SessionRuntimeService::class.java)
                .putExtra(SessionRuntimeService.EXTRA_SESSION_ID, sessionId),
            0,
            42,
        )
        kotlinx.coroutines.delay(100)

        val after = executor.readState()
        assertThat(after.activeSessionId).isNull()
        assertThat(after.sessions.single { it.sessionId == sessionId }.state).isEqualTo(SessionState.FINISHED)
        assertThat(FocusReminder.isResultPending(ApplicationProvider.getApplicationContext<Context>(), sessionId)).isTrue()
        controller.destroy()
    }

    /** A finished session is not recoverable, and the assessment must not touch it. */
    @Test
    fun a_finished_session_is_left_alone(): Unit = runBlocking {
        val executor = container.commandExecutor
        val sessionId = runningSession("已结束判据")
        executor.dispatch(DomainCommands.finishNow(sessionId), "cmd-finish:$sessionId")
        val state = executor.readState()

        container.assessRecovery(state, sessionId)

        assertThat(executor.readState().sessions.single { it.sessionId == sessionId }.state)
            .isEqualTo(SessionState.FINISHED)
    }
}
