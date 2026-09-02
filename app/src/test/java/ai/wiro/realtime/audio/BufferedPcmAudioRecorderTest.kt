package ai.wiro.realtime.audio

import ai.wiro.realtime.audio.support.FakePcmCaptureSource
import ai.wiro.realtime.realtime.support.waitUntil
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedPcmAudioRecorderTest {
    @Test
    fun emitsFixedTwentyMillisecondChunksInFifoOrder() = runTest {
        val source = FakePcmCaptureSource()
        val recorder = BufferedPcmAudioRecorder(source, scope = this)
        val stream = recorder.start(PcmCaptureProfile.Transcription)
        val received = mutableListOf<ByteArray>()
        val collector = launch {
            stream.collect { chunk ->
                received += chunk
            }
        }

        val bytes = ByteArray(AudioConstants.PCM_BYTES_PER_CHUNK * 2 + 80) {
            (it % 255).toByte()
        }
        source.emit(bytes)
        waitUntil { received.size == 2 }

        assertEquals(2, received.size)
        assertTrue(
            received.all { it.size == AudioConstants.PCM_BYTES_PER_CHUNK },
        )
        collector.cancel()
        recorder.dispose()
    }

    @Test
    fun muteDropsCapturedAndQueuedMicrophoneAudio() = runTest {
        val source = FakePcmCaptureSource()
        val recorder = BufferedPcmAudioRecorder(source, scope = this)
        val stream = recorder.start(PcmCaptureProfile.Conversation)
        val received = mutableListOf<ByteArray>()
        val collector = launch {
            stream.collect { chunk ->
                received += chunk
            }
        }

        recorder.setMuted(true)
        source.emit(ByteArray(AudioConstants.PCM_BYTES_PER_CHUNK * 2))
        waitUntil { recorder.mutedSourceBuffers >= 1 }
        recorder.setMuted(false)
        source.emit(
            ByteArray(AudioConstants.PCM_BYTES_PER_CHUNK) { 7 },
        )
        waitUntil { received.size == 1 }

        assertEquals(1, received.size)
        assertTrue(received[0].all { it == 7.toByte() })
        collector.cancel()
        recorder.dispose()
    }

    @Test
    fun mapsRawCaptureErrorsToASanitizedFailure() = runTest {
        val source = FakePcmCaptureSource()
        val recorder = BufferedPcmAudioRecorder(source, scope = this)
        val stream = recorder.start(PcmCaptureProfile.Transcription)
        var error: Throwable? = null
        val collector = launch {
            try {
                stream.collect { }
            } catch (thrown: Throwable) {
                error = thrown
            }
        }

        source.emitError(Exception("private platform detail"))
        waitUntil { error != null }

        assertEquals(AudioFailure.Capture, error)
        assertFalse(error.toString().contains("platform"))
        collector.cancel()
        recorder.dispose()
    }

    @Test
    fun repeatedStartStopAndDisposeReleaseTheSource() = runTest {
        val source = FakePcmCaptureSource()
        val recorder = BufferedPcmAudioRecorder(source, scope = this)

        recorder.start(PcmCaptureProfile.Transcription)
        recorder.stop()
        recorder.start(PcmCaptureProfile.Conversation)
        recorder.stop()
        recorder.dispose()
        recorder.dispose()

        assertEquals(2, source.startCalls)
        assertEquals(2, source.stopCalls)
        assertEquals(1, source.disposeCalls)
    }
}
