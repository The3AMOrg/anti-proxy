package antiproxy.core.attendance

import kotlin.test.*

class ClockGuardTest {
    @Test
    fun testClockGuard() {
        var savedTime = 0L
        val guard = ClockGuard(
            loadLastTime = { savedTime },
            saveLastTime = { savedTime = it }
        )
        
        assertTrue(guard.checkAndUpdate(1000L, 30L))
        assertEquals(1000L, savedTime)
        
        assertTrue(guard.checkAndUpdate(1010L, 30L)) // Forward
        assertEquals(1010L, savedTime)
        
        assertFalse(guard.checkAndUpdate(900L, 30L)) // Obvious rollback
        assertEquals(1010L, savedTime) // Not updated
        
        assertTrue(guard.checkAndUpdate(990L, 30L)) // Small rollback within tolerance
        assertEquals(1010L, savedTime) // Max observed is kept
        
        assertTrue(guard.checkAndUpdate(980L, 30L)) // Exact boundary
    }
}
