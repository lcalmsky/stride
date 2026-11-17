package stride.app.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RefactoringKeymapTest {
    private val keymap = MacKeymap.keymap

    @Test
    fun `the four refactoring strokes resolve in the editor`() {
        assertEquals(ActionId.REFACTOR_THIS, action("control T", KeyContext.EDITOR))
        assertEquals(ActionId.INTRODUCE_VARIABLE, action("meta alt V", KeyContext.EDITOR))
        assertEquals(ActionId.EXTRACT_METHOD, action("meta alt M", KeyContext.EDITOR))
        assertEquals(ActionId.INLINE, action("meta alt N", KeyContext.EDITOR))
    }

    @Test
    fun `modifier order does not change the stroke`() {
        val strokes = listOf("alt meta V" to "meta alt V", "alt meta M" to "meta alt M", "alt meta N" to "meta alt N")

        for ((written, canonical) in strokes) {
            assertEquals(KeyStroke.parse(canonical), KeyStroke.parse(written))
            assertEquals(action(canonical, KeyContext.EDITOR), action(written, KeyContext.EDITOR))
        }
    }

    @Test
    fun `the strokes do not fire in the global context, the terminal or popups`() {
        assertNull(action("meta alt V", KeyContext.GLOBAL))
        assertNull(action("control T", KeyContext.TERMINAL))
        assertNull(action("meta alt M", KeyContext.CONTEXT_POPUP))
        assertNull(action("meta alt N", KeyContext.SEARCH_POPUP))
    }

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)
}
