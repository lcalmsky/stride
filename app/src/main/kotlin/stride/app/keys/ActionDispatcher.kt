package stride.app.keys

import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.run.RunManager
import stride.app.run.runAtCaret
import stride.core.services.service
import stride.search.SearchTab
import java.nio.file.Path

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
        state.session?.editors?.saveSelected()
        true
    }
    ActionId.CLOSE_TAB -> {
        state.session?.editors?.closeSelected()
        true
    }
    ActionId.SEARCH_EVERYWHERE -> {
        if (state.session != null) state.openSearch(SearchTab.ALL)
        true
    }
    ActionId.NEXT_TAB -> {
        state.session?.editors?.selectNext()
        true
    }
    ActionId.PREVIOUS_TAB -> {
        state.session?.editors?.selectPrevious()
        true
    }
    ActionId.ACTIVATE_RUN_TOOL_WINDOW -> {
        state.toolWindows.toggle("run", focused = state.focus == KeyContext.RUN_WINDOW)
        true
    }
    ActionId.ACTIVATE_TERMINAL_TOOL_WINDOW -> {
        state.toolWindows.toggle("terminal", focused = state.focus == KeyContext.TERMINAL)
        true
    }
    ActionId.HIDE_ACTIVE_WINDOW -> {
        state.toolWindows.hide()
        state.focus = KeyContext.GLOBAL
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
        if (!state.findBar.visible) return false
        if (action == ActionId.FIND_NEXT) state.findBar.next(editor) else state.findBar.previous(editor)
        true
    }
    ActionId.FIND_IN_PATH -> {
        if (state.session != null) state.findInPathVisible = true
        true
    }
    ActionId.RECENT_FILES -> {
        if (state.session != null) state.recentFilesVisible = true
        true
    }
    // BasicTextField already binds meta Z and meta shift Z to its own undo and redo
    // (Foundation KeyMapping.kt:71,82); consuming them here keeps that from running.
    ActionId.UNDO, ActionId.REDO -> {
        (state.session?.editors?.selected as? TextFileEditor)?.applyAction(action)
        true
    }
    ActionId.EDITOR_DUPLICATE, ActionId.EDITOR_DELETE_LINE, ActionId.COMMENT_LINE, ActionId.COMMENT_BLOCK,
    ActionId.MOVE_LINE_UP, ActionId.MOVE_LINE_DOWN, ActionId.GOTO_LINE, ActionId.INDENT, ActionId.UNINDENT,
    ActionId.EDITOR_ESCAPE,
    -> (state.session?.editors?.selected as? TextFileEditor)?.applyAction(action) ?: false
    ActionId.RUN -> {
        val session = state.session ?: return false
        val manager = session.project.service<RunManager>()
        when {
            manager.rerun() != null -> {
                state.statusMessage = null
                state.toolWindows.activate("run")
            }
            runAtCaret(state) -> state.statusMessage = null
            else -> state.statusMessage = "Nothing to run"
        }
        true
    }
    ActionId.RUN_CONTEXT -> {
        if (state.session == null) return false
        if (runAtCaret(state)) {
            state.statusMessage = null
        } else {
            state.statusMessage = "No test at caret"
        }
        true
    }
    ActionId.STOP -> {
        val session = state.session ?: return false
        session.project.service<RunManager>().stop()
        true
    }
    ActionId.RERUN -> {
        val session = state.session ?: return false
        if (session.project.service<RunManager>().rerun() != null) {
            state.statusMessage = null
            state.toolWindows.activate("run")
        } else {
            state.statusMessage = "Nothing to rerun"
        }
        true
    }
}
