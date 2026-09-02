package ai.wiro.realtime.audio

import ai.wiro.realtime.audio.support.FakePcmPlaybackSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedPcmAudioPlayerTest {
    @Test
    fun audioFailuresExposeStableCodes() {
        assertEquals(
            AudioFailureCode.PlaybackUnavailable,
            AudioFailure.Playback.code,
        )
        assertEquals(
            AudioFailureCode.CaptureUnavailable,
            AudioFailure.Capture.code,
        )
    }

    @Test
    fun startInitializesTheSinkWithTheGivenProfile() = runTest {
        val sink = FakePcmPlaybackSink()
        val player = QueuedPcmAudioPlayer(
            sink,
            profile = PcmPlaybackProfile.Media,
            scope = this,
        )

        player.start()

        assertEquals(PcmPlaybackProfile.Media, sink.lastProfile)
        player.dispose()
    }

    @Test
    fun feedsQueuedChunksToTheSinkInFifoOrder() = runTest {
        val sink = FakePcmPlaybackSink()
        val player = QueuedPcmAudioPlayer(sink, scope = this)

        player.start()
        player.enqueue(byteArrayOf(1))
        player.enqueue(byteArrayOf(2))
        player.complete()

        assertEquals(2, sink.fedChunks.size)
        assertArrayEquals(byteArrayOf(1), sink.fedChunks[0])
        assertArrayEquals(byteArrayOf(2), sink.fedChunks[1])
        assertEquals(1, sink.endOfStreamCalls)
        assertFalse(player.isPlaying)
        player.dispose()
    }

    @Test
    fun completionWaitsOutALongTailWhileSinkConsumes() = runTest {
        val sink = FakePcmPlaybackSink(feedDelay = 30.milliseconds)
        val player = QueuedPcmAudioPlayer(
            sink,
            drainTimeout = 60.milliseconds,
            scope = this,
        )

        player.start()
        repeat(10) { index ->
            player.enqueue(byteArrayOf(index.toByte()))
        }
        player.complete()

        assertEquals(10, sink.fedChunks.size)
        assertEquals(1, sink.endOfStreamCalls)
        assertFalse(player.isPlaying)
        player.dispose()
    }

    @Test
    fun aStalledSinkStillLetsCompletionFinish() = runTest {
        val sink = FakePcmPlaybackSink(stalled = true)
        val player = QueuedPcmAudioPlayer(
            sink,
            drainTimeout = 40.milliseconds,
            scope = this,
        )

        player.start()
        player.enqueue(byteArrayOf(1))
        player.complete()

        assertFalse(player.isPlaying)
        assertTrue(sink.fedChunks.isEmpty())
        player.dispose()
    }

    @Test
    fun speakerDisableFlushesAndReenableAcceptsPcm() = runTest {
        val sink = FakePcmPlaybackSink()
        val player = QueuedPcmAudioPlayer(sink, scope = this)

        player.start()
        player.setEnabled(false)
        player.enqueue(byteArrayOf(1, 2))
        player.setEnabled(true)
        player.enqueue(byteArrayOf(3, 4))
        player.complete()

        assertEquals(1, sink.fedChunks.size)
        assertArrayEquals(byteArrayOf(3, 4), sink.fedChunks[0])
        assertEquals(listOf(1.0, 0.0, 1.0), sink.volumes)
        assertEquals(1, sink.stopCalls)
        assertTrue(player.isEnabled)
        player.dispose()
    }

    @Test
    fun reportsSanitizedPlaybackFailures() = runTest {
        val sink = FakePcmPlaybackSink(failFeed = true)
        val player = QueuedPcmAudioPlayer(sink, scope = this)
        var failure: AudioFailure? = null
        val collector = launch {
            player.failures.collect { seen ->
                failure = seen
            }
        }

        player.start()
        player.enqueue(byteArrayOf(1))
        testScheduler.advanceUntilIdle()

        assertEquals(AudioFailure.Playback, failure)
        assertFalse(failure.toString().contains("platform"))
        collector.cancel()
        player.dispose()
    }

    @Test
    fun everyStartReinitializesTheSinkAndDisposeRunsOnce() = runTest {
        val sink = FakePcmPlaybackSink()
        val player = QueuedPcmAudioPlayer(sink, scope = this)

        player.start()
        player.stop()
        player.start()
        player.complete()
        player.dispose()
        player.dispose()

        assertEquals(2, sink.initializeCalls)
        assertEquals(2, sink.playCalls)
        assertEquals(1, sink.disposeCalls)
    }
}
