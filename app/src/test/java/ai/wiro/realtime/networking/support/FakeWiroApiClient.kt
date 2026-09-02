package ai.wiro.realtime.networking.support

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.WiroApiClient
import ai.wiro.realtime.networking.WiroRequestCancellation
import kotlinx.coroutines.CompletableDeferred

class FakeWiroApiClient : WiroApiClient {
    var runCalls: Int = 0
    var lastModel: ModelDefinition? = null
    var lastParameters: Map<String, Any?>? = null
    var lastCancellation: WiroRequestCancellation? = null
    val terminatedTaskIds: MutableList<String> = mutableListOf()
    var terminateCalls: Int = 0
    var runErrors: MutableList<Throwable> = mutableListOf()
    var holdRunModel: Boolean = false
    var nextRunResult: WiroRunResult = WiroRunResult(
        taskId = "task-1",
        taskToken = "token-1",
    )
    var isWaiting: Boolean = false
        private set

    private var gate: CompletableDeferred<Unit>? = null

    fun completeRunModel() {
        val current = gate
        gate = null
        isWaiting = false
        current?.complete(Unit)
    }

    suspend fun waitUntilHolding() {
        repeat(100) {
            if (isWaiting) {
                return
            }
            kotlinx.coroutines.delay(10)
        }
    }

    override fun createCancellation(): WiroRequestCancellation = WiroRequestCancellation().also { lastCancellation = it }

    override suspend fun runModel(
        model: ModelDefinition,
        parameters: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroRunResult {
        runCalls += 1
        lastModel = model
        lastParameters = parameters
        if (runErrors.isNotEmpty()) {
            throw runErrors.removeAt(0)
        }
        if (holdRunModel) {
            val wait = CompletableDeferred<Unit>()
            gate = wait
            isWaiting = true
            wait.await()
        }
        return nextRunResult
    }

    override suspend fun terminateTaskBestEffort(
        taskId: String,
        taskToken: String,
    ) {
        terminateCalls += 1
        terminatedTaskIds += taskId
    }
}
