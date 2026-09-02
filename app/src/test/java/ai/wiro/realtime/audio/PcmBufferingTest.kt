package ai.wiro.realtime.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PcmBufferingTest {
    @Test
    fun chunkerEmitsFixedChunksAndKeepsARemainder() {
        val chunker = PcmChunker(chunkSizeInBytes = 4)
        val first = chunker.add(byteArrayOf(1, 2, 3))
        val second = chunker.add(byteArrayOf(4, 5, 6, 7, 8, 9))

        assertTrue(first.isEmpty())
        assertEquals(2, second.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), second[0])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), second[1])
        assertEquals(1, chunker.remainderLength)
    }

    @Test
    fun boundedQueuePreservesFifoOrder() = runTest {
        val queue = BoundedPcmQueue(capacityInChunks = 2)
        queue.add(byteArrayOf(1))
        queue.add(byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), queue.take())
        assertArrayEquals(byteArrayOf(2), queue.take())
    }

    @Test
    fun boundedQueueAppliesProducerBackpressure() = runTest {
        val queue = BoundedPcmQueue(capacityInChunks = 1)
        queue.add(byteArrayOf(1))
        var added = false
        val secondAdd = async {
            queue.add(byteArrayOf(2))
            added = true
        }
        delay(20.milliseconds)
        assertFalse(added)

        assertArrayEquals(byteArrayOf(1), queue.take())
        secondAdd.await()
        assertTrue(added)
        assertArrayEquals(byteArrayOf(2), queue.take())
    }

    @Test
    fun boundedQueueShedsOverflowAndTracksMetrics() {
        val queue = BoundedPcmQueue(capacityInChunks = 1)

        assertTrue(queue.tryAdd(byteArrayOf(1)))
        assertFalse(queue.tryAdd(byteArrayOf(2)))

        assertEquals(1, queue.highWaterMarkInChunks)
        assertEquals(1, queue.rejectedChunks)
        assertEquals(1, queue.length)
    }

    @Test
    fun closingAQueueReleasesPendingConsumers() = runTest {
        val queue = BoundedPcmQueue(capacityInChunks = 1)
        val pending = async { queue.take() }
        queue.close()

        assertNull(pending.await())
        assertTrue(queue.isClosed)
    }

    @Test
    fun bufferedDurationIsUnplayedFramesNotARunningTotal() {
        assertEquals(
            2.seconds,
            pcmBufferedDuration(
                writtenFrames = 72_000,
                playbackHeadFrames = 24_000,
            ),
        )
        assertEquals(
            Duration.ZERO,
            pcmBufferedDuration(
                writtenFrames = 24_000,
                playbackHeadFrames = 24_000,
            ),
        )
        assertEquals(
            Duration.ZERO,
            pcmBufferedDuration(
                writtenFrames = 1_000,
                playbackHeadFrames = 2_000,
            ),
        )
    }

    @Test
    fun wireFormatMatchesTheDocumentedPcmFrame() {
        assertEquals(24_000, AudioConstants.SAMPLE_RATE_HZ)
        assertEquals(480, AudioConstants.SAMPLES_PER_CHUNK)
        assertEquals(960, AudioConstants.PCM_BYTES_PER_CHUNK)
        assertEquals(2, AudioConstants.BYTES_PER_SAMPLE)
    }
}
