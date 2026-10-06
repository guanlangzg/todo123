package app.arttodo.system

import app.arttodo.data.AppDatabase
import app.arttodo.domain.bridge.DomainBridge
import app.arttodo.domain.bridge.DomainResult

/**
 * The application natural day of an instant, decided by the domain core (架构契约 §2).
 *
 * Used where a *stored* day label is needed outside a command — the daily recovery point is the only
 * such place today. The zone comes from the newest `zone_epoch` row, the same authority the command
 * writer reads, so the boundary of a "day" cannot disagree with the days the ledger uses.
 *
 * [Days] uses the core's `dayLabelOf`, not `appDateOf`: the day an instant falls into is defined by
 * the `[start, end)` windows that slicing already uses (架构契约 §2.3 rule 4/5).
 */
class AppDayLabel(
    private val database: AppDatabase,
    private val bridge: DomainBridge,
    private val fallbackZoneId: () -> String,
) {
    /** `null` when the core cannot answer; callers must refuse rather than guess a day. */
    suspend fun of(wallMs: Long): String? {
        val newest = database.supportDao().zoneEpochs().lastOrNull()
        val zone = newest?.zoneId ?: fallbackZoneId()
        val epochSeq = newest?.epochSeq ?: 1
        return when (val label = bridge.dayLabelOf(wallMs, zone, epochSeq)) {
            is DomainResult.Success -> label.value
            is DomainResult.Failure -> null
        }
    }
}
