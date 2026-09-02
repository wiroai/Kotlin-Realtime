package ai.wiro.realtime.services.transcription

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.RealtimeAudioInterruption
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.realtime.RealtimeEvent
import ai.wiro.realtime.realtime.RealtimeEventType
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.transcription.support.TranscriptionTestDependencies
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechToTextSessionServiceTest {
    @Test
    fun runsVoxtralAndStreamsPrefixedPcmAfterReadiness() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)

        service.start()
        dependencies.recorder.emit(byteArrayOf(1, 2, 3))
        waitUntil { dependencies.session.sentAudio.size == 1 }

        assertEquals(
            listOf(RealtimeAudioProfile.Transcription),
            dependencies.audioSession.preparedProfiles,
        )
        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(
            "balanced",
            dependencies.apiClient.lastParameters?.get("transcriptionDelay"),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            dependencies.session.sentAudio[0],
        )
        assertEquals(
            RealtimeSessionState.Streaming,
            service.snapshot.sessionState,
        )
        assertTrue(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun aGrowingUtteranceRefinesTheTranscriptInPlace() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello"),
        )
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello world"),
        )

        assertEquals("Hello world", service.snapshot.transcript)
        assertEquals(
            2,
            service.snapshot.protocolEvents.count { it == "task_output" },
        )
        service.dispose()
    }

    @Test
    fun aNewUtteranceJoinsTheTranscriptInsteadOfErasingIt() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello world."),
        )
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:How are"),
        )
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:How are you?"),
        )
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Good."),
        )

        assertEquals(
            "Hello world. How are you? Good.",
            service.snapshot.transcript,
        )
        service.dispose()
    }

    @Test
    fun aShorterReEmitOfTheLiveUtteranceChangesNothing() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello world"),
        )
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello"),
        )

        assertEquals("Hello world", service.snapshot.transcript)
        service.dispose()
    }

    @Test
    fun aNewTakeStartsFromABlankTranscript() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Old take"),
        )
        service.stop()

        service.start()
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:New take"),
        )

        assertEquals("New take", service.snapshot.transcript)
        service.dispose()
    }

    @Test
    fun stopReleasesMicrophoneAndSendsOneSessionEnd() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        val first = async { service.stop() }
        val second = async { service.stop() }
        first.await()
        second.await()

        assertEquals(1, dependencies.recorder.stopCalls)
        assertEquals(1, dependencies.audioSession.deactivateCalls)
        assertEquals(1, dependencies.session.sessionEndCalls)
        assertEquals(1, dependencies.session.closeCalls)
        assertEquals(
            RealtimeSessionState.Completed,
            service.snapshot.sessionState,
        )
        assertFalse(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun aSlowShutdownConfirmationCompletesWithoutAnError() = runTest {
        val dependencies = TranscriptionTestDependencies(
            completeSessionEnd = false,
        )
        val service = dependencies.createService(this)
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
    fun mapsDeniedMicrophonePermissionWithoutRunningATask() = runTest {
        val dependencies = TranscriptionTestDependencies(
            preparationResult = AudioPreparationResult.PermissionDenied,
        )
        val service = dependencies.createService(this)

        service.start()

        assertEquals(0, dependencies.apiClient.runCalls)
        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertEquals(
            RealtimeSessionState.Failure,
            service.snapshot.sessionState,
        )
        assertNotNull(service.snapshot.errorMessage)
        assertFalse(service.snapshot.canOpenSystemSettings)
        service.dispose()
    }

    @Test
    fun aPermanentlyDeniedMicrophoneOffersSettings() = runTest {
        val dependencies = TranscriptionTestDependencies(
            preparationResult =
            AudioPreparationResult.PermissionPermanentlyDenied,
        )
        val service = dependencies.createService(this)

        service.start()

        assertTrue(service.snapshot.canOpenSystemSettings)

        service.openSystemSettings()

        assertEquals(1, dependencies.audioSession.openSystemSettingsCalls)
        service.dispose()
    }

    @Test
    fun cancelsAPendingRunRequestDuringStop() = runTest {
        val dependencies = TranscriptionTestDependencies()
        dependencies.apiClient.holdRunModel = true
        val service = dependencies.createService(this)
        val starting = async { service.start() }
        dependencies.apiClient.waitUntilHolding()

        val stopping = async { service.stop() }
        waitUntil {
            dependencies.apiClient.lastCancellation?.isCancelled == true
        }
        dependencies.apiClient.completeRunModel()
        starting.await()
        stopping.await()

        assertEquals(
            true,
            dependencies.apiClient.lastCancellation?.isCancelled,
        )
        assertEquals(0, dependencies.session.connectCalls)
        assertEquals(
            RealtimeSessionState.Completed,
            service.snapshot.sessionState,
        )
        service.dispose()
    }

    @Test
    fun terminatesARunResultThatArrivesAfterStop() = runTest {
        val dependencies = TranscriptionTestDependencies()
        dependencies.apiClient.holdRunModel = true
        val service = dependencies.createService(this)
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
    fun streamEndStopsCaptureBeforeNaturalCompletion() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Stopping
        }

        assertFalse(service.snapshot.microphoneActive)
        assertEquals(1, dependencies.recorder.stopCalls)
        assertEquals(0, dependencies.session.closeCalls)
        service.dispose()
    }

    @Test
    fun completesNaturallyAfterPostprocessEnd() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.PostprocessEnded),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertFalse(service.snapshot.microphoneActive)
        assertEquals(1, dependencies.recorder.stopCalls)
        service.dispose()
    }

    @Test
    fun rejectsUnexpectedServerAudioForStt() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Binary(byteArrayOf(1, 2)),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Failure
        }

        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertFalse(service.snapshot.errorMessage?.contains("1, 2") ?: true)
        assertTrue(
            service.snapshot.protocolEvents.contains(
                "failure:realtimeProtocol",
            ),
        )
        service.dispose()
    }

    @Test
    fun clearTranscriptIsInactiveOnlyBusinessBehavior() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Keep me"),
        )

        service.clearTranscript()
        assertEquals("Keep me", service.snapshot.transcript)

        service.stop()
        service.clearTranscript()
        assertTrue(service.snapshot.transcript.isEmpty())
        service.dispose()
    }

    @Test
    fun muteDropsMicrophoneAudioWithoutClosingTheSession() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        service.setMuted(true)
        dependencies.recorder.emit(byteArrayOf(1))
        service.setMuted(false)
        dependencies.recorder.emit(byteArrayOf(2))
        waitUntil { dependencies.session.sentAudio.size == 1 }

        assertArrayEquals(byteArrayOf(2), dependencies.session.sentAudio[0])
        assertEquals(0, dependencies.session.closeCalls)
        assertTrue(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun stopsAnActiveSessionWhenAudioIsInterrupted() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
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
    fun commandsAfterDisposeAreSafeNoOps() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        service.dispose()
        val snapshot = service.snapshot

        service.start()
        service.stop()
        service.clearTranscript()

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(1, dependencies.recorder.startCalls)
        assertEquals(snapshot, service.snapshot)
    }

    @Test
    fun aStartAfterDisposeDoesNotStrandTheSharedLease() = runTest {
        val dependencies = TranscriptionTestDependencies()
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
    fun aStartDuringAnotherFeatureTeardownWaitsItOut() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
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

    @Test
    fun rapidStartsCreateOnlyOneRun() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)

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
    fun disposeClosesTheSocketAndMicrophone() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        service.dispose()

        assertTrue(dependencies.session.closeCalls >= 1)
        assertEquals(1, dependencies.recorder.disposeCalls)
    }

    @Test
    fun aHeadphoneUnplugStopsWithoutASecondRun() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.audioSession.emitBecomingNoisy()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        service.dispose()
    }

    @Test
    fun keepsTheLocalProtocolHistoryBounded() = runTest {
        val dependencies = TranscriptionTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        repeat(50) { index ->
            dependencies.session.emit(
                RealtimeEvent.Unknown("future_event_$index"),
            )
        }

        assertEquals(40, service.snapshot.protocolEvents.size)
        service.dispose()
    }
}
