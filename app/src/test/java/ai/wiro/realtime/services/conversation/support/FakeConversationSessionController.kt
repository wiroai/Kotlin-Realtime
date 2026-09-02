package ai.wiro.realtime.services.conversation.support

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.conversation.ConversationSessionController
import ai.wiro.realtime.services.conversation.ConversationSessionSnapshot
import ai.wiro.realtime.services.conversation.ConversationVoiceActivity
import ai.wiro.realtime.ui.conversation.ConversationFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps the snapshot stream open after [dispose] so a test can prove
 * the view model stopped applying events rather than the controller
 * shutting the stream down.
 */
class FakeConversationSessionController : ConversationSessionController {
    private val events = MutableSharedFlow<ConversationSessionSnapshot>(
        extraBufferCapacity = 64,
    )

    override var snapshot: ConversationSessionSnapshot =
        ConversationSessionSnapshot()
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    var interruptCalls: Int = 0
        private set
    var suspendCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var openSettingsCalls: Int = 0
        private set
    val muteRequests: MutableList<Boolean> = mutableListOf()
    val changeVoiceCalls: MutableList<String> = mutableListOf()
    val setParameterCalls: MutableList<Pair<String, String>> = mutableListOf()

    override val model: ModelDefinition = WiroModelCatalog.gptRealtimeMini
    override var parameters: ModelParameterValues =
        ConversationConfig.parameterValues()
    override val sessionGuard = RealtimeSessionGuard()
    override val voiceActivity = ConversationVoiceActivity(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    override val snapshots: Flow<ConversationSessionSnapshot>
        get() = events.asSharedFlow()

    override suspend fun start() {
        startCalls += 1
    }

    override suspend fun stop() {
        stopCalls += 1
    }

    override suspend fun changeVoice(voice: String) {
        changeVoiceCalls += voice
        parameters = parameters.withValue(ConversationFields.VOICE_ID, voice)
    }

    override suspend fun suspend() {
        suspendCalls += 1
    }

    override suspend fun resume() {
        resumeCalls += 1
    }

    override fun setMuted(muted: Boolean) {
        muteRequests += muted
    }

    override fun setParameter(id: String, value: String) {
        setParameterCalls += id to value
        parameters = parameters.withValue(id, value)
    }

    override fun interruptAssistant() {
        interruptCalls += 1
    }

    override suspend fun openSystemSettings() {
        openSettingsCalls += 1
    }

    override suspend fun dispose() {
        disposeCalls += 1
    }

    fun publish(next: ConversationSessionSnapshot) {
        snapshot = next
        events.tryEmit(next)
    }
}
