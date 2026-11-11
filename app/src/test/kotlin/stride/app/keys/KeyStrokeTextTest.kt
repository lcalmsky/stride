package stride.app.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyStrokeTextTest {
    @Test
    fun `formats modifiers in the macOS glyph order`() {
        assertEquals("⌘S", formatKeyStroke(KeyStroke.parse("meta S")))
        assertEquals("⇧⌘O", formatKeyStroke(KeyStroke.parse("meta shift O")))
        assertEquals("⌥⇧↑", formatKeyStroke(KeyStroke.parse("alt shift UP")))
        assertEquals("⌃R", formatKeyStroke(KeyStroke.parse("control R")))
        assertEquals("Esc", formatKeyStroke(KeyStroke.parse("ESCAPE")))
    }

    @Test
    fun `formats platform modifier labels when the keymap is not the macOS one`() {
        assertEquals("Ctrl+S", formatKeyStroke(KeyStroke.parse("control S"), macOs = false))
        assertEquals("Shift+Ctrl+O", formatKeyStroke(KeyStroke.parse("control shift O"), macOs = false))
        assertEquals("Shift+Alt+↑", formatKeyStroke(KeyStroke.parse("alt shift UP"), macOs = false))
        assertEquals("Esc", formatKeyStroke(KeyStroke.parse("ESCAPE"), macOs = false))
    }

    @Test
    fun `the first bound stroke of an action is its shortcut`() {
        assertEquals("⌘S", shortcutText(MacKeymap.keymap, ActionId.SAVE))
        assertEquals("⌘O", shortcutText(MacKeymap.keymap, ActionId.GOTO_CLASS))
        assertEquals("⇧⌘O", shortcutText(MacKeymap.keymap, ActionId.GOTO_FILE))
        assertEquals("⌥⌘O", shortcutText(MacKeymap.keymap, ActionId.GOTO_SYMBOL))
        assertEquals("⇧⌘A", shortcutText(MacKeymap.keymap, ActionId.GOTO_ACTION))
        assertEquals("", shortcutText(MacKeymap.keymap, ActionId.OPEN_FOLDER))
    }
}
