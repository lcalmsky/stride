package stride.app.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyStroke
import stride.app.keys.MacKeymap

class GenerateKeymapTest {
    private val keymap = MacKeymap.keymap

    @Test
    fun `the four generate strokes resolve in the editor`() {
        assertEquals(ActionId.GENERATE, action("meta N", KeyContext.EDITOR))
        assertEquals(ActionId.GENERATE, action("control ENTER", KeyContext.EDITOR))
        assertEquals(ActionId.OVERRIDE_METHODS, action("control O", KeyContext.EDITOR))
        assertEquals(ActionId.IMPLEMENT_METHODS, action("control I", KeyContext.EDITOR))
    }

    @Test
    fun `no other binding shares a generate stroke`() {
        val expected = mapOf(
            KeyStroke.parse("meta N") to ActionId.GENERATE,
            KeyStroke.parse("control ENTER") to ActionId.GENERATE,
            KeyStroke.parse("control O") to ActionId.OVERRIDE_METHODS,
            KeyStroke.parse("control I") to ActionId.IMPLEMENT_METHODS,
        )
        for ((stroke, action) in expected) {
            val bindings = keymap.bindings[stroke].orEmpty()
            assertEquals(1, bindings.size, "$stroke is bound ${bindings.size} times")
            assertEquals(action, bindings.single().action)
            assertEquals(KeyContext.EDITOR, bindings.single().context)
        }
    }

    @Test
    fun `the strokes stay out of the global context, the terminal and popups`() {
        assertNull(action("meta N", KeyContext.GLOBAL))
        assertNull(action("control ENTER", KeyContext.GLOBAL))
        assertNull(action("control O", KeyContext.TERMINAL))
        assertNull(action("control I", KeyContext.CONTEXT_POPUP))
        assertNull(action("meta N", KeyContext.SEARCH_POPUP))
    }

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)
}
