package stride.app.search

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import stride.app.editor.find.FindOptions
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.selectionRow
import stride.search.FileNameIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun FindInPathPopup(
    index: FileNameIndex,
    initialQuery: String = "",
    initialWholeWord: Boolean = false,
    onChoose: (FindInPathHit) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(initialWholeWord) }
    var hits by remember { mutableStateOf(emptyList<FindInPathHit>()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(query, caseSensitive, regex, wholeWord) {
        if (query.isEmpty()) {
            hits = emptyList()
            selectedIndex = 0
            searching = false
            return@LaunchedEffect
        }
        searching = true
        val options = FindOptions(caseSensitive, regex, wholeWord)
        val scanned = withContext(Dispatchers.Default) {
            FindInPathScanner.scan(index.snapshot(), query, options) { !isActive }
        }
        hits = scanned
        selectedIndex = 0
        searching = false
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
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
                            Key.DirectionDown -> {
                                if (hits.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(hits.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (hits.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                hits.getOrNull(selectedIndex)?.let(onChoose)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            Header(
                query = query,
                onQueryChange = { query = it },
                caseSensitive = caseSensitive,
                regex = regex,
                wholeWord = wholeWord,
                onToggleCase = { caseSensitive = !caseSensitive },
                onToggleRegex = { regex = !regex },
                onToggleWholeWord = { wholeWord = !wholeWord },
                focusRequester = focusRequester,
            )
            if (query.isNotEmpty()) {
                StatusLine(searching = searching, hits = hits)
                when {
                    searching && hits.isEmpty() -> Unit
                    hits.isEmpty() -> Text(
                        "No results",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                    )
                    else -> ResultList(hits, selectedIndex, onChoose)
                }
            }
            PopupHintBar("↑↓ Navigate", "↵ Open", "Esc Close")
        }
    }
}

@Composable
private fun Header(
    query: String,
    onQueryChange: (String) -> Unit,
    caseSensitive: Boolean,
    regex: Boolean,
    wholeWord: Boolean,
    onToggleCase: () -> Unit,
    onToggleRegex: () -> Unit,
    onToggleWholeWord: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Find in Path",
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        Spacer(Modifier.width(DesignTokens.space16))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search text in project",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("find-in-path-query")
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = DesignTokens.text,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                ),
                cursorBrush = SolidColor(DesignTokens.accent),
            )
        }
        Spacer(Modifier.width(DesignTokens.space12))
        Toggle("Aa", caseSensitive, onToggleCase)
        Toggle("W", wholeWord, onToggleWholeWord)
        Toggle(".*", regex, onToggleRegex)
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) DesignTokens.accent2 else DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusLine(searching: Boolean, hits: List<FindInPathHit>) {
    val text = when {
        searching -> "searching…"
        hits.size >= FindInPathScanner.MAX_HITS -> "${FindInPathScanner.MAX_HITS}+ results (truncated)"
        else -> "${hits.size} results"
    }
    Text(
        text,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = 4.dp),
    )
}

@Composable
private fun ResultList(
    hits: List<FindInPathHit>,
    selectedIndex: Int,
    onChoose: (FindInPathHit) -> Unit,
) {
    val listState = rememberLazyListState()
    val visibleRows = hits.size.coerceAtMost(DesignTokens.popupMaxRows)
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight * visibleRows),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(hits) { index, hit ->
                HitRow(hit, index == selectedIndex) { onChoose(hit) }
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
private fun HitRow(hit: FindInPathHit, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(hit.file.name, Modifier.size(13.dp), color = if (selected) DesignTokens.accent else DesignTokens.muted)
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            "${hit.relativePath}:${hit.line}",
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            hitLine(hit),
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.codeFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun hitLine(hit: FindInPathHit) = buildAnnotatedString {
    append(hit.lineText)
    val from = (hit.column - 1).coerceIn(0, hit.lineText.length)
    val to = (from + (hit.end - hit.start)).coerceIn(from, hit.lineText.length)
    if (to > from) {
        addStyle(SpanStyle(color = DesignTokens.neonAmber, background = DesignTokens.neonAmberGlow), from, to)
    }
}
