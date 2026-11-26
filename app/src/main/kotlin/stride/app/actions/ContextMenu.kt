package stride.app.actions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.dispatchAction
import stride.app.ui.popupOwnsFocus
import stride.app.ui.refocusAfterPopup
import stride.app.ui.refocusEditor
import java.nio.file.Path

/**
 * One open context menu: the group it shows, the surface it opened from, the pointer in window
 * coordinates, and the rows A3 resolved when it opened. [componentActions] holds the surface's own
 * handlers (A7); [initialSubmenus] is the row index per level the menu opens with, which the
 * snapshot scene uses.
 */
class ContextMenuRequest(
    val groupId: String,
    val openedFrom: KeyContext,
    val position: Offset,
    val rows: List<MenuRow>,
    val componentActions: Map<ActionId, ComponentAction> = emptyMap(),
    val initialSubmenus: List<Int> = emptyList(),
)

/** A6: the menu's top-left corner, flipped left or up when it would cross the window edge. */
fun contextMenuPosition(pointer: Offset, menuSize: Size, window: Size): Offset {
    val x = if (pointer.x + menuSize.width <= window.width) {
        pointer.x
    } else {
        (pointer.x - menuSize.width).coerceIn(0f, (window.width - menuSize.width).coerceAtLeast(0f))
    }
    val y = if (pointer.y + menuSize.height <= window.height) {
        pointer.y
    } else {
        (pointer.y - menuSize.height).coerceIn(0f, (window.height - menuSize.height).coerceAtLeast(0f))
    }
    return Offset(x, y)
}

/**
 * A6: a submenu's top-left corner beside [rowTopLeft], top-aligned, on the right unless that
 * crosses the window edge.
 */
fun submenuPosition(rowTopLeft: Offset, menuWidth: Float, submenuSize: Size, window: Size): Offset {
    val right = rowTopLeft.x + menuWidth
    val x = if (right + submenuSize.width <= window.width) {
        right
    } else {
        (rowTopLeft.x - submenuSize.width).coerceAtLeast(0f)
    }
    val y = rowTopLeft.y.coerceIn(0f, (window.height - submenuSize.height).coerceAtLeast(0f))
    return Offset(x, y)
}

/**
 * A4: opens [groupId] as the context menu of the surface whose context is [openedFrom]. The rows
 * resolve against the surface, which is why `state.focus` is [openedFrom] before and after; a
 * component row's `enabled` is asked by that same resolution, so it sees the surface too. The
 * menu's container reports `CONTEXT_POPUP` once it has the keyboard.
 */
fun openContextMenu(
    state: AppState,
    groupId: String,
    openedFrom: KeyContext,
    position: Offset,
    componentActions: Map<ActionId, ComponentAction> = emptyMap(),
    initialSubmenus: List<Int> = emptyList(),
): Boolean {
    val group = ActionGroups.byId[groupId] ?: return false
    state.focus = openedFrom
    val keymap = Keymap.forPlatform(KeyRouter.isMacOs())
    val rows = resolveMenuRows(keymap, state, group, componentActions)
    if (rows.isEmpty()) return true
    state.contextMenu = ContextMenuRequest(
        groupId = groupId,
        openedFrom = openedFrom,
        position = position,
        rows = rows,
        componentActions = componentActions,
        initialSubmenus = initialSubmenus,
    )
    return true
}

/**
 * A4: choosing a row clears the menu and puts `state.focus` back on the surface in the same call,
 * so the row's action reads the surface as its context. A component action (A7) runs through its
 * handler; every other row runs through the dispatcher, as its key would.
 */
fun chooseMenuRow(
    state: AppState,
    request: ContextMenuRequest,
    row: MenuRow,
    chooseFolder: () -> Path?,
) {
    val action = row as? MenuRow.Action ?: return
    if (!action.enabled) return
    state.contextMenu = null
    state.focus = request.openedFrom
    val component = request.componentActions[action.action]
    if (component != null) {
        component.run()
    } else {
        dispatchAction(action.action, state, chooseFolder)
    }
    if (popupOwnsFocus(state)) return
    // An action that left the keyboard somewhere else (Jump to Source opens the editor) keeps it.
    if (state.focus != request.openedFrom) return
    restoreSurfaceFocus(state, request.openedFrom)
}

/**
 * A4: Esc or a click outside the menu closes it and gives the surface the keyboard back, unless
 * the row the menu is closing onto left another popup holding it.
 */
fun closeContextMenu(state: AppState, request: ContextMenuRequest?) {
    state.contextMenu = null
    val openedFrom = request?.openedFrom ?: return
    if (popupOwnsFocus(state)) return
    restoreSurfaceFocus(state, openedFrom)
}

private fun restoreSurfaceFocus(state: AppState, openedFrom: KeyContext) {
    when (openedFrom) {
        KeyContext.PROJECT_TREE -> state.focusProjectTree()
        // The row may have swapped the tab (Close) or closed the last one (Close All); the editor
        // area requests focus for the new field after the next frame, and with no text editor
        // left the context falls back to GLOBAL, as a plain ⌘W on the last tab leaves it.
        KeyContext.EDITOR, KeyContext.EDITOR_FIELD -> state.focusSelectedEditor()
        KeyContext.COMMIT_WINDOW, KeyContext.LIST_WINDOW, KeyContext.RUN_WINDOW, KeyContext.TERMINAL ->
            refocusAfterPopup(state, openedFrom)
        else -> refocusEditor(state)
    }
}
