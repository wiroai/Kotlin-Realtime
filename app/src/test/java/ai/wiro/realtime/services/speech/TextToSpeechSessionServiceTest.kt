package ai.wiro.realtime.services.speech

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.RealtimeAudioInterruption
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeEvent
import ai.wiro.realtime.realtime.RealtimeEventType
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.speech.support.SpeechTestDependencies
import ai.wiro.realtime.ui.speech.SpeechCopy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextToSpeechSessionServiceTest {
    @Test
    fun requiresNonEmptyTrimmedText() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "   "

        service.start()

        assertEquals(0, dependencies.apiClient.runCalls)
        assertTrue(dependencies.audioSession.preparedProfiles.isEmpty())
        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertEquals(SpeechCopy.ENTER_TEXT, service.snapshot.errorMessage)
        service.dispose()
    }

    @Test
    fun runsMossWithTextAndPreparesPlayback() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "  Hello realtime speech.  "

        service.start()

        assertEquals(
            listOf(RealtimeAudioProfile.Speech),
            dependencies.audioSession.preparedProfiles,
        )
        assertEquals(
            "Hello realtime speech.",
            dependencies.apiClient.lastParameters?.get("text"),
        )
        assertEquals(1, dependencies.session.connectCalls)
        assertEquals(1, dependencies.player.startCalls)
        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun selectsInactiveTtsModelsAndChangesThePromptField() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)

        service.selectModel(WiroModelCatalog.voxcpm2)
        service.prompt = "Vox prompt"
        service.start()

        assertEquals(WiroModelCatalog.voxcpm2, service.snapshot.selectedModel)
        assertEquals(
            "Vox prompt",
            dependencies.apiClient.lastParameters?.get("prompt"),
        )
        assertEquals(
            2.0,
            dependencies.apiClient.lastParameters?.get("cfgValue"),
        )
        assertEquals(
            10,
            dependencies.apiClient.lastParameters?.get("inferenceSteps"),
        )
        service.dispose()
    }

    @Test
    fun reselectingTheModelResetsItsParameterValues() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Discard me"

        service.selectModel(WiroModelCatalog.mossTtsRealtime)

        assertTrue(service.prompt.isEmpty())
        service.dispose()
    }

    @Test
    fun rejectsModelChangesWhileActiveOrInAnotherDirection() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)

        service.selectModel(WiroModelCatalog.voxtralMiniRealtime)
        assertEquals(
            ai.wiro.realtime.models.RealtimeDirection.TextToSpeech,
            service.snapshot.selectedModel.direction,
        )
        service.prompt = "Hello"
        service.start()
        service.selectModel(WiroModelCatalog.voxcpm2)

        assertEquals(
            WiroModelCatalog.mossTtsRealtime.modelSlug,
            service.snapshot.selectedModel.modelSlug,
        )
        service.dispose()
    }

    @Test
    fun resubscribesToATaskTheSocketDroppedWhileSpinningUp() = runTest {
        val dependencies = SpeechTestDependencies(sessionConnectFailures = 2)
        val service = dependencies.createService(this)
        service.prompt = "Hello"

        service.start()

        assertEquals(3, dependencies.session.connectCalls)
        assertTrue(dependencies.apiClient.terminatedTaskIds.isEmpty())
        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        assertTrue(
            service.snapshot.protocolEvents.contains("socket_reconnect:1"),
        )
        service.dispose()
    }

    @Test
    fun aTaskThatNeverStreamsFailsOnceTheRetryBudgetIsSpent() = runTest {
        val dependencies = SpeechTestDependencies(sessionConnectFailures = 99)
        val service = dependencies.createService(this)
        service.prompt = "Hello"

        service.start()

        assertEquals(8, dependencies.session.connectCalls)
        assertEquals(
            RealtimeSessionState.Failure,
            service.snapshot.sessionState,
        )
        assertEquals(
            listOf("task-id"),
            dependencies.apiClient.terminatedTaskIds,
        )
        service.dispose()
    }

    @Test
    fun queuesPcmInFifoAndDrainsBeforeNaturalCompletion() = runTest {
        val dependencies = SpeechTestDependencies(holdComplete = true)
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1, 2)))
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(3, 4)))
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.PostprocessEnded),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Stopping
        }

        assertEquals(2, dependencies.player.fedChunks.size)
        assertArrayEquals(byteArrayOf(1, 2), dependencies.player.fedChunks[0])
        assertArrayEquals(byteArrayOf(3, 4), dependencies.player.fedChunks[1])
        assertTrue(service.snapshot.isPlaying)
        assertEquals(
            RealtimeSessionState.Stopping,
            service.snapshot.sessionState,
        )

        dependencies.player.releaseComplete()
        testScheduler.advanceUntilIdle()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }
        assertFalse(service.snapshot.isPlaying)
        assertEquals(1, dependencies.player.completeCalls)
        service.dispose()
    }

    @Test
    fun earlyStopFlushesPlaybackAndEndsOnce() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1, 2)))

        val first = async { service.stop() }
        val second = async { service.stop() }
        first.await()
        second.await()

        assertEquals(1, dependencies.player.stopCalls)
        assertEquals(1, dependencies.session.sessionEndCalls)
        assertEquals(1, dependencies.session.closeCalls)
        assertEquals(
            RealtimeSessionState.Completed,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun mapsPlaybackFailuresToASanitizedFailure() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.player.emitFailure()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Failure
        }

        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertFalse(service.snapshot.errorMessage?.contains("platform") ?: true)
        service.dispose()
    }

    @Test
    fun rejectsMalformedRealtimeAudioFrames() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.session.emit(RealtimeEvent.Malformed)
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Failure
        }

        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertEquals(
            RealtimeSessionState.Failure,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun cancelsPendingRunAndTerminatesAStaleResult() = runTest {
        val dependencies = SpeechTestDependencies()
        dependencies.apiClient.holdRunModel = true
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        val starting = async { service.start() }
        dependencies.apiClient.waitUntilHolding()

        val stopping = async { service.stop() }
        waitUntil {
            dependencies.apiClient.lastCancellation?.isCancelled == true
        }
        dependencies.apiClient.completeRunModel()
        starting.await()
        stopping.await()

        assertEquals(listOf("task-id"), dependencies.apiClient.terminatedTaskIds)
        assertEquals(0, dependencies.session.connectCalls)
        service.dispose()
    }

    @Test
    fun aSlowShutdownConfirmationCompletesWithoutAnError() = runTest {
        val dependencies = SpeechTestDependencies(completeSessionEnd = false)
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        service.stop()

        assertEquals(ViewStatus.Success, service.snapshot.status)
        assertEquals(
            RealtimeSessionState.Completed,
            service.snapshot.sessionState,
        )
        assertNull(service.snapshot.errorMessage)
        assertTrue(
            service.snapshot.protocolEvents.contains("shutdown_timeout"),
        )
        service.dispose()
    }

    @Test
    fun stopsAnActiveSessionOnInterruption() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.audioSession.emitInterruption(
            RealtimeAudioInterruption.Began,
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.session.sessionEndCalls)
        service.dispose()
    }

    @Test
    fun keepsProtocolHistoryBounded() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        repeat(50) { index ->
            dependencies.session.emit(
                RealtimeEvent.Unknown("future_event_$index"),
            )
        }

        assertEquals(40, service.snapshot.protocolEvents.size)
        service.dispose()
    }

    @Test
    fun commandsAfterDisposeAreSafeNoOps() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.dispose()
        val snapshot = service.snapshot

        service.selectModel(WiroModelCatalog.voxcpm2)
        service.start()
        service.stop()

        assertEquals(0, dependencies.apiClient.runCalls)
        assertEquals(snapshot, service.snapshot)
    }

    @Test
    fun startAfterDisposeDoesNotStrandTheSharedLease() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.dispose()

        service.start()

        assertNotNull(
            dependencies.sessionGuard.acquire(
                RealtimeSessionOwner.Conversation,
            ),
        )
    }

    @Test
    fun streamAndPostprocessRacingStopRemainIdempotent() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.PostprocessEnded),
        )
        testScheduler.advanceUntilIdle()
        service.stop()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.session.closeCalls)
        assertEquals(1, dependencies.player.completeCalls)
        assertEquals(
            RealtimeSessionState.Completed,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun rapidStartsCreateOnlyOneRun() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"

        val first = async { service.start() }
        val second = async { service.start() }
        first.await()
        second.await()

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun disposeClosesTheSocketAndPlayer() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        service.dispose()

        assertTrue(dependencies.session.closeCalls >= 1)
        assertEquals(1, dependencies.player.disposeCalls)
    }

    @Test
    fun aHeadphoneUnplugStopsWithoutASecondRun() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.audioSession.emitBecomingNoisy()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        service.dispose()
    }

    @Test
    fun aRouteChangeDoesNotCreateASecondRun() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        service.start()

        dependencies.audioSession.emitRouteChange()
        waitUntil {
            service.snapshot.protocolEvents.contains("audio_route_changed")
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun aStartDuringAnotherFeatureTeardownWaitsItOut() = runTest {
        val dependencies = SpeechTestDependencies()
        val service = dependencies.createService(this)
        service.prompt = "Hello"
        val departing = dependencies.sessionGuard.acquire(
            RealtimeSessionOwner.Conversation,
        )

        val starting = async { service.start() }
        waitUntil { service.snapshot.status == ViewStatus.Loading }
        dependencies.sessionGuard.release(departing)
        starting.await()

        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        assertNull(service.snapshot.errorMessage)
        service.dispose()
    }
}
