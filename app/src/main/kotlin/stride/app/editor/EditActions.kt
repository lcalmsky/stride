package stride.app.editor

import androidx.compose.ui.text.TextRange

data class EditResult(val text: String, val selection: TextRange)

/** Line-oriented edit operations, all pure functions of the text and the selection. */
object EditActions {

    fun duplicateLineOrSelection(text: String, selection: TextRange): EditResult {
        val min = selection.min
        val max = selection.max
        if (min != max) {
            val selected = text.substring(min, max)
            val newText = text.substring(0, max) + selected + text.substring(max)
            return EditResult(newText, TextRange(max, max + selected.length))
        }
        val start = lineStart(text, min)
        val end = lineEnd(text, min)
        val line = text.substring(start, end)
        val column = min - start
        val newText = text.substring(0, end) + "\n" + line + text.substring(end)
        return EditResult(newText, TextRange(end + 1 + column))
    }

    fun deleteLines(text: String, selection: TextRange): EditResult {
        val caret = selection.min
        val caretLine = lineIndexOf(text, caret)
        val caretColumn = caret - lineStart(text, caret)
        val firstStart = lineStart(text, selection.min)
        val lastCovered = if (selection.max > selection.min) selection.max - 1 else selection.min
        var deleteStart = firstStart
        var deleteEnd = lineEnd(text, lastCovered)
        if (deleteEnd < text.length) {
            deleteEnd++
        } else {
            if (deleteStart > 0) deleteStart--
            deleteEnd = text.length
        }
        val newText = text.substring(0, deleteStart) + text.substring(deleteEnd)
        val targetLine = caretLine.coerceAtMost(lineCount(newText) - 1)
        val targetStart = lineStartAt(newText, targetLine)
        val targetEnd = lineEnd(newText, targetStart)
        val offset = (targetStart + caretColumn).coerceIn(targetStart, targetEnd)
        return EditResult(newText, TextRange(offset))
    }

    fun moveLines(text: String, selection: TextRange, up: Boolean): EditResult {
        val lines = splitLines(text)
        val firstLine = lineIndexOf(text, selection.min)
        val lastCovered = if (selection.max > selection.min) selection.max - 1 else selection.min
        val lastLine = lineIndexOf(text, lastCovered)
        if (up && firstLine == 0) return EditResult(text, selection)
        if (!up && lastLine == lines.lastIndex) return EditResult(text, selection)

        fun mapLine(line: Int): Int = when {
            up && line in firstLine..lastLine -> line - 1
            up && line == firstLine - 1 -> lastLine
            !up && line in firstLine..lastLine -> line + 1
            !up && line == lastLine + 1 -> firstLine
            else -> line
        }

        val newLines = ArrayList<String>(lines.size)
        if (up) {
            newLines.addAll(lines.subList(0, firstLine - 1))
            newLines.addAll(lines.subList(firstLine, lastLine + 1))
            newLines.add(lines[firstLine - 1])
            newLines.addAll(lines.subList(lastLine + 1, lines.size))
        } else {
            newLines.addAll(lines.subList(0, firstLine))
            newLines.add(lines[lastLine + 1])
            newLines.addAll(lines.subList(firstLine, lastLine + 1))
            newLines.addAll(lines.subList(lastLine + 2, lines.size))
        }

        val startLine = lineIndexOf(text, selection.start)
        val startColumn = selection.start - lineStart(text, selection.start)
        val endLine = lineIndexOf(text, selection.end)
        val endColumn = selection.end - lineStart(text, selection.end)
        val newStart = offsetOf(newLines, mapLine(startLine), startColumn)
        val newEnd = offsetOf(newLines, mapLine(endLine), endColumn)
        return EditResult(newLines.joinToString("\n"), TextRange(newStart, newEnd))
    }

    fun toggleLineComment(text: String, selection: TextRange, prefix: String): EditResult {
        val spans = lineSpans(text, selection)
        val nonBlank = spans.filter { text.substring(it.first, it.second).isNotBlank() }
        if (nonBlank.isEmpty()) return EditResult(text, selection)

        val allCommented = nonBlank.all { (start, end) ->
            text.substring(start, end).trimStart().startsWith(prefix)
        }
        return if (allCommented) removeLineComments(text, selection, prefix, nonBlank)
        else addLineComments(text, selection, prefix, nonBlank)
    }

    fun toggleBlockComment(text: String, selection: TextRange, prefix: String, suffix: String): EditResult {
        val min = selection.min
        val max = selection.max
        val selected = text.substring(min, max)
        if (selected.length >= prefix.length + suffix.length &&
            selected.startsWith(prefix) &&
            selected.endsWith(suffix)
        ) {
            val newText = text.substring(0, min) + text.substring(min + prefix.length, max - suffix.length) +
                text.substring(max)
            return EditResult(newText, TextRange(min, max - prefix.length - suffix.length))
        }
        val newText = text.substring(0, min) + prefix + selected + suffix + text.substring(max)
        return EditResult(newText, TextRange(min + prefix.length, max + prefix.length))
    }

    fun indent(text: String, selection: TextRange, unit: String = "    "): EditResult {
        val min = selection.min
        val max = selection.max
        if (min == max) {
            val newText = text.substring(0, min) + unit + text.substring(min)
            return EditResult(newText, TextRange(min + unit.length))
        }
        val spans = lineSpans(text, selection)
        val newText = buildString {
            var cursor = 0
            for ((start, _) in spans) {
                append(text, cursor, start)
                append(unit)
                cursor = start
            }
            append(text, cursor, text.length)
        }
        val firstStart = spans.first().first
        val lastEnd = spans.last().second
        return EditResult(newText, TextRange(firstStart, lastEnd + unit.length * spans.size))
    }

    fun unindent(text: String, selection: TextRange, unit: String = "    "): EditResult {
        val spans = lineSpans(text, selection)
        val removals = ArrayList<IntRange>()
        for ((start, end) in spans) {
            val whitespace = leadingWhitespaceLength(text.substring(start, end))
            if (whitespace > 0) {
                val removeStart = start
                val removeEnd = start + minOf(whitespace, unit.length)
                removals.add(removeStart until removeEnd)
            }
        }
        if (removals.isEmpty()) return EditResult(text, selection)
        val newText = buildString {
            var cursor = 0
            for (range in removals) {
                append(text, cursor, range.first)
                cursor = range.last + 1
            }
            append(text, cursor, text.length)
        }
        return EditResult(newText, TextRange(mapRemoved(selection.start, removals), mapRemoved(selection.end, removals)))
    }

    fun gotoLine(text: String, line: Int, column: Int = 1): TextRange {
        val lineIndex = (line - 1).coerceIn(0, lineCount(text) - 1)
        val start = lineStartAt(text, lineIndex)
        val end = lineEnd(text, start)
        val offset = (start + (column - 1)).coerceIn(start, end)
        return TextRange(offset)
    }

    private fun addLineComments(
        text: String,
        selection: TextRange,
        prefix: String,
        lines: List<Pair<Int, Int>>,
    ): EditResult {
        val minIndent = lines.minOf { leadingWhitespaceLength(text.substring(it.first, it.second)) }
        val insertion = prefix + " "
        val points = lines.map { it.first + minIndent }
        val newText = buildString {
            var cursor = 0
            for (point in points) {
                append(text, cursor, point)
                append(insertion)
                cursor = point
            }
            append(text, cursor, text.length)
        }
        fun mapInserted(position: Int): Int =
            position + insertion.length * points.count { it <= position }
        return EditResult(newText, TextRange(mapInserted(selection.start), mapInserted(selection.end)))
    }

    private fun removeLineComments(
        text: String,
        selection: TextRange,
        prefix: String,
        lines: List<Pair<Int, Int>>,
    ): EditResult {
        val removals = ArrayList<IntRange>()
        for ((start, end) in lines) {
            val indent = leadingWhitespaceLength(text.substring(start, end))
            val removeStart = start + indent
            var removeLength = prefix.length
            if (removeStart + removeLength < end && text[removeStart + removeLength] == ' ') removeLength++
            removals.add(removeStart until removeStart + removeLength)
        }
        val newText = buildString {
            var cursor = 0
            for (range in removals) {
                append(text, cursor, range.first)
                cursor = range.last + 1
            }
            append(text, cursor, text.length)
        }
        return EditResult(newText, TextRange(mapRemoved(selection.start, removals), mapRemoved(selection.end, removals)))
    }

    private fun mapRemoved(position: Int, removals: List<IntRange>): Int {
        var result = position
        for (range in removals) {
            when {
                position <= range.first -> Unit
                position > range.last -> result -= range.last - range.first + 1
                else -> result -= position - range.first
            }
        }
        return result
    }

    private fun lineSpans(text: String, selection: TextRange): List<Pair<Int, Int>> {
        val firstStart = lineStart(text, selection.min)
        val lastCovered = if (selection.max > selection.min) selection.max - 1 else selection.min
        val lastEnd = lineEnd(text, lastCovered)
        val spans = ArrayList<Pair<Int, Int>>()
        var start = firstStart
        while (true) {
            val end = lineEnd(text, start)
            spans.add(start to end)
            if (end >= lastEnd || end >= text.length) break
            start = end + 1
        }
        return spans
    }

    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                lines.add(text.substring(start))
                break
            }
            lines.add(text.substring(start, newline))
            start = newline + 1
        }
        return lines
    }

    private fun offsetOf(lines: List<String>, line: Int, column: Int): Int {
        var offset = 0
        for (i in 0 until line) offset += lines[i].length + 1
        return offset + column.coerceIn(0, lines[line].length)
    }

    private fun lineStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun lineIndexOf(text: String, offset: Int): Int {
        var line = 0
        val limit = offset.coerceIn(0, text.length)
        for (i in 0 until limit) if (text[i] == '\n') line++
        return line
    }

    private fun lineStartAt(text: String, line: Int): Int {
        if (line <= 0) return 0
        var count = 0
        for (i in text.indices) {
            if (text[i] == '\n' && ++count == line) return i + 1
        }
        return text.length
    }

    private fun lineCount(text: String): Int {
        var count = 1
        for (char in text) if (char == '\n') count++
        return count
    }

    private fun leadingWhitespaceLength(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return i
    }
}
