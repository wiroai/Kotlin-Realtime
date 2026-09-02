package ai.wiro.realtime.networking

/**
 * Official Wiro hosts confirmed from docs on 2026-08-31.
 *
 * Cancel and Kill were re-checked against Wiro MCP on 2026-09-01:
 * queued work uses [TASK_CANCEL] (`taskid`); a started task falls
 * through to [TASK_KILL] (`socketaccesstoken`).
 *
 * Direct LLM routes on `llm.wiro.ai` are out of scope. This app uses
 * asynchronous Run plus the task WebSocket.
 */
object WiroEndpoints {
    const val REST_BASE: String = "https://api.wiro.ai/v1"
    const val SOCKET_URL: String = "wss://socket.wiro.ai/v1"

    const val TASK_CANCEL: String = "/Task/Cancel"
    const val TASK_KILL: String = "/Task/Kill"

    fun runPath(owner: String, slug: String): String = "/Run/$owner/$slug"
}
