package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardHistoryTest {
    @Test
    fun `the newest text comes first`() {
        val history = ClipboardHistory()

        history.record("one")
        history.record("two")

        assertEquals(listOf("two", "one"), history.items)
    }

    @Test
    fun `recording the same text again moves it to the front without a duplicate`() {
        val history = ClipboardHistory()

        history.record("one")
        history.record("two")
        history.record("one")

        assertEquals(listOf("one", "two"), history.items)
    }

    @Test
    fun `the list keeps at most twenty entries`() {
        val history = ClipboardHistory()

        for (index in 1..25) history.record("entry $index")

        assertEquals(ClipboardHistory.MAX_ITEMS, history.items.size)
        assertEquals("entry 25", history.items.first())
        assertEquals("entry 6", history.items.last())
    }

    @Test
    fun `blank text is not recorded`() {
        val history = ClipboardHistory()

        history.record("")
        history.record("   \n\t")

        assertTrue(history.items.isEmpty())
    }

    @Test
    fun `clear empties the list`() {
        val history = ClipboardHistory()
        history.record("one")

        history.clear()

        assertTrue(history.items.isEmpty())
    }
}
