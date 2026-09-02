package ai.wiro.realtime.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.time.Duration

class PcmNoiseGateTest {
    @Test
    fun quietRoomAudioIsAttenuatedToAWhisper() {
        val gate = PcmNoiseGate()
        val output = gate.process(pcm(amplitude = 300))

        assertFalse(gate.isOpen)
        assertTrue(maxAmplitude(output) < 30)
        assertTrue(maxAmplitude(output) > 0)
    }

    @Test
    fun nearFieldSpeechOpensTheGateAndPassesUntouched() {
        val gate = PcmNoiseGate()
        val input = pcm(amplitude = 15_000)
        val output = gate.process(input)

        assertArrayEquals(input, output)
        assertTrue(gate.isOpen)
    }

    @Test
    fun theDipBetweenTwoWordsStaysInsideTheHangover() {
        val gate = PcmNoiseGate()
        gate.process(pcm(amplitude = 15_000))
        val dip = pcm(amplitude = 900)
        val output = gate.process(dip)
        assertArrayEquals(dip, output)
    }

    @Test
    fun hangoverLowersTheBarWithoutRemovingIt() {
        val gate = PcmNoiseGate()
        gate.process(pcm(amplitude = 15_000))
        val hum = gate.process(pcm(amplitude = 200))
        assertTrue(maxAmplitude(hum) < 30)
    }

    @Test
    fun aSteadyHumIsRecognisedAndLearnedInSeconds() {
        val gate = PcmNoiseGate(hangover = Duration.ZERO)
        val hum = pcm(amplitude = 1_500)

        assertArrayEquals(hum, gate.process(hum))
        assertFalse(gate.hearsHum)

        repeat(50) { gate.process(hum) }
        assertTrue(gate.hearsHum)

        repeat(150) { gate.process(hum) }
        assertTrue(maxAmplitude(gate.process(hum)) < 150)

        val speech = pcm(amplitude = 15_000)
        assertArrayEquals(speech, gate.process(speech))
    }

    @Test
    fun aLongSentenceIsNeverMistakenForAMachine() {
        val gate = PcmNoiseGate()
        val syllables = intArrayOf(
            14_000,
            16_000,
            9_000,
            15_000,
            6_000,
            13_000,
            800,
            11_000,
        )
        repeat(500) { index ->
            gate.process(pcm(amplitude = syllables[index % syllables.size]))
            assertFalse(gate.hearsHum)
        }
        val loud = pcm(amplitude = 15_000)
        assertArrayEquals(loud, gate.process(loud))
    }

    @Test
    fun theHumTestReadsShapeNotVolume() {
        val loudHum = PcmNoiseGate(hangover = Duration.ZERO)
        val quietSpeech = PcmNoiseGate(hangover = Duration.ZERO)
        val syllables = intArrayOf(
            1_400,
            1_600,
            900,
            1_500,
            600,
            1_300,
            80,
            1_100,
        )
        repeat(100) { index ->
            loudHum.process(pcm(amplitude = 14_000))
            quietSpeech.process(
                pcm(amplitude = syllables[index % 8]),
            )
        }
        assertTrue(loudHum.hearsHum)
        assertFalse(quietSpeech.hearsHum)
    }

    @Test
    fun aTelevisionBecomesTheLearnedFloor() {
        val gate = PcmNoiseGate(hangover = Duration.ZERO)
        repeat(2_000) { gate.process(pcm(amplitude = 2_500)) }

        assertTrue(gate.ambientFloor > 0.1)
        val television = gate.process(pcm(amplitude = 2_500))
        assertTrue(maxAmplitude(television) < 200)

        val speech = pcm(amplitude = 18_000)
        assertArrayEquals(speech, gate.process(speech))
    }

    @Test
    fun assistantPlaybackRaisesTheBarSoEchoStaysQuiet() {
        val gate = PcmNoiseGate()
        val echo = pcm(amplitude = 2_000)
        val leaked = gate.process(echo, assistantAudible = true)

        assertFalse(gate.isOpen)
        assertTrue(maxAmplitude(leaked) < 200)

        val speech = pcm(amplitude = 15_000)
        assertArrayEquals(
            speech,
            gate.process(speech, assistantAudible = true),
        )
        assertTrue(gate.isOpen)
    }

    @Test
    fun resetForgetsTheLearnedRoom() {
        val gate = PcmNoiseGate()
        repeat(2_000) { gate.process(pcm(amplitude = 2_500)) }
        assertTrue(gate.ambientFloor > 0.1)

        gate.reset()

        assertEquals(0.0, gate.ambientFloor, 0.0)
        assertFalse(gate.isOpen)
    }
}

private fun pcm(amplitude: Int, samples: Int = 480): ByteArray {
    val bytes = ByteArray(samples * 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    repeat(samples) {
        buffer.putShort(amplitude.toShort())
    }
    return bytes
}

private fun maxAmplitude(chunk: ByteArray): Int {
    val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
    var maxValue = 0
    repeat(chunk.size / 2) {
        maxValue = maxOf(maxValue, abs(buffer.short.toInt()))
    }
    return maxValue
}
