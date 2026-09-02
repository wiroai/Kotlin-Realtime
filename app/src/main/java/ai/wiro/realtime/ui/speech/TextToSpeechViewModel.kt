package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.audio.PcmAudioPlayer
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.realtime.RealtimeSessionDependencies
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.services.speech.SpeechSessionController
import ai.wiro.realtime.services.speech.SpeechSessionSnapshot
import ai.wiro.realtime.services.speech.TextToSpeechSessionService
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
 * Projects a text-to-speech session onto view state.
 *
 * Commands go to [sessionService]. This type only narrows the
 * snapshot to what the screen draws.
 */
class TextToSpeechViewModel(
    val sessionService: SpeechSessionController,
    private val dependencies: RealtimeSessionDependencies? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TextToSpeechUiState(
            selectedModel = sessionService.snapshot.selectedModel,
        ),
    )
    val uiState: StateFlow<TextToSpeechUiState> = _uiState.asStateFlow()

    private var disposed = false

    val parameters
        get() = sessionService.parameters

    val sessionGuard
        get() = sessionService.sessionGuard

    val prompt: String
        get() = sessionService.prompt

    val canStart: Boolean
        get() {
            if (!_uiState.value.canStart) {
                return false
            }
            val lease = sessionService.sessionGuard.currentLease
            return lease == null ||
                lease.owner == RealtimeSessionOwner.Speech
        }

    val canChangeModel: Boolean
        get() = _uiState.value.sessionState.isIdleLike

    init {
        apply(sessionService.snapshot)
        viewModelScope.launch {
            sessionService.snapshots.collect { apply(it) }
        }
    }

    fun setPrompt(value: String) {
        sessionService.prompt = value
        _uiState.update { it.copy(prompt = value) }
    }

    fun selectModel(model: ModelDefinition) {
        if (disposed) {
            return
        }
        sessionService.selectModel(model)
    }

    fun setParameter(id: String, value: String) {
        if (disposed) {
            return
        }
        sessionService.setParameter(id, value)
        _uiState.update { it.copy(parameters = sessionService.parameters) }
    }

    suspend fun start() {
        if (disposed) {
            return
        }
        sessionService.start()
    }

    suspend fun stop() {
        sessionService.stop()
    }

    fun onVisibilityChanged(isVisible: Boolean) {
        if (!isVisible) {
            viewModelScope.launch { stop() }
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

    private fun apply(snapshot: SpeechSessionSnapshot) {
        if (disposed) {
            return
        }
        _uiState.update { state ->
            state.copy(
                selectedModel = snapshot.selectedModel,
                sessionState = snapshot.sessionState,
                elapsed = snapshot.elapsed,
                isPlaying = snapshot.isPlaying,
                errorMessage = snapshot.errorMessage,
                prompt = sessionService.prompt,
                parameters = sessionService.parameters,
            )
        }
    }

    companion object {
        const val PLAYBACK_QUEUE_CAPACITY_IN_CHUNKS: Int = 2500

        fun live(
            credentials: WiroCredentials,
            audioSession: RealtimeAudioSession,
            sessionGuard: RealtimeSessionGuard,
            player: PcmAudioPlayer,
        ): TextToSpeechViewModel {
            val dependencies = RealtimeSessionDependencies.create(
                credentials = credentials,
                readyTimeout = 120.seconds,
            )
            val service = TextToSpeechSessionService(
                audioSession = audioSession,
                apiClient = dependencies.apiClient,
                sessionFactory = { taskId, taskToken ->
                    dependencies.createSession(taskId, taskToken)
                },
                player = player,
                sessionGuard = sessionGuard,
            )
            return TextToSpeechViewModel(
                sessionService = service,
                dependencies = dependencies,
            )
        }
    }
}
