package ai.wiro.realtime.ui.home

import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RealtimeHomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun onlyTheSelectedDestinationIsVisible() {
        val model = RealtimeHomeViewModel()
        assertTrue(model.isVisible(RealtimeDestination.Conversation))
        assertFalse(model.isVisible(RealtimeDestination.Transcription))
        assertFalse(model.isVisible(RealtimeDestination.Speech))
    }

    @Test
    fun selectingATabHidesTheOthers() {
        val model = RealtimeHomeViewModel()
        model.select(RealtimeDestination.Transcription)
        assertFalse(model.isVisible(RealtimeDestination.Conversation))
        assertTrue(model.isVisible(RealtimeDestination.Transcription))
        assertFalse(model.isVisible(RealtimeDestination.Speech))

        model.select(RealtimeDestination.Speech)
        assertFalse(model.isVisible(RealtimeDestination.Conversation))
        assertFalse(model.isVisible(RealtimeDestination.Transcription))
        assertTrue(model.isVisible(RealtimeDestination.Speech))
    }

    @Test
    fun returningToConversationRestoresItsVisibility() {
        val model = RealtimeHomeViewModel()
        model.select(RealtimeDestination.Speech)
        model.select(RealtimeDestination.Conversation)
        assertTrue(model.isVisible(RealtimeDestination.Conversation))
        assertFalse(model.isVisible(RealtimeDestination.Transcription))
        assertFalse(model.isVisible(RealtimeDestination.Speech))
    }

    @Test
    fun deactivateAudioReleasesTheSharedSession() = runTest {
        val audioSession = FakeRealtimeAudioSession()
        val model = RealtimeHomeViewModel(audioSession = audioSession)

        model.deactivateAudio()

        assertEquals(1, audioSession.deactivateCalls)
        model.dispose()
    }

    @Test
    fun disposeClosesAudioAndTheSharedGuard() = runTest {
        val audioSession = FakeRealtimeAudioSession()
        val model = RealtimeHomeViewModel(audioSession = audioSession)
        model.sessionGuard.acquire(RealtimeSessionOwner.Conversation)

        model.dispose()

        assertEquals(1, audioSession.disposeCalls)
        assertNull(
            model.sessionGuard.acquire(RealtimeSessionOwner.Speech),
        )
    }
}
