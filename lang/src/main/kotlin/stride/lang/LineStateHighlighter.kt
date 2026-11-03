package stride.lang

import java.util.Arrays

data class TokenSpan(val start: Int, val end: Int, val key: TextAttributesKey)

class LineStateHighlighter(
    private val lexer: Lexer,
    text: CharSequence,
    private val tokenHighlights: (TokenType) -> Array<TextAttributesKey> = { emptyArray() },
) {
    private val buffer = StringBuilder(text)
    private var lineStarts: IntArray = computeLineStarts(buffer)
    private var lineStates: IntArray = IntArray(lineStarts.size)
    private val spanList = ArrayList<TokenSpan>()

    var lastRelexedLineCount: Int = 0
        private set

    init {
        fullRelex()
    }

    fun spans(): List<TokenSpan> = ArrayList(spanList)

    fun lineState(line: Int): Int = lineStates[line]

    fun lineCount(): Int = lineStarts.size

    fun edit(offset: Int, oldLength: Int, newText: String) {
        require(offset >= 0 && oldLength >= 0 && offset + oldLength <= buffer.length) {
            "Invalid edit: offset=$offset oldLength=$oldLength length=${buffer.length}"
        }
        val oldLineStarts = lineStarts
        val oldLineStates = lineStates
        val oldSpans = spanList.toList()
        val delta = newText.length - oldLength
        val firstChangedLine = lineIndexOf(oldLineStarts, offset)
        val startLineOffset = oldLineStarts[firstChangedLine]
        val editEnd = offset + newText.length

        buffer.replace(offset, offset + oldLength, newText)
        val newLineStarts = computeLineStarts(buffer)
        lineStarts = newLineStarts

        val newStates = IntArray(newLineStarts.size)
        for (line in 0 until firstChangedLine) newStates[line] = oldLineStates[line]
        newStates[0] = 0

        val newSpans = ArrayList<TokenSpan>()
        var line = firstChangedLine
        var state = if (line == 0) 0 else oldLineStates[line]
        var stopOldOffset = -1
        var relexed = 0
        while (line < newLineStarts.size) {
            newStates[line] = state
            val nextState = lexLine(line, state, newSpans)
            relexed++
            if (line + 1 < newLineStarts.size) {
                val boundary = newLineStarts[line + 1]
                newStates[line + 1] = nextState
                if (boundary >= editEnd) {
                    val oldBoundary = boundary - delta
                    val oldIndex = lineIndexOfExact(oldLineStarts, oldBoundary)
                    if (oldIndex >= 0 && oldLineStates[oldIndex] == nextState) {
                        stopOldOffset = oldBoundary
                        for (j in (line + 1) until newLineStarts.size) {
                            val k = oldIndex + (j - (line + 1))
                            if (k < oldLineStates.size) newStates[j] = oldLineStates[k]
                        }
                        break
                    }
                }
            }
            state = nextState
            line++
        }

        val combined = ArrayList<TokenSpan>(oldSpans.size + newSpans.size)
        for (span in oldSpans) {
            if (span.end <= startLineOffset) combined.add(span)
        }
        combined.addAll(newSpans)
        if (stopOldOffset >= 0) {
            for (span in oldSpans) {
                if (span.start >= stopOldOffset) {
                    combined.add(span.copy(start = span.start + delta, end = span.end + delta))
                }
            }
        }

        spanList.clear()
        spanList.addAll(combined)
        lineStates = newStates
        lastRelexedLineCount = relexed
    }

    private fun fullRelex() {
        lineStarts = computeLineStarts(buffer)
        lineStates = IntArray(lineStarts.size)
        spanList.clear()
        var state = 0
        for (line in lineStarts.indices) {
            lineStates[line] = state
            state = lexLine(line, state, spanList)
        }
    }

    private fun lexLine(line: Int, state: Int, sink: MutableList<TokenSpan>): Int {
        val lineStart = lineStarts[line]
        val lineEnd = lineEnd(line)
        lexer.start(buffer, lineStart, buffer.length, state)
        while (true) {
            val type = lexer.getTokenType() ?: break
            val start = lexer.getTokenStart()
            if (start >= lineEnd) break
            sink.add(TokenSpan(start, lexer.getTokenEnd(), keyOf(type)))
            lexer.advance()
        }
        return lexer.getState()
    }

    private fun lineEnd(line: Int): Int =
        if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else buffer.length

    private fun keyOf(type: TokenType): TextAttributesKey =
        tokenHighlights(type).firstOrNull() ?: TextAttributesKey.DEFAULT_TEXT

    private fun computeLineStarts(text: CharSequence): IntArray {
        val starts = ArrayList<Int>(text.length / 32 + 1)
        starts.add(0)
        for (i in 0 until text.length) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray()
    }

    private fun lineIndexOf(starts: IntArray, offset: Int): Int {
        val index = Arrays.binarySearch(starts, offset)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }

    private fun lineIndexOfExact(starts: IntArray, offset: Int): Int {
        if (offset < 0) return -1
        val index = Arrays.binarySearch(starts, offset)
        return if (index >= 0) index else -1
    }
}
