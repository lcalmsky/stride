package stride.app.actions

import stride.app.AppState
import stride.app.editor.FileEditorManager
import stride.app.files.fileClipboardFilled
import stride.app.files.fileOperationContext
import stride.app.files.openableFileTarget
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.run.RunManager
import stride.app.run.contextRun
import stride.app.vcs.commitWindowSelectedRow
import stride.core.services.service

/** A3: how a row draws now — whether it exists on this surface, can run, and its dynamic text. */
data class ActionUpdate(val visible: Boolean = true, val enabled: Boolean = true, val text: String? = null)

/**
 * A3: [action]'s one update. The default is visible and enabled; an action gets an update only
 * for a cheap synchronous precondition, and the update calls the predicate the action itself
 * calls, so the menu and the action cannot disagree.
 */
fun actionUpdate(action: ActionId, state: AppState): ActionUpdate = when (action) {
    ActionId.PASTE_FILE -> ActionUpdate(enabled = fileClipboardFilled(state))
    ActionId.EDIT_SOURCE -> editSourceUpdate(state)
    ActionId.CLOSE_OTHER_TABS -> tabUpdate(state) { it.canCloseOthers() }
    ActionId.CLOSE_ALL_TABS -> tabUpdate(state) { it.canCloseAll() }
    ActionId.CLOSE_TABS_TO_THE_LEFT -> tabUpdate(state) { it.canCloseTabsToTheLeft() }
    ActionId.CLOSE_TABS_TO_THE_RIGHT -> tabUpdate(state) { it.canCloseTabsToTheRight() }
    ActionId.REOPEN_CLOSED_TAB -> tabUpdate(state) { it.hasClosedFiles() }
    ActionId.ROLLBACK_CHANGES, ActionId.SHOW_DIFF -> commitWindowUpdate(state)
    ActionId.RUN_CONTEXT -> runContextUpdate(state)
    ActionId.RERUN -> runUpdate(state) { it.canRerun }
    ActionId.STOP -> runUpdate(state) { it.canStop }
    else -> ActionUpdate()
}

/** A3: a hidden group takes its reference row with it; the Git submenu exists only in a repository. */
fun groupUpdate(groupId: String, state: AppState): ActionUpdate = when (groupId) {
    ActionGroups.GIT_CONTEXT_MENU ->
        ActionUpdate(visible = state.session?.git?.snapshot?.repository != null)
    else -> ActionUpdate()
}

/** A3: a tab row's update asks the same predicate its action asks on [FileEditorManager]. */
private fun tabUpdate(state: AppState, predicate: (FileEditorManager) -> Boolean): ActionUpdate {
    val editors = state.session?.editors ?: return ActionUpdate()
    return ActionUpdate(enabled = predicate(editors))
}

private fun editSourceUpdate(state: AppState): ActionUpdate = when {
    state.focus == KeyContext.COMMIT_WINDOW -> ActionUpdate(enabled = commitWindowSelectedRow(state) != null)
    fileOperationContext(state) != KeyContext.PROJECT_TREE -> ActionUpdate()
    else -> ActionUpdate(enabled = openableFileTarget(state) != null)
}

/**
 * A3: the Run row exists where A10's resolver finds a run and shows its name. The Run window's
 * test tree is the exception: its row is an A7 component action that brings its own text.
 */
private fun runContextUpdate(state: AppState): ActionUpdate {
    if (state.focus == KeyContext.RUN_WINDOW) return ActionUpdate()
    val run = contextRun(state) ?: return ActionUpdate(visible = false)
    return ActionUpdate(text = "Run '${run.name}'")
}

/** A3: the two Commit window rows read the row the window has selected, inside the window only. */
private fun commitWindowUpdate(state: AppState): ActionUpdate {
    if (state.focus != KeyContext.COMMIT_WINDOW) return ActionUpdate()
    return ActionUpdate(enabled = commitWindowSelectedRow(state) != null)
}

/** A3: a Run row asks the same predicate its action and its key ask on [RunManager]. */
private fun runUpdate(state: AppState, predicate: (RunManager) -> Boolean): ActionUpdate {
    val session = state.session ?: return ActionUpdate()
    return ActionUpdate(enabled = predicate(session.project.service<RunManager>()))
}
