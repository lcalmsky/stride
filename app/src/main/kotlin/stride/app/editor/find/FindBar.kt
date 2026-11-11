package stride.app.editor.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.neonUnderline

private val FIND_ROW_HEIGHT = 34.dp
private val FIND_COUNTER_WIDTH = 110.dp

/** Height of the bar in [state], so the editor can reserve the same viewport inset. */
fun findBarHeight(state: FindBarState): Dp =
    if (state.replaceVisible) FIND_ROW_HEIGHT * 2 else FIND_ROW_HEIGHT

@Composable
fun FindBar(
    state: FindBarState,
    editor: TextFileEditor?,
    onFocusContext: (KeyContext) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    val queryFocus = remember { FocusRequester() }
    var queryValue by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(0, state.query.length)))
    }
    var replacementValue by remember { mutableStateOf(TextFieldValue(state.replacement)) }
    var fieldHadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(editor, editor?.value?.text, state.query, state.caseSensitive, state.regex) {
        state.refresh(editor)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { queryFocus.requestFocus() }
    }

    Column(
        modifier
            .fillMaxWidth()
            // The floating panel carries its own fill, rim and shadow inside its rounded shape.
            .capsuleGlass(shape)
            .neonUnderline(
                DesignTokens.accent2,
                thickness = 1.dp,
                insetX = DesignTokens.capsuleHeight / 2,
            )
            // Hit testing stops at the topmost pointer node, so a press in the bar's padding
            // does not reach the editor under it. Nothing is consumed.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
        Row(
            Modifier.fillMaxWidth().height(FIND_ROW_HEIGHT).padding(horizontal = DesignTokens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (state.error != null) {
                            Modifier.border(1.dp, DesignTokens.neonPink, RoundedCornerShape(DesignTokens.radiusSmall))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (queryValue.text.isEmpty()) {
                    Text(
                        "Find",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                BasicTextField(
                    value = queryValue,
                    onValueChange = { value ->
                        queryValue = value
                        state.query = value.text
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("find-query")
                        .focusRequester(queryFocus)
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                fieldHadFocus = true
                                onFocusContext(KeyContext.FIND_BAR)
                            } else if (fieldHadFocus) {
                                fieldHadFocus = false
                                onFocusContext(KeyContext.EDITOR)
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else if (event.key == Key.Escape) {
                                closeToEditor(state, editor, onFocusContext)
                                true
                            } else if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.isShiftPressed) state.previous(editor) else state.next(editor)
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    textStyle = DesignTokens.chromeTextStyle.merge(
                        TextStyle(
                            color = DesignTokens.text,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                        ),
                    ),
                    cursorBrush = SolidColor(DesignTokens.accent2),
                )
            }
            Spacer(Modifier.width(DesignTokens.space8))
            MatchCounter(state)
            Spacer(Modifier.width(DesignTokens.space8))
            Toggle("Aa", active = state.caseSensitive) {
                state.caseSensitive = !state.caseSensitive
            }
            Toggle(".*", active = state.regex) {
                state.regex = !state.regex
            }
            BarButton("↑") { state.previous(editor) }
            BarButton("↓") { state.next(editor) }
            BarButton("×") { closeToEditor(state, editor, onFocusContext) }
        }
        if (state.replaceVisible) {
            Row(
                Modifier.fillMaxWidth().height(FIND_ROW_HEIGHT).padding(horizontal = DesignTokens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (replacementValue.text.isEmpty()) {
                        Text(
                            "Replace",
                            color = DesignTokens.textSecondaryOnGlass,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                    BasicTextField(
                        value = replacementValue,
                        onValueChange = { value ->
                            replacementValue = value
                            state.replacement = value.text
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("find-replacement")
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    fieldHadFocus = true
                                    onFocusContext(KeyContext.FIND_BAR)
                                } else if (fieldHadFocus) {
                                    fieldHadFocus = false
                                    onFocusContext(KeyContext.EDITOR)
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    state.replaceCurrent(editor)
                                    true
                                } else {
                                    false
                                }
                            },
                        singleLine = true,
                        textStyle = DesignTokens.chromeTextStyle.merge(
                            TextStyle(
                                color = DesignTokens.text,
                                fontSize = DesignTokens.uiTextSize,
                                fontFamily = DesignTokens.uiFont,
                            ),
                        ),
                        cursorBrush = SolidColor(DesignTokens.accent2),
                    )
                }
                Spacer(Modifier.width(DesignTokens.space8))
                BarButton("Replace") { state.replaceCurrent(editor) }
                BarButton("Replace all") { state.replaceAll(editor) }
            }
        }
        }
    }
}

@Composable
private fun MatchCounter(state: FindBarState) {
    val text = when {
        state.error != null -> state.error
        state.query.isEmpty() -> ""
        state.matches.isEmpty() -> "No matches"
        else -> "${(state.currentIndex ?: 0) + 1} / ${state.matches.size}"
    }
    Text(
        text ?: "",
        color = if (state.error != null) DesignTokens.neonAmber else DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = FIND_COUNTER_WIDTH),
    )
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
private fun BarButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DesignTokens.radiusSmall)
    Text(
        label,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier
            .padding(start = 4.dp)
            .background(DesignTokens.glassFill, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun closeToEditor(
    state: FindBarState,
    editor: TextFileEditor?,
    onFocusContext: (KeyContext) -> Unit,
) {
    state.close()
    onFocusContext(KeyContext.EDITOR)
    editor?.focusRequester?.let { runCatching { it.requestFocus() } }
}
