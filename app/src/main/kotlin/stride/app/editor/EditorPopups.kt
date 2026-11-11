package stride.app.editor

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.selectionRow

private val completionPopupWidth = 360.dp
private val renamePopupWidth = 320.dp
private const val COMPLETION_VISIBLE_ROWS = 8

/** Test tags the UI tests find the popups by. */
const val COMPLETION_POPUP_TAG: String = "completion-popup"
const val COMPLETION_FIELD_TAG: String = "completion-field"
const val COMPLETION_SELECTED_ROW_TAG: String = "completion-selected-row"
const val RENAME_POPUP_TAG: String = "rename-popup"
const val RENAME_FIELD_TAG: String = "rename-field"

/**
 * The completion popup. It keeps its own field so typing filters while the caret stays where
 * the prefix is; [CompletionRequest] remembers the range to replace.
 */
@Composable
fun CompletionPopup(
    request: CompletionRequest,
    onChoose: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember(request) {
        mutableStateOf(TextFieldValue(request.prefix, TextRange(request.prefix.length)))
    }
    var candidates by remember(request) { mutableStateOf<List<String>?>(null) }
    val query = input.text
    var selectedIndex by remember(request, query) { mutableStateOf(0) }
    val items = remember(request, query, candidates) {
        CodeCompletion.candidates(query, candidates.orEmpty())
    }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(request) {
        candidates = withContext(Dispatchers.Default) {
            val context = coroutineContext
            request.candidates { context.ensureActive() }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(selectedIndex, items) {
        withFrameNanos { }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || items.isEmpty()) return@LaunchedEffect
        when {
            selectedIndex < visible.first().index -> listState.scrollToItem(selectedIndex)
            selectedIndex > visible.last().index -> listState.scrollToItem(selectedIndex)
        }
    }

    val height = DesignTokens.popupRowHeight * items.size.coerceAtMost(COMPLETION_VISIBLE_ROWS)

    Box(
        Modifier
            .fillMaxSize()
            .testTag(COMPLETION_POPUP_TAG)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(completionPopupWidth)
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
                                if (items.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(items.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (items.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter, Key.Tab -> {
                                items.getOrNull(selectedIndex)?.let(onChoose)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompletionHeader(input, focusRequester) { input = it }
            if (items.isEmpty()) {
                Text(
                    if (candidates == null) "Loading…" else "No matching names",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(height)) {
                    LazyColumn(state = listState) {
                        itemsIndexed(items) { index, item ->
                            CompletionRow(item, index == selectedIndex) { onChoose(item) }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = DesignTokens.scrollbarStyle,
                    )
                }
            }
            PopupFooter("↑↓ navigate · ⏎ insert · esc close")
        }
    }
}

@Composable
private fun CompletionHeader(
    input: TextFieldValue,
    focusRequester: FocusRequester,
    onQueryChange: (TextFieldValue) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Completion",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        Box(Modifier.weight(1f).padding(start = DesignTokens.space12)) {
            BasicTextField(
                value = input,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(COMPLETION_FIELD_TAG),
                singleLine = true,
                textStyle = TextStyle(
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                ),
                cursorBrush = SolidColor(DesignTokens.accent),
            )
        }
    }
}

@Composable
private fun CompletionRow(item: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .then(if (selected) Modifier.testTag(COMPLETION_SELECTED_ROW_TAG) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The rename popup: a field prefilled with the identifier at the caret. */
@Composable
fun RenamePopup(
    initialName: String,
    onRename: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember(initialName) {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    var emptyHint by remember(initialName) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(initialName) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(RENAME_POPUP_TAG)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(renamePopupWidth)
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
                            Key.Enter, Key.NumPadEnter -> {
                                if (input.text.isEmpty()) {
                                    emptyHint = true
                                } else {
                                    onRename(input.text)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            Text(
                "Rename",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.padding(
                    horizontal = DesignTokens.space16,
                    vertical = DesignTokens.space8,
                ),
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = 6.dp)) {
                BasicTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        emptyHint = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(RENAME_FIELD_TAG),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    ),
                    cursorBrush = SolidColor(DesignTokens.accent),
                )
                if (input.text.isEmpty()) {
                    Text(
                        "new name",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
            }
            if (emptyHint) {
                Text(
                    "Type a new name",
                    color = DesignTokens.neonAmber,
                    fontSize = DesignTokens.labelTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16),
                )
            }
            PopupFooter("⏎ rename · esc cancel")
        }
    }
}

@Composable
private fun PopupFooter(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupFooterHeight)
            .drawBehind {
                drawLine(DesignTokens.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}
