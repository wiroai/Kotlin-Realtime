package ai.wiro.realtime.services.speech.support

import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.audio.support.FakeSpeechPlayer
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.support.FakeWiroApiClient
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.speech.TextToSpeechSessionService
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

class SpeechTestDependencies(
    preparationResult: AudioPreparationResult = AudioPreparationResult.Ready,
    completeSessionEnd: Boolean = true,
    holdComplete: Boolean = false,
    sessionConnectFailures: Int = 0,
) {
    val audioSession = FakeRealtimeAudioSession(preparationResult)
    val apiClient = FakeWiroApiClient().also {
        it.nextRunResult = WiroRunResult(
            taskId = "task-id",
            taskToken = "task-token",
        )
    }
    val player = FakeSpeechPlayer(holdComplete)
    val session = FakeSpeechSession(
        completeSessionEnd = completeSessionEnd,
        connectFailures = sessionConnectFailures,
    )
    val sessionGuard = RealtimeSessionGuard()

    fun createService(
        scope: CoroutineScope,
        model: ModelDefinition = WiroModelCatalog.mossTtsRealtime,
    ): TextToSpeechSessionService = TextToSpeechSessionService(
        audioSession = audioSession,
        apiClient = apiClient,
        sessionFactory = { _, _ -> session },
        player = player,
        sessionGuard = sessionGuard,
        model = model,
        terminalTimeout = Duration.ZERO,
        connectRetryDelay = Duration.ZERO,
        scope = scope,
    )
}
