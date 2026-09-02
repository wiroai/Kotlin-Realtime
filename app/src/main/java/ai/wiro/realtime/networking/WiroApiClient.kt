package ai.wiro.realtime.networking

import ai.wiro.realtime.auth.WiroAuthHeaders
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.WiroRunResult
import kotlinx.coroutines.CancellationException

interface WiroApiClient {
    fun createCancellation(): WiroRequestCancellation

    suspend fun runModel(
        model: ModelDefinition,
        parameters: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroRunResult

    suspend fun terminateTaskBestEffort(taskId: String, taskToken: String)
}

class DefaultWiroApiClient(
    private val httpClient: WiroHttpClient,
    private val authHeaders: WiroAuthHeaders,
) : WiroApiClient {
    override fun createCancellation(): WiroRequestCancellation = WiroRequestCancellation()

    override suspend fun runModel(
        model: ModelDefinition,
        parameters: Map<String, Any?>,
        cancellation: WiroRequestCancellation?,
    ): WiroRunResult {
        val response = httpClient.post(
            path = WiroEndpoints.runPath(model.owner, model.slug),
            headers = authHeaders.create(),
            body = parameters,
            cancellation = cancellation,
        )
        validateStatusCode(response.statusCode)
        val dto = WiroRunResponseDto.from(response.json)
            ?: throw WiroFailure.MalformedResponse
        val result = dto.result ?: throw WiroFailure.MalformedResponse
        val errors = dto.errors ?: throw WiroFailure.MalformedResponse
        if (!result) {
            throw WiroFailure.ApiRejected
        }
        val taskId = dto.taskId?.trim().orEmpty()
        val taskToken = dto.taskToken?.trim().orEmpty()
        if (errors.isNotEmpty() || taskId.isEmpty() || taskToken.isEmpty()) {
            throw WiroFailure.MalformedResponse
        }
        return WiroRunResult(taskId = taskId, taskToken = taskToken)
    }

    override suspend fun terminateTaskBestEffort(
        taskId: String,
        taskToken: String,
    ) {
        try {
            val response = httpClient.post(
                path = WiroEndpoints.TASK_CANCEL,
                headers = authHeaders.create(),
                body = mapOf("taskid" to taskId),
                cancellation = null,
            )
            if (isSuccessfulTaskAction(response)) {
                return
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            // The task may already be running; try Kill.
        }
        try {
            httpClient.post(
                path = WiroEndpoints.TASK_KILL,
                headers = authHeaders.create(),
                body = mapOf("socketaccesstoken" to taskToken),
                cancellation = null,
            )
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            // Abnormal-shutdown termination is best effort.
        }
    }

    private fun isSuccessfulTaskAction(response: WiroHttpResponse): Boolean {
        if (response.statusCode !in 200..299) {
            return false
        }
        return response.json?.get("result") == true
    }

    private fun validateStatusCode(statusCode: Int) {
        if (statusCode in 200..299) {
            return
        }
        throw when (statusCode) {
            401, 403 -> WiroFailure.Authentication
            429 -> WiroFailure.RateLimited
            else -> WiroFailure.ServerUnavailable
        }
    }
}

private data class WiroRunResponseDto(
    val result: Boolean?,
    val errors: List<*>?,
    val taskId: String?,
    val taskToken: String?,
) {
    companion object {
        fun from(json: Map<String, Any?>?): WiroRunResponseDto? {
            if (json == null) {
                return null
            }
            val errors = json["errors"] as? List<*>
            return WiroRunResponseDto(
                result = json["result"] as? Boolean,
                errors = errors,
                taskId = json["taskid"] as? String,
                taskToken = json["socketaccesstoken"] as? String,
            )
        }
    }
}
