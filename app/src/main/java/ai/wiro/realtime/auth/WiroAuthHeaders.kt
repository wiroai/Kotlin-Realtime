package ai.wiro.realtime.auth

class WiroAuthHeaders(
    val credentials: WiroCredentials,
    nonceGenerator: WiroNonceGenerator? = null,
    private val signer: (
        apiKey: String,
        apiSecret: String,
        nonce: String,
    ) -> String = ::createWiroRequestSignature,
) {
    private val nonceGenerator: WiroNonceGenerator =
        nonceGenerator ?: MonotonicWiroNonceGenerator()

    fun create(): Map<String, String> {
        val headers = mutableMapOf(API_KEY_HEADER to credentials.apiKey)
        val apiSecret = credentials.apiSecret
        if (!credentials.usesSignature || apiSecret == null) {
            return headers
        }
        val nonce = nonceGenerator.next()
        headers[NONCE_HEADER] = nonce
        headers[SIGNATURE_HEADER] = signer(
            credentials.apiKey,
            apiSecret,
            nonce,
        )
        return headers
    }

    companion object {
        const val API_KEY_HEADER: String = "x-api-key"
        const val NONCE_HEADER: String = "x-nonce"
        const val SIGNATURE_HEADER: String = "x-signature"
    }
}
