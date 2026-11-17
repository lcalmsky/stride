package stride.app.problems

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LineRemapTest {
    @Test
    fun `lines before and after the changed span keep their offset from the edges`() {
        val remap = LineRemap.of(
            listOf("a", "b", "c", "d"),
            listOf("a", "X", "c", "d"),
        )

        assertEquals(0, remap.map(0))
        assertEquals(2, remap.map(2))
        assertEquals(3, remap.map(3))
        assertNull(remap.map(1))
    }

    @Test
    fun `an inserted line moves the lines after it`() {
        val remap = LineRemap.of(
            listOf("a", "b", "c"),
            listOf("a", "X", "b", "c"),
        )

        assertEquals(0, remap.map(0))
        assertEquals(2, remap.map(1))
        assertEquals(3, remap.map(2))
    }

    @Test
    fun `a line unique on both sides of the changed span follows its text`() {
        val remap = LineRemap.of(
            listOf("a", "target", "c", "d"),
            listOf("a", "x", "target", "d"),
        )

        assertEquals(2, remap.map(1))
        assertNull(remap.map(2))
    }

    @Test
    fun `a duplicated line is hidden`() {
        val remap = LineRemap.of(
            listOf("x", "dup", "dup", "tail"),
            listOf("y", "dup", "dup", "end"),
        )

        assertNull(remap.map(1))
        assertNull(remap.map(2))
    }

    @Test
    fun `a deleted line is hidden`() {
        val remap = LineRemap.of(
            listOf("a", "gone", "b"),
            listOf("a", "b"),
        )

        assertNull(remap.map(1))
        assertEquals(1, remap.map(2))
    }

    @Test
    fun `identical texts map every line to itself`() {
        val text = "one\ntwo\nthree\n"
        val remap = LineRemap.of(text, text)

        assertEquals(listOf(0, 1, 2, 3), (0..3).map { remap.map(it) })
        assertNull(remap.map(4))
        assertNull(remap.map(-1))
    }
}
