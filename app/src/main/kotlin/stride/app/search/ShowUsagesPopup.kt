package stride.app.search

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import stride.app.editor.find.FindOptions
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface
import stride.app.ui.theme.selectionRow
import stride.search.IndexedFile
import java.nio.file.Path

/**
 * One Show Usages request: the identifier, the files and editor texts the scan reads, and the
 * hits it found so far. [id] is the request counter the popup's scan effect is keyed on.
 */
class ShowUsagesRequest(
    val id: Long,
    val name: String,
    val files: List<IndexedFile>,
    val textOf: (Path) -> String? = { null },
) {
    var hits: List<FindInPathHit> by mutableStateOf(emptyList())
        internal set

    var searching: Boolean by mutableStateOf(true)
        internal set

    var truncated: Boolean by mutableStateOf(false)
        internal set
}

internal const val USAGE_SELECTED_ROW_TAG = "usage-selected-row"

/** Show Usages: the same whole-word case-sensitive scan Find Usages runs, listed in a popup. */
@Composable
fun ShowUsagesPopup(
    request: ShowUsagesRequest,
    onOpen: (FindInPathHit) -> Unit,
    onOpenInFindWindow: () -> Unit,
    onClose: () -> Unit,
) {
    var selectedIndex by remember(request) { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()
    val hits = request.hits
    val latest = rememberUpdatedState(request)

    LaunchedEffect(request.id) {
        val current = latest.value
        val found = withContext(Dispatchers.Default) {
            FindInPathScanner.scan(
                files = current.files,
                query = current.name,
                options = FindOptions(caseSensitive = true, wholeWord = true),
                limit = FindInPathScanner.MAX_HITS,
                textOf = { path -> current.textOf(path) },
                shouldStop = { !isActive },
            )
        }
        Snapshot.withMutableSnapshot {
            current.hits = found
            current.truncated = found.size >= FindInPathScanner.MAX_HITS
            current.searching = false
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun openSelected() {
        hits.getOrNull(selectedIndex)?.let(onOpen)
    }

    Box(
        Modifier
            .fillMaxSize()
            .scrimSurface(DesignTokens.scrim.alpha * fade, holes)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(DesignTokens.popupWidth)
                .popupPanel("popup-usages", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when {
                            event.key == Key.Escape -> {
                                onClose()
                                true
                            }
                            event.key == Key.DirectionDown && event.isMetaPressed -> {
                                openSelected()
                                true
                            }
                            event.key == Key.DirectionUp -> {
                                if (hits.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            event.key == Key.DirectionDown -> {
                                if (hits.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(hits.lastIndex)
                                true
                            }
                            event.key == Key.F4 -> {
                                openSelected()
                                true
                            }
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) && event.isMetaPressed -> {
                                onOpenInFindWindow()
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                openSelected()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = DesignTokens.space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Usages of ${request.name}",
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
                Spacer(Modifier.width(DesignTokens.space12))
                Text(
                    when {
                        request.searching -> "Searching…"
                        request.truncated -> "${FindInPathScanner.MAX_HITS}+"
                        else -> "${hits.size}"
                    },
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            when {
                hits.isEmpty() -> Text(
                    if (request.searching) "Searching…" else "No usages",
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                )
                else -> UsageList(hits, selectedIndex, onOpen)
            }
            PopupHintBar("↑↓ Navigate", "↵ Open", "⌘↵ Open in Find Window", "Esc Close")
        }
    }
}

@Composable
private fun UsageList(hits: List<FindInPathHit>, selectedIndex: Int, onOpen: (FindInPathHit) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, hits) {
        // Wait for the layout before scrolling: scrollToItem during a measure pass leaves the
        // lazy list's composition pending.
        withFrameNanos { }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        when {
            selectedIndex < visible.first().index -> listState.scrollToItem(selectedIndex)
            selectedIndex > visible.last().index ->
                listState.scrollToItem((selectedIndex - visible.size + 1).coerceAtLeast(0))
        }
    }
    val visibleRows = hits.size.coerceAtMost(DesignTokens.popupMaxRows)
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight * visibleRows),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(hits) { index, hit ->
                UsageRow(hit, index == selectedIndex) { onOpen(hit) }
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
private fun UsageRow(hit: FindInPathHit, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .then(if (selected) Modifier.testTag(USAGE_SELECTED_ROW_TAG) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(hit.file.name, Modifier.size(13.dp), color = if (selected) DesignTokens.accent else DesignTokens.muted)
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            usageLine(hit),
            color = DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.codeFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            "${hit.file.name}:${hit.line}",
            color = if (selected) DesignTokens.selectedText else DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The hit's line without its indentation, with the matched text in the popup's accent role. */
private fun usageLine(hit: FindInPathHit): AnnotatedString {
    val raw = hit.lineText
    val body = raw.trim()
    val removed = raw.length - raw.trimStart().length
    return buildAnnotatedString {
        append(body)
        val from = (hit.column - 1 - removed).coerceIn(0, body.length)
        val to = (from + (hit.end - hit.start)).coerceIn(from, body.length)
        if (to > from) addStyle(SpanStyle(color = DesignTokens.accentOnPopupPlate), from, to)
    }
}
