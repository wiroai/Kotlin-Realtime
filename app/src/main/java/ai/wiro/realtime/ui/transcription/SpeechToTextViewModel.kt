package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.audio.PcmAudioRecorder
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.realtime.RealtimeSessionDependencies
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.services.transcription.SpeechToTextSessionService
import ai.wiro.realtime.services.transcription.TranscriptionSessionController
import ai.wiro.realtime.services.transcription.TranscriptionSessionSnapshot
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
 * Projects a transcription session onto view state.
 *
 * Commands go to [sessionService]. This type only narrows the
 * snapshot to what the screen draws.
 */
class SpeechToTextViewModel(
    val sessionService: TranscriptionSessionController,
    private val dependencies: RealtimeSessionDependencies? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeechToTextUiState())
    val uiState: StateFlow<SpeechToTextUiState> = _uiState.asStateFlow()

    private var settingsOffer = SystemSettingsOffer()
    private var disposed = false

    val model
        get() = sessionService.model

    val accuracyOptions: List<String>
        get() = model.fields
            .first { it.id == TranscriptionFields.DELAY_ID }
            .options
            .map { it.value }

    val canStart: Boolean
        get() {
            if (!_uiState.value.sessionState.canStart) {
                return false
            }
            val lease = sessionService.sessionGuard.currentLease
            return lease == null ||
                lease.owner == RealtimeSessionOwner.Transcription
        }

    val canChangeAccuracy: Boolean
        get() = _uiState.value.sessionState.isIdleLike

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
        sessionService.start()
    }

    suspend fun stop() {
        sessionService.stop()
    }

    fun setMuted(muted: Boolean) {
        sessionService.setMuted(muted)
    }

    fun selectAccuracy(accuracy: String) {
        if (accuracy == _uiState.value.accuracy) {
            return
        }
        sessionService.setParameter(TranscriptionFields.DELAY_ID, accuracy)
        _uiState.update { state ->
            state.copy(
                accuracy = sessionService.parameters.value(
                    TranscriptionFields.DELAY_ID,
                ) ?: accuracy,
                parameters = sessionService.parameters,
            )
        }
    }

    fun clearTranscript() {
        sessionService.clearTranscript()
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

    private fun apply(snapshot: TranscriptionSessionSnapshot) {
        if (disposed) {
            return
        }
        settingsOffer = settingsOffer.apply(
            canOpen = snapshot.canOpenSystemSettings,
        )
        _uiState.update { state ->
            state.copy(
                sessionState = snapshot.sessionState,
                elapsed = snapshot.elapsed,
                transcript = snapshot.transcript,
                muted = snapshot.muted,
                errorMessage = snapshot.errorMessage,
                canOpenSystemSettings = settingsOffer.canOpen,
                showsSystemSettingsAlert = settingsOffer.showsAlert,
                accuracy = sessionService.parameters.value(
                    TranscriptionFields.DELAY_ID,
                ) ?: state.accuracy,
                parameters = sessionService.parameters,
            )
        }
    }

    companion object {
        fun live(
            credentials: WiroCredentials,
            audioSession: RealtimeAudioSession,
            sessionGuard: RealtimeSessionGuard,
            recorder: PcmAudioRecorder,
        ): SpeechToTextViewModel {
            val dependencies = RealtimeSessionDependencies.create(
                credentials = credentials,
                readyTimeout = 120.seconds,
            )
            val service = SpeechToTextSessionService(
                audioSession = audioSession,
                apiClient = dependencies.apiClient,
                sessionFactory = { taskId, taskToken ->
                    dependencies.createSession(taskId, taskToken)
                },
                recorder = recorder,
                sessionGuard = sessionGuard,
            )
            return SpeechToTextViewModel(
                sessionService = service,
                dependencies = dependencies,
            )
        }
    }
}
