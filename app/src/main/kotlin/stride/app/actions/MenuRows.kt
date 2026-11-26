package stride.app.actions

import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.Keymap
import stride.app.keys.menuShortcutText
import stride.app.search.ActionNames

/** A7: a row a surface runs through its own handler instead of the dispatcher. */
class ComponentAction(
    val enabled: Boolean = true,
    val text: String? = null,
    val run: () -> Unit,
)

/** A3's resolved row: what the menu draws, and what choosing it does. */
sealed interface MenuRow {
    data class Action(
        val action: ActionId,
        val text: String,
        val shortcut: String,
        val enabled: Boolean,
    ) : MenuRow

    data object Separator : MenuRow

    data class Submenu(val text: String, val rows: List<MenuRow>) : MenuRow
}

/**
 * A3: [group]'s rows as the menu draws them, resolved once against [state] with `state.focus`
 * already set to the surface the menu opens from. Hidden rows are dropped, along with the
 * separators they leave and submenus with nothing left to show.
 */
fun resolveMenuRows(
    keymap: Keymap,
    state: AppState,
    group: ActionGroup,
    componentActions: Map<ActionId, ComponentAction> = emptyMap(),
): List<MenuRow> = trimSeparators(resolveItems(keymap, state, group.items, componentActions))

private fun resolveItems(
    keymap: Keymap,
    state: AppState,
    items: List<GroupItem>,
    componentActions: Map<ActionId, ComponentAction>,
): List<MenuRow> = items.mapNotNull { item ->
    when (item) {
        is GroupAction -> {
            val update = actionUpdate(item.action, state)
            if (!update.visible) return@mapNotNull null
            val component = componentActions[item.action]
            MenuRow.Action(
                action = item.action,
                text = item.text ?: component?.text ?: update.text ?: ActionNames.of(item.action),
                shortcut = menuShortcutText(keymap, item.action, state.focus),
                enabled = component?.enabled ?: update.enabled,
            )
        }
        GroupSeparator -> MenuRow.Separator
        is GroupSubmenu -> {
            val rows = trimSeparators(resolveItems(keymap, state, item.items, componentActions))
            if (rows.isEmpty()) null else MenuRow.Submenu(item.text, rows)
        }
        is GroupReference -> {
            if (!groupUpdate(item.groupId, state).visible) return@mapNotNull null
            val referenced = ActionGroups.byId[item.groupId] ?: return@mapNotNull null
            val rows = resolveMenuRows(keymap, state, referenced, componentActions)
            if (rows.isEmpty()) null else MenuRow.Submenu(item.text, rows)
        }
    }
}

private fun trimSeparators(rows: List<MenuRow>): List<MenuRow> {
    val result = ArrayList<MenuRow>(rows.size)
    for (row in rows) {
        if (row is MenuRow.Separator && (result.isEmpty() || result.last() is MenuRow.Separator)) continue
        result += row
    }
    while (result.lastOrNull() is MenuRow.Separator) result.removeAt(result.lastIndex)
    return result
}
