package stride.app.keys

enum class ActionId {
    OPEN_FOLDER, SAVE, CLOSE_TAB, SEARCH_EVERYWHERE, NEXT_TAB, PREVIOUS_TAB, HIDE_ACTIVE_WINDOW,
    RUN, RUN_CONTEXT, STOP, RERUN, ACTIVATE_RUN_TOOL_WINDOW,
    EDITOR_DUPLICATE, EDITOR_DELETE_LINE, COMMENT_LINE, COMMENT_BLOCK, MOVE_LINE_UP, MOVE_LINE_DOWN,
    GOTO_LINE, UNDO, REDO, INDENT, UNINDENT, EDITOR_ESCAPE,
    FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, FIND_IN_PATH, GOTO_FILE, GOTO_CLASS, GOTO_SYMBOL,
    GOTO_ACTION, RECENT_FILES, ACTIVATE_TERMINAL_TOOL_WINDOW,
}

enum class KeyContext { GLOBAL, EDITOR, FIND_BAR, SEARCH_POPUP, RUN_WINDOW, TERMINAL }

data class Binding(val action: ActionId, val context: KeyContext)

class Keymap(val bindings: Map<KeyStroke, List<Binding>>) {
    /** The binding in [focus]'s context first, otherwise a GLOBAL binding, otherwise `null`. */
    fun actionFor(stroke: KeyStroke, focus: KeyContext): ActionId? {
        val candidates = bindings[stroke] ?: return null
        return candidates.firstOrNull { it.context == focus }?.action
            ?: candidates.firstOrNull { it.context == KeyContext.GLOBAL }?.action
    }

    fun strokesFor(action: ActionId): List<KeyStroke> =
        bindings.filterValues { list -> list.any { it.action == action } }.keys.toList()

    companion object {
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
}
