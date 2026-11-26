package stride.app.keys

enum class ActionId {
    OPEN_FOLDER, SAVE, CLOSE_TAB, SEARCH_EVERYWHERE, NEXT_TAB, PREVIOUS_TAB, HIDE_ACTIVE_WINDOW,
    RUN, RUN_CONTEXT, STOP, RERUN, ACTIVATE_RUN_TOOL_WINDOW,
    ACTIVATE_BOOKMARKS_TOOL_WINDOW, ACTIVATE_FIND_TOOL_WINDOW, ACTIVATE_STRUCTURE_TOOL_WINDOW,
    ACTIVATE_PROBLEMS_TOOL_WINDOW, GOTO_NEXT_ERROR, GOTO_PREVIOUS_ERROR, SHOW_ERROR_DESCRIPTION,
    SHOW_INTENTION_ACTIONS, CHOOSE_RUN_CONFIGURATION, BUILD_PROJECT,
    EDITOR_DUPLICATE, EDITOR_DELETE_LINE, COMMENT_LINE, COMMENT_BLOCK, MOVE_LINE_UP, MOVE_LINE_DOWN,
    GOTO_LINE, UNDO, REDO, INDENT, UNINDENT, EDITOR_ESCAPE,
    FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, FIND_IN_PATH, REPLACE_IN_PATH, GOTO_FILE, GOTO_CLASS, GOTO_SYMBOL,
    GOTO_ACTION, RECENT_FILES, ACTIVATE_TERMINAL_TOOL_WINDOW,
    EDITOR_SELECT_WORD, EDITOR_UNSELECT_WORD, EDITOR_TOGGLE_CASE, EDITOR_JOIN_LINES,
    EDITOR_START_NEW_LINE, EDITOR_START_NEW_LINE_BEFORE, EDITOR_MATCH_BRACE,
    EDITOR_CODE_BLOCK_START, EDITOR_CODE_BLOCK_END, METHOD_UP, METHOD_DOWN,
    HIPPIE_COMPLETION, CODE_COMPLETION, RENAME_ELEMENT, COPY_LINE, CUT_LINE,
    GOTO_DECLARATION, BACK, FORWARD, FILE_STRUCTURE_POPUP, GOTO_TEST, FIND_USAGES,
    TOGGLE_BOOKMARK, SHOW_BOOKMARKS, ACTIVATE_PROJECT_TOOL_WINDOW, HIDE_ALL_WINDOWS,
    COPY_PATHS, COPY_REFERENCE, SWITCHER, SELECT_IN_PROJECT_VIEW, REOPEN_CLOSED_TAB, CLOSE_OTHER_TABS,
    TOGGLE_UI_FONT, MOVE_STATEMENT_UP, MOVE_STATEMENT_DOWN, EDITOR_COMPLETE_STATEMENT,
    UNWRAP, REFORMAT_CODE, EDITOR_SCROLL_TO_CENTER,
    OPTIMIZE_IMPORTS, SMART_TYPE_COMPLETION, PASTE_MULTIPLE,
    PARAMETER_INFO, QUICK_DOC, QUICK_DEFINITION, SURROUND_WITH,
    SELECT_NEXT_OCCURRENCE, UNSELECT_PREVIOUS_OCCURRENCE, SELECT_ALL_OCCURRENCES,
    EDITOR_TOGGLE_COLUMN_MODE, EDITOR_CLONE_CARET_ABOVE, EDITOR_CLONE_CARET_BELOW,
    EXPAND_REGION, COLLAPSE_REGION, EXPAND_ALL_REGIONS, COLLAPSE_ALL_REGIONS,
    JUMP_TO_LAST_CHANGE, SHOW_USAGES, HIGHLIGHT_USAGES_IN_FILE,
    RECENT_LOCATIONS, SHOW_NAV_BAR, EDIT_SOURCE,
    GOTO_IMPLEMENTATION, GOTO_TYPE_DECLARATION, GOTO_SUPER_METHOD, TYPE_HIERARCHY, CALL_HIERARCHY,
    REFACTOR_THIS, INTRODUCE_VARIABLE, EXTRACT_METHOD, INLINE,
    GENERATE, OVERRIDE_METHODS, IMPLEMENT_METHODS,
    COMMIT, PUSH, VCS_POPUP, ACTIVATE_GIT_TOOL_WINDOW, ACTIVATE_COMMIT_TOOL_WINDOW,
    ROLLBACK_LINES, NEXT_CHANGE, PREVIOUS_CHANGE, SHOW_DIFF,
    COMMIT_AND_PUSH, UPDATE_PROJECT, FETCH, ROLLBACK_CHANGES, TOGGLE_AMEND,
    SHOW_BRANCHES, NEW_BRANCH,
}

enum class KeyContext {
    GLOBAL, EDITOR, EDITOR_FIELD, FIND_BAR, SEARCH_POPUP, RUN_WINDOW, TERMINAL, CONTEXT_POPUP,
    LIST_WINDOW, NAV_BAR, COMMIT_WINDOW,
}

data class Binding(val action: ActionId, val context: KeyContext)

class Keymap(
    val bindings: Map<KeyStroke, List<Binding>>,
    /** Whether [bindings] follow the macOS layout; `shortcutText` reads its labels from this. */
    val isMacOs: Boolean = true,
) {
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
        private val MODAL_CONTEXTS = setOf(KeyContext.SEARCH_POPUP, KeyContext.CONTEXT_POPUP, KeyContext.NAV_BAR)

        fun forPlatform(isMac: Boolean): Keymap =
            if (isMac) {
                MacKeymap.keymap
            } else {
                Keymap(
                    MacKeymap.keymap.bindings.mapKeys { (stroke, _) ->
                        stroke.copy(meta = stroke.ctrl, ctrl = stroke.meta)
                    },
                    isMacOs = false,
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
    bind(
        "shift ESCAPE",
        ActionId.HIDE_ACTIVE_WINDOW,
        KeyContext.RUN_WINDOW,
        KeyContext.TERMINAL,
        KeyContext.LIST_WINDOW,
        KeyContext.COMMIT_WINDOW,
    )
    // Run control R
    bind("control R", ActionId.RUN, KeyContext.GLOBAL)
    // RunClass control shift R (the last RunClass declaration)
    bind("control shift R", ActionId.RUN_CONTEXT, KeyContext.EDITOR)
    // Stop control F2 (→meta)
    bind("meta F2", ActionId.STOP, KeyContext.GLOBAL)
    // Rerun meta R; the editor's meta R is Replace
    bind("meta R", ActionId.RERUN, KeyContext.RUN_WINDOW)
    // ActivateBookmarksToolWindow meta 2
    bind("meta 2", ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW, KeyContext.GLOBAL)
    // ActivateFindToolWindow meta 3
    bind("meta 3", ActionId.ACTIVATE_FIND_TOOL_WINDOW, KeyContext.GLOBAL)
    // ActivateRunToolWindow meta 4
    bind("meta 4", ActionId.ACTIVATE_RUN_TOOL_WINDOW, KeyContext.GLOBAL)
    // ActivateProblemsViewToolWindow meta 6
    bind("meta 6", ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW, KeyContext.GLOBAL)
    // ActivateStructureToolWindow meta 7
    bind("meta 7", ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW, KeyContext.GLOBAL)
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
    // MoveStatementUp meta shift UP
    bind("meta shift UP", ActionId.MOVE_STATEMENT_UP, KeyContext.EDITOR)
    // MoveStatementDown meta shift DOWN
    bind("meta shift DOWN", ActionId.MOVE_STATEMENT_DOWN, KeyContext.EDITOR)
    // EditorCompleteStatement meta shift ENTER
    bind("meta shift ENTER", ActionId.EDITOR_COMPLETE_STATEMENT, KeyContext.EDITOR)
    // Unwrap meta shift DELETE
    bind("meta shift DELETE", ActionId.UNWRAP, KeyContext.EDITOR)
    // ReformatCode meta alt L
    bind("meta alt L", ActionId.REFORMAT_CODE, KeyContext.EDITOR)
    // EditorScrollToCenter control L
    bind("control L", ActionId.EDITOR_SCROLL_TO_CENTER, KeyContext.EDITOR)
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
    // ReplaceInPath meta shift R
    bind("meta shift R", ActionId.REPLACE_IN_PATH, KeyContext.GLOBAL)
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
    // SmartTypeCompletion control shift SPACE
    bind("control shift SPACE", ActionId.SMART_TYPE_COMPLETION, KeyContext.EDITOR)
    // OptimizeImports control alt O
    bind("control alt O", ActionId.OPTIMIZE_IMPORTS, KeyContext.EDITOR)
    // PasteMultiple meta shift V
    bind("meta shift V", ActionId.PASTE_MULTIPLE, KeyContext.EDITOR)
    // ParameterInfo meta P
    bind("meta P", ActionId.PARAMETER_INFO, KeyContext.EDITOR)
    // QuickJavaDoc F1; control J (the second macOS default stroke)
    bind("F1", ActionId.QUICK_DOC, KeyContext.EDITOR)
    bind("control J", ActionId.QUICK_DOC, KeyContext.EDITOR)
    // QuickImplementations alt SPACE
    bind("alt SPACE", ActionId.QUICK_DEFINITION, KeyContext.EDITOR)
    // SurroundWith meta alt T
    bind("meta alt T", ActionId.SURROUND_WITH, KeyContext.EDITOR)
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
    // SelectNextOccurrence control G
    bind("control G", ActionId.SELECT_NEXT_OCCURRENCE, KeyContext.EDITOR)
    // UnselectPreviousOccurrence control shift G
    bind("control shift G", ActionId.UNSELECT_PREVIOUS_OCCURRENCE, KeyContext.EDITOR)
    // SelectAllOccurrences meta control G
    bind("meta control G", ActionId.SELECT_ALL_OCCURRENCES, KeyContext.EDITOR)
    // EditorToggleColumnMode shift meta 8 (last declaration)
    bind("shift meta 8", ActionId.EDITOR_TOGGLE_COLUMN_MODE, KeyContext.EDITOR)
    // ExpandRegion meta ADD | meta EQUALS
    bind("meta ADD", ActionId.EXPAND_REGION, KeyContext.EDITOR)
    bind("meta EQUALS", ActionId.EXPAND_REGION, KeyContext.EDITOR)
    // CollapseRegion meta SUBTRACT | meta MINUS
    bind("meta SUBTRACT", ActionId.COLLAPSE_REGION, KeyContext.EDITOR)
    bind("meta MINUS", ActionId.COLLAPSE_REGION, KeyContext.EDITOR)
    // ExpandAllRegions shift meta ADD | shift meta EQUALS
    bind("meta shift ADD", ActionId.EXPAND_ALL_REGIONS, KeyContext.EDITOR)
    bind("meta shift EQUALS", ActionId.EXPAND_ALL_REGIONS, KeyContext.EDITOR)
    // CollapseAllRegions shift meta SUBTRACT | shift meta MINUS
    bind("meta shift SUBTRACT", ActionId.COLLAPSE_ALL_REGIONS, KeyContext.EDITOR)
    bind("meta shift MINUS", ActionId.COLLAPSE_ALL_REGIONS, KeyContext.EDITOR)
    // GotoNextError F2
    bind("F2", ActionId.GOTO_NEXT_ERROR, KeyContext.EDITOR)
    // GotoPreviousError shift F2
    bind("shift F2", ActionId.GOTO_PREVIOUS_ERROR, KeyContext.EDITOR)
    // ShowErrorDescription meta F1
    bind("meta F1", ActionId.SHOW_ERROR_DESCRIPTION, KeyContext.EDITOR)
    // RecentLocations meta shift E
    bind("meta shift E", ActionId.RECENT_LOCATIONS, KeyContext.GLOBAL)
    // ShowNavBar alt HOME; meta UP only in a list window
    bind("alt HOME", ActionId.SHOW_NAV_BAR, KeyContext.GLOBAL)
    bind("meta UP", ActionId.SHOW_NAV_BAR, KeyContext.LIST_WINDOW)
    // EditSource F4; meta DOWN only in a list window.
    // In the editor meta UP / meta DOWN keep reaching the text field, which moves the caret to
    // the document start / end (Compose's macOS mapping); the editor uses alt HOME / F4 instead.
    bind("F4", ActionId.EDIT_SOURCE, KeyContext.GLOBAL)
    bind("meta DOWN", ActionId.EDIT_SOURCE, KeyContext.LIST_WINDOW)
    // REOPEN_CLOSED_TAB and CLOSE_OTHER_TABS have no strokes and are reachable from the Actions tab.
    // EditorCloneCaretAbove/Below have no stroke either: a double Option gesture in KeyRouter.
    // JumpToLastChange meta shift BACK_SPACE
    bind("meta shift BACK_SPACE", ActionId.JUMP_TO_LAST_CHANGE, KeyContext.GLOBAL)
    // ShowUsages meta alt F7
    bind("meta alt F7", ActionId.SHOW_USAGES, KeyContext.EDITOR)
    // HighlightUsagesInFile meta shift F7
    bind("meta shift F7", ActionId.HIGHLIGHT_USAGES_IN_FILE, KeyContext.EDITOR)
    // GotoImplementation meta alt B
    bind("meta alt B", ActionId.GOTO_IMPLEMENTATION, KeyContext.EDITOR)
    // GotoTypeDeclaration meta shift B, control shift B
    bind("meta shift B", ActionId.GOTO_TYPE_DECLARATION, KeyContext.EDITOR)
    bind("control shift B", ActionId.GOTO_TYPE_DECLARATION, KeyContext.EDITOR)
    // GotoSuperMethod meta U
    bind("meta U", ActionId.GOTO_SUPER_METHOD, KeyContext.EDITOR)
    // TypeHierarchy control H
    bind("control H", ActionId.TYPE_HIERARCHY, KeyContext.EDITOR)
    // CallHierarchy control alt H
    bind("control alt H", ActionId.CALL_HIERARCHY, KeyContext.EDITOR)
    // Refactorings.QuickListPopupAction control T
    bind("control T", ActionId.REFACTOR_THIS, KeyContext.EDITOR)
    // IntroduceVariable meta alt V
    bind("meta alt V", ActionId.INTRODUCE_VARIABLE, KeyContext.EDITOR)
    // ExtractMethod meta alt M
    bind("meta alt M", ActionId.EXTRACT_METHOD, KeyContext.EDITOR)
    // Inline meta alt N
    bind("meta alt N", ActionId.INLINE, KeyContext.EDITOR)
    // Generate meta N | control ENTER
    bind("meta N", ActionId.GENERATE, KeyContext.EDITOR)
    bind("control ENTER", ActionId.GENERATE, KeyContext.EDITOR)
    // OverrideMethods control O
    bind("control O", ActionId.OVERRIDE_METHODS, KeyContext.EDITOR)
    // ImplementMethods control I
    bind("control I", ActionId.IMPLEMENT_METHODS, KeyContext.EDITOR)
    // CheckinProject control K (→meta)
    bind("meta K", ActionId.COMMIT, KeyContext.GLOBAL)
    // Vcs.Push control shift K (→meta)
    bind("meta shift K", ActionId.PUSH, KeyContext.GLOBAL)
    // Vcs.QuickListPopupAction control V; the handler declines it in the terminal
    bind("control V", ActionId.VCS_POPUP, KeyContext.GLOBAL)
    // ActivateVersionControlToolWindow meta 9
    bind("meta 9", ActionId.ACTIVATE_GIT_TOOL_WINDOW, KeyContext.GLOBAL)
    // ActivateCommitToolWindow meta 0
    bind("meta 0", ActionId.ACTIVATE_COMMIT_TOOL_WINDOW, KeyContext.GLOBAL)
    // Vcs.RollbackChangedLines control alt Z (→meta)
    bind("meta alt Z", ActionId.ROLLBACK_LINES, KeyContext.EDITOR)
    // ChangesView.Revert control alt Z ($default, control as meta); same stroke as Rollback Lines in EDITOR
    bind("meta alt Z", ActionId.ROLLBACK_CHANGES, KeyContext.COMMIT_WINDOW)
    // Vcs.ToggleAmendCommitMode control alt M (Mac OS X 10.5+)
    bind("control alt M", ActionId.TOGGLE_AMEND, KeyContext.COMMIT_WINDOW)
    // VcsShowPrevChangeMarker control alt shift UP
    bind("control alt shift UP", ActionId.PREVIOUS_CHANGE, KeyContext.EDITOR)
    // VcsShowNextChangeMarker control alt shift DOWN
    bind("control alt shift DOWN", ActionId.NEXT_CHANGE, KeyContext.EDITOR)
    // Diff.ShowDiff control D (→meta) in the Commit window only; the editor keeps EditorDuplicate
    bind("meta D", ActionId.SHOW_DIFF, KeyContext.COMMIT_WINDOW)
    // Git.Commit.And.Push.Executor control alt K (→meta)
    bind("meta alt K", ActionId.COMMIT_AND_PUSH, KeyContext.GLOBAL)
    // Vcs.UpdateProject control T (→meta)
    bind("meta T", ActionId.UPDATE_PROJECT, KeyContext.GLOBAL)
    // Git.Fetch has no stroke in IntelliJ; VCS popup row 8 runs it.
}
