package ai.wiro.realtime.services.transcription.support

import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.transcription.TranscriptionSessionController
import ai.wiro.realtime.services.transcription.TranscriptionSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps the snapshot stream open after [dispose] so a test can
 * prove the view model stopped applying events rather than the
 * controller shutting the stream down.
 */
class FakeTranscriptionSessionController : TranscriptionSessionController {
    private val events = MutableSharedFlow<TranscriptionSessionSnapshot>(
        extraBufferCapacity = 64,
    )

    override var snapshot: TranscriptionSessionSnapshot =
        TranscriptionSessionSnapshot()
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set
    var openSettingsCalls: Int = 0
        private set
    val muteRequests: MutableList<Boolean> = mutableListOf()
    val setParameterCalls: MutableList<Pair<String, String>> = mutableListOf()

    override val snapshots: Flow<TranscriptionSessionSnapshot>
        get() = events.asSharedFlow()

    override val model = WiroModelCatalog.voxtralMiniRealtime

    override var parameters: ModelParameterValues = ModelParameterValues(
        fields = WiroModelCatalog.voxtralMiniRealtime.firstPassFields,
    )

    override val sessionGuard = RealtimeSessionGuard()

    override suspend fun start() {
        startCalls += 1
    }

    override suspend fun stop() {
        stopCalls += 1
    }

    override fun setMuted(muted: Boolean) {
        muteRequests += muted
    }

    override fun setParameter(id: String, value: String) {
        setParameterCalls += id to value
        parameters = parameters.withValue(id, value)
    }

    override fun clearTranscript() {
        clearCalls += 1
    }

    override suspend fun openSystemSettings() {
        openSettingsCalls += 1
    }

    override suspend fun dispose() {
        disposeCalls += 1
    }

    fun publish(next: TranscriptionSessionSnapshot) {
        snapshot = next
        events.tryEmit(next)
    }
}
