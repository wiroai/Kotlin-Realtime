package ai.wiro.realtime.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionElapsedClockTest {
    @Test
    fun startReplacesThePreviousTimer() = runTest {
        val clock = SessionElapsedClock(
            tick = 10.milliseconds,
            scope = this,
        )
        val ticks = mutableListOf<Duration>()
        clock.start { ticks += it }
        assertTrue(clock.isRunning)
        clock.start { ticks += it }
        advanceTimeBy(15)
        clock.stop()
        assertFalse(clock.isRunning)
        assertEquals(1, ticks.size)
        assertEquals(10.milliseconds, ticks.first())
    }
}
