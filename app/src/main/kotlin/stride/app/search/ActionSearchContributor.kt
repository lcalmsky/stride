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
        ActionId.SAVE to "Save",
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

    private val entries: List<ActionEntry> = ActionId.entries.map { action ->
        ActionEntry(action, ActionNames.of(action), shortcutText(keymap, action))
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
