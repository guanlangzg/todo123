package app.arttodo.data

import app.arttodo.core.DomainState
import app.arttodo.core.EventHighWater
import app.arttodo.core.HeartbeatRecord
import app.arttodo.core.LedgerRow
import app.arttodo.core.TaskRecord
import app.arttodo.core.TemporaryCompletionEvent
import app.arttodo.core.WorkSegment
import app.arttodo.data.WorkDomainMappers.toDomain
import app.arttodo.data.WorkDomainMappers.toDomainAudit

/**
 * Reads the persisted facts back into a [DomainState] the domain core can reason over.
 *
 * Every field is a transcript of a stored row. Kotlin adds no interpretation here: totals, dates
 * and completion state all come from the core (架构契约 §4.1, §9).
 *
 * The tables are small (a personal task list), so the loader reads them whole in one pass rather
 * than maintaining per-screen queries. That also removes any chance of a screen loading a
 * *different* subset of the truth than a command sees.
 */
class DomainStateLoader(private val database: AppDatabase) {

    private val taskDao = database.taskDao()
    private val occurrenceDao = database.occurrenceDao()
    private val sessionDao = database.sessionDao()
    private val ledgerDao = database.ledgerDao()
    private val supportDao = database.supportDao()

    suspend fun load(zoneId: String): DomainState {
        val tasks = taskDao.all()
        val epochs = supportDao.zoneEpochs()

        val occurrences = occurrenceDao.all().map { row ->
            val derived = WorkDomainMappers.DerivedCompletion.from(occurrenceDao.eventsFor(row.occurrenceId))
            row.toDomain(derived)
        }
        val highWater = occurrences.map { EventHighWater(it.occurrenceId, it.derivedFromEventHighWater) }

        val sessions = sessionDao.all().map { it.toDomain() }
        val segments = sessionDao.allSegments().map { it.toDomain() }
        val heartbeats = sessionDao.allHeartbeats().map { it.toDomain() }

        val ledger = ledgerDao.all().map { it.toDomain() }
        val audits = ledgerDao.allAudits().map { it.toDomainAudit() }
        val commandLog = supportDao.allCommands().map { it.toDomain() }

        return DomainState(
            revision = (commandLog.size + 1).toULong(),
            // The sequence is the newest recorded epoch, **not** a count of rows: a fresh install has
            // no row yet (the core's `initial_state` therefore starts at 1), and the first manual
            // change appends `1 + 1`. Counting rows would report 1 for a table whose only row is
            // epoch 2, so the next change would overwrite that row instead of appending epoch 3
            // (架构契约 §2.4: 追加新纪元, 历史不重算).
            zoneEpochSeq = (epochs.maxOfOrNull { it.epochSeq } ?: 1).toUInt(),
            zoneId = epochs.lastOrNull()?.zoneId ?: zoneId,
            tasks = tasks.map { it.toDomain() },
            occurrences = occurrences,
            sessions = sessions,
            activeSessionId = sessionDao.activeSlot()?.sessionId,
            segments = segments,
            heartbeats = heartbeats,
            ledger = ledger,
            audits = audits,
            zoneEpochs = epochs.map { it.toDomain() },
            commandLog = commandLog,
            completionEventHighWater = highWater,
        )
    }

    /**
     * Records the installation zone as epoch 1 if this is a fresh install, then loads.
     *
     * 规格 3 requires the first install to create the app zone epoch. Nothing calls this yet:
     * [app.arttodo.system.ZoneProvider.ensureInitialised] only writes the `app_setting` pointer,
     * so a fresh install has no `zone_epoch` row and [load] falls back to sequence 1. Kept as the
     * implementation of that requirement rather than removed.
     */
    suspend fun initialiseIfEmpty(zoneId: String, nowWallMs: Long): DomainState {
        if (supportDao.maxEpochSeq() == 0) {
            supportDao.upsertZoneEpoch(
                ZoneEpochEntity(
                    epochSeq = 1,
                    zoneId = zoneId,
                    effectiveWallMs = nowWallMs,
                    impactSummary = "首次安装",
                    createdWallMs = nowWallMs,
                ),
            )
        }
        return load(zoneId)
    }

    /**
     * The task-completion truth and the ledger, read for projection only.
     *
     * Screens need two things the whole-state read does not give them cheaply:
     *
     * 1. **Completion replayed from the authoritative event log** rather than the cached column
     *    (架构契约 §4.1: the view is a cache, the events are the truth).
     * 2. **Ledger rows for a date range**, not all rows ever written, because the statistics
     *    projections are per-range on the calendar/heat-map screens.
     *
     * Both are read-only queries. They add no interpretation: the caller passes the rows to the
     * domain core, which does the aggregation (架构契约 §9).
     */
    suspend fun completionFacts(): CompletionFacts {
        val occurrenceCompleted = mutableMapOf<String, Boolean>()
        for (occurrence in occurrenceDao.all()) {
            val derived = WorkDomainMappers.DerivedCompletion.from(
                occurrenceDao.eventsFor(occurrence.occurrenceId),
            )
            occurrenceCompleted[occurrence.occurrenceId] = derived.isCompleted
        }
        val temporaryCompleted = mutableMapOf<String, Boolean>()
        for (task in taskDao.all()) {
            if (task.kind != TASK_KIND_TEMPORARY) continue
            val events = occurrenceDao.temporaryEventsFor(task.taskId).map { it.toDomain() }
            temporaryCompleted[task.taskId] = derivedTemporaryCompletion(events)
        }
        return CompletionFacts(occurrenceCompleted, temporaryCompleted)
    }

    /** Ledger rows whose application date falls inside `[fromIso, toIso]`, inclusive. */
    suspend fun ledgerBetween(fromIso: String, toIso: String): List<LedgerRow> =
        ledgerDao.range(fromIso, toIso).map { it.toDomain() }

    /**
     * One task-day's ledger rows in replay order, which is the order the core expects.
     *
     * `ORDER BY occurred_wall_ms ASC, ledger_seq ASC` is the contract's replay order (架构契约 §4.3.1);
     * the DAO writes it once so no screen can invent a different one.
     */
    suspend fun ledgerForTaskDay(taskId: String, appDate: String): List<LedgerRow> =
        ledgerDao.forTaskDay(taskId, appDate).map { it.toDomain() }

    /** Every ledger row, for projections that span the whole history (task shares). */
    suspend fun allLedgerRows(): List<LedgerRow> = ledgerDao.all().map { it.toDomain() }

    /**
     * The two read-only sources the row-title projection needs (AC-14): the segments that carry each
     * automatic slice's `title_snapshot`, and **every** task row for the current name.
     *
     * Archived tasks are included on purpose: the records page keeps showing their history, so their
     * rows must resolve to a title rather than degrade to a raw task id.
     */
    suspend fun titleSources(): TitleSources = TitleSources(
        segments = sessionDao.allSegments().map { it.toDomain() },
        tasks = taskDao.all().map { it.toDomain() },
    )

    /** Inputs of the `LedgerRowTitle` projection, as of one read. */
    data class TitleSources(val segments: List<WorkSegment>, val tasks: List<TaskRecord>)

    suspend fun zoneEpochs(): List<ZoneEpochEntity> = supportDao.zoneEpochs()

    /** Latest local recovery point, shown in settings (架构契约 §8.4). */
    suspend fun latestBackupRecord(): BackupRecordEntity? =
        supportDao.backupRecords().maxByOrNull { it.createdWallMs }

    suspend fun archivedTasks(): List<TaskEntity> = taskDao.all().filter { it.archivedAtMs != null }

    private fun derivedTemporaryCompletion(events: List<TemporaryCompletionEvent>): Boolean {
        var completed = false
        for (event in events.sortedBy { it.eventSeq }) {
            completed = event.action == 0
        }
        return completed
    }

    data class CompletionFacts(
        val occurrenceCompleted: Map<String, Boolean>,
        val temporaryCompleted: Map<String, Boolean>,
    )

    private companion object {
        /** Mirrors `WorkDomainMappers`: 0 = daily, 1 = temporary (架构契约 §4.2 `task.kind`). */
        const val TASK_KIND_TEMPORARY = 1
    }
}
