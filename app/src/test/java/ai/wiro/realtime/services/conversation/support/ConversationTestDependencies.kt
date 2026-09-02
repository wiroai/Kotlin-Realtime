package ai.wiro.realtime.services.conversation.support

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.audio.AudioConstants
import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.support.FakePcmAudioRecorder
import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.audio.support.FakeSpeechPlayer
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.support.FakeWiroApiClient
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.conversation.SpeechToSpeechSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConversationTestDependencies(
    preparationResult: AudioPreparationResult = AudioPreparationResult.Ready,
    runErrors: List<Throwable> = emptyList(),
) {
    val audioSession = FakeRealtimeAudioSession(preparationResult)
    val apiClient = FakeWiroApiClient().also {
        it.nextRunResult = WiroRunResult(
            taskId = "task-id",
            taskToken = "task-token",
        )
        it.runErrors = runErrors.toMutableList()
    }
    val recorder = FakePcmAudioRecorder()
    val player = FakeSpeechPlayer()
    val session = FakeConversationSession()
    val sessionGuard = RealtimeSessionGuard()
    val subscribedTaskTokens = mutableListOf<String>()

    val taskTokens: List<String>
        get() = synchronized(subscribedTaskTokens) {
            subscribedTaskTokens.toList()
        }

    fun createService(
        scope: CoroutineScope,
        maxRetryAttempts: Int = 0,
        retryBaseDelay: Duration = Duration.ZERO,
        micStallTimeout: Duration = 3.seconds,
        autoRestartDelay: Duration = 5.seconds,
        maxAutoRestarts: Int = 2,
        maxSessionDuration: Duration = Duration.ZERO,
        suspendGracePeriod: Duration = ConversationConfig.suspendGracePeriod,
        parameterOverrides: Map<String, String> = ConversationConfig.parameters,
    ): SpeechToSpeechSessionService = SpeechToSpeechSessionService(
        audioSession = audioSession,
        apiClient = apiClient,
        sessionFactory = { _, taskToken ->
            synchronized(subscribedTaskTokens) {
                subscribedTaskTokens += taskToken
            }
            session
        },
        recorder = recorder,
        player = player,
        sessionGuard = sessionGuard,
        parameterOverrides = parameterOverrides,
        maxSessionDuration = maxSessionDuration,
        suspendGracePeriod = suspendGracePeriod,
        terminalTimeout = Duration.ZERO,
        maxRetryAttempts = maxRetryAttempts,
        retryBaseDelay = retryBaseDelay,
        micStallTimeout = micStallTimeout,
        autoRestartDelay = autoRestartDelay,
        maxAutoRestarts = maxAutoRestarts,
        scope = scope,
        nowMillis = virtualNow(scope),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun virtualNow(scope: CoroutineScope): () -> Long {
        val scheduler = (scope as? TestScope)?.testScheduler
        return { scheduler?.currentTime ?: System.currentTimeMillis() }
    }
}

fun conversationPcm(
    amplitude: Short,
    milliseconds: Int = 20,
): ByteArray {
    val sampleCount = AudioConstants.SAMPLE_RATE_HZ * milliseconds / 1_000
    val bytes = ByteArray(sampleCount * 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    repeat(sampleCount) {
        buffer.putShort(amplitude)
    }
    return bytes
}
