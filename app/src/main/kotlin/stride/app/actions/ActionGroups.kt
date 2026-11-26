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

/** M6: a row that names a stroke; its action is what the stroke runs on the surface with keyboard. */
data class GroupStroke(val stroke: String, val text: String) : GroupItem

/** M7: a row group the app fills at resolve time, IntelliJ's runtime-filled group. */
data class GroupGenerated(val kind: GeneratedRows) : GroupItem

/** The generated row kinds a [GroupGenerated] item stands for. */
enum class GeneratedRows { RECENT_PROJECTS }

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
    const val MAIN_MENU: String = "MainMenu"
    const val FILE_MENU: String = "FileMenu"
    const val EDIT_MENU: String = "EditMenu"
    const val VIEW_MENU: String = "ViewMenu"
    const val GO_TO_MENU: String = "GoToMenu"
    const val CODE_MENU: String = "CodeMenu"
    const val REFACTORING_MENU: String = "RefactoringMenu"
    const val BUILD_MENU: String = "BuildMenu"
    const val RUN_MENU: String = "RunMenu"
    const val VCS_GROUPS: String = "VcsGroups"
    const val WINDOW_MENU: String = "WindowMenu"
    const val HELP_MENU: String = "HelpMenu"

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

    /** M8: File, in IntelliJ's order; Recent Projects is the generated group (M7). */
    val fileMenu = ActionGroup(
        FILE_MENU,
        listOf(
            GroupSubmenu(
                "New",
                listOf(
                    GroupAction(ActionId.NEW_KOTLIN_FILE),
                    GroupAction(ActionId.NEW_FILE),
                    GroupAction(ActionId.NEW_DIRECTORY),
                ),
            ),
            GroupAction(ActionId.OPEN_FOLDER, "Open…"),
            GroupSubmenu("Recent Projects", listOf(GroupGenerated(GeneratedRows.RECENT_PROJECTS))),
            GroupSeparator,
            GroupAction(ActionId.SAVE),
        ),
    )

    /** M8: Edit, with Cut, Copy, Paste and Delete as stroke rows (M6). */
    val editMenu = ActionGroup(
        EDIT_MENU,
        listOf(
            GroupAction(ActionId.UNDO),
            GroupAction(ActionId.REDO),
            GroupSeparator,
            GroupStroke("meta X", "Cut"),
            GroupStroke("meta C", "Copy"),
            GroupAction(ActionId.COPY_PATHS, "Copy Paths"),
            GroupSubmenu("Copy Path/Reference…", listOf(GroupAction(ActionId.COPY_REFERENCE))),
            GroupSubmenu(
                "Paste",
                listOf(
                    GroupStroke("meta V", "Paste"),
                    GroupAction(ActionId.PASTE_MULTIPLE, "Paste from History…"),
                ),
            ),
            GroupStroke("BACK_SPACE", "Delete"),
            GroupSeparator,
            GroupSubmenu(
                "Find",
                listOf(
                    GroupAction(ActionId.FIND, "Find…"),
                    GroupAction(ActionId.REPLACE, "Replace…"),
                    GroupAction(ActionId.FIND_NEXT, "Find Next / Move to Next Occurrence"),
                    GroupAction(ActionId.FIND_PREVIOUS, "Find Previous / Move to Previous Occurrence"),
                    GroupAction(ActionId.SELECT_ALL_OCCURRENCES, "Select All Occurrences"),
                    GroupAction(ActionId.SELECT_NEXT_OCCURRENCE, "Add Selection for Next Occurrence"),
                    GroupAction(ActionId.UNSELECT_PREVIOUS_OCCURRENCE, "Unselect Occurrence"),
                    GroupSeparator,
                    GroupAction(ActionId.FIND_IN_PATH, "Find in Files…"),
                    GroupAction(ActionId.REPLACE_IN_PATH, "Replace in Files…"),
                ),
            ),
            GroupSubmenu(
                "Find Usages",
                listOf(
                    GroupAction(ActionId.FIND_USAGES),
                    GroupAction(ActionId.SHOW_USAGES),
                    GroupSeparator,
                    GroupAction(ActionId.HIGHLIGHT_USAGES_IN_FILE),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.EDITOR_TOGGLE_COLUMN_MODE),
            GroupAction(ActionId.EDITOR_SELECT_WORD, "Extend Selection"),
            GroupAction(ActionId.EDITOR_UNSELECT_WORD, "Shrink Selection"),
            GroupSeparator,
            GroupAction(ActionId.EDITOR_TOGGLE_CASE),
            GroupAction(ActionId.EDITOR_JOIN_LINES),
            GroupAction(ActionId.EDITOR_DUPLICATE),
            GroupSeparator,
            GroupAction(ActionId.INDENT, "Indent Selection"),
            GroupAction(ActionId.UNINDENT, "Unindent Line or Selection"),
            GroupSeparator,
            GroupSubmenu(
                "Bookmarks",
                listOf(
                    GroupAction(ActionId.TOGGLE_BOOKMARK),
                    GroupSeparator,
                    GroupAction(ActionId.SHOW_BOOKMARKS, "Show Bookmarks…"),
                ),
            ),
        ),
    )

    /** M8: View; the Tool Windows submenu is IntelliJ's runtime-filled group. */
    val viewMenu = ActionGroup(
        VIEW_MENU,
        listOf(
            GroupSubmenu(
                "Tool Windows",
                listOf(
                    GroupAction(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, "Project"),
                    GroupAction(ActionId.ACTIVATE_GIT_TOOL_WINDOW, "Git"),
                    GroupAction(ActionId.ACTIVATE_COMMIT_TOOL_WINDOW, "Commit"),
                    GroupAction(ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW, "Terminal"),
                    GroupAction(ActionId.ACTIVATE_RUN_TOOL_WINDOW, "Run"),
                    GroupAction(ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW, "Problems"),
                    GroupAction(ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW, "Structure"),
                    GroupAction(ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW, "Bookmarks"),
                    GroupAction(ActionId.ACTIVATE_FIND_TOOL_WINDOW, "Find"),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.QUICK_DEFINITION, "Quick Definition"),
            GroupAction(ActionId.QUICK_DOC, "Quick Documentation"),
            GroupAction(ActionId.PARAMETER_INFO, "Parameter Info"),
            GroupAction(ActionId.SHOW_ERROR_DESCRIPTION, "Error Description"),
            GroupSeparator,
            GroupAction(ActionId.EDIT_SOURCE, "Jump to Source"),
            GroupSeparator,
            GroupAction(ActionId.RECENT_FILES),
            GroupAction(ActionId.RECENT_LOCATIONS),
        ),
    )

    /** M8: Navigate (IntelliJ's `GoToMenu`). */
    val goToMenu = ActionGroup(
        GO_TO_MENU,
        listOf(
            GroupAction(ActionId.BACK),
            GroupAction(ActionId.FORWARD),
            GroupSeparator,
            GroupAction(ActionId.SEARCH_EVERYWHERE),
            GroupSeparator,
            GroupAction(ActionId.GOTO_CLASS, "Go to Class…"),
            GroupAction(ActionId.GOTO_FILE, "Go to File…"),
            GroupAction(ActionId.GOTO_SYMBOL, "Go to Symbol…"),
            GroupAction(ActionId.GOTO_LINE, "Go to Line:Column…"),
            GroupSeparator,
            GroupAction(ActionId.GOTO_NEXT_ERROR, "Next Highlighted Error"),
            GroupAction(ActionId.GOTO_PREVIOUS_ERROR, "Previous Highlighted Error"),
            GroupSeparator,
            GroupAction(ActionId.JUMP_TO_LAST_CHANGE),
            GroupSeparator,
            GroupSubmenu(
                "Navigate in File",
                listOf(
                    GroupAction(ActionId.METHOD_DOWN, "Next Method"),
                    GroupAction(ActionId.METHOD_UP, "Previous Method"),
                    GroupAction(ActionId.EDITOR_MATCH_BRACE, "Move Caret to Matching Brace"),
                    GroupSeparator,
                    GroupAction(ActionId.NEXT_CHANGE),
                    GroupAction(ActionId.PREVIOUS_CHANGE),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.SELECT_IN_PROJECT_VIEW),
            GroupAction(ActionId.SHOW_NAV_BAR),
            GroupSeparator,
            GroupAction(ActionId.GOTO_DECLARATION, "Go to Declaration or Usages"),
            GroupAction(ActionId.GOTO_IMPLEMENTATION, "Go to Implementation(s)"),
            GroupAction(ActionId.GOTO_TYPE_DECLARATION),
            GroupAction(ActionId.GOTO_SUPER_METHOD),
            GroupAction(ActionId.GOTO_TEST),
            GroupSeparator,
            GroupAction(ActionId.FILE_STRUCTURE_POPUP),
            GroupAction(ActionId.TYPE_HIERARCHY),
            GroupAction(ActionId.CALL_HIERARCHY),
        ),
    )

    /** M8: Code, with Code Completion and Folding as submenus. */
    val codeMenu = ActionGroup(
        CODE_MENU,
        listOf(
            GroupAction(ActionId.OVERRIDE_METHODS, "Override Methods…"),
            GroupAction(ActionId.IMPLEMENT_METHODS, "Implement Methods…"),
            GroupAction(ActionId.GENERATE, "Generate…"),
            GroupSeparator,
            GroupSubmenu(
                "Code Completion",
                listOf(
                    GroupAction(ActionId.CODE_COMPLETION, "Basic"),
                    GroupAction(ActionId.SMART_TYPE_COMPLETION, "Type-Matching"),
                    GroupSeparator,
                    GroupAction(ActionId.EDITOR_COMPLETE_STATEMENT),
                    GroupSeparator,
                    GroupAction(ActionId.HIPPIE_COMPLETION, "Cyclic Expand Word"),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.SURROUND_WITH, "Surround With…"),
            GroupAction(ActionId.UNWRAP, "Unwrap/Remove…"),
            GroupSeparator,
            GroupSubmenu(
                "Folding",
                listOf(
                    GroupAction(ActionId.EXPAND_REGION),
                    GroupAction(ActionId.EXPAND_ALL_REGIONS),
                    GroupSeparator,
                    GroupAction(ActionId.COLLAPSE_REGION),
                    GroupAction(ActionId.COLLAPSE_ALL_REGIONS),
                ),
            ),
            GroupSeparator,
            GroupAction(ActionId.COMMENT_LINE),
            GroupAction(ActionId.COMMENT_BLOCK),
            GroupAction(ActionId.REFORMAT_CODE),
            GroupAction(ActionId.OPTIMIZE_IMPORTS),
            GroupSeparator,
            GroupAction(ActionId.MOVE_STATEMENT_DOWN),
            GroupAction(ActionId.MOVE_STATEMENT_UP),
            GroupAction(ActionId.MOVE_LINE_DOWN),
            GroupAction(ActionId.MOVE_LINE_UP),
        ),
    )

    /** M8: Refactor. */
    val refactoringMenu = ActionGroup(
        REFACTORING_MENU,
        listOf(
            GroupAction(ActionId.REFACTOR_THIS, "Refactor This…"),
            GroupAction(ActionId.RENAME_ELEMENT, "Rename…"),
            GroupAction(ActionId.RENAME_FILE),
            GroupSeparator,
            GroupSubmenu(
                "Extract/Introduce",
                listOf(
                    GroupAction(ActionId.INTRODUCE_VARIABLE, "Introduce Variable…"),
                    GroupSeparator,
                    GroupAction(ActionId.EXTRACT_METHOD, "Extract Method…"),
                ),
            ),
            GroupAction(ActionId.INLINE, "Inline…"),
            GroupSeparator,
            GroupAction(ActionId.MOVE_ELEMENT),
            GroupAction(ActionId.COPY_ELEMENT),
        ),
    )

    /** M8: Build. */
    val buildMenu = ActionGroup(
        BUILD_MENU,
        listOf(GroupAction(ActionId.BUILD_PROJECT)),
    )

    /** M8: Run. */
    val runMenu = ActionGroup(
        RUN_MENU,
        listOf(
            GroupAction(ActionId.RUN_CONTEXT),
            GroupSeparator,
            GroupAction(ActionId.CHOOSE_RUN_CONFIGURATION, "Run…"),
            GroupSeparator,
            GroupAction(ActionId.STOP),
        ),
    )

    /** M8: Git (IntelliJ's `VcsGroups`). */
    val vcsGroups = ActionGroup(
        VCS_GROUPS,
        listOf(
            GroupAction(ActionId.COMMIT),
            GroupAction(ActionId.PUSH),
            GroupAction(ActionId.UPDATE_PROJECT),
            GroupAction(ActionId.FETCH),
            GroupSeparator,
            GroupAction(ActionId.SHOW_BRANCHES),
            GroupAction(ActionId.NEW_BRANCH),
            GroupSeparator,
            GroupAction(ActionId.ACTIVATE_GIT_TOOL_WINDOW, "Show VCS Log"),
            GroupSubmenu(
                "Uncommitted Changes",
                listOf(GroupAction(ActionId.ROLLBACK_CHANGES)),
            ),
            GroupSeparator,
            GroupAction(ActionId.VCS_POPUP, "VCS Operations Popup…"),
        ),
    )

    /** M8: Window. */
    val windowMenu = ActionGroup(
        WINDOW_MENU,
        listOf(
            GroupSubmenu(
                "Active Tool Window",
                listOf(
                    GroupAction(ActionId.HIDE_ACTIVE_WINDOW, "Hide Active Tool Window"),
                    GroupAction(ActionId.HIDE_ALL_WINDOWS, "Hide All Tool Windows"),
                ),
            ),
            GroupSubmenu(
                "Editor Tabs",
                listOf(
                    GroupAction(ActionId.NEXT_TAB, "Select Next Tab"),
                    GroupAction(ActionId.PREVIOUS_TAB, "Select Previous Tab"),
                    GroupSeparator,
                    GroupAction(ActionId.CLOSE_TAB),
                    GroupAction(ActionId.CLOSE_OTHER_TABS),
                    GroupAction(ActionId.CLOSE_ALL_TABS),
                    GroupAction(ActionId.CLOSE_TABS_TO_THE_LEFT),
                    GroupAction(ActionId.CLOSE_TABS_TO_THE_RIGHT),
                    GroupSeparator,
                    GroupAction(ActionId.REOPEN_CLOSED_TAB),
                ),
            ),
        ),
    )

    /** M8: Help. */
    val helpMenu = ActionGroup(
        HELP_MENU,
        listOf(GroupAction(ActionId.GOTO_ACTION, "Find Action…")),
    )

    /** M3: the bar's eleven menus, in IntelliJ's order. */
    val mainMenu = ActionGroup(
        MAIN_MENU,
        listOf(
            GroupSubmenu("File", fileMenu.items),
            GroupSubmenu("Edit", editMenu.items),
            GroupSubmenu("View", viewMenu.items),
            GroupSubmenu("Navigate", goToMenu.items),
            GroupSubmenu("Code", codeMenu.items),
            GroupSubmenu("Refactor", refactoringMenu.items),
            GroupSubmenu("Build", buildMenu.items),
            GroupSubmenu("Run", runMenu.items),
            GroupSubmenu("Git", vcsGroups.items),
            GroupSubmenu("Window", windowMenu.items),
            GroupSubmenu("Help", helpMenu.items),
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
            mainMenu,
            fileMenu,
            editMenu,
            viewMenu,
            goToMenu,
            codeMenu,
            refactoringMenu,
            buildMenu,
            runMenu,
            vcsGroups,
            windowMenu,
            helpMenu,
        )

    val byId: Map<String, ActionGroup> = all.associateBy { it.id }
}
