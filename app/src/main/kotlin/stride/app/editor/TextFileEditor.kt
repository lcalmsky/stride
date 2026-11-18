package stride.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.problems.FileProblem
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.EdgeBandCache
import stride.app.ui.theme.GLASS_FIND_MATCH_ALPHA
import stride.app.ui.theme.LocalEditorInsets
import stride.app.ui.theme.LocalSidebarChrome
import stride.app.ui.theme.MirroredStripCache
import stride.app.ui.theme.SidebarChrome
import stride.app.glass.LocalNativeGlass
import stride.app.ui.theme.backgroundExtension
import stride.app.ui.theme.contentTint
import stride.app.ui.theme.editorFieldLuminance
import stride.app.ui.theme.neonBorder
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrollEdgeBands
import stride.app.vcs.HunkKind
import stride.app.vcs.LineHunk
import stride.core.vfs.VFile
import stride.lang.ColorScheme
import stride.lang.Commenter
import stride.lang.FileTypeRegistry
import stride.lang.LanguageFileType
import stride.lang.LineStateHighlighter
import stride.lang.ProblemSeverity
import stride.lang.SyntaxHighlighterFactory
import stride.lang.TextAttributes
import stride.lang.TextAttributesKey
import stride.lang.TextEdit
import stride.lang.TokenSpan
import stride.search.DeclarationParser
import stride.search.ParsedDeclaration
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt

/** A clickable ▶ drawn in the gutter of [line] (0-based). */
data class GutterMarker(val line: Int, val onClick: () -> Unit)

/**
 * Test seam: how many document lines the edge band and the background extension painted in
 * their last draw. A scroll frame must keep both bounded by the visible line count.
 */
internal object EditorBandPaintStats {
    var bandLines: Int = 0
    var mirrorLines: Int = 0
}

/** Extra lines painted past the visible range, so the band blur still samples nearby content. */
private const val BAND_LINE_MARGIN = 4

/** Lines per cached slice; scrolling inside a slice only translates it. */
private const val SLICE_LINES = 64

/**
 * Keeps the measured paragraph for a run of lines across scroll frames. A scroll step changes
 * which lines are visible, but the glyphs of the lines it skips are unchanged; only crossing a
 * slice boundary (every [SLICE_LINES] lines) measures a new one.
 */
internal class LineSliceCache {
    private var layout: TextLayoutResult? = null
    private var first = -1
    private var last = -1
    private var paragraph: MultiParagraph? = null

    /** The paragraph holding [range] and the line its first line starts at. */
    fun slice(layout: TextLayoutResult, range: IntRange): Pair<MultiParagraph, Int> {
        if (this.layout === layout && range.first >= first && range.last <= last) {
            return checkNotNull(paragraph) to first
        }
        val chunkFirst = (range.first / SLICE_LINES) * SLICE_LINES
        val chunkLast = minOf(
            layout.lineCount - 1,
            (range.last / SLICE_LINES) * SLICE_LINES + SLICE_LINES - 1,
        )
        val text = layout.layoutInput.text
        val start = layout.getLineStart(chunkFirst)
        val end = layout.getLineEnd(chunkLast, visibleEnd = false).coerceAtLeast(start)
        val built = MultiParagraph(
            annotatedString = text.subSequence(start, end),
            style = layout.layoutInput.style,
            // The slice is measured at the same maximum as the full layout; the minimums must be
            // zero, which the field's own layout does not guarantee.
            constraints = androidx.compose.ui.unit.Constraints(
                maxWidth = layout.layoutInput.constraints.maxWidth,
            ),
            density = layout.layoutInput.density,
            fontFamilyResolver = layout.layoutInput.fontFamilyResolver,
            maxLines = layout.layoutInput.maxLines,
            overflow = layout.layoutInput.overflow,
        )
        this.layout = layout
        this.first = chunkFirst
        this.last = chunkLast
        paragraph = built
        return built to chunkFirst
    }
}

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

/** Everything the blurred scroll edge bands depend on. */
private data class BandKey(
    val layout: TextLayoutResult,
    val gutter: TextLayoutResult?,
    val horizontal: Int,
    val vertical: Int,
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
    val top: androidx.compose.ui.unit.Dp,
    val bottom: androidx.compose.ui.unit.Dp,
    val left: androidx.compose.ui.unit.Dp,
)

/**
 * Single-file text editor backed by [LineStateHighlighter]. Files larger than
 * [MAX_HIGHLIGHTED_SIZE] open read-only without a highlighter.
 */
class TextFileEditor(
    override val file: VFile,
    val readOnly: Boolean = false,
    internal var clipboard: TextClipboard = SystemTextClipboard,
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

    /** The carets that are not the primary one, oldest first. */
    var secondaryCarets: List<TextRange> by mutableStateOf(emptyList())
        private set

    /** The collapsed ranges, outer regions first; nested members stay collapsed when one opens. */
    internal var foldedRegions: List<FoldRegion> by mutableStateOf(emptyList())
        private set

    var columnMode: Boolean by mutableStateOf(false)
        private set

    /** Every caret in addition order; the last one is [value]'s selection, the primary caret. */
    val carets: List<TextRange> get() = secondaryCarets + value.selection

    /** The caret operation in flight; each preserves only the state its next step reads. */
    private enum class CaretStep { NONE, OCCURRENCE, CLONE, BLOCK, VERTICAL }

    private var caretStep: CaretStep? = null
    private var wholeWords = false
    private var desiredColumns: List<Int>? = null
    private var cloneColumn: Int? = null
    private var cloneDirection: Boolean? = null
    private var blockActive = false
    private var blockAnchorLine = 0
    private var blockAnchorCol = 0
    private var blockLeadLine = 0
    private var blockLeadCol = 0

    var spans: List<TokenSpan> by mutableStateOf(emptyList())
        private set

    var searchSpans: List<BackgroundSpan> by mutableStateOf(emptyList())

    /** Matches of Highlight Usages in File, drawn as background spans under the text. */
    var usageHighlights: List<IntRange> by mutableStateOf(emptyList())

    var gutterMarkers: List<GutterMarker> by mutableStateOf(emptyList())

    /** The problems of the current text, drawn as squiggles and walked by F2/⇧F2. */
    var problems: List<FileProblem> by mutableStateOf(emptyList())

    /** 0-based lines carrying a bookmark mark in the gutter. */
    var bookmarkLines: Set<Int> by mutableStateOf(emptySet())

    /** The document's changes against HEAD, drawn as gutter marks; empty draws nothing. */
    internal var vcsHunks: List<LineHunk> by mutableStateOf(emptyList())

    /** Called with the hunk under a click on a gutter change mark. */
    var onChangeMarkClick: ((LineHunk) -> Unit)? = null

    /** Called after a jump moved the caret, so the navigation history can record the target. */
    var onJump: (() -> Unit)? = null

    /**
     * Called after the editor applied a text change: one entry per replaced range, ascending and
     * each in the coordinates of the text after the previous ones were applied, plus whether the
     * change entered the undo stack; undo, redo and every other unrecorded path report `false`.
     * [recordIndex] points at the range the caret produced when the change has several, so an edit
     * place records where the user was; it is 0 for a single range.
     */
    var onTextChange: ((changes: List<TextChange>, recorded: Boolean, recordIndex: Int) -> Unit)? = null

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

    /** Increments on every scroll-to-center request so [Content] can act on it once. */
    var centerRequests: Int by mutableIntStateOf(0)
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

    /**
     * Applies a new text/selection from a program path; the secondary carets are dropped.
     * Anything a keystroke produces reaches [onFieldValueChange] instead.
     */
    fun updateValue(newValue: TextFieldValue) = applyValue(newValue, record = true)

    /**
     * Applies [newValue], built from [changes] (ascending, each in the coordinates after the
     * previous ones), as one undo step; the observer hears every range instead of their merged span.
     */
    fun updateValue(newValue: TextFieldValue, changes: List<TextChange>) =
        applyValue(newValue, record = true, reported = changes)

    private fun applyValue(newValue: TextFieldValue, record: Boolean, reported: List<TextChange>? = null) {
        val changed = newValue.text != text || newValue.selection != value.selection
        if (changed && !selectionStep && !hippieStep && caretStep == null) {
            selectionStack.clear()
            hippie = null
        }
        val cleared = if (caretStep == null && secondaryCarets.isNotEmpty()) secondaryCarets else emptyList()
        if (caretStep == null) clearCaretState()
        val change = if (newValue.text != text) computeTextChange(text, newValue.text) else null
        if (change != null) {
            if (record) {
                undo.record(
                    UndoEntry(
                        offset = change.offset,
                        oldText = text.substring(change.offset, change.offset + change.oldLength),
                        newText = change.newText,
                        selectionBefore = value.selection,
                        selectionAfter = newValue.selection,
                        secondaryBefore = cleared,
                        pieces = reported?.takeIf { it.size > 1 },
                    ),
                )
            }
            highlighter?.edit(change.offset, change.oldLength, change.newText)
            spans = highlighter?.spans() ?: emptyList()
        }
        if (caretStep == null) secondaryCarets = emptyList()
        value = newValue
        reconcileFolds(change, carets)
        if (change != null) reportTextChange(reported ?: listOf(change), recorded = record)
    }

    /** Tells the observer about the replaced ranges and drops the usage highlights they invalidate. */
    private fun reportTextChange(changes: List<TextChange>, recorded: Boolean, recordIndex: Int = 0) {
        usageHighlights = emptyList()
        onTextChange?.invoke(changes, recorded, recordIndex)
    }

    /** Applies a multi-caret result: the last caret becomes [value]'s selection. */
    private fun applyCarets(edit: CaretEdit, record: Boolean) {
        val length = edit.text.length
        val clamped = edit.carets.map { TextRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }
        val normalized = MultiCaret.normalize(clamped)
        val primary = normalized.lastOrNull() ?: value.selection
        val secondaries = if (normalized.size > 1) normalized.subList(0, normalized.size - 1).toList() else emptyList()
        val change = if (edit.text != text) computeTextChange(text, edit.text) else null
        val reported = when {
            change == null -> emptyList()
            edit.changes.size > 1 -> edit.changes
            else -> listOf(change)
        }
        if (change != null) {
            if (record) {
                undo.record(
                    UndoEntry(
                        offset = change.offset,
                        oldText = text.substring(change.offset, change.offset + change.oldLength),
                        newText = change.newText,
                        selectionBefore = value.selection,
                        selectionAfter = primary,
                        secondaryBefore = secondaryCarets,
                        secondaryAfter = secondaries,
                        typing = edit.typing,
                        pieces = reported.takeIf { it.size > 1 },
                    ),
                )
            }
            highlighter?.edit(change.offset, change.oldLength, change.newText)
            spans = highlighter?.spans() ?: emptyList()
        }
        when (caretStep) {
            null -> {
                selectionStack.clear()
                hippie = null
                clearCaretState()
            }
            CaretStep.OCCURRENCE -> {
                desiredColumns = null
                cloneColumn = null
                cloneDirection = null
                blockActive = false
            }
            CaretStep.CLONE -> {
                wholeWords = false
                desiredColumns = null
                blockActive = false
            }
            CaretStep.BLOCK -> {
                wholeWords = false
                desiredColumns = null
                cloneColumn = null
                cloneDirection = null
            }
            CaretStep.VERTICAL -> {
                wholeWords = false
                cloneColumn = null
                cloneDirection = null
                blockActive = false
            }
            CaretStep.NONE -> clearCaretState()
        }
        secondaryCarets = secondaries
        value = TextFieldValue(edit.text, primary, edit.composition)
        reconcileFolds(change, carets)
        if (change != null) {
            val recordIndex = if (reported.size > 1) edit.primaryChange.coerceIn(0, reported.lastIndex) else 0
            reportTextChange(reported, recorded = record, recordIndex = recordIndex)
        }
    }

    private fun clearCaretState() {
        wholeWords = false
        desiredColumns = null
        cloneColumn = null
        cloneDirection = null
        blockActive = false
    }

    /** A text field change: a selection-only change drops the secondary carets, a text change replicates into them. */
    internal fun onFieldValueChange(newValue: TextFieldValue) {
        if (newValue.text == text) {
            when {
                newValue.selection != value.selection -> applyValue(snappedSelection(newValue), record = false)
                newValue.composition != value.composition -> value = newValue
            }
            return
        }
        if (secondaryCarets.isEmpty()) {
            applyValue(newValue, record = true)
            return
        }
        val replicated = MultiCaret.replicate(text, value.selection, value.composition, newValue, secondaryCarets)
        if (replicated != null) applyCarets(replicated, record = true) else applyValue(newValue, record = true)
    }

    fun setText(newText: String) {
        updateValue(TextFieldValue(newText, TextRange(newText.length)))
    }

    /** Reverts the last recorded change; `false` when there is nothing to undo. */
    fun undo(): Boolean {
        val entry = undo.undo() ?: return false
        val newText = text.substring(0, entry.offset) + entry.oldText +
            text.substring(entry.offset + entry.newText.length)
        restoreHistory(
            TextFieldValue(newText, entry.selectionBefore),
            entry.secondaryBefore,
            inversePieces(entry, newText),
        )
        return true
    }

    /** Reapplies the last undone change; `false` when there is nothing to redo. */
    fun redo(): Boolean {
        val entry = undo.redo() ?: return false
        val newText = text.substring(0, entry.offset) + entry.newText +
            text.substring(entry.offset + entry.oldText.length)
        restoreHistory(TextFieldValue(newText, entry.selectionAfter), entry.secondaryAfter, entry.pieces)
        return true
    }

    /**
     * A multi-range entry's pieces reversed, ascending: each replaces the text the change wrote at
     * that range with the old text it removed, taken from [oldText]. Undoing walks back to the text
     * before the change, so a piece lands where its range sat in that text.
     */
    private fun inversePieces(entry: UndoEntry, oldText: String): List<TextChange>? {
        val pieces = entry.pieces ?: return null
        val changes = ArrayList<TextChange>(pieces.size)
        var shift = 0
        for (piece in pieces) {
            val start = piece.offset - shift
            changes.add(
                TextChange(
                    offset = start,
                    oldLength = piece.newText.length,
                    newText = oldText.substring(start, start + piece.oldLength),
                ),
            )
            shift += piece.newText.length - piece.oldLength
        }
        return changes
    }

    /** Restores a history entry's text, primary caret and secondary carets as one state. */
    private fun restoreHistory(
        newValue: TextFieldValue,
        secondaries: List<TextRange>,
        reported: List<TextChange>? = null,
    ) {
        val change = if (newValue.text != text) computeTextChange(text, newValue.text) else null
        if (change != null) {
            highlighter?.edit(change.offset, change.oldLength, change.newText)
            spans = highlighter?.spans() ?: emptyList()
        }
        val length = newValue.text.length
        val clamped = secondaries.map { TextRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }
        val primary = TextRange(
            newValue.selection.start.coerceIn(0, length),
            newValue.selection.end.coerceIn(0, length),
        )
        val all = MultiCaret.normalize(clamped + primary)
        secondaryCarets = all.dropLast(1)
        value = TextFieldValue(newValue.text, all.last())
        selectionStack.clear()
        hippie = null
        clearCaretState()
        reconcileFolds(change, carets)
        if (change != null) reportTextChange(reported ?: listOf(change), recorded = false)
    }

    /**
     * Moves the caret to [offset], dropping the secondary carets and unfolding a collapsed range
     * the way [gotoLine] does; the jump is recorded through [onJump].
     */
    fun jumpTo(offset: Int) {
        val clamped = offset.coerceIn(0, text.length)
        applyValue(TextFieldValue(text, TextRange(clamped)), record = false)
        onJump?.invoke()
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

    /** Moves every caret; `false` when there is only the primary caret the field owns. */
    internal fun moveCarets(move: CaretMove, extend: Boolean): Boolean {
        if (secondaryCarets.isEmpty()) return false
        val vertical = move == CaretMove.UP || move == CaretMove.DOWN
        caretStep = if (vertical) CaretStep.VERTICAL else CaretStep.NONE
        try {
            val previous = carets
            val (moved, columns) = MultiCaret.move(text, previous, move, extend, desiredColumns)
            desiredColumns = columns
            applyCarets(CaretEdit(text, if (vertical) moved else snapMovedCarets(moved, previous)), record = false)
        } finally {
            caretStep = null
        }
        return true
    }

    /** Collapses the region starting on the caret's line, else the innermost one containing the caret. */
    private fun collapseRegion() {
        val regions = foldingRegions()
        if (regions.isEmpty()) return
        val caret = value.selection.end
        val starts = MultiCaret.lineStarts(text)
        val line = MultiCaret.lineIndexAt(starts, caret)
        val startsOnLine = regions.filter { MultiCaret.lineIndexAt(starts, it.hiddenStart) == line }
        val single = startsOnLine.singleOrNull()
        val target = if (startsOnLine.size == 1 && single != null && single !in foldedRegions) {
            single
        } else {
            regions.filter { it !in foldedRegions && it.contains(caret) }
                .minByOrNull { it.hiddenEnd - it.hiddenStart }
        } ?: return
        foldedRegions = sortedFolds(foldedRegions + target)
        moveCaretsOutOfFolds()
    }

    /** Expands the region starting on the caret's line, else the outermost collapsed one containing the caret. */
    private fun expandRegion() {
        if (foldedRegions.isEmpty()) return
        val regions = foldingRegions()
        val caret = value.selection.end
        val starts = MultiCaret.lineStarts(text)
        val line = MultiCaret.lineIndexAt(starts, caret)
        val startsOnLine = regions.filter { MultiCaret.lineIndexAt(starts, it.hiddenStart) == line }
        val single = startsOnLine.singleOrNull()
        val target = if (startsOnLine.size == 1 && single != null && single in foldedRegions) {
            single
        } else {
            foldedRegions.filter { it.contains(caret) }.maxByOrNull { it.hiddenEnd - it.hiddenStart }
        } ?: return
        foldedRegions = foldedRegions - target
    }

    private fun expandAllRegions() {
        foldedRegions = emptyList()
    }

    private fun collapseAllRegions() {
        foldedRegions = sortedFolds(foldingRegions())
        moveCaretsOutOfFolds()
    }

    /** Expands the fold whose placeholder covers [viewOffset]; `false` when there is none. */
    fun expandFoldAt(viewOffset: Int): Boolean {
        if (foldedRegions.isEmpty()) return false
        val view = FoldView.of(text, foldedRegions)
        val index = view.foldIndexAtView(viewOffset)
        if (index < 0) return false
        val fold = view.folds[index]
        foldedRegions = foldedRegions - fold
        applyValue(TextFieldValue(text, TextRange(fold.hiddenStart)), record = false)
        return true
    }

    private fun foldingCandidates(): List<FoldRegion> =
        if (file.extension?.lowercase() in BRACE_EXTENSIONS) FoldRegions.candidates(text) else emptyList()

    private fun foldingRegions(): List<FoldRegion> {
        if (foldedRegions.isEmpty()) return foldingCandidates()
        val candidates = foldingCandidates()
        if (candidates.isEmpty()) return foldedRegions
        return sortedFolds(candidates + foldedRegions)
    }

    private fun sortedFolds(regions: List<FoldRegion>): List<FoldRegion> =
        regions.distinct().sortedWith(FOLD_REGION_ORDER)

    /** Moves every caret out of a fold to that fold's start, keeping the secondary carets. */
    private fun moveCaretsOutOfFolds() {
        val folds = effectiveFolds(foldedRegions)
        if (folds.isEmpty()) return
        val moved = carets.map { caret -> TextRange(folds.snap(caret.start), folds.snap(caret.end)) }
        // Re-applying untouched carets would drop the IME composition and the caret-step state.
        if (moved == carets) return
        caretStep = CaretStep.NONE
        try {
            applyCarets(CaretEdit(text, moved), record = false)
        } finally {
            caretStep = null
        }
    }

    private fun List<FoldRegion>.snap(offset: Int): Int = foldAt(offset)?.hiddenStart ?: offset

    private fun List<FoldRegion>.snapMoved(offset: Int, old: Int): Int {
        val fold = foldAt(offset) ?: return offset
        return if (offset > old) fold.hiddenEnd else fold.hiddenStart
    }

    /** The fold whose hidden text strictly holds [offset]; the list is sorted and non-overlapping. */
    private fun List<FoldRegion>.foldAt(offset: Int): FoldRegion? {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val fold = this[mid]
            when {
                offset <= fold.hiddenStart -> high = mid - 1
                offset >= fold.hiddenEnd -> low = mid + 1
                else -> return fold
            }
        }
        return null
    }

    private fun FoldRegion.contains(offset: Int): Boolean = hiddenStart <= offset && offset <= hiddenEnd

    /**
     * Keeps the folds consistent with a text change and with the carets of the new state: a fold
     * that the change touches, or that hides a caret, opens.
     */
    private fun reconcileFolds(change: TextChange?, carets: List<TextRange>) {
        if (foldedRegions.isEmpty()) return
        var regions = foldedRegions
        if (change != null) {
            val delta = change.newText.length - change.oldLength
            regions = regions.mapNotNull { region ->
                when {
                    change.offset + change.oldLength <= region.hiddenStart ->
                        FoldRegion(region.hiddenStart + delta, region.hiddenEnd + delta)
                    change.offset >= region.hiddenEnd -> region
                    else -> null
                }
            }
        }
        if (regions.isNotEmpty() && carets.isNotEmpty()) {
            val ends = IntArray(carets.size * 2)
            for ((index, caret) in carets.withIndex()) {
                ends[index * 2] = caret.start
                ends[index * 2 + 1] = caret.end
            }
            ends.sort()
            regions = regions.filterNot { region ->
                val at = firstAfter(ends, region.hiddenStart)
                at < ends.size && ends[at] < region.hiddenEnd
            }
        }
        if (regions != foldedRegions) foldedRegions = regions
    }

    /** The first index of the sorted [ends] whose value is greater than [value], or its size. */
    private fun firstAfter(ends: IntArray, value: Int): Int {
        var low = 0
        var high = ends.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }

    /** Snaps a horizontal field movement out of a fold instead of letting the fold open. */
    private fun snappedSelection(newValue: TextFieldValue): TextFieldValue {
        if (foldedRegions.isEmpty()) return newValue
        val folds = effectiveFolds(foldedRegions)
        if (folds.isEmpty()) return newValue
        val old = value.selection
        val selection = newValue.selection
        val start = folds.snapMoved(selection.start, old.start)
        val end = folds.snapMoved(selection.end, old.end)
        return if (start == selection.start && end == selection.end) {
            newValue
        } else {
            newValue.copy(selection = TextRange(start, end))
        }
    }

    private fun snapMovedCarets(moved: List<TextRange>, previous: List<TextRange>): List<TextRange> {
        if (foldedRegions.isEmpty()) return moved
        val folds = effectiveFolds(foldedRegions)
        if (folds.isEmpty()) return moved
        return moved.mapIndexed { index, caret ->
            val before = previous.getOrNull(index)
            if (before == null) {
                caret
            } else {
                val start = folds.snapMoved(caret.start, before.start)
                val end = folds.snapMoved(caret.end, before.end)
                if (start == caret.start && end == caret.end) caret else TextRange(start, end)
            }
        }
    }

    /** Deletes at every caret; `false` when there is only the primary caret. */
    fun deleteAtCarets(forward: Boolean, word: Boolean): Boolean {
        if (secondaryCarets.isEmpty()) return false
        if (isReadOnly) return true
        undo.breakMerge()
        applyCarets(MultiCaret.delete(text, carets, forward, word), record = true)
        undo.breakMerge()
        return true
    }

    /** Pastes into every caret; `false` when there is only the primary caret. */
    fun pasteAtCarets(clip: String): Boolean {
        if (secondaryCarets.isEmpty()) return false
        if (isReadOnly) return true
        undo.breakMerge()
        applyCarets(MultiCaret.paste(text, carets, clip), record = true)
        undo.breakMerge()
        return true
    }

    /** Grows or shrinks the rectangular selection by [dLine] lines and [dCol] columns. */
    fun extendBlock(dLine: Int, dCol: Int): Boolean {
        if (!columnMode) return false
        caretStep = CaretStep.BLOCK
        try {
            if (!blockActive) {
                val position = value.selection.end
                blockAnchorLine = MultiCaret.lineIndexOf(text, position)
                blockAnchorCol = MultiCaret.columnAt(text, position)
                blockLeadLine = blockAnchorLine
                blockLeadCol = blockAnchorCol
                blockActive = true
            }
            blockLeadLine = (blockLeadLine + dLine).coerceIn(0, MultiCaret.lineCountOf(text) - 1)
            blockLeadCol = (blockLeadCol + dCol).coerceAtLeast(0)
            val carets = MultiCaret.block(text, blockAnchorLine, blockAnchorCol, blockLeadLine, blockLeadCol)
            applyCarets(CaretEdit(text, carets), record = false)
        } finally {
            caretStep = null
        }
        return true
    }

    /** Starts or replaces the rectangular selection between the anchor and lead corners. */
    fun selectBlock(anchorLine: Int, anchorCol: Int, leadLine: Int, leadCol: Int) {
        blockActive = true
        blockAnchorLine = anchorLine
        blockAnchorCol = anchorCol
        blockLeadLine = leadLine
        blockLeadCol = leadCol
        caretStep = CaretStep.BLOCK
        try {
            applyCarets(CaretEdit(text, MultiCaret.block(text, anchorLine, anchorCol, leadLine, leadCol)), record = false)
        } finally {
            caretStep = null
        }
    }

    fun toggleCaretAt(offset: Int) {
        applyCarets(CaretEdit(text, MultiCaret.toggleCaret(text, carets, offset)), record = false)
    }

    /** Drops the secondary carets and keeps the primary one's selection. */
    fun removeSecondaryCarets(): Boolean {
        if (secondaryCarets.isEmpty()) return false
        applyCarets(CaretEdit(text, listOf(value.selection)), record = false)
        return true
    }

    /** Selects the caret's word, else adds the next occurrence; `false` when there is none. */
    fun selectNextOccurrence(): Boolean {
        val result = MultiCaret.selectNextOccurrence(text, carets, wholeWords)
        if (!result.found) return false
        wholeWords = result.wholeWords
        caretStep = CaretStep.OCCURRENCE
        try {
            applyCarets(CaretEdit(text, result.carets), record = false)
        } finally {
            caretStep = null
        }
        return true
    }

    /** Selects every occurrence of the primary caret's word or selection; `false` when there is none. */
    fun selectAllOccurrences(): Boolean {
        val result = MultiCaret.selectAllOccurrences(text, carets, wholeWords)
        if (!result.found) return false
        wholeWords = result.wholeWords
        caretStep = CaretStep.OCCURRENCE
        try {
            applyCarets(CaretEdit(text, result.carets), record = false)
        } finally {
            caretStep = null
        }
        return true
    }

    /** Drops the primary caret, promoting the previous one; collapses a lone selection. */
    fun unselectPreviousOccurrence() {
        caretStep = CaretStep.OCCURRENCE
        try {
            applyCarets(CaretEdit(text, MultiCaret.unselectOccurrence(carets)), record = false)
        } finally {
            caretStep = null
        }
    }

    /** Adds an empty caret on the line above or below; `false` when nothing was added. */
    fun cloneCaret(above: Boolean): Boolean {
        if (secondaryCarets.isNotEmpty() && cloneDirection != null && cloneDirection != above) {
            applyCarets(CaretEdit(text, carets.dropLast(1)), record = false)
            cloneColumn = null
            cloneDirection = null
            return true
        }
        val column = cloneColumn ?: run {
            val extreme = if (above) carets.minByOrNull { it.end } else carets.maxByOrNull { it.end }
            extreme?.let { MultiCaret.columnAt(text, it.end) }
        } ?: return false
        val added = MultiCaret.cloneCaret(text, carets, above, column)
        if (added.size == carets.size) return false
        caretStep = CaretStep.CLONE
        try {
            cloneColumn = column
            cloneDirection = above
            applyCarets(CaretEdit(text, added), record = false)
        } finally {
            caretStep = null
        }
        return true
    }

    /** Turns column selection mode on or off; returns the new state. */
    fun toggleColumnMode(): Boolean {
        columnMode = !columnMode
        return columnMode
    }

    /** Applies [action] at every caret; the line-based ones run once per group of overlapping lines. */
    private fun applyAtEveryCaret(action: ActionId): Boolean {
        val prefix = commenter?.lineCommentPrefix
        if (action == ActionId.COMMENT_LINE && prefix == null) return true
        if (isReadOnly) return true
        val lineBased = when (action) {
            ActionId.EDITOR_TOGGLE_CASE -> false
            ActionId.INDENT -> carets.any { !it.collapsed }
            else -> true
        }
        val edit = MultiCaret.perCaret(text, carets, lineBased) { current, range ->
            when (action) {
                ActionId.COMMENT_LINE -> EditActions.toggleLineComment(current, range, requireNotNull(prefix))
                ActionId.EDITOR_DUPLICATE -> EditActions.duplicateLineOrSelection(current, range)
                ActionId.EDITOR_DELETE_LINE -> EditActions.deleteLines(current, range)
                ActionId.INDENT -> EditActions.indent(current, range)
                ActionId.UNINDENT -> EditActions.unindent(current, range)
                else -> EditActions.toggleCase(current, range)
            }
        }
        undo.breakMerge()
        applyCarets(edit, record = true)
        undo.breakMerge()
        return true
    }

    fun applyAction(action: ActionId): Boolean {
        if (secondaryCarets.isNotEmpty() && action in EVERY_CARET_ACTIONS) return applyAtEveryCaret(action)
        return when (action) {
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
        ActionId.EDITOR_ESCAPE -> {
            val hadHighlights = usageHighlights.isNotEmpty()
            usageHighlights = emptyList()
            when {
                gotoLineVisible -> {
                    gotoLineVisible = false
                    runCatching { focusRequester.requestFocus() }
                    true
                }
                secondaryCarets.isNotEmpty() -> {
                    removeSecondaryCarets()
                    true
                }
                !value.selection.collapsed -> {
                    applyValue(TextFieldValue(text, TextRange(value.selection.end)), record = false)
                    true
                }
                hadHighlights -> true
                else -> false
            }
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
        ActionId.MOVE_STATEMENT_UP, ActionId.MOVE_STATEMENT_DOWN -> {
            if (!isReadOnly) {
                applyEdit(
                    EditActions.moveStatement(text, value.selection, up = action == ActionId.MOVE_STATEMENT_UP),
                )
            }
            true
        }
        ActionId.EDITOR_COMPLETE_STATEMENT -> {
            if (!isReadOnly) {
                applyEdit(
                    EditActions.completeStatement(
                        text,
                        value.selection,
                        semicolons = file.extension?.lowercase() == "java",
                    ),
                )
            }
            true
        }
        ActionId.UNWRAP -> {
            if (!isReadOnly) unwrap()
            true
        }
        ActionId.REFORMAT_CODE -> {
            if (!isReadOnly) reformat()
            true
        }
        ActionId.EDITOR_SCROLL_TO_CENTER -> {
            centerRequests++
            true
        }
        ActionId.HIPPIE_COMPLETION -> hippieComplete()
        ActionId.SELECT_NEXT_OCCURRENCE -> selectNextOccurrence()
        ActionId.UNSELECT_PREVIOUS_OCCURRENCE -> {
            unselectPreviousOccurrence()
            true
        }
        ActionId.SELECT_ALL_OCCURRENCES -> selectAllOccurrences()
        ActionId.EDITOR_TOGGLE_COLUMN_MODE -> {
            toggleColumnMode()
            true
        }
        ActionId.EDITOR_CLONE_CARET_ABOVE -> {
            cloneCaret(above = true)
            true
        }
        ActionId.EDITOR_CLONE_CARET_BELOW -> {
            cloneCaret(above = false)
            true
        }
        ActionId.EXPAND_REGION -> {
            expandRegion()
            true
        }
        ActionId.COLLAPSE_REGION -> {
            collapseRegion()
            true
        }
        ActionId.EXPAND_ALL_REGIONS -> {
            expandAllRegions()
            true
        }
        ActionId.COLLAPSE_ALL_REGIONS -> {
            collapseAllRegions()
            true
        }
        else -> false
        }
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
        if (secondaryCarets.isNotEmpty()) return copyLinesAtCarets(cut)
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

    /** Copies the selections, or the whole lines, of every caret; a cut removes those ranges. */
    private fun copyLinesAtCarets(cut: Boolean): Boolean {
        val (copied, ranges) = MultiCaret.copyText(text, carets)
        if (!clipboard.writeText(copied)) return false
        if (cut && !isReadOnly) {
            undo.breakMerge()
            applyCarets(MultiCaret.deleteRanges(text, carets, ranges), record = true)
            undo.breakMerge()
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
        val changes = ArrayList<TextChange>(ranges.size)
        var delta = 0
        for (range in ranges) {
            val length = range.last + 1 - range.first
            changes.add(TextChange(range.first + delta, length, replacement))
            delta += replacement.length - length
        }
        val caret = ranges.first().first + replacement.length
        applyEdit(EditResult(newText, TextRange(caret)), changes)
        return ranges.size
    }

    /** Removes the innermost control block around the caret; `false` when there is none. */
    fun unwrap(): Boolean {
        if (isReadOnly) return false
        val result = EditActions.unwrap(text, value.selection) ?: return false
        applyEdit(result)
        return true
    }

    /** Re-indents the selection or the whole file; returns the status line to show. */
    fun reformat(): String {
        if (isReadOnly) return "File is read-only"
        val edits = EditActions.reformat(
            text,
            value.selection,
            braceLanguage = file.extension?.lowercase() in BRACE_EXTENSIONS,
        )
        if (edits.isEmpty()) return "Nothing to reformat"
        if (!applyEdits(edits)) return "Nothing to reformat"
        return "Reformatted ${edits.size} lines"
    }

    /** Replaces `[start, end)` with [replacement] as one undo step; used by the popups. */
    fun replaceRange(start: Int, end: Int, replacement: String) {
        if (isReadOnly) return
        val rangeStart = start.coerceIn(0, text.length)
        val rangeEnd = end.coerceIn(rangeStart, text.length)
        val newText = text.substring(0, rangeStart) + replacement + text.substring(rangeEnd)
        applyEdit(EditResult(newText, TextRange(rangeStart + replacement.length)))
    }

    /**
     * Applies [edits] as one undo step; `false` when the editor is read-only or an edit is out of
     * range or overlaps another. [caret], an offset in the text after the edits, replaces the
     * mapped caret when it is given.
     */
    fun applyEdits(edits: List<TextEdit>, caret: Int? = null): Boolean {
        if (isReadOnly || edits.isEmpty()) return false
        val sorted = edits.sortedBy { it.start }
        var previousEnd = -1
        for (edit in sorted) {
            if (edit.start < 0 || edit.end > text.length || edit.start > edit.end) return false
            if (edit.start < previousEnd) return false
            previousEnd = edit.end
        }
        var newText = text
        for (edit in sorted.asReversed()) {
            newText = newText.substring(0, edit.start) + edit.replacement + newText.substring(edit.end)
        }
        val changes = ArrayList<TextChange>(sorted.size)
        var delta = 0
        for (edit in sorted) {
            val length = edit.end - edit.start
            changes.add(TextChange(edit.start + delta, length, edit.replacement))
            delta += edit.replacement.length - length
        }
        applyEdit(EditResult(newText, TextRange(caret ?: mappedCaret(sorted))), changes)
        return true
    }

    private fun mappedCaret(edits: List<TextEdit>): Int {
        val caret = value.selection.start
        var mapped = caret
        var delta = 0
        for (edit in edits) {
            when {
                caret >= edit.end -> {
                    delta += edit.replacement.length - (edit.end - edit.start)
                    mapped = caret + delta
                }
                caret > edit.start -> return edit.start + delta + edit.replacement.length
                else -> break
            }
        }
        return mapped
    }

    /** Applies [result] as one undo step and places its selection; `false` when read-only. */
    fun applyResult(result: EditResult): Boolean {
        if (isReadOnly) return false
        applyEdit(result)
        return true
    }

    private fun applyEdit(result: EditResult, changes: List<TextChange>? = null) {
        undo.breakMerge()
        applyValue(TextFieldValue(result.text, result.selection), record = true, reported = changes)
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
        val currentUsages = usageHighlights
        val currentValue = value
        val foldView = if (foldedRegions.isEmpty()) {
            FoldView.NONE
        } else {
            remember(currentValue.text, foldedRegions) { FoldView.of(currentValue.text, foldedRegions) }
        }
        val nativeGlass = LocalNativeGlass.current
        val appearance = AppearanceState.appearance
        val schemeAndGutter = remember(nativeGlass, appearance) {
            if (nativeGlass) glassColorScheme(appearance) to glassGutterColor(appearance)
            else activeColorScheme() to DesignTokens.muted
        }
        val scheme = schemeAndGutter.first
        val gutterColor = schemeAndGutter.second
        // Without usage highlights the transformation gets the find bar's own list, so a clean
        // editor allocates nothing and keeps the frame path it had.
        val usageSpans = if (currentUsages.isEmpty()) {
            emptyList()
        } else {
            remember(currentUsages, nativeGlass, appearance) {
                val alpha = if (nativeGlass) GLASS_FIND_MATCH_ALPHA else 0.55f
                currentUsages.map { range ->
                    BackgroundSpan(range.first, range.last + 1, DesignTokens.accent.copy(alpha = alpha))
                }
            }
        }
        val currentBackgroundSpans = if (usageSpans.isEmpty()) {
            currentSearchSpans
        } else {
            remember(currentSearchSpans, usageSpans) { currentSearchSpans + usageSpans }
        }
        val transformation = remember(currentSpans, currentBackgroundSpans, scheme, gutterColor, foldView) {
            HighlightingVisualTransformation(
                currentSpans,
                currentBackgroundSpans,
                scheme,
                foldView,
                SpanStyle(color = gutterColor),
            )
        }
        val vertical = verticalScroll
        val horizontal = horizontalScroll
        val fieldScroll = rememberTextFieldVerticalScrollState()
        // Keyed on the editor: switching tabs reuses this composition slot with a new document.
        var layout by remember(this) { mutableStateOf<TextLayoutResult?>(null) }
        var gutterLayout by remember(this) { mutableStateOf<TextLayoutResult?>(null) }
        val mirrorSlices = remember(this) { LineSliceCache() }
        val bandSlices = remember(this) { LineSliceCache() }
        val gutterSlices = remember(this) { LineSliceCache() }
        val latestLayout = rememberUpdatedState(layout)
        val latestFolds = rememberUpdatedState(foldView)
        val currentProblems = problems
        val latestProblems = rememberUpdatedState(problems)
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
            color = defaultForeground(scheme),
        )
        val caretLine = lineIndexAt(currentValue.text, currentValue.selection.start)
        val caretRow = if (foldView.isEmpty) caretLine else foldView.rowOf(caretLine)
        val lineHeight = with(LocalDensity.current) { DesignTokens.codeLineHeight.toDp() }
        val gutterText = remember(lineCount, caretLine, caretRow, foldView) {
            if (foldView.isEmpty) buildGutterText(lineCount, caretLine) else buildGutterText(foldView.visibleLines, caretRow)
        }
        val secondaryLines = remember(currentValue.text, secondaryCarets, foldView) {
            val lines = secondaryCaretLines(currentValue.text, secondaryCarets)
            if (foldView.isEmpty) lines else lines.map { foldView.rowOf(it) }.distinct().sorted().toIntArray()
        }

        BoxWithConstraints(modifier.contentTint()) {
            val density = LocalDensity.current
            val chromeInsets = LocalEditorInsets.current
            val leftPad = chromeInsets.left
            val topPad = 8.dp + chromeInsets.top
            val bottomPad = 8.dp + chromeInsets.bottom
            val topPadPx = with(density) { topPad.toPx() }
            val viewportHeightPx = with(density) { (maxHeight - topPad - bottomPad).coerceAtLeast(0.dp).toPx() }
            val windowHeightPx = with(density) { maxHeight.toPx() }
            LaunchedEffect(currentValue.selection.end, layout, maxWidth, maxHeight, leftPad, foldView) {
                val textLayout = layout ?: return@LaunchedEffect
                // The layout can still be the previous document's for one frame after a tab switch.
                val limit = textLayout.layoutInput.text.length
                val caretOffset = currentValue.selection.end.coerceIn(0, limit)
                val viewOffset = if (foldView.isEmpty) caretOffset else foldView.toView(caretOffset).coerceIn(0, limit)
                val rect = textLayout.getCursorRect(viewOffset)
                // The text field spans the window; the glyph column starts past the gutter and the
                // visible region starts at the gutter's right edge.
                val contentLeft = with(density) { (leftPad + gutterWidth + DesignTokens.space16).toPx() }
                val visibleLeft = with(density) { (leftPad + gutterWidth).toPx() }
                val visibleRight = with(density) { maxWidth.toPx() }
                // The viewport reaches the window edges; the insets bound the region scrolling
                // keeps the caret inside, and the content keeps a padding at rest.
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
            LaunchedEffect(centerRequests) {
                if (centerRequests == 0) return@LaunchedEffect
                val textLayout = layout ?: return@LaunchedEffect
                val folds = latestFolds.value
                val limit = textLayout.layoutInput.text.length
                val caretOffset = currentValue.selection.end.coerceIn(0, limit)
                // A mapping built for another document would make the layout offset invalid.
                val viewOffset = if (folds.isEmpty || folds.docLength != text.length) {
                    caretOffset
                } else {
                    folds.toView(caretOffset).coerceIn(0, limit)
                }
                val line = textLayout.getLineForOffset(viewOffset)
                val lineCenter = topPadPx + (textLayout.getLineTop(line) + textLayout.getLineBottom(line)) / 2f
                // The chrome insets bound the visible viewport, so its middle sits below topPad.
                val target = lineCenter - topPadPx - viewportHeightPx / 2f
                vertical.scrollTo(target.roundToInt().coerceIn(0, vertical.maxValue))
            }
            val contentLines = if (foldView.isEmpty) lineCount else foldView.rowCount
            val gutterHeight = maxOf((maxHeight - topPad - bottomPad).coerceAtLeast(0.dp), lineHeight * contentLines)
            val textMinWidth = (maxWidth - leftPad - gutterWidth - 32.dp).coerceAtLeast(0.dp)
            val sidebar = LocalSidebarChrome.current
            Canvas(Modifier.matchParentSize()) {
                val linePx = DesignTokens.codeLineHeight.toPx()
                val first = ((vertical.value - topPadPx) / linePx).toInt() - 1
                val last = ((vertical.value - topPadPx + windowHeightPx) / linePx).toInt() + 1
                // Nothing of a current line paints left of the floating sidebar; that strip
                // keeps the editor's own field colour.
                val clipLeft = sidebar?.left?.toPx() ?: 0f
                fun paintLine(line: Int) {
                    val top = topPadPx + line * linePx - vertical.value
                    if (top + linePx <= 0f || top >= size.height) return
                    clipRect(left = clipLeft) {
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
                for (index in firstLineAtOrAfter(secondaryLines, first) until secondaryLines.size) {
                    val line = secondaryLines[index]
                    if (line > last) break
                    if (line != caretRow) paintLine(line)
                }
                paintLine(caretRow)
            }
            VerticalScrollViewport(vertical, Modifier.fillMaxSize(), layered = layered) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topPad, bottom = bottomPad),
            ) {
                HorizontalScrollViewport(
                    horizontal,
                    Modifier.fillMaxWidth(),
                    layered = layered,
                    clipStart = leftPad + gutterWidth,
                ) {
                    CompositionLocalProvider(
                        LocalTextSelectionColors provides TextSelectionColors(
                            handleColor = DesignTokens.accent,
                            backgroundColor = DesignTokens.selection,
                        ),
                    ) {
                        BasicTextField(
                            value = currentValue,
                            onValueChange = ::onFieldValueChange,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .widthIn(min = textMinWidth)
                                .padding(
                                    start = leftPad + gutterWidth + DesignTokens.space16,
                                    end = DesignTokens.space16,
                                )
                                // Only when there are secondary carets does the chain grow, so
                                // the single-caret path keeps its modifiers and frame cost.
                                .then(
                                    if (secondaryCarets.isEmpty()) {
                                        Modifier
                                    } else {
                                        Modifier.drawWithContent {
                                            val textLayout = latestLayout.value
                                            if (textLayout == null) {
                                                drawContent()
                                                return@drawWithContent
                                            }
                                            // The layout can still be the previous document's
                                            // for one frame after a tab switch.
                                            val limit = textLayout.layoutInput.text.length
                                            // Same window as the background canvas, in layout rows.
                                            val linePx = DesignTokens.codeLineHeight.toPx()
                                            val first = ((vertical.value - topPadPx) / linePx).toInt() - 1
                                            val last =
                                                ((vertical.value - topPadPx + windowHeightPx) / linePx).toInt() + 1
                                            val lastLine = textLayout.lineCount - 1
                                            // The layout is the folded text; only when the view
                                            // holds folds do the carets need moving into it.
                                            val folds = latestFolds.value
                                            val carets = if (folds.isEmpty || folds.docLength != text.length) {
                                                secondaryCarets
                                            } else {
                                                secondaryCarets.map {
                                                    TextRange(
                                                        folds.toView(it.start).coerceIn(0, limit),
                                                        folds.toView(it.end).coerceIn(0, limit),
                                                    )
                                                }
                                            }
                                            val visible = visibleCaretRanges(
                                                carets = carets,
                                                lineOf = { offset ->
                                                    textLayout.getLineForOffset(offset.coerceIn(0, limit))
                                                },
                                                lineStart = textLayout::getLineStart,
                                                lineEnd = textLayout::getLineEnd,
                                                first = first.coerceIn(0, lastLine),
                                                last = last.coerceIn(0, lastLine),
                                            )
                                            for (caret in visible) {
                                                val selection = caret.selection ?: continue
                                                drawPath(
                                                    textLayout.getPathForRange(selection.min, selection.max),
                                                    DesignTokens.selection,
                                                )
                                            }
                                            drawContent()
                                            val caretWidth = 2.dp.toPx()
                                            for (caret in visible) {
                                                val bar = caret.bar ?: continue
                                                val rect = textLayout.getCursorRect(bar.coerceIn(0, limit))
                                                drawRect(
                                                    DesignTokens.accent2,
                                                    topLeft = Offset(rect.left, rect.top),
                                                    size = Size(caretWidth, rect.height),
                                                )
                                            }
                                        }
                                    },
                                )
                                // Only when something is folded does the chain grow, so the
                                // unfold path keeps its modifiers and frame cost.
                                .then(
                                    if (foldView.isEmpty) {
                                        Modifier
                                    } else {
                                        Modifier.drawBehind {
                                            val textLayout = latestLayout.value ?: return@drawBehind
                                            if (textLayout.layoutInput.text.length != foldView.viewLength) return@drawBehind
                                            // Same window as the background canvas, in layout rows.
                                            val linePx = DesignTokens.codeLineHeight.toPx()
                                            val lastLine = (textLayout.lineCount - 1).coerceAtLeast(0)
                                            val first = (((vertical.value - topPadPx) / linePx).toInt() - 1)
                                                .coerceIn(0, lastLine)
                                            val last = (((vertical.value - topPadPx + windowHeightPx) / linePx).toInt() + 1)
                                                .coerceIn(0, lastLine)
                                            val inflate = 1.dp.toPx()
                                            val folded = foldView.foldIndicesInView(
                                                textLayout.getLineStart(first),
                                                textLayout.getLineEnd(last),
                                            )
                                            for (index in folded) {
                                                val start = foldView.placeholderStart(index)
                                                val head = textLayout.getBoundingBox(start)
                                                val tail = textLayout.getBoundingBox(start + FOLD_PLACEHOLDER.length - 1)
                                                val left = minOf(head.left, tail.left) - inflate
                                                val right = maxOf(head.right, tail.right) + inflate
                                                val top = minOf(head.top, tail.top)
                                                val bottom = maxOf(head.bottom, tail.bottom)
                                                drawRoundRect(
                                                    color = DesignTokens.accent2.copy(alpha = 0.55f),
                                                    topLeft = Offset(left, top),
                                                    size = Size(right - left, bottom - top),
                                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                                    style = Stroke(width = 1.dp.toPx()),
                                                )
                                            }
                                        }
                                    },
                                )
                                // Only when there are problems does the chain grow, so the
                                // clean path keeps its modifiers and frame cost.
                                .then(
                                    if (currentProblems.isEmpty()) {
                                        Modifier
                                    } else {
                                        Modifier.drawWithContent {
                                            drawContent()
                                            val textLayout = latestLayout.value ?: return@drawWithContent
                                            val folds = latestFolds.value
                                            val length = text.length
                                            val expected = if (folds.isEmpty) length else folds.viewLength
                                            if (textLayout.layoutInput.text.length != expected) {
                                                return@drawWithContent
                                            }
                                            // Same window as the background canvas, in layout rows.
                                            val linePx = DesignTokens.codeLineHeight.toPx()
                                            val lastLine = (textLayout.lineCount - 1).coerceAtLeast(0)
                                            val first = (((vertical.value - topPadPx) / linePx).toInt() - 1)
                                                .coerceIn(0, lastLine)
                                            val last = (((vertical.value - topPadPx + windowHeightPx) / linePx).toInt() + 1)
                                                .coerceIn(0, lastLine)
                                            drawProblemUnderlines(
                                                textLayout = textLayout,
                                                problems = latestProblems.value,
                                                folds = folds,
                                                textLength = length,
                                                first = first,
                                                last = last,
                                            )
                                        }
                                    },
                                )
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val command = CaretKeys.commandFor(
                                        key = event.key,
                                        meta = event.isMetaPressed,
                                        ctrl = event.isCtrlPressed,
                                        alt = event.isAltPressed,
                                        shift = event.isShiftPressed,
                                        hasSecondary = secondaryCarets.isNotEmpty(),
                                        columnMode = columnMode,
                                    ) ?: return@onPreviewKeyEvent false
                                    when (command) {
                                        is CaretKeyCommand.Move -> moveCarets(command.move, command.extend)
                                        is CaretKeyCommand.ExtendBlock ->
                                            extendBlock(command.dLine, command.dCol)
                                        is CaretKeyCommand.Delete ->
                                            deleteAtCarets(command.forward, command.word)
                                        CaretKeyCommand.Paste -> clipboard.readText()?.let(::pasteAtCarets)
                                    }
                                    true
                                }
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (event.type != PointerEventType.Press) continue
                                            val change = event.changes.firstOrNull { it.changedToDown() } ?: continue
                                            val textLayout = latestLayout.value ?: continue
                                            val modifiers = event.keyboardModifiers
                                            if (modifiers.isMetaPressed) {
                                                val line = textLayout.getLineForVerticalPosition(change.position.y)
                                                val inside = insideLine(
                                                    change.position,
                                                    textLayout.getLineTop(line),
                                                    textLayout.getLineBottom(line),
                                                    textLayout.getLineRight(line),
                                                )
                                                if (!inside) continue
                                                val raw = textLayout.getOffsetForPosition(change.position)
                                                    .coerceIn(0, textLayout.layoutInput.text.length)
                                                val folds = latestFolds.value
                                                val offset = if (folds.isEmpty) raw else folds.toDoc(raw)
                                                change.consume()
                                                onGoToDeclarationAt?.invoke(offset.coerceIn(0, text.length))
                                                continue
                                            }
                                            val gesture = caretPointerGesture(
                                                meta = false,
                                                ctrl = modifiers.isCtrlPressed,
                                                alt = modifiers.isAltPressed,
                                                shift = modifiers.isShiftPressed,
                                                columnMode = columnMode,
                                            )
                                            if (gesture == null) {
                                                val unmodified = event.buttons.isPrimaryPressed &&
                                                    !modifiers.isAltPressed &&
                                                    !modifiers.isCtrlPressed &&
                                                    !modifiers.isMetaPressed
                                                if (unmodified && !modifiers.isShiftPressed) {
                                                    val folds = latestFolds.value
                                                    val index = foldPlaceholderAt(
                                                        folds,
                                                        textLayout,
                                                        change.position,
                                                        1.dp.toPx(),
                                                    )
                                                    if (index >= 0) {
                                                        change.consume()
                                                        expandFoldAt(folds.placeholderStart(index))
                                                        runCatching { focusRequester.requestFocus() }
                                                        continue
                                                    }
                                                }
                                                // A plain press (shift allowed) moves the single
                                                // caret where it lands, so the extra ones go.
                                                if (unmodified && secondaryCarets.isNotEmpty()) {
                                                    removeSecondaryCarets()
                                                }
                                                continue
                                            }
                                            change.consume()
                                            when (gesture) {
                                                CaretPointerGesture.TOGGLE -> {
                                                    val raw = textLayout.getOffsetForPosition(change.position)
                                                        .coerceIn(0, textLayout.layoutInput.text.length)
                                                    val folds = latestFolds.value
                                                    val offset = if (folds.isEmpty) raw else folds.toDoc(raw)
                                                    toggleCaretAt(offset.coerceIn(0, text.length))
                                                    runCatching { focusRequester.requestFocus() }
                                                }
                                                CaretPointerGesture.BLOCK -> {
                                                    val anchor = blockCorner(textLayout, change.position, latestFolds.value)
                                                    selectBlock(
                                                        anchor.line,
                                                        anchor.column,
                                                        anchor.line,
                                                        anchor.column,
                                                    )
                                                    runCatching { focusRequester.requestFocus() }
                                                    while (true) {
                                                        val next = awaitPointerEvent(PointerEventPass.Initial)
                                                        val moved = next.changes
                                                            .firstOrNull { it.id == change.id } ?: break
                                                        val current = latestLayout.value ?: textLayout
                                                        val lead = blockCorner(current, moved.position, latestFolds.value)
                                                        selectBlock(anchor.line, anchor.column, lead.line, lead.column)
                                                        moved.consume()
                                                        if (!moved.pressed) break
                                                    }
                                                }
                                            }
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
                            // The gutter scrolls with the content, so the visible rows are the
                            // viewport translated back by the scroll offset.
                            val first = ((vertical.value - topPadPx) / linePx).toInt() - 1
                            val last = ((vertical.value - topPadPx + windowHeightPx) / linePx).toInt() + 1
                            fun paintLine(line: Int) {
                                val glowTop = line * linePx
                                if (glowTop + linePx <= 0f || glowTop >= size.height) return
                                drawRect(
                                    DesignTokens.currentLineGlow,
                                    topLeft = Offset(0f, glowTop),
                                    size = Size(size.width, linePx),
                                )
                            }
                            for (index in firstLineAtOrAfter(secondaryLines, first) until secondaryLines.size) {
                                val line = secondaryLines[index]
                                if (line > last) break
                                if (line != caretRow) paintLine(line)
                            }
                            paintLine(caretRow)
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
                        style = codeStyle.copy(color = gutterColor, textAlign = TextAlign.End),
                        // The edge bands paint the numbers into their blurred copy.
                        onTextLayout = { gutterLayout = it },
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
                                val row = if (foldView.isEmpty) line else foldView.visibleRowOf(line) ?: continue
                                val top = row * linePx + (linePx - glyphHeight) / 2
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
                        val row = if (foldView.isEmpty) marker.line else foldView.visibleRowOf(marker.line) ?: continue
                        Canvas(
                            Modifier
                                .offset(
                                    x = 2.dp,
                                    y = (lineHeight * row).coerceAtLeast(0.dp) +
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
                    val changes = vcsHunks
                    if (changes.isNotEmpty()) {
                        val marks = remember(changes, foldView, lineCount) {
                            changeMarkRows(changes, foldView, lineCount)
                        }
                        val onClick = onChangeMarkClick
                        val rowHeightPx = with(density) { lineHeight.toPx() }
                        Canvas(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp)
                                .width(8.dp)
                                .height(gutterHeight)
                                .testTag(CHANGE_MARKS_TAG)
                                .pointerInput(marks, onClick) {
                                    detectTapGestures { position ->
                                        val row = (position.y / rowHeightPx).toInt()
                                        val mark = marks.firstOrNull { row in it.firstRow..it.lastRow }
                                            ?: return@detectTapGestures
                                        onClick?.invoke(mark.hunk)
                                    }
                                },
                        ) {
                            val linePx = lineHeight.toPx()
                            val barWidth = 3.dp.toPx()
                            for (mark in marks) {
                                val top = mark.firstRow * linePx
                                when (mark.kind) {
                                    HunkKind.DELETED -> {
                                        val middle = top + linePx / 2
                                        val half = 2.dp.toPx()
                                        val path = Path().apply {
                                            moveTo(size.width - half * 2, middle - half)
                                            lineTo(size.width, middle)
                                            lineTo(size.width - half * 2, middle + half)
                                            close()
                                        }
                                        drawPath(path, DesignTokens.vcsDeleted)
                                    }
                                    else -> drawRect(
                                        if (mark.kind == HunkKind.ADDED) DesignTokens.vcsAdded else DesignTokens.vcsModified,
                                        topLeft = Offset(size.width - barWidth, top),
                                        size = Size(barWidth, (mark.lastRow - mark.firstRow + 1) * linePx),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            val textLayout = layout
            if (sidebar != null && textLayout != null && !layered) {
                val extensionWidth = (maxHeight - sidebar.top - sidebar.bottom).coerceAtLeast(0.dp)
                // Read at draw time: recomposing the editor for every scroll offset would cost
                // more than the cached rebuild the key decides.
                val extensionKey = {
                    ExtensionKey(
                        layout = textLayout,
                        spans = currentSpans,
                        searchSpans = currentBackgroundSpans,
                        horizontal = horizontal.value,
                        vertical = vertical.value,
                        chrome = sidebar,
                        width = maxWidth,
                        height = maxHeight,
                    )
                }
                val stripCache = remember { MirroredStripCache() }
                DisposableEffect(stripCache) {
                    onDispose { stripCache.dispose() }
                }
                val textStartPx = with(density) {
                    (leftPad + gutterWidth + DesignTokens.space16).toPx()
                }
                val extensionLeft = sidebar.left + sidebar.width
                val extensionTopPx = with(density) { sidebar.top.toPx() }
                val extensionBottomPx = with(density) { (sidebar.top + extensionWidth).toPx() }
                Box(
                    Modifier
                        .offset(x = 0.dp, y = sidebar.top)
                        .width(leftPad)
                        .height(extensionWidth)
                        // The document right of the sidebar's edge, mirrored into its rounded
                        // shape only; the field around it stays the editor's own.
                        .backgroundExtension(
                            key = extensionKey,
                            cache = stripCache,
                            sourceLeft = extensionLeft,
                            sourceTop = sidebar.top,
                            sourceWidth = leftPad,
                            shapeLeft = sidebar.left,
                            shapeWidth = sidebar.width,
                            shapeRadius = DesignTokens.sidebarRadius,
                            luminanceCap = editorFieldLuminance,
                        ) { canvas ->
                            canvas.save()
                            canvas.translate(textStartPx - horizontal.value, topPadPx - vertical.value)
                            paintVisibleLines(
                                canvas,
                                textLayout,
                                top = extensionTopPx - topPadPx + vertical.value,
                                bottom = extensionBottomPx - topPadPx + vertical.value,
                                cache = mirrorSlices,
                                onPainted = { EditorBandPaintStats.mirrorLines = it },
                            )
                            canvas.restore()
                        },
                )
            }
            // The scroll edge effect: the editor paints its own blurred copy, because a backdrop
            // filter cannot sample the text field.
            val bandLayout = layout
            if (bandLayout != null && (chromeInsets.top > 0.dp || chromeInsets.bottom > 0.dp)) {
                val bandCache = remember { EdgeBandCache() }
                DisposableEffect(bandCache) {
                    onDispose { bandCache.dispose() }
                }
                val bandKey = {
                    BandKey(
                        layout = bandLayout,
                        gutter = gutterLayout,
                        horizontal = horizontal.value,
                        vertical = vertical.value,
                        width = maxWidth,
                        height = maxHeight,
                        top = chromeInsets.top,
                        bottom = chromeInsets.bottom,
                        left = leftPad,
                    )
                }
                val textStartPx = with(density) {
                    (leftPad + gutterWidth + DesignTokens.space16).toPx()
                }
                val gutterLeftPx = with(density) { leftPad.toPx() }
                val gutterRightPx = with(density) { (leftPad + gutterWidth).toPx() }
                val bandWidthPx = with(density) { maxWidth.toPx() }
                val bandHeightPx = with(density) { maxHeight.toPx() }
                Box(
                    Modifier
                        .matchParentSize()
                        .scrollEdgeBands(
                            key = bandKey,
                            cache = bandCache,
                            topBand = chromeInsets.top,
                            bottomBand = chromeInsets.bottom,
                            clipStart = leftPad,
                        ) { canvas ->
                            // The bands only keep the window's top and bottom rows, so the
                            // painted lines are those of the viewport, not the whole document.
                            val layoutTop = vertical.value - topPadPx
                            val layoutBottom = bandHeightPx - topPadPx + vertical.value
                            canvas.save()
                            // Same clip as the live field: the copy carries no glyphs left of the gutter.
                            canvas.clipRect(gutterRightPx, 0f, bandWidthPx, bandHeightPx, ClipOp.Intersect)
                            canvas.translate(textStartPx - horizontal.value, topPadPx - vertical.value)
                            paintVisibleLines(
                                canvas,
                                bandLayout,
                                top = layoutTop,
                                bottom = layoutBottom,
                                cache = bandSlices,
                                onPainted = { EditorBandPaintStats.bandLines = it },
                            )
                            canvas.restore()
                            gutterLayout?.let { gutter ->
                                canvas.save()
                                canvas.translate(gutterLeftPx, topPadPx - vertical.value)
                                paintVisibleLines(canvas, gutter, layoutTop, layoutBottom, gutterSlices)
                                canvas.restore()
                            }
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
                // The track starts right of the sidebar and gutter; without the inset the thumb
                // would advertise that much scroll the state does not have.
                adapter = rememberEditorScrollbarAdapter(
                    horizontal,
                    insetPx = with(density) { (leftPad + gutterWidth).toPx() },
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = leftPad + gutterWidth, bottom = chromeInsets.bottom)
                    .fillMaxWidth(),
                style = DesignTokens.scrollbarStyle,
            )
        }
    }

    /**
     * The Go to Line popup. It is hosted by the editor area, not by the editor content: the
     * native editor surface clips its content to the popup's own hole, so a popup drawn there
     * would clip its own text away.
     */
    @Composable
    internal fun GotoLinePopup(modifier: Modifier = Modifier) {
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
                // The native path paints the popup plate over this border, so draw it last there.
                .neonBorder(
                    DesignTokens.accent,
                    shape,
                    radius = DesignTokens.glowRadiusLarge,
                    overContent = LocalNativeGlass.current,
                )
                .popupPanel("popup-goto-line", shape, skiaShadow = false)
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

    /** The document line and column a press at [position] points at, past the line end included. */
    private fun blockCorner(layout: TextLayoutResult, position: Offset, folds: FoldView): BlockCorner {
        val source = layout.layoutInput.text
        val row = layout.getLineForVerticalPosition(position.y).coerceIn(0, layout.lineCount - 1)
        val start = layout.getLineStart(row).coerceIn(0, source.length)
        var end = layout.getLineEnd(row).coerceIn(0, source.length)
        if (end > start && source[end - 1] == '\n') end--
        if (end > start && source[end - 1] == '\r') end--
        val lineRight = layout.getLineRight(row)
        if (folds.isEmpty) {
            val column = if (position.x <= lineRight) {
                (layout.getOffsetForPosition(position) - start).coerceIn(0, end - start)
            } else {
                virtualColumn(position.x, lineRight, end - start, asciiCharWidth(layout))
            }
            return BlockCorner(row, column)
        }
        // The row is the folded text, so the corner is taken on the document offset it maps to.
        val viewOffset: Int
        val extra: Int
        if (position.x <= lineRight) {
            viewOffset = layout.getOffsetForPosition(position).coerceIn(start, end)
            extra = 0
        } else {
            viewOffset = end
            extra = virtualColumn(position.x, lineRight, 0, asciiCharWidth(layout))
        }
        val docOffset = folds.toDoc(viewOffset).coerceIn(0, text.length)
        val line = lineIndexAt(text, docOffset)
        val lineStart = if (docOffset == 0) 0 else text.lastIndexOf('\n', docOffset - 1) + 1
        return BlockCorner(line, docOffset - lineStart + extra)
    }

    /** The fold whose placeholder box holds [position], or -1; the box is inflated by 1 dp. */
    private fun foldPlaceholderAt(
        folds: FoldView,
        layout: TextLayoutResult,
        position: Offset,
        inflate: Float,
    ): Int {
        if (folds.isEmpty || folds.viewLength != layout.layoutInput.text.length) return -1
        val offset = layout.getOffsetForPosition(position).coerceIn(0, folds.viewLength)
        // The nearest offset can sit one past the placeholder, so the fold before it is checked too.
        val last = folds.foldIndexStartingAtOrBefore(offset)
        for (index in intArrayOf(last, last - 1)) {
            if (index < 0) continue
            val start = folds.placeholderStart(index)
            if (offset < start || offset > start + FOLD_PLACEHOLDER.length) continue
            val head = layout.getBoundingBox(start)
            val tail = layout.getBoundingBox(start + FOLD_PLACEHOLDER.length - 1)
            val left = minOf(head.left, tail.left) - inflate
            val right = maxOf(head.right, tail.right) + inflate
            val top = minOf(head.top, tail.top)
            val bottom = maxOf(head.bottom, tail.bottom)
            if (position.x in left..right && position.y in top..bottom) return index
        }
        return -1
    }

    /** The width of the first non-blank ASCII glyph; the virtual column step of the block drag. */
    private fun asciiCharWidth(layout: TextLayoutResult): Float {
        val source = layout.layoutInput.text
        for (index in source.indices) {
            if (source[index].code in 0x21..0x7e) return layout.getBoundingBox(index).width
        }
        return 0f
    }

    private fun buildGutterText(lineCount: Int, caretLine: Int): AnnotatedString = buildAnnotatedString {
        for (line in 1..lineCount) {
            val start = length
            append(line.toString())
            if (line == caretLine + 1) addStyle(SpanStyle(color = DesignTokens.accent2), start, length)
            if (line < lineCount) append('\n')
        }
    }

    /** The gutter of a folded document: one number per row, from the row's document line. */
    private fun buildGutterText(lines: IntArray, caretRow: Int): AnnotatedString = buildAnnotatedString {
        for (row in lines.indices) {
            val start = length
            append((lines[row] + 1).toString())
            if (row == caretRow) addStyle(SpanStyle(color = DesignTokens.accent2), start, length)
            if (row < lines.size - 1) append('\n')
        }
    }

    private fun defaultForeground(scheme: ColorScheme): Color {
        val attributes: TextAttributes? = scheme.getAttributes(TextAttributesKey.DEFAULT_TEXT)
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
        const val CHANGE_MARKS_TAG: String = "change-marks"
        private const val MAX_SELECTION_STEPS: Int = 64

        /** The edit actions that run at every caret through [MultiCaret.perCaret]. */
        private val EVERY_CARET_ACTIONS = setOf(
            ActionId.COMMENT_LINE,
            ActionId.EDITOR_DUPLICATE,
            ActionId.EDITOR_DELETE_LINE,
            ActionId.INDENT,
            ActionId.UNINDENT,
            ActionId.EDITOR_TOGGLE_CASE,
        )
    }
}

/** A press position as the document line and character column it lands on. */
private data class BlockCorner(val line: Int, val column: Int)

/** The sorted, distinct document lines a secondary caret ends on. */
internal fun secondaryCaretLines(text: String, carets: List<TextRange>): IntArray {
    if (carets.isEmpty()) return IntArray(0)
    val starts = MultiCaret.lineStarts(text)
    return carets.map { MultiCaret.lineIndexAt(starts, it.end) }.distinct().sorted().toIntArray()
}

/** The index of the first element of [lines] that is at least [line]. */
internal fun firstLineAtOrAfter(lines: IntArray, line: Int): Int {
    val found = lines.binarySearch(line)
    return if (found >= 0) found else -found - 1
}

/** A secondary caret as the draw pass needs it: the selection clipped to painted lines, and its bar. */
internal data class VisibleCaret(val selection: TextRange?, val bar: Int?)

/**
 * The secondary [carets] that reach the lines [first]..[last], with selections clipped to those
 * lines. A caret whose bar lies outside the range still contributes a clipped selection; a
 * collapsed caret contributes only a bar. [lineOf], [lineStart] and [lineEnd] map offsets and
 * lines through the text layout, so the decision itself is testable without one.
 */
internal fun visibleCaretRanges(
    carets: List<TextRange>,
    lineOf: (Int) -> Int,
    lineStart: (Int) -> Int,
    lineEnd: (Int) -> Int,
    first: Int,
    last: Int,
): List<VisibleCaret> {
    if (carets.isEmpty() || first > last) return emptyList()
    val windowStart = lineStart(first)
    val windowEnd = lineEnd(last)
    val visible = ArrayList<VisibleCaret>(carets.size)
    for (caret in carets) {
        val bar = if (lineOf(caret.end) in first..last) caret.end else null
        if (caret.collapsed) {
            if (bar != null) visible += VisibleCaret(selection = null, bar = bar)
            continue
        }
        // A visible bar implies the selection touches the window; only a hidden bar needs both lines.
        val crosses = bar != null ||
            (lineOf(caret.max) >= first && lineOf(caret.min) <= last)
        val selection = if (crosses) {
            val from = maxOf(caret.min, windowStart)
            val to = minOf(caret.max, windowEnd)
            if (from < to) TextRange(from, to) else null
        } else {
            null
        }
        if (selection != null || bar != null) visible += VisibleCaret(selection, bar)
    }
    return visible
}

/**
 * The lines of [layout] that intersect the layout rows [top]..[bottom], widened by [margin]
 * lines. The result is bounded by the viewport, not by the document, so painting it keeps a
 * scroll frame independent of the file length.
 */
internal fun visibleLineRange(
    layout: TextLayoutResult,
    top: Float,
    bottom: Float,
    margin: Int = BAND_LINE_MARGIN,
): IntRange? {
    if (layout.lineCount == 0) return null
    if (bottom < top) return null
    val first = layout.getLineForVerticalPosition(top)
    val last = layout.getLineForVerticalPosition(bottom)
    val start = (first - margin).coerceAtLeast(0)
    val end = (last + margin).coerceAtMost(layout.lineCount - 1)
    return if (end < start) null else start..end
}

/**
 * Paints the lines of [layout] that intersect the layout rows [top]..[bottom] into [canvas],
 * which is already translated to the layout origin. Only a bounded run of lines is measured and
 * drawn; [cache] keeps that run across scroll frames. [onPainted] receives the painted line count
 * for the frame observation point.
 */
internal fun paintVisibleLines(
    canvas: androidx.compose.ui.graphics.Canvas,
    layout: TextLayoutResult,
    top: Float,
    bottom: Float,
    cache: LineSliceCache,
    margin: Int = BAND_LINE_MARGIN,
    onPainted: (Int) -> Unit = {},
) {
    val range = visibleLineRange(layout, top, bottom, margin) ?: return
    val (slice, firstLine) = cache.slice(layout, range)
    canvas.save()
    canvas.translate(0f, layout.getLineTop(firstLine))
    slice.paint(canvas)
    canvas.restore()
    onPainted(range.last - range.first + 1)
}

/** One line's piece of a problem range, as offsets into the text layout. */
internal data class ProblemSegment(val line: Int, val start: Int, val end: Int)

/**
 * The per-line pieces of [from]..[to] that fall on rows [first]..[last]. A piece is empty when a
 * range ends at a line end; on the line the range starts on it is still returned, so the caller
 * can draw its one-character fallback width, while an empty piece on a later line is dropped.
 * Map the layout through the [lineOf], [lineStart] and [lineEnd] functions, which keeps the rule
 * testable without a layout.
 */
internal fun problemSegments(
    from: Int,
    to: Int,
    limit: Int,
    first: Int,
    last: Int,
    lineOf: (Int) -> Int,
    lineStart: (Int) -> Int,
    lineEnd: (Int) -> Int,
): List<ProblemSegment> {
    if (from < 0 || from >= limit) return emptyList()
    val firstLine = lineOf(from)
    val lastLine = lineOf((to.coerceAtLeast(from + 1) - 1).coerceAtMost(limit - 1))
    if (lastLine < first || firstLine > last) return emptyList()
    val segments = ArrayList<ProblemSegment>()
    for (line in maxOf(firstLine, first)..minOf(lastLine, last)) {
        val segmentStart = maxOf(from, lineStart(line))
        val segmentEnd = minOf(maxOf(to, segmentStart + 1), lineEnd(line))
        if (segmentEnd < segmentStart) continue
        if (segmentEnd == segmentStart && line != firstLine) continue
        segments += ProblemSegment(line, segmentStart, segmentEnd)
    }
    return segments
}

/**
 * Draws a squiggle under every problem that starts in the layout rows [first]..[last] and is
 * not hidden inside a fold. Offsets are mapped from the document into the folded layout, and a
 * problem whose range crosses lines is drawn once per line.
 */
private fun DrawScope.drawProblemUnderlines(
    textLayout: TextLayoutResult,
    problems: List<FileProblem>,
    folds: FoldView,
    textLength: Int,
    first: Int,
    last: Int,
) {
    val limit = textLayout.layoutInput.text.length
    val strokeWidth = 1.dp.toPx()
    val windowStart = textLayout.getLineStart(first)
    val windowEnd = textLayout.getLineEnd(last)
    val layoutText = textLayout.layoutInput.text
    // The code font is monospaced, so one visible glyph gives the width an empty range widens to.
    val charWidth = (windowStart until minOf(windowEnd, limit))
        .firstOrNull { !layoutText[it].isWhitespace() }
        ?.let { textLayout.getBoundingBox(it).width }
        ?: (4 * strokeWidth)
    for (problem in problems) {
        if (problem.start < 0 || problem.start >= textLength) continue
        if (!folds.isEmpty && folds.docLength == textLength && folds.foldIndexContaining(problem.start) >= 0) continue
        val end = problem.end.coerceIn(problem.start, textLength)
        val usable = folds.isEmpty || folds.docLength != textLength
        val from = (if (usable) problem.start else folds.toView(problem.start)).coerceIn(0, limit)
        val to = (if (usable) end else folds.toView(end)).coerceIn(from, limit)
        // The problems are start-sorted and the fold mapping is monotone, so every later one is
        // past the window too.
        if (from > windowEnd) break
        if (to < windowStart) continue
        if (from >= limit) continue
        val color = if (problem.severity == ProblemSeverity.ERROR) DesignTokens.neonPink else DesignTokens.neonAmber
        val segments = problemSegments(
            from = from,
            to = to,
            limit = limit,
            first = first,
            last = last,
            lineOf = textLayout::getLineForOffset,
            lineStart = textLayout::getLineStart,
            lineEnd = textLayout::getLineEnd,
        )
        for (segment in segments) {
            val left = textLayout.getHorizontalPosition(segment.start, usePrimaryDirection = true)
            var right = textLayout.getHorizontalPosition(segment.end, usePrimaryDirection = true)
            if (right <= left) right = left + charWidth
            drawWave(left, right, textLayout.getLineBottom(segment.line) - 2.dp.toPx(), color, strokeWidth)
        }
    }
}

/** A one-pixel-high zigzag along [left]..[right] at [baseline]. */
private fun DrawScope.drawWave(left: Float, right: Float, baseline: Float, color: Color, strokeWidth: Float) {
    val step = 3.dp.toPx()
    val amplitude = 1.dp.toPx()
    val path = Path()
    path.moveTo(left, baseline)
    var x = left
    var up = true
    while (x < right) {
        x = minOf(x + step, right)
        path.lineTo(x, baseline + if (up) -amplitude else amplitude)
        up = !up
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
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

/** One drawn change mark: the hunk it belongs to and the gutter rows it covers. */
internal data class ChangeMarkRow(
    val hunk: LineHunk,
    val firstRow: Int,
    val lastRow: Int,
    val kind: HunkKind,
)

/**
 * The gutter rows [hunks] are drawn on. A folded hunk is drawn on its visible rows; a hunk whose
 * lines are all inside a fold is one MODIFIED mark on the fold's row.
 */
internal fun changeMarkRows(hunks: List<LineHunk>, foldView: FoldView, lineCount: Int): List<ChangeMarkRow> {
    val rows = ArrayList<ChangeMarkRow>(hunks.size)
    for (hunk in hunks) {
        if (hunk.newCount == 0) {
            val boundary = hunk.newStart.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
            val row = foldView.rowOf(boundary)
            rows += ChangeMarkRow(hunk, row, row, HunkKind.DELETED)
            continue
        }
        if (foldView.isEmpty) {
            rows += ChangeMarkRow(hunk, hunk.newStart, hunk.newStart + hunk.newCount - 1, hunk.kind)
            continue
        }
        var runStart = -1
        var runEnd = -1
        for (line in hunk.newStart until hunk.newStart + hunk.newCount) {
            val row = foldView.visibleRowOf(line) ?: continue
            when {
                runStart < 0 -> {
                    runStart = row
                    runEnd = row
                }
                row == runEnd + 1 -> runEnd = row
                else -> {
                    rows += ChangeMarkRow(hunk, runStart, runEnd, hunk.kind)
                    runStart = row
                    runEnd = row
                }
            }
        }
        if (runStart >= 0) {
            rows += ChangeMarkRow(hunk, runStart, runEnd, hunk.kind)
        } else {
            val row = foldView.rowOf(hunk.newStart)
            rows += ChangeMarkRow(hunk, row, row, HunkKind.MODIFIED)
        }
    }
    return rows
}
