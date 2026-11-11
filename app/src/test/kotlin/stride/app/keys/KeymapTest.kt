package stride.app.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeymapTest {
    private val keymap = MacKeymap.keymap

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)

    @Test
    fun `every macOS binding resolves in its context`() {
        // GotoClass meta O; OPEN_FOLDER has no stroke and is reachable from the Actions tab.
        assertEquals(ActionId.GOTO_CLASS, action("meta O", KeyContext.GLOBAL))
        // SaveAll control S (→meta)
        assertEquals(ActionId.SAVE, action("meta S", KeyContext.GLOBAL))
        // CloseContent meta W
        assertEquals(ActionId.CLOSE_TAB, action("meta W", KeyContext.GLOBAL))
        // NextTab
        assertEquals(ActionId.NEXT_TAB, action("meta shift CLOSE_BRACKET", KeyContext.GLOBAL))
        assertEquals(ActionId.NEXT_TAB, action("control RIGHT", KeyContext.GLOBAL))
        // PreviousTab
        assertEquals(ActionId.PREVIOUS_TAB, action("meta shift OPEN_BRACKET", KeyContext.GLOBAL))
        assertEquals(ActionId.PREVIOUS_TAB, action("control LEFT", KeyContext.GLOBAL))
        // HideActiveWindow shift ESCAPE
        assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action("shift ESCAPE", KeyContext.RUN_WINDOW))
        assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action("shift ESCAPE", KeyContext.TERMINAL))
        // Run control R
        assertEquals(ActionId.RUN, action("control R", KeyContext.GLOBAL))
        // RunClass control shift R
        assertEquals(ActionId.RUN_CONTEXT, action("control shift R", KeyContext.EDITOR))
        // Stop control F2 (→meta)
        assertEquals(ActionId.STOP, action("meta F2", KeyContext.GLOBAL))
        // Rerun meta R
        assertEquals(ActionId.RERUN, action("meta R", KeyContext.RUN_WINDOW))
        // ActivateRunToolWindow meta 4
        assertEquals(ActionId.ACTIVATE_RUN_TOOL_WINDOW, action("meta 4", KeyContext.GLOBAL))
        // EditorDuplicate control D (→meta)
        assertEquals(ActionId.EDITOR_DUPLICATE, action("meta D", KeyContext.EDITOR))
        // EditorDeleteLine meta BACK_SPACE
        assertEquals(ActionId.EDITOR_DELETE_LINE, action("meta BACK_SPACE", KeyContext.EDITOR))
        // CommentByLineComment control SLASH; control DIVIDE (→meta)
        assertEquals(ActionId.COMMENT_LINE, action("meta SLASH", KeyContext.EDITOR))
        assertEquals(ActionId.COMMENT_LINE, action("meta DIVIDE", KeyContext.EDITOR))
        // CommentByBlockComment meta alt SLASH
        assertEquals(ActionId.COMMENT_BLOCK, action("meta alt SLASH", KeyContext.EDITOR))
        // MoveLineUp alt shift UP
        assertEquals(ActionId.MOVE_LINE_UP, action("alt shift UP", KeyContext.EDITOR))
        // MoveLineDown alt shift DOWN
        assertEquals(ActionId.MOVE_LINE_DOWN, action("alt shift DOWN", KeyContext.EDITOR))
        // GotoLine meta L
        assertEquals(ActionId.GOTO_LINE, action("meta L", KeyContext.EDITOR))
        // $Undo meta Z
        assertEquals(ActionId.UNDO, action("meta Z", KeyContext.EDITOR))
        // $Redo control shift Z (→meta)
        assertEquals(ActionId.REDO, action("meta shift Z", KeyContext.EDITOR))
        // EditorIndentSelection TAB
        assertEquals(ActionId.INDENT, action("TAB", KeyContext.EDITOR))
        // EditorUnindentSelection shift TAB
        assertEquals(ActionId.UNINDENT, action("shift TAB", KeyContext.EDITOR))
        // EditorEscape ESCAPE
        assertEquals(ActionId.EDITOR_ESCAPE, action("ESCAPE", KeyContext.EDITOR))
        // Find meta F
        assertEquals(ActionId.FIND, action("meta F", KeyContext.EDITOR))
        // Replace control R (→meta)
        assertEquals(ActionId.REPLACE, action("meta R", KeyContext.EDITOR))
        // FindNext meta G
        assertEquals(ActionId.FIND_NEXT, action("meta G", KeyContext.EDITOR))
        assertEquals(ActionId.FIND_NEXT, action("meta G", KeyContext.FIND_BAR))
        // FindPrevious meta shift G
        assertEquals(ActionId.FIND_PREVIOUS, action("meta shift G", KeyContext.EDITOR))
        assertEquals(ActionId.FIND_PREVIOUS, action("meta shift G", KeyContext.FIND_BAR))
        // FindInPath meta shift F
        assertEquals(ActionId.FIND_IN_PATH, action("meta shift F", KeyContext.GLOBAL))
        // GotoFile meta shift O
        assertEquals(ActionId.GOTO_FILE, action("meta shift O", KeyContext.GLOBAL))
        // GotoSymbol meta alt O
        assertEquals(ActionId.GOTO_SYMBOL, action("meta alt O", KeyContext.GLOBAL))
        // GotoAction control shift A (→meta)
        assertEquals(ActionId.GOTO_ACTION, action("meta shift A", KeyContext.GLOBAL))
        // RecentFiles control E (→meta)
        assertEquals(ActionId.RECENT_FILES, action("meta E", KeyContext.GLOBAL))
        // ActivateTerminalToolWindow alt F12
        assertEquals(ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW, action("alt F12", KeyContext.GLOBAL))
        // ShowIntentionActions alt ENTER
        assertEquals(ActionId.SHOW_INTENTION_ACTIONS, action("alt ENTER", KeyContext.EDITOR))
        // ChooseRunConfiguration control alt R
        assertEquals(ActionId.CHOOSE_RUN_CONFIGURATION, action("control alt R", KeyContext.GLOBAL))
        // CompileDirty control F9 (→meta)
        assertEquals(ActionId.BUILD_PROJECT, action("meta F9", KeyContext.GLOBAL))
    }

    @Test
    fun `the context wins over other bindings and missing contexts stay null`() {
        // Replace is the editor's meta R; Rerun is the run window's
        assertEquals(ActionId.REPLACE, action("meta R", KeyContext.EDITOR))
        assertEquals(ActionId.RERUN, action("meta R", KeyContext.RUN_WINDOW))
        assertNull(action("meta R", KeyContext.GLOBAL))

        // EditorDuplicate is editor-only
        assertEquals(ActionId.EDITOR_DUPLICATE, action("meta D", KeyContext.EDITOR))
        assertNull(action("meta D", KeyContext.TERMINAL))
        assertNull(action("meta D", KeyContext.GLOBAL))

        // SaveAll is global and answers from every context
        assertEquals(ActionId.SAVE, action("meta S", KeyContext.EDITOR))
        assertEquals(ActionId.SAVE, action("meta S", KeyContext.TERMINAL))
    }

    @Test
    fun `modal popups never fall back to global bindings`() {
        assertNull(action("control R", KeyContext.CONTEXT_POPUP))
        assertNull(action("meta F9", KeyContext.CONTEXT_POPUP))
        assertNull(action("meta 4", KeyContext.CONTEXT_POPUP))
        assertNull(action("control R", KeyContext.SEARCH_POPUP))
        assertNull(action("meta shift O", KeyContext.SEARCH_POPUP))
        // Escape is not a keymap binding: the popup itself closes on it.
        assertNull(action("ESCAPE", KeyContext.CONTEXT_POPUP))
        assertNull(action("ESCAPE", KeyContext.SEARCH_POPUP))
    }

    @Test
    fun `open folder keeps no stroke but stays an action`() {
        assertTrue(keymap.strokesFor(ActionId.OPEN_FOLDER).isEmpty())
    }

    @Test
    fun `non-mac swaps meta for control`() {
        val other = Keymap.forPlatform(false)

        assertEquals(ActionId.SAVE, other.actionFor(KeyStroke.parse("ctrl S"), KeyContext.GLOBAL))
        assertNull(other.actionFor(KeyStroke.parse("meta S"), KeyContext.GLOBAL))
    }
}
