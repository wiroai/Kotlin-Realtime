package ai.wiro.realtime.services.transcription.support

import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.support.FakePcmAudioRecorder
import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.support.FakeWiroApiClient
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.transcription.SpeechToTextSessionService
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

class TranscriptionTestDependencies(
    preparationResult: AudioPreparationResult =
        AudioPreparationResult.Ready,
    completeSessionEnd: Boolean = true,
) {
    val audioSession = FakeRealtimeAudioSession(preparationResult)
    val apiClient = FakeWiroApiClient().also {
        it.nextRunResult = WiroRunResult(
            taskId = "task-id",
            taskToken = "task-token",
        )
    }
    val recorder = FakePcmAudioRecorder()
    val session = FakeTranscriptionSession(completeSessionEnd)
    val sessionGuard = RealtimeSessionGuard()

    fun createService(
        scope: CoroutineScope,
    ): SpeechToTextSessionService = SpeechToTextSessionService(
        audioSession = audioSession,
        apiClient = apiClient,
        sessionFactory = { _, _ -> session },
        recorder = recorder,
        sessionGuard = sessionGuard,
        terminalTimeout = Duration.ZERO,
        scope = scope,
    )
}
