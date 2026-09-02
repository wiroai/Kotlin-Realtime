package ai.wiro.realtime.audio

import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.realtime.support.waitUntil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeAudioRouteWatcherTest {
    @Test
    fun beginningInterruptionRequestsAStop() = runTest {
        val harness = Harness(this)
        harness.session.emitInterruption(RealtimeAudioInterruption.Began)
        waitUntil { harness.stopRequests == 1 }
        assertEquals(1, harness.stopRequests)
        harness.dispose()
    }

    @Test
    fun endingInterruptionDoesNotRequestAStop() = runTest {
        val harness = Harness(this)
        harness.session.emitInterruption(RealtimeAudioInterruption.Ended)
        testScheduler.advanceUntilIdle()
        assertEquals(0, harness.stopRequests)
        harness.dispose()
    }

    @Test
    fun unpluggingTheOutputRouteRequestsAStop() = runTest {
        val harness = Harness(this)
        harness.session.emitBecomingNoisy()
        waitUntil { harness.stopRequests == 1 }
        assertEquals(1, harness.stopRequests)
        harness.dispose()
    }

    @Test
    fun aRouteChangeIsReportedSeparatelyFromAStop() = runTest {
        val harness = Harness(this)
        harness.session.emitRouteChange()
        waitUntil { harness.routeChanges == 1 }
        assertEquals(1, harness.routeChanges)
        assertEquals(0, harness.stopRequests)
        harness.dispose()
    }

    @Test
    fun playbackFailuresReachTheCallerUnchanged() = runTest {
        val harness = Harness(this)
        harness.failureEvents.tryEmit(AudioFailure.Playback)
        waitUntil { harness.failures == listOf(AudioFailure.Playback) }
        assertEquals(listOf(AudioFailure.Playback), harness.failures)
        harness.dispose()
    }

    @Test
    fun disposalDetachesEverySource() = runTest {
        val harness = Harness(this)
        harness.watcher.dispose()

        harness.session.emitInterruption(RealtimeAudioInterruption.Began)
        harness.session.emitBecomingNoisy()
        harness.session.emitRouteChange()
        harness.failureEvents.tryEmit(AudioFailure.Playback)
        testScheduler.advanceUntilIdle()

        assertEquals(0, harness.stopRequests)
        assertEquals(0, harness.routeChanges)
        assertTrue(harness.failures.isEmpty())
        harness.session.dispose()
    }
}

private class Harness(
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val session = FakeRealtimeAudioSession()
    var stopRequests: Int = 0
    var routeChanges: Int = 0
    val failures: MutableList<AudioFailure> = mutableListOf()
    val failureEvents = MutableSharedFlow<AudioFailure>(
        extraBufferCapacity = 16,
    )
    val watcher = RealtimeAudioRouteWatcher(
        audioSession = session,
        playbackFailures = failureEvents,
        onShouldStop = { stopRequests += 1 },
        onRouteChanged = { routeChanges += 1 },
        onPlaybackFailure = { failures += it },
        scope = scope,
    )

    suspend fun dispose() {
        watcher.dispose()
        session.dispose()
    }
}
