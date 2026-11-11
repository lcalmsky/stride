package stride.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.KeyStroke
import stride.app.keys.MacKeymap
import stride.app.keys.formatKeyStroke

class WelcomeHintsTest {
    private val keymap = MacKeymap.keymap

    @Test
    fun `every hint with a project shows the stroke the keymap binds`() {
        val hints = welcomeHints(keymap, hasProject = true)
        assertEquals(
            listOf("Search Everywhere", "Go to File", "Recent Files", "Find Action"),
            hints.map { it.label },
        )
        for (hint in hints) {
            if (hint.action == ActionId.SEARCH_EVERYWHERE) {
                assertTrue(keymap.strokesFor(hint.action).isEmpty(), "Double Shift is not a keymap stroke")
                assertEquals(KeyRouter.SEARCH_EVERYWHERE_GESTURE, hint.keys)
                continue
            }
            val stroke = keymap.strokesFor(hint.action).single()
            assertEquals(formatKeyStroke(stroke), hint.keys, "${hint.label} must show the keymap stroke")
            assertEquals(hint.action, keymap.actionFor(stroke, KeyContext.GLOBAL))
        }
        assertEquals(listOf("⇧⇧", "⇧⌘O", "⌘E", "⇧⌘A"), hints.map { it.keys })
    }

    @Test
    fun `without a project the open folder hint shows the stroke that opens a folder`() {
        val hints = welcomeHints(keymap, hasProject = false)
        assertEquals(listOf("Open Folder"), hints.map { it.label })
        val hint = hints.single()
        val stroke = keymap.strokesFor(hint.action).single()
        assertEquals("⌘O", formatKeyStroke(stroke))
        assertEquals(hint.keys, formatKeyStroke(stroke))
        // Command-O is Go to Class with a project and opens a folder without one.
        assertEquals(ActionId.GOTO_CLASS, keymap.actionFor(KeyStroke.parse("meta O"), KeyContext.GLOBAL))
        assertEquals(ActionId.GOTO_CLASS, hint.action)
    }
}
