package stride.app.hierarchy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import stride.app.actions.ComponentAction
import stride.app.keys.ActionId
import stride.app.ui.theme.DesignTokens
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowContextMenu
import stride.app.ui.toolwindow.ToolWindowEmptyState

/**
 * The Hierarchy tool window: the tree's title and note over its depth-first row list. The state
 * owns the expansion and loading; this window only translates rows and keys to it. Enter on a
 * node with a location calls [onOpen], on a node without one it flips the expansion.
 */
@Composable
fun HierarchyToolWindow(
    state: HierarchyToolWindowState,
    focusRequests: Int,
    onOpen: (HierarchyNode) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
    onContextMenu: ((Offset, Map<ActionId, ComponentAction>) -> Unit)? = null,
) {
    val tree = state.tree
    if (tree == null) {
        ToolWindowEmptyState(
            "No hierarchy",
            modifier,
            focusRequests = focusRequests,
            onEscape = onEscape,
        )
        return
    }
    Column(modifier) {
        Text(
            tree.title,
            color = DesignTokens.muted,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.padding(horizontal = DesignTokens.space12, vertical = 4.dp),
        )
        tree.note?.let { note ->
            Text(
                note,
                color = DesignTokens.muted,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.padding(horizontal = DesignTokens.space12, vertical = 4.dp),
            )
        }
        key(tree) {
            ListToolWindow(
                rows = state.listRows,
                focusRequests = focusRequests,
                onOpen = { index ->
                    val node = state.rows.getOrNull(index)?.node
                    when {
                        node == null -> Unit
                        node.location != null -> onOpen(node)
                        else -> state.toggle(index)
                    }
                },
                onEscape = onEscape,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRight = state::moveRight,
                onLeft = state::moveLeft,
                contextMenu = onContextMenu?.let { ListWindowContextMenu(open = it) },
            )
        }
    }
}
