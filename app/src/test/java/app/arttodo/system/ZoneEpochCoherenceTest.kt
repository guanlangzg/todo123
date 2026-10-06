package app.arttodo.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.DomainCommand
import app.arttodo.core.TaskKind
import app.arttodo.data.AppDatabase
import app.arttodo.data.DomainCommands
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
import java.util.TimeZone

/**
 * ① A manual application-zone change must leave exactly one truth behind it.
 *
 * 架构契约 §2.4 splits the two sources: a *system* zone change writes no epoch and changes nothing,
 * while a manual change appends one `zone_epoch` row. That row is the authority for every later
 * fact, so three things have to agree immediately after the change:
 *
 * 1. the epoch sequence the core is fed (`zone_epoch.epoch_seq`, not a count of rows),
 * 2. the zone every later command is dated in (the newest epoch row, not a Kotlin cache),
 * 3. the Kotlin pointer (`app_setting["app_zone_id"]`) — the bootstrap value, which must not keep
 *    describing the zone the user just left.
 *
 * The same assertions also pin the two things a zone change must never do: rewrite an existing
 * ledger row's `app_date`/`zone_epoch_seq`, or overwrite the previous epoch row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoneEpochCoherenceTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var bridge: UniffiDomainBridge
    private lateinit var zoneProvider: ZoneProvider
    private lateinit var executor: CommandExecutor

    /**
     * 2026-03-09T20:00Z — Shanghai reads 2026-03-10 04:00, New York still reads 2026-03-09 15:00,
     * so the two candidate zones disagree about which day this instant belongs to.
     */
    private var now = 1_773_086_400_000L
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        database = AppDatabase.build(context, "zone-epoch-coherence.db")
        database.openHelper.writableDatabase
        bridge = UniffiDomainBridge()
        zoneProvider = ZoneProvider(database)
        zoneProvider.ensureInitialised()
        executor = CommandExecutor(
            database = database,
            bridge = bridge,
            zoneIdProvider = zoneProvider::appZoneId,
            wallClock = { now },
            // The production wiring: a zone the core records is handed back to the bootstrap pointer.
            onZoneObserved = zoneProvider::observeZone,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("zone-epoch-coherence.db")
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun after_a_manual_change_the_core_epoch_the_pointer_and_new_facts_agree(): Unit = runBlocking {
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "任务", ""))
        val taskId = executor.readState().tasks.single().taskId
        executor.dispatch(DomainCommands.addManualSeconds(taskId, "2026-03-10", 600))

        val switched = executor.dispatch(
            DomainCommand.AppendZoneEpoch(zoneId = "America/New_York", impactSummary = "手动变更"),
        )
        assertThat(switched).isInstanceOf(Executed.Applied::class.java)

        // 1+2. The newest epoch row is the authority for both the zone and its sequence.
        val state = executor.readState()
        assertThat(state.zoneId).isEqualTo("America/New_York")
        assertThat(state.zoneEpochSeq).isEqualTo(2u)
        assertThat(database.supportDao().zoneEpochs().map { it.epochSeq }).containsExactly(2)

        // 3. The pointer follows the authority instead of keeping the zone the user left, and a new
        //    process (which reads the persisted pointer) sees the same zone.
        assertThat(zoneProvider.appZoneId()).isEqualTo("America/New_York")
        assertThat(database.supportDao().setting(ZoneProvider.KEY_APP_ZONE)).isEqualTo("America/New_York")
        val afterRestart = ZoneProvider(database)
        afterRestart.ensureInitialised()
        assertThat(afterRestart.appZoneId()).isEqualTo("America/New_York")

        // The next fact is dated by the new epoch's zone: 03-09 in New York, not 03-10 in Shanghai.
        val renamed = executor.dispatch(DomainCommand.RenameTask(taskId = taskId, title = "改名后"))
        assertThat(renamed).isInstanceOf(Executed.Applied::class.java)
        val revision = database.titleRevisionDao().forTask(taskId).maxByOrNull { it.effectiveWallMs }
        assertThat(revision).isNotNull()
        assertThat(revision!!.zoneEpochSeq).isEqualTo(2)
        assertThat(revision.effectiveAppDate).isEqualTo("2026-03-09")

        // History is not recomputed: the row keeps the day and the epoch it was recorded under.
        val history = database.ledgerDao().forTaskDay(taskId, "2026-03-10").single()
        assertThat(history.deltaSeconds).isEqualTo(600)
        assertThat(history.zoneEpochSeq).isEqualTo(1)
    }

    @Test
    fun a_second_manual_change_appends_an_epoch_instead_of_overwriting_the_first(): Unit = runBlocking {
        executor.dispatch(DomainCommand.AppendZoneEpoch("America/New_York", "第一次"))
        executor.dispatch(DomainCommand.AppendZoneEpoch("Europe/Berlin", "第二次"))

        val epochs = database.supportDao().zoneEpochs()
        assertThat(epochs.map { it.zoneId }).containsExactly("America/New_York", "Europe/Berlin").inOrder()
        assertThat(epochs.map { it.epochSeq }).containsExactly(2, 3).inOrder()
        assertThat(executor.readState().zoneEpochSeq).isEqualTo(3u)
    }

    @Test
    fun a_fresh_install_dates_facts_with_the_installation_zone_and_sequence_one(): Unit = runBlocking {
        val date = bridge.appDateOf(now, zoneProvider.appZoneId(), 1)
        assertThat(date).isInstanceOf(DomainResult.Success::class.java)
        assertThat((date as DomainResult.Success).value.iso).isEqualTo("2026-03-10")
    }
}
