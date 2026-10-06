package app.arttodo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.arttodo.core.LedgerEffect
import app.arttodo.core.SessionMode
import app.arttodo.core.TaskKind
import app.arttodo.domain.CommandExecutor
import app.arttodo.domain.Executed
import app.arttodo.domain.bridge.DomainResult
import app.arttodo.domain.bridge.UniffiDomainBridge
import app.arttodo.data.WorkDomainMappers.toDomain
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The data layer's own promises, against a real database file (规格 §11 "Room/备份：文件库持久化与迁移，
 * 不只测内存库；时间记录和完成状态事务一致").
 *
 * Every test drives the shipped [AppDatabase.build] configuration — the same migrations, the same
 * `PRAGMA foreign_keys = ON`, the same WAL journal mode the app runs with — and commands go through
 * the real `CommandExecutor` and the real Rust core, so the effects under test are the ones
 * production writes.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `DatabaseWriteTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseWriteTest {

    private lateinit var database: AppDatabase
    private lateinit var executor: CommandExecutor
    private var now = BASE_MS

    @Before
    fun setUp() {
        database = openDatabase()
        executor = newExecutor(database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.build(ApplicationProvider.getApplicationContext<Context>(), TEST_DB)

    private fun newExecutor(db: AppDatabase): CommandExecutor = CommandExecutor(
        database = db,
        bridge = UniffiDomainBridge(),
        zoneIdProvider = { ZONE },
        wallClock = { now },
    )

    private fun applier(db: AppDatabase = database) = EffectsApplier(db) { DATE }

    /** Close and reopen the same file: the honest model of a killed and restarted process. */
    private fun restart() {
        database.close()
        database = openDatabase()
        executor = newExecutor(database)
    }

    // ---------------------------------------------------------------------------------------
    // 1. Writing a parent row must never cascade its history away (the INSERT OR REPLACE hazard)
    // ---------------------------------------------------------------------------------------

    /**
     * The core emits `UpsertTask` for every rename, note edit, reorder, archive and countdown
     * change. `INSERT OR REPLACE` implements that as delete-then-insert, and `task` cascades to
     * `daily_occurrence`, `focus_session`, `ledger_entry`, `task_title_revision` and
     * `temporary_completion_event` — so a single rename would have erased the task's whole history.
     */
    @Test
    fun a_task_re_upsert_keeps_its_occurrences_sessions_and_ledger() = runBlocking {
        val task = givenDailyTask()
        val occurrenceId = givenCompletedOccurrence(task.taskId)
        givenFinishedSession(task.taskId, seconds = 600)
        val ledgerBefore = database.ledgerDao().count()
        val segmentsBefore = database.sessionDao().segmentCount()
        assertThat(ledgerBefore).isGreaterThan(0)
        assertThat(segmentsBefore).isGreaterThan(0)

        // The exact effect a rename emits.
        val current = executor.readState().tasks.single()
        applier().apply(listOf(LedgerEffect.UpsertTask(current.copy(title = "改名后"))))

        assertThat(database.taskDao().find(task.taskId)?.title).isEqualTo("改名后")
        assertThat(database.taskDao().count()).isEqualTo(1)
        assertThat(database.occurrenceDao().eventsFor(occurrenceId)).hasSize(1)
        assertThat(database.occurrenceDao().view(occurrenceId)).isNotNull()
        assertThat(database.sessionDao().segmentCount()).isEqualTo(segmentsBefore)
        assertThat(database.ledgerDao().count()).isEqualTo(ledgerBefore)
        assertThat(database.ledgerDao().liveCount()).isEqualTo(ledgerBefore)
    }

    /**
     * `SetOccurrenceCompletion` and `RebuildOccurrenceView` re-upsert the occurrence. Replacing that
     * row would delete the append-only completion log — the *authoritative* record of whether a day
     * was done (架构契约 §4.1).
     */
    @Test
    fun an_occurrence_re_upsert_keeps_its_completion_events_and_view() = runBlocking {
        val task = givenDailyTask()
        val occurrenceId = givenCompletedOccurrence(task.taskId)
        val highWaterBefore = database.occurrenceDao().maxEventSeq()

        val occurrence = executor.readState().occurrences.single()
        applier().apply(
            listOf(LedgerEffect.UpsertOccurrence(occurrence.copy(displayTitleSnapshot = "改名后"))),
        )

        assertThat(database.occurrenceDao().byId(occurrenceId)?.displayTitleSnapshot).isEqualTo("改名后")
        assertThat(database.occurrenceDao().eventsFor(occurrenceId)).hasSize(1)
        assertThat(database.occurrenceDao().maxEventSeq()).isEqualTo(highWaterBefore)
        assertThat(database.occurrenceDao().view(occurrenceId)?.isCompleted).isEqualTo(1)
    }

    /**
     * Pause, resume, rename-while-running and finish all emit `UpdateSession`. Replacing the session
     * row would delete the open segment (the only record of the time being tracked right now), the
     * session's other segments, its heartbeat anchor and the active-session pointer.
     */
    @Test
    fun a_session_re_upsert_keeps_its_segments_heartbeat_and_active_slot() = runBlocking {
        val task = givenDailyTask()
        val sessionId = givenRunningSessionWithTwoSegments(task.taskId)

        val session = executor.readState().sessions.single()
        applier().apply(
            listOf(LedgerEffect.UpdateSession(session = session, closedSegment = null, openedSegment = null)),
        )

        assertThat(database.sessionDao().session(sessionId)).isNotNull()
        assertThat(database.sessionDao().segments(sessionId)).hasSize(2)
        assertThat(database.sessionDao().allHeartbeats().map { it.sessionId }).contains(sessionId)
        assertThat(database.sessionDao().activeSlot()?.sessionId).isEqualTo(sessionId)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Replaying a command must not double-finish, double-open or double-book
    // ---------------------------------------------------------------------------------------

    /** Replaying a whole user action with the same command id changes nothing at all. */
    @Test
    fun replaying_start_and_finish_with_the_same_command_id_changes_nothing() = runBlocking {
        val task = givenDailyTask()

        val start = DomainCommands.startSession(task.taskId, SessionMode.COUNT_UP, null, null)
        assertThat(executor.dispatch(start, "cmd-start")).isInstanceOf(Executed.Applied::class.java)
        val sessionId = executor.readState().activeSessionId!!
        val afterStart = fileFacts()

        // One session, one open segment, one active slot, and nothing booked yet.
        assertThat(afterStart.sessions).isEqualTo(1)
        assertThat(afterStart.segments).isEqualTo(1)
        assertThat(afterStart.activeSlots).isEqualTo(1)
        assertThat(afterStart.ledger).isEqualTo(0)

        // Same command id again: the core refuses it and the applier is never reached.
        assertThat(executor.dispatch(start, "cmd-start")).isInstanceOf(Executed.Rejected::class.java)
        assertThat(fileFacts()).isEqualTo(afterStart)

        now += 600_000
        val finish = DomainCommands.finishAfter(sessionId, 600)
        assertThat(executor.dispatch(finish, "cmd-finish")).isInstanceOf(Executed.Applied::class.java)
        val afterFinish = fileFacts()
        assertThat(afterFinish.ledger).isEqualTo(1)
        assertThat(afterFinish.activeSlots).isEqualTo(0)
        assertThat(database.sessionDao().session(sessionId)?.state).isEqualTo(2)

        // Replaying the finish must not append a second slice, reopen the session or re-arm the slot.
        assertThat(executor.dispatch(finish, "cmd-finish")).isInstanceOf(Executed.Rejected::class.java)
        assertThat(fileFacts()).isEqualTo(afterFinish)
        assertThat(database.sessionDao().session(sessionId)?.state).isEqualTo(2)
    }

    /**
     * A resumed session keeps both segments: replaying `ResumeSession` cannot delete the closed one,
     * and resume/finish must never book the same span twice.
     */
    @Test
    fun pausing_and_resuming_preserves_every_segment_and_books_each_span_once() = runBlocking {
        val task = givenDailyTask()
        val sessionId = givenRunningSessionWithTwoSegments(task.taskId);

        val factsAfterResume = fileFacts()
        assertThat(database.sessionDao().segments(sessionId)).hasSize(2)
        // The span closed by the pause was booked exactly once.
        assertThat(factsAfterResume.ledger).isEqualTo(1)

        // Replaying the resume command is refused and changes nothing.
        val resume = DomainCommands.resumeSession(sessionId)
        assertThat(executor.dispatch(resume, "cmd-resume:$sessionId"))
            .isInstanceOf(Executed.Rejected::class.java)
        assertThat(fileFacts()).isEqualTo(factsAfterResume)

        now += 300_000
        executor.dispatch(DomainCommands.finishAfter(sessionId, 300), "cmd-finish:$sessionId")
        // Two closed spans (pause + finish), so exactly two booked rows and two live ones.
        assertThat(database.ledgerDao().count()).isEqualTo(2)
        assertThat(database.ledgerDao().liveCount()).isEqualTo(2)
        assertThat(database.ledgerDao().all().map { it.refId }).doesNotContain(null)
    }

    /**
     * The same span replayed through the database after a *process restart* is rejected by the
     * deterministic slice key, not by the command id (架构契约 §6 rule 4).
     */
    @Test
    fun reapplying_the_same_slice_effect_books_it_once() = runBlocking {
        val task = givenDailyTask()
        givenFinishedSession(task.taskId, seconds = 600)
        val rows = database.ledgerDao().all()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().refId).isNotNull()

        // Exactly what a replayed recovery would emit, one transaction later.
        applier().apply(listOf(LedgerEffect.AppendLedgerEntry(rows.single().toDomain())))

        assertThat(database.ledgerDao().count()).isEqualTo(1)
        assertThat(database.ledgerDao().liveCount()).isEqualTo(1)
    }

    /** Completing and reopening a temporary task appends history; a replay appends nothing. */
    @Test
    fun temporary_completion_history_is_append_only_and_replay_safe() = runBlocking {
        val task = givenTemporaryTask()

        assertThat(executor.dispatch(DomainCommands.completeTemporary(task.taskId), "cmd-done"))
            .isInstanceOf(Executed.Applied::class.java)
        now += 60_000
        executor.dispatch(DomainCommands.reopenTemporary(task.taskId), "cmd-reopen")
        now += 60_000
        executor.dispatch(DomainCommands.completeTemporary(task.taskId), "cmd-done-2")

        val events = database.occurrenceDao().temporaryEventsFor(task.taskId)
        assertThat(events).hasSize(3)
        assertThat(events.map { it.action }).containsExactly(0, 1, 0).inOrder()

        // Same command id replayed: refused, and the history stays three events long.
        assertThat(executor.dispatch(DomainCommands.completeTemporary(task.taskId), "cmd-done-2"))
            .isInstanceOf(Executed.Rejected::class.java)
        assertThat(database.occurrenceDao().temporaryEventsFor(task.taskId)).hasSize(3)

        // The core still derives "done" from that history, i.e. reopening did not erase completion.
        val derived = UniffiDomainBridge().temporaryCompleted(events.map { it.toDomain() }, task.taskId)
        assertThat((derived as DomainResult.Success).value).isTrue()
    }

    /**
     * Two separate actions at the same millisecond are two events.
     *
     * This is the guard against "deduplicate the log by action + instant": on a device whose clock
     * resolution cannot separate two taps, that rule would silently drop the second event and the
     * history would lose a fact. The sequence column is assigned by SQLite, so the only correct
     * behaviour is to append what the core emitted.
     */
    @Test
    fun two_events_at_the_same_instant_are_both_recorded(): Unit = runBlocking {
        val task = givenTemporaryTask()

        // `now` is deliberately NOT advanced between the two actions.
        assertThat(executor.dispatch(DomainCommands.completeTemporary(task.taskId), "cmd-a"))
            .isInstanceOf(Executed.Applied::class.java)
        assertThat(executor.dispatch(DomainCommands.reopenTemporary(task.taskId), "cmd-b"))
            .isInstanceOf(Executed.Applied::class.java)
        assertThat(executor.dispatch(DomainCommands.completeTemporary(task.taskId), "cmd-c"))
            .isInstanceOf(Executed.Applied::class.java)

        val events = database.occurrenceDao().temporaryEventsFor(task.taskId)
        assertThat(events).hasSize(3)
        assertThat(events.map { it.action }).containsExactly(0, 1, 0).inOrder()

        // Completion events on the daily side behave the same way.
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, "同日两勾", ""), "cmd-daily-second")
        val daily = database.taskDao().all().first { it.title == "同日两勾" }
        executor.dispatch(DomainCommands.ensureOccurrence(daily.taskId, DATE), "cmd-occ")
        executor.dispatch(DomainCommands.setCompletion(daily.taskId, DATE, true), "cmd-on")
        executor.dispatch(DomainCommands.setCompletion(daily.taskId, DATE, false), "cmd-off")
        executor.dispatch(DomainCommands.setCompletion(daily.taskId, DATE, true), "cmd-on-2")

        val occurrenceId = database.occurrenceDao().find(daily.taskId, DATE)!!.occurrenceId
        val completions = database.occurrenceDao().eventsFor(occurrenceId)
        assertThat(completions).hasSize(3)
        assertThat(completions.map { it.action }).containsExactly(0, 1, 0).inOrder()
        assertThat(database.taskDao().all().map { it.taskId })
            .containsExactlyElementsIn(listOf(task.taskId, daily.taskId))
    }

    /** Killing the process mid-session loses nothing that was already committed. */
    @Test
    fun a_running_session_survives_a_process_restart() = runBlocking {
        val task = givenDailyTask()
        val sessionId = givenRunningSessionWithTwoSegments(task.taskId)
        val heartbeatBefore = database.sessionDao().latestHeartbeat(sessionId)
        val segmentsBefore = database.sessionDao().segments(sessionId).map { it.segmentId }
        val ledgerBefore = database.ledgerDao().count()

        restart()

        assertThat(database.sessionDao().session(sessionId)?.state).isEqualTo(0)
        assertThat(database.sessionDao().activeSlot()?.sessionId).isEqualTo(sessionId)
        assertThat(database.sessionDao().segments(sessionId).map { it.segmentId })
            .containsExactlyElementsIn(segmentsBefore).inOrder()
        assertThat(database.sessionDao().latestHeartbeat(sessionId)).isEqualTo(heartbeatBefore)
        assertThat(database.ledgerDao().count()).isEqualTo(ledgerBefore)
        // The heartbeats and the active session are reachable through the executor's own state load.
        assertThat(executor.readState().heartbeats.map { it.sessionId }).contains(sessionId)
        assertThat(executor.readState().activeSessionId).isEqualTo(sessionId)
    }

    // ---------------------------------------------------------------------------------------
    // 3. Transactions are all-or-nothing, and the constraints are real
    // ---------------------------------------------------------------------------------------

    /** A rejected value rolls back the effects that preceded it in the same list. */
    @Test
    fun a_failing_effect_rolls_back_the_whole_command() = runBlocking {
        val task = givenDailyTask()
        val commandsBefore = database.supportDao().commandCount()
        val tasksBefore = database.taskDao().count()
        val base = executor.readState().tasks.single()

        val thrown = runCatching {
            applier().apply(
                listOf(
                    LedgerEffect.UpsertTask(base.copy(taskId = "task:second", title = "合法")),
                    LedgerEffect.UpsertTask(base.copy(taskId = "task:third", title = "   ")),
                ),
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(DataViolationException::class.java)
        assertThat((thrown as DataViolationException).code).isEqualTo("EmptyTitle")
        assertThat(database.taskDao().count()).isEqualTo(tasksBefore)
        assertThat(database.taskDao().find("task:second")).isNull()
        assertThat(database.taskDao().find("task:third")).isNull()
        assertThat(database.supportDao().commandCount()).isEqualTo(commandsBefore)
        assertThat(database.taskDao().find(task.taskId)).isNotNull()
    }

    /** A foreign-key violation is a database error, and it too rolls the transaction back. */
    @Test
    fun a_foreign_key_violation_rolls_back_the_whole_command() = runBlocking {
        val task = givenDailyTask()
        val tasksBefore = database.taskDao().count()

        val orphan = LedgerEntryEntity(
            kind = 1,
            refId = null,
            taskId = "task:does-not-exist",
            appDate = DATE,
            occurredWallMs = BASE_MS,
            zoneEpochSeq = 1,
            deltaSeconds = 60,
            setTotalSeconds = null,
            createdWallMs = BASE_MS,
            editedAtMs = null,
            isDeleted = 0,
            deletedAtMs = null,
        )
        val thrown = runCatching {
            applier().apply(
                listOf(
                    LedgerEffect.UpsertTask(executor.readState().tasks.single().copy(title = "改")),
                    LedgerEffect.AppendLedgerEntry(orphan.toDomain()),
                ),
            )
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(database.taskDao().count()).isEqualTo(tasksBefore)
        assertThat(database.taskDao().find(task.taskId)?.title).isEqualTo("历史保全")
        assertThat(database.ledgerDao().count()).isEqualTo(0)
    }

    /** Foreign keys are enabled on the connection the app actually opens. */
    @Test
    fun foreign_keys_are_enabled_on_the_file_database() {
        val enabled = database.openHelper.writableDatabase
            .query("PRAGMA foreign_keys")
            .use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
        assertThat(enabled).isTrue()
    }

    /**
     * The promise is a *file* library, not an in-memory one (规格 §11: "文件库持久化与迁移，不只测内存库").
     *
     * `restart()` alone would also pass against an in-memory database kept alive by Room's helper, so
     * this test verifies the two things memory cannot fake: the store is a real file with a non-zero
     * on-disk size, and a *separate* SQLite connection opened on that path sees the committed rows.
     */
    @Test
    fun the_store_is_a_real_file_that_other_connections_can_read(): Unit = runBlocking {
        val task = givenDailyTask()
        val occurrenceId = givenCompletedOccurrence(task.taskId)

        val live = requireNotNull(database.openHelper.writableDatabase.path)
        val file = File(live)
        assertThat(file.isFile).isTrue()
        assertThat(file.length()).isGreaterThan(0L)

        // A brand-new connection on the same path — not the writing handle, not its cache.
        SQLiteDatabase.openDatabase(live, null, SQLiteDatabase.OPEN_READONLY).use { reader ->
            reader.query("task", arrayOf("task_id", "title"), "task_id = ?", arrayOf(task.taskId), null, null, null)
                .use { cursor ->
                    assertThat(cursor.count).isEqualTo(1)
                    cursor.moveToFirst()
                    assertThat(cursor.getString(1)).isEqualTo("历史保全")
                }
            reader.query(
                "occurrence_completion_event",
                arrayOf("action"),
                "occurrence_id = ?",
                arrayOf(occurrenceId),
                null,
                null,
                "event_seq ASC",
            ).use { cursor ->
                assertThat(cursor.count).isEqualTo(1)
                cursor.moveToFirst()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
            // The foreign-key pragma is per connection, which is exactly why the app sets it on open.
            reader.rawQuery("PRAGMA foreign_keys", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    /**
     * Values are bound, never interpolated: a title that *is* SQL syntax round-trips as data.
     *
     * Every DAO query binds through Room's `?` placeholders, so a crafted title cannot end a string
     * literal or drop a table. This drives such a title through the real core and every read path the
     * data layer exposes, and confirms the schema is untouched afterwards.
     */
    @Test
    fun hostile_text_is_stored_as_data_and_never_executed(): Unit = runBlocking {
        val hostile = "'; DROP TABLE task; -- 中文🎨"
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, hostile, hostile), "cmd-hostile")

        val stored = database.taskDao().all().single()
        assertThat(stored.title).isEqualTo(hostile)
        assertThat(stored.note).isEqualTo(hostile)
        // The same value round-trips through every parameterised read path.
        assertThat(database.taskDao().find(stored.taskId)?.title).isEqualTo(hostile)

        executor.dispatch(DomainCommands.ensureOccurrence(stored.taskId, DATE), "cmd-hostile-occ")
        val occurrenceId = database.occurrenceDao().find(stored.taskId, DATE)!!.occurrenceId
        assertThat(database.occurrenceDao().byId(occurrenceId)?.taskId).isEqualTo(stored.taskId)
        assertThat(database.ledgerDao().forTaskDay(stored.taskId, DATE)).isEmpty()

        // Nothing was executed: the table still exists and still holds exactly the one task.
        assertThat(database.taskDao().count()).isEqualTo(1)
        assertThat(database.occurrenceDao().count()).isEqualTo(1)
    }

    /** The single-row slot makes a second active session physically impossible. */
    @Test
    fun the_active_session_slot_cannot_hold_two_sessions() = runBlocking {
        val task = givenDailyTask()
        val sessionId = givenRunningSessionWithTwoSegments(task.taskId)

        val other = database.sessionDao().session(sessionId)!!.copy(sessionId = "session:other")
        database.sessionDao().insertSession(other)
        database.sessionDao().setActiveSlot(ActiveSessionSlotEntity(sessionId = "session:other"))

        val slots = database.openHelper.readableDatabase
            .query("SELECT * FROM active_session_slot")
            .use { cursor -> cursor.count }
        assertThat(slots).isEqualTo(1)
        assertThat(database.sessionDao().activeSlot()?.sessionId).isEqualTo("session:other")
    }

    /** `(task_id, app_date)` is unique: one daily instance per task per day, whatever tries. */
    @Test
    fun a_second_occurrence_for_the_same_task_and_day_is_rejected() = runBlocking {
        val task = givenDailyTask()
        val occurrenceId = givenCompletedOccurrence(task.taskId)

        val thrown = runCatching {
            database.occurrenceDao().insert(
                DailyOccurrenceEntity(
                    occurrenceId = "$occurrenceId:dup",
                    taskId = task.taskId,
                    appDate = DATE,
                    zoneEpochSeq = 1,
                    displayTitleSnapshot = "重复",
                    createdAtMs = BASE_MS,
                ),
            )
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(database.occurrenceDao().count()).isEqualTo(1)
    }

    /** One title revision per (task, instant): a second row for the same instant is rejected. */
    @Test
    fun a_second_title_revision_for_the_same_instant_is_rejected() = runBlocking {
        val task = givenDailyTask()

        val thrown = runCatching {
            applier().apply(
                listOf(
                    LedgerEffect.RenameTitleRevision(
                        taskId = task.taskId,
                        revision = app.arttodo.core.TitleRevision(effectiveWallMs = now, title = "第一次"),
                        zoneEpochSeq = 1u,
                    ),
                    LedgerEffect.RenameTitleRevision(
                        taskId = task.taskId,
                        revision = app.arttodo.core.TitleRevision(effectiveWallMs = now, title = "第二次"),
                        zoneEpochSeq = 1u,
                    ),
                ),
            )
        }.exceptionOrNull()

        // REPLACE, not ABORT: the same instant is the same revision, so the newest title wins and the
        // command is not lost.
        assertThat(thrown).isNull()
        val revisions = database.titleRevisionDao().forTask(task.taskId)
        assertThat(revisions).hasSize(1)
        assertThat(revisions.single().title).isEqualTo("第二次")
    }

    /** Deleting a task cascades exactly once, leaving no orphan rows behind. */
    @Test
    fun deleting_a_task_cascades_to_its_history() = runBlocking {
        val task = givenDailyTask()
        val occurrenceId = givenCompletedOccurrence(task.taskId)
        givenFinishedSession(task.taskId, seconds = 600)

        // The app itself archives rather than deletes (AC-10); this asserts the declared schema
        // behaviour that archive-vs-delete reasoning relies on.
        database.openHelper.writableDatabase
            .execSQL("DELETE FROM task WHERE task_id = ?", arrayOf<Any?>(task.taskId))

        assertThat(database.taskDao().count()).isEqualTo(0)
        assertThat(database.occurrenceDao().count()).isEqualTo(0)
        assertThat(database.occurrenceDao().eventCount()).isEqualTo(0)
        assertThat(database.occurrenceDao().view(occurrenceId)).isNull()
        assertThat(database.sessionDao().count()).isEqualTo(0)
        assertThat(database.sessionDao().segmentCount()).isEqualTo(0)
        assertThat(database.ledgerDao().count()).isEqualTo(0)
    }

    // ---------------------------------------------------------------------------------------
    // 4. Length / range limits at the write boundary
    // ---------------------------------------------------------------------------------------

    @Test
    fun overlong_and_invalid_fields_are_refused_and_persist_nothing() = runBlocking {
        val task = givenDailyTask()
        val base = executor.readState().tasks.single()
        val tasksBefore = database.taskDao().count()

        val cases = listOf(
            base.copy(taskId = "t:long-title", title = "标".repeat(FieldLimits.MAX_TITLE_CHARS + 1)) to "TitleTooLong",
            base.copy(taskId = "t:blank", title = "   ") to "EmptyTitle",
            base.copy(taskId = "t:long-note", note = "备".repeat(FieldLimits.MAX_NOTE_CHARS + 1)) to "NoteTooLong",
            base.copy(taskId = "t:minutes-low", lastCountdownMinutes = 0u) to "CountdownMinutesOutOfRange",
            base.copy(taskId = "t:minutes-high", lastCountdownMinutes = 1441u) to "CountdownMinutesOutOfRange",
        )
        for ((candidate, expectedCode) in cases) {
            val thrown = runCatching {
                applier().apply(listOf(LedgerEffect.UpsertTask(candidate)))
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(DataViolationException::class.java)
            assertThat((thrown as DataViolationException).code).isEqualTo(expectedCode)
            assertThat(database.taskDao().count()).isEqualTo(tasksBefore)
        }

        // The boundaries themselves are accepted.
        applier().apply(
            listOf(
                LedgerEffect.UpsertTask(
                    base.copy(taskId = "t:edge-title", title = "标".repeat(FieldLimits.MAX_TITLE_CHARS)),
                ),
                LedgerEffect.UpsertTask(
                    base.copy(taskId = "t:edge-note", note = "备".repeat(FieldLimits.MAX_NOTE_CHARS)),
                ),
                LedgerEffect.UpsertTask(base.copy(taskId = "t:edge-min", lastCountdownMinutes = 1u)),
                LedgerEffect.UpsertTask(base.copy(taskId = "t:edge-max", lastCountdownMinutes = 1440u)),
            ),
        )
        assertThat(database.taskDao().count()).isEqualTo(tasksBefore + 4)
        assertThat(database.taskDao().find(task.taskId)).isNotNull()
    }

    /** The constants here must agree with the core's behaviour, or the two can drift apart. */
    @Test
    fun the_field_limits_match_the_domain_core() = runBlocking {
        val tooLong = executor.dispatch(
            DomainCommands.createTask(TaskKind.TEMPORARY, "标".repeat(FieldLimits.MAX_TITLE_CHARS + 1), ""),
            "cmd-limit-1",
        )
        val exact = executor.dispatch(
            DomainCommands.createTask(TaskKind.TEMPORARY, "标".repeat(FieldLimits.MAX_TITLE_CHARS), ""),
            "cmd-limit-2",
        )
        assertThat(tooLong).isInstanceOf(Executed.Rejected::class.java)
        assertThat(exact).isInstanceOf(Executed.Applied::class.java)
        assertThat(database.taskDao().count()).isEqualTo(1)
    }

    /**
     * The core counts `chars()` (Unicode code points) in Rust, so the Kotlin side must too.
     *
     * An emoji outside the basic plane is one Rust `char` but two UTF-16 units, so counting UTF-16
     * units here would reject a title the core accepts — a visible "the app refused a 60-emoji title
     * as too long" defect across the FFI seam.
     */
    @Test
    fun the_limits_count_code_points_like_the_core_not_utf16_units(): Unit = runBlocking {
        // One astral emoji, repeated exactly to the limit the core allows.
        val emoji = "\uD83C\uDFA8" // 🎨
        assertThat(emoji.length).isEqualTo(2)
        val atLimit = emoji.repeat(FieldLimits.MAX_TITLE_CHARS)
        assertThat(atLimit.codePointCount(0, atLimit.length)).isEqualTo(FieldLimits.MAX_TITLE_CHARS)

        assertThat(FieldLimits.titleViolation(atLimit)).isNull()
        val accepted = executor.dispatch(
            DomainCommands.createTask(TaskKind.TEMPORARY, atLimit, ""),
            "cmd-emoji-limit",
        )
        assertThat(accepted).isInstanceOf(Executed.Applied::class.java)
        assertThat(database.taskDao().all().single().title).isEqualTo(atLimit)

        val overLimit = atLimit + emoji
        assertThat(FieldLimits.titleViolation(overLimit)).isEqualTo("TitleTooLong")
        val refused = executor.dispatch(
            DomainCommands.createTask(TaskKind.TEMPORARY, overLimit, ""),
            "cmd-emoji-over",
        )
        assertThat(refused).isInstanceOf(Executed.Rejected::class.java)
        assertThat(database.taskDao().count()).isEqualTo(1)

        // The note limit is counted the same way.
        assertThat(FieldLimits.noteViolation(emoji.repeat(FieldLimits.MAX_NOTE_CHARS))).isNull()
        assertThat(FieldLimits.noteViolation(emoji.repeat(FieldLimits.MAX_NOTE_CHARS + 1)))
            .isEqualTo("NoteTooLong")
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** A stable snapshot of the facts a replayed command must not change. */
    private data class FileFacts(
        val tasks: Int,
        val occurrences: Int,
        val completionEvents: Int,
        val temporaryEvents: Int,
        val sessions: Int,
        val segments: Int,
        val heartbeats: Int,
        val activeSlots: Int,
        val ledger: Int,
        val liveLedger: Int,
        val audits: Int,
        val commands: Int,
        val titleRevisions: Int,
        val zoneEpochs: Int,
        val settings: Int,
    )

    private suspend fun fileFacts(): FileFacts = FileFacts(
        tasks = database.taskDao().count(),
        occurrences = database.occurrenceDao().count(),
        completionEvents = database.occurrenceDao().eventCount(),
        temporaryEvents = database.occurrenceDao().temporaryEventCount(),
        sessions = database.sessionDao().count(),
        segments = database.sessionDao().segmentCount(),
        heartbeats = database.sessionDao().allHeartbeats().size,
        activeSlots = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM active_session_slot")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
        ledger = database.ledgerDao().count(),
        liveLedger = database.ledgerDao().liveCount(),
        audits = database.ledgerDao().auditCount(),
        commands = database.supportDao().commandCount(),
        titleRevisions = database.titleRevisionDao().count(),
        zoneEpochs = database.supportDao().zoneEpochCount(),
        settings = database.supportDao().settingCount(),
    )

    private suspend fun givenDailyTask(): TaskEntity {
        executor.dispatch(DomainCommands.createTask(TaskKind.DAILY, "历史保全", ""), "cmd-task")
        return database.taskDao().all().single()
    }

    private suspend fun givenTemporaryTask(): TaskEntity {
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "临时任务", ""), "cmd-temp")
        return database.taskDao().all().single()
    }

    private suspend fun givenCompletedOccurrence(taskId: String): String {
        executor.dispatch(DomainCommands.ensureOccurrence(taskId, DATE), "cmd-occurrence:$taskId")
        executor.dispatch(DomainCommands.setCompletion(taskId, DATE, true), "cmd-complete:$taskId")
        return database.occurrenceDao().find(taskId, DATE)!!.occurrenceId
    }

    /** Finishes a session so the ledger holds one deterministic slice. */
    private suspend fun givenFinishedSession(taskId: String, seconds: Long): String {
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null),
            "cmd-start:$taskId",
        )
        val sessionId = executor.readState().activeSessionId!!
        now += seconds * 1000
        executor.dispatch(DomainCommands.finishAfter(sessionId, seconds), "cmd-finish:$sessionId")
        return sessionId
    }

    /** A running session with one closed (paused) and one open segment, plus heartbeats and the slot. */
    private suspend fun givenRunningSessionWithTwoSegments(taskId: String): String {
        executor.dispatch(
            DomainCommands.startSession(taskId, SessionMode.COUNT_UP, null, null),
            "cmd-start:$taskId",
        )
        val sessionId = executor.readState().activeSessionId!!
        now += 60_000
        executor.dispatch(DomainCommands.pauseSession(sessionId), "cmd-pause:$sessionId")
        now += 60_000
        executor.dispatch(DomainCommands.resumeSession(sessionId), "cmd-resume:$sessionId")
        assertThat(database.sessionDao().segments(sessionId)).hasSize(2)
        return sessionId
    }

    private companion object {
        const val TEST_DB = "write-test.db"
        const val ZONE = "Asia/Shanghai"
        const val DATE = "2026-02-25"
        const val BASE_MS = 1_772_000_000_000L
    }
}
