package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class BookmarksTest {
    private val path = Paths.get("/project/a.kt")

    private fun bookmark(line: Int, text: String = "line") = Bookmark(path, "a.kt", line, text)

    @Test
    fun `toggle adds and removes the line`() {
        val manager = BookmarkManager()

        assertTrue(manager.toggle(bookmark(2)))
        assertEquals(1, manager.entries.size)
        assertEquals(setOf(2), manager.linesOf(path))

        assertFalse(manager.toggle(bookmark(2)))
        assertTrue(manager.isEmpty())
    }

    @Test
    fun `lines of other files are left out`() {
        val manager = BookmarkManager()
        manager.toggle(bookmark(1))
        manager.toggle(Bookmark(Paths.get("/project/b.kt"), "b.kt", 4, "x"))

        assertEquals(setOf(1), manager.linesOf(path))
    }

    @Test
    fun `remove drops exactly the chosen bookmark`() {
        val manager = BookmarkManager()
        manager.toggle(bookmark(1))
        manager.toggle(bookmark(2))

        manager.remove(bookmark(1))

        assertEquals(listOf(2), manager.entries.map { it.line })
    }

    @Test
    fun `line and text helpers read the requested line`() {
        val text = "one\r\ntwo\r\nthree"

        assertEquals(1, BookmarkManager.lineIndex(text, text.indexOf("two")))
        assertEquals("two", BookmarkManager.lineText(text, 1))
        assertEquals("three", BookmarkManager.lineText(text, 2))
        assertEquals("", BookmarkManager.lineText(text, 9))
    }
}
