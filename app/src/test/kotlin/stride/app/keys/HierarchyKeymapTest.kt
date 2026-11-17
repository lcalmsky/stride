package stride.app.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HierarchyKeymapTest {
    private val keymap = MacKeymap.keymap

    @Test
    fun `the six hierarchy strokes resolve in the editor`() {
        assertEquals(ActionId.GOTO_IMPLEMENTATION, action("meta alt B", KeyContext.EDITOR))
        assertEquals(ActionId.GOTO_TYPE_DECLARATION, action("meta shift B", KeyContext.EDITOR))
        assertEquals(ActionId.GOTO_TYPE_DECLARATION, action("control shift B", KeyContext.EDITOR))
        assertEquals(ActionId.GOTO_SUPER_METHOD, action("meta U", KeyContext.EDITOR))
        assertEquals(ActionId.TYPE_HIERARCHY, action("control H", KeyContext.EDITOR))
        assertEquals(ActionId.CALL_HIERARCHY, action("control alt H", KeyContext.EDITOR))
    }

    @Test
    fun `modifier order does not change the stroke`() {
        val strokes = listOf(
            "alt meta B" to "meta alt B",
            "shift meta B" to "meta shift B",
            "shift control B" to "control shift B",
            "alt control H" to "control alt H",
        )

        for ((written, canonical) in strokes) {
            assertEquals(KeyStroke.parse(canonical), KeyStroke.parse(written))
            assertEquals(keymap.actionFor(KeyStroke.parse(canonical), KeyContext.EDITOR), action(written, KeyContext.EDITOR))
        }
    }

    @Test
    fun `the hierarchy strokes do not fire in the global or modal contexts`() {
        assertEquals(ActionId.GOTO_IMPLEMENTATION, action("meta alt B", KeyContext.EDITOR))
        assertNull(action("meta alt B", KeyContext.GLOBAL))
        assertNull(action("control H", KeyContext.LIST_WINDOW))
        assertNull(action("control alt H", KeyContext.SEARCH_POPUP))
    }

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)
}
