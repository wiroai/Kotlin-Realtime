package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.audio.PcmAudioPlayer
import ai.wiro.realtime.audio.PcmAudioRecorder
import ai.wiro.realtime.audio.PcmNoiseGate
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionDependencies
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.services.conversation.ConversationSessionController
import ai.wiro.realtime.services.conversation.ConversationSessionSnapshot
import ai.wiro.realtime.services.conversation.SpeechToSpeechSessionService
import ai.wiro.realtime.ui.shared.SystemSettingsOffer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Projects a conversation session onto view state.
 *
 * Commands go to [sessionService]. This type only narrows the
 * snapshot to what the screen draws, plus [reportsConnecting].
 */
class SpeechToSpeechViewModel(
    val sessionService: ConversationSessionController,
    private val dependencies: RealtimeSessionDependencies? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SpeechToSpeechUiState(
            voice = sessionService.parameters.value(
                ConversationFields.VOICE_ID,
            ) ?: ConversationConfig.VOICE,
            parameters = sessionService.parameters,
        ),
    )
    val uiState: StateFlow<SpeechToSpeechUiState> = _uiState.asStateFlow()

    private var settingsOffer = SystemSettingsOffer()
    private var disposed = false

    val parameters
        get() = sessionService.parameters

    val model
        get() = sessionService.model

    val sessionGuard
        get() = sessionService.sessionGuard

    val voice: String
        get() = _uiState.value.voice

    val voiceOptions: List<String>
        get() = WiroModelCatalog.gptRealtimeMini.fields
            .first { it.id == ConversationFields.VOICE_ID }
            .options
            .map { it.value }

    val settingsFields
        get() = model.firstPassFields.filter { field ->
            field.id != ConversationFields.VOICE_ID &&
                field.id != ConversationFields.INSTRUCTIONS_ID
        }

    val displayedStage: ConversationStage
        get() = _uiState.value.displayedStage

    val canStart: Boolean
        get() {
            if (!_uiState.value.canStart) {
                return false
            }
            val lease = sessionService.sessionGuard.currentLease
            return lease == null ||
                lease.owner == RealtimeSessionOwner.Conversation
        }

    val canChangeVoice: Boolean
        get() {
            val state = _uiState.value
            if (state.isReconfiguring || state.showsConnecting) {
                return false
            }
            return displayedStage == ConversationStage.Ready ||
                displayedStage == ConversationStage.Live
        }

    init {
        apply(sessionService.snapshot)
        viewModelScope.launch {
            sessionService.snapshots.collect { apply(it) }
        }
    }

    suspend fun start() {
        if (disposed) {
            return
        }
        _uiState.update { it.copy(reportsConnecting = true) }
        sessionService.start()
    }

    suspend fun stop() {
        sessionService.stop()
    }

    suspend fun selectVoice(voice: String) {
        if (voice == this.voice) {
            return
        }
        if (displayedStage == ConversationStage.Live) {
            sessionService.changeVoice(voice)
        } else {
            sessionService.setParameter(ConversationFields.VOICE_ID, voice)
        }
        val resolved = sessionService.parameters.value(
            ConversationFields.VOICE_ID,
        ) ?: voice
        _uiState.update { it.copy(voice = resolved) }
    }

    fun setParameter(id: String, value: String) {
        if (disposed) {
            return
        }
        sessionService.setParameter(id, value)
        _uiState.update { state ->
            state.copy(
                parameters = sessionService.parameters,
                voice = if (id == ConversationFields.VOICE_ID) {
                    value
                } else {
                    state.voice
                },
            )
        }
    }

    suspend fun suspendSession() {
        sessionService.suspend()
    }

    suspend fun resumeSession() {
        sessionService.resume()
    }

    fun setMuted(muted: Boolean) {
        sessionService.setMuted(muted)
    }

    fun interruptAssistant() {
        sessionService.interruptAssistant()
    }

    suspend fun openSystemSettings() {
        sessionService.openSystemSettings()
    }

    fun dismissSystemSettingsAlert() {
        if (disposed) {
            return
        }
        settingsOffer = settingsOffer.dismissAlert()
        _uiState.update {
            it.copy(showsSystemSettingsAlert = settingsOffer.showsAlert)
        }
    }

    fun onVisibilityChanged(isVisible: Boolean) {
        viewModelScope.launch {
            if (isVisible) {
                resumeSession()
            } else {
                suspendSession()
            }
        }
    }

    suspend fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        sessionService.dispose()
        dependencies?.close()
    }

    override fun onCleared() {
        runBlocking { dispose() }
        super.onCleared()
    }

    private fun apply(snapshot: ConversationSessionSnapshot) {
        if (disposed) {
            return
        }
        val holdsFirstWait = when (snapshot.sessionState.conversationStage) {
            ConversationStage.Ready,
            ConversationStage.Connecting,
            -> true

            ConversationStage.Live,
            ConversationStage.Ending,
            ConversationStage.Failed,
            -> false
        }
        settingsOffer = settingsOffer.apply(
            canOpen = snapshot.canOpenSystemSettings,
        )
        _uiState.update { state ->
            state.copy(
                sessionState = snapshot.sessionState,
                elapsed = snapshot.elapsed,
                userTranscript = snapshot.userTranscript,
                aiTranscript = snapshot.aiTranscript,
                muted = snapshot.muted,
                isReconfiguring = snapshot.isReconfiguring,
                reportsConnecting = state.reportsConnecting && holdsFirstWait,
                errorMessage = snapshot.errorMessage,
                canOpenSystemSettings = settingsOffer.canOpen,
                showsSystemSettingsAlert = settingsOffer.showsAlert,
                voice = sessionService.parameters.value(
                    ConversationFields.VOICE_ID,
                ) ?: state.voice,
                parameters = sessionService.parameters,
            )
        }
    }

    companion object {
        const val PLAYBACK_QUEUE_CAPACITY_IN_CHUNKS: Int = 50

        fun live(
            credentials: WiroCredentials,
            audioSession: RealtimeAudioSession,
            sessionGuard: RealtimeSessionGuard,
            recorder: PcmAudioRecorder,
            player: PcmAudioPlayer,
        ): SpeechToSpeechViewModel {
            val dependencies = RealtimeSessionDependencies.create(
                credentials = credentials,
                readyTimeout = 60.seconds,
            )
            val service = SpeechToSpeechSessionService(
                audioSession = audioSession,
                apiClient = dependencies.apiClient,
                sessionFactory = { taskId, taskToken ->
                    dependencies.createSession(taskId, taskToken)
                },
                recorder = recorder,
                player = player,
                sessionGuard = sessionGuard,
                noiseGate = PcmNoiseGate(),
            )
            return SpeechToSpeechViewModel(
                sessionService = service,
                dependencies = dependencies,
            )
        }
    }
}
