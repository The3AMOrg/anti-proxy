package antiproxy.core.protocol

import kotlin.test.*

class FragmentCodecTest {
    @Test
    fun testRoundTrip() {
        val payload = ByteArray(10) { it.toByte() }
        val fragment = Fragment(
            transferId = 123.toUShort(),
            sequenceNumber = 1.toByte(),
            totalChunks = 3.toByte(),
            chunkLength = payload.size.toUShort(),
            payload = payload
        )
        val encoded = encodeFragment(fragment)
        assertEquals(FRAGMENT_HEADER_SIZE + payload.size, encoded.size)
        val decoded = decodeFragment(encoded)
        assertEquals(fragment.transferId, decoded.transferId)
        assertEquals(fragment.sequenceNumber, decoded.sequenceNumber)
        assertEquals(fragment.totalChunks, decoded.totalChunks)
        assertEquals(fragment.chunkLength, decoded.chunkLength)
        assertTrue(fragment.payload.contentEquals(decoded.payload))
    }
    
    @Test
    fun testFragmentPayload() {
        val payload = ByteArray(25) { it.toByte() }
        val chunks = fragmentPayload(123.toUShort(), payload, 10)
        assertEquals(3, chunks.size)
        assertEquals(10, chunks[0].payload.size)
        assertEquals(10, chunks[1].payload.size)
        assertEquals(5, chunks[2].payload.size)
        assertEquals(0.toByte(), chunks[0].sequenceNumber)
        assertEquals(1.toByte(), chunks[1].sequenceNumber)
        assertEquals(2.toByte(), chunks[2].sequenceNumber)
        for (c in chunks) assertEquals(3.toByte(), c.totalChunks)
    }
    
    @Test
    fun testFragmentPayloadExact() {
        val payload = ByteArray(10)
        val chunks = fragmentPayload(1.toUShort(), payload, 10)
        assertEquals(1, chunks.size)
    }

    @Test
    fun testInvalidFragments() {
        assertFailsWith<IllegalArgumentException> {
            decodeFragment(ByteArray(5)) // Too short for header
        }
        assertFailsWith<IllegalArgumentException> {
            Fragment(1.toUShort(), 0.toByte(), 17.toByte(), 10.toUShort(), ByteArray(10)) // > MAX_FRAGMENT_CHUNKS
        }
        assertFailsWith<IllegalArgumentException> {
            Fragment(1.toUShort(), 0.toByte(), 0.toByte(), 10.toUShort(), ByteArray(10)) // 0 chunks
        }
    }
}
