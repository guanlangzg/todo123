package app.arttodo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.TaskKind
import app.arttodo.data.AppDatabase
import app.arttodo.data.DomainCommands
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
 * The vertical slice, end to end on the JVM:
 *
 *   CommandExecutor -> real UniFFI/Rust core -> effects -> Room transaction -> reload from disk
 *
 * The "restart" is modelled by closing the database and reopening it, so this asserts persistence
 * rather than an in-memory cache. Nothing here is stubbed: the domain core is the actual native
 * library, and the totals that come back are computed by Rust.
 *
 * The native library used is the *host* build (`app/build/rust-host/release`), wired in through
 * `jna.library.path` by `app/build.gradle.kts`. That is what makes the slice testable without a
 * device; see the report for why the Android `.so` could not be produced on this machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerticalSliceTest {

    private lateinit var database: AppDatabase
    private lateinit var executor: CommandExecutor

    private fun newExecutor(db: AppDatabase): CommandExecutor = CommandExecutor(
        database = db,
        bridge = UniffiDomainBridge(),
        zoneIdProvider = { "Asia/Shanghai" },
        wallClock = { BASE_MS },
    )

    @Before
    fun setUp() {
        database = openDatabase()
        executor = newExecutor(database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun openDatabase(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.databaseBuilder(context, AppDatabase::class.java, "slice-test.db")
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun creating_a_task_travels_through_rust_and_survives_a_restart() = runBlocking {
        assertThat(executor.readState().tasks).isEmpty()

        val executed = executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "垂直切片任务", "备注"))
        assertThat(executed).isInstanceOf(Executed.Applied::class.java)

        val afterCreate = executor.readState()
        assertThat(afterCreate.tasks).hasSize(1)
        val created = afterCreate.tasks.single()
        // The id is minted by the domain core from the command id, not by Kotlin.
        assertThat(created.taskId).startsWith("task:")
        assertThat(created.title).isEqualTo("垂直切片任务")

        // "Restart": discard all in-memory state and read the file again.
        database.close()
        database = openDatabase()
        executor = newExecutor(database)

        val afterRestart = executor.readState()
        assertThat(afterRestart.tasks).hasSize(1)
        assertThat(afterRestart.tasks.single().title).isEqualTo("垂直切片任务")
        assertThat(afterRestart.tasks.single().taskId).isEqualTo(created.taskId)
    }

    /** The day total is replayed by Rust from the persisted rows, including a set-total. */
    @Test
    fun ledger_total_is_replayed_by_the_domain_core() = runBlocking {
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "账本任务", ""))
        val taskId = executor.readState().tasks.single().taskId
        val appDate = "2026-02-25"

        executor.dispatch(DomainCommands.addManualSeconds(taskId, appDate, 1200))
        executor.dispatch(DomainCommands.addManualSeconds(taskId, appDate, 1800))
        executor.dispatch(DomainCommands.setDailyTotal(taskId, appDate, 2100))
        executor.dispatch(DomainCommands.addManualSeconds(taskId, appDate, 600))

        val rows = database.ledgerDao().forTaskDay(taskId, appDate)
        assertThat(rows).hasSize(4)
        val replayed = UniffiDomainBridge()
            .replayDailyTotal(taskId, appDate, rows.map { it.toDomain() })
        assertThat(replayed).isInstanceOf(DomainResult.Success::class.java)
        assertThat((replayed as DomainResult.Success<Long>).value).isEqualTo(2700L)
    }

    /** A rejected command must write nothing at all. */
    @Test
    fun an_invalid_command_leaves_the_database_untouched() = runBlocking {
        val executed = executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "   ", ""))
        assertThat(executed).isInstanceOf(Executed.Rejected::class.java)
        assertThat(executor.readState().tasks).isEmpty()
        assertThat(database.supportDao().allCommands()).isEmpty()
    }

    /**
     * Proof that the native library is genuinely executing, not a Kotlin fallback.
     *
     * `2018-11-04` in `America/Sao_Paulo` has no local `00:00`, so that civil day is 23 hours long
     * (82800 s). The value comes from jiff inside Rust; nothing in Kotlin computes it. If the `.so`
     * were missing or stale, JNA would throw and the bridge would return a failure instead.
     */
    @Test
    fun the_dst_day_length_is_computed_by_rust() {
        val window = UniffiDomainBridge().dayWindow("2018-11-04", "America/Sao_Paulo", 1)
        assertThat(window).isInstanceOf(DomainResult.Success::class.java)
        val value = (window as DomainResult.Success).value
        assertThat(value.daySeconds).isEqualTo(82_800L)
        assertThat(value.endWallMs - value.startWallMs).isEqualTo(82_800_000L)
    }

    private companion object {
        const val BASE_MS = 1_772_000_000_000L
    }
}
