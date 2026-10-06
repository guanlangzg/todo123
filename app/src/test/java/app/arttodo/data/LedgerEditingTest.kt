package app.arttodo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.DomainCommand
import app.arttodo.core.TaskKind
import app.arttodo.data.WorkDomainMappers.toDomain
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.Executed
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.domain.bridge.UniffiDomainBridge
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ledger operations the records screen depends on, driven end to end through Room and the real
 * Rust core.
 *
 * The records screen offers three mutations — add a manual contribution, set a day total, and delete a
 * single row with a session-scoped undo — and one property matters more than any of them: after an
 * undo the day must be back where it was, not merely "close". That is why the restore is verified
 * here against the persisted rows rather than only in the core's own unit tests.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `LedgerEditingTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerEditingTest {

    private lateinit var database: AppDatabase
    private lateinit var executor: CommandExecutor

    private val bridge = UniffiDomainBridge()

    @Before
    fun setUp() {
        database = AppDatabase.build(ApplicationProvider.getApplicationContext<Context>(), TEST_DB)
        executor = CommandExecutor(
            database = database,
            bridge = bridge,
            zoneIdProvider = { ZONE },
            wallClock = { BASE_MS },
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun day(): String =
        unwrap(bridge.appDateOf(BASE_MS, ZONE, 1)).iso

    private fun <T> unwrap(result: DomainResult<T>): T {
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
        return (result as DomainResult.Success<T>).value
    }

    private fun createTask(): String = runBlocking {
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "木刻练习", ""))
        executor.readState().tasks.single().taskId
    }

    private suspend fun rowsOf(taskId: String): List<app.arttodo.core.LedgerRow> =
        database.ledgerDao().forTaskDay(taskId, day()).map { it.toDomain() }

    private fun liveTotal(rows: List<app.arttodo.core.LedgerRow>): Long =
        unwrap(bridge.replayDailyTotal(taskId, day(), rows))

    private lateinit var taskId: String

    /** Three contributions and a set-total, so a delete has something meaningful to undo. */
    private suspend fun seedLedger() {
        taskId = createTask()
        executor.dispatch(DomainCommands.addManualSeconds(taskId, day(), 2_400))
        executor.dispatch(DomainCommands.addManualSeconds(taskId, day(), 1_800))
        executor.dispatch(DomainCommands.setDailyTotal(taskId, day(), 2_100))
        executor.dispatch(DomainCommands.addManualSeconds(taskId, day(), 600))
    }

    /**
     * The undo path the Snackbar triggers.
     *
     * The day must return to exactly the total it had before the delete. Re-appending an equal value
     * would not do that here: the set-total event sits before the deleted row in replay order, so a
     * re-added 2,100 would be added on top of the remaining contributions instead of pinning them.
     */
    @Test
    fun undoing_a_delete_restores_the_day_total_through_the_real_stack() = runBlocking {
        seedLedger()
        val before = liveTotal(rowsOf(taskId))
        assertThat(before).isEqualTo(2_700L)

        val setRow = rowsOf(taskId).first { it.kind == 2 }
        assertThat(executor.dispatch(DomainCommand.DeleteLedgerEntry(ledgerSeq = setRow.ledgerSeq)))
            .isInstanceOf(Executed.Applied::class.java)

        val afterDelete = liveTotal(rowsOf(taskId))
        assertThat(afterDelete).isEqualTo(4_800L)

        // A kind=2 delete must report the date and the resulting total for the UI notice (规格 6.1:128).
        val executed = executor.dispatch(DomainCommand.RestoreLedgerEntry(ledgerSeq = setRow.ledgerSeq))
        assertThat(executed).isInstanceOf(Executed.Applied::class.java)

        val afterUndo = liveTotal(rowsOf(taskId))
        assertThat(afterUndo).isEqualTo(before)
        assertThat(afterUndo).isNotEqualTo(afterDelete)

        // The row kept its slot: no new ledger_seq was appended for the restore.
        val restored = rowsOf(taskId).first { it.ledgerSeq == setRow.ledgerSeq }
        assertThat(restored.isDeleted).isFalse()
        assertThat(restored.setTotalSeconds).isEqualTo(2_100L)
        // And the undo left its own audit record.
        val audits = database.ledgerDao().audits(setRow.ledgerSeq)
        assertThat(audits.map { it.changeKind }).containsExactly(1, 2).inOrder()
    }

    /**
     * The notice the records screen shows after deleting a set-total row.
     *
     * `DomainOutcome.notices` is the only channel for "this affects the day's total", so the deleted
     * row's date and the resulting total both have to survive the trip through `CommandExecutor`.
     */
    @Test
    fun deleting_a_set_total_row_returns_the_affected_date_and_total() = runBlocking {
        seedLedger()
        val setRow = rowsOf(taskId).first { it.kind == 2 }

        val executed = executor.dispatch(DomainCommand.DeleteLedgerEntry(ledgerSeq = setRow.ledgerSeq))
        assertThat(executed).isInstanceOf(Executed.Applied::class.java)
        val notices = (executed as Executed.Applied).notices
        val notice = notices.single { it.code == "SetTotalDeleted" }
        assertThat(notice.affectedAppDate).isEqualTo(day())
        assertThat(notice.resultingTotalSeconds).isEqualTo(4_800L)
    }

    /**
     * Deleting an ordinary contribution changes only that day's total, and the preview the dialog shows
     * is the same number the core will produce.
     */
    @Test
    fun total_without_a_row_matches_what_actually_happens_after_deleting_it() = runBlocking {
        seedLedger()
        val rows = rowsOf(taskId)
        val manual = rows.first { it.kind == 1 }
        val predicted = unwrap(bridge.totalWithoutLedgerEntry(rows, manual.ledgerSeq))

        executor.dispatch(DomainCommand.DeleteLedgerEntry(ledgerSeq = manual.ledgerSeq))

        assertThat(liveTotal(rowsOf(taskId))).isEqualTo(predicted)
    }

    /** Editing a row changes its value in place and keeps its position in the replay order. */
    @Test
    fun editing_a_row_keeps_its_order_slot_and_changes_the_total() = runBlocking {
        seedLedger()
        val manual = rowsOf(taskId).first { it.kind == 1 }
        val seqsBefore = rowsOf(taskId).map { it.ledgerSeq }

        val executed = executor.dispatch(
            DomainCommand.EditLedgerEntry(
                ledgerSeq = manual.ledgerSeq,
                newDeltaSeconds = 600,
                newSetTotalSeconds = null,
            ),
        )
        assertThat(executed).isInstanceOf(Executed.Applied::class.java)
        assertThat(rowsOf(taskId).map { it.ledgerSeq }).isEqualTo(seqsBefore)
        // 600 + 1800 = 2400 ran before the set-total pinned the day at 2100, then +600.
        assertThat(liveTotal(rowsOf(taskId))).isEqualTo(2_700L)
    }

    /**
     * A restore is refused when the row is not deleted.
     *
     * This is the guard against a stale Snackbar: an undo tapped after the row was already restored (or
     * never deleted) must be a rejection, not a silent success.
     */
    @Test
    fun restoring_a_live_row_is_rejected_and_writes_nothing() = runBlocking {
        seedLedger()
        val live = rowsOf(taskId).first()
        val commandsBefore = database.supportDao().commandCount()

        val executed = executor.dispatch(DomainCommand.RestoreLedgerEntry(ledgerSeq = live.ledgerSeq))
        assertThat(executed).isInstanceOf(Executed.Rejected::class.java)
        assertThat(database.supportDao().commandCount()).isEqualTo(commandsBefore)
        assertThat(rowsOf(taskId).map { it.ledgerSeq }).hasSize(4)
    }

    private companion object {
        const val TEST_DB = "ledger-editing-test.db"
        const val ZONE = "Asia/Shanghai"
        const val BASE_MS = 1_772_000_000_000L
    }
}
