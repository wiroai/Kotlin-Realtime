package ai.wiro.realtime.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WiroEnvironmentTest {
    @Test
    fun missingKeyFailsFastWithoutASecretValue() {
        try {
            WiroEnvironment.fromValues(apiKey = "  ")
            fail("expected MissingApiKey")
        } catch (error: WiroEnvironment.MissingApiKey) {
            assertTrue(error.message.orEmpty().contains("WIRO_API_KEY"))
            assertFalse(error.message.orEmpty().contains("sk-"))
        }
    }

    @Test
    fun keyOnlyDoesNotUseASignature() {
        val credentials = WiroEnvironment.fromValues(apiKey = " test-key ")
        assertEquals("test-key", credentials.apiKey)
        assertNull(credentials.apiSecret)
        assertFalse(credentials.usesSignature)
    }

    @Test
    fun signatureModeKeepsTheSecret() {
        val credentials = WiroEnvironment.fromValues(
            apiKey = "test-key",
            apiSecret = " test-secret ",
        )
        assertEquals("test-secret", credentials.apiSecret)
        assertTrue(credentials.usesSignature)
    }

    @Test
    fun environmentWinsOverBuildConfig() {
        val credentials = WiroEnvironment.fromLaunchEnvironment(
            environment = mapOf(
                WiroEnvironment.API_KEY_NAME to " env-key ",
                WiroEnvironment.API_SECRET_NAME to "env-secret",
            ),
            buildApiKey = "build-key",
            buildApiSecret = "build-secret",
        )
        assertEquals("env-key", credentials.apiKey)
        assertEquals("env-secret", credentials.apiSecret)
    }

    @Test
    fun unresolvedBuildSettingsAreTreatedAsMissing() {
        try {
            WiroEnvironment.fromLaunchEnvironment(
                environment = emptyMap(),
                buildApiKey = "\$(WIRO_API_KEY)",
            )
            fail("expected MissingApiKey")
        } catch (_: WiroEnvironment.MissingApiKey) {
            // Fail-fast is the whole contract.
        }
    }
}
