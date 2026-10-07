package antiproxy.core.attendance

import kotlin.test.*

class RssiStatsTest {
    @Test
    fun testMean() {
        assertEquals(-50.0, rssiMean(listOf(-50, -50, -50)))
        assertEquals(-50.0, rssiMean(listOf(-40, -60)))
    }
    
    @Test
    fun testVariance() {
        assertEquals(0.0, rssiVariance(listOf(-50, -50, -50)))
        assertEquals(100.0, rssiVariance(listOf(-40, -60)))
    }

    @Test
    fun testQ1() {
        val q1 = toQ1(-50.0)
        assertEquals((-100).toByte(), q1)
        assertEquals(-50.0, fromQ1(q1))
        assertEquals((-128).toByte(), toQ1(-64.0))
        assertEquals(127.toByte(), toQ1(63.5))
        
        assertFailsWith<IllegalArgumentException> { toQ1(-64.5) }
        assertFailsWith<IllegalArgumentException> { toQ1(64.0) }
    }
    
    @Test
    fun testQ8_8() {
        assertEquals(0.toUShort(), toQ8_8(0.0))
        assertEquals(256.toUShort(), toQ8_8(1.0))
        assertFailsWith<IllegalArgumentException> { toQ8_8(-1.0) }
        assertFailsWith<IllegalArgumentException> { toQ8_8(256.0) }
    }
    
    @Test
    fun testEmpty() {
        assertFailsWith<IllegalArgumentException> { rssiMean(emptyList()) }
        assertFailsWith<IllegalArgumentException> { rssiVariance(emptyList()) }
    }
}
