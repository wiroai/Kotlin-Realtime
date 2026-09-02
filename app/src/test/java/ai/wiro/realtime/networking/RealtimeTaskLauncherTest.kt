package ai.wiro.realtime.networking

import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.networking.support.FakeWiroApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTaskLauncherTest {
    @Test
    fun returnsTheTaskAndForwardsTheRequestParameters() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        val runResult = launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = mapOf("sampleRate" to 24000),
            isStillWanted = { true },
        )
        assertEquals("task-1", runResult?.taskId)
        assertEquals(1, apiClient.runCalls)
        assertEquals(24000, apiClient.lastParameters?.get("sampleRate"))
        assertEquals(
            WiroModelCatalog.gptRealtimeMini.modelSlug,
            apiClient.lastModel?.modelSlug,
        )
    }

    @Test
    fun terminatesATaskThatArrivesAfterTheCallerMovedOn() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        val runResult = launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = emptyMap(),
            isStillWanted = { false },
        )
        assertNull(runResult)
        assertEquals(listOf("task-1"), apiClient.terminatedTaskIds)
    }

    @Test
    fun aStaleTaskIsNotTerminatedTwiceByALaterTeardown() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = emptyMap(),
            isStillWanted = { false },
        )
        launcher.terminateBestEffort()
        assertEquals(listOf("task-1"), apiClient.terminatedTaskIds)
    }

    @Test
    fun terminateBestEffortTearsDownTheLiveTaskExactlyOnce() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = emptyMap(),
            isStillWanted = { true },
        )
        launcher.terminateBestEffort()
        launcher.terminateBestEffort()
        assertEquals(listOf("task-1"), apiClient.terminatedTaskIds)
    }

    @Test
    fun releaseForgetsTheTaskWithoutTerminatingIt() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = emptyMap(),
            isStillWanted = { true },
        )
        launcher.release()
        launcher.terminateBestEffort()
        assertTrue(apiClient.terminatedTaskIds.isEmpty())
    }

    @Test
    fun cancelPendingRequestAbortsAnInFlightRequest() = runTest {
        val apiClient = FakeWiroApiClient()
        apiClient.holdRunModel = true
        val launcher = RealtimeTaskLauncher(apiClient)
        val pending = async {
            launcher.launch(
                model = WiroModelCatalog.gptRealtimeMini,
                parameters = emptyMap(),
                isStillWanted = { true },
            )
        }
        apiClient.waitUntilHolding()
        launcher.cancelPendingRequest()
        assertEquals(true, apiClient.lastCancellation?.isCancelled)
        apiClient.completeRunModel()
        pending.await()
    }

    @Test
    fun aCompletedLaunchLeavesNoCancellationToAbort() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        launcher.launch(
            model = WiroModelCatalog.gptRealtimeMini,
            parameters = emptyMap(),
            isStillWanted = { true },
        )
        launcher.cancelPendingRequest()
        assertFalse(apiClient.lastCancellation?.isCancelled == true)
        assertNotNull(apiClient.lastCancellation)
    }

    @Test
    fun teardownIsSafeWhenNothingWasEverLaunched() = runTest {
        val apiClient = FakeWiroApiClient()
        val launcher = RealtimeTaskLauncher(apiClient)
        launcher.release()
        launcher.terminateBestEffort()
        launcher.cancelPendingRequest()
        assertTrue(apiClient.terminatedTaskIds.isEmpty())
    }
}
