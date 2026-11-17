package stride.app.navigation

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.search.FindInPathScanner
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface
import stride.app.ui.theme.selectionRow
import stride.core.vfs.VFile
import java.nio.file.Path

/** One line of a row's code fragment; [place] marks the line the location is on. */
data class RecentLocationCodeLine(val number: Int, val text: String, val place: Boolean)

/**
 * One row of the Recent Locations popup. [line] is 1-based and `null` until the file's text is
 * loaded, so the rows can show before the code arrives.
 */
data class RecentLocationRow(
    val location: NavigationLocation,
    val fileName: String,
    val directory: String,
    val relativePath: String,
    val line: Int?,
    val code: List<RecentLocationCodeLine> = emptyList(),
)

/**
 * The popup's rows for [locations] in history order (oldest first): newest first, one row per
 * file and line with the newest kept, and the place under [caret] left out. [textOf] answers a
 * file's text, or `null` while it is not loaded; places whose line is unknown are compared by
 * their offset instead.
 */
fun recentLocationRows(
    locations: List<NavigationLocation>,
    root: Path,
    caret: NavigationLocation?,
    textOf: (Path) -> String?,
): List<RecentLocationRow> {
    val caretLine = caret?.let { place -> textOf(place.path)?.let { BookmarkManager.lineIndex(it, place.offset) } }
    val rows = ArrayList<RecentLocationRow>()
    val seen = HashSet<String>()
    for (location in locations.asReversed()) {
        val text = textOf(location.path)
        val line = text?.let { BookmarkManager.lineIndex(it, location.offset) }
        if (caret != null && location.path == caret.path && line != null && line == caretLine) continue
        val key = if (line != null) "${location.path}:$line" else "${location.path}@${location.offset}"
        if (!seen.add(key)) continue
        val relative = relativePathOf(root, location.path)
        rows.add(
            RecentLocationRow(
                location = location,
                fileName = location.path.fileName?.toString() ?: relative,
                directory = relative.substringBeforeLast('/', ""),
                relativePath = relative,
                line = line?.plus(1),
                code = if (text != null && line != null) codeAround(text, line) else emptyList(),
            ),
        )
    }
    return rows
}

/**
 * The row holding [selected] after [rows] were recomputed, so a place keeps its selection when
 * the caret line drops out and the rows collapse; the first row when it is gone.
 */
fun recentLocationIndex(rows: List<RecentLocationRow>, selected: NavigationLocation?): Int {
    val index = rows.indexOfFirst { it.location == selected }
    return if (index < 0) 0 else index
}

/**
 * The place's line and one line on each side, 0-based [line], clamped to what the text has. The
 * lines are trimmed like a bookmark's, so a fragment reads as one column.
 */
fun codeAround(text: String, line: Int): List<RecentLocationCodeLine> {
    val lastLine = text.count { it == '\n' }
    return ((line - 1)..(line + 1))
        .filter { it in 0..lastLine }
        .map {
            RecentLocationCodeLine(
                number = it + 1,
                text = BookmarkManager.lineText(text, it),
                place = it == line,
            )
        }
}

private fun relativePathOf(root: Path, path: Path): String {
    val rootPath = root.toAbsolutePath().normalize()
    val normalized = path.toAbsolutePath().normalize()
    if (!normalized.startsWith(rootPath)) return normalized.toString().replace('\\', '/')
    return runCatching { rootPath.relativize(normalized).toString().replace('\\', '/') }
        .getOrDefault(normalized.toString())
}

/**
 * Recent Locations over the navigation history: one row per file and line, each with the code
 * around the place. The open editors' text answers first; the rest is read from disk on
 * [Dispatchers.IO], so the rows are there before the code is.
 */
@Composable
fun RecentLocationsPopup(
    request: Int,
    locations: List<NavigationLocation>,
    caret: NavigationLocation?,
    projectRoot: Path,
    textOf: (Path) -> String?,
    onChoose: (NavigationLocation) -> Unit,
    onClose: () -> Unit,
) {
    var texts by remember(request) { mutableStateOf(emptyMap<Path, String>()) }
    var selectedLocation by remember(request) { mutableStateOf<NavigationLocation?>(null) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()
    val listState = rememberLazyListState()
    val latestLocations by rememberUpdatedState(locations)
    val latestTextOf by rememberUpdatedState(textOf)

    LaunchedEffect(request) {
        val paths = latestLocations.map { it.path }.distinct()
        // The open editors' text is read on this thread; only the disk reads go to IO.
        val open = paths.associateWith { latestTextOf(it) }
        texts = withContext(Dispatchers.IO) {
            paths.associateWith { path -> open[path] ?: FindInPathScanner.readText(VFile(path)) }
                .filterValues { it != null }
        }.mapValues { requireNotNull(it.value) }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    val rows = remember(locations, caret, projectRoot, texts) {
        recentLocationRows(locations, projectRoot, caret) { path -> texts[path] }
    }
    val index = recentLocationIndex(rows, selectedLocation)

    LaunchedEffect(index, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || visible.any { it.index == index }) return@LaunchedEffect
        listState.scrollToItem(index)
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
                .popupPanel("popup-recent-locations", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
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
                            Key.DirectionDown -> {
                                if (event.isMetaPressed) {
                                    rows.getOrNull(index)?.let { onChoose(it.location) }
                                } else if (rows.isNotEmpty()) {
                                    selectedLocation = rows[(index + 1).coerceAtMost(rows.lastIndex)].location
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (rows.isNotEmpty()) {
                                    selectedLocation = rows[(index - 1).coerceAtLeast(0)].location
                                }
                                true
                            }
                            Key.Enter, Key.NumPadEnter, Key.F4 -> {
                                rows.getOrNull(index)?.let { onChoose(it.location) }
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recent Locations",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                if (rows.isEmpty()) {
                    Text(
                        "No recent locations",
                        color = DesignTokens.textOnBusyGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                    )
                } else {
                    ResultList(rows, index, listState, onChoose)
                }
                PopupHintBar("↑↓ Navigate", "↵ Open", "Esc Close")
            }
        }
    }
}

@Composable
private fun ResultList(
    rows: List<RecentLocationRow>,
    selectedIndex: Int,
    listState: LazyListState,
    onChoose: (NavigationLocation) -> Unit,
) {
    val visibleRows = rows.size.coerceAtMost(RECENT_LOCATION_MAX_ROWS)
    Box(
        Modifier
            .fillMaxWidth()
            .height(RECENT_LOCATION_ROW_HEIGHT * visibleRows),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(rows) { index, row ->
                RecentLocationItem(row, index == selectedIndex) { onChoose(row.location) }
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
private fun RecentLocationItem(row: RecentLocationRow, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .height(RECENT_LOCATION_ROW_HEIGHT)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FileTypeIcon(
                row.fileName,
                Modifier.size(13.dp),
                color = if (selected) DesignTokens.accent else DesignTokens.muted,
            )
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                row.fileName,
                color = if (selected) DesignTokens.selectedText else DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.directory.isNotEmpty()) {
                Spacer(Modifier.width(DesignTokens.space12))
                Text(
                    row.directory,
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            row.line?.let { line ->
                Spacer(Modifier.width(DesignTokens.space12))
                Text(
                    "line $line",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    maxLines = 1,
                )
            }
        }
        for (line in row.code) {
            Text(
                line.text.ifEmpty { " " },
                color = if (line.place) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.codeTextSize,
                fontFamily = DesignTokens.codeFont,
                lineHeight = DesignTokens.codeLineHeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DesignTokens.space16),
            )
        }
    }
}

// Fixed, so a row does not jump when its code arrives after the popup shows.
private val RECENT_LOCATION_ROW_HEIGHT = 92.dp
private const val RECENT_LOCATION_MAX_ROWS = 6
