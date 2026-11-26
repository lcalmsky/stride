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

    @Test
    fun `the menu column shows a stroke only where it runs that action`() {
        assertEquals("⌫", menuShortcutText(MacKeymap.keymap, ActionId.DELETE_ELEMENT, KeyContext.PROJECT_TREE))
        assertEquals("⇧⌘C", menuShortcutText(MacKeymap.keymap, ActionId.COPY_PATHS, KeyContext.PROJECT_TREE))
        // ⌥⌘Z is Rollback Lines in the editor, so Rollback… shows no key there.
        assertEquals("", menuShortcutText(MacKeymap.keymap, ActionId.ROLLBACK_CHANGES, KeyContext.EDITOR))
        assertEquals(
            "⌥⌘Z",
            menuShortcutText(MacKeymap.keymap, ActionId.ROLLBACK_CHANGES, KeyContext.COMMIT_WINDOW),
        )
        // ⌘D is bound in the Commit window only.
        assertEquals("", menuShortcutText(MacKeymap.keymap, ActionId.SHOW_DIFF, KeyContext.LIST_WINDOW))
        assertEquals("⌘D", menuShortcutText(MacKeymap.keymap, ActionId.SHOW_DIFF, KeyContext.COMMIT_WINDOW))
    }

    @Test
    fun `the menu column follows the keymap's platform`() {
        val keymap = Keymap.forPlatform(false)
        assertEquals(
            "Shift+Ctrl+C",
            menuShortcutText(keymap, ActionId.COPY_PATHS, KeyContext.PROJECT_TREE),
        )
        // Only the modifiers change with the platform; the key labels stay the app's glyphs.
        assertEquals("⌫", menuShortcutText(keymap, ActionId.DELETE_ELEMENT, KeyContext.PROJECT_TREE))
    }
}
