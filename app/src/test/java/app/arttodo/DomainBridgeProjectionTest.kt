package app.arttodo

import app.arttodo.core.CompletionChoice
import app.arttodo.core.DomainCommand
import app.arttodo.core.LedgerRow
import app.arttodo.core.OccurrenceCompletionEvent
import app.arttodo.core.RecoveryChoice
import app.arttodo.core.SessionMode
import app.arttodo.core.TaskKind
import app.arttodo.core.TemporaryCompletionEvent
import app.arttodo.core.TimeBucket
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.domain.bridge.UniffiDomainBridge
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.arttodo.data.AppDatabase
import app.arttodo.data.DomainCommands
import app.arttodo.domain.CommandExecutor
import app.arttodo.data.WorkDomainMappers.toDomain

/**
 * Verifies the whole IDL surface is reachable through the single [app.arttodo.domain.bridge.DomainBridge]
 * seam and that each call really executes in Rust.
 *
 * The most important case is the AC-12 / N10 one: the calendar, the trend buckets, the heat map and
 * the task-share list must all report the same number, because they read the same projection source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DomainBridgeProjectionTest {

    private val bridge = UniffiDomainBridge()

    private fun <T> unwrap(result: DomainResult<T>): T {
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        return (result as DomainResult.Success<T>).value
    }

    /**
     * N10: a task timed once with the count-up mode and once with the countdown mode shares one
     * ledger, so all four statistics views must agree on a single total.
     */
    @Test
    fun all_four_statistics_views_report_the_same_total() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "proj-test.db")
            .allowMainThreadQueries()
            .build()
        val executor = CommandExecutor(
            database = database,
            bridge = bridge,
            zoneIdProvider = { "Asia/Shanghai" },
            wallClock = { BASE_MS },
        )

        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "共用账本", ""))
        val taskId = executor.readState().tasks.single().taskId
        val today = unwrap(bridge.appDateOf(BASE_MS, "Asia/Shanghai", 1)).iso

        // Count-up session: 900 s booked by the core.
        val startUp = executor.dispatch(DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null))
        assertThat(startUp).isInstanceOf(app.arttodo.domain.Executed.Applied::class.java)
        val upSession = executor.readState().activeSessionId!!
        executor.dispatch(DomainCommands.finishAfter(upSession, 900))

        // Countdown session on the same day: another 600 s.
        val startDown = executor.dispatch(DomainCommands.startSession(taskId, SessionMode.COUNTDOWN, 1_500L, null))
        assertThat(startDown).isInstanceOf(app.arttodo.domain.Executed.Applied::class.java)
        val downSession = executor.readState().activeSessionId!!
        executor.dispatch(DomainCommands.finishAfter(downSession, 600))

        val rows: List<LedgerRow> = database.ledgerDao().forTaskDay(taskId, today).map { it.toDomain() }
        assertThat(rows).isNotEmpty()

        val dayTotal = unwrap(bridge.projectDayTotals(rows)).single { it.appDate == today }.seconds
        val share = unwrap(bridge.projectTaskShares(rows)).single { it.taskId == taskId }.seconds
        val weekBucket = unwrap(bridge.projectPeriodTotals(rows, TimeBucket.WEEK))
            .sumOf { it.seconds }
        val monthBucket = unwrap(bridge.projectPeriodTotals(rows, TimeBucket.MONTH))
            .sumOf { it.seconds }

        // Both sessions land on one day: 900 + 600, and every view agrees.
        assertThat(dayTotal).isEqualTo(1_500L)
        assertThat(share).isEqualTo(1_500L)
        assertThat(weekBucket).isEqualTo(1_500L)
        assertThat(monthBucket).isEqualTo(1_500L)

        // The day label the calendar uses comes from the same day windows slicing uses.
        assertThat(unwrap(bridge.dayLabelOf(BASE_MS, "Asia/Shanghai", 1))).isEqualTo(today)

        database.close()
    }

    /** AC-03 / AC-07: completion truth is replayed from events, and the cache staleness is visible. */
    @Test
    fun completion_is_replayed_from_events() {
        val events = listOf(
            OccurrenceCompletionEvent(1, "occ:a:2026-02-25", 0, 1_000, "2026-02-25", 1u),
            OccurrenceCompletionEvent(2, "occ:a:2026-02-25", 1, 2_000, "2026-02-25", 1u),
        )
        assertThat(unwrap(bridge.occurrenceCompleted(events, "occ:a:2026-02-25"))).isFalse()

        val reopen = listOf(events.first())
        assertThat(unwrap(bridge.occurrenceCompleted(reopen, "occ:a:2026-02-25"))).isTrue()

        // A cache that only folded in event 1 is stale relative to the full log.
        val stale = app.arttodo.core.OccurrenceRecord(
            occurrenceId = "occ:a:2026-02-25",
            taskId = "task:a",
            appDate = "2026-02-25",
            zoneEpochSeq = 1u,
            displayTitleSnapshot = "t",
            createdAtMs = 0,
            isCompleted = true,
            completedWallMs = 1_000,
            derivedFromEventHighWater = 1,
        )
        assertThat(unwrap(bridge.occurrenceViewIsCurrent(stale, events))).isFalse()
    }

    /** AC-11: a temporary task's done state is replayed, not stored as a flag. */
    @Test
    fun temporary_completion_is_replayed() {
        val completed = listOf(
            TemporaryCompletionEvent(1, "task:t", 0, 1_000, "2026-02-25", "标题"),
        )
        assertThat(unwrap(bridge.temporaryCompleted(completed, "task:t"))).isTrue()
        assertThat(unwrap(bridge.temporaryCompleted(completed, "task:other"))).isFalse()

        val reopened = completed + TemporaryCompletionEvent(2, "task:t", 1, 2_000, "2026-02-25", "标题")
        assertThat(unwrap(bridge.temporaryCompleted(reopened, "task:t"))).isFalse()
    }

    /** AC-09: the three recovery choices book the amounts the contract fixes. */
    @Test
    fun recovery_amounts_are_decided_by_the_core() {
        val accept = unwrap(bridge.recoveryAmounts(0, 300_000, 900_000, RecoveryChoice.Accept, null))
        assertThat(accept.trustedSeconds).isEqualTo(300L)
        assertThat(accept.gapSeconds).isEqualTo(600L)
        assertThat(accept.bookedSeconds).isEqualTo(900L)

        val discard = unwrap(bridge.recoveryAmounts(0, 300_000, 900_000, RecoveryChoice.Discard, null))
        assertThat(discard.bookedSeconds).isEqualTo(300L)

        val capped = unwrap(bridge.recoveryAmounts(0, 300_000, 900_000, RecoveryChoice.Accept, 600L))
        assertThat(capped.bookedSeconds).isEqualTo(600L)
    }

    /** N5: the edit precondition is checkable before dispatch. */
    @Test
    fun edit_precondition_is_exposed() {
        assertThat(unwrap(bridge.validateEditLedgerEntry(1, 60L, null))).isTrue()
        assertThat(unwrap(bridge.validateEditLedgerEntry(2, null, 60L))).isTrue()
        // kind=1 with a set-total value is an invariant violation, reported not thrown.
        assertThat(bridge.validateEditLedgerEntry(1, null, 60L))
            .isInstanceOf(DomainResult.Failure::class.java)
        // A rejected edit must not be able to write: nothing to assert here beyond the failure.
        assertThat(bridge.validateEditLedgerEntry(1, null, 60L))
            .isInstanceOf(DomainResult.Failure::class.java)
    }

    private companion object {
        const val BASE_MS = 1_772_000_000_000L
    }
}
