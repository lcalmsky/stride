package stride.app.vcs

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.navigation.openFileAtLine
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface
import stride.core.vfs.VFile
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** Alpha of the added-line tint; the code text keeps 4.5:1 on it in both palettes (see DiffTintTest). */
internal const val DIFF_ADDED_TINT_ALPHA: Float = 0.20f

/** Alpha of the removed-line tint; the code text keeps 4.5:1 on it in both palettes (see DiffTintTest). */
internal const val DIFF_DELETED_TINT_ALPHA: Float = 0.20f

private val diffNumberWidth = 44.dp
private val diffSignWidth = 16.dp
private val diffRowHeight = 20.dp
private val diffDigitStyle = TextStyle(fontFamily = DesignTokens.codeFont, fontSize = DesignTokens.codeTextSize)

/**
 * The one-column diff popup: the title, a file row when there are several files, and the unified
 * rows. F7/⇧F7 move between hunks, ⌃⇧→/⌃⇧← between files, ↑/↓/PgUp/PgDn scroll, ↵ or a click on a
 * fold row expands it, F4/↵ on a code row opens an openable file at that line, Esc closes.
 */
@Composable
fun DiffPopup(request: DiffRequest, state: AppState, onClose: () -> Unit) {
    val files = request.files
    if (files.isEmpty()) return
    var fileIndex by remember(request) {
        mutableStateOf(request.initialFile.coerceIn(0, files.lastIndex))
    }
    val file = files[fileIndex]
    var rows by remember(request, fileIndex) { mutableStateOf<List<DiffRow>?>(null) }
    var selected by remember(request, fileIndex) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth = remember(rows, measurer, density) { numberColumnWidth(rows, measurer, density) }

    fun scrollTo(index: Int) {
        val count = rows?.size ?: return
        scope.launch { listState.scrollToItem(index.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    }

    fun select(index: Int) {
        selected = index
        scrollTo(index)
    }

    fun switchFile(delta: Int) {
        val next = (fileIndex + delta).coerceIn(0, files.lastIndex)
        if (next != fileIndex) fileIndex = next
    }

    fun expandFold(index: Int) {
        val current = rows ?: return
        rows = DiffRows.expand(current, index)
        selected = index
    }

    fun open(row: DiffRow.Code) {
        val path = file.path ?: return
        if (!file.openable) return
        val line = (row.newNumber ?: row.oldNumber)?.minus(1) ?: return
        val session = state.session ?: return
        onClose()
        openFileAtLine(state, session, VFile(path), line)
    }

    fun confirm() {
        when (val row = rows?.getOrNull(selected)) {
            is DiffRow.Fold -> expandFold(selected)
            is DiffRow.Code -> open(row)
            else -> Unit
        }
    }

    fun moveBy(delta: Int) {
        val count = rows?.size ?: return
        select((selected + delta).coerceIn(0, count - 1))
    }

    fun moveHunk(delta: Int) {
        val current = rows ?: return
        val target = DiffRows.nextHunk(current, selected, forward = delta > 0) ?: return
        select(target)
    }

    LaunchedEffect(request, fileIndex) {
        var built = withContext(Dispatchers.Default) { DiffRows.build(file) }
        val line = request.initialLine
        var target = 0
        if (line != null && fileIndex == request.initialFile) {
            val row = DiffRows.rowAtLine(built, line)
            if (built.getOrNull(row) is DiffRow.Fold) built = DiffRows.expand(built, row)
            target = DiffRows.rowAtLine(built, line)
        }
        // The effect can run off the UI thread; write the rows on a frame so composition observes them.
        withFrameNanos { }
        selected = target
        rows = built
        listState.requestScrollToItem(target)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun pageSize(): Int = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(10)

    Box(
        Modifier
            .fillMaxSize()
            .scrimSurface(DesignTokens.scrim.alpha * fade, holes)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .popupPanel(DIFF_POPUP_KEY, shape)
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
                            event.key == Key.F7 -> {
                                moveHunk(if (event.isShiftPressed) -1 else 1)
                                true
                            }
                            event.key == Key.DirectionRight && event.isCtrlPressed && event.isShiftPressed -> {
                                switchFile(1)
                                true
                            }
                            event.key == Key.DirectionLeft && event.isCtrlPressed && event.isShiftPressed -> {
                                switchFile(-1)
                                true
                            }
                            event.key == Key.DirectionDown -> {
                                moveBy(1)
                                true
                            }
                            event.key == Key.DirectionUp -> {
                                moveBy(-1)
                                true
                            }
                            event.key == Key.PageDown -> {
                                moveBy(pageSize())
                                true
                            }
                            event.key == Key.PageUp -> {
                                moveBy(-pageSize())
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                confirm()
                                true
                            }
                            event.key == Key.F4 -> {
                                (rows?.getOrNull(selected) as? DiffRow.Code)?.let(::open)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                DiffTitle(request.title)
                if (files.size > 1 || request.moreFiles > 0) {
                    FileRow(
                        files = files,
                        selected = fileIndex,
                        moreFiles = request.moreFiles,
                        onSelect = { index -> if (index != fileIndex) fileIndex = index },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(rows.orEmpty()) { index, row ->
                            when (row) {
                                is DiffRow.Code -> CodeRow(
                                    row = row,
                                    selected = index == selected,
                                    numberWidth = numberWidth,
                                    onClick = { select(index) },
                                )
                                is DiffRow.Fold -> FoldRow(
                                    fold = row,
                                    selected = index == selected,
                                    onClick = { expandFold(index) },
                                )
                                is DiffRow.Message -> MessageRow(row)
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = DesignTokens.scrollbarStyle,
                    )
                }
                PopupHintBar("F7 Next Change", "⇧F7 Previous", "⌃⇧→ Next File", "↵ Open", "Esc Close")
            }
        }
    }
}

internal const val DIFF_POPUP_KEY: String = "popup-diff"

@Composable
private fun DiffTitle(title: String) {
    Text(
        title,
        color = DesignTokens.text,
        fontSize = DesignTokens.uiTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
    )
}

@Composable
private fun FileRow(files: List<DiffFile>, selected: Int, moreFiles: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEachIndexed { index, file ->
            if (index > 0) Spacer(Modifier.width(DesignTokens.space12))
            Text(
                file.displayPath,
                color = if (index == selected) DesignTokens.accentOnPopupPlate else DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
            )
        }
        if (moreFiles > 0) {
            Spacer(Modifier.width(DesignTokens.space12))
            Text(
                "… $moreFiles more files",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}

@Composable
private fun CodeRow(row: DiffRow.Code, selected: Boolean, numberWidth: Dp, onClick: () -> Unit) {
    val tint = when (row.sign) {
        '+' -> DesignTokens.vcsAdded.copy(alpha = DIFF_ADDED_TINT_ALPHA)
        '-' -> DesignTokens.vcsDeleted.copy(alpha = DIFF_DELETED_TINT_ALPHA)
        else -> DesignTokens.transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(diffRowHeight)
            .background(tint)
            .selectedEdge(selected)
            .pointerInput(row) { detectTapGestures { onClick() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberCell(row.oldNumber, numberWidth)
        NumberCell(row.newNumber, numberWidth)
        Text(
            if (row.sign == ' ') "" else row.sign.toString(),
            color = DesignTokens.text,
            fontSize = DesignTokens.codeTextSize,
            fontFamily = DesignTokens.codeFont,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(diffSignWidth),
        )
        Text(
            row.text.ifEmpty { " " },
            color = DesignTokens.text,
            fontSize = DesignTokens.codeTextSize,
            fontFamily = DesignTokens.codeFont,
            lineHeight = DesignTokens.codeLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(start = DesignTokens.space8),
        )
    }
}

@Composable
private fun NumberCell(number: Int?, width: Dp) {
    Text(
        number?.toString().orEmpty(),
        color = DesignTokens.text,
        fontSize = DesignTokens.codeTextSize,
        fontFamily = DesignTokens.codeFont,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

/** The widest number the rows draw, in the code font, but never narrower than [diffNumberWidth]. */
private fun numberColumnWidth(rows: List<DiffRow>?, measurer: TextMeasurer, density: Density): Dp {
    val digits = rows.orEmpty().maxOfOrNull(::digitCount) ?: 1
    val digit = with(density) { measurer.measure("0", diffDigitStyle).size.width.toDp() }
    return (digit.value * digits).dp.coerceAtLeast(diffNumberWidth)
}

private fun digitCount(row: DiffRow): Int = when (row) {
    is DiffRow.Code -> maxOf(row.oldNumber ?: 0, row.newNumber ?: 0).toString().length
    is DiffRow.Fold -> row.lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) }?.toString()?.length ?: 1
    is DiffRow.Message -> 1
}

@Composable
private fun FoldRow(fold: DiffRow.Fold, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(diffRowHeight)
            .selectedEdge(selected)
            .pointerInput(fold) { detectTapGestures { onClick() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⋯ ${fold.count} unchanged lines",
            color = DesignTokens.accent2,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.padding(start = DesignTokens.space16),
        )
    }
}

@Composable
private fun MessageRow(row: DiffRow.Message) {
    Text(
        row.text,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.uiTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
    )
}

/** The selected row's marker: one accent bar at the left edge, no glow. */
private fun Modifier.selectedEdge(selected: Boolean): Modifier = if (!selected) {
    this
} else {
    drawBehind {
        drawRect(DesignTokens.accent, size = Size(2.dp.toPx(), size.height))
    }
}

/**
 * Opens the diff popup for [file]: the file at HEAD against the open editor's text, or against the
 * text on disk when no editor has the file open. [line] is the 0-based line the popup starts at.
 * Outside a repository, or without git, the status bar gets the reason and no popup opens.
 */
fun openWorkingTreeDiff(state: AppState, file: Path, line: Int? = null): Boolean {
    val session = state.session ?: return false
    val git = session.git
    val path = file.toAbsolutePath().normalize()
    val editorText = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .firstOrNull { it.file.path.toAbsolutePath().normalize() == path }
        ?.value?.text
    git.launch {
        // The snapshot is empty until a refresh has run, and the first diff can be asked for before.
        if (git.snapshot.repository == null) git.refreshNow()
        val old = git.baseText(path)
        val root = git.snapshot.repository?.root
        if (root == null && old is BaseText.Unavailable) {
            Snapshot.withMutableSnapshot { state.statusMessage = old.reason }
            return@launch
        }
        val new = editorText?.let { BaseText.Text(it) } ?: workingText(path)
        val display = displayPath(root, path)
        val request = DiffRequest(
            title = "$display  HEAD ↔ Local",
            files = listOf(DiffFile(path, display, old, new, openable = true)),
            initialLine = line,
        )
        Snapshot.withMutableSnapshot { state.diffRequest = request }
    }
    return true
}

private fun workingText(path: Path): BaseText {
    if (!Files.exists(path)) return BaseText.Text("")
    return try {
        if (Files.size(path) > GitService.MAX_BASE_BYTES) {
            BaseText.Unavailable("Larger than 8 MB")
        } else {
            BaseText.Text(Files.readString(path))
        }
    } catch (_: CharacterCodingException) {
        BaseText.Unavailable("Not valid UTF-8")
    } catch (_: IOException) {
        BaseText.Unavailable("Cannot read ${path.fileName}")
    }
}

private fun displayPath(root: Path?, path: Path): String {
    if (root == null) return path.fileName.toString()
    val real = runCatching { path.toRealPath() }.getOrDefault(path)
    return if (real.startsWith(root)) root.relativize(real).toString() else path.fileName.toString()
}
