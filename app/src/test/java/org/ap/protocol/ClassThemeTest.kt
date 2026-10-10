package org.ap.protocol

import org.ap.protocol.classroom.CLASS_THEMES
import org.ap.protocol.classroom.Classroom
import org.ap.protocol.classroom.themeIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassThemeTest {

    private fun classroom(id: String, theme: Int) = Classroom(id, "Physics", "t", "Dr X", "key", "K7M4QX", theme = theme)

    @Test
    fun keepsTheTeachersChoice() {
        assertEquals(5, classroom("abc", 5).themeIndex)
    }

    @Test
    fun classesWithoutAThemeGetAStableOneInRange() {
        // Includes ids whose hashCode is negative, and an out-of-range stored value
        listOf("abc", "zzzzzzzzzzzz", "Ab3xYz9Qw", "").forEach { id ->
            val first = classroom(id, -1).themeIndex
            assertTrue("$id -> $first", first in 0 until CLASS_THEMES)
            assertEquals(first, classroom(id, -1).themeIndex)
            assertEquals(first, classroom(id, 99).themeIndex)
        }
    }
}
