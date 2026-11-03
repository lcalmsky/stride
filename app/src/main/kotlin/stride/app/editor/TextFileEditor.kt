package stride.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.rememberTextFieldVerticalScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import stride.app.keys.ActionId
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.contentTint
import stride.app.ui.theme.neonBorder
import stride.core.vfs.VFile
import stride.lang.ColorScheme
import stride.lang.Commenter
import stride.lang.FileTypeRegistry
import stride.lang.LanguageFileType
import stride.lang.LineStateHighlighter
import stride.lang.SyntaxHighlighterFactory
import stride.lang.TextAttributes
import stride.lang.TextAttributesKey
import stride.lang.TokenSpan
import java.util.logging.Level
import java.util.logging.Logger

/** A clickable ▶ drawn in the gutter of [line] (0-based). */
data class GutterMarker(val line: Int, val onClick: () -> Unit)

/**
 * Single-file text editor backed by [LineStateHighlighter]. Files larger than
 * [MAX_HIGHLIGHTED_SIZE] open read-only without a highlighter.
 */
class TextFileEditor(
    override val file: VFile,
    val readOnly: Boolean = false,
) : FileEditor {
    private val log = Logger.getLogger("stride.app.editor")

    private var savedText: String = ""
    private var forcedReadOnly: Boolean = false
    private val highlighter: LineStateHighlighter?

    val isReadOnly: Boolean get() = readOnly || forcedReadOnly

    var value: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    var spans: List<TokenSpan> by mutableStateOf(emptyList())
        private set

    var searchSpans: List<BackgroundSpan> by mutableStateOf(emptyList())

    var gutterMarkers: List<GutterMarker> by mutableStateOf(emptyList())

    /** Attached to the text field so the find bar and popups can hand focus back to the caret. */
    val focusRequester = FocusRequester()

    val undo = UndoManager()

    var gotoLineVisible: Boolean by mutableStateOf(false)

    val commenter: Commenter?

    var isDisposed: Boolean = false
        private set

    private val text: String get() = value.text

    init {
        savedText = try {
            file.readText()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "Failed to read ${file.path}", t)
            forcedReadOnly = true
            ""
        }
        highlighter = if (isReadOnly) null else createHighlighter(file, savedText)
        commenter = (FileTypeRegistry.getFileType(file.name) as? LanguageFileType)
            ?.let { Commenter.forLanguage(it.language) }
        value = TextFieldValue(savedText, TextRange(savedText.length))
        spans = highlighter?.spans() ?: emptyList()
    }

    override val isModified: Boolean get() = text != savedText

    /** Applies a new text/selection, feeding only the changed range to the highlighter. */
    fun updateValue(newValue: TextFieldValue) = applyValue(newValue, record = true)

    private fun applyValue(newValue: TextFieldValue, record: Boolean) {
        if (newValue.text != text) {
            val change = computeTextChange(text, newValue.text)
            if (record) {
                undo.record(
                    UndoEntry(
                        offset = change.offset,
                        oldText = text.substring(change.offset, change.offset + change.oldLength),
                        newText = change.newText,
                        selectionBefore = value.selection,
                        selectionAfter = newValue.selection,
                    ),
                )
            }
            highlighter?.edit(change.offset, change.oldLength, change.newText)
            spans = highlighter?.spans() ?: emptyList()
        }
        value = newValue
    }

    fun setText(newText: String) {
        updateValue(TextFieldValue(newText, TextRange(newText.length)))
    }

    /** Reverts the last recorded change; `false` when there is nothing to undo. */
    fun undo(): Boolean {
        val entry = undo.undo() ?: return false
        val newText = text.substring(0, entry.offset) + entry.oldText +
            text.substring(entry.offset + entry.newText.length)
        applyValue(TextFieldValue(newText, entry.selectionBefore), record = false)
        return true
    }

    /** Reapplies the last undone change; `false` when there is nothing to redo. */
    fun redo(): Boolean {
        val entry = undo.redo() ?: return false
        val newText = text.substring(0, entry.offset) + entry.newText +
            text.substring(entry.offset + entry.oldText.length)
        applyValue(TextFieldValue(newText, entry.selectionAfter), record = false)
        return true
    }

    /** Moves the caret to `"line"` or `"line:column"` (1-based). Invalid input changes nothing. */
    fun gotoLine(input: String): Boolean {
        val parts = input.trim().split(":")
        if (parts.size > 2) return false
        val line = parts[0].toIntOrNull() ?: return false
        val column = if (parts.size == 2) parts[1].toIntOrNull() ?: return false else 1
        applyValue(TextFieldValue(text, EditActions.gotoLine(text, line, column)), record = false)
        return true
    }

    fun applyAction(action: ActionId): Boolean = when (action) {
        ActionId.EDITOR_DUPLICATE,
        ActionId.EDITOR_DELETE_LINE,
        ActionId.MOVE_LINE_UP,
        ActionId.MOVE_LINE_DOWN,
        ActionId.INDENT,
        ActionId.UNINDENT,
        -> {
            if (!isReadOnly) {
                val result = when (action) {
                    ActionId.EDITOR_DUPLICATE -> EditActions.duplicateLineOrSelection(text, value.selection)
                    ActionId.EDITOR_DELETE_LINE -> EditActions.deleteLines(text, value.selection)
                    ActionId.MOVE_LINE_UP -> EditActions.moveLines(text, value.selection, up = true)
                    ActionId.MOVE_LINE_DOWN -> EditActions.moveLines(text, value.selection, up = false)
                    ActionId.INDENT -> EditActions.indent(text, value.selection)
                    else -> EditActions.unindent(text, value.selection)
                }
                applyEdit(result)
            }
            true
        }
        ActionId.COMMENT_LINE -> {
            val prefix = commenter?.lineCommentPrefix
            if (prefix != null && !isReadOnly) {
                applyEdit(EditActions.toggleLineComment(text, value.selection, prefix))
            }
            true
        }
        ActionId.COMMENT_BLOCK -> {
            val prefix = commenter?.blockCommentPrefix
            val suffix = commenter?.blockCommentSuffix
            if (prefix != null && suffix != null && !isReadOnly) {
                applyEdit(EditActions.toggleBlockComment(text, value.selection, prefix, suffix))
            }
            true
        }
        ActionId.UNDO -> {
            undo()
            true
        }
        ActionId.REDO -> {
            redo()
            true
        }
        ActionId.GOTO_LINE -> {
            gotoLineVisible = true
            true
        }
        ActionId.EDITOR_ESCAPE -> when {
            gotoLineVisible -> {
                gotoLineVisible = false
                runCatching { focusRequester.requestFocus() }
                true
            }
            !value.selection.collapsed -> {
                applyValue(TextFieldValue(text, TextRange(value.selection.end)), record = false)
                true
            }
            else -> false
        }
        else -> false
    }

    private fun applyEdit(result: EditResult) {
        undo.breakMerge()
        updateValue(TextFieldValue(result.text, result.selection))
        undo.breakMerge()
    }

    override fun save() {
        if (isReadOnly || !isModified) return
        file.writeText(text)
        savedText = text
    }

    override fun dispose() {
        isDisposed = true
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content(modifier: Modifier) {
        val currentSpans = spans
        val currentSearchSpans = searchSpans
        val transformation = remember(currentSpans, currentSearchSpans) {
            HighlightingVisualTransformation(currentSpans, currentSearchSpans)
        }
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        val fieldScroll = rememberTextFieldVerticalScrollState()
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val lineCount = highlighter?.lineCount() ?: (text.count { it == '\n' } + 1)
        val gutterWidth = (lineCount.toString().length.coerceAtLeast(2) * 9 + 16 + 14).dp
        val codeStyle = TextStyle(
            fontFamily = DesignTokens.codeFont,
            fontSize = DesignTokens.codeTextSize,
            lineHeight = DesignTokens.codeLineHeight,
            color = defaultForeground(),
        )
        val currentValue = value
        val caretLine = lineIndexAt(currentValue.text, currentValue.selection.start)
        val lineHeight = with(LocalDensity.current) { DesignTokens.codeLineHeight.toDp() }
        val gutterText = remember(lineCount, caretLine) { buildGutterText(lineCount, caretLine) }

        BoxWithConstraints(modifier.contentTint()) {
            val density = LocalDensity.current
            LaunchedEffect(currentValue.selection.end, layout, maxWidth, maxHeight) {
                val textLayout = layout ?: return@LaunchedEffect
                val caretOffset = currentValue.selection.end.coerceIn(0, currentValue.text.length)
                val rect = textLayout.getCursorRect(caretOffset)
                val contentLeft = with(density) { DesignTokens.space16.toPx() }
                val contentTop = with(density) { 8.dp.toPx() }
                val viewportWidth = with(density) { (maxWidth - gutterWidth).toPx() }
                val viewportHeight = with(density) { maxHeight.toPx() }
                val margin = with(density) { 24.dp.toPx() }

                val left = contentLeft + rect.left
                val right = contentLeft + rect.right
                val targetX = when {
                    right + margin > horizontal.value + viewportWidth ->
                        horizontal.value + (right + margin - horizontal.value - viewportWidth)
                    rect.left == 0f -> 0f
                    left - margin < horizontal.value -> left - margin
                    else -> horizontal.value.toFloat()
                }
                val targetXPx = targetX.toInt().coerceIn(0, horizontal.maxValue)
                if (targetXPx != horizontal.value) horizontal.scrollTo(targetXPx)

                val top = contentTop + rect.top
                val bottom = contentTop + rect.bottom
                val targetY = when {
                    bottom + margin > vertical.value + viewportHeight ->
                        vertical.value + (bottom + margin - vertical.value - viewportHeight)
                    top - margin < vertical.value -> top - margin
                    else -> vertical.value.toFloat()
                }
                val targetYPx = targetY.toInt().coerceIn(0, vertical.maxValue)
                if (targetYPx != vertical.value) vertical.scrollTo(targetYPx)
            }
            val gutterHeight = maxOf(maxHeight - 16.dp, lineHeight * lineCount)
            val textMinWidth = (maxWidth - gutterWidth - 32.dp).coerceAtLeast(0.dp)
            Canvas(Modifier.matchParentSize()) {
                val linePx = DesignTokens.codeLineHeight.toPx()
                val top = 8.dp.toPx() + caretLine * linePx - vertical.value
                if (top + linePx > 0f && top < size.height) {
                    drawRect(
                        DesignTokens.currentLineGlow,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, linePx),
                    )
                    drawRect(
                        DesignTokens.accent2Glow,
                        topLeft = Offset(0f, top),
                        size = Size(2.dp.toPx(), linePx),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vertical)
                    .padding(vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .testTag("gutter")
                        .width(gutterWidth)
                        .height(gutterHeight)
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                DesignTokens.glassBorderSide,
                                topLeft = Offset(size.width - stroke, 0f),
                                size = Size(stroke, size.height),
                            )
                        }
                        .padding(end = 8.dp),
                ) {
                    Text(
                        gutterText,
                        modifier = Modifier.fillMaxWidth(),
                        style = codeStyle.copy(color = DesignTokens.muted, textAlign = TextAlign.End),
                    )
                    val currentMarkers = gutterMarkers
                    for (marker in currentMarkers) {
                        Canvas(
                            Modifier
                                .offset(
                                    x = 2.dp,
                                    y = (lineHeight * marker.line).coerceAtLeast(0.dp) +
                                        (lineHeight - 10.dp) / 2,
                                )
                                .size(10.dp)
                                .clickable { marker.onClick() },
                        ) {
                            val path = Path().apply {
                                moveTo(size.width * 0.1f, 0f)
                                lineTo(size.width, size.height / 2f)
                                lineTo(size.width * 0.1f, size.height)
                                close()
                            }
                            drawPath(path, DesignTokens.accent2)
                        }
                    }
                }
                Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    CompositionLocalProvider(
                        LocalTextSelectionColors provides TextSelectionColors(
                            handleColor = DesignTokens.accent,
                            backgroundColor = DesignTokens.selection,
                        ),
                    ) {
                        BasicTextField(
                            value = currentValue,
                            onValueChange = ::updateValue,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .widthIn(min = textMinWidth)
                                .padding(start = DesignTokens.space16, end = DesignTokens.space16),
                            textStyle = codeStyle,
                            readOnly = isReadOnly,
                            visualTransformation = transformation,
                            cursorBrush = SolidColor(DesignTokens.accent2),
                            scrollState = fieldScroll,
                            onTextLayout = { layout = it },
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vertical),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = DesignTokens.scrollbarStyle,
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = gutterWidth)
                    .fillMaxWidth(),
                style = DesignTokens.scrollbarStyle,
            )
            if (gotoLineVisible) {
                GotoLinePopup(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(DesignTokens.space12),
                )
            }
        }
    }

    @Composable
    private fun GotoLinePopup(modifier: Modifier) {
        var input by remember { mutableStateOf("") }
        val inputFocus = remember { FocusRequester() }
        val shape = RoundedCornerShape(8.dp)
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { inputFocus.requestFocus() }
        }
        Row(
            modifier
                .neonBorder(DesignTokens.accent, shape, radius = DesignTokens.glowRadiusLarge)
                .clip(shape)
                .backdropGlass(shape)
                .padding(horizontal = DesignTokens.space12, vertical = DesignTokens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Go to line:",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
            Spacer(Modifier.width(DesignTokens.space8))
            Box {
                if (input.isEmpty()) {
                    Text(
                        "line[:column]",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .width(96.dp)
                        .focusRequester(inputFocus)
                        .testTag(GOTO_LINE_TAG)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        if (gotoLine(input)) {
                                            gotoLineVisible = false
                                            runCatching { focusRequester.requestFocus() }
                                        }
                                        true
                                    }
                                    Key.Escape -> {
                                        gotoLineVisible = false
                                        runCatching { focusRequester.requestFocus() }
                                        true
                                    }
                                    else -> false
                                }
                            }
                        },
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

    private fun lineIndexAt(text: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        for (i in 0 until clamped) if (text[i] == '\n') line++
        return line
    }

    private fun buildGutterText(lineCount: Int, caretLine: Int): AnnotatedString = buildAnnotatedString {
        for (line in 1..lineCount) {
            val start = length
            append(line.toString())
            if (line == caretLine + 1) addStyle(SpanStyle(color = DesignTokens.accent2), start, length)
            if (line < lineCount) append('\n')
        }
    }

    private fun defaultForeground(): Color {
        val attributes: TextAttributes? = ColorScheme.DARK.getAttributes(TextAttributesKey.DEFAULT_TEXT)
        return attributes?.foreground?.let { Color(it.toInt()) } ?: DesignTokens.text
    }

    private fun createHighlighter(file: VFile, text: String): LineStateHighlighter? {
        val fileType = FileTypeRegistry.getFileType(file.name)
        if (fileType !is LanguageFileType) return null
        val syntaxHighlighter = SyntaxHighlighterFactory.forLanguage(fileType.language) ?: return null
        return LineStateHighlighter(
            syntaxHighlighter.getHighlightingLexer(),
            text,
            syntaxHighlighter::getTokenHighlights,
        )
    }

    companion object {
        const val MAX_HIGHLIGHTED_SIZE: Long = 2L * 1024 * 1024
        const val GOTO_LINE_TAG: String = "goto-line"
    }
}
