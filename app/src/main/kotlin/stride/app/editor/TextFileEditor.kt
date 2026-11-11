package stride.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.rememberTextFieldVerticalScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
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
import stride.app.keys.KeyContext
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.LocalEditorInsets
import stride.app.ui.theme.LocalSidebarChrome
import stride.app.ui.theme.MirroredStripCache
import stride.app.ui.theme.SidebarChrome
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.backgroundExtension
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
import stride.search.DeclarationParser
import stride.search.ParsedDeclaration
import java.util.logging.Level
import java.util.logging.Logger

/** A clickable ▶ drawn in the gutter of [line] (0-based). */
data class GutterMarker(val line: Int, val onClick: () -> Unit)

/** Everything the cached mirror of the content strip depends on. */
private data class ExtensionKey(
    val layout: TextLayoutResult,
    val spans: List<TokenSpan>,
    val searchSpans: List<BackgroundSpan>,
    val horizontal: Int,
    val vertical: Int,
    val chrome: SidebarChrome,
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
)

/**
 * Single-file text editor backed by [LineStateHighlighter]. Files larger than
 * [MAX_HIGHLIGHTED_SIZE] open read-only without a highlighter.
 */
class TextFileEditor(
    override val file: VFile,
    val readOnly: Boolean = false,
    private val clipboard: TextClipboard = SystemTextClipboard,
) : FileEditor {
    private val log = Logger.getLogger("stride.app.editor")

    private var savedText: String = ""
    private var forcedReadOnly: Boolean = false
    private val highlighter: LineStateHighlighter?
    private val selectionStack = ArrayList<TextRange>()
    private var selectionStep = false
    private var hippieStep = false
    private var hippie: HippieState? = null
    @Volatile
    private var identifierSnapshot: IdentifierSnapshot? = null
    private var declarationLinesText: String? = null
    private var declarationLinesCache: List<Int> = emptyList()
    private var parsedDeclarationsText: String? = null
    private var parsedDeclarationsCache: List<ParsedDeclaration> = emptyList()

    /** Reports the focus context of editor sub-fields, such as the goto-line input. */
    var onFocusContext: (KeyContext) -> Unit = {}

    val isReadOnly: Boolean get() = readOnly || forcedReadOnly

    var value: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    var spans: List<TokenSpan> by mutableStateOf(emptyList())
        private set

    var searchSpans: List<BackgroundSpan> by mutableStateOf(emptyList())

    var gutterMarkers: List<GutterMarker> by mutableStateOf(emptyList())

    /** 0-based lines carrying a bookmark mark in the gutter. */
    var bookmarkLines: Set<Int> by mutableStateOf(emptySet())

    /** Called after a jump moved the caret, so the navigation history can record the target. */
    var onJump: (() -> Unit)? = null

    /** Called with the caret offset of a ⌘-click; the app turns it into Go to Declaration. */
    var onGoToDeclarationAt: ((Int) -> Unit)? = null

    /** Attached to the text field so the find bar and popups can hand focus back to the caret. */
    val focusRequester = FocusRequester()

    val undo = UndoManager()

    /** Vertical scroll position of the document viewport; the chrome bars read it. */
    val verticalScroll = EditorScrollState()

    /** Horizontal scroll position of the text body. */
    val horizontalScroll = EditorScrollState()

    /**
     * Test hook: forces the scroll layering policy so the frame harness can measure both paths on
     * the same document. Production code leaves it null and follows [shouldLayerScroll].
     */
    internal var scrollLayerOverride: Boolean? = null

    var gotoLineVisible: Boolean by mutableStateOf(false)

    /** Increments on every GO_TO_LINE request so an already-open popup takes focus again. */
    var gotoLineFocusRequests: Int by mutableIntStateOf(0)
        private set

    val commenter: Commenter?

    var isDisposed: Boolean = false
        private set

    private val text: String get() = value.text

    /**
     * Identifiers of the current text, skipping the range that is exactly [excluded] — the
     * identifier being completed. The scan is published as one immutable snapshot, so concurrent
     * readers never see two revisions mixed; [checkCancelled] throws to abandon a closed popup's scan.
     */
    internal fun identifiers(excluded: IntRange? = null, checkCancelled: () -> Unit = {}): List<String> {
        val current = text
        val snapshot = identifierSnapshot?.takeIf { it.text == current }
            ?: IdentifierSnapshot.scan(current, checkCancelled).also { identifierSnapshot = it }
        return snapshot.namesExcluding(excluded)
    }

    /** Declaration start lines of the current text, computed once per text revision. */
    internal val declarationLines: List<Int>
        get() {
            val current = text
            if (declarationLinesText != current) {
                declarationLinesText = current
                declarationLinesCache = DeclarationScan.lines(file.name, current)
            }
            return declarationLinesCache
        }

    /** Parsed declarations of the current text, computed once per text revision. */
    internal val parsedDeclarations: List<ParsedDeclaration>
        get() {
            val current = text
            if (parsedDeclarationsText != current) {
                parsedDeclarationsText = current
                parsedDeclarationsCache = DeclarationParser.parse(file.name, current)
            }
            return parsedDeclarationsCache
        }

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
        val changed = newValue.text != text || newValue.selection != value.selection
        if (changed && !selectionStep && !hippieStep) {
            selectionStack.clear()
            hippie = null
        }
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
        onJump?.invoke()
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
            gotoLineFocusRequests++
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
        ActionId.EDITOR_SELECT_WORD -> {
            expandWordSelection()
            true
        }
        ActionId.EDITOR_UNSELECT_WORD -> {
            shrinkWordSelection()
            true
        }
        ActionId.EDITOR_TOGGLE_CASE -> {
            if (!isReadOnly) applyEdit(EditActions.toggleCase(text, value.selection))
            true
        }
        ActionId.EDITOR_JOIN_LINES -> {
            if (!isReadOnly) applyEdit(EditActions.joinLines(text, value.selection))
            true
        }
        ActionId.EDITOR_START_NEW_LINE -> {
            if (!isReadOnly) applyEdit(EditActions.startNewLine(text, value.selection, before = false))
            true
        }
        ActionId.EDITOR_START_NEW_LINE_BEFORE -> {
            if (!isReadOnly) applyEdit(EditActions.startNewLine(text, value.selection, before = true))
            true
        }
        ActionId.EDITOR_MATCH_BRACE -> {
            moveCaret(EditActions.matchBrace(text, value.selection.start, CodeScan(text)))
            true
        }
        ActionId.EDITOR_CODE_BLOCK_START -> {
            moveCaret(EditActions.codeBlockStart(text, value.selection.start, CodeScan(text)))
            true
        }
        ActionId.EDITOR_CODE_BLOCK_END -> {
            moveCaret(EditActions.codeBlockEnd(text, value.selection.start, CodeScan(text)))
            true
        }
        ActionId.METHOD_UP, ActionId.METHOD_DOWN -> {
            val target = EditActions.declarationTarget(
                text,
                value.selection.start,
                declarationLines,
                up = action == ActionId.METHOD_UP,
            )
            moveCaret(target)
            true
        }
        ActionId.COPY_LINE -> copyLine(cut = false)
        ActionId.CUT_LINE -> copyLine(cut = true)
        ActionId.HIPPIE_COMPLETION -> hippieComplete()
        else -> false
    }

    /** Moves the caret to [target]; `null` leaves it where it is. */
    private fun moveCaret(target: TextRange?) {
        if (target == null) return
        applyValue(TextFieldValue(text, target), record = false)
    }

    private fun expandWordSelection() {
        val current = value.selection
        val next = EditActions.selectWord(text, current) ?: return
        if (selectionStack.size >= MAX_SELECTION_STEPS) selectionStack.removeAt(0)
        selectionStack.add(current)
        selectionStep = true
        applyValue(TextFieldValue(text, next), record = false)
        selectionStep = false
    }

    private fun shrinkWordSelection() {
        val previous = selectionStack.removeLastOrNull() ?: return
        selectionStep = true
        applyValue(TextFieldValue(text, previous), record = false)
        selectionStep = false
    }

    /** Copies the line at the caret, or cuts it when [cut]; a real selection is left to the field. */
    private fun copyLine(cut: Boolean): Boolean {
        if (!value.selection.collapsed) return false
        val range = EditActions.wholeLine(text, value.selection.start)
        if (!clipboard.writeText(text.substring(range.min, range.max))) return false
        if (cut && !isReadOnly) {
            val start = range.min
            val newText = text.substring(0, range.min) + text.substring(range.max)
            applyEdit(EditResult(newText, TextRange(start.coerceAtMost(newText.length))))
        }
        return true
    }

    /** Cycles the identifier prefix before the caret through words of this and other files. */
    fun hippieComplete(otherTexts: List<String> = emptyList()): Boolean {
        if (isReadOnly) return true
        val selection = value.selection
        val current = hippie
        if (current != null && selection.min == selection.max) {
            val word = current.candidates[current.index]
            if (selection.start == current.prefixStart + word.length &&
                text.startsWith(word, current.prefixStart)
            ) {
                val next = current.copy(index = (current.index + 1) % current.candidates.size)
                hippie = next
                applyHippie(next, word.length)
                return true
            }
        }
        val prefixRange = EditActions.identifierPrefix(text, selection.start)
        if (prefixRange.isEmpty()) return true
        val prefix = text.substring(prefixRange.first, prefixRange.last + 1)
        val candidates = EditActions.hippieCandidates(text, prefix, prefixRange.first, selection.start, otherTexts)
        if (candidates.isEmpty()) return true
        val state = HippieState(prefixRange.first, prefix, candidates, 0)
        hippie = state
        applyHippie(state, prefix.length)
        return true
    }

    private fun applyHippie(state: HippieState, replaceLength: Int) {
        val word = state.candidates[state.index]
        val start = state.prefixStart
        val end = start + replaceLength
        val newText = text.substring(0, start) + word + text.substring(end)
        hippieStep = true
        applyEdit(EditResult(newText, TextRange(start + word.length)))
        hippieStep = false
    }

    /** Renames every whole-word occurrence of [original]; returns how many were replaced. */
    fun renameWord(original: String, replacement: String): Int {
        if (isReadOnly || original.isEmpty()) return 0
        val ranges = EditActions.wholeWordRanges(text, original)
        if (ranges.isEmpty()) return 0
        if (original == replacement) return ranges.size
        val newText = buildString {
            var cursor = 0
            for (range in ranges) {
                append(text, cursor, range.first)
                append(replacement)
                cursor = range.last + 1
            }
            append(text, cursor, text.length)
        }
        val caret = ranges.first().first + replacement.length
        applyEdit(EditResult(newText, TextRange(caret)))
        return ranges.size
    }

    /** Replaces `[start, end)` with [replacement] as one undo step; used by the popups. */
    fun replaceRange(start: Int, end: Int, replacement: String) {
        if (isReadOnly) return
        val rangeStart = start.coerceIn(0, text.length)
        val rangeEnd = end.coerceIn(rangeStart, text.length)
        val newText = text.substring(0, rangeStart) + replacement + text.substring(rangeEnd)
        applyEdit(EditResult(newText, TextRange(rangeStart + replacement.length)))
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
        val vertical = verticalScroll
        val horizontal = horizontalScroll
        val fieldScroll = rememberTextFieldVerticalScrollState()
        // Keyed on the editor: switching tabs reuses this composition slot with a new document.
        var layout by remember(this) { mutableStateOf<TextLayoutResult?>(null) }
        val latestLayout = rememberUpdatedState(layout)
        val lineCount = highlighter?.lineCount() ?: (text.count { it == '\n' } + 1)
        var layeredScroll by remember(this) { mutableStateOf(lineCount >= LAYERED_SCROLL_ENTER_LINES) }
        LaunchedEffect(lineCount) {
            layeredScroll = shouldLayerScroll(lineCount, layeredScroll)
        }
        val layered = scrollLayerOverride ?: layeredScroll
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
            val chromeInsets = LocalEditorInsets.current
            val leftPad = chromeInsets.left
            val topPad = 8.dp + chromeInsets.top
            val bottomPad = 8.dp + chromeInsets.bottom
            val topPadPx = with(density) { topPad.toPx() }
            val viewportHeightPx = with(density) { (maxHeight - topPad - bottomPad).coerceAtLeast(0.dp).toPx() }
            LaunchedEffect(currentValue.selection.end, layout, maxWidth, maxHeight) {
                val textLayout = layout ?: return@LaunchedEffect
                // The layout can still be the previous document's for one frame after a tab switch.
                val caretOffset = currentValue.selection.end
                    .coerceIn(0, textLayout.layoutInput.text.length)
                val rect = textLayout.getCursorRect(caretOffset)
                // The text field spans the window; the glyph column starts past the gutter and the
                // visible region starts at the gutter's right edge.
                val contentLeft = with(density) { (leftPad + gutterWidth + DesignTokens.space16).toPx() }
                val visibleLeft = with(density) { (leftPad + gutterWidth).toPx() }
                val visibleRight = with(density) { maxWidth.toPx() }
                val contentTop = topPadPx
                val margin = with(density) { 24.dp.toPx() }

                val left = contentLeft + rect.left
                val right = contentLeft + rect.right
                val targetX = when {
                    right + margin > horizontal.value + visibleRight ->
                        horizontal.value + (right + margin - horizontal.value - visibleRight)
                    rect.left == 0f -> 0f
                    left - margin < horizontal.value + visibleLeft -> left - margin - visibleLeft
                    else -> horizontal.value.toFloat()
                }
                val targetXPx = targetX.toInt().coerceIn(0, horizontal.maxValue)
                if (targetXPx != horizontal.value) horizontal.scrollTo(targetXPx)

                val top = contentTop + rect.top
                val bottom = contentTop + rect.bottom
                // The chrome bars hold the visible viewport: it starts at topPad and ends at bottomPad.
                val targetY = when {
                    bottom + margin > vertical.value + topPadPx + viewportHeightPx ->
                        vertical.value + (bottom + margin - vertical.value - topPadPx - viewportHeightPx)
                    top - margin < vertical.value + topPadPx -> top - margin - topPadPx
                    else -> vertical.value.toFloat()
                }
                val targetYPx = targetY.toInt().coerceIn(0, vertical.maxValue)
                if (targetYPx != vertical.value) vertical.scrollTo(targetYPx)
            }
            val gutterHeight = maxOf((maxHeight - topPad - bottomPad).coerceAtLeast(0.dp), lineHeight * lineCount)
            val textMinWidth = (maxWidth - leftPad - gutterWidth - 32.dp).coerceAtLeast(0.dp)
            Canvas(Modifier.matchParentSize()) {
                val linePx = DesignTokens.codeLineHeight.toPx()
                val top = topPadPx + caretLine * linePx - vertical.value
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
            VerticalScrollViewport(vertical, Modifier.fillMaxSize(), layered = layered) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topPad, bottom = bottomPad),
            ) {
                HorizontalScrollViewport(horizontal, Modifier.fillMaxWidth(), layered = layered) {
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
                                .padding(
                                    start = leftPad + gutterWidth + DesignTokens.space16,
                                    end = DesignTokens.space16,
                                )
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.type != PointerEventType.Press) continue
                                            if (!event.keyboardModifiers.isMetaPressed) continue
                                            val change = event.changes.firstOrNull { it.changedToDown() } ?: continue
                                            val textLayout = latestLayout.value ?: continue
                                            val line = textLayout.getLineForVerticalPosition(change.position.y)
                                            val inside = insideLine(
                                                change.position,
                                                textLayout.getLineTop(line),
                                                textLayout.getLineBottom(line),
                                                textLayout.getLineRight(line),
                                            )
                                            if (!inside) continue
                                            val offset = textLayout.getOffsetForPosition(change.position)
                                                .coerceIn(0, text.length)
                                            change.consume()
                                            onGoToDeclarationAt?.invoke(offset)
                                        }
                                    }
                                },
                            textStyle = codeStyle,
                            readOnly = isReadOnly,
                            visualTransformation = transformation,
                            cursorBrush = SolidColor(DesignTokens.accent2),
                            scrollState = fieldScroll,
                            onTextLayout = { layout = it },
                        )
                    }
                }
                // The gutter stays put while the text slides under it and the sidebar.
                Box(
                    Modifier
                        .offset(x = leftPad)
                        .testTag("gutter")
                        .width(gutterWidth)
                        .height(gutterHeight)
                        .contentTint()
                        .drawBehind {
                            val linePx = DesignTokens.codeLineHeight.toPx()
                            val glowTop = caretLine * linePx
                            if (glowTop + linePx > 0f && glowTop < size.height) {
                                drawRect(
                                    DesignTokens.currentLineGlow,
                                    topLeft = Offset(0f, glowTop),
                                    size = Size(size.width, linePx),
                                )
                            }
                            val stroke = 1.dp.toPx()
                            drawRect(
                                DesignTokens.glassBorderSide,
                                topLeft = Offset(size.width - stroke, 0f),
                                size = Size(stroke, size.height),
                            )
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        }
                        .padding(end = 8.dp),
                ) {
                    Text(
                        gutterText,
                        modifier = Modifier.fillMaxWidth(),
                        style = codeStyle.copy(color = DesignTokens.muted, textAlign = TextAlign.End),
                    )
                    val bookmarked = bookmarkLines
                    if (bookmarked.isNotEmpty()) {
                        Canvas(Modifier.matchParentSize()) {
                            val linePx = lineHeight.toPx()
                            val glyphWidth = 7.dp.toPx()
                            val glyphHeight = 10.dp.toPx()
                            // The run marker owns 2..12 dp; the bookmark glyph takes the next column.
                            val left = 13.dp.toPx()
                            val notch = 3.dp.toPx()
                            for (line in bookmarked) {
                                val top = line * linePx + (linePx - glyphHeight) / 2
                                val path = Path().apply {
                                    moveTo(left, top)
                                    lineTo(left + glyphWidth, top)
                                    lineTo(left + glyphWidth, top + glyphHeight)
                                    lineTo(left + glyphWidth / 2, top + glyphHeight - notch)
                                    lineTo(left, top + glyphHeight)
                                    close()
                                }
                                drawPath(path, DesignTokens.neonAmber)
                            }
                        }
                    }
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
            }
            }
            val sidebar = LocalSidebarChrome.current
            val textLayout = layout
            if (sidebar != null && textLayout != null && !layered) {
                val extensionWidth = (maxHeight - sidebar.top - sidebar.bottom).coerceAtLeast(0.dp)
                val extensionKey = ExtensionKey(
                    layout = textLayout,
                    spans = currentSpans,
                    searchSpans = currentSearchSpans,
                    horizontal = horizontal.value,
                    vertical = vertical.value,
                    chrome = sidebar,
                    width = maxWidth,
                    height = maxHeight,
                )
                val stripCache = remember { MirroredStripCache() }
                DisposableEffect(stripCache) {
                    onDispose { stripCache.dispose() }
                }
                val textStartPx = with(density) {
                    (leftPad + gutterWidth + DesignTokens.space16).toPx()
                }
                // The extension also covers the window inset left of the sidebar, so code never
                // leaks past its outer edge; both regions show the same soft field.
                val extensionLeft = sidebar.left + sidebar.width
                Box(
                    Modifier
                        .offset(x = 0.dp, y = sidebar.top)
                        .width(extensionLeft)
                        .height(extensionWidth)
                        // The document right of the sidebar's edge, mirrored under its glass.
                        .backgroundExtension(
                            key = extensionKey,
                            cache = stripCache,
                            sourceLeft = extensionLeft,
                            sourceTop = sidebar.top,
                            sourceWidth = extensionLeft,
                        ) { canvas ->
                            canvas.save()
                            canvas.translate(textStartPx - horizontal.value, topPadPx - vertical.value)
                            textLayout.multiParagraph.paint(canvas)
                            canvas.restore()
                        },
                )
            }
            VerticalScrollbar(
                adapter = rememberEditorScrollbarAdapter(
                    vertical,
                    insetPx = with(density) { (chromeInsets.top + chromeInsets.bottom).toPx() },
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = chromeInsets.top, bottom = chromeInsets.bottom),
                style = DesignTokens.scrollbarStyle,
            )
            HorizontalScrollbar(
                adapter = rememberEditorScrollbarAdapter(horizontal),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = leftPad + gutterWidth, bottom = chromeInsets.bottom)
                    .fillMaxWidth(),
                style = DesignTokens.scrollbarStyle,
            )
            if (gotoLineVisible) {
                GotoLinePopup(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = chromeInsets.top + DesignTokens.space12, end = DesignTokens.space12),
                )
            }
        }
    }

    @Composable
    private fun GotoLinePopup(modifier: Modifier) {
        var input by remember { mutableStateOf("") }
        var fieldHadFocus by remember { mutableStateOf(false) }
        val inputFocus = remember { FocusRequester() }
        val shape = RoundedCornerShape(8.dp)
        LaunchedEffect(gotoLineFocusRequests) {
            withFrameNanos { }
            runCatching { inputFocus.requestFocus() }
        }
        Row(
            modifier
                .neonBorder(DesignTokens.accent, shape, radius = DesignTokens.glowRadiusLarge)
                .clip(shape)
                .backdropGlass(shape)
                .padding(horizontal = DesignTokens.space12, vertical = DesignTokens.space8)
                // The popup floats over the text field; a passive node keeps a press in its
                // padding from moving the caret underneath.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
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
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                fieldHadFocus = true
                                onFocusContext(KeyContext.EDITOR_FIELD)
                            } else if (fieldHadFocus) {
                                fieldHadFocus = false
                                onFocusContext(KeyContext.EDITOR)
                            }
                        }
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
                    textStyle = DesignTokens.chromeTextStyle.merge(
                        TextStyle(
                            color = DesignTokens.text,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                        ),
                    ),
                    cursorBrush = SolidColor(DesignTokens.accent),
                )
            }
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
        private const val MAX_SELECTION_STEPS: Int = 64
    }
}

/**
 * Whether [position] falls on the text of a line and not in the blank area after it or below the
 * last line, where a ⌘-click would otherwise take the nearest identifier.
 */
internal fun insideLine(
    position: Offset,
    lineTop: Float,
    lineBottom: Float,
    lineRight: Float,
): Boolean = position.y >= lineTop && position.y < lineBottom && position.x <= lineRight

/** The prefix replaced by hippie completion and the candidate it currently shows. */
private data class HippieState(
    val prefixStart: Int,
    val prefix: String,
    val candidates: List<String>,
    val index: Int,
)
