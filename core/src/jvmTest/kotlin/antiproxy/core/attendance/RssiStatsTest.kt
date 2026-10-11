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
        assertEquals(-50.0, fromQ1(toQ1(-50.0)))
        // Readings from across a classroom must encode (v1 threw below -64 dBm)
        assertEquals(-64.5, fromQ1(toQ1(-64.5)))
        assertEquals(-88.5, fromQ1(toQ1(-88.5)))
        assertEquals(-127.5, fromQ1(toQ1(-127.5)))
        // Out of range clamps instead of failing the check-in
        assertEquals(-127.5, fromQ1(toQ1(-140.0)))
        assertEquals(0.0, fromQ1(toQ1(5.0)))
    }
    
    @Test
    fun testQ8_8() {
        assertEquals(0.toUShort(), toQ8_8(0.0))
        assertEquals(256.toUShort(), toQ8_8(1.0))
        assertFailsWith<IllegalArgumentException> { toQ8_8(-1.0) }
        assertEquals(0xFFFF.toUShort(), toQ8_8(300.0))
    }
    
    @Test
    fun testEmpty() {
        assertFailsWith<IllegalArgumentException> { rssiMean(emptyList()) }
        assertFailsWith<IllegalArgumentException> { rssiVariance(emptyList()) }
    }
}
