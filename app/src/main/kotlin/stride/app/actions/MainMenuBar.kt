package stride.app.actions

import androidx.compose.ui.awt.ComposeWindow
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.canDispatch
import stride.app.keys.dispatchAction
import stride.app.recentProjectRows
import stride.app.ui.FolderChooserGuard
import java.nio.file.Path
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.UIManager

/** M2: the shortcut is part of the item text, two spaces before it; one function owns the join. */
fun menuItemText(text: String, shortcut: String): String =
    if (shortcut.isEmpty()) text else "$text  $shortcut"

/** I7: the bar is the screen menu bar only on macOS, under the Aqua look and feel, when set. */
fun screenMenuBarApplies(isMac: Boolean, lookAndFeelId: String?, screenMenuProperty: String?): Boolean =
    isMac && lookAndFeelId == "Aqua" && screenMenuProperty == "true"

/** The property `AquaMenuBarUI` reads; `SystemAppearance.prepareProcess` sets it. */
const val SCREEN_MENU_BAR_PROPERTY: String = "apple.laf.useScreenMenuBar"

/**
 * M3: the eleven menus of [ActionGroups.mainMenu] as a Swing bar. Rows resolve when a menu opens
 * (M4); choosing one resolves it again and runs it through the dispatcher or the surface's
 * component action (M5). [dispatch] is a test seam for counting the calls.
 */
fun buildMainMenuBar(
    state: AppState,
    keymap: Keymap,
    chooser: FolderChooserGuard,
    bar: JMenuBar = JMenuBar(),
    dispatch: (ActionId, AppState, () -> Path?) -> Boolean = ::dispatchAction,
): JMenuBar {
    bar.isFocusable = false
    val builder = MainMenuBuilder(state, keymap, chooser, dispatch)
    for (item in ActionGroups.mainMenu.items) {
        val submenu = item as? GroupSubmenu ?: continue
        bar.add(builder.menu(submenu.text, submenu.items))
    }
    return bar
}

/** I7: installs the bar on [window] when [screenMenuBarApplies] holds; otherwise adds nothing. */
fun installMainMenuBar(
    window: ComposeWindow,
    state: AppState,
    keymap: Keymap,
    chooser: FolderChooserGuard,
): JMenuBar? {
    val applies = screenMenuBarApplies(
        isMac = KeyRouter.isMacOs(),
        lookAndFeelId = UIManager.getLookAndFeel()?.id,
        screenMenuProperty = System.getProperty(SCREEN_MENU_BAR_PROPERTY),
    )
    if (!applies) return null
    val bar = buildMainMenuBar(state, keymap, chooser)
    window.jMenuBar = bar
    return bar
}

private class MainMenuBuilder(
    private val state: AppState,
    private val keymap: Keymap,
    private val chooser: FolderChooserGuard,
    private val dispatch: (ActionId, AppState, () -> Path?) -> Boolean,
) {
    fun menu(text: String, items: List<GroupItem>): JMenu = RebuildingMenu(text, items)

    private fun modal(): Boolean = state.focus in Keymap.MODAL_CONTEXTS

    private fun componentActions(): Map<ActionId, ComponentAction> = when (state.focus) {
        KeyContext.EDITOR -> (state.session?.editors?.selected as? TextFileEditor)
            ?.menuComponentActions(state.clipboardHistory).orEmpty()
        KeyContext.TERMINAL -> state.terminalMenuActions?.invoke().orEmpty()
        else -> emptyMap()
    }

    private fun generatedRows(kind: GeneratedRows): List<MenuRow> = when (kind) {
        GeneratedRows.RECENT_PROJECTS -> recentProjectRows(state)
    }

    private fun resolve(item: GroupItem): MenuRow? = resolveMenuRow(
        keymap = keymap,
        state = state,
        item = item,
        componentActions = componentActions(),
        canDispatch = { action -> canDispatch(action, state, state.focus) },
        generatedRows = ::generatedRows,
        modal = modal(),
    )

    /** M5: the clicked row is resolved again; only an enabled row runs, and nothing runs while the chooser is open. */
    fun chooseAction(item: GroupItem) {
        if (chooser.isOpen) return
        val components = componentActions()
        val row = resolveMenuRow(
            keymap = keymap,
            state = state,
            item = item,
            componentActions = components,
            canDispatch = { action -> canDispatch(action, state, state.focus) },
            generatedRows = ::generatedRows,
            modal = modal(),
        ) as? MenuRow.Action ?: return
        if (!row.enabled) return
        val component = components[row.action]
        if (component != null) component.run() else dispatch(row.action, state, chooser::choose)
    }

    /** M5/M7: a generated row is generated again when chosen; only the row at the same path runs. */
    fun chooseCommand(item: GroupItem, row: MenuRow.Command) {
        if (chooser.isOpen) return
        val kind = (item as? GroupGenerated)?.kind ?: return
        val current = generatedRows(kind)
            .map { generated -> if (modal() && generated is MenuRow.Command) generated.copy(enabled = false) else generated }
            .filterIsInstance<MenuRow.Command>()
            .firstOrNull { it.text == row.text }
        if (current != null && current.enabled) current.run()
    }

    private class Entry(val row: MenuRow, val item: GroupItem?)

    /** A `JMenu` that rebuilds its children every time it is selected, already-selected opens included (I4). */
    private inner class RebuildingMenu(text: String, private val source: List<GroupItem>) : JMenu(text) {
        init {
            isFocusable = false
            fill()
        }

        override fun setSelected(selected: Boolean) {
            if (selected) fill()
            super.setSelected(selected)
        }

        private fun fill() {
            val entries = ArrayList<Entry>()
            for (item in source) {
                if (item is GroupGenerated) {
                    generatedRows(item.kind).forEach { row ->
                        val disabled = if (modal() && row is MenuRow.Command) row.copy(enabled = false) else row
                        entries += Entry(disabled, item)
                    }
                } else {
                    val row = resolve(item) ?: continue
                    entries += Entry(row, item)
                }
            }
            removeAll()
            for (entry in trimSeparators(entries)) addEntry(entry)
        }

        private fun trimSeparators(entries: List<Entry>): List<Entry> {
            val result = ArrayList<Entry>(entries.size)
            for (entry in entries) {
                if (entry.row is MenuRow.Separator && (result.isEmpty() || result.last().row is MenuRow.Separator)) {
                    continue
                }
                result += entry
            }
            while (result.lastOrNull()?.row is MenuRow.Separator) result.removeAt(result.lastIndex)
            return result
        }

        private fun addEntry(entry: Entry) {
            when (val row = entry.row) {
                MenuRow.Separator -> add(JPopupMenu.Separator().apply { isFocusable = false })
                is MenuRow.Submenu -> add(RebuildingMenu(row.text, submenuItems(entry.item)))
                is MenuRow.Action -> {
                    val item = entry.item ?: return
                    add(
                        JMenuItem(menuItemText(row.text, row.shortcut)).apply {
                            isFocusable = false
                            isEnabled = row.enabled
                            addActionListener { chooseAction(item) }
                        },
                    )
                }
                is MenuRow.Command -> {
                    val item = entry.item ?: return
                    add(
                        JMenuItem(menuItemText(row.text, row.shortcut)).apply {
                            isFocusable = false
                            isEnabled = row.enabled
                            addActionListener { chooseCommand(item, row) }
                        },
                    )
                }
            }
        }

        private fun submenuItems(item: GroupItem?): List<GroupItem> = when (item) {
            is GroupSubmenu -> item.items
            is GroupReference -> ActionGroups.byId[item.groupId]?.items.orEmpty()
            else -> emptyList()
        }
    }
}
