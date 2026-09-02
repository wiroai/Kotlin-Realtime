package ai.wiro.realtime.auth

data class WiroCredentials(
    val apiKey: String,
    val apiSecret: String? = null,
) {
    val usesSignature: Boolean
        get() = !apiSecret.isNullOrEmpty()
}
