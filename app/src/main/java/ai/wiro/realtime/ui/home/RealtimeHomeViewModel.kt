package ai.wiro.realtime.ui.home

import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns tab selection and the audio/session objects every
 * destination shares. Features never create a second guard or
 * session.
 */
class RealtimeHomeViewModel(
    destination: RealtimeDestination = RealtimeDestination.Conversation,
    private val audioSession: RealtimeAudioSession? = null,
    val sessionGuard: RealtimeSessionGuard = RealtimeSessionGuard(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        RealtimeHomeUiState(destination),
    )
    val uiState: StateFlow<RealtimeHomeUiState> = _uiState.asStateFlow()

    fun isVisible(destination: RealtimeDestination): Boolean = _uiState.value.isVisible(destination)

    fun select(destination: RealtimeDestination) {
        _uiState.value = RealtimeHomeUiState(destination)
    }

    fun deactivateAudio() {
        viewModelScope.launch { audioSession?.deactivate() }
    }

    suspend fun dispose() {
        audioSession?.dispose()
        sessionGuard.dispose()
    }

    override fun onCleared() {
        runBlocking { dispose() }
        super.onCleared()
    }
}
