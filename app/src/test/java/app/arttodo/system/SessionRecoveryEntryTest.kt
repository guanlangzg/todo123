package app.arttodo.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.SessionMode
import app.arttodo.core.SessionState
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
