package stride.app.actions

import stride.app.AppState
import stride.app.files.fileClipboardFilled
import stride.app.files.fileOperationContext
import stride.app.files.openableFileTarget
import stride.app.keys.ActionId
import stride.app.keys.KeyContext

/** A3: how a row draws now — whether it exists on this surface, can run, and its dynamic text. */
data class ActionUpdate(val visible: Boolean = true, val enabled: Boolean = true, val text: String? = null)

/**
 * A3: [action]'s one update. The default is visible and enabled; an action gets an update only
 * for a cheap synchronous precondition, and the update calls the predicate the action itself
 * calls, so the menu and the action cannot disagree.
 */
fun actionUpdate(action: ActionId, state: AppState): ActionUpdate = when (action) {
    ActionId.PASTE_FILE -> ActionUpdate(enabled = fileClipboardFilled(state))
    ActionId.EDIT_SOURCE -> treeEditSource(state)
    else -> ActionUpdate()
}

/** A3: a hidden group takes its reference row with it; the Git submenu exists only in a repository. */
fun groupUpdate(groupId: String, state: AppState): ActionUpdate = when (groupId) {
    ActionGroups.GIT_CONTEXT_MENU ->
        ActionUpdate(visible = state.session?.git?.snapshot?.repository != null)
    else -> ActionUpdate()
}

private fun treeEditSource(state: AppState): ActionUpdate {
    if (fileOperationContext(state) != KeyContext.PROJECT_TREE) return ActionUpdate()
    return ActionUpdate(enabled = openableFileTarget(state) != null)
}
