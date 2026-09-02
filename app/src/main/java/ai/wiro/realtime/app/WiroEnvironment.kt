package ai.wiro.realtime.app

import ai.wiro.realtime.auth.WiroCredentials

object WiroEnvironment {
    const val API_KEY_NAME: String = "WIRO_API_KEY"
    const val API_SECRET_NAME: String = "WIRO_API_SECRET"

    class MissingApiKey :
        IllegalStateException(
            "Missing required $API_KEY_NAME. Copy " +
                "secrets.properties.example to secrets.properties " +
                "and set the key, or pass $API_KEY_NAME in the " +
                "process environment.",
        )

    fun fromLaunchEnvironment(
        environment: Map<String, String?> = System.getenv(),
        buildApiKey: String = "",
        buildApiSecret: String = "",
    ): WiroCredentials = fromValues(
        apiKey = resolvedValue(
            name = API_KEY_NAME,
            environment = environment,
            fallback = buildApiKey,
        ),
        apiSecret = resolvedValue(
            name = API_SECRET_NAME,
            environment = environment,
            fallback = buildApiSecret,
        ),
    )

    fun fromValues(
        apiKey: String,
        apiSecret: String? = null,
    ): WiroCredentials {
        val normalizedKey = normalized(apiKey)
        if (normalizedKey.isEmpty()) {
            throw MissingApiKey()
        }
        val normalizedSecret = normalized(apiSecret.orEmpty())
        return WiroCredentials(
            apiKey = normalizedKey,
            apiSecret = normalizedSecret.ifEmpty { null },
        )
    }

    private fun resolvedValue(
        name: String,
        environment: Map<String, String?>,
        fallback: String,
    ): String {
        val envValue = normalized(environment[name].orEmpty())
        if (envValue.isNotEmpty()) {
            return envValue
        }
        return normalized(fallback)
    }

    private fun normalized(value: String): String {
        val trimmed = value.trim()
        if (isUnresolvedBuildSetting(trimmed)) {
            return ""
        }
        return trimmed
    }

    private fun isUnresolvedBuildSetting(value: String): Boolean = value.startsWith("$(") && value.endsWith(")")
}
