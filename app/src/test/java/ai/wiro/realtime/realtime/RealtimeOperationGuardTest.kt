package ai.wiro.realtime.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeOperationGuardTest {
    @Test
    fun onlyTheNewestOperationStaysCurrent() {
        val operationGuard = RealtimeOperationGuard()
        val first = operationGuard.begin()
        assertTrue(operationGuard.isCurrent(first))

        val second = operationGuard.begin()
        assertFalse(operationGuard.isCurrent(first))
        assertTrue(operationGuard.isCurrent(second))
        assertEquals(second, operationGuard.current)
    }

    @Test
    fun anOperationIdIsNeverReused() {
        val operationGuard = RealtimeOperationGuard()
        val ids = (0 until 5).map { operationGuard.begin() }
        assertEquals(listOf(1, 2, 3, 4, 5), ids)
    }

    @Test
    fun disposalInvalidatesEveryOperation() {
        val operationGuard = RealtimeOperationGuard()
        operationGuard.begin()
        val pending = operationGuard.current

        operationGuard.dispose()

        assertFalse(operationGuard.isCurrent(pending))
        assertFalse(operationGuard.isCurrent(operationGuard.current))
        assertFalse(operationGuard.isCurrent(operationGuard.begin()))
    }

    @Test
    fun disposalIsReportedForCallersHoldingNoOperationId() {
        val operationGuard = RealtimeOperationGuard()
        assertFalse(operationGuard.isDisposed)
        operationGuard.dispose()
        assertTrue(operationGuard.isDisposed)
    }

    @Test
    fun anUnclaimedIdIsNotCurrentBeforeAnyOperationStarts() {
        val operationGuard = RealtimeOperationGuard()
        assertFalse(operationGuard.isCurrent(1))
        assertTrue(operationGuard.isCurrent(operationGuard.current))
    }
}
