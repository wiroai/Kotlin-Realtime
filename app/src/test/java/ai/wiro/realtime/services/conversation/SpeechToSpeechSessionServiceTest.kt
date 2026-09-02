package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.RealtimeAudioInterruption
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.networking.WiroFailure
import ai.wiro.realtime.realtime.RealtimeEvent
import ai.wiro.realtime.realtime.RealtimeEventType
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.support.waitUntil
import ai.wiro.realtime.services.conversation.support.ConversationTestDependencies
import ai.wiro.realtime.services.conversation.support.conversationPcm
import ai.wiro.realtime.services.transcription.SpeechToTextSessionService
import ai.wiro.realtime.services.transcription.support.TranscriptionTestDependencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechToSpeechSessionServiceTest {
    @Test
    fun startsGptRealtimeWithVerifiedPcmParameters() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)

        service.start()

        assertEquals(
            listOf(RealtimeAudioProfile.Conversation),
            dependencies.audioSession.preparedProfiles,
        )
        assertEquals("audio/pcm", stringParam(dependencies, "input_audio_format"))
        assertEquals(
            "audio/pcm",
            stringParam(dependencies, "output_audio_format"),
        )
        assertEquals("24000", stringParam(dependencies, "input_audio_rate"))
        assertEquals("24000", stringParam(dependencies, "output_audio_rate"))
        assertEquals(1, dependencies.recorder.startCalls)
        assertEquals(1, dependencies.player.startCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        assertTrue(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun sendsTheConfiguredPersonaAndVoice() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)

        service.start()

        assertEquals(
            ConversationConfig.PERSONA,
            stringParam(dependencies, "system_instructions"),
        )
        assertEquals(ConversationConfig.VOICE, stringParam(dependencies, "voice"))
        service.dispose()
    }

    @Test
    fun aConversationLeftOpenEndsItselfAtTheCeiling() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            maxSessionDuration = 1.seconds,
        )
        service.start()

        kotlinx.coroutines.delay(1_400.milliseconds)

        assertTrue(
            service.snapshot.protocolEvents.contains("session_limit_reached"),
        )
        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertEquals(1, dependencies.apiClient.runCalls)
        service.dispose()
    }

    @Test
    fun aZeroCeilingLetsTheConversationRunOn() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            maxSessionDuration = Duration.ZERO,
        )
        service.start()

        kotlinx.coroutines.delay(1_200.milliseconds)

        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun streamsMicrophoneAndAssistantPcmConcurrently() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.recorder.emit(byteArrayOf(1, 2))
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(3, 4)))
        waitUntil { dependencies.session.sentAudio.size == 1 }

        assertEquals(1, dependencies.session.sentAudio.size)
        assertArrayEquals(byteArrayOf(1, 2), dependencies.session.sentAudio[0])
        assertEquals(1, dependencies.player.fedChunks.size)
        assertArrayEquals(byteArrayOf(3, 4), dependencies.player.fedChunks[0])
        service.dispose()
    }

    @Test
    fun keepsMicrophoneStreamingWhileTheAiIsSpeaking() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(3, 4)))
        dependencies.recorder.emit(byteArrayOf(5))
        waitUntil { dependencies.session.sentAudio.size == 1 }

        assertArrayEquals(byteArrayOf(5), dependencies.session.sentAudio[0])
        assertTrue(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun aUserTranscriptAloneNeverCutsAssistantAudio() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1)))
        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_USER:Stop"))
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(2)))

        assertEquals(0, dependencies.player.flushCalls)
        assertEquals(0, dependencies.player.stopCalls)
        assertEquals(2, dependencies.player.fedChunks.size)
        assertEquals("Stop", service.snapshot.userTranscript)
        service.dispose()
    }

    @Test
    fun aNewAssistantTurnDropsTheInterruptedTurnTail() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1)))
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(2)))
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(3)))

        assertEquals(1, dependencies.player.flushCalls)
        assertEquals(2, dependencies.player.fedChunks.size)
        assertArrayEquals(byteArrayOf(2), dependencies.player.fedChunks[0])
        assertArrayEquals(byteArrayOf(3), dependencies.player.fedChunks[1])
        assertTrue(
            service.snapshot.protocolEvents.contains("previous_turn_dropped"),
        )
        service.dispose()
    }

    @Test
    fun interruptAssistantCutsTheTurnAndKeepsTheNext() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(ByteArray(48_000)))
        service.interruptAssistant()

        assertEquals(1, dependencies.player.flushCalls)
        assertTrue(
            service.snapshot.protocolEvents
                .contains("assistant_silenced_by_user"),
        )

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(9)))
        assertTrue(dependencies.player.fedChunks.isEmpty())

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(7)))
        assertEquals(1, dependencies.player.fedChunks.size)
        assertArrayEquals(byteArrayOf(7), dependencies.player.fedChunks[0])
        service.dispose()
    }

    @Test
    fun interruptAssistantWithNothingPlayingNeverLatches() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        service.interruptAssistant()
        assertEquals(0, dependencies.player.flushCalls)

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1)))
        assertEquals(1, dependencies.player.fedChunks.size)
        service.dispose()
    }

    @Test
    fun aNoiseBurstPausesTheAnswerAndHandsItBack() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(ByteArray(48_000)))
        repeat(3) {
            dependencies.recorder.emit(conversationPcm(20_000))
        }
        waitUntil {
            service.snapshot.protocolEvents.contains("assistant_playback_held")
        }

        assertEquals(1, dependencies.player.flushCalls)
        assertFalse(
            service.snapshot.protocolEvents.contains("assistant_interrupted"),
        )

        repeat(3) {
            dependencies.recorder.emit(conversationPcm(0))
        }
        waitUntil {
            service.snapshot.protocolEvents
                .contains("assistant_playback_resumed")
        }

        assertEquals(1, dependencies.player.fedChunks.size)
        service.dispose()
    }

    @Test
    fun sustainedSpeechOverTheAssistantCommitsTheCut() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Binary(ByteArray(48_000)))
        repeat(8) {
            dependencies.recorder.emit(conversationPcm(20_000))
        }
        waitUntil {
            service.snapshot.protocolEvents.contains("assistant_interrupted")
        }

        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(9)))
        assertTrue(dependencies.player.fedChunks.isEmpty())
        service.dispose()
    }

    @Test
    fun assignsProgressiveTranscriptsToTheCorrectRole() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_USER:Hello"))
        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_AI:Hi there"))
        dependencies.session.emit(
            RealtimeEvent.Output("TRANSCRIPT_USER:Hello world"),
        )

        assertEquals("Hello world", service.snapshot.userTranscript)
        assertEquals("Hi there", service.snapshot.aiTranscript)
        service.dispose()
    }

    @Test
    fun muteDropsMicrophoneAudioWithoutClosingTheSession() = runTest {
        val dependencies = ConversationTestDependencies()
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
    fun stopReleasesAllMediaAndSendsOneSessionEnd() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        val first = async { service.stop() }
        val second = async { service.stop() }
        first.await()
        second.await()

        assertEquals(1, dependencies.recorder.stopCalls)
        assertEquals(1, dependencies.player.stopCalls)
        assertEquals(1, dependencies.session.sessionEndCalls)
        assertEquals(1, dependencies.session.closeCalls)
        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertFalse(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun aiTurnEndResumesListeningWithoutClosing() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        dependencies.session.emit(RealtimeEvent.Binary(byteArrayOf(1, 2)))

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.StreamEnded),
        )
        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.Ended),
        )

        assertEquals(0, dependencies.player.completeCalls)
        assertEquals(1, dependencies.player.startCalls)
        assertEquals(1, dependencies.player.fedChunks.size)
        assertEquals(0, dependencies.recorder.stopCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        assertTrue(service.snapshot.microphoneActive)
        service.dispose()
    }

    @Test
    fun postprocessEndDrainsMediaAndCompletesTheSession() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.PostprocessEnded),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.player.completeCalls)
        assertEquals(1, dependencies.recorder.stopCalls)
        service.dispose()
    }

    @Test
    fun aRemoteTaskEndCostsNothingMore() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            autoRestartDelay = 50.milliseconds,
        )
        service.start()

        dependencies.session.emit(
            RealtimeEvent.Status(RealtimeEventType.PostprocessEnded),
        )
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertTrue(
            service.snapshot.protocolEvents
                .contains("conversation_ended_remotely"),
        )

        kotlinx.coroutines.delay(200.milliseconds)

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun aUserStopNeverRestartsByItself() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            autoRestartDelay = 20.milliseconds,
        )
        service.start()

        service.stop()
        kotlinx.coroutines.delay(100.milliseconds)

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertFalse(
            service.snapshot.protocolEvents
                .contains("conversation_ended_remotely"),
        )
        service.dispose()
    }

    @Test
    fun permissionDenialDoesNotCreateATask() = runTest {
        val dependencies = ConversationTestDependencies(
            preparationResult =
            ai.wiro.realtime.audio.AudioPreparationResult.PermissionDenied,
        )
        val service = dependencies.createService(this)

        service.start()

        assertEquals(0, dependencies.apiClient.runCalls)
        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        assertNotNull(service.snapshot.errorMessage)
        assertFalse(service.snapshot.canOpenSystemSettings)
        service.dispose()
    }

    @Test
    fun aPermanentlyDeniedMicrophoneOffersSettings() = runTest {
        val dependencies = ConversationTestDependencies(
            preparationResult = ai.wiro.realtime.audio.AudioPreparationResult
                .PermissionPermanentlyDenied,
        )
        val service = dependencies.createService(this)

        service.start()

        assertTrue(service.snapshot.canOpenSystemSettings)

        service.openSystemSettings()

        assertEquals(1, dependencies.audioSession.openSystemSettingsCalls)
        service.dispose()
    }

    @Test
    fun aTransportFailureIsNotASettingsProblem() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Failure
        }

        assertNotNull(service.snapshot.errorMessage)
        assertFalse(service.snapshot.canOpenSystemSettings)
        service.dispose()
    }

    @Test
    fun retriesATransientStartFailureBeforePublishingFailure() = runTest {
        val dependencies = ConversationTestDependencies(
            runErrors = listOf(WiroFailure.NetworkUnavailable),
        )
        val service = dependencies.createService(this, maxRetryAttempts = 2)
        val publishedStates = mutableListOf<RealtimeSessionState>()
        val collector = launch {
            service.snapshots.collect { publishedStates += it.sessionState }
        }

        service.start()

        assertEquals(2, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        assertFalse(publishedStates.contains(RealtimeSessionState.Failure))
        assertTrue(service.snapshot.protocolEvents.contains("retry_started:1"))
        collector.cancel()
        service.dispose()
    }

    @Test
    fun publishesFailureOnlyAfterRetriesAreExhausted() = runTest {
        val dependencies = ConversationTestDependencies(
            runErrors = listOf(
                WiroFailure.NetworkUnavailable,
                WiroFailure.NetworkUnavailable,
                WiroFailure.NetworkUnavailable,
            ),
        )
        val service = dependencies.createService(this, maxRetryAttempts = 2)

        service.start()

        assertEquals(3, dependencies.apiClient.runCalls)
        assertEquals(ViewStatus.Error, service.snapshot.status)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun stopsAnActiveSessionWhenAudioIsInterrupted() = runTest {
        val dependencies = ConversationTestDependencies()
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
    fun aMidStreamSocketFailureReturnsToTheSameTask() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this, maxRetryAttempts = 1)
        service.start()
        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_USER:Hello"))

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.protocolEvents.contains("reconnect_accepted")
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(0, dependencies.apiClient.terminateCalls)
        assertEquals(listOf("task-token", "task-token"), dependencies.taskTokens)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        assertEquals("Hello", service.snapshot.userTranscript)
        service.dispose()
    }

    @Test
    fun leavingSilencesTheMicrophoneAndFreesTheHardware() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            suspendGracePeriod = 5.seconds,
        )
        service.start()

        service.suspend()

        assertTrue(service.snapshot.isSuspended)
        assertFalse(service.snapshot.microphoneActive)
        assertFalse(dependencies.recorder.isRecording)
        assertEquals(0, dependencies.session.sessionEndCalls)
        assertEquals(0, dependencies.apiClient.terminateCalls)
        assertNotNull(
            dependencies.sessionGuard.acquire(RealtimeSessionOwner.Transcription),
        )
        service.dispose()
    }

    @Test
    fun comingBackContinuesTheSameTask() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            suspendGracePeriod = 5.seconds,
        )
        service.start()
        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_USER:Hello"))
        service.suspend()

        service.resume()

        assertFalse(service.snapshot.isSuspended)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        assertEquals("Hello", service.snapshot.userTranscript)
        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(listOf("task-token", "task-token"), dependencies.taskTokens)
        service.dispose()
    }

    @Test
    fun aSpeakerWhoNeverComesBackIsHungUpOn() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            suspendGracePeriod = 40.milliseconds,
        )
        service.start()
        service.suspend()

        kotlinx.coroutines.delay(120.milliseconds)

        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertFalse(service.snapshot.isSuspended)
        assertTrue(service.snapshot.protocolEvents.contains("hold_expired"))
        assertEquals(1, dependencies.apiClient.terminateCalls)
        service.dispose()
    }

    @Test
    fun aLapsedHoldIsNotResumedEvenIfTheTimerNeverRan() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            suspendGracePeriod = 20.milliseconds,
        )
        service.start()
        service.suspend()
        kotlinx.coroutines.delay(60.milliseconds)

        service.resume()

        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertEquals(1, dependencies.apiClient.runCalls)
        service.dispose()
    }

    @Test
    fun theConfiguredDefaultEndsTheConversationOutright() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        service.suspend()

        assertFalse(service.snapshot.isSuspended)
        assertEquals(RealtimeSessionState.Completed, service.snapshot.sessionState)
        assertEquals(1, dependencies.session.sessionEndCalls)
        service.dispose()
    }

    @Test
    fun anIdleConversationHasNothingToHold() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)

        service.suspend()
        service.resume()

        assertEquals(RealtimeSessionState.Idle, service.snapshot.sessionState)
        assertEquals(0, dependencies.apiClient.runCalls)
        service.dispose()
    }

    @Test
    fun aTaskThatRefusesTheReconnectIsReplaced() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this, maxRetryAttempts = 1)
        service.start()
        dependencies.session.connectError = WiroFailure.SocketConnection

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Streaming &&
                dependencies.apiClient.runCalls == 2
        }

        assertTrue(
            service.snapshot.protocolEvents.contains("reconnect_refused"),
        )
        assertEquals(1, dependencies.apiClient.terminateCalls)
        service.dispose()
    }

    @Test
    fun aDeadMicrophoneReconnectsThenFailsOnceRetriesRunOut() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            maxRetryAttempts = 1,
            micStallTimeout = 60.milliseconds,
        )

        service.start()
        kotlinx.coroutines.delay(500.milliseconds)

        assertTrue(
            service.snapshot.protocolEvents.contains("microphone_stalled"),
        )
        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun aRecoverableFailureRestartsTheConversationByItself() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            autoRestartDelay = 50.milliseconds,
        )
        service.start()

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.protocolEvents.contains("auto_restart_scheduled")
        }

        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)

        kotlinx.coroutines.delay(200.milliseconds)

        assertEquals(2, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun leavingTheScreenCancelsAScheduledRestart() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            autoRestartDelay = 50.milliseconds,
        )
        service.start()

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.protocolEvents.contains("auto_restart_scheduled")
        }

        service.suspend()
        kotlinx.coroutines.delay(200.milliseconds)

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun aBackendThatKeepsRefusingHandsTheSessionBack() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            autoRestartDelay = 20.milliseconds,
            maxAutoRestarts = 1,
        )
        service.start()

        repeat(4) {
            dependencies.session.emitError(WiroFailure.SocketConnection)
            waitUntil {
                service.snapshot.sessionState == RealtimeSessionState.Failure ||
                    service.snapshot.sessionState ==
                    RealtimeSessionState.Streaming
            }
            kotlinx.coroutines.delay(120.milliseconds)
        }

        assertEquals(2, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun aDeniedPermissionNeverRestartsByItself() = runTest {
        val dependencies = ConversationTestDependencies(
            preparationResult =
            ai.wiro.realtime.audio.AudioPreparationResult.PermissionDenied,
        )
        val service = dependencies.createService(
            this,
            autoRestartDelay = 20.milliseconds,
        )

        service.start()
        kotlinx.coroutines.delay(100.milliseconds)

        assertEquals(0, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Failure, service.snapshot.sessionState)
        assertFalse(
            service.snapshot.protocolEvents.contains("auto_restart_scheduled"),
        )
        service.dispose()
    }

    @Test
    fun aFailureReportsWhatHappenedInOneLine() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emitError(WiroFailure.SocketConnection)
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Failure
        }

        assertNotNull(service.snapshot.errorMessage)
        service.dispose()
    }

    @Test
    fun aTaskErrorHandsTheConversationBack() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.session.emit(RealtimeEvent.Error("task_error"))
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertFalse(service.snapshot.microphoneActive)
        assertFalse(dependencies.recorder.isRecording)
        assertEquals(1, dependencies.apiClient.runCalls)
        assertTrue(service.snapshot.protocolEvents.contains("task_error"))
        service.dispose()
    }

    @Test
    fun aCommandAfterDisposeIsIgnored() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        service.dispose()

        service.start()
        service.stop()
        service.setMuted(true)

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(1, dependencies.recorder.startCalls)
    }

    @Test
    fun aStartAfterDisposeDoesNotStrandTheSharedLease() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.dispose()

        service.start()

        assertNotNull(
            dependencies.sessionGuard.acquire(RealtimeSessionOwner.Transcription),
        )
    }

    @Test
    fun configuredParametersReplaceTheCatalogDefaults() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(
            this,
            parameterOverrides = mapOf(
                "transcription_model" to "whisper-1",
                "turn_detection_threshold" to "0.6",
                "voice" to "cedar",
            ),
        )

        service.start()

        assertEquals(
            "whisper-1",
            stringParam(dependencies, "transcription_model"),
        )
        assertEquals(
            0.6,
            dependencies.apiClient.lastParameters
                ?.get("turn_detection_threshold"),
        )
        assertEquals(
            ConversationConfig.VOICE,
            stringParam(dependencies, "voice"),
        )
        service.dispose()
    }

    @Test
    fun rapidStartsCreateOnlyOneRun() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)

        val first = async { service.start() }
        val second = async { service.start() }
        first.await()
        second.await()

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun disposeClosesTheSocketAndAudio() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        service.dispose()

        assertTrue(dependencies.session.closeCalls >= 1)
        assertEquals(1, dependencies.recorder.disposeCalls)
        assertEquals(1, dependencies.player.disposeCalls)
    }

    @Test
    fun aHeadphoneUnplugStopsWithoutASecondRun() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.audioSession.emitBecomingNoisy()
        waitUntil {
            service.snapshot.sessionState == RealtimeSessionState.Completed
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(1, dependencies.session.sessionEndCalls)
        service.dispose()
    }

    @Test
    fun aRouteChangeDoesNotCreateASecondRun() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()

        dependencies.audioSession.emitRouteChange()
        waitUntil {
            service.snapshot.protocolEvents.contains("audio_route_changed")
        }

        assertEquals(1, dependencies.apiClient.runCalls)
        assertEquals(RealtimeSessionState.Streaming, service.snapshot.sessionState)
        service.dispose()
    }

    @Test
    fun aLiveConversationBlocksATranscriptionStart() = runTest {
        val conversation = ConversationTestDependencies()
        val transcription = TranscriptionTestDependencies()
        val conversationService = conversation.createService(
            this,
            micStallTimeout = 1.minutes,
        )
        val transcriptionService = SpeechToTextSessionService(
            audioSession = transcription.audioSession,
            apiClient = transcription.apiClient,
            sessionFactory = { _, _ -> transcription.session },
            recorder = transcription.recorder,
            sessionGuard = conversation.sessionGuard,
            terminalTimeout = Duration.ZERO,
            scope = this,
        )
        conversationService.start()

        transcriptionService.start()

        assertEquals(1, conversation.apiClient.runCalls)
        assertEquals(0, transcription.apiClient.runCalls)
        assertEquals(
            WiroFailure.SessionBusy.message,
            transcriptionService.snapshot.errorMessage,
        )
        conversationService.dispose()
        transcriptionService.dispose()
    }

    @Test
    fun protocolEventsNeverCarryTheTaskToken() = runTest {
        val dependencies = ConversationTestDependencies()
        val service = dependencies.createService(this)
        service.start()
        dependencies.session.emit(RealtimeEvent.Output("TRANSCRIPT_USER:Hi"))
        dependencies.session.emit(RealtimeEvent.Unknown("future_event"))

        assertTrue(
            service.snapshot.protocolEvents.none { event ->
                event.contains("task-token")
            },
        )
        service.dispose()
    }

    @Test
    fun keepsTheLocalProtocolHistoryBounded() = runTest {
        val dependencies = ConversationTestDependencies()
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

    private fun stringParam(
        dependencies: ConversationTestDependencies,
        key: String,
    ): String? = dependencies.apiClient.lastParameters?.get(key) as? String
}
