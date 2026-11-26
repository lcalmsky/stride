package stride.app.ui.toolwindow

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.actions.ComponentAction
import stride.app.actions.contextMenuRequest
import stride.app.keys.ActionId
import stride.app.ui.FileTypeIcon
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.selectionRow

/** Test tag of the focusable list a list tool window renders. */
internal const val LIST_WINDOW_TAG: String = "list-window"

/** One row of a [ListToolWindow]; [indent] nests the primary text, [accent] paints it in `accent2`. */
data class ListWindowRow(
    val primary: AnnotatedString,
    val secondary: AnnotatedString? = null,
    val iconName: String? = null,
    val indent: Int = 0,
    val accent: Boolean = false,
)

/**
 * A7: a list tool window's right-click menu. [actions] is the surface's own handlers for the row;
 * the window adds Jump to Source from its open callback, and [open] hands the rows and the pointer
 * to the surface that owns the app state.
 */
class ListWindowContextMenu(
    val actions: (row: Int) -> Map<ActionId, ComponentAction> = { emptyMap() },
    val open: (position: Offset, actions: Map<ActionId, ComponentAction>) -> Unit,
)

/**
 * The selectable row list shared by the Bookmarks, Find and Structure tool windows. Up/Down move
 * the selection, Enter, F4 and ⌘↓ open the selected row, Esc hands the keyboard back and leaves
 * the window visible; a click selects, a double click opens, and Delete/Backspace removes when
 * [onDelete] is set. [selectOnFocus] runs when the window takes the keyboard and once more when
 * rows first arrive, so a list parsed off the UI thread can still start on the requested row.
 * [onSelectionChange] reports the selected row so a header button can act on it. [onRight] and
 * [onLeft] handle → and ← when they are set: each receives the selected index and returns the new
 * index (the key is consumed) or `null` (the key is left alone). Without them the horizontal keys
 * are not consumed.
 */
@Composable
fun ListToolWindow(
    rows: List<ListWindowRow>,
    focusRequests: Int,
    onOpen: (Int) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((Int) -> Unit)? = null,
    selectOnFocus: (() -> Int)? = null,
    onSelectionChange: ((Int) -> Unit)? = null,
    onRight: ((Int) -> Int?)? = null,
    onLeft: ((Int) -> Int?)? = null,
    contextMenu: ListWindowContextMenu? = null,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    var pendingSelection by remember { mutableStateOf(false) }
    val index = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val latestSelectOnFocus by rememberUpdatedState(selectOnFocus)
    val latestSelectionChange by rememberUpdatedState(onSelectionChange)

    LaunchedEffect(index, rows.size) {
        latestSelectionChange?.invoke(index)
    }

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            pendingSelection = latestSelectOnFocus != null
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(pendingSelection, rows.size) {
        if (!pendingSelection || rows.isEmpty()) return@LaunchedEffect
        latestSelectOnFocus?.invoke()?.let { selectedIndex = it }
        pendingSelection = false
    }
    LaunchedEffect(index, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val target = index.coerceIn(0, rows.lastIndex)
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || visible.any { it.index == target }) return@LaunchedEffect
        if (target < visible.first().index) {
            listState.scrollToItem(target)
        } else {
            // Shift the list by the visible count so the row lands where the last visible row
            // was, instead of jumping to the top of the viewport.
            val shift = visible.last().index - visible.first().index
            listState.scrollToItem((target - shift).coerceAtLeast(0))
        }
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .testTag(LIST_WINDOW_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Escape -> {
                            onEscape()
                            true
                        }
                        Key.DirectionDown -> {
                            if (event.isMetaPressed) {
                                if (rows.isNotEmpty()) onOpen(index)
                            } else if (rows.isNotEmpty()) {
                                selectedIndex = (index + 1).coerceAtMost(rows.lastIndex)
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (rows.isNotEmpty()) selectedIndex = (index - 1).coerceAtLeast(0)
                            true
                        }
                        Key.DirectionRight -> {
                            val next = onRight?.invoke(index)
                            if (next == null) {
                                false
                            } else {
                                selectedIndex = next.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                                true
                            }
                        }
                        Key.DirectionLeft -> {
                            val next = onLeft?.invoke(index)
                            if (next == null) {
                                false
                            } else {
                                selectedIndex = next.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                                true
                            }
                        }
                        Key.Delete, Key.Backspace -> {
                            if (onDelete == null || rows.isEmpty()) {
                                false
                            } else {
                                onDelete(index)
                                true
                            }
                        }
                        Key.Enter, Key.NumPadEnter, Key.F4 -> {
                            if (rows.isNotEmpty()) onOpen(index)
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusable(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(rows) { rowIndex, row ->
                    ListRow(
                        row = row,
                        selected = rowIndex == index,
                        onSelect = { selectedIndex = rowIndex },
                        onOpen = {
                            selectedIndex = rowIndex
                            onOpen(rowIndex)
                        },
                        // A5: a right press selects the row and never opens it; the window never
                        // opens a menu over an empty list, which has no row to press.
                        onRightClick = contextMenu?.let { menu ->
                            { position: Offset ->
                                selectedIndex = rowIndex
                                menu.open(
                                    position,
                                    menu.actions(rowIndex) +
                                        (ActionId.EDIT_SOURCE to ComponentAction { onOpen(rowIndex) }),
                                )
                            }
                        },
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = DesignTokens.scrollbarStyle,
            )
        }
    }
}

/**
 * The one muted line a list tool window shows when it has nothing to list. With [onEscape] it
 * takes the keyboard like the list does, so Esc still hands focus back from an empty window.
 */
@Composable
fun ToolWindowEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    focusRequests: Int = 0,
    onEscape: (() -> Unit)? = null,
) {
    val label: @Composable (Modifier) -> Unit = { labelModifier ->
        Text(
            text,
            color = DesignTokens.muted,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = labelModifier.padding(DesignTokens.space12),
        )
    }
    if (onEscape == null) {
        label(modifier)
        return
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onEscape()
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        label(Modifier)
    }
}

@Composable
private fun ListRow(
    row: ListWindowRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRightClick: ((Offset) -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .then(if (onRightClick != null) Modifier.contextMenuRequest(onRightClick) else Modifier)
            .pointerInput(row) {
                detectTapGestures(onTap = { onSelect() }, onDoubleTap = { onOpen() })
            }
            .padding(start = DesignTokens.space16 + 12.dp * row.indent, end = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.iconName?.let { name ->
            FileTypeIcon(name, Modifier.size(13.dp), color = if (selected) DesignTokens.accent else DesignTokens.muted)
            Spacer(Modifier.width(DesignTokens.space8))
        }
        Text(
            row.primary,
            color = when {
                selected -> DesignTokens.selectedText
                row.accent -> DesignTokens.accent2
                else -> DesignTokens.text
            },
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.secondary?.let { secondary ->
            Spacer(Modifier.width(DesignTokens.space12))
            Text(
                secondary,
                color = DesignTokens.muted,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
