package ai.wiro.realtime.networking

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.WiroRunResult
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the Wiro Run task behind a realtime session: the in-flight
 * request, its cancellation, and the server-side task it produces.
 *
 * A task that outlives its caller keeps billing on the server, so the
 * two ways of letting go are deliberately separate: [release] forgets
 * a task that a clean session end already wound down, while
 * [terminateBestEffort] tells the server to tear it down.
 */
class RealtimeTaskLauncher(
    private val apiClient: WiroApiClient,
) {
    private data class State(
        val runResult: WiroRunResult? = null,
        val cancellation: WiroRequestCancellation? = null,
    )

    private val state = AtomicReference(State())

    val currentRunResult: WiroRunResult?
        get() = state.get().runResult

    /**
     * Creates the task, or returns null when [isStillWanted] reports
     * that the caller moved on while the request was in flight.
     */
    suspend fun launch(
        model: ModelDefinition,
        parameters: Map<String, Any?>,
        isStillWanted: () -> Boolean,
    ): WiroRunResult? {
        val cancellation = apiClient.createCancellation()
        state.update { it.copy(cancellation = cancellation) }
        val runResult = apiClient.runModel(
            model = model,
            parameters = parameters,
            cancellation = cancellation,
        )
        if (!isStillWanted()) {
            apiClient.terminateTaskBestEffort(
                taskId = runResult.taskId,
                taskToken = runResult.taskToken,
            )
            return null
        }
        state.update {
            it.copy(runResult = runResult, cancellation = null)
        }
        return runResult
    }

    fun cancelPendingRequest() {
        val cancellation = state.getAndUpdate {
            it.copy(cancellation = null)
        }.cancellation
        cancellation?.cancel()
    }

    fun release() {
        state.update { it.copy(runResult = null) }
    }

    suspend fun terminateBestEffort() {
        val runResult = state.getAndUpdate {
            it.copy(runResult = null)
        }.runResult ?: return
        apiClient.terminateTaskBestEffort(
            taskId = runResult.taskId,
            taskToken = runResult.taskToken,
        )
    }
}

private fun <T> AtomicReference<T>.update(block: (T) -> T) {
    while (true) {
        val current = get()
        if (compareAndSet(current, block(current))) {
            return
        }
    }
}
