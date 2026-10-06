package app.arttodo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.arttodo.core.WorkSegment
import kotlinx.coroutines.delay

/**
 * A one-second ticker, scoped to a single composable.
 *
 * 架构契约 §3.1 forbids calling FFI per tick: only the timer text may refresh each second, and the
 * refresh uses a **local anchor** rather than a core round trip. The ticker stops entirely while the
 * session is paused, which is what makes the paused digits stable rather than merely slow
 * (动效与无障碍说明 section 2.3).
 */
@Composable
fun rememberSessionNow(
    running: Boolean,
    powerSave: Boolean,
    initialNowMs: () -> Long,
): State<Long> {
    val period = when {
        !running -> Long.MAX_VALUE
        // Power-save drops the digit repaint to every 5 seconds; the seconds actually booked do not
        // change, because the finish path measures real instants (动效与无障碍说明 section 4.4).
        powerSave -> 5_000L
        else -> 1_000L
    }
    var now by remember { mutableLongStateOf(initialNowMs()) }
    LaunchedEffect(period) {
        if (running) {
            while (true) {
                now = initialNowMs()
                delay(period)
            }
        } else {
            now = initialNowMs()
        }
    }
    return remember(now) { mutableLongStateOf(now) }
}

/**
 * Seconds actually invested: the sum of the session's segments measured up to [nowWallMs].
 *
 * Paused spans are absent from the segment list by construction, so this never charges the pause
 * interval (AC-05). The formula mirrors `session::effective_seconds` exactly; the authority for the
 * booked number is still the core, which re-derives it when the session finishes.
 */
fun elapsedSeconds(segments: List<WorkSegment>, sessionId: String, nowWallMs: Long): Long =
    segments.filter { it.sessionId == sessionId }.sumOf { segment ->
        val end = segment.endWallMs ?: nowWallMs
        if (end <= segment.startWallMs) 0L else (end - segment.startWallMs) / 1000
    }

/** Remaining seconds of a countdown target, floored at zero. */
fun remainingSeconds(targetSeconds: Long?, elapsed: Long): Long? =
    targetSeconds?.let { (it - elapsed).coerceAtLeast(0) }

/**
 * True once a countdown has reached its target.
 *
 * The screen never finishes the session itself: reaching zero produces a visible result state and the
 * user's choice, and the task is decidedly **not** auto-completed (规格 5.1, AC-07).
 */
fun countdownReached(targetSeconds: Long?, elapsed: Long): Boolean =
    targetSeconds != null && elapsed >= targetSeconds
