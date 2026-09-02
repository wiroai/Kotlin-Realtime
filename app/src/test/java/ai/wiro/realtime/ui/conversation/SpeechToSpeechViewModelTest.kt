package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.conversation.ConversationSessionSnapshot
import ai.wiro.realtime.services.conversation.support.FakeConversationSessionController
import ai.wiro.realtime.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SpeechToSpeechViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun adoptsASessionThatIsAlreadyMidConversation() = runTest {
        val controller = FakeConversationSessionController()
        controller.snapshot = ConversationSessionSnapshot(
            sessionState = RealtimeSessionState.Streaming,
            muted = true,
        )
        val viewModel = SpeechToSpeechViewModel(controller)

        assertEquals(
            RealtimeSessionState.Streaming,
            viewModel.uiState.value.sessionState,
        )
        assertTrue(viewModel.uiState.value.muted)
        viewModel.dispose()
    }

    @Test
    fun projectsOntoViewStateWhatTheScreenDraws() = runTest {
        val harness = makeHarness()

        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.Streaming,
                elapsed = 7.seconds,
                userTranscript = "hello",
                aiTranscript = "hi there",
                muted = true,
                isReconfiguring = true,
                errorMessage = "boom",
                canOpenSystemSettings = true,
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.Streaming
        }

        val state = harness.viewModel.uiState.value
        assertEquals(RealtimeSessionState.Streaming, state.sessionState)
        assertEquals(7.seconds, state.elapsed)
        assertEquals("hello", state.userTranscript)
        assertEquals("hi there", state.aiTranscript)
        assertTrue(state.muted)
        assertTrue(state.isReconfiguring)
        assertEquals("boom", state.errorMessage)
        assertTrue(state.canOpenSystemSettings)
        assertTrue(state.showsSystemSettingsAlert)
        harness.viewModel.dispose()
    }

    @Test
    fun clearingAnErrorMessagePropagatesTheNull() = runTest {
        val harness = makeHarness()
        harness.controller.publish(
            ConversationSessionSnapshot(errorMessage = "boom"),
        )
        waitUntil { harness.viewModel.uiState.value.errorMessage == "boom" }

        harness.controller.publish(ConversationSessionSnapshot())
        waitUntil { harness.viewModel.uiState.value.errorMessage == null }

        assertNull(harness.viewModel.uiState.value.errorMessage)
        harness.viewModel.dispose()
    }

    @Test
    fun startRaisesReportsConnectingAndGoingLiveLowersIt() = runTest {
        val harness = makeHarness()
        assertFalse(harness.viewModel.uiState.value.reportsConnecting)

        harness.viewModel.start()
        assertTrue(harness.viewModel.uiState.value.reportsConnecting)

        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.CreatingTask,
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.CreatingTask
        }
        assertTrue(harness.viewModel.uiState.value.reportsConnecting)
        assertTrue(harness.viewModel.uiState.value.showsConnecting)
        assertEquals(ConversationStage.Connecting, harness.viewModel.displayedStage)

        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.Streaming,
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.Streaming
        }
        assertFalse(harness.viewModel.uiState.value.reportsConnecting)
        assertFalse(harness.viewModel.uiState.value.showsConnecting)
        assertEquals(ConversationStage.Live, harness.viewModel.displayedStage)
        harness.viewModel.dispose()
    }

    @Test
    fun aFailedStartAlsoLowersReportsConnecting() = runTest {
        val harness = makeHarness()
        harness.viewModel.start()

        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.Failure,
                errorMessage = "boom",
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.Failure
        }

        assertFalse(harness.viewModel.uiState.value.reportsConnecting)
        assertEquals(ConversationStage.Failed, harness.viewModel.displayedStage)
        harness.viewModel.dispose()
    }

    @Test
    fun aSessionConnectingOnItsOwnRaisesNoReportsConnecting() = runTest {
        val harness = makeHarness()

        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.ConnectingSocket,
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.ConnectingSocket
        }

        assertFalse(harness.viewModel.uiState.value.reportsConnecting)
        assertFalse(harness.viewModel.uiState.value.showsConnecting)
        assertEquals(ConversationStage.Live, harness.viewModel.displayedStage)
        harness.viewModel.dispose()
    }

    @Test
    fun settingsAlertIsEdgeTriggered() = runTest {
        val harness = makeHarness()
        harness.controller.publish(
            ConversationSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil { harness.viewModel.uiState.value.showsSystemSettingsAlert }

        harness.viewModel.dismissSystemSettingsAlert()
        assertFalse(harness.viewModel.uiState.value.showsSystemSettingsAlert)
        assertTrue(harness.viewModel.uiState.value.canOpenSystemSettings)

        harness.controller.publish(
            ConversationSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil { harness.viewModel.uiState.value.canOpenSystemSettings }
        assertFalse(harness.viewModel.uiState.value.showsSystemSettingsAlert)

        harness.controller.publish(ConversationSessionSnapshot())
        waitUntil { !harness.viewModel.uiState.value.canOpenSystemSettings }
        harness.controller.publish(
            ConversationSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil { harness.viewModel.uiState.value.showsSystemSettingsAlert }
        harness.viewModel.dispose()
    }

    @Test
    fun commandsAreDelegatedToTheSession() = runTest {
        val harness = makeHarness()

        harness.viewModel.start()
        harness.viewModel.stop()
        harness.viewModel.setMuted(true)
        harness.viewModel.interruptAssistant()
        harness.viewModel.suspendSession()
        harness.viewModel.resumeSession()
        harness.viewModel.openSystemSettings()

        assertEquals(1, harness.controller.startCalls)
        assertEquals(1, harness.controller.stopCalls)
        assertEquals(listOf(true), harness.controller.muteRequests)
        assertEquals(1, harness.controller.interruptCalls)
        assertEquals(1, harness.controller.suspendCalls)
        assertEquals(1, harness.controller.resumeCalls)
        assertEquals(1, harness.controller.openSettingsCalls)
        harness.viewModel.dispose()
    }

    @Test
    fun idleVoiceChangeWritesTheParameter() = runTest {
        val harness = makeHarness()

        harness.viewModel.selectVoice("cedar")

        assertEquals(
            listOf("cedar"),
            harness.controller.setParameterCalls.map { it.second },
        )
        assertTrue(harness.controller.changeVoiceCalls.isEmpty())
        assertEquals("cedar", harness.viewModel.voice)
        harness.viewModel.dispose()
    }

    @Test
    fun liveVoiceChangeGoesThroughChangeVoice() = runTest {
        val harness = makeHarness()
        harness.controller.publish(
            ConversationSessionSnapshot(
                sessionState = RealtimeSessionState.Streaming,
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.Streaming
        }

        harness.viewModel.selectVoice("cedar")

        assertEquals(listOf("cedar"), harness.controller.changeVoiceCalls)
        assertTrue(harness.controller.setParameterCalls.isEmpty())
        assertEquals("cedar", harness.viewModel.voice)
        harness.viewModel.dispose()
    }

    @Test
    fun anotherFeatureHoldingTheMicBlocksStart() = runTest {
        val harness = makeHarness()
        val lease = harness.controller.sessionGuard.acquire(
            RealtimeSessionOwner.Transcription,
        )
        assertNotNullLease(lease)
        assertFalse(harness.viewModel.canStart)

        harness.controller.sessionGuard.release(lease)
        assertTrue(harness.viewModel.canStart)
        harness.viewModel.dispose()
    }

    @Test
    fun disposeReleasesTheSessionAndStopsListening() = runTest {
        val controller = FakeConversationSessionController()
        val viewModel = SpeechToSpeechViewModel(controller)
        viewModel.dispose()

        controller.publish(ConversationSessionSnapshot(muted = true))
        kotlinx.coroutines.delay(40)

        assertEquals(1, controller.disposeCalls)
        assertFalse(viewModel.uiState.value.muted)
    }

    private fun makeHarness(): Harness {
        val controller = FakeConversationSessionController()
        return Harness(controller, SpeechToSpeechViewModel(controller))
    }

    private data class Harness(
        val controller: FakeConversationSessionController,
        val viewModel: SpeechToSpeechViewModel,
    )

    private fun assertNotNullLease(value: Any?) {
        assertTrue(value != null)
    }
}
