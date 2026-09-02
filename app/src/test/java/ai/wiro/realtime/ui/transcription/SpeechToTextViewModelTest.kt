package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.transcription.TranscriptionSessionSnapshot
import ai.wiro.realtime.services.transcription.support.FakeTranscriptionSessionController
import ai.wiro.realtime.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SpeechToTextViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun takesTheFixedModelFromTheSession() = runTest {
        val harness = makeHarness()

        assertEquals(harness.controller.model, harness.viewModel.model)
        harness.viewModel.dispose()
    }

    @Test
    fun adoptsASessionThatIsAlreadyTranscribing() = runTest {
        val controller = FakeTranscriptionSessionController()
        controller.snapshot = TranscriptionSessionSnapshot(
            sessionState = RealtimeSessionState.Streaming,
            transcript = "in progress",
        )
        val viewModel = SpeechToTextViewModel(controller)

        assertEquals(
            RealtimeSessionState.Streaming,
            viewModel.uiState.value.sessionState,
        )
        assertEquals("in progress", viewModel.uiState.value.transcript)
        viewModel.dispose()
    }

    @Test
    fun projectsEveryDrawnSnapshotFieldOntoViewState() = runTest {
        val harness = makeHarness()

        harness.controller.publish(
            TranscriptionSessionSnapshot(
                sessionState = RealtimeSessionState.Streaming,
                elapsed = 7.seconds,
                transcript = "hello",
                muted = true,
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
        assertEquals("hello", state.transcript)
        assertTrue(state.muted)
        assertEquals("boom", state.errorMessage)
        assertTrue(state.canOpenSystemSettings)
        assertTrue(state.showsSystemSettingsAlert)
        harness.viewModel.dispose()
    }

    @Test
    fun clearingAnErrorMessagePropagatesTheNull() = runTest {
        val harness = makeHarness()
        harness.controller.publish(
            TranscriptionSessionSnapshot(errorMessage = "boom"),
        )
        waitUntil {
            harness.viewModel.uiState.value.errorMessage == "boom"
        }

        harness.controller.publish(TranscriptionSessionSnapshot())
        waitUntil { harness.viewModel.uiState.value.errorMessage == null }

        assertNull(harness.viewModel.uiState.value.errorMessage)
        harness.viewModel.dispose()
    }

    @Test
    fun commandsAreDelegatedToTheSession() = runTest {
        val harness = makeHarness()

        harness.viewModel.start()
        harness.viewModel.stop()
        harness.viewModel.setMuted(true)
        harness.viewModel.clearTranscript()
        harness.viewModel.openSystemSettings()

        assertEquals(1, harness.controller.startCalls)
        assertEquals(1, harness.controller.stopCalls)
        assertEquals(listOf(true), harness.controller.muteRequests)
        assertEquals(1, harness.controller.clearCalls)
        assertEquals(1, harness.controller.openSettingsCalls)
        harness.viewModel.dispose()
    }

    @Test
    fun idleAccuracyChangeWritesTheParameter() = runTest {
        val harness = makeHarness()

        harness.viewModel.selectAccuracy("accurate")

        assertEquals(
            listOf("transcriptionDelay" to "accurate"),
            harness.controller.setParameterCalls,
        )
        assertEquals("accurate", harness.viewModel.uiState.value.accuracy)
        harness.viewModel.dispose()
    }

    @Test
    fun settingsAlertIsEdgeTriggered() = runTest {
        val harness = makeHarness()
        harness.controller.publish(
            TranscriptionSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil {
            harness.viewModel.uiState.value.showsSystemSettingsAlert
        }

        harness.viewModel.dismissSystemSettingsAlert()
        assertFalse(harness.viewModel.uiState.value.showsSystemSettingsAlert)
        assertTrue(harness.viewModel.uiState.value.canOpenSystemSettings)

        harness.controller.publish(
            TranscriptionSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil {
            harness.viewModel.uiState.value.canOpenSystemSettings
        }
        assertFalse(harness.viewModel.uiState.value.showsSystemSettingsAlert)

        harness.controller.publish(TranscriptionSessionSnapshot())
        waitUntil {
            !harness.viewModel.uiState.value.canOpenSystemSettings
        }
        harness.controller.publish(
            TranscriptionSessionSnapshot(canOpenSystemSettings = true),
        )
        waitUntil {
            harness.viewModel.uiState.value.showsSystemSettingsAlert
        }
        harness.viewModel.dispose()
    }

    @Test
    fun anotherFeatureHoldingTheMicBlocksStart() = runTest {
        val harness = makeHarness()
        val lease = harness.controller.sessionGuard.acquire(
            RealtimeSessionOwner.Conversation,
        )
        assertTrue(lease != null)
        assertFalse(harness.viewModel.canStart)

        harness.controller.sessionGuard.release(lease)
        assertTrue(harness.viewModel.canStart)
        harness.viewModel.dispose()
    }

    @Test
    fun disposeReleasesTheSessionAndStopsListening() = runTest {
        val controller = FakeTranscriptionSessionController()
        val viewModel = SpeechToTextViewModel(controller)
        viewModel.dispose()

        controller.publish(
            TranscriptionSessionSnapshot(transcript = "late"),
        )

        assertEquals(1, controller.disposeCalls)
        assertTrue(viewModel.uiState.value.transcript.isEmpty())
    }

    private fun makeHarness(): Harness {
        val controller = FakeTranscriptionSessionController()
        return Harness(
            controller = controller,
            viewModel = SpeechToTextViewModel(controller),
        )
    }

    private class Harness(
        val controller: FakeTranscriptionSessionController,
        val viewModel: SpeechToTextViewModel,
    )
}
