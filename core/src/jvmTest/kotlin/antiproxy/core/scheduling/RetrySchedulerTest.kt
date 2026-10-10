package antiproxy.core.scheduling

import kotlin.test.*
import antiproxy.core.protocol.*

class RetrySchedulerTest {
    @Test
    fun testComputeDelay() {
        val d0 = RetryScheduler.computeDelayMs(0)
        assertNotNull(d0)
        assertTrue(d0 >= SLOT_DURATION_MS)
        
        for (r in 0 until MAX_RETRY_COUNT) {
            val delay = RetryScheduler.computeDelayMs(r)
            assertNotNull(delay)
            val baseSlots = kotlin.math.min(1 shl r, RETRY_MAX_SLOTS)
            val minDelay = baseSlots.toLong() * SLOT_DURATION_MS
            val maxDelay = (baseSlots + RETRY_JITTER_BOUND).toLong() * SLOT_DURATION_MS
            assertTrue(delay in minDelay..maxDelay, "Delay $delay should be between $minDelay and $maxDelay for retry $r")
        }
        
        assertNull(RetryScheduler.computeDelayMs(MAX_RETRY_COUNT))
        assertFailsWith<IllegalArgumentException> { RetryScheduler.computeDelayMs(-1) }
    }
    
    @Test
    fun testGattTransportException() {
        val cause = IllegalStateException("Socket disconnected")
        val ex = GattTransportException("Connection dropped", cause)
        assertEquals("Connection dropped", ex.message)
        assertEquals(cause, ex.cause)
    }
    
    @Test
    fun testCanRetry() {
        assertTrue(RetryScheduler.canRetry(0))
        assertFalse(RetryScheduler.canRetry(MAX_RETRY_COUNT))
    }
}
