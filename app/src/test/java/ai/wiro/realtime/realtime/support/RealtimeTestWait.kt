package ai.wiro.realtime.realtime.support

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

suspend fun waitUntil(
    attempts: Int = 100,
    condition: () -> Boolean,
) {
    repeat(attempts) {
        if (condition()) {
            return
        }
        yield()
        delay(5)
    }
}
