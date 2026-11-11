package stride.app.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.SearchIcon
import stride.app.ui.theme.CapsuleEdge
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.rememberNeonAlpha
import stride.app.ui.theme.selectionRow
import stride.core.vfs.VFile
import stride.search.SearchTab

/** The tab row and group headers share the panel, so the list shows two rows fewer. */
private val MAX_LIST_HEIGHT =
    DesignTokens.popupRowHeight * (DesignTokens.popupMaxRows - 2) - DesignTokens.popupTabHeight - DesignTokens.popupGroupHeight

/** Test tag on the row the keyboard selection is on; UI tests check it stays visible. */
internal const val SEARCH_SELECTED_ROW_TAG = "search-selected-row"

@Composable
fun SearchEverywherePopup(
    search: SearchEverywhere,
    onChoose: (SearchResultEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    initialTab: SearchTab = SearchTab.ALL,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var tab by remember { mutableStateOf(initialTab) }
    var results by remember { mutableStateOf(emptyList<SearchResultEntry>()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val rows = remember(results, tab) { popupRows(results, grouped = tab == SearchTab.ALL) }

    LaunchedEffect(query, tab) {
        // Drop the previous tab's list before the new fetch so Enter can never open a stale row.
        results = emptyList()
        selectedIndex = 0
        if (query.isEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val fetched = withContext(Dispatchers.Default) {
            search.fetch(query, tab, isActive = { isActive })
        }
        results = fetched
        selectedIndex = 0
        loading = false
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(DesignTokens.scrim.copy(alpha = DesignTokens.scrim.alpha * fade))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(DesignTokens.popupWidth)
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .backdropGlass(shape)
                .then(contentFade)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            Key.Tab -> {
                                tab = if (event.isShiftPressed) tab.previous() else tab.next()
                                true
                            }
                            Key.DirectionDown -> {
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                results.getOrNull(selectedIndex)?.let(onChoose)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            PopupHeader(query, focusRequester, onQueryChange = { query = it })
            TabRow(selected = tab, onSelect = { tab = it })
            if (query.isNotEmpty() && results.isEmpty() && !loading) {
                Text(
                    "No matching results",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                )
            } else if (rows.isNotEmpty()) {
                ResultList(rows, tab, results.size, selectedIndex, onChoose)
            }
            PopupHintBar("↑↓ Navigate", "⇥ Switch Tab", "↵ Open", "Esc Close")
        }
    }
}

@Composable
private fun PopupHeader(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(Modifier.size(16.dp), color = DesignTokens.accent)
        Spacer(Modifier.width(DesignTokens.space12))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search everywhere",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = DesignTokens.text,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                ),
                cursorBrush = SolidColor(DesignTokens.accent),
            )
        }
    }
}

@Composable
private fun TabRow(selected: SearchTab, onSelect: (SearchTab) -> Unit) {
    val shape = RoundedCornerShape(DesignTokens.radiusSmall)
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupTabHeight)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in SearchTab.entries) {
            val neon = rememberNeonAlpha(tab == selected)
            Box(
                Modifier
                    .glassCapsule(
                        shape = shape,
                        insetX = 0.dp,
                        insetY = 0.dp,
                        lineEdge = CapsuleEdge.BOTTOM,
                        lineAlpha = neon,
                    )
                    .semantics { this.selected = tab == selected }
                    .clickable(onClick = { onSelect(tab) })
                    .padding(horizontal = DesignTokens.space12, vertical = 3.dp),
            ) {
                Text(
                    tab.title,
                    color = if (tab == selected) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            if (tab != SearchTab.entries.last()) Spacer(Modifier.width(DesignTokens.space8))
        }
    }
}

/** One row of the result list: either a group label or an entry with its index in [results]. */
internal sealed interface PopupRow {
    data class Group(val label: String) : PopupRow

    data class Entry(val entry: SearchResultEntry, val index: Int) : PopupRow
}

/** Flattens [results] into rows, inserting a [PopupRow.Group] whenever the group label changes. */
internal fun popupRows(results: List<SearchResultEntry>, grouped: Boolean): List<PopupRow> {
    if (!grouped) return results.mapIndexed { index, entry -> PopupRow.Entry(entry, index) }
    val rows = ArrayList<PopupRow>(results.size)
    var label: String? = null
    var index = 0
    for (entry in results) {
        if (entry.groupLabel.isNotEmpty() && entry.groupLabel != label) {
            rows.add(PopupRow.Group(entry.groupLabel))
            label = entry.groupLabel
        }
        rows.add(PopupRow.Entry(entry, index))
        index++
    }
    return rows
}

@Composable
private fun ResultList(
    rows: List<PopupRow>,
    tab: SearchTab,
    resultCount: Int,
    selectedIndex: Int,
    onChoose: (SearchResultEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    LaunchedEffect(selectedIndex, rows) {
        // Wait for the layout before scrolling: scrollToItem during a measure pass leaves the
        // lazy list's composition pending.
        withFrameNanos { }
        // Rows include group headers, so find the selected entry's row rather than its index.
        val target = rows.indexOfFirst { it is PopupRow.Entry && it.index == selectedIndex }
        if (target < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        when {
            target < visible.first().index -> listState.scrollToItem(target)
            target > visible.last().index ->
                listState.scrollToItem((target - visible.size + 1).coerceAtLeast(0))
        }
    }
    val groups = rows.count { it is PopupRow.Group }
    // The tab row must not make the glass panel taller than the popup's old maximum height.
    val available = (MAX_LIST_HEIGHT - DesignTokens.popupGroupHeight * groups).coerceAtLeast(0.dp)
    val visibleItems = resultCount.coerceAtMost((available / DesignTokens.popupRowHeight).toInt())
    val height = DesignTokens.popupRowHeight * visibleItems + DesignTokens.popupGroupHeight * groups
    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        LazyColumn(state = listState) {
            items(rows) { row ->
                when (row) {
                    is PopupRow.Group -> GroupHeader(row.label)
                    is PopupRow.Entry -> ResultRow(row.entry, row.index == selectedIndex) { onChoose(row.entry) }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = DesignTokens.scrollbarStyle,
        )
    }
}

@Composable
private fun GroupHeader(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupGroupHeight)
            .padding(horizontal = DesignTokens.space16),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun ResultRow(entry: SearchResultEntry, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .then(if (selected) Modifier.testTag(SEARCH_SELECTED_ROW_TAG) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val file = entry.item as? VFile
        if (file != null) {
            FileTypeIcon(file.name, Modifier.size(13.dp), color = if (selected) DesignTokens.accent else DesignTokens.muted)
            Spacer(Modifier.width(DesignTokens.space8))
        }
        Text(
            entry.presentationText,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = secondaryText(entry)
        if (secondary.isNotEmpty()) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                secondary,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Files show the directory prefix only; other contributors carry their own secondary text. */
private fun secondaryText(entry: SearchResultEntry): String =
    if (entry.item is VFile) {
        val directory = entry.secondaryText.substringBeforeLast('/', "")
        if (directory.isEmpty()) "" else "$directory/"
    } else {
        entry.secondaryText
    }

/** Popup open fade from 0 to 1; the final state on the first frame when animations are disabled. */
@Composable
internal fun rememberPopupFadeAlpha(): Float {
    if (!LocalAnimationsEnabled.current) return 1f
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            1f,
            tween(DesignTokens.motionPopupMillis, easing = DesignTokens.standardEasing),
        )
    }
    return alpha.value
}

/**
 * An alpha layer for the popup contents only. The glass node itself and its ancestors stay
 * layer-free so `backdropGlass` keeps blurring the real canvas.
 */
@Composable
internal fun popupContentFade(alpha: Float): Modifier =
    if (LocalAnimationsEnabled.current) Modifier.graphicsLayer { this.alpha = alpha } else Modifier
