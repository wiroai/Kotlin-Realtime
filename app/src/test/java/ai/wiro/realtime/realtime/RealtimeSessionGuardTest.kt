package ai.wiro.realtime.realtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class RealtimeSessionGuardTest {
    @Test
    fun allowsOneLeaseAndRejectsParallelSessions() {
        val sessionGuard = RealtimeSessionGuard()
        val conversation = sessionGuard.acquire(
            RealtimeSessionOwner.Conversation,
        )
        val transcription = sessionGuard.acquire(
            RealtimeSessionOwner.Transcription,
        )

        assertNotNull(conversation)
        assertNull(transcription)
        assertEquals(conversation, sessionGuard.currentLease)
        sessionGuard.dispose()
    }

    @Test
    fun onlyTheCurrentLeaseCanReleaseTheGuard() {
        val sessionGuard = RealtimeSessionGuard()
        val first = sessionGuard.acquire(RealtimeSessionOwner.Speech)
        sessionGuard.release(first)
        val second = sessionGuard.acquire(RealtimeSessionOwner.Speech)

        sessionGuard.release(first)

        assertEquals(second, sessionGuard.currentLease)
        sessionGuard.dispose()
    }

    @Test
    fun acquireWhenFreeAcquiresImmediatelyWhenNothingIsHeld() = runTest {
        val sessionGuard = RealtimeSessionGuard()
        val lease = sessionGuard.acquireWhenFree(
            RealtimeSessionOwner.Conversation,
        )

        assertNotNull(lease)
        assertEquals(lease, sessionGuard.currentLease)
        sessionGuard.dispose()
    }

    @Test
    fun acquireWhenFreeWaitsOutTheDepartingSession() = runTest {
        val sessionGuard = RealtimeSessionGuard()
        val departing = sessionGuard.acquire(
            RealtimeSessionOwner.Conversation,
        )

        val pending = async {
            sessionGuard.acquireWhenFree(
                RealtimeSessionOwner.Transcription,
            )
        }
        delay(20.milliseconds)
        sessionGuard.release(departing)

        val lease = pending.await()
        assertEquals(RealtimeSessionOwner.Transcription, lease?.owner)
        assertEquals(lease, sessionGuard.currentLease)
        sessionGuard.dispose()
    }

    @Test
    fun acquireWhenFreeGivesUpOnceNothingLetsGo() = runTest {
        val sessionGuard = RealtimeSessionGuard()
        sessionGuard.acquire(RealtimeSessionOwner.Conversation)

        val lease = sessionGuard.acquireWhenFree(
            owner = RealtimeSessionOwner.Speech,
            timeout = 20.milliseconds,
        )

        assertNull(lease)
        sessionGuard.dispose()
    }

    @Test
    fun disposalWakesAPendingAcquireWhenFreeWithNothing() = runTest {
        val sessionGuard = RealtimeSessionGuard()
        sessionGuard.acquire(RealtimeSessionOwner.Conversation)
        val pending = async {
            sessionGuard.acquireWhenFree(RealtimeSessionOwner.Speech)
        }
        delay(20.milliseconds)
        sessionGuard.dispose()

        assertNull(pending.await())
    }

    @Test
    fun ignoresAsynchronousReleaseAfterDisposal() {
        val sessionGuard = RealtimeSessionGuard()
        val lease = sessionGuard.acquire(
            RealtimeSessionOwner.Conversation,
        )

        sessionGuard.dispose()
        sessionGuard.release(lease)

        assertNull(
            sessionGuard.acquire(RealtimeSessionOwner.Transcription),
        )
    }
}
