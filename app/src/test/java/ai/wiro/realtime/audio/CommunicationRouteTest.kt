package ai.wiro.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunicationRouteTest {
    @Test
    fun wiredHeadsetBeatsBluetoothAndSpeaker() {
        assertEquals(
            CommunicationRoute.Output.WiredHeadset,
            CommunicationRoute.choose(
                hasWiredHeadset = true,
                hasBluetoothSco = true,
            ),
        )
    }

    @Test
    fun bluetoothBeatsTheSpeaker() {
        assertEquals(
            CommunicationRoute.Output.BluetoothSco,
            CommunicationRoute.choose(
                hasWiredHeadset = false,
                hasBluetoothSco = true,
            ),
        )
    }

    @Test
    fun speakerIsTheDefaultWhenNothingIsPluggedIn() {
        assertEquals(
            CommunicationRoute.Output.Speaker,
            CommunicationRoute.choose(
                hasWiredHeadset = false,
                hasBluetoothSco = false,
            ),
        )
    }
}
