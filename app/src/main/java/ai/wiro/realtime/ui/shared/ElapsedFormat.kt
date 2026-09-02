package ai.wiro.realtime.ui.shared

import kotlin.time.Duration

/** The live stage clock: minutes and padded seconds, `1:07`. */
fun formatElapsed(elapsed: Duration): String {
    val seconds = elapsed.inWholeSeconds
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "$minutes:${remainder.toString().padStart(2, '0')}"
}
