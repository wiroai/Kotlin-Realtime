package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.speech.SpeechSessionSnapshot
import ai.wiro.realtime.services.speech.support.FakeSpeechSessionController
import ai.wiro.realtime.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TextToSpeechViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun playStaysDisabledUntilThereIsText() {
        val viewModel = TextToSpeechViewModel(FakeSpeechSessionController())
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.setPrompt("Hello")
        assertTrue(viewModel.uiState.value.canStart)
    }

    @Test
    fun selectingVoxcpm2ResetsThePrompt() {
        val controller = FakeSpeechSessionController()
        val viewModel = TextToSpeechViewModel(controller)
        viewModel.setPrompt("Hello")
        viewModel.selectModel(WiroModelCatalog.voxcpm2)

        val state = viewModel.uiState.value
        assertTrue(state.prompt.isEmpty())
        assertFalse(state.canStart)
        assertEquals(
            WiroModelCatalog.voxcpm2.modelSlug,
            state.selectedModel.modelSlug,
        )
    }

    @Test
    fun appliesTheCurrentSnapshotInTheConstructor() = runTest {
        val controller = FakeSpeechSessionController()
        controller.snapshot = SpeechSessionSnapshot(
            selectedModel = WiroModelCatalog.voxcpm2,
            sessionState = RealtimeSessionState.Streaming,
            isPlaying = true,
        )
        val viewModel = TextToSpeechViewModel(controller)

        assertEquals(
            WiroModelCatalog.voxcpm2,
            viewModel.uiState.value.selectedModel,
        )
        assertEquals(
            RealtimeSessionState.Streaming,
            viewModel.uiState.value.sessionState,
        )
        assertTrue(viewModel.uiState.value.isPlaying)
        viewModel.dispose()
    }

    @Test
    fun projectsEveryDrawnSnapshotField() = runTest {
        val harness = makeHarness()

        harness.controller.publish(
            SpeechSessionSnapshot(
                selectedModel = WiroModelCatalog.voxcpm2,
                sessionState = RealtimeSessionState.Streaming,
                elapsed = 4.seconds,
                isPlaying = true,
                errorMessage = "error",
            ),
        )
        waitUntil {
            harness.viewModel.uiState.value.sessionState ==
                RealtimeSessionState.Streaming
        }

        val state = harness.viewModel.uiState.value
        assertEquals(WiroModelCatalog.voxcpm2, state.selectedModel)
        assertEquals(RealtimeSessionState.Streaming, state.sessionState)
        assertTrue(state.isPlaying)
        assertEquals("error", state.errorMessage)
        harness.viewModel.dispose()
    }

    @Test
    fun delegatesCommandsAndServiceGetters() = runTest {
        val harness = makeHarness()

        harness.viewModel.selectModel(WiroModelCatalog.voxcpm2)
        harness.viewModel.start()
        harness.viewModel.stop()
        harness.viewModel.setParameter("cfgValue", "3.0")

        assertEquals(1, harness.controller.selectCalls)
        assertEquals(1, harness.controller.startCalls)
        assertEquals(1, harness.controller.stopCalls)
        assertEquals(
            listOf("cfgValue" to "3.0"),
            harness.controller.setParameterCalls,
        )
        assertEquals(
            harness.controller.parameters,
            harness.viewModel.parameters,
        )
        assertTrue(
            harness.viewModel.sessionGuard ===
                harness.controller.sessionGuard,
        )
        harness.viewModel.dispose()
    }

    @Test
    fun promptReadsAndWritesThroughTheSession() = runTest {
        val harness = makeHarness()

        harness.viewModel.setPrompt("Speak this")

        assertEquals("Speak this", harness.controller.prompt)
        assertEquals("Speak this", harness.viewModel.prompt)
        harness.viewModel.dispose()
    }

    @Test
    fun anotherFeatureHoldingTheSessionBlocksStart() = runTest {
        val harness = makeHarness()
        harness.viewModel.setPrompt("Hello")
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
        val controller = FakeSpeechSessionController()
        val viewModel = TextToSpeechViewModel(controller)
        viewModel.dispose()

        controller.publish(
            SpeechSessionSnapshot(
                selectedModel = WiroModelCatalog.mossTtsRealtime,
                isPlaying = true,
            ),
        )

        assertEquals(1, controller.disposeCalls)
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    private fun makeHarness(): Harness {
        val controller = FakeSpeechSessionController()
        return Harness(
            controller = controller,
            viewModel = TextToSpeechViewModel(controller),
        )
    }

    private class Harness(
        val controller: FakeSpeechSessionController,
        val viewModel: TextToSpeechViewModel,
    )
}
