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
        val separator = separatorAfter(text, end)
        val newText = text.substring(0, end) + separator + line + text.substring(end)
        return EditResult(newText, TextRange(end + separator.length + column))
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

    /** The identifier touching [offset], including the one that ends exactly at it. */
    fun wordAt(text: String, offset: Int): TextRange? {
        val position = offset.coerceIn(0, text.length)
        if (position > 0 && isIdentifierPart(text[position - 1])) {
            var start = position - 1
            while (start > 0 && isIdentifierPart(text[start - 1])) start--
            var end = position
            while (end < text.length && isIdentifierPart(text[end])) end++
            return TextRange(start, end)
        }
        if (position < text.length && isIdentifierPart(text[position])) {
            var start = position
            while (start > 0 && isIdentifierPart(text[start - 1])) start--
            var end = position + 1
            while (end < text.length && isIdentifierPart(text[end])) end++
            return TextRange(start, end)
        }
        return null
    }

    /** The identifier characters immediately before [offset]; empty when there are none. */
    fun identifierPrefix(text: String, offset: Int): IntRange {
        val position = offset.coerceIn(0, text.length)
        var start = position
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        return start until position
    }

    /** Every identifier in [text], in document order; [checkCancelled] throws to stop a long scan. */
    fun identifierRanges(text: String, checkCancelled: () -> Unit = {}): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var index = 0
        var nextCheck = CANCELLATION_CHECK_INTERVAL
        while (index < text.length) {
            if (index >= nextCheck) {
                checkCancelled()
                nextCheck = index + CANCELLATION_CHECK_INTERVAL
            }
            if (!isIdentifierStart(text[index])) {
                index++
                continue
            }
            val start = index
            while (index < text.length && isIdentifierPart(text[index])) index++
            ranges.add(start until index)
        }
        return ranges
    }

    /**
     * The next larger selection around [selection]: the word, string-literal contents, bracket
     * contents, the brackets themselves, the touched lines, then the whole text. `null` when the
     * selection already covers everything.
     */
    fun selectWord(text: String, selection: TextRange): TextRange? {
        val current = TextRange(
            selection.min.coerceIn(0, text.length),
            selection.max.coerceIn(0, text.length),
        )
        for (candidate in selectionLadder(text, current)) {
            val larger = candidate.min <= current.min && candidate.max >= current.max &&
                (candidate.min < current.min || candidate.max > current.max)
            if (larger) return candidate
        }
        return null
    }

    /** Upper-cases [selection] (or the word at the caret) when it has a lower-case letter, else lower-cases it. */
    fun toggleCase(text: String, selection: TextRange): EditResult {
        val target = if (selection.min == selection.max) {
            wordAt(text, selection.min) ?: return EditResult(text, selection)
        } else {
            selection
        }
        val start = target.min
        val end = target.max
        val selected = text.substring(start, end)
        val replacement = if (selected.any { it.isLowerCase() }) selected.uppercase() else selected.lowercase()
        val newText = text.substring(0, start) + replacement + text.substring(end)
        return EditResult(newText, TextRange(start, start + replacement.length))
    }

    /**
     * Joins the next line, or every line the selection touches, into the first touched line.
     * The joined line loses its indentation and one space separates the parts unless it starts
     * with a closing bracket, a dot or a comma. The caret lands on the first join point.
     */
    fun joinLines(text: String, selection: TextRange): EditResult {
        val lines = splitLines(text)
        val firstLine = lineIndexOf(text, selection.min)
        val collapsed = selection.min == selection.max
        val lastCovered = if (collapsed) selection.min else selection.max - 1
        val lastLine = lineIndexOf(text, lastCovered)
        val joinThrough = if (collapsed || lastLine == firstLine) firstLine + 1 else lastLine
        if (joinThrough <= firstLine || joinThrough > lines.lastIndex) return EditResult(text, selection)

        val merged = StringBuilder(lines[firstLine].trimEnd())
        var joinPoint = -1
        for (line in firstLine + 1..joinThrough) {
            val content = lines[line].trimStart()
            // The last joined line keeps the carriage return that terminates the merged line.
            val trimmed = if (line == joinThrough && content.endsWith('\r')) {
                content.trimEnd() + '\r'
            } else {
                content.trimEnd()
            }
            val separator = if (trimmed.isEmpty() || trimmed == "\r" || trimmed[0] in NO_SPACE_BEFORE) "" else " "
            if (joinPoint < 0) joinPoint = merged.length + separator.length
            merged.append(separator).append(trimmed)
        }
        val newLines = ArrayList<String>(lines.size - (joinThrough - firstLine))
        newLines.addAll(lines.subList(0, firstLine))
        newLines.add(merged.toString())
        newLines.addAll(lines.subList(joinThrough + 1, lines.size))
        return EditResult(newLines.joinToString("\n"), TextRange(offsetOf(newLines, firstLine, joinPoint)))
    }

    /** Inserts an empty line with the current line's indentation after (or before) it. */
    fun startNewLine(text: String, selection: TextRange, before: Boolean): EditResult {
        val caret = selection.min
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        val indent = text.substring(start, start + leadingWhitespaceLength(text.substring(start, end)))
        val separator = lineSeparator(text, end)
        return if (before) {
            val newText = text.substring(0, start) + indent + separator + text.substring(start)
            EditResult(newText, TextRange(start + indent.length))
        } else {
            val carriageReturn = end < text.length && text[end] == '\n' && end > start && text[end - 1] == '\r'
            val contentEnd = if (carriageReturn) end - 1 else end
            val newText = text.substring(0, contentEnd) + separator + indent + text.substring(contentEnd)
            EditResult(newText, TextRange(contentEnd + separator.length + indent.length))
        }
    }

    /** The bracket matching the one at or just before [caret], ignoring strings and comments. */
    fun matchBrace(text: String, caret: Int): TextRange? = matchBrace(text, caret, CodeScan(text))

    /** [matchBrace] with a [scan] the caller shares across the actions of one press. */
    internal fun matchBrace(text: String, caret: Int, scan: CodeScan): TextRange? {
        val position = caret.coerceIn(0, text.length)
        val index = when {
            position < text.length && scan.isCode(position) && text[position] in BRACKET_OPENING + BRACKET_CLOSING -> position
            position > 0 && scan.isCode(position - 1) && text[position - 1] in BRACKET_OPENING + BRACKET_CLOSING -> position - 1
            else -> return null
        }
        return scan.match(index)?.let { TextRange(it) }
    }

    /** The `{` that encloses [caret], or `null` outside any block. */
    fun codeBlockStart(text: String, caret: Int): TextRange? = codeBlockStart(text, caret, CodeScan(text))

    /** [codeBlockStart] with a [scan] the caller shares across the actions of one press. */
    internal fun codeBlockStart(text: String, caret: Int, scan: CodeScan): TextRange? {
        val position = caret.coerceIn(0, text.length)
        var depth = 0
        for (index in position - 1 downTo 0) {
            if (!scan.isCode(index)) continue
            when (text[index]) {
                '}' -> depth++
                '{' -> if (depth == 0) return TextRange(index) else depth--
            }
        }
        return null
    }

    /** The `}` that closes the block holding [caret], or `null` outside any block. */
    fun codeBlockEnd(text: String, caret: Int): TextRange? = codeBlockEnd(text, caret, CodeScan(text))

    /** [codeBlockEnd] with a [scan] the caller shares across the actions of one press. */
    internal fun codeBlockEnd(text: String, caret: Int, scan: CodeScan): TextRange? {
        if (codeBlockStart(text, caret, scan) == null) return null
        val position = caret.coerceIn(0, text.length)
        val startsOnBrace = position < text.length && scan.isCode(position) && text[position] == '{'
        val from = if (startsOnBrace) position + 1 else position
        var depth = if (startsOnBrace) 1 else 0
        for (index in from until text.length) {
            if (!scan.isCode(index)) continue
            when (text[index]) {
                '{' -> depth++
                '}' -> if (depth == 0) return TextRange(index) else depth--
            }
        }
        return null
    }

    /** The whole line at [caret], including its newline when one follows. */
    fun wholeLine(text: String, caret: Int): TextRange {
        val start = lineStart(text, caret)
        var end = lineEnd(text, caret)
        if (end < text.length) end++
        return TextRange(start, end)
    }

    /** The start of the previous or next line in [declarationLines], or `null` when there is none. */
    fun declarationTarget(text: String, caret: Int, declarationLines: List<Int>, up: Boolean): TextRange? {
        val line = lineIndexOf(text, caret)
        val target = if (up) {
            declarationLines.filter { it < line }.maxOrNull()
        } else {
            declarationLines.filter { it > line }.minOrNull()
        } ?: return null
        return TextRange(lineStartAt(text, target))
    }

    /** Every occurrence of [word] that is not part of a larger identifier. */
    fun wholeWordRanges(text: String, word: String): List<IntRange> {
        if (word.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + word.length)
            val whole = (before == null || !isIdentifierPart(before)) &&
                (after == null || !isIdentifierPart(after))
            if (whole) ranges.add(index until index + word.length)
            index = text.indexOf(word, index + 1)
        }
        return ranges
    }

    /**
     * Words starting with [prefix], nearest first: occurrences before the caret, then after it,
     * then the words of [otherTexts]. The occurrence being replaced is left out.
     */
    fun hippieCandidates(
        text: String,
        prefix: String,
        prefixStart: Int,
        caret: Int,
        otherTexts: List<String> = emptyList(),
    ): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val before = ArrayList<Pair<Int, String>>()
        val after = ArrayList<Pair<Int, String>>()
        for (range in identifierRanges(text)) {
            if (range.first <= prefixStart && range.last + 1 >= caret) continue
            val word = text.substring(range.first, range.last + 1)
            if (word == prefix || !word.startsWith(prefix)) continue
            if (range.last + 1 <= prefixStart) before.add((prefixStart - range.last) to word)
            else if (range.first >= caret) after.add((range.first - caret) to word)
        }
        val ordered = buildList {
            addAll(before.sortedBy { it.first }.map { it.second })
            addAll(after.sortedBy { it.first }.map { it.second })
            for (other in otherTexts) {
                addAll(identifierRanges(other).map { other.substring(it.first, it.last + 1) })
            }
        }
        return ordered.filter { it != prefix && it.startsWith(prefix) }.distinct()
    }

    private fun selectionLadder(text: String, selection: TextRange): List<TextRange> {
        val scan = CodeScan(text)
        val ladder = ArrayList<TextRange>(6)
        val word = wordAt(text, selection.min)
        if (word != null && word.min <= selection.min && word.max >= selection.max) ladder.add(word)
        stringLiteralAt(scan, selection)?.let(ladder::add)
        val bracket = enclosingBracket(scan, selection)
        if (bracket != null) {
            ladder.add(TextRange(bracket.first + 1, bracket.second))
            ladder.add(TextRange(bracket.first, bracket.second + 1))
        }
        ladder.add(lineSpan(text, selection))
        ladder.add(TextRange(0, text.length))
        return ladder
    }

    private fun stringLiteralAt(scan: CodeScan, selection: TextRange): TextRange? {
        val range = scan.stringContents.firstOrNull {
            it.first <= selection.min && it.last + 1 >= selection.max
        } ?: return null
        return TextRange(range.first, range.last + 1)
    }

    private fun enclosingBracket(scan: CodeScan, selection: TextRange): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (pair in scan.bracketPairs()) {
            if (pair.first < selection.min && pair.second >= selection.max &&
                (best == null || pair.first > best.first)
            ) {
                best = pair
            }
        }
        return best
    }

    private fun lineSpan(text: String, selection: TextRange): TextRange {
        val start = lineStart(text, selection.min)
        val lastCovered = if (selection.max > selection.min) selection.max - 1 else selection.max
        return TextRange(start, lineEnd(text, lastCovered))
    }

    private fun isIdentifierStart(character: Char): Boolean =
        character.isLetter() || character == '_' || character == '$'

    private fun isIdentifierPart(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '$'

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

    /** The separator ending the line whose content ends at [lineEnd]. */
    private fun lineSeparator(text: String, lineEnd: Int): String = when {
        lineEnd < text.length && text[lineEnd] == '\n' && lineEnd > 0 && text[lineEnd - 1] == '\r' -> "\r\n"
        lineEnd < text.length && text[lineEnd] == '\n' -> "\n"
        // A final lone carriage return already supplies half of a CRLF.
        lineEnd > 0 && text[lineEnd - 1] == '\r' -> "\n"
        else -> documentLineSeparator(text)
    }

    /** The separator to insert before [offset] when the line ends there. */
    private fun separatorAfter(text: String, offset: Int): String = when {
        offset < text.length -> "\n"
        offset > 0 && text[offset - 1] == '\r' -> "\n"
        else -> documentLineSeparator(text)
    }

    private fun documentLineSeparator(text: String): String {
        val newline = text.indexOf('\n')
        return if (newline > 0 && text[newline - 1] == '\r') "\r\n" else "\n"
    }
}

private const val BRACKET_OPENING = "([{"
private const val BRACKET_CLOSING = ")]}"
private val NO_SPACE_BEFORE = charArrayOf(')', ']', '.', ',')
private const val CANCELLATION_CHECK_INTERVAL = 64 * 1024
private val BRACKET_PARTNER = mapOf(
    '(' to ')', ')' to '(',
    '[' to ']', ']' to '[',
    '{' to '}', '}' to '{',
)

/**
 * Marks the characters inside string literals and comments so bracket matching can skip them.
 * The scan is best effort: a line comment ends at the newline, and a normal string ends at its
 * quote or the newline.
 */
internal class CodeScan(private val text: String) {
    private val kinds = ByteArray(text.length)

    /** Content ranges of string and character literals, delimiters excluded. */
    val stringContents = ArrayList<IntRange>()

    private val brackets: List<Pair<Int, Int>> by lazy { computeBrackets() }

    init {
        scan()
    }

    fun isCode(index: Int): Boolean = kinds[index] == CODE

    /** Every matched bracket pair as (open, close), innermost included. */
    fun bracketPairs(): List<Pair<Int, Int>> = brackets

    /** The index of the bracket matching the one at [index], or `null` when it does not match. */
    fun match(index: Int): Int? {
        val bracket = text[index]
        val partner = BRACKET_PARTNER[bracket] ?: return null
        if (bracket in BRACKET_OPENING) {
            var depth = 0
            for (i in index + 1 until text.length) {
                if (!isCode(i)) continue
                val current = text[i]
                if (current == bracket) {
                    depth++
                } else if (current == partner) {
                    if (depth == 0) return i
                    depth--
                }
            }
        } else {
            var depth = 0
            for (i in index - 1 downTo 0) {
                if (!isCode(i)) continue
                val current = text[i]
                if (current == bracket) {
                    depth++
                } else if (current == partner) {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        return null
    }

    private fun scan() {
        var i = 0
        while (i < text.length) {
            val character = text[i]
            when {
                character == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') kinds[i++] = COMMENT
                }
                character == '/' && text.getOrNull(i + 1) == '*' -> {
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        if (text[i] != '\n') kinds[i] = COMMENT
                        i++
                    }
                    repeat(2) { if (i < text.length) kinds[i++] = COMMENT }
                }
                text.startsWith("\"\"\"", i) -> {
                    i += 3
                    val start = i
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        if (text[i] != '\n') kinds[i] = STRING
                        i++
                    }
                    if (i > start) stringContents.add(start until i)
                    if (i < text.length) i += 3
                }
                character == '"' || character == '\'' -> {
                    val quote = character
                    i++
                    val start = i
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            kinds[i++] = STRING
                            if (i < text.length && text[i] != '\n') kinds[i++] = STRING
                        } else {
                            kinds[i++] = STRING
                        }
                    }
                    if (i > start) stringContents.add(start until i)
                    if (i < text.length && text[i] == quote) i++
                }
                else -> i++
            }
        }
    }

    private fun computeBrackets(): List<Pair<Int, Int>> {
        val pairs = ArrayList<Pair<Int, Int>>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        for (index in text.indices) {
            if (!isCode(index)) continue
            val character = text[index]
            when {
                character in BRACKET_OPENING -> stack.addLast(character to index)
                character in BRACKET_CLOSING -> {
                    val top = stack.lastOrNull() ?: continue
                    if (BRACKET_PARTNER[top.first] == character) {
                        stack.removeLast()
                        pairs.add(top.second to index)
                    }
                }
            }
        }
        return pairs
    }

    private companion object {
        const val CODE: Byte = 0
        const val STRING: Byte = 1
        const val COMMENT: Byte = 2
    }
}
