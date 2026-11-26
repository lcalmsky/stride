package stride.app.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeymapTest {
    private val keymap = MacKeymap.keymap

    @Test
    fun `the navigation bindings resolve in their contexts`() {
        // RecentLocations meta shift E
        assertEquals(ActionId.RECENT_LOCATIONS, action("meta shift E", KeyContext.GLOBAL))
        assertEquals(ActionId.RECENT_LOCATIONS, action("meta shift E", KeyContext.EDITOR))
        // ShowNavBar alt HOME; meta UP only in a list window
        assertEquals(ActionId.SHOW_NAV_BAR, action("alt HOME", KeyContext.GLOBAL))
        assertEquals(ActionId.SHOW_NAV_BAR, action("alt HOME", KeyContext.EDITOR))
        assertEquals(ActionId.SHOW_NAV_BAR, action("meta UP", KeyContext.LIST_WINDOW))
        // EditSource F4; meta DOWN only in a list window
        assertEquals(ActionId.EDIT_SOURCE, action("F4", KeyContext.GLOBAL))
        assertEquals(ActionId.EDIT_SOURCE, action("F4", KeyContext.EDITOR))
        assertEquals(ActionId.EDIT_SOURCE, action("meta DOWN", KeyContext.LIST_WINDOW))
    }

    @Test
    fun `command up and command down stay unbound in the editor and global`() {
        assertNull(action("meta UP", KeyContext.EDITOR))
        assertNull(action("meta DOWN", KeyContext.EDITOR))
        assertNull(action("meta UP", KeyContext.GLOBAL))
        assertNull(action("meta DOWN", KeyContext.GLOBAL))
        // The navigation bar owns the keyboard like the other modal popups.
        assertNull(action("meta shift E", KeyContext.NAV_BAR))
        assertNull(action("alt HOME", KeyContext.NAV_BAR))
        assertNull(action("F4", KeyContext.NAV_BAR))
    }

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
        assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action("shift ESCAPE", KeyContext.LIST_WINDOW))
        // ActivateBookmarksToolWindow meta 2, ActivateFindToolWindow meta 3, ActivateStructureToolWindow meta 7
        assertEquals(ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW, action("meta 2", KeyContext.GLOBAL))
        assertEquals(ActionId.ACTIVATE_FIND_TOOL_WINDOW, action("meta 3", KeyContext.GLOBAL))
        assertEquals(ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW, action("meta 7", KeyContext.GLOBAL))
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
        // ActivateProblemsViewToolWindow meta 6
        assertEquals(ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW, action("meta 6", KeyContext.GLOBAL))
        // GotoNextError F2, GotoPreviousError shift F2, ShowErrorDescription meta F1
        assertEquals(ActionId.GOTO_NEXT_ERROR, action("F2", KeyContext.EDITOR))
        assertEquals(ActionId.GOTO_PREVIOUS_ERROR, action("shift F2", KeyContext.EDITOR))
        assertEquals(ActionId.SHOW_ERROR_DESCRIPTION, action("meta F1", KeyContext.EDITOR))
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
        // MoveStatementUp meta shift UP and MoveStatementDown meta shift DOWN
        assertEquals(ActionId.MOVE_STATEMENT_UP, action("meta shift UP", KeyContext.EDITOR))
        assertEquals(ActionId.MOVE_STATEMENT_DOWN, action("meta shift DOWN", KeyContext.EDITOR))
        // EditorCompleteStatement meta shift ENTER
        assertEquals(ActionId.EDITOR_COMPLETE_STATEMENT, action("meta shift ENTER", KeyContext.EDITOR))
        // Unwrap meta shift DELETE
        assertEquals(ActionId.UNWRAP, action("meta shift DELETE", KeyContext.EDITOR))
        // ReformatCode meta alt L
        assertEquals(ActionId.REFORMAT_CODE, action("meta alt L", KeyContext.EDITOR))
        // EditorScrollToCenter control L
        assertEquals(ActionId.EDITOR_SCROLL_TO_CENTER, action("control L", KeyContext.EDITOR))
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
        // ReplaceInPath meta shift R
        assertEquals(ActionId.REPLACE_IN_PATH, action("meta shift R", KeyContext.GLOBAL))
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
        // EditorSelectWord alt UP
        assertEquals(ActionId.EDITOR_SELECT_WORD, action("alt UP", KeyContext.EDITOR))
        // EditorUnSelectWord alt DOWN
        assertEquals(ActionId.EDITOR_UNSELECT_WORD, action("alt DOWN", KeyContext.EDITOR))
        // EditorToggleCase control shift U (→meta)
        assertEquals(ActionId.EDITOR_TOGGLE_CASE, action("meta shift U", KeyContext.EDITOR))
        // EditorJoinLines control shift J
        assertEquals(ActionId.EDITOR_JOIN_LINES, action("control shift J", KeyContext.EDITOR))
        // EditorStartNewLine shift ENTER
        assertEquals(ActionId.EDITOR_START_NEW_LINE, action("shift ENTER", KeyContext.EDITOR))
        // EditorStartNewLineBefore control alt ENTER (→meta)
        assertEquals(ActionId.EDITOR_START_NEW_LINE_BEFORE, action("meta alt ENTER", KeyContext.EDITOR))
        // EditorMatchBrace control M
        assertEquals(ActionId.EDITOR_MATCH_BRACE, action("control M", KeyContext.EDITOR))
        // EditorCodeBlockStart meta alt OPEN_BRACKET
        assertEquals(ActionId.EDITOR_CODE_BLOCK_START, action("meta alt OPEN_BRACKET", KeyContext.EDITOR))
        // EditorCodeBlockEnd meta alt CLOSE_BRACKET
        assertEquals(ActionId.EDITOR_CODE_BLOCK_END, action("meta alt CLOSE_BRACKET", KeyContext.EDITOR))
        // MethodUp control shift UP and MethodDown control shift DOWN
        assertEquals(ActionId.METHOD_UP, action("control shift UP", KeyContext.EDITOR))
        assertEquals(ActionId.METHOD_DOWN, action("control shift DOWN", KeyContext.EDITOR))
        // HippieCompletion alt SLASH
        assertEquals(ActionId.HIPPIE_COMPLETION, action("alt SLASH", KeyContext.EDITOR))
        // CodeCompletion control SPACE; control alt SPACE avoids the system input-source switch
        assertEquals(ActionId.CODE_COMPLETION, action("control SPACE", KeyContext.EDITOR))
        assertEquals(ActionId.CODE_COMPLETION, action("control alt SPACE", KeyContext.EDITOR))
        // SmartTypeCompletion control shift SPACE
        assertEquals(ActionId.SMART_TYPE_COMPLETION, action("control shift SPACE", KeyContext.EDITOR))
        // OptimizeImports control alt O
        assertEquals(ActionId.OPTIMIZE_IMPORTS, action("control alt O", KeyContext.EDITOR))
        // PasteMultiple meta shift V
        assertEquals(ActionId.PASTE_MULTIPLE, action("meta shift V", KeyContext.EDITOR))
        // RenameElement shift F6
        assertEquals(ActionId.RENAME_ELEMENT, action("shift F6", KeyContext.EDITOR))
        // $Copy and $Cut take the whole line when nothing is selected
        assertEquals(ActionId.COPY_LINE, action("meta C", KeyContext.EDITOR))
        assertEquals(ActionId.CUT_LINE, action("meta X", KeyContext.EDITOR))
        // GotoDeclaration meta B
        assertEquals(ActionId.GOTO_DECLARATION, action("meta B", KeyContext.EDITOR))
        // Back and Forward keep the tab-switching strokes apart
        assertEquals(ActionId.BACK, action("meta OPEN_BRACKET", KeyContext.GLOBAL))
        assertEquals(ActionId.BACK, action("meta alt LEFT", KeyContext.GLOBAL))
        assertEquals(ActionId.FORWARD, action("meta CLOSE_BRACKET", KeyContext.GLOBAL))
        assertEquals(ActionId.FORWARD, action("meta alt RIGHT", KeyContext.GLOBAL))
        assertEquals(ActionId.PREVIOUS_TAB, action("meta shift OPEN_BRACKET", KeyContext.GLOBAL))
        assertEquals(ActionId.NEXT_TAB, action("meta shift CLOSE_BRACKET", KeyContext.GLOBAL))
        // FileStructurePopup control F12 (→meta)
        assertEquals(ActionId.FILE_STRUCTURE_POPUP, action("meta F12", KeyContext.EDITOR))
        // GotoTest control shift T (→meta)
        assertEquals(ActionId.GOTO_TEST, action("meta shift T", KeyContext.GLOBAL))
        // FindUsages alt F7
        assertEquals(ActionId.FIND_USAGES, action("alt F7", KeyContext.EDITOR))
        // ToggleBookmark F3 and ShowBookmarks meta F3
        assertEquals(ActionId.TOGGLE_BOOKMARK, action("F3", KeyContext.EDITOR))
        assertEquals(ActionId.SHOW_BOOKMARKS, action("meta F3", KeyContext.GLOBAL))
        // ActivateProjectToolWindow meta 1
        assertEquals(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, action("meta 1", KeyContext.GLOBAL))
        // HideAllWindows control shift F12 (→meta)
        assertEquals(ActionId.HIDE_ALL_WINDOWS, action("meta shift F12", KeyContext.GLOBAL))
        // CopyPaths control shift C (→meta)
        assertEquals(ActionId.COPY_PATHS, action("meta shift C", KeyContext.GLOBAL))
        // CopyReference control alt shift C (→meta)
        assertEquals(ActionId.COPY_REFERENCE, action("meta alt shift C", KeyContext.GLOBAL))
        // Switcher control TAB; control shift TAB steps up
        assertEquals(ActionId.SWITCHER, action("control TAB", KeyContext.GLOBAL))
        assertEquals(ActionId.SWITCHER, action("control shift TAB", KeyContext.GLOBAL))
        // SelectInProjectView has no macOS default stroke; alt F1 reveals the file
        assertEquals(ActionId.SELECT_IN_PROJECT_VIEW, action("alt F1", KeyContext.GLOBAL))
    }

    @Test
    fun `the peek and surround bindings resolve in the editor`() {
        // ParameterInfo meta P
        assertEquals(ActionId.PARAMETER_INFO, action("meta P", KeyContext.EDITOR))
        // QuickJavaDoc F1; control J
        assertEquals(ActionId.QUICK_DOC, action("F1", KeyContext.EDITOR))
        assertEquals(ActionId.QUICK_DOC, action("control J", KeyContext.EDITOR))
        // QuickImplementations alt SPACE
        assertEquals(ActionId.QUICK_DEFINITION, action("alt SPACE", KeyContext.EDITOR))
        // SurroundWith meta alt T
        assertEquals(ActionId.SURROUND_WITH, action("meta alt T", KeyContext.EDITOR))
        // None of them answer while a modal popup owns the keyboard.
        assertNull(action("meta P", KeyContext.CONTEXT_POPUP))
        assertNull(action("F1", KeyContext.CONTEXT_POPUP))
        assertNull(action("alt SPACE", KeyContext.CONTEXT_POPUP))
        assertNull(action("meta alt T", KeyContext.CONTEXT_POPUP))
    }

    @Test
    fun `actions without a stroke stay reachable from the actions tab`() {
        assertTrue(keymap.strokesFor(ActionId.REOPEN_CLOSED_TAB).isEmpty())
        assertTrue(keymap.strokesFor(ActionId.CLOSE_OTHER_TABS).isEmpty())
        // The clone gestures belong to KeyRouter, not the keymap.
        assertTrue(keymap.strokesFor(ActionId.EDITOR_CLONE_CARET_ABOVE).isEmpty())
        assertTrue(keymap.strokesFor(ActionId.EDITOR_CLONE_CARET_BELOW).isEmpty())
    }

    @Test
    fun `the occurrence and column bindings resolve in the editor`() {
        // SelectNextOccurrence control G
        assertEquals(ActionId.SELECT_NEXT_OCCURRENCE, action("control G", KeyContext.EDITOR))
        // UnselectPreviousOccurrence control shift G
        assertEquals(ActionId.UNSELECT_PREVIOUS_OCCURRENCE, action("control shift G", KeyContext.EDITOR))
        // SelectAllOccurrences meta control G
        assertEquals(ActionId.SELECT_ALL_OCCURRENCES, action("meta control G", KeyContext.EDITOR))
        // EditorToggleColumnMode shift meta 8
        assertEquals(ActionId.EDITOR_TOGGLE_COLUMN_MODE, action("shift meta 8", KeyContext.EDITOR))
        // FindNext and FindPrevious stay on their own strokes.
        assertEquals(ActionId.FIND_NEXT, action("meta G", KeyContext.EDITOR))
        assertEquals(ActionId.FIND_PREVIOUS, action("meta shift G", KeyContext.EDITOR))
    }

    @Test
    fun `the folding bindings resolve in the editor`() {
        // ExpandRegion meta ADD | meta EQUALS
        assertEquals(ActionId.EXPAND_REGION, action("meta ADD", KeyContext.EDITOR))
        assertEquals(ActionId.EXPAND_REGION, action("meta EQUALS", KeyContext.EDITOR))
        // CollapseRegion meta SUBTRACT | meta MINUS
        assertEquals(ActionId.COLLAPSE_REGION, action("meta SUBTRACT", KeyContext.EDITOR))
        assertEquals(ActionId.COLLAPSE_REGION, action("meta MINUS", KeyContext.EDITOR))
        // ExpandAllRegions shift meta ADD | shift meta EQUALS
        assertEquals(ActionId.EXPAND_ALL_REGIONS, action("meta shift ADD", KeyContext.EDITOR))
        assertEquals(ActionId.EXPAND_ALL_REGIONS, action("meta shift EQUALS", KeyContext.EDITOR))
        // CollapseAllRegions shift meta SUBTRACT | shift meta MINUS
        assertEquals(ActionId.COLLAPSE_ALL_REGIONS, action("meta shift SUBTRACT", KeyContext.EDITOR))
        assertEquals(ActionId.COLLAPSE_ALL_REGIONS, action("meta shift MINUS", KeyContext.EDITOR))
    }

    @Test
    fun `alt enter stays the context actions popup`() {
        assertEquals(ActionId.SHOW_INTENTION_ACTIONS, action("alt ENTER", KeyContext.EDITOR))
        assertNull(action("meta alt ENTER", KeyContext.CONTEXT_POPUP))
        assertNull(action("alt ENTER", KeyContext.CONTEXT_POPUP))
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
    fun `editor sub-fields take no editor binding but still reach global ones`() {
        assertNull(action("meta C", KeyContext.EDITOR_FIELD))
        assertNull(action("control SPACE", KeyContext.EDITOR_FIELD))
        assertNull(action("ESCAPE", KeyContext.EDITOR_FIELD))
        assertEquals(ActionId.SAVE, action("meta S", KeyContext.EDITOR_FIELD))
    }

    @Test
    fun `open folder keeps no stroke but stays an action`() {
        assertTrue(keymap.strokesFor(ActionId.OPEN_FOLDER).isEmpty())
    }

    @Test
    fun `the edit location and usage bindings resolve in their contexts`() {
        // JumpToLastChange meta shift BACK_SPACE
        assertEquals(ActionId.JUMP_TO_LAST_CHANGE, action("meta shift BACK_SPACE", KeyContext.GLOBAL))
        // ShowUsages meta alt F7
        assertEquals(ActionId.SHOW_USAGES, action("meta alt F7", KeyContext.EDITOR))
        // HighlightUsagesInFile meta shift F7
        assertEquals(ActionId.HIGHLIGHT_USAGES_IN_FILE, action("meta shift F7", KeyContext.EDITOR))
        // ⌘⌫ stays Delete Line.
        assertEquals(ActionId.EDITOR_DELETE_LINE, action("meta BACK_SPACE", KeyContext.EDITOR))
        // The editor-only bindings do not answer from the global context, and modal popups own no key.
        assertNull(action("meta alt F7", KeyContext.GLOBAL))
        assertNull(action("meta shift F7", KeyContext.GLOBAL))
        assertNull(action("meta alt F7", KeyContext.SEARCH_POPUP))
        assertNull(action("meta shift F7", KeyContext.CONTEXT_POPUP))
    }

    @Test
    fun `the project tree bindings resolve in their context`() {
        // New is meta N and control ENTER in the tree; the editor keeps Generate on both.
        assertEquals(ActionId.NEW_ELEMENT, action("meta N", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.NEW_ELEMENT, action("control ENTER", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.GENERATE, action("meta N", KeyContext.EDITOR))
        assertEquals(ActionId.GENERATE, action("control ENTER", KeyContext.EDITOR))
        // Rename and Move
        assertEquals(ActionId.RENAME_FILE, action("shift F6", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.RENAME_ELEMENT, action("shift F6", KeyContext.EDITOR))
        assertEquals(ActionId.MOVE_ELEMENT, action("F6", KeyContext.PROJECT_TREE))
        // Delete in the tree and the editor's own command delete
        assertEquals(ActionId.DELETE_ELEMENT, action("BACK_SPACE", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.DELETE_ELEMENT, action("DELETE", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.DELETE_ELEMENT, action("meta BACK_SPACE", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.EDITOR_DELETE_LINE, action("meta BACK_SPACE", KeyContext.EDITOR))
        // Copy, cut and paste
        assertEquals(ActionId.COPY_ELEMENT, action("F5", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.CUT_FILE, action("meta X", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.COPY_FILE, action("meta C", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.PASTE_FILE, action("meta V", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.CUT_LINE, action("meta X", KeyContext.EDITOR))
        assertEquals(ActionId.COPY_LINE, action("meta C", KeyContext.EDITOR))
        // A7: the editor's paste is a component action; the dispatcher declines the stroke.
        assertEquals(ActionId.PASTE, action("meta V", KeyContext.EDITOR))
        assertEquals(ActionId.COPY, action("meta C", KeyContext.TERMINAL))
        assertEquals(ActionId.PASTE, action("meta V", KeyContext.TERMINAL))
        // GLOBAL bindings still answer in the tree, which is not modal.
        assertEquals(ActionId.SAVE, action("meta S", KeyContext.PROJECT_TREE))
        assertEquals(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, action("meta 1", KeyContext.PROJECT_TREE))
    }

    @Test
    fun `no stroke has two bindings in the same context`() {
        for ((stroke, bindings) in keymap.bindings) {
            for ((context, inContext) in bindings.groupBy { it.context }) {
                assertEquals(1, inContext.size, "$stroke has ${inContext.size} bindings in $context")
            }
        }
    }

    @Test
    fun `non-mac swaps meta for control`() {
        val other = Keymap.forPlatform(false)

        assertEquals(ActionId.SAVE, other.actionFor(KeyStroke.parse("ctrl S"), KeyContext.GLOBAL))
        assertNull(other.actionFor(KeyStroke.parse("meta S"), KeyContext.GLOBAL))
    }
}
