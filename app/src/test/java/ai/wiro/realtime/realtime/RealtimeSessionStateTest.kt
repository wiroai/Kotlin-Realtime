package ai.wiro.realtime.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSessionStateTest {
    @Test
    fun idleLikeStatesCanStartAndActiveStatesCanStop() {
        assertTrue(RealtimeSessionState.Idle.canStart)
        assertTrue(RealtimeSessionState.Completed.canStart)
        assertTrue(RealtimeSessionState.Failure.canStart)
        assertFalse(RealtimeSessionState.Streaming.canStart)
        assertTrue(RealtimeSessionState.Streaming.canStop)
        assertFalse(RealtimeSessionState.Idle.canStop)
    }
}
