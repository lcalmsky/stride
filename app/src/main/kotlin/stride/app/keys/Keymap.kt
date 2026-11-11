package stride.app.keys

enum class ActionId {
    OPEN_FOLDER, SAVE, CLOSE_TAB, SEARCH_EVERYWHERE, NEXT_TAB, PREVIOUS_TAB, HIDE_ACTIVE_WINDOW,
    RUN, RUN_CONTEXT, STOP, RERUN, ACTIVATE_RUN_TOOL_WINDOW, SHOW_INTENTION_ACTIONS,
    CHOOSE_RUN_CONFIGURATION, BUILD_PROJECT,
    EDITOR_DUPLICATE, EDITOR_DELETE_LINE, COMMENT_LINE, COMMENT_BLOCK, MOVE_LINE_UP, MOVE_LINE_DOWN,
    GOTO_LINE, UNDO, REDO, INDENT, UNINDENT, EDITOR_ESCAPE,
    FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, FIND_IN_PATH, GOTO_FILE, GOTO_CLASS, GOTO_SYMBOL,
    GOTO_ACTION, RECENT_FILES, ACTIVATE_TERMINAL_TOOL_WINDOW,
    EDITOR_SELECT_WORD, EDITOR_UNSELECT_WORD, EDITOR_TOGGLE_CASE, EDITOR_JOIN_LINES,
    EDITOR_START_NEW_LINE, EDITOR_START_NEW_LINE_BEFORE, EDITOR_MATCH_BRACE,
    EDITOR_CODE_BLOCK_START, EDITOR_CODE_BLOCK_END, METHOD_UP, METHOD_DOWN,
    HIPPIE_COMPLETION, CODE_COMPLETION, RENAME_ELEMENT, COPY_LINE, CUT_LINE,
    GOTO_DECLARATION, BACK, FORWARD, FILE_STRUCTURE_POPUP, GOTO_TEST, FIND_USAGES,
    TOGGLE_BOOKMARK, SHOW_BOOKMARKS, ACTIVATE_PROJECT_TOOL_WINDOW, HIDE_ALL_WINDOWS,
    COPY_PATHS, COPY_REFERENCE, SWITCHER, SELECT_IN_PROJECT_VIEW, REOPEN_CLOSED_TAB, CLOSE_OTHER_TABS,
    TOGGLE_UI_FONT,
}

enum class KeyContext {
    GLOBAL, EDITOR, EDITOR_FIELD, FIND_BAR, SEARCH_POPUP, RUN_WINDOW, TERMINAL, CONTEXT_POPUP,
}

data class Binding(val action: ActionId, val context: KeyContext)

class Keymap(val bindings: Map<KeyStroke, List<Binding>>) {
    /**
     * The binding in [focus]'s context first, otherwise a GLOBAL binding, otherwise `null`.
     * Modal popups own the keyboard, so GLOBAL never answers for them.
     */
    fun actionFor(stroke: KeyStroke, focus: KeyContext): ActionId? {
        val candidates = bindings[stroke] ?: return null
        candidates.firstOrNull { it.context == focus }?.let { return it.action }
        if (focus in MODAL_CONTEXTS) return null
        return candidates.firstOrNull { it.context == KeyContext.GLOBAL }?.action
    }

    fun strokesFor(action: ActionId): List<KeyStroke> =
        bindings.filterValues { list -> list.any { it.action == action } }.keys.toList()

    companion object {
        /** Contexts whose popup handles every key itself; GLOBAL must not fire through them. */
        private val MODAL_CONTEXTS = setOf(KeyContext.SEARCH_POPUP, KeyContext.CONTEXT_POPUP)

        fun forPlatform(isMac: Boolean): Keymap =
            if (isMac) {
                MacKeymap.keymap
            } else {
                Keymap(
                    MacKeymap.keymap.bindings.mapKeys { (stroke, _) ->
                        stroke.copy(meta = stroke.ctrl, ctrl = stroke.meta)
                    },
                )
            }
    }
}

/** The macOS default keymap as key stroke → actions. */
object MacKeymap {
    val keymap: Keymap = Keymap(macBindings())
}

private fun macBindings(): Map<KeyStroke, List<Binding>> = buildMap {
    fun bind(stroke: String, action: ActionId, vararg contexts: KeyContext) {
        val parsed = KeyStroke.parse(stroke)
        put(parsed, (get(parsed).orEmpty()) + contexts.map { Binding(action, it) })
    }

    // GotoClass meta O; OPEN_FOLDER has no stroke and is reachable from the Actions tab.
    bind("meta O", ActionId.GOTO_CLASS, KeyContext.GLOBAL)
    // SaveAll control S (→meta)
    bind("meta S", ActionId.SAVE, KeyContext.GLOBAL)
    // CloseContent meta W
    bind("meta W", ActionId.CLOSE_TAB, KeyContext.GLOBAL)
    // NextTab
    bind("meta shift CLOSE_BRACKET", ActionId.NEXT_TAB, KeyContext.GLOBAL)
    bind("control RIGHT", ActionId.NEXT_TAB, KeyContext.GLOBAL)
    // PreviousTab
    bind("meta shift OPEN_BRACKET", ActionId.PREVIOUS_TAB, KeyContext.GLOBAL)
    bind("control LEFT", ActionId.PREVIOUS_TAB, KeyContext.GLOBAL)
    // HideActiveWindow shift ESCAPE
    bind("shift ESCAPE", ActionId.HIDE_ACTIVE_WINDOW, KeyContext.RUN_WINDOW, KeyContext.TERMINAL)
    // Run control R
    bind("control R", ActionId.RUN, KeyContext.GLOBAL)
    // RunClass control shift R (the last RunClass declaration)
    bind("control shift R", ActionId.RUN_CONTEXT, KeyContext.EDITOR)
    // Stop control F2 (→meta)
    bind("meta F2", ActionId.STOP, KeyContext.GLOBAL)
    // Rerun meta R; the editor's meta R is Replace
    bind("meta R", ActionId.RERUN, KeyContext.RUN_WINDOW)
    // ActivateRunToolWindow meta 4
    bind("meta 4", ActionId.ACTIVATE_RUN_TOOL_WINDOW, KeyContext.GLOBAL)
    // EditorDuplicate control D (→meta)
    bind("meta D", ActionId.EDITOR_DUPLICATE, KeyContext.EDITOR)
    // EditorDeleteLine meta BACK_SPACE
    bind("meta BACK_SPACE", ActionId.EDITOR_DELETE_LINE, KeyContext.EDITOR)
    // CommentByLineComment control SLASH; control DIVIDE (→meta)
    bind("meta SLASH", ActionId.COMMENT_LINE, KeyContext.EDITOR)
    bind("meta DIVIDE", ActionId.COMMENT_LINE, KeyContext.EDITOR)
    // CommentByBlockComment meta alt SLASH (first entry only)
    bind("meta alt SLASH", ActionId.COMMENT_BLOCK, KeyContext.EDITOR)
    // MoveLineUp alt shift UP
    bind("alt shift UP", ActionId.MOVE_LINE_UP, KeyContext.EDITOR)
    // MoveLineDown alt shift DOWN
    bind("alt shift DOWN", ActionId.MOVE_LINE_DOWN, KeyContext.EDITOR)
    // GotoLine meta L
    bind("meta L", ActionId.GOTO_LINE, KeyContext.EDITOR)
    // $Undo meta Z
    bind("meta Z", ActionId.UNDO, KeyContext.EDITOR)
    // $Redo control shift Z (→meta)
    bind("meta shift Z", ActionId.REDO, KeyContext.EDITOR)
    // EditorIndentSelection TAB
    bind("TAB", ActionId.INDENT, KeyContext.EDITOR)
    // EditorUnindentSelection shift TAB
    bind("shift TAB", ActionId.UNINDENT, KeyContext.EDITOR)
    // EditorEscape ESCAPE
    bind("ESCAPE", ActionId.EDITOR_ESCAPE, KeyContext.EDITOR)
    // Find meta F
    bind("meta F", ActionId.FIND, KeyContext.EDITOR)
    // Replace control R (→meta)
    bind("meta R", ActionId.REPLACE, KeyContext.EDITOR)
    // FindNext meta G (last declaration)
    bind("meta G", ActionId.FIND_NEXT, KeyContext.EDITOR, KeyContext.FIND_BAR)
    // FindPrevious meta shift G (last declaration)
    bind("meta shift G", ActionId.FIND_PREVIOUS, KeyContext.EDITOR, KeyContext.FIND_BAR)
    // FindInPath meta shift F
    bind("meta shift F", ActionId.FIND_IN_PATH, KeyContext.GLOBAL)
    // GotoFile meta shift O
    bind("meta shift O", ActionId.GOTO_FILE, KeyContext.GLOBAL)
    // GotoSymbol meta alt O
    bind("meta alt O", ActionId.GOTO_SYMBOL, KeyContext.GLOBAL)
    // GotoAction control shift A (→meta)
    bind("meta shift A", ActionId.GOTO_ACTION, KeyContext.GLOBAL)
    // RecentFiles control E (→meta)
    bind("meta E", ActionId.RECENT_FILES, KeyContext.GLOBAL)
    // ActivateTerminalToolWindow alt F12
    bind("alt F12", ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW, KeyContext.GLOBAL)
    // ShowIntentionActions alt ENTER
    bind("alt ENTER", ActionId.SHOW_INTENTION_ACTIONS, KeyContext.EDITOR)
    // ChooseRunConfiguration control alt R
    bind("control alt R", ActionId.CHOOSE_RUN_CONFIGURATION, KeyContext.GLOBAL)
    // CompileDirty control F9 (→meta)
    bind("meta F9", ActionId.BUILD_PROJECT, KeyContext.GLOBAL)
    // EditorSelectWord alt UP
    bind("alt UP", ActionId.EDITOR_SELECT_WORD, KeyContext.EDITOR)
    // EditorUnSelectWord alt DOWN
    bind("alt DOWN", ActionId.EDITOR_UNSELECT_WORD, KeyContext.EDITOR)
    // EditorToggleCase control shift U (→meta)
    bind("meta shift U", ActionId.EDITOR_TOGGLE_CASE, KeyContext.EDITOR)
    // EditorJoinLines control shift J
    bind("control shift J", ActionId.EDITOR_JOIN_LINES, KeyContext.EDITOR)
    // EditorStartNewLine shift ENTER
    bind("shift ENTER", ActionId.EDITOR_START_NEW_LINE, KeyContext.EDITOR)
    // EditorStartNewLineBefore control alt ENTER (→meta)
    bind("meta alt ENTER", ActionId.EDITOR_START_NEW_LINE_BEFORE, KeyContext.EDITOR)
    // EditorMatchBrace control M
    bind("control M", ActionId.EDITOR_MATCH_BRACE, KeyContext.EDITOR)
    // EditorCodeBlockStart meta alt OPEN_BRACKET
    bind("meta alt OPEN_BRACKET", ActionId.EDITOR_CODE_BLOCK_START, KeyContext.EDITOR)
    // EditorCodeBlockEnd meta alt CLOSE_BRACKET
    bind("meta alt CLOSE_BRACKET", ActionId.EDITOR_CODE_BLOCK_END, KeyContext.EDITOR)
    // MethodUp control shift UP
    bind("control shift UP", ActionId.METHOD_UP, KeyContext.EDITOR)
    // MethodDown control shift DOWN
    bind("control shift DOWN", ActionId.METHOD_DOWN, KeyContext.EDITOR)
    // HippieCompletion alt SLASH
    bind("alt SLASH", ActionId.HIPPIE_COMPLETION, KeyContext.EDITOR)
    // CodeCompletion control SPACE; the system input-source switch often owns this stroke,
    // so control alt SPACE is the same action for keyboards with more than one input source.
    bind("control SPACE", ActionId.CODE_COMPLETION, KeyContext.EDITOR)
    bind("control alt SPACE", ActionId.CODE_COMPLETION, KeyContext.EDITOR)
    // RenameElement shift F6
    bind("shift F6", ActionId.RENAME_ELEMENT, KeyContext.EDITOR)
    // $Copy and $Cut with an empty selection take the whole line
    bind("meta C", ActionId.COPY_LINE, KeyContext.EDITOR)
    bind("meta X", ActionId.CUT_LINE, KeyContext.EDITOR)
    // GotoDeclaration meta B
    bind("meta B", ActionId.GOTO_DECLARATION, KeyContext.EDITOR)
    // Back meta OPEN_BRACKET; meta alt LEFT
    bind("meta OPEN_BRACKET", ActionId.BACK, KeyContext.GLOBAL)
    bind("meta alt LEFT", ActionId.BACK, KeyContext.GLOBAL)
    // Forward meta CLOSE_BRACKET; meta alt RIGHT
    bind("meta CLOSE_BRACKET", ActionId.FORWARD, KeyContext.GLOBAL)
    bind("meta alt RIGHT", ActionId.FORWARD, KeyContext.GLOBAL)
    // FileStructurePopup control F12 (→meta)
    bind("meta F12", ActionId.FILE_STRUCTURE_POPUP, KeyContext.EDITOR)
    // GotoTest control shift T (→meta)
    bind("meta shift T", ActionId.GOTO_TEST, KeyContext.GLOBAL)
    // FindUsages alt F7
    bind("alt F7", ActionId.FIND_USAGES, KeyContext.EDITOR)
    // ToggleBookmark F3
    bind("F3", ActionId.TOGGLE_BOOKMARK, KeyContext.EDITOR)
    // ShowBookmarks meta F3
    bind("meta F3", ActionId.SHOW_BOOKMARKS, KeyContext.GLOBAL)
    // ActivateProjectToolWindow meta 1
    bind("meta 1", ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, KeyContext.GLOBAL)
    // HideAllWindows control shift F12 (→meta)
    bind("meta shift F12", ActionId.HIDE_ALL_WINDOWS, KeyContext.GLOBAL)
    // CopyPaths control shift C (→meta)
    bind("meta shift C", ActionId.COPY_PATHS, KeyContext.GLOBAL)
    // CopyReference control alt shift C (→meta)
    bind("meta alt shift C", ActionId.COPY_REFERENCE, KeyContext.GLOBAL)
    // Switcher control TAB; control shift TAB opens it and steps up
    bind("control TAB", ActionId.SWITCHER, KeyContext.GLOBAL)
    bind("control shift TAB", ActionId.SWITCHER, KeyContext.GLOBAL)
    // SelectInProjectView has no macOS default stroke; alt F1 reveals the current file
    bind("alt F1", ActionId.SELECT_IN_PROJECT_VIEW, KeyContext.GLOBAL)
    // REOPEN_CLOSED_TAB and CLOSE_OTHER_TABS have no strokes and are reachable from the Actions tab.
}
