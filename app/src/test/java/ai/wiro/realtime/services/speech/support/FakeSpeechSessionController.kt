package ai.wiro.realtime.services.speech.support

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.services.speech.SpeechSessionController
import ai.wiro.realtime.services.speech.SpeechSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeSpeechSessionController : SpeechSessionController {
    private val events = MutableSharedFlow<SpeechSessionSnapshot>(
        extraBufferCapacity = 64,
    )

    override var snapshot: SpeechSessionSnapshot = SpeechSessionSnapshot(
        selectedModel = WiroModelCatalog.mossTtsRealtime,
    )
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set
    var selectCalls: Int = 0
        private set
    val setParameterCalls: MutableList<Pair<String, String>> = mutableListOf()
    override var prompt: String = ""

    override val snapshots: Flow<SpeechSessionSnapshot>
        get() = events.asSharedFlow()

    override var parameters: ModelParameterValues = ModelParameterValues(
        fields = WiroModelCatalog.mossTtsRealtime.firstPassFields,
    )

    override val sessionGuard = RealtimeSessionGuard()

    val commandCalls: Int
        get() = selectCalls + startCalls + stopCalls

    override fun selectModel(model: ModelDefinition) {
        selectCalls += 1
        prompt = ""
        parameters = ModelParameterValues(fields = model.firstPassFields)
        snapshot = snapshot.copy(selectedModel = model)
        events.tryEmit(snapshot)
    }

    override fun setParameter(id: String, value: String) {
        setParameterCalls += id to value
        parameters = parameters.withValue(id, value)
    }

    override suspend fun start() {
        startCalls += 1
    }

    override suspend fun stop() {
        stopCalls += 1
    }

    override suspend fun dispose() {
        disposeCalls += 1
    }

    fun publish(next: SpeechSessionSnapshot) {
        snapshot = next
        events.tryEmit(next)
    }
}
