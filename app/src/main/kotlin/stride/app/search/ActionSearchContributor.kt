package stride.app.search

import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.dispatchAction
import stride.app.keys.shortcutText
import stride.search.FoundItem
import stride.search.NameMatcher
import stride.search.SearchEverywhereContributor
import stride.search.SearchTab
import java.nio.file.Path

/** Human-readable name for every [ActionId], used by the Actions tab. */
object ActionNames {
    val entries: Map<ActionId, String> = mapOf(
        ActionId.OPEN_FOLDER to "Open Folder",
        ActionId.SAVE to "Save All",
        ActionId.CLOSE_TAB to "Close Tab",
        ActionId.SEARCH_EVERYWHERE to "Search Everywhere",
        ActionId.NEXT_TAB to "Next Tab",
        ActionId.PREVIOUS_TAB to "Previous Tab",
        ActionId.HIDE_ACTIVE_WINDOW to "Hide Active Window",
        ActionId.RUN to "Run",
        ActionId.RUN_CONTEXT to "Run Test at Caret",
        ActionId.STOP to "Stop",
        ActionId.RERUN to "Rerun",
        ActionId.ACTIVATE_RUN_TOOL_WINDOW to "Run Window",
        ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW to "Bookmarks Window",
        ActionId.ACTIVATE_FIND_TOOL_WINDOW to "Find Window",
        ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW to "Structure Window",
        ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW to "Problems Window",
        ActionId.GOTO_NEXT_ERROR to "Next Error",
        ActionId.GOTO_PREVIOUS_ERROR to "Previous Error",
        ActionId.SHOW_ERROR_DESCRIPTION to "Show Error Description",
        ActionId.EDITOR_DUPLICATE to "Duplicate Line or Selection",
        ActionId.EDITOR_DELETE_LINE to "Delete Line",
        ActionId.COMMENT_LINE to "Comment with Line Comment",
        ActionId.COMMENT_BLOCK to "Comment with Block Comment",
        ActionId.MOVE_LINE_UP to "Move Line Up",
        ActionId.MOVE_LINE_DOWN to "Move Line Down",
        ActionId.GOTO_LINE to "Go to Line",
        ActionId.UNDO to "Undo",
        ActionId.REDO to "Redo",
        ActionId.INDENT to "Indent Selection",
        ActionId.UNINDENT to "Unindent Selection",
        ActionId.EDITOR_ESCAPE to "Editor Escape",
        ActionId.FIND to "Find",
        ActionId.REPLACE to "Replace",
        ActionId.FIND_NEXT to "Find Next",
        ActionId.FIND_PREVIOUS to "Find Previous",
        ActionId.FIND_IN_PATH to "Find in Path",
        ActionId.REPLACE_IN_PATH to "Replace in Path",
        ActionId.GOTO_FILE to "Go to File",
        ActionId.GOTO_CLASS to "Go to Class",
        ActionId.GOTO_SYMBOL to "Go to Symbol",
        ActionId.GOTO_ACTION to "Go to Action",
        ActionId.RECENT_FILES to "Recent Files",
        ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW to "Terminal Window",
        ActionId.SHOW_INTENTION_ACTIONS to "Show Context Actions",
        ActionId.CHOOSE_RUN_CONFIGURATION to "Choose Run Configuration",
        ActionId.BUILD_PROJECT to "Build Project",
        ActionId.EDITOR_SELECT_WORD to "Extend Selection",
        ActionId.EDITOR_UNSELECT_WORD to "Shrink Selection",
        ActionId.EDITOR_TOGGLE_CASE to "Toggle Case",
        ActionId.EDITOR_JOIN_LINES to "Join Lines",
        ActionId.EDITOR_START_NEW_LINE to "Start New Line",
        ActionId.EDITOR_START_NEW_LINE_BEFORE to "Start New Line Before",
        ActionId.EDITOR_MATCH_BRACE to "Match Brace",
        ActionId.EDITOR_CODE_BLOCK_START to "Code Block Start",
        ActionId.EDITOR_CODE_BLOCK_END to "Code Block End",
        ActionId.METHOD_UP to "Method Up",
        ActionId.METHOD_DOWN to "Method Down",
        ActionId.HIPPIE_COMPLETION to "Hippie Completion",
        ActionId.CODE_COMPLETION to "Code Completion",
        ActionId.RENAME_ELEMENT to "Rename Element",
        ActionId.COPY_LINE to "Copy Line",
        ActionId.CUT_LINE to "Cut Line",
        ActionId.GOTO_DECLARATION to "Go to Declaration",
        ActionId.BACK to "Back",
        ActionId.FORWARD to "Forward",
        ActionId.FILE_STRUCTURE_POPUP to "File Structure",
        ActionId.GOTO_TEST to "Go to Test",
        ActionId.FIND_USAGES to "Find Usages",
        ActionId.TOGGLE_BOOKMARK to "Toggle Bookmark",
        ActionId.SHOW_BOOKMARKS to "Show Bookmarks",
        ActionId.ACTIVATE_PROJECT_TOOL_WINDOW to "Project Window",
        ActionId.HIDE_ALL_WINDOWS to "Hide All Windows",
        ActionId.COPY_PATHS to "Copy Path",
        ActionId.COPY_REFERENCE to "Copy Reference",
        ActionId.SWITCHER to "Switcher",
        ActionId.SELECT_IN_PROJECT_VIEW to "Select in Project View",
        ActionId.REOPEN_CLOSED_TAB to "Reopen Closed Tab",
        ActionId.CLOSE_OTHER_TABS to "Close Other Tabs",
        ActionId.TOGGLE_UI_FONT to "Switch UI Font",
        ActionId.MOVE_STATEMENT_UP to "Move Statement Up",
        ActionId.MOVE_STATEMENT_DOWN to "Move Statement Down",
        ActionId.EDITOR_COMPLETE_STATEMENT to "Complete Current Statement",
        ActionId.UNWRAP to "Unwrap/Remove",
        ActionId.REFORMAT_CODE to "Reformat Code",
        ActionId.EDITOR_SCROLL_TO_CENTER to "Scroll to Center",
        ActionId.OPTIMIZE_IMPORTS to "Optimize Imports",
        ActionId.SMART_TYPE_COMPLETION to "Type-Matching Completion",
        ActionId.PASTE_MULTIPLE to "Paste from History",
        ActionId.PARAMETER_INFO to "Parameter Info",
        ActionId.QUICK_DOC to "Quick Documentation",
        ActionId.QUICK_DEFINITION to "Quick Definition",
        ActionId.SURROUND_WITH to "Surround With",
        ActionId.SELECT_NEXT_OCCURRENCE to "Select Next Occurrence",
        ActionId.UNSELECT_PREVIOUS_OCCURRENCE to "Unselect Previous Occurrence",
        ActionId.SELECT_ALL_OCCURRENCES to "Select All Occurrences",
        ActionId.EDITOR_TOGGLE_COLUMN_MODE to "Column Selection Mode",
        ActionId.EDITOR_CLONE_CARET_ABOVE to "Clone Caret Above",
        ActionId.EDITOR_CLONE_CARET_BELOW to "Clone Caret Below",
        ActionId.EXPAND_REGION to "Expand",
        ActionId.COLLAPSE_REGION to "Collapse",
        ActionId.EXPAND_ALL_REGIONS to "Expand All",
        ActionId.COLLAPSE_ALL_REGIONS to "Collapse All",
        ActionId.JUMP_TO_LAST_CHANGE to "Last Edit Location",
        ActionId.SHOW_USAGES to "Show Usages",
        ActionId.HIGHLIGHT_USAGES_IN_FILE to "Highlight Usages in File",
        ActionId.RECENT_LOCATIONS to "Recent Locations",
        ActionId.SHOW_NAV_BAR to "Jump to Navigation Bar",
        ActionId.EDIT_SOURCE to "Edit Source",
        ActionId.GOTO_IMPLEMENTATION to "Go to Implementation",
        ActionId.GOTO_TYPE_DECLARATION to "Go to Type Declaration",
        ActionId.GOTO_SUPER_METHOD to "Go to Super Method",
        ActionId.TYPE_HIERARCHY to "Type Hierarchy",
        ActionId.CALL_HIERARCHY to "Call Hierarchy",
        ActionId.REFACTOR_THIS to "Refactor This",
        ActionId.INTRODUCE_VARIABLE to "Introduce Variable",
        ActionId.EXTRACT_METHOD to "Extract Method",
        ActionId.INLINE to "Inline",
        ActionId.GENERATE to "Generate",
        ActionId.OVERRIDE_METHODS to "Override Methods",
        ActionId.IMPLEMENT_METHODS to "Implement Methods",
        ActionId.COMMIT to "Commit…",
        ActionId.PUSH to "Push…",
        ActionId.VCS_POPUP to "VCS Operations",
        ActionId.ACTIVATE_GIT_TOOL_WINDOW to "Git",
        ActionId.ACTIVATE_COMMIT_TOOL_WINDOW to "Commit",
        ActionId.ROLLBACK_LINES to "Rollback Lines",
        ActionId.NEXT_CHANGE to "Next Change",
        ActionId.PREVIOUS_CHANGE to "Previous Change",
        ActionId.SHOW_DIFF to "Show Diff",
        ActionId.COMMIT_AND_PUSH to "Commit and Push…",
        ActionId.UPDATE_PROJECT to "Update Project…",
        ActionId.FETCH to "Fetch",
        ActionId.ROLLBACK_CHANGES to "Rollback…",
        ActionId.TOGGLE_AMEND to "Amend",
        ActionId.SHOW_BRANCHES to "Branches…",
        ActionId.NEW_BRANCH to "New Branch…",
        ActionId.NEW_ELEMENT to "New…",
        ActionId.RENAME_FILE to "Rename File…",
        ActionId.MOVE_ELEMENT to "Move…",
        ActionId.DELETE_ELEMENT to "Delete…",
        ActionId.COPY_ELEMENT to "Copy…",
        ActionId.CUT_FILE to "Cut File",
        ActionId.COPY_FILE to "Copy File",
        ActionId.PASTE_FILE to "Paste File",
        ActionId.REVEAL_IN_FINDER to "Reveal in Finder",
        ActionId.OPEN_IN_TERMINAL to "Open in Terminal",
        ActionId.NEW_KOTLIN_FILE to "Kotlin Class/File",
        ActionId.NEW_FILE to "File",
        ActionId.NEW_DIRECTORY to "Directory",
        ActionId.COPY to "Copy",
        ActionId.PASTE to "Paste",
        ActionId.CLOSE_ALL_TABS to "Close All Tabs",
        ActionId.CLOSE_TABS_TO_THE_LEFT to "Close Tabs to the Left",
        ActionId.CLOSE_TABS_TO_THE_RIGHT to "Close Tabs to the Right",
    )

    /** [action]'s name, falling back to the enum name for actions without an entry. */
    fun of(action: ActionId): String = entries[action] ?: action.name
}

data class ActionEntry(val action: ActionId, val presentableName: String, val shortcut: String)

/**
 * Search Everywhere contributor for the Actions tab. It needs [AppState] to run the chosen
 * action, so [SearchEverywhere] creates it directly instead of loading it from the extension
 * point; [process] closes the popup and dispatches the action.
 */
class ActionSearchContributor(
    private val state: AppState,
    private val chooseFolder: () -> Path? = { null },
    private val keymap: Keymap = Keymap.forPlatform(KeyRouter.isMacOs()),
) : SearchEverywhereContributor<ActionEntry> {
    override val id: String get() = ID

    override val displayName: String get() = "Actions"

    override val sortWeight: Int get() = 70

    override val tab: SearchTab get() = SearchTab.ACTIONS

    /** A2: Copy and Paste act on a component's own state, so Find Action leaves them out. */
    private val entries: List<ActionEntry> = ActionId.entries
        .filter { it != ActionId.COPY && it != ActionId.PASTE }
        .map { action -> ActionEntry(action, ActionNames.of(action), shortcut(action)) }

    /** The clone actions are gestures, so their label comes from [KeyRouter], not the keymap. */
    private fun shortcut(action: ActionId): String = when (action) {
        ActionId.EDITOR_CLONE_CARET_ABOVE -> KeyRouter.CLONE_CARET_ABOVE_GESTURE
        ActionId.EDITOR_CLONE_CARET_BELOW -> KeyRouter.CLONE_CARET_BELOW_GESTURE
        else -> shortcutText(keymap, action)
    }

    override fun fetch(query: String, limit: Int): List<FoundItem<ActionEntry>> {
        if (limit <= 0) return emptyList()
        return entries
            .mapNotNull { entry ->
                val score = NameMatcher.match(query, entry.presentableName) ?: return@mapNotNull null
                FoundItem(entry, score, entry.presentableName, entry.shortcut)
            }
            .sortedWith(
                compareByDescending<FoundItem<ActionEntry>> { it.score }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.presentationText }
                    .thenBy { it.item.action.ordinal }
            )
            .take(minOf(limit, MAX_RESULTS))
    }

    override fun process(item: ActionEntry): Boolean {
        dispatchAction(item.action, state, chooseFolder)
        return true
    }

    companion object {
        const val ID: String = "stride.actions"
        const val MAX_RESULTS: Int = 50
    }
}
