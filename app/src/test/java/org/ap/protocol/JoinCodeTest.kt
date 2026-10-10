package org.ap.protocol

import org.ap.protocol.classroom.JoinCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinCodeTest {

    // Same pattern as validCode() in firestore.rules; a mismatch means codes the server rejects
    private val rulesPattern = Regex("^[A-HJKMNP-Z2-9]{6}$")

    @Test
    fun randomCodesMatchTheServerRule() {
        repeat(1000) {
            val code = JoinCode.random()
            assertTrue(code, rulesPattern.matches(code))
            assertTrue(code, JoinCode.isValid(code))
        }
    }

    @Test
    fun alphabetIsExactlyWhatTheRuleAllows() {
        val allowed = (('A'..'Z') + ('0'..'9')).filter { rulesPattern.matches("$it".repeat(6)) }
        assertEquals(allowed.joinToString(""), JoinCode.ALPHABET)
    }

    @Test
    fun normalizesTypedAndPastedInput() {
        assertEquals("K7M4QX", JoinCode.normalize(" k7m-4qx "))
        assertTrue(JoinCode.isValid(JoinCode.normalize("k7m 4qx")))
    }

    @Test
    fun rejectsLookAlikesAndWrongLength() {
        assertFalse(JoinCode.isValid("K0M4QX"))
        assertFalse(JoinCode.isValid("KOM4QX"))
        assertFalse(JoinCode.isValid("K1M4QX"))
        assertFalse(JoinCode.isValid("KIM4QX"))
        assertFalse(JoinCode.isValid("KLM4QX"))
        assertFalse(JoinCode.isValid("K7M4Q"))
    }

    @Test
    fun formatsForDisplayAndSpeech() {
        assertEquals("K7M-4QX", JoinCode.display("K7M4QX"))
        assertEquals("K, 7, M, 4, Q, X", JoinCode.spoken("K7M4QX"))
    }

    @Test
    fun readsCodesFromJoinLinks() {
        assertEquals("A5AXM2", JoinCode.fromLinkPath("/join/A5AXM2"))
        assertEquals("A5AXM2", JoinCode.fromLinkPath("/join/a5a-xm2/"))
        assertEquals("K7M4QX", JoinCode.link("K7M4QX").let { JoinCode.fromLinkPath(java.net.URI(it).path) })
    }

    @Test
    fun ignoresPathsThatAreNotJoinLinks() {
        assertEquals(null, JoinCode.fromLinkPath(null))
        assertEquals(null, JoinCode.fromLinkPath("/"))
        assertEquals(null, JoinCode.fromLinkPath("/join/"))
        assertEquals(null, JoinCode.fromLinkPath("/join/K0M4QX"))
        assertEquals(null, JoinCode.fromLinkPath("/other/K7M4QX"))
    }
}
