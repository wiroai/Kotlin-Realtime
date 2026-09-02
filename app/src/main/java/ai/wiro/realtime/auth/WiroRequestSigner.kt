package ai.wiro.realtime.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 as lowercase hex.
 *
 * `HMAC-SHA256(key = UTF8(apiKey), message = UTF8(apiSecret + nonce))`.
 */
fun createWiroRequestSignature(
    apiKey: String,
    apiSecret: String,
    nonce: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(
        (apiSecret + nonce).toByteArray(Charsets.UTF_8),
    )
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() shr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
}
