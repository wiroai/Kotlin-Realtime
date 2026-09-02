package ai.wiro.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEchoCancelerTest {
    @Test
    fun missingEffectDoesNotBlockCapture() {
        val canceler = SessionEchoCanceler(
            isAvailable = { false },
            create = { error("must not create") },
        )
        canceler.attach(audioSessionId = 7)
        assertFalse(canceler.isAttached)
    }

    @Test
    fun attachBindsTheSessionAndReleaseIsIdempotent() {
        val released = mutableListOf<Int>()
        val canceler = SessionEchoCanceler(
            isAvailable = { true },
            create = { sessionId ->
                SessionEchoCanceler.CancellableEffect {
                    released += sessionId
                }
            },
        )
        canceler.attach(audioSessionId = 3)
        assertTrue(canceler.isAttached)
        canceler.release()
        canceler.release()
        assertFalse(canceler.isAttached)
        assertEquals(listOf(3), released)
    }

    @Test
    fun aSecondAttachReleasesTheFirstEffect() {
        val released = mutableListOf<Int>()
        val canceler = SessionEchoCanceler(
            isAvailable = { true },
            create = { sessionId ->
                SessionEchoCanceler.CancellableEffect {
                    released += sessionId
                }
            },
        )
        canceler.attach(audioSessionId = 1)
        canceler.attach(audioSessionId = 2)
        assertEquals(listOf(1), released)
        canceler.release()
        assertEquals(listOf(1, 2), released)
    }

    @Test
    fun aFailedCreateLeavesNothingAttached() {
        val canceler = SessionEchoCanceler(
            isAvailable = { true },
            create = { null },
        )
        canceler.attach(audioSessionId = 9)
        assertFalse(canceler.isAttached)
    }
}
