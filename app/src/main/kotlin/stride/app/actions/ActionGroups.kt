package stride.app.actions

import stride.app.keys.ActionId

/** One item of an action group, IntelliJ's `<group>` children (A2). */
sealed interface GroupItem

/** An action row; [text] is this place's label, IntelliJ's `override-text`. */
data class GroupAction(val action: ActionId, val text: String? = null) : GroupItem

/** A separator row. */
data object GroupSeparator : GroupItem

/** A submenu with its own label and items. */
data class GroupSubmenu(val text: String, val items: List<GroupItem>) : GroupItem

/** A reference to another group, drawn as a submenu under [text] (IntelliJ's `<reference>`). */
data class GroupReference(val groupId: String, val text: String) : GroupItem

/** A named action group; [id] is IntelliJ's group id. */
class ActionGroup(val id: String, val items: List<GroupItem>)

/**
 * The menu groups the app builds its right-click menus, the menu bar and Find Action from (A2).
 * Rows follow IntelliJ's order; a group's id is the IntelliJ group id.
 */
object ActionGroups {
    const val GIT_CONTEXT_MENU: String = "Git.ContextMenu"
    const val PROJECT_VIEW_POPUP_MENU: String = "ProjectViewPopupMenu"
    const val EDITOR_POPUP_MENU: String = "EditorPopupMenu"
    const val EDITOR_GUTTER_POPUP_MENU: String = "EditorGutterPopupMenu"
    const val EDITOR_TAB_POPUP_MENU: String = "EditorTabPopupMenu"
    const val CHANGES_VIEW_POPUP_MENU: String = "ChangesViewPopupMenu"
    const val VCS_LOG_CONTEXT_MENU: String = "Vcs.Log.ContextMenu"
    const val TEST_TREE_POPUP_MENU: String = "TestTreePopupMenu"
    const val CONSOLE_VIEW_POPUP_MENU: String = "ConsoleView.PopupMenu"
    const val TERMINAL_CONTEXT_MENU: String = "Terminal.ReworkedTerminalContextMenu"
    const val USAGE_VIEW_POPUP: String = "UsageView.Popup"
    const val PROBLEMS_VIEW_TREE_POPUP: String = "ProblemsView.ToolWindow.TreePopup"
    const val STRUCTURE_VIEW_POPUP_MENU: String = "StructureViewPopupMenu"
    const val HIERARCHY_POPUP_MENU: String = "HierarchyPopupMenu.Common"
    const val BOOKMARKS_TOOL_WINDOW_POPUP_MENU: String = "Bookmarks.ToolWindow.PopupMenu"

    /** The shared Git submenu; each row calls what the matching VCS popup row calls (A8). */
    val gitContextMenu = ActionGroup(
        GIT_CONTEXT_MENU,
        listOf(
            GroupAction(ActionId.COMMIT),
            GroupAction(ActionId.ROLLBACK_CHANGES),
            GroupAction(ActionId.SHOW_DIFF),
            GroupSeparator,
            GroupAction(ActionId.PUSH),
            GroupAction(ActionId.UPDATE_PROJECT),
            GroupAction(ActionId.FETCH),
            GroupSeparator,
            GroupAction(ActionId.SHOW_BRANCHES),
            GroupAction(ActionId.NEW_BRANCH),
        ),
    )

    /** The project tree's menu (A8); `Run '<name>'` joins it with RUN_CONTEXT (A10). */
    val projectViewPopupMenu = ActionGroup(
        PROJECT_VIEW_POPUP_MENU,
        listOf(
            GroupSubmenu(
                "New",
                listOf(
                    GroupAction(ActionId.NEW_KOTLIN_FILE, "Kotlin Class/File"),
                    GroupAction(ActionId.NEW_FILE, "File"),
                    GroupAction(ActionId.NEW_DIRECTORY, "Directory"),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.CUT_FILE, "Cut"),
            GroupAction(ActionId.COPY_FILE, "Copy"),
            GroupSubmenu(
                "Copy Path/Reference…",
                listOf(
                    GroupAction(ActionId.COPY_PATHS),
                    GroupAction(ActionId.COPY_REFERENCE),
                ),
            ),
            GroupAction(ActionId.PASTE_FILE, "Paste"),
            GroupAction(ActionId.EDIT_SOURCE),
            GroupSeparator,
            GroupAction(ActionId.FIND_IN_PATH, "Find in Files…"),
            GroupAction(ActionId.REPLACE_IN_PATH, "Replace in Files…"),
            GroupSeparator,
            GroupAction(ActionId.RENAME_FILE, "Rename…"),
            GroupSubmenu(
                "Refactor",
                listOf(
                    GroupAction(ActionId.MOVE_ELEMENT),
                    GroupAction(ActionId.COPY_ELEMENT),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.DELETE_ELEMENT),
            GroupSeparator,
            GroupAction(ActionId.BUILD_PROJECT),
            GroupAction(ActionId.RUN_CONTEXT),
            GroupSubmenu(
                "Open In",
                listOf(
                    GroupAction(ActionId.REVEAL_IN_FINDER, "Finder"),
                    GroupAction(ActionId.OPEN_IN_TERMINAL, "Terminal"),
                ),
            ),
            GroupSeparator,
            GroupReference(GIT_CONTEXT_MENU, "Git"),
        ),
    )

    /** The editor text menu (A8); `Run '<name>'` joins it with RUN_CONTEXT (A10). */
    val editorPopupMenu = ActionGroup(
        EDITOR_POPUP_MENU,
        listOf(
            GroupAction(ActionId.SHOW_INTENTION_ACTIONS),
            GroupSeparator,
            GroupAction(ActionId.CUT_LINE, "Cut"),
            GroupAction(ActionId.COPY_LINE, "Copy"),
            GroupAction(ActionId.PASTE, "Paste"),
            GroupSubmenu(
                "Copy / Paste Special…",
                listOf(
                    GroupAction(ActionId.COPY_REFERENCE),
                    GroupAction(ActionId.PASTE_MULTIPLE, "Paste from History…"),
                ),
            ),
            GroupAction(ActionId.EDITOR_TOGGLE_COLUMN_MODE),
            GroupSeparator,
            GroupAction(ActionId.FIND_IN_PATH, "Find in Files…"),
            GroupAction(ActionId.FIND_USAGES),
            GroupSubmenu(
                "Go To",
                listOf(
                    GroupAction(ActionId.GOTO_DECLARATION, "Declaration or Usages"),
                    GroupAction(ActionId.GOTO_IMPLEMENTATION, "Implementation(s)"),
                    GroupAction(ActionId.GOTO_TYPE_DECLARATION, "Type Declaration"),
                    GroupAction(ActionId.GOTO_SUPER_METHOD, "Super Method"),
                    GroupAction(ActionId.GOTO_TEST, "Test"),
                ),
            ),
            GroupSubmenu(
                "Folding",
                listOf(
                    GroupAction(ActionId.EXPAND_REGION),
                    GroupAction(ActionId.COLLAPSE_REGION),
                    GroupAction(ActionId.EXPAND_ALL_REGIONS),
                    GroupAction(ActionId.COLLAPSE_ALL_REGIONS),
                ),
            ),
            GroupSeparator,
            GroupSubmenu(
                "Refactor",
                listOf(
                    GroupAction(ActionId.REFACTOR_THIS, "Refactor This…"),
                    GroupAction(ActionId.RENAME_ELEMENT, "Rename…"),
                    GroupAction(ActionId.RENAME_FILE),
                    GroupSeparator,
                    GroupAction(ActionId.INTRODUCE_VARIABLE, "Introduce Variable…"),
                    GroupAction(ActionId.EXTRACT_METHOD, "Extract Method…"),
                    GroupAction(ActionId.INLINE, "Inline…"),
                ),
            ),
            GroupAction(ActionId.GENERATE, "Generate…"),
            GroupSeparator,
            GroupAction(ActionId.RUN_CONTEXT),
            GroupSubmenu(
                "Open In",
                listOf(
                    GroupAction(ActionId.REVEAL_IN_FINDER, "Finder"),
                    GroupAction(ActionId.OPEN_IN_TERMINAL, "Terminal"),
                ),
            ),
            GroupSeparator,
            GroupReference(GIT_CONTEXT_MENU, "Git"),
        ),
    )

    /** The gutter menu (A8). */
    val editorGutterPopupMenu = ActionGroup(
        EDITOR_GUTTER_POPUP_MENU,
        listOf(
            GroupAction(ActionId.TOGGLE_BOOKMARK),
            GroupAction(ActionId.SHOW_BOOKMARKS),
        ),
    )

    /** The editor tab menu (A8), including the A9 closers. */
    val editorTabPopupMenu = ActionGroup(
        EDITOR_TAB_POPUP_MENU,
        listOf(
            GroupAction(ActionId.CLOSE_TAB, "Close"),
            GroupAction(ActionId.CLOSE_OTHER_TABS),
            GroupAction(ActionId.CLOSE_ALL_TABS),
            GroupAction(ActionId.CLOSE_TABS_TO_THE_LEFT),
            GroupAction(ActionId.CLOSE_TABS_TO_THE_RIGHT),
            GroupSeparator,
            GroupAction(ActionId.COPY_PATHS),
            GroupAction(ActionId.COPY_REFERENCE),
            GroupSeparator,
            GroupAction(ActionId.REOPEN_CLOSED_TAB),
        ),
    )

    /** The Commit window's file list (A8): the selected row's actions, then the Git submenu. */
    val changesViewPopupMenu = ActionGroup(
        CHANGES_VIEW_POPUP_MENU,
        listOf(
            GroupAction(ActionId.ROLLBACK_CHANGES, "Rollback…"),
            GroupAction(ActionId.SHOW_DIFF),
            GroupAction(ActionId.EDIT_SOURCE, "Jump to Source"),
            GroupSeparator,
            GroupReference(GIT_CONTEXT_MENU, "Git"),
        ),
    )

    /** The Git window's Log tab (A8); the Console tab opens no menu. */
    val vcsLogContextMenu = ActionGroup(
        VCS_LOG_CONTEXT_MENU,
        listOf(GroupAction(ActionId.SHOW_DIFF)),
    )

    /** The Run window over a test row (A8); `Run '<node>'` joins it as a component action (A10). */
    val testTreePopupMenu = ActionGroup(
        TEST_TREE_POPUP_MENU,
        listOf(
            GroupAction(ActionId.RUN_CONTEXT),
            GroupSeparator,
            GroupAction(ActionId.RERUN),
            GroupAction(ActionId.STOP),
        ),
    )

    /** The Run window everywhere else (A8). */
    val consoleViewPopupMenu = ActionGroup(
        CONSOLE_VIEW_POPUP_MENU,
        listOf(
            GroupAction(ActionId.RERUN),
            GroupAction(ActionId.STOP),
        ),
    )

    /** The terminal (A8); Copy and Paste are the terminal's own ⌘C and ⌘V (A7). */
    val terminalContextMenu = ActionGroup(
        TERMINAL_CONTEXT_MENU,
        listOf(
            GroupAction(ActionId.COPY, "Copy"),
            GroupAction(ActionId.PASTE, "Paste"),
        ),
    )

    /** One Jump to Source row over a list tool window's selected row (A7, A8). */
    private fun jumpToSourceMenu(id: String): ActionGroup =
        ActionGroup(id, listOf(GroupAction(ActionId.EDIT_SOURCE, "Jump to Source")))

    val usageViewPopupMenu: ActionGroup = jumpToSourceMenu(USAGE_VIEW_POPUP)
    val structureViewPopupMenu: ActionGroup = jumpToSourceMenu(STRUCTURE_VIEW_POPUP_MENU)
    val hierarchyPopupMenu: ActionGroup = jumpToSourceMenu(HIERARCHY_POPUP_MENU)
    val bookmarksToolWindowPopupMenu: ActionGroup = jumpToSourceMenu(BOOKMARKS_TOOL_WINDOW_POPUP_MENU)

    /** The Problems window (A8): the quick fixes at the problem, then Jump to Source. */
    val problemsViewTreePopup = ActionGroup(
        PROBLEMS_VIEW_TREE_POPUP,
        listOf(
            GroupAction(ActionId.SHOW_INTENTION_ACTIONS, "Show Quick-Fixes"),
            GroupAction(ActionId.EDIT_SOURCE, "Jump to Source"),
        ),
    )

    val all: List<ActionGroup> =
        listOf(
            gitContextMenu,
            projectViewPopupMenu,
            editorPopupMenu,
            editorGutterPopupMenu,
            editorTabPopupMenu,
            changesViewPopupMenu,
            vcsLogContextMenu,
            testTreePopupMenu,
            consoleViewPopupMenu,
            terminalContextMenu,
            usageViewPopupMenu,
            structureViewPopupMenu,
            hierarchyPopupMenu,
            bookmarksToolWindowPopupMenu,
            problemsViewTreePopup,
        )

    val byId: Map<String, ActionGroup> = all.associateBy { it.id }
}
