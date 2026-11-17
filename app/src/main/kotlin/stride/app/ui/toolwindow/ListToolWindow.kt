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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.ui.FileTypeIcon
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.selectionRow

/** One row of a [ListToolWindow]; [indent] nests the primary text, [accent] paints it in `accent2`. */
data class ListWindowRow(
    val primary: AnnotatedString,
    val secondary: AnnotatedString? = null,
    val iconName: String? = null,
    val indent: Int = 0,
    val accent: Boolean = false,
)

/**
 * The selectable row list shared by the Bookmarks, Find and Structure tool windows. Up/Down move
 * the selection, Enter opens the selected row, Esc hands the keyboard back and leaves the window
 * visible; a click selects, a double click opens, and Delete/Backspace removes when [onDelete] is
 * set. [selectOnFocus] runs when the window takes the keyboard and once more when rows first
 * arrive, so a list parsed off the UI thread can still start on the requested row.
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
) {
    var selectedIndex by remember { mutableStateOf(0) }
    var pendingSelection by remember { mutableStateOf(false) }
    val index = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val latestSelectOnFocus by rememberUpdatedState(selectOnFocus)

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
        if (rows.isNotEmpty()) listState.scrollToItem(index.coerceIn(0, rows.lastIndex))
    }

    Column(
        modifier
            .focusRequester(focusRequester)
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
                            if (rows.isNotEmpty()) selectedIndex = (index + 1).coerceAtMost(rows.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            if (rows.isNotEmpty()) selectedIndex = (index - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Delete, Key.Backspace -> {
                            if (onDelete == null || rows.isEmpty()) {
                                false
                            } else {
                                onDelete(index)
                                true
                            }
                        }
                        Key.Enter, Key.NumPadEnter -> {
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
private fun ListRow(row: ListWindowRow, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
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
