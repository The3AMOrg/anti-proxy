package antiproxy.core.scheduling

import kotlin.test.*
import antiproxy.core.protocol.*

class SlotSchedulerTest {
    @Test
    fun testPickSlot() {
        for (i in 0..1000) {
            val slot = SlotScheduler.pickSlot()
            assertTrue(slot in 0 until NUM_SLOTS)
        }
    }
    
    @Test
    fun testSlotDelay() {
        assertEquals(0L, SlotScheduler.slotDelayMs(0))
        assertEquals((NUM_SLOTS - 1) * SLOT_DURATION_MS, SlotScheduler.slotDelayMs(NUM_SLOTS - 1))
        
        assertFailsWith<IllegalArgumentException> { SlotScheduler.slotDelayMs(-1) }
        assertFailsWith<IllegalArgumentException> { SlotScheduler.slotDelayMs(NUM_SLOTS) }
    }
}
