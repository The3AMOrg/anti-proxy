package antiproxy.core.protocol

import kotlin.test.*

class WireFormatTest {
    @Test
    fun testUInt64BE() {
        val bytes = ByteArray(8)
        val values = listOf(0L, 1L, Long.MAX_VALUE, -1L)
        for (v in values) {
            putUInt64BE(bytes, 0, v)
            assertEquals(v, getUInt64BE(bytes, 0))
        }
    }
    
    @Test
    fun testUInt32BE() {
        val bytes = ByteArray(4)
        val values = listOf(0, 1, Int.MAX_VALUE, -1)
        for (v in values) {
            putUInt32BE(bytes, 0, v)
            assertEquals(v, getUInt32BE(bytes, 0))
        }
    }
    
    @Test
    fun testUInt16BE() {
        val bytes = ByteArray(2)
        val values = listOf(0.toUShort(), 1.toUShort(), UShort.MAX_VALUE)
        for (v in values) {
            putUInt16BE(bytes, 0, v)
            assertEquals(v, getUInt16BE(bytes, 0))
        }
    }

    @Test
    fun testBytes() {
        val bytes = ByteArray(10)
        val src = ByteArray(5) { it.toByte() }
        putBytes(bytes, 2, src)
        val dest = getBytes(bytes, 2, 5)
        assertTrue(src.contentEquals(dest))
    }

    @Test
    fun testBounds() {
        assertFailsWith<IllegalArgumentException> {
            checkBounds(ByteArray(5), 2, 4)
        }
        checkBounds(ByteArray(5), 2, 3)
    }
}
