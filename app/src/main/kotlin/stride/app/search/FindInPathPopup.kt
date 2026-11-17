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
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import stride.app.editor.find.FindOptions
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface
import stride.app.ui.theme.selectionRow
import stride.search.FileNameIndex
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FindInPathPopup(
    index: FileNameIndex,
    initialQuery: String = "",
    initialWholeWord: Boolean = false,
    onChoose: (FindInPathHit) -> Unit,
    onClose: () -> Unit,
    textOf: (Path) -> String? = { null },
    onOpenInFindWindow: (query: String, options: FindOptions) -> Unit = { _, _ -> },
    replace: Boolean = false,
    initialReplacement: String = "",
    onOpenReplaceInFindWindow: (query: String, options: FindOptions, replacement: String) -> Unit =
        { _, _, _ -> },
    onReplaceAll: (ReplacePlan) -> Unit = {},
) {
    var query by remember { mutableStateOf(initialQuery) }
    var replacement by remember { mutableStateOf(initialReplacement) }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(initialWholeWord) }
    var hits by remember { mutableStateOf(emptyList<FindInPathHit>()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var counting by remember { mutableStateOf(false) }
    var replaceMessage by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var pendingPlan by remember { mutableStateOf<ReplacePlan?>(null) }
    var queryFocused by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val replaceFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()

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
            FindInPathScanner.scan(index.snapshot(), query, options, textOf = textOf) { !isActive }
        }
        hits = scanned
        selectedIndex = 0
        searching = false
    }
    LaunchedEffect(query, replacement, caseSensitive, regex, wholeWord) {
        pendingPlan = null
        confirmation = null
        replaceMessage = null
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun cancelConfirmation() {
        pendingPlan = null
        confirmation = null
        runCatching { focusRequester.requestFocus() }
    }

    fun startReplaceAll() {
        if (query.isEmpty() || counting) return
        val plannedQuery = query
        val plannedReplacement = replacement
        val plannedOptions = FindOptions(caseSensitive, regex, wholeWord)
        counting = true
        replaceMessage = null
        scope.launch {
            val plan = withContext(Dispatchers.Default) {
                ReplaceInPath.plan(index.snapshot(), plannedQuery, plannedOptions, plannedReplacement, textOf)
            }
            counting = false
            // The query or replacement changed while counting: the plan no longer matches.
            if (plannedQuery != query || plannedReplacement != replacement) return@launch
            if (plan.occurrences == 0) {
                replaceMessage = "No occurrences to replace"
            } else {
                pendingPlan = plan
                confirmation = ReplaceInPath.confirmation(plan, plannedQuery, plannedReplacement)
            }
        }
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
                .popupPanel("popup-find-in-path", shape)
                .then(contentFade)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else if (confirmation != null) {
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                val plan = pendingPlan
                                pendingPlan = null
                                confirmation = null
                                if (plan != null) onReplaceAll(plan)
                                true
                            }
                            Key.Escape -> {
                                cancelConfirmation()
                                true
                            }
                            else -> true
                        }
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
                            Key.Tab -> {
                                if (replace) {
                                    runCatching {
                                        if (queryFocused) {
                                            replaceFocusRequester.requestFocus()
                                        } else {
                                            focusRequester.requestFocus()
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                when {
                                    event.isMetaPressed && event.isAltPressed -> {
                                        if (replace) startReplaceAll()
                                    }
                                    event.isMetaPressed -> {
                                        if (query.isNotEmpty()) {
                                            val options = FindOptions(caseSensitive, regex, wholeWord)
                                            if (replace) {
                                                onOpenReplaceInFindWindow(query, options, replacement)
                                            } else {
                                                onOpenInFindWindow(query, options)
                                            }
                                        }
                                    }
                                    else -> hits.getOrNull(selectedIndex)?.let(onChoose)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Header(
                    title = if (replace) "Replace in Path" else "Find in Path",
                    query = query,
                    onQueryChange = { query = it },
                    replacement = if (replace) replacement else null,
                    onReplacementChange = { replacement = it },
                    caseSensitive = caseSensitive,
                    regex = regex,
                    wholeWord = wholeWord,
                    onToggleCase = { caseSensitive = !caseSensitive },
                    onToggleRegex = { regex = !regex },
                    onToggleWholeWord = { wholeWord = !wholeWord },
                    focusRequester = focusRequester,
                    replacementFocusRequester = replaceFocusRequester,
                    onQueryFocus = { queryFocused = true },
                    onReplacementFocus = { queryFocused = false },
                )
                val shownConfirmation = confirmation
                val shownMessage = replaceMessage
                when {
                    shownConfirmation != null -> ReplaceConfirmationRow(
                        text = shownConfirmation,
                        onConfirm = {
                            val plan = pendingPlan
                            pendingPlan = null
                            confirmation = null
                            if (plan != null) onReplaceAll(plan)
                        },
                        onCancel = { cancelConfirmation() },
                    )
                    counting -> ReplaceStatusLine("counting…")
                    shownMessage != null -> ReplaceStatusLine(shownMessage)
                }
                if (query.isNotEmpty()) {
                    StatusLine(searching = searching, hits = hits)
                    when {
                        searching && hits.isEmpty() -> Unit
                        hits.isEmpty() -> Text(
                            "No results",
                            color = DesignTokens.textOnBusyGlass,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                            modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                        )
                        else -> ResultList(
                            hits = hits,
                            selectedIndex = selectedIndex,
                            onChoose = onChoose,
                            replacement = if (replace) replacement else null,
                            replacementColor = DesignTokens.accentOnPopupPlate,
                        )
                    }
                }
                if (replace) {
                    PopupHintBar("↵ Open", "⌘↵ Open in Find Window", "⌥⌘↵ Replace All", "Esc Close")
                } else {
                    PopupHintBar("↑↓ Navigate", "↵ Open", "⌘↵ Open in Find Window", "Esc Close")
                }
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String?,
    onReplacementChange: (String) -> Unit,
    caseSensitive: Boolean,
    regex: Boolean,
    wholeWord: Boolean,
    onToggleCase: () -> Unit,
    onToggleRegex: () -> Unit,
    onToggleWholeWord: () -> Unit,
    focusRequester: FocusRequester,
    replacementFocusRequester: FocusRequester,
    onQueryFocus: () -> Unit,
    onReplacementFocus: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = DesignTokens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
            Spacer(Modifier.width(DesignTokens.space16))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search text in project",
                        color = DesignTokens.textOnBusyGlass,
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
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus -> if (focus.isFocused) onQueryFocus() },
                    singleLine = true,
                    textStyle = DesignTokens.chromeTextStyle.merge(
                        TextStyle(
                            color = DesignTokens.text,
                            fontSize = 15.sp,
                            fontFamily = DesignTokens.uiFont,
                        ),
                    ),
                    cursorBrush = SolidColor(DesignTokens.accent),
                )
            }
            Spacer(Modifier.width(DesignTokens.space12))
            Toggle("Aa", caseSensitive, onToggleCase)
            Toggle("W", wholeWord, onToggleWholeWord)
            Toggle(".*", regex, onToggleRegex)
        }
        if (replacement != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = DesignTokens.space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (replacement.isEmpty()) {
                        Text(
                            "Replace with",
                            color = DesignTokens.textOnBusyGlass,
                            fontSize = 15.sp,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                    BasicTextField(
                        value = replacement,
                        onValueChange = onReplacementChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("find-in-path-replacement")
                            .focusRequester(replacementFocusRequester)
                            .onFocusChanged { focus -> if (focus.isFocused) onReplacementFocus() },
                        singleLine = true,
                        textStyle = DesignTokens.chromeTextStyle.merge(
                            TextStyle(
                                color = DesignTokens.text,
                                fontSize = 15.sp,
                                fontFamily = DesignTokens.uiFont,
                            ),
                        ),
                        cursorBrush = SolidColor(DesignTokens.accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplaceStatusLine(text: String) {
    Text(
        text,
        color = DesignTokens.textOnBusyGlass,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = 4.dp),
    )
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) DesignTokens.accent2 else DesignTokens.textOnBusyGlass,
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
        color = DesignTokens.textOnBusyGlass,
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
    replacement: String? = null,
    replacementColor: Color = DesignTokens.accent2,
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
                HitRow(hit, index == selectedIndex, replacement, replacementColor) { onChoose(hit) }
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
private fun HitRow(
    hit: FindInPathHit,
    selected: Boolean,
    replacement: String?,
    replacementColor: Color,
    onClick: () -> Unit,
) {
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
            if (replacement != null) replacePreviewLine(hit, replacement, replacementColor) else hitLine(hit),
            color = DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.codeFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

