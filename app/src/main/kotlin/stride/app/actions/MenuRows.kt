package stride.app.actions

import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.KeyStroke
import stride.app.keys.Keymap
import stride.app.keys.menuShortcutText
import stride.app.search.ActionNames

/**
 * A7: a row a surface runs through its own handler instead of the dispatcher. [enabled] is asked
 * while the menu resolves its rows, after `state.focus` has been set to the surface the menu opens
 * from, so a row cannot read a judgment the surface took before opening.
 */
class ComponentAction(
    val enabled: () -> Boolean = { true },
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

    /** M7: a generated row that is not an action; choosing an enabled row runs [run]. */
    data class Command(
        val text: String,
        val shortcut: String,
        val enabled: Boolean,
        val run: () -> Unit,
    ) : MenuRow
}

/**
 * A3: [group]'s rows as the menu draws them, resolved once against [state] with `state.focus`
 * already set to the surface the menu opens from. Hidden rows are dropped, along with the
 * separators they leave and submenus with nothing left to show.
 *
 * [canDispatch] is M4's extra enablement condition (the action would be consumed by the
 * dispatcher); [generatedRows] fills [GroupGenerated] items (M7); [modal] disables every row while
 * a modal popup owns the keyboard (M4).
 */
fun resolveMenuRows(
    keymap: Keymap,
    state: AppState,
    group: ActionGroup,
    componentActions: Map<ActionId, ComponentAction> = emptyMap(),
    canDispatch: (ActionId) -> Boolean = { true },
    generatedRows: (GeneratedRows) -> List<MenuRow> = { emptyList() },
    modal: Boolean = state.focus in Keymap.MODAL_CONTEXTS,
): List<MenuRow> = trimSeparators(
    resolveItems(keymap, state, group.items, componentActions, canDispatch, generatedRows, modal),
)

/**
 * M5: the one row [item] resolves to now, with the same resolution [resolveMenuRows] uses. The
 * menu bar re-resolves a clicked row with it, so opening and choosing cannot disagree.
 */
fun resolveMenuRow(
    keymap: Keymap,
    state: AppState,
    item: GroupItem,
    componentActions: Map<ActionId, ComponentAction> = emptyMap(),
    canDispatch: (ActionId) -> Boolean = { true },
    generatedRows: (GeneratedRows) -> List<MenuRow> = { emptyList() },
    modal: Boolean = state.focus in Keymap.MODAL_CONTEXTS,
): MenuRow? = when (item) {
    GroupSeparator -> MenuRow.Separator
    // A generated item expands to a list, so it has no single row to resolve again.
    is GroupGenerated -> null
    is GroupAction -> actionRow(keymap, state, item.action, item.text, componentActions, canDispatch, modal)
    is GroupStroke -> {
        val action = keymap.actionFor(KeyStroke.parse(item.stroke), state.focus)
        if (action == null) {
            MenuRow.Command(text = item.text, shortcut = "", enabled = false, run = {})
        } else {
            actionRow(keymap, state, action, item.text, componentActions, canDispatch, modal)
        }
    }
    is GroupSubmenu -> {
        val rows = trimSeparators(
            resolveItems(keymap, state, item.items, componentActions, canDispatch, generatedRows, modal),
        )
        if (rows.isEmpty()) null else MenuRow.Submenu(item.text, rows)
    }
    is GroupReference -> {
        if (!groupUpdate(item.groupId, state).visible) return null
        val referenced = ActionGroups.byId[item.groupId] ?: return null
        val rows = resolveMenuRows(keymap, state, referenced, componentActions, canDispatch, generatedRows, modal)
        if (rows.isEmpty()) null else MenuRow.Submenu(item.text, rows)
    }
}

private fun resolveItems(
    keymap: Keymap,
    state: AppState,
    items: List<GroupItem>,
    componentActions: Map<ActionId, ComponentAction>,
    canDispatch: (ActionId) -> Boolean,
    generatedRows: (GeneratedRows) -> List<MenuRow>,
    modal: Boolean,
): List<MenuRow> = items.flatMap { item ->
    if (item is GroupGenerated) {
        generatedRows(item.kind).map { row ->
            if (row is MenuRow.Command && modal) row.copy(enabled = false) else row
        }
    } else {
        listOfNotNull(resolveMenuRow(keymap, state, item, componentActions, canDispatch, generatedRows, modal))
    }
}

private fun actionRow(
    keymap: Keymap,
    state: AppState,
    action: ActionId,
    placeText: String?,
    componentActions: Map<ActionId, ComponentAction>,
    canDispatch: (ActionId) -> Boolean,
    modal: Boolean,
): MenuRow.Action? {
    val update = actionUpdate(action, state)
    if (!update.visible) return null
    val component = componentActions[action]
    return MenuRow.Action(
        action = action,
        text = placeText ?: component?.text ?: update.text ?: ActionNames.of(action),
        shortcut = menuShortcutText(keymap, action, state.focus),
        enabled = if (modal) {
            false
        } else {
            component?.enabled?.invoke() ?: (update.enabled && canDispatch(action))
        },
    )
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
