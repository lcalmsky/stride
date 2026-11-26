package stride.app.keys

import stride.app.AppState
import stride.app.editor.EditActions
import stride.app.editor.MultiCaret
import stride.app.editor.TextFileEditor
import stride.app.editor.openCodeCompletion
import stride.app.editor.openPasteHistory
import stride.app.editor.openRename
import stride.app.editor.openSurroundWith
import stride.app.editor.optimizeImports
import stride.app.files.NewKind
import stride.app.files.copyFile
import stride.app.files.cutFile
import stride.app.files.fileOperationTarget
import stride.app.files.openFileInTerminal
import stride.app.files.openableFileTarget
import stride.app.files.pasteFile
import stride.app.files.revealFileInFinder
import stride.app.files.startCopyElement
import stride.app.files.startDeleteElement
import stride.app.files.startMoveElement
import stride.app.files.startNewElement
import stride.app.files.startNewKind
import stride.app.files.startRenameFile
import stride.app.generate.openGenerate
import stride.app.generate.startGenerate
import stride.app.navigation.UsageMatches
import stride.app.navigation.NavigationBarState
import stride.app.navigation.clearUsageStatus
import stride.app.navigation.copyPath
import stride.app.navigation.findUsages
import stride.app.navigation.goToDeclaration
import stride.app.navigation.goToImplementation
import stride.app.navigation.goToSuperMethod
import stride.app.navigation.goToTest
import stride.app.navigation.goToTypeDeclaration
import stride.app.navigation.highlightUsagesInFile
import stride.app.navigation.jumpToLastChange
import stride.app.navigation.navigateBack
import stride.app.navigation.navigateForward
import stride.app.navigation.openFileStructure
import stride.app.navigation.openParameterInfo
import stride.app.navigation.openQuickDefinition
import stride.app.navigation.openQuickDoc
import stride.app.navigation.selectInProjectView
import stride.app.navigation.showBookmarks
import stride.app.navigation.showCallHierarchy
import stride.app.navigation.showTypeHierarchy
import stride.app.navigation.showUsages
import stride.app.navigation.toggleBookmark
import stride.app.problems.ErrorDescriptionRequest
import stride.app.problems.ProblemNavigation
import stride.app.refactor.openRefactorThis
import stride.app.refactor.startRefactoring
import stride.app.run.MainLocator
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.contextRun
import stride.app.run.editorRunConfiguration
import stride.app.run.mainConfigurationProblem
import stride.app.run.openContextActions
import stride.app.run.openRunConfigurationChooser
import stride.app.vcs.commitAndPush
import stride.app.vcs.fetchFromVcs
import stride.app.vcs.gotoChange
import stride.app.vcs.openBranches
import stride.app.vcs.openCommit
import stride.app.vcs.openNewBranch
import stride.app.vcs.openPush
import stride.app.vcs.openRollback
import stride.app.vcs.openUpdate
import stride.app.vcs.openVcsPopup
import stride.app.vcs.rollbackLines
import stride.app.vcs.showSelectedDiff
import stride.app.vcs.toggleAmend
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.GenerateKind
import stride.lang.RefactoringKind
import stride.search.SearchTab
import java.nio.file.Path

/** Contexts whose own component consumes the navigation keys, so the bar must not take them. */
private val NON_NAV_BAR_CONTEXTS = setOf(
    KeyContext.TERMINAL,
    KeyContext.RUN_WINDOW,
    KeyContext.FIND_BAR,
    KeyContext.EDITOR_FIELD,
)

/**
 * Applies [action] to [state]. Returns `true` when the event was consumed; actions with no
 * implementation return `false` so the key reaches the focused component.
 */
fun dispatchAction(action: ActionId, state: AppState, chooseFolder: () -> Path?): Boolean = when (action) {
    ActionId.OPEN_FOLDER -> {
        chooseFolder()?.let(state::openFolder)
        true
    }
    ActionId.SAVE -> {
        state.session?.let { session ->
            session.editors.saveAll()?.let { state.statusMessage = it }
            session.git.refresh()
        }
        true
    }
    ActionId.CLOSE_TAB -> {
        if (state.focus == KeyContext.LIST_WINDOW && state.toolWindows.activeId == "find") {
            state.findToolWindow.closeSelected()
        } else {
            state.session?.editors?.closeSelected()
        }
        true
    }
    ActionId.SEARCH_EVERYWHERE -> {
        if (state.session != null) state.openSearch(SearchTab.ALL)
        true
    }
    ActionId.NEXT_TAB -> {
        if (state.focus == KeyContext.LIST_WINDOW && state.toolWindows.activeId == "problems") {
            state.problemsToolWindow.selectNext()
        } else {
            state.session?.editors?.selectNext()
        }
        true
    }
    ActionId.PREVIOUS_TAB -> {
        if (state.focus == KeyContext.LIST_WINDOW && state.toolWindows.activeId == "problems") {
            state.problemsToolWindow.selectPrevious()
        } else {
            state.session?.editors?.selectPrevious()
        }
        true
    }
    ActionId.ACTIVATE_RUN_TOOL_WINDOW -> {
        state.toggleToolWindow("run", KeyContext.RUN_WINDOW)
        true
    }
    ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW -> {
        state.toggleToolWindow("terminal", KeyContext.TERMINAL)
        true
    }
    ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW -> {
        state.toggleToolWindow("bookmarks", KeyContext.LIST_WINDOW)
        true
    }
    ActionId.ACTIVATE_FIND_TOOL_WINDOW -> {
        state.toggleToolWindow("find", KeyContext.LIST_WINDOW)
        true
    }
    ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW -> {
        state.toggleToolWindow("structure", KeyContext.LIST_WINDOW)
        true
    }
    ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW -> {
        state.toggleToolWindow("problems", KeyContext.LIST_WINDOW)
        true
    }
    ActionId.ACTIVATE_GIT_TOOL_WINDOW -> {
        state.toggleToolWindow("git", KeyContext.LIST_WINDOW)
        true
    }
    ActionId.ACTIVATE_COMMIT_TOOL_WINDOW -> {
        state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
        true
    }
    ActionId.COMMIT -> openCommit(state)
    ActionId.PUSH -> openPush(state)
    ActionId.COMMIT_AND_PUSH -> commitAndPush(state)
    ActionId.UPDATE_PROJECT -> openUpdate(state)
    ActionId.FETCH -> fetchFromVcs(state)
    ActionId.VCS_POPUP -> {
        // Control letters belong to the shell, so the popup declines the stroke in the terminal.
        if (state.focus == KeyContext.TERMINAL) return false
        openVcsPopup(state)
    }
    ActionId.ROLLBACK_LINES -> rollbackLines(state)
    ActionId.ROLLBACK_CHANGES -> openRollback(state)
    ActionId.SHOW_BRANCHES -> openBranches(state)
    ActionId.NEW_BRANCH -> openNewBranch(state)
    ActionId.NEW_ELEMENT -> startNewElement(state)
    ActionId.NEW_KOTLIN_FILE -> startNewKind(state, NewKind.KOTLIN)
    ActionId.NEW_FILE -> startNewKind(state, NewKind.FILE)
    ActionId.NEW_DIRECTORY -> startNewKind(state, NewKind.DIRECTORY)
    ActionId.RENAME_FILE -> startRenameFile(state)
    ActionId.MOVE_ELEMENT -> startMoveElement(state)
    ActionId.DELETE_ELEMENT -> startDeleteElement(state)
    ActionId.COPY_ELEMENT -> startCopyElement(state)
    ActionId.CUT_FILE -> cutFile(state)
    ActionId.COPY_FILE -> copyFile(state)
    ActionId.PASTE_FILE -> pasteFile(state)
    ActionId.REVEAL_IN_FINDER -> revealFileInFinder(state)
    ActionId.OPEN_IN_TERMINAL -> openFileInTerminal(state)
    ActionId.TOGGLE_AMEND -> toggleAmend(state)
    ActionId.NEXT_CHANGE, ActionId.PREVIOUS_CHANGE -> gotoChange(state, action == ActionId.NEXT_CHANGE)
    ActionId.SHOW_DIFF -> showSelectedDiff(state)
    ActionId.GOTO_NEXT_ERROR, ActionId.GOTO_PREVIOUS_ERROR -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        val target = ProblemNavigation.target(
            problems = editor.problems,
            caret = editor.value.selection.start,
            forward = action == ActionId.GOTO_NEXT_ERROR,
        )
        if (target == null) {
            state.statusMessage = "No problems in this file"
        } else {
            editor.jumpTo(target.start)
            state.statusMessage = null
        }
        true
    }
    ActionId.SHOW_ERROR_DESCRIPTION -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        val problems = ProblemNavigation.at(editor.problems, editor.value.selection.start)
        if (problems.isEmpty()) {
            state.statusMessage = "No problems at the caret"
        } else {
            state.errorDescription = ErrorDescriptionRequest(editor, problems)
            state.focus = KeyContext.CONTEXT_POPUP
            state.statusMessage = null
        }
        true
    }
    ActionId.HIDE_ACTIVE_WINDOW -> {
        state.hideActiveWindow()
        true
    }
    ActionId.GOTO_FILE -> {
        if (state.session != null) state.openSearch(SearchTab.FILES)
        true
    }
    ActionId.GOTO_CLASS -> {
        if (state.session != null) {
            state.openSearch(SearchTab.CLASSES)
        } else {
            chooseFolder()?.let(state::openFolder)
        }
        true
    }
    ActionId.GOTO_SYMBOL -> {
        if (state.session != null) state.openSearch(SearchTab.SYMBOLS)
        true
    }
    ActionId.GOTO_ACTION -> {
        if (state.session != null) state.openSearch(SearchTab.ACTIONS)
        true
    }
    ActionId.FIND, ActionId.REPLACE -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.findBar.open(editor, replace = action == ActionId.REPLACE)
        true
    }
    ActionId.FIND_NEXT, ActionId.FIND_PREVIOUS -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        if (editor.usageHighlights.isNotEmpty()) {
            val caret = editor.value.selection.start
            val target = if (action == ActionId.FIND_NEXT) {
                UsageMatches.next(editor.usageHighlights, caret)
            } else {
                UsageMatches.previous(editor.usageHighlights, caret)
            }
            target?.let { editor.jumpTo(it.first) }
            true
        } else {
            if (!state.findBar.visible) return false
            if (action == ActionId.FIND_NEXT) state.findBar.next(editor) else state.findBar.previous(editor)
            true
        }
    }
    ActionId.FIND_IN_PATH -> {
        if (state.session != null) state.openFindInPath()
        true
    }
    ActionId.REPLACE_IN_PATH -> {
        if (state.session != null) state.openFindInPath(replace = true)
        true
    }
    ActionId.RECENT_FILES, ActionId.SWITCHER -> {
        if (state.session != null) {
            state.recentFilesVisible = true
            state.focus = KeyContext.SEARCH_POPUP
        }
        true
    }
    ActionId.GOTO_DECLARATION -> goToDeclaration(state)
    ActionId.BACK -> navigateBack(state)
    ActionId.FORWARD -> navigateForward(state)
    ActionId.FILE_STRUCTURE_POPUP -> openFileStructure(state)
    ActionId.GOTO_TEST -> goToTest(state)
    ActionId.FIND_USAGES -> findUsages(state)
    ActionId.SHOW_USAGES -> showUsages(state)
    ActionId.HIGHLIGHT_USAGES_IN_FILE -> highlightUsagesInFile(state)
    ActionId.GOTO_IMPLEMENTATION -> goToImplementation(state)
    ActionId.GOTO_TYPE_DECLARATION -> goToTypeDeclaration(state)
    ActionId.GOTO_SUPER_METHOD -> goToSuperMethod(state)
    ActionId.TYPE_HIERARCHY -> showTypeHierarchy(state)
    ActionId.CALL_HIERARCHY -> showCallHierarchy(state)
    ActionId.REFACTOR_THIS -> openRefactorThis(state)
    ActionId.INTRODUCE_VARIABLE -> startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE)
    ActionId.EXTRACT_METHOD -> startRefactoring(state, RefactoringKind.EXTRACT_METHOD)
    ActionId.INLINE -> startRefactoring(state, RefactoringKind.INLINE)
    ActionId.GENERATE -> openGenerate(state)
    ActionId.OVERRIDE_METHODS -> startGenerate(state, GenerateKind.OVERRIDE_METHODS)
    ActionId.IMPLEMENT_METHODS -> startGenerate(state, GenerateKind.IMPLEMENT_METHODS)
    ActionId.JUMP_TO_LAST_CHANGE -> jumpToLastChange(state)
    ActionId.TOGGLE_BOOKMARK -> toggleBookmark(state)
    ActionId.SHOW_BOOKMARKS -> showBookmarks(state)
    ActionId.ACTIVATE_PROJECT_TOOL_WINDOW -> {
        if (state.session == null) return false
        state.toggleProjectTree()
        true
    }
    ActionId.HIDE_ALL_WINDOWS -> {
        if (state.session == null) return false
        state.hideAllWindows()
        true
    }
    ActionId.COPY_PATHS -> copyPath(state, reference = false)
    ActionId.COPY_REFERENCE -> copyPath(state, reference = true)
    ActionId.SELECT_IN_PROJECT_VIEW -> selectInProjectView(state)
    ActionId.REOPEN_CLOSED_TAB -> {
        val session = state.session ?: return false
        val reopened = if (session.editors.hasClosedFiles()) session.editors.reopenClosed() else null
        state.statusMessage = if (reopened == null) "No closed tab to reopen" else null
        true
    }
    ActionId.CLOSE_OTHER_TABS -> {
        val session = state.session ?: return false
        session.editors.closeOthers()
        true
    }
    ActionId.CLOSE_ALL_TABS -> {
        val session = state.session ?: return false
        session.editors.closeAll()
        true
    }
    ActionId.CLOSE_TABS_TO_THE_LEFT -> {
        val session = state.session ?: return false
        session.editors.closeTabsToTheLeft()
        true
    }
    ActionId.CLOSE_TABS_TO_THE_RIGHT -> {
        val session = state.session ?: return false
        session.editors.closeTabsToTheRight()
        true
    }
    // A7: Copy and Paste are component actions; the key must reach the focused component.
    ActionId.COPY, ActionId.PASTE -> false
    ActionId.TOGGLE_UI_FONT -> {
        state.statusMessage = "UI font: ${state.toggleUiFont().label}"
        true
    }
    // BasicTextField already binds meta Z and meta shift Z to its own undo and redo
    // (Foundation KeyMapping.kt:71,82); consuming them here keeps that from running.
    ActionId.UNDO, ActionId.REDO -> {
        (state.session?.editors?.selected as? TextFileEditor)?.applyAction(action)
        true
    }
    // Esc closes Parameter Info before the editor's own Escape handling sees it.
    ActionId.EDITOR_ESCAPE -> {
        if (state.parameterInfo != null) {
            state.parameterInfo = null
            true
        } else {
            val editor = state.session?.editors?.selected as? TextFileEditor
            val handled = editor?.applyAction(action) ?: false
            if (editor != null && editor.usageHighlights.isEmpty()) clearUsageStatus(state)
            handled
        }
    }
    ActionId.EDITOR_DUPLICATE, ActionId.EDITOR_DELETE_LINE, ActionId.COMMENT_LINE, ActionId.COMMENT_BLOCK,
    ActionId.MOVE_LINE_UP, ActionId.MOVE_LINE_DOWN, ActionId.GOTO_LINE, ActionId.INDENT, ActionId.UNINDENT,
    ActionId.EDITOR_SELECT_WORD, ActionId.EDITOR_UNSELECT_WORD,
    ActionId.EDITOR_TOGGLE_CASE, ActionId.EDITOR_JOIN_LINES, ActionId.EDITOR_START_NEW_LINE,
    ActionId.EDITOR_START_NEW_LINE_BEFORE, ActionId.EDITOR_MATCH_BRACE, ActionId.EDITOR_CODE_BLOCK_START,
    ActionId.EDITOR_CODE_BLOCK_END, ActionId.METHOD_UP, ActionId.METHOD_DOWN,
    ActionId.MOVE_STATEMENT_UP, ActionId.MOVE_STATEMENT_DOWN,
    ActionId.EDITOR_COMPLETE_STATEMENT, ActionId.EDITOR_SCROLL_TO_CENTER,
    ActionId.EXPAND_REGION, ActionId.COLLAPSE_REGION, ActionId.EXPAND_ALL_REGIONS, ActionId.COLLAPSE_ALL_REGIONS,
    -> (state.session?.editors?.selected as? TextFileEditor)?.applyAction(action) ?: false
    ActionId.SELECT_NEXT_OCCURRENCE -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.statusMessage = if (editor.selectNextOccurrence()) null else "No more occurrences"
        true
    }
    ActionId.UNSELECT_PREVIOUS_OCCURRENCE,
    ActionId.SELECT_ALL_OCCURRENCES,
    ActionId.EDITOR_CLONE_CARET_ABOVE,
    ActionId.EDITOR_CLONE_CARET_BELOW,
    -> {
        val handled = (state.session?.editors?.selected as? TextFileEditor)?.applyAction(action)
            ?: return false
        if (handled) state.statusMessage = null
        handled
    }
    ActionId.EDITOR_TOGGLE_COLUMN_MODE -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.statusMessage = if (editor.toggleColumnMode()) {
            "Column selection mode on"
        } else {
            "Column selection mode off"
        }
        true
    }
    ActionId.COPY_LINE, ActionId.CUT_LINE -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        val selection = editor.value.selection
        val text = editor.value.text
        if (editor.secondaryCarets.isNotEmpty()) {
            val copied = MultiCaret.copyText(text, editor.carets).first
            val handled = editor.applyAction(action)
            if (handled) state.clipboardHistory.record(copied)
            return handled
        }
        val copied = if (!selection.collapsed) {
            text.substring(
                selection.min.coerceIn(0, text.length),
                selection.max.coerceIn(0, text.length),
            )
        } else {
            val range = EditActions.wholeLine(text, selection.start)
            text.substring(range.min, range.max)
        }
        val handled = editor.applyAction(action)
        // A real selection is copied by BasicTextField itself, so the key is not consumed here.
        if (handled || !selection.collapsed) state.clipboardHistory.record(copied)
        handled
    }
    ActionId.UNWRAP -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.statusMessage = when {
            editor.isReadOnly -> null
            editor.unwrap() -> null
            else -> "Nothing to unwrap"
        }
        true
    }
    ActionId.REFORMAT_CODE -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.statusMessage = editor.reformat()
        true
    }
    ActionId.HIPPIE_COMPLETION -> {
        val session = state.session ?: return false
        val editor = session.editors.selected as? TextFileEditor ?: return false
        val others = session.editors.openFiles
            .filter { it !== editor }
            .mapNotNull { (it as? TextFileEditor)?.value?.text }
        editor.hippieComplete(others)
    }
    ActionId.CODE_COMPLETION -> openCodeCompletion(state)
    ActionId.SMART_TYPE_COMPLETION -> openCodeCompletion(state, smart = true)
    ActionId.OPTIMIZE_IMPORTS -> {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        state.statusMessage = optimizeImports(editor)
        true
    }
    ActionId.PASTE_MULTIPLE -> openPasteHistory(state)
    ActionId.PARAMETER_INFO -> openParameterInfo(state)
    ActionId.QUICK_DOC -> openQuickDoc(state)
    ActionId.QUICK_DEFINITION -> openQuickDefinition(state)
    ActionId.SURROUND_WITH -> openSurroundWith(state)
    ActionId.RENAME_ELEMENT -> openRename(state)
    ActionId.RUN -> {
        val session = state.session ?: return false
        val manager = session.project.service<RunManager>()
        when {
            manager.rerun() != null -> {
                state.statusMessage = null
                state.toolWindows.activate("run")
            }
            runContext(state) -> state.statusMessage = null
            else -> state.statusMessage = runContextProblem(state) ?: "Nothing to run here"
        }
        true
    }
    ActionId.RUN_CONTEXT -> {
        val session = state.session ?: return false
        val configuration = contextRun(state)?.configuration()
        if (configuration != null) {
            session.project.service<RunManager>().run(configuration)
            state.statusMessage = null
            state.toolWindows.activate("run")
        } else {
            state.statusMessage = runContextProblem(state) ?: "Nothing to run here"
        }
        true
    }
    ActionId.SHOW_INTENTION_ACTIONS -> openContextActions(state)
    ActionId.CHOOSE_RUN_CONFIGURATION -> openRunConfigurationChooser(state)
    ActionId.BUILD_PROJECT -> {
        val session = state.session ?: return false
        session.project.service<RunManager>().run(
            RunConfiguration(
                name = "Build",
                workingDir = session.project.root,
                args = listOf("classes", "testClasses"),
                kind = RunKind.TASK,
            ),
        )
        state.statusMessage = null
        state.toolWindows.activate("run")
        true
    }
    ActionId.STOP -> {
        val session = state.session ?: return false
        val manager = session.project.service<RunManager>()
        if (manager.canStop) manager.stop()
        true
    }
    ActionId.RERUN -> {
        val session = state.session ?: return false
        val manager = session.project.service<RunManager>()
        if (manager.canRerun) {
            manager.rerun()
            state.statusMessage = null
            state.toolWindows.activate("run")
        } else {
            state.statusMessage = "Nothing to rerun"
        }
        true
    }
    ActionId.RECENT_LOCATIONS -> {
        state.openRecentLocations()
        true
    }
    ActionId.SHOW_NAV_BAR -> {
        val session = state.session ?: return false
        // The same stroke reaches the terminal and the other windows; there it must go on to the
        // focused component. Search Everywhere's Actions tab dispatches here with the search
        // popup still in `focus`, and it opens the bar.
        if (state.focus in NON_NAV_BAR_CONTEXTS) return false
        state.navigationBar = NavigationBarState.forFile(
            session.project.root,
            session.editors.selected?.file?.path,
        )
        state.focus = KeyContext.NAV_BAR
        state.statusMessage = null
        true
    }
    ActionId.EDIT_SOURCE -> when (state.focus) {
        // A list window opens the selected row from its own handler; the key must reach it.
        KeyContext.LIST_WINDOW -> false
        KeyContext.TERMINAL, KeyContext.RUN_WINDOW, KeyContext.FIND_BAR, KeyContext.EDITOR_FIELD -> false
        KeyContext.GLOBAL -> state.focusSelectedEditor()
        KeyContext.PROJECT_TREE -> {
            val session = state.session ?: return false
            val target = openableFileTarget(state)
            if (target != null) {
                session.editors.openFile(VFile(target))
                state.focusEditorOrTree()
            }
            true
        }
        // The editor, and the Actions tab whose search popup already closed over it.
        else -> goToDeclaration(state)
    }
}

/** The test at the caret, else the file's main or script entry point. */
private fun runContext(state: AppState): Boolean {
    val session = state.session ?: return false
    val configuration = editorRunConfiguration(state) ?: return false
    session.project.service<RunManager>().run(configuration)
    state.toolWindows.activate("run")
    return true
}

/** The specific reason the selected file's main cannot run, when there is one. */
private fun runContextProblem(state: AppState): String? {
    val session = state.session ?: return null
    val editor = session.editors.selected as? TextFileEditor ?: return null
    val main = MainLocator.locate(editor.value.text, editor.file.name)
    return mainConfigurationProblem(session, editor.file.path, main)
}
