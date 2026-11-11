package stride.app.terminal

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.jediterm.terminal.TextStyle as JediTextStyle
import com.jediterm.terminal.model.TerminalTextBuffer
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.DesignTokens
import kotlin.math.floor

private val cellTextStyle = TextStyle(
    fontFamily = DesignTokens.codeFont,
    fontSize = DesignTokens.codeTextSize,
)

private data class CellColors(val foreground: Color, val background: Color, val bold: Boolean)

/** Draws [session]'s screen and scrollback as a monospace grid, and forwards typing to it. */
@Composable
fun TerminalView(session: TerminalSession, modifier: Modifier = Modifier, focusRequests: Int = 0) {
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphWidth = remember(measurer, density) {
        measurer.measure("M", cellTextStyle).size.width.toFloat()
    }.coerceAtLeast(1f)
    val cellHeight = with(density) { DesignTokens.codeLineHeight.toPx() }

    var focused by remember { mutableStateOf(false) }
    var scrollOffset by remember { mutableIntStateOf(0) }
    var selectionStart by remember { mutableIntStateOf(-1) }
    var selectionEnd by remember { mutableIntStateOf(-1) }

    val blinkAlpha: Float = if (LocalAnimationsEnabled.current) {
        val transition = rememberInfiniteTransition(label = "terminal-cursor")
        val alpha by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
            label = "terminal-cursor-alpha",
        )
        alpha
    } else {
        1f
    }

    fun copySelection(): Boolean {
        if (selectionStart < 0 || selectionEnd < 0) return false
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        val text = session.buffer.lockedLines(from, to - from + 1).joinToString("\n")
        clipboard.setText(AnnotatedString(text))
        return true
    }

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(session.revision) {
        scrollOffset = 0
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.isMetaPressed && event.key == Key.V -> {
                        clipboard.getText()?.text?.let(session::write)
                        true
                    }
                    event.isMetaPressed && event.key == Key.C -> copySelection()
                    else -> {
                        val codePoint = event.utf16CodePoint
                        val char = if (codePoint in 0x20..0x10FFFF) codePoint.toChar() else null
                        session.sendKey(
                            key = event.key,
                            meta = event.isMetaPressed,
                            ctrl = event.isCtrlPressed,
                            alt = event.isAltPressed,
                            shift = event.isShiftPressed,
                            char = char,
                        )
                    }
                }
            }
            .focusable(),
    ) {
        val columns = (constraints.maxWidth / glyphWidth).toInt().coerceAtLeast(1)
        val rows = (constraints.maxHeight / cellHeight).toInt().coerceAtLeast(1)
        LaunchedEffect(columns, rows) {
            session.resize(columns, rows)
        }

        val rowAt: (Float) -> Int = { y ->
            floor(y / cellHeight).toInt() - scrollOffset
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            scrollOffset = (scrollOffset - delta.toInt())
                                .coerceIn(0, session.buffer.historyLinesCount)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures {
                        runCatching { focusRequester.requestFocus() }
                        selectionStart = -1
                        selectionEnd = -1
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            selectionStart = rowAt(offset.y)
                            selectionEnd = selectionStart
                        },
                        onDrag = { change, _ ->
                            selectionEnd = rowAt(change.position.y)
                        },
                    )
                },
        ) {
            val buffer = session.buffer
            val history = buffer.historyLinesCount
            val selectedFrom = minOf(selectionStart, selectionEnd)
            val selectedTo = maxOf(selectionStart, selectionEnd)
            buffer.lock()
            try {
                for (row in 0 until rows) {
                    val lineIndex = row - scrollOffset
                    if (lineIndex < -history || lineIndex >= buffer.height) continue
                    val top = row * cellHeight
                    if (selectionStart >= 0 && lineIndex in selectedFrom..selectedTo) {
                        drawRect(
                            color = DesignTokens.selection,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, cellHeight),
                        )
                    }
                    drawLineCells(measurer, buffer, lineIndex, row, columns, glyphWidth, cellHeight)
                }
            } finally {
                buffer.unlock()
            }
            drawCursor(session, columns, rows, glyphWidth, cellHeight, scrollOffset, focused, blinkAlpha)
        }
    }
}

private fun DrawScope.drawLineCells(
    measurer: TextMeasurer,
    buffer: TerminalTextBuffer,
    lineIndex: Int,
    row: Int,
    columns: Int,
    glyphWidth: Float,
    cellHeight: Float,
) {
    val top = row * cellHeight
    var x = 0
    while (x < columns) {
        val colors = buffer.cellColors(x, lineIndex)
        var end = x + 1
        while (end < columns && buffer.cellColors(end, lineIndex) == colors) end++
        if (colors.background != TerminalPalette.background) {
            drawRect(
                color = colors.background,
                topLeft = Offset(x * glyphWidth, top),
                size = Size((end - x) * glyphWidth, cellHeight),
            )
        }
        if (end > x) {
            val text = buildString {
                for (column in x until end) {
                    val char = buffer.getCharAt(column, lineIndex)
                    append(if (char == '\u0000') ' ' else char)
                }
            }
            if (text.isNotBlank()) {
                drawText(
                    textMeasurer = measurer,
                    text = text,
                    topLeft = Offset(x * glyphWidth, top),
                    style = cellTextStyle.copy(
                        color = colors.foreground,
                        fontWeight = if (colors.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
        }
        x = end
    }
}

private fun DrawScope.drawCursor(
    session: TerminalSession,
    columns: Int,
    rows: Int,
    glyphWidth: Float,
    cellHeight: Float,
    scrollOffset: Int,
    focused: Boolean,
    blinkAlpha: Float,
) {
    val cursorRow = session.cursorY - 1
    val visible = scrollOffset == 0 && session.cursorX in 0 until columns && cursorRow in 0 until rows
    if (!visible) return
    val left = session.cursorX * glyphWidth
    val top = cursorRow * cellHeight
    if (focused && blinkAlpha > 0.05f) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = DesignTokens.accent2Glow.copy(alpha = DesignTokens.accent2Glow.alpha * blinkAlpha)
                asFrameworkPaint().maskFilter =
                    MaskFilter.makeBlur(FilterBlurMode.NORMAL, DesignTokens.glowRadius.toPx() / 2f)
            }
            canvas.drawRect(left, top, left + glyphWidth, top + cellHeight, paint)
        }
        drawRect(
            color = TerminalPalette.cursor.copy(alpha = blinkAlpha),
            topLeft = Offset(left, top),
            size = Size(glyphWidth, cellHeight),
        )
    } else if (focused) {
        drawRect(
            color = TerminalPalette.cursor,
            topLeft = Offset(left, top),
            size = Size(glyphWidth, cellHeight),
            style = Stroke(1.dp.toPx()),
        )
    }
}

private fun TerminalTextBuffer.cellColors(x: Int, lineIndex: Int): CellColors {
    val style: JediTextStyle? = getStyleAt(x, lineIndex)
    val inverse = style?.hasOption(JediTextStyle.Option.INVERSE) == true
    val foreground = TerminalPalette.colorOf(style?.foreground, TerminalPalette.foreground)
    val background = TerminalPalette.colorOf(style?.background, TerminalPalette.background)
    return CellColors(
        foreground = if (inverse) background else foreground,
        background = if (inverse) foreground else background,
        bold = style?.hasOption(JediTextStyle.Option.BOLD) == true,
    )
}

private fun TerminalTextBuffer.lockedLines(from: Int, count: Int): List<String> {
    lock()
    try {
        return (from until from + count).map { getLine(it).text }
    } finally {
        unlock()
    }
}
