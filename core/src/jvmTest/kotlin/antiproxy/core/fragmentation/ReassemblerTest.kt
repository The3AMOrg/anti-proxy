package antiproxy.core.fragmentation

import antiproxy.core.protocol.*
import kotlin.test.*

class ReassemblerTest {
    @Test
    fun testReassembler() {
        val data = ByteArray(25) { it.toByte() }
        val chunks = fragmentPayload(123.toUShort(), data, 10)
        
        val r = Reassembler(123.toUShort())
        assertEquals(ReassemblyResult.Incomplete, r.addFragment(chunks[0]))
        assertEquals(ReassemblyResult.Incomplete, r.addFragment(chunks[2]))
        
        val res = r.addFragment(chunks[1])
        assertTrue(res is ReassemblyResult.Complete)
        assertTrue(data.contentEquals((res as ReassemblyResult.Complete).payload))
    }
    
    @Test
    fun testReassemblerReject() {
        val r = Reassembler(123.toUShort())
        val f1 = Fragment(123.toUShort(), 0.toByte(), 2.toByte(), 10.toUShort(), ByteArray(10))
        r.addFragment(f1)
        
        val fDup = Fragment(123.toUShort(), 0.toByte(), 2.toByte(), 10.toUShort(), ByteArray(10))
        assertTrue(r.addFragment(fDup) is ReassemblyResult.Error)
        
        val fWrongId = Fragment(124.toUShort(), 1.toByte(), 2.toByte(), 10.toUShort(), ByteArray(10))
        assertTrue(r.addFragment(fWrongId) is ReassemblyResult.Error)
    }
}
