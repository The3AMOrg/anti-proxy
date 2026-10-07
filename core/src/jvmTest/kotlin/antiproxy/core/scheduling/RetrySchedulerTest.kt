package antiproxy.core.scheduling

import kotlin.test.*
import antiproxy.core.protocol.*

class RetrySchedulerTest {
    @Test
    fun testComputeDelay() {
        val d0 = RetryScheduler.computeDelayMs(0)
        assertNotNull(d0)
        assertTrue(d0 != null && d0 >= SLOT_DURATION_MS)
        
        val dMax = RetryScheduler.computeDelayMs(MAX_RETRY_COUNT - 1)
        assertNotNull(dMax)
        
        assertNull(RetryScheduler.computeDelayMs(MAX_RETRY_COUNT))
        
        assertFailsWith<IllegalArgumentException> { RetryScheduler.computeDelayMs(-1) }
    }
    
    @Test
    fun testCanRetry() {
        assertTrue(RetryScheduler.canRetry(0))
        assertFalse(RetryScheduler.canRetry(MAX_RETRY_COUNT))
    }
}
