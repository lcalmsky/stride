package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndoManagerTest {
    private var now = 0L
    private val manager = UndoManager(clock = { now }, limit = 10)

    @Test
    fun `consecutive characters merge into one entry`() {
        manager.record(insert(0, "a"))
        now += 100
        manager.record(insert(1, "b"))
        now += 100
        manager.record(insert(2, "c"))

        val entry = manager.undo()!!
        assertEquals(0, entry.offset)
        assertEquals("abc", entry.newText)
        assertFalse(manager.canUndo)
    }

    @Test
    fun `a whitespace character starts a new entry`() {
        manager.record(insert(0, "a"))
        manager.record(insert(1, " "))

        assertEquals(" ", manager.undo()!!.newText)
        assertEquals("a", manager.undo()!!.newText)
    }

    @Test
    fun `a gap longer than 500ms starts a new entry`() {
        manager.record(insert(0, "a"))
        now = 501
        manager.record(insert(1, "b"))

        assertEquals("b", manager.undo()!!.newText)
        assertEquals("a", manager.undo()!!.newText)
    }

    @Test
    fun `exactly 500ms still merges`() {
        manager.record(insert(0, "a"))
        now = 500
        manager.record(insert(1, "b"))

        assertEquals("ab", manager.undo()!!.newText)
        assertFalse(manager.canUndo)
    }

    @Test
    fun `deletions never merge`() {
        manager.record(UndoEntry(0, "a", "", TextRange(1), TextRange(0)))
        manager.record(UndoEntry(0, "b", "", TextRange(1), TextRange(0)))

        assertEquals("b", manager.undo()!!.oldText)
        assertEquals("a", manager.undo()!!.oldText)
    }

    @Test
    fun `recording after undo clears the redo stack`() {
        manager.record(insert(0, "a"))
        manager.undo()
        assertTrue(manager.canRedo)

        manager.record(insert(0, "b"))

        assertFalse(manager.canRedo)
    }

    @Test
    fun `break merge keeps the next change separate`() {
        manager.record(insert(0, "a"))
        manager.breakMerge()
        manager.record(insert(1, "b"))

        assertEquals("b", manager.undo()!!.newText)
        assertEquals("a", manager.undo()!!.newText)
    }

    @Test
    fun `undo and redo return the stored selections`() {
        val entry = UndoEntry(2, "", "x", TextRange(2), TextRange(3))
        manager.record(entry)

        assertEquals(entry, manager.undo())
        assertFalse(manager.canUndo)
        assertEquals(entry, manager.redo())
        assertFalse(manager.canRedo)
    }

    @Test
    fun `the oldest entry is dropped beyond the limit`() {
        val small = UndoManager(clock = { 0L }, limit = 2)
        small.record(insert(0, "a"))
        small.record(insert(0, "b"))
        small.record(insert(0, "c"))

        assertEquals("c", small.undo()!!.newText)
        assertEquals("b", small.undo()!!.newText)
        assertFalse(small.canUndo)
    }

    private fun insert(
        offset: Int,
        text: String,
        before: TextRange = TextRange(offset),
        after: TextRange = TextRange(offset + text.length),
    ): UndoEntry = UndoEntry(offset, "", text, before, after)
}
