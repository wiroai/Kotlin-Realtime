package ai.wiro.realtime.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSettingsOfferTest {
    @Test
    fun aFreshDenialRaisesTheAlertOnce() {
        var offer = SystemSettingsOffer()
        offer = offer.apply(canOpen = true)

        assertTrue(offer.canOpen)
        assertTrue(offer.showsAlert)

        offer = offer.dismissAlert()
        assertTrue(offer.canOpen)
        assertFalse(offer.showsAlert)

        offer = offer.apply(canOpen = true)
        assertFalse(offer.showsAlert)
    }

    @Test
    fun clearingTheDenialDropsTheAlert() {
        var offer = SystemSettingsOffer()
            .apply(canOpen = true)
            .apply(canOpen = false)

        assertFalse(offer.canOpen)
        assertFalse(offer.showsAlert)
    }

    @Test
    fun aLaterDenialIsANewOffer() {
        val offer = SystemSettingsOffer()
            .apply(canOpen = true)
            .dismissAlert()
            .apply(canOpen = false)
            .apply(canOpen = true)

        assertTrue(offer.canOpen)
        assertTrue(offer.showsAlert)
    }

    @Test
    fun copyIsTheSharedSettingsWording() {
        assertEquals("Open settings", SystemSettingsCopy.OPEN)
        assertEquals("Microphone access", SystemSettingsCopy.ALERT_TITLE)
    }
}
