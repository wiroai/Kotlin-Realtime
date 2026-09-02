package ai.wiro.realtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiroAuthHeadersTest {
    @Test
    fun createsApiKeyOnlyHeaders() {
        val headers = WiroAuthHeaders(
            credentials = WiroCredentials(apiKey = "test-key"),
        ).create()
        assertEquals(
            mapOf(WiroAuthHeaders.API_KEY_HEADER to "test-key"),
            headers,
        )
    }

    @Test
    fun createsDeterministicSignatureHeaders() {
        val headers = WiroAuthHeaders(
            credentials = WiroCredentials(
                apiKey = "test-key",
                apiSecret = "test-secret",
            ),
            nonceGenerator = WiroNonceGenerator { "1700000000" },
        ).create()
        assertEquals("test-key", headers[WiroAuthHeaders.API_KEY_HEADER])
        assertEquals("1700000000", headers[WiroAuthHeaders.NONCE_HEADER])
        assertEquals(
            "0e8b719d1c158c40c2b10415f96c09b794780274f5f9cbaa48d2e723ac07fc3c",
            headers[WiroAuthHeaders.SIGNATURE_HEADER],
        )
    }

    @Test
    fun createsAFreshNonceWhenTimestampsAreEqual() {
        val generator = MonotonicWiroNonceGenerator(
            microsecondsSinceEpoch = { 42 },
        )
        assertEquals("42", generator.next())
        assertEquals("43", generator.next())
    }

    @Test
    fun signerEmitsLowercaseHexadecimalOutput() {
        val signature = createWiroRequestSignature(
            apiKey = "test-key",
            apiSecret = "test-secret",
            nonce = "1700000000",
        )
        assertEquals(signature, signature.lowercase())
        assertEquals(64, signature.length)
        assertTrue(signature.all { it in "0123456789abcdef" })
    }
}
