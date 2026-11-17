package stride.app.editor

import androidx.compose.ui.text.TextRange
import stride.lang.TextEdit

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

    /**
     * Moves the statements at the caret (or the selected lines) past their sibling above or below.
     * A unit whose first or last line opens a `{` carries lines through its closing `}`, and the
     * comment and annotation lines glued above it travel with it; neither side is re-indented. A
     * unit that starts with the `}` of a block opened above it stays where it is.
     */
    fun moveStatement(text: String, selection: TextRange, up: Boolean): EditResult {
        val lines = splitLines(text)
        val scan = CodeScan(text)
        val firstSelected = lineIndexOf(text, selection.min)
        var lastLine = if (selection.max > selection.min) {
            lineIndexOf(text, selection.max - 1)
        } else {
            firstSelected
        }
        lastLine = maxOf(blockLineEnd(text, scan, lastLine), blockLineEnd(text, scan, firstSelected))
        val firstLine = attachedHeaderStart(lines, firstSelected)
        if (startsByClosingBlock(text, scan, firstSelected, firstLine)) return EditResult(text, selection)

        val neighbourFirst: Int
        val neighbourLast: Int
        if (up) {
            if (firstLine == 0) return EditResult(text, selection)
            neighbourLast = firstLine - 1
            neighbourFirst = previousUnitStart(text, lines, scan, neighbourLast, lastLine)
                ?: return EditResult(text, selection)
        } else {
            if (lastLine >= lines.lastIndex) return EditResult(text, selection)
            neighbourFirst = lastLine + 1
            neighbourLast = nextUnitEnd(text, lines, scan, neighbourFirst, firstLine)
                ?: return EditResult(text, selection)
        }

        val unitSize = lastLine - firstLine + 1
        val neighbourSize = neighbourLast - neighbourFirst + 1
        val blockStart = minOf(firstLine, neighbourFirst)
        val blockEnd = maxOf(lastLine, neighbourLast)
        val combined = if (up) {
            lines.subList(firstLine, lastLine + 1) + lines.subList(neighbourFirst, neighbourLast + 1)
        } else {
            lines.subList(neighbourFirst, neighbourLast + 1) + lines.subList(firstLine, lastLine + 1)
        }
        val newLines = lines.subList(0, blockStart) + combined + lines.subList(blockEnd + 1, lines.size)

        fun mapLine(line: Int): Int = when {
            line in firstLine..lastLine -> if (up) line - neighbourSize else line + neighbourSize
            line in neighbourFirst..neighbourLast -> if (up) line + unitSize else line - unitSize
            else -> line
        }
        val startLine = lineIndexOf(text, selection.start)
        val endLine = lineIndexOf(text, selection.end)
        val endsAtNextLineStart =
            endLine == lastLine + 1 && selection.end == lineStartAt(text, endLine)
        val newText = newLines.joinToString("\n")
        val newStart = offsetOf(newLines, mapLine(startLine), selection.start - lineStartAt(text, startLine))
        val newEnd = if (endsAtNextLineStart) {
            offsetOf(newLines, mapLine(lastLine), newLines[mapLine(lastLine)].length) + 1
        } else {
            offsetOf(newLines, mapLine(endLine), selection.end - lineStartAt(text, endLine))
        }
        return EditResult(
            newText,
            TextRange(newStart.coerceIn(0, newText.length), newEnd.coerceIn(0, newText.length)),
        )
    }

    /**
     * Finishes the caret's line: closes the `(` and `[` left open on it, opens (or closes) the
     * block the line introduces, adds a semicolon when [semicolons] is set, then puts the caret
     * on a new line below. This works on the line's text alone and never parses the file.
     */
    fun completeStatement(
        text: String,
        selection: TextRange,
        semicolons: Boolean,
        unit: String = "    ",
    ): EditResult {
        val caret = selection.max.coerceIn(0, text.length)
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        if (text.substring(start, end).isBlank()) return EditResult(text, TextRange(caret))

        val scan = CodeScan(text)
        val lineCodeEnd = codeEndInLine(text, start, end, scan)
        val lineEndsWithBrace = text.substring(start, lineCodeEnd).endsWith("{")
        val closers = if (lineEndsWithBrace) "" else unclosedClosers(text, start, lineCodeEnd, scan)

        var current = text
        var codeEnd = lineCodeEnd
        var lineEndOffset = end
        if (closers.isNotEmpty()) {
            current = text.substring(0, lineCodeEnd) + closers + text.substring(lineCodeEnd)
            codeEnd = lineCodeEnd + closers.length
            lineEndOffset = end + closers.length
        }
        val lineScan = if (closers.isEmpty()) scan else CodeScan(current)
        val indent = text.substring(start, start + leadingWhitespaceLength(text.substring(start, end)))
        val innerIndent = indent + unit
        val separator = lineSeparator(current, lineEndOffset)
        val code = current.substring(start, codeEnd)
        val trimmed = code.trim()

        if (trimmed.endsWith("{")) {
            val insertion = if (lineScan.match(codeEnd - 1) != null) {
                separator + innerIndent
            } else {
                separator + innerIndent + separator + indent + "}"
            }
            // A comment after the `{` stays on the line the block opens.
            val insertAt = if (lineEndOffset > 0 && current[lineEndOffset - 1] == '\r') {
                lineEndOffset - 1
            } else {
                lineEndOffset
            }
            return EditResult(
                current.substring(0, insertAt) + insertion + current.substring(insertAt),
                TextRange(insertAt + separator.length + innerIndent.length),
            )
        }
        if (startsWithBlockKeyword(trimmed) && !trimmed.startsWith("}") &&
            !isExpressionBody(current, lineScan, start, codeEnd, indent.length)
        ) {
            val insertion = " {" + separator + innerIndent + separator + indent + "}"
            return EditResult(
                current.substring(0, codeEnd) + insertion + current.substring(codeEnd),
                TextRange(codeEnd + 2 + separator.length + innerIndent.length),
            )
        }

        val last = trimmed.lastOrNull()
        val appendSemicolon = semicolons && last != null && last !in ";{},"
        val source = if (appendSemicolon) {
            current.substring(0, codeEnd) + ";" + current.substring(codeEnd)
        } else {
            current
        }
        val insertionAt = lineEndOffset + if (appendSemicolon) 1 else 0
        val contentEnd = if (insertionAt > 0 && source[insertionAt - 1] == '\r') insertionAt - 1 else insertionAt
        val newline = lineSeparator(source, insertionAt)
        return EditResult(
            source.substring(0, contentEnd) + newline + indent + source.substring(contentEnd),
            TextRange(contentEnd + newline.length + indent.length),
        )
    }

    /**
     * Removes the innermost control block around the caret and keeps its body, with the body
     * dedented one unit, as `null` when the caret is not inside a control block. Chained `else`,
     * `catch`, `finally` blocks and a `do`'s trailing `while` go with it.
     */
    fun unwrap(text: String, selection: TextRange): EditResult? {
        val scan = CodeScan(text)
        val caret = selection.start.coerceIn(0, text.length)
        val target = scan.bracketPairs()
            .asSequence()
            .filter { text[it.first] == '{' && it.first <= caret && caret <= it.second }
            .sortedByDescending { it.first }
            .mapNotNull { pair ->
                controlKeyword(text, pair.first)?.let { Block(pair.first, pair.second, it) }
            }
            .firstOrNull() ?: return null

        var first = target
        while (true) {
            val headerStart = lineStart(text, first.open) + leadingWhitespaceLength(
                text.substring(lineStart(text, first.open), first.open),
            )
            if (headerStart >= text.length || text[headerStart] != '}') break
            val previousOpen = scan.match(headerStart) ?: break
            if (text.getOrNull(previousOpen) != '{') break
            val keyword = controlKeyword(text, previousOpen) ?: break
            if (keyword !in familyOf(target.keyword)) break
            first = Block(previousOpen, headerStart, keyword)
        }
        var last = target
        while (true) {
            val next = nextCodeIndex(text, scan, last.close + 1) ?: break
            val word = wordStartingAt(text, next)
            if (word !in followersOf(target.keyword)) break
            val bodyOpen = nextCodeBrace(text, scan, next + word.length) ?: break
            val bodyClose = scan.match(bodyOpen) ?: break
            last = Block(bodyOpen, bodyClose, word)
        }
        if (target.keyword == "do") {
            val next = nextCodeIndex(text, scan, last.close + 1)
            if (next != null && wordStartingAt(text, next) == "while") {
                val conditionOpen = nextCodeParen(text, scan, next + "while".length)
                val conditionClose = conditionOpen?.let(scan::match)
                if (conditionClose != null) last = last.copy(close = conditionClose)
            }
        }

        val chainFirstLine = lineStart(text, first.open)
        val headerStart = chainFirstLine + leadingWhitespaceLength(text.substring(chainFirstLine, first.open))
        val headKeywordStart = if (text.getOrNull(headerStart) == '}') {
            var i = headerStart + 1
            while (i < first.open && (text[i] == ' ' || text[i] == '\t')) i++
            i
        } else {
            headerStart
        }
        var chainStart = if (headKeywordStart == headerStart) chainFirstLine else headKeywordStart
        val indent = text.substring(chainFirstLine, headerStart)
        var closingEnd = last.close
        var afterClose = closingEnd + 1
        while (afterClose < text.length && (text[afterClose] == ' ' || text[afterClose] == '\t')) afterClose++
        if (text.getOrNull(afterClose) == ';') closingEnd = afterClose
        val tailStart = closingEnd + 1
        val openLine = lineIndexOf(text, target.open)
        val closeLine = lineIndexOf(text, target.close)
        val openIsLineEnd = !hasCodeBetween(text, scan, target.open + 1, lineEnd(text, target.open))
        val closeIsLineStart = !hasCodeBetween(text, scan, lineStart(text, target.close), target.close)
        val bodyLines = if (openIsLineEnd && closeIsLineStart && closeLine > openLine + 1) {
            (openLine + 1 until closeLine).map { line ->
                val begin = lineStartAt(text, line)
                dedentOneUnit(text.substring(begin, lineEnd(text, begin)))
            }
        } else {
            emptyList()
        }
        val inlineBody = if (openIsLineEnd && closeIsLineStart) "" else text.substring(target.open + 1, target.close).trim()
        val replacement = when {
            openIsLineEnd && closeIsLineStart -> bodyLines.joinToString("\n")
            inlineBody.isEmpty() -> ""
            chainStart == chainFirstLine -> indent + inlineBody
            else -> inlineBody
        }
        val tailEnd = lineEnd(text, tailStart)
        val tail = text.substring(tailStart, tailEnd)
        val removalEnd = when {
            !tail.isBlank() || replacement.isNotEmpty() -> tailEnd
            tailEnd < text.length && text[tailEnd] == '\n' -> tailEnd + 1
            else -> {
                if (chainStart > 0) {
                    chainStart--
                    if (chainStart > 0 && text[chainStart - 1] == '\r') chainStart--
                }
                tailEnd
            }
        }
        val keptTail = if (tail.isBlank() && replacement.isEmpty()) "" else tail
        val appendFrom = if (tail.isBlank()) removalEnd else tailEnd
        val newText = text.substring(0, chainStart) + replacement + keptTail + text.substring(appendFrom)

        val caretLine = lineIndexOf(text, caret)
        val bodyFirstLine = openLine + 1
        val mapped = when {
            replacement.isEmpty() -> chainStart
            bodyLines.isEmpty() -> {
                val inner = text.substring(target.open + 1, target.close)
                val bodyStart = target.open + 1 + leadingWhitespaceLength(inner)
                chainStart + replacement.length - inlineBody.length +
                    (caret - bodyStart).coerceIn(0, inlineBody.length)
            }
            else -> {
                val index = caretLine - bodyFirstLine
                if (index in bodyLines.indices) {
                    val begin = lineStartAt(text, caretLine)
                    val column = caret - begin
                    val removed = leadingWhitespaceLength(text.substring(begin, lineEnd(text, begin))) -
                        leadingWhitespaceLength(bodyLines[index])
                    chainStart + offsetOf(bodyLines, index, 0) + (column - removed).coerceIn(0, bodyLines[index].length)
                } else {
                    chainStart + replacement.length
                }
            }
        }
        return EditResult(newText, TextRange(mapped.coerceIn(0, newText.length)))
    }

    /**
     * Re-indents [selection]'s lines, or the whole [text] when nothing is selected, and trims
     * trailing whitespace. Only indentation, trailing whitespace and runs of blank lines change;
     * line breaks and intra-line spacing are left alone.
     */
    fun reformat(text: String, selection: TextRange, braceLanguage: Boolean, unit: String = "    "): List<TextEdit> {
        val infos = lineInfos(text)
        if (infos.isEmpty()) return emptyList()
        val scan = CodeScan(text)
        val wholeFile = selection.min == selection.max
        val firstTarget = if (wholeFile) 0 else lineIndexOf(text, selection.min)
        val lastTarget = if (wholeFile) infos.lastIndex else lineIndexOf(text, selection.max - 1)
        val deletions = if (wholeFile) blankLineDeletions(text, infos) else emptySet()
        val edits = ArrayList<TextEdit>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        for (line in infos.indices) {
            val info = infos[line]
            val content = text.substring(info.start, info.end)
            val hasCarriageReturn = content.endsWith('\r')
            val body = if (hasCarriageReturn) content.dropLast(1) else content
            val level = lineLevel(text, scan, stack, info, body)
            if (line in firstTarget..lastTarget && line !in deletions) {
                val updated = reformattedLine(body, info, level, braceLanguage, unit)
                if (updated != body) {
                    edits.add(TextEdit(info.start, info.end, updated + if (hasCarriageReturn) "\r" else ""))
                }
            }
            updateBracketStack(text, scan, stack, info, level)
        }
        for (line in deletions) {
            val info = infos[line]
            when {
                info.end < text.length -> edits.add(TextEdit(info.start, info.end + 1, ""))
                // A last blank line without a newline has none to take with it.
                info.start < info.end -> edits.add(TextEdit(info.start, info.end, ""))
            }
        }
        return edits
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

    /** The last line of the block a line opens, or [line] when it opens none. */
    private fun blockLineEnd(text: String, scan: CodeScan, line: Int): Int {
        val open = firstCodeBrace(text, scan, line) ?: return line
        val close = scan.match(open) ?: return line
        return maxOf(line, lineIndexOf(text, close))
    }

    /** True when [line] starts with the `}` of a block opened above [unitFirst]. */
    private fun startsByClosingBlock(text: String, scan: CodeScan, line: Int, unitFirst: Int): Boolean {
        val begin = lineStartAt(text, line)
        val first = firstCodeIndex(text, scan, begin, lineEnd(text, begin))
        if (first < 0 || text[first] != '}') return false
        val open = scan.match(first) ?: return false
        return lineIndexOf(text, open) < unitFirst
    }

    private fun attachedHeaderStart(lines: List<String>, line: Int): Int {
        var start = line
        while (start > 0 && isHeaderLine(lines[start - 1])) start--
        return start
    }

    private fun isHeaderLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") || trimmed.startsWith("/*") ||
            trimmed.startsWith("*") || trimmed.startsWith("@")
    }

    /** The first line of the neighbour above [line], or `null` when moving into it is a no-op. */
    private fun previousUnitStart(
        text: String,
        lines: List<String>,
        scan: CodeScan,
        line: Int,
        unitLast: Int,
    ): Int? {
        if (lines[line].isBlank()) return line
        val open = firstCodeBrace(text, scan, line)
        if (open != null) {
            val close = scan.match(open)
            if (close != null && lineIndexOf(text, close) > unitLast) return null
        }
        val begin = lineStartAt(text, line)
        val first = firstCodeIndex(text, scan, begin, lineEnd(text, begin))
        if (first >= 0 && text[first] == '}') {
            val previousOpen = scan.match(first)
            if (previousOpen != null && lineIndexOf(text, previousOpen) < line) {
                return attachedHeaderStart(lines, lineIndexOf(text, previousOpen))
            }
        }
        return line
    }

    /** The last line of the neighbour below [line], or `null` when moving into it is a no-op. */
    private fun nextUnitEnd(
        text: String,
        lines: List<String>,
        scan: CodeScan,
        line: Int,
        unitFirst: Int,
    ): Int? {
        if (line > lines.lastIndex) return null
        if (lines[line].isBlank()) return line
        val begin = lineStartAt(text, line)
        val first = firstCodeIndex(text, scan, begin, lineEnd(text, begin))
        if (first >= 0 && text[first] == '}') {
            val open = scan.match(first)
            if (open != null && lineIndexOf(text, open) < unitFirst) return null
        }
        val own = firstCodeBrace(text, scan, line)
        if (own != null) {
            val close = scan.match(own)
            if (close != null) return lineIndexOf(text, close)
        }
        var following = line
        while (following <= lines.lastIndex && isHeaderLine(lines[following])) following++
        if (following > line && following <= lines.lastIndex) {
            val blockOpen = firstCodeBrace(text, scan, following)
            val blockClose = blockOpen?.let(scan::match)
            if (blockClose != null) return lineIndexOf(text, blockClose)
        }
        return line
    }

    private fun firstCodeIndex(text: String, scan: CodeScan, start: Int, end: Int): Int {
        for (i in start until end) {
            if (scan.isCode(i) && !text[i].isWhitespace()) return i
        }
        return -1
    }

    private fun firstCodeBrace(text: String, scan: CodeScan, line: Int): Int? {
        val begin = lineStartAt(text, line)
        val end = lineEnd(text, begin)
        for (i in begin until end) {
            if (scan.isCode(i) && text[i] == '{') return i
        }
        return null
    }

    /** Where the line's code ends: just past its last character that [scan] counts as code. */
    private fun codeEndInLine(text: String, start: Int, end: Int, scan: CodeScan): Int {
        for (i in end - 1 downTo start) {
            if (scan.isCode(i) && !text[i].isWhitespace()) return i + 1
        }
        return start
    }

    private fun unclosedClosers(text: String, start: Int, end: Int, scan: CodeScan): String {
        val stack = ArrayList<Char>()
        for (i in start until end) {
            if (!scan.isCode(i)) continue
            when (val character = text[i]) {
                '(', '[', '{' -> stack.add(character)
                ')', ']', '}' -> if (stack.isNotEmpty() && BRACKET_PARTNER[stack.last()] == character) {
                    stack.removeAt(stack.lastIndex)
                }
            }
        }
        return buildString {
            for (index in stack.indices.reversed()) {
                when (stack[index]) {
                    '(' -> append(')')
                    '[' -> append(']')
                }
            }
        }
    }

    private fun startsWithBlockKeyword(code: String): Boolean {
        val head = code.substringBefore('(').substringBefore('=').substringBefore('{')
        val tokens = head.split(' ', '\t', ':', '<', '>', '@').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.first() in BLOCK_KEYWORDS) return true
        return tokens.any { it in DECLARATION_KEYWORDS }
    }

    private fun isExpressionBody(
        text: String,
        scan: CodeScan,
        start: Int,
        end: Int,
        indentLength: Int,
    ): Boolean {
        var depth = 0
        for (i in start + indentLength until end) {
            if (!scan.isCode(i)) continue
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '=' -> {
                    val previous = previousNonSpace(text, i)
                    if (depth == 0 && text.getOrNull(i + 1) != '=' &&
                        (previous == null || previous !in COMPOUND_ASSIGNMENT_CHARS)
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun previousNonSpace(text: String, index: Int): Char? {
        var i = index - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return text.getOrNull(i)
    }

    private fun controlKeyword(text: String, open: Int): String? {
        val header = text.substring(lineStart(text, open), open).trim()
        if (header.isEmpty()) return null
        val afterClosing = if (header.startsWith("}")) header.drop(1).trim() else header
        val word = afterClosing.takeWhile { it.isLetter() }
        return if (word in CONTROL_KEYWORDS) word else null
    }

    private fun familyOf(keyword: String): Set<String> = when (keyword) {
        "if", "else" -> IF_FAMILY
        "try", "catch", "finally" -> TRY_FAMILY
        else -> setOf(keyword)
    }

    private fun followersOf(keyword: String): Set<String> = when (keyword) {
        "if", "else" -> setOf("else")
        "try", "catch", "finally" -> setOf("catch", "finally")
        else -> emptySet()
    }

    private fun nextCodeIndex(text: String, scan: CodeScan, from: Int): Int? {
        var i = from.coerceIn(0, text.length)
        while (i < text.length) {
            if (scan.isCode(i) && !text[i].isWhitespace()) return i
            i++
        }
        return null
    }

    private fun wordStartingAt(text: String, index: Int): String {
        var end = index
        while (end < text.length && (text[end].isLetter() || text[end] == '_')) end++
        return text.substring(index, end)
    }

    private fun nextCodeBrace(text: String, scan: CodeScan, from: Int): Int? {
        var i = from.coerceIn(0, text.length)
        while (i < text.length) {
            if (scan.isCode(i)) {
                when (text[i]) {
                    '{' -> return i
                    ';', '}' -> return null
                }
            }
            i++
        }
        return null
    }

    private fun nextCodeParen(text: String, scan: CodeScan, from: Int): Int? {
        var i = from.coerceIn(0, text.length)
        while (i < text.length) {
            if (scan.isCode(i)) {
                when (text[i]) {
                    '(' -> return i
                    '{', ';', '}' -> return null
                }
            }
            i++
        }
        return null
    }

    private fun hasCodeBetween(text: String, scan: CodeScan, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (scan.isCode(i) && !text[i].isWhitespace()) return true
        }
        return false
    }

    private fun dedentOneUnit(line: String): String {
        val whitespace = leadingWhitespaceLength(line)
        if (whitespace == 0) return line
        val remove = if (line[0] == '\t') 1 else minOf(whitespace, INDENT_UNIT_LENGTH)
        return line.substring(remove)
    }

    private fun lineInfos(text: String): List<LineInfo> {
        val infos = ArrayList<LineInfo>()
        var inBlockComment = false
        var inRawString = false
        var lineStart = 0
        while (true) {
            val newline = text.indexOf('\n', lineStart)
            val end = if (newline < 0) text.length else newline
            val startsInBlockComment = inBlockComment
            val startsInString = inRawString
            var inString = false
            var i = lineStart
            while (i < end) {
                when {
                    inRawString && text.startsWith("\"\"\"", i) -> {
                        inRawString = false
                        i += 3
                    }
                    inRawString -> i++
                    inBlockComment && text.startsWith("*/", i) -> {
                        inBlockComment = false
                        i += 2
                    }
                    inBlockComment -> i++
                    inString && text[i] == '\\' -> i += 2
                    inString && text[i] == '"' -> {
                        inString = false
                        i++
                    }
                    inString -> i++
                    text.startsWith("//", i) -> i = end
                    text.startsWith("/*", i) -> {
                        inBlockComment = true
                        i += 2
                    }
                    text.startsWith("\"\"\"", i) -> {
                        inRawString = true
                        i += 3
                    }
                    text[i] == '"' -> {
                        inString = true
                        i++
                    }
                    text[i] == '\'' -> {
                        i++
                        while (i < end && text[i] != '\'') {
                            if (text[i] == '\\') i++
                            i++
                        }
                        if (i < end) i++
                    }
                    else -> i++
                }
            }
            infos.add(LineInfo(lineStart, end, startsInBlockComment, startsInString, inRawString || inString))
            if (newline < 0) break
            lineStart = newline + 1
        }
        return infos
    }

    private fun lineLevel(
        text: String,
        scan: CodeScan,
        stack: ArrayDeque<Pair<Char, Int>>,
        info: LineInfo,
        body: String,
    ): Int {
        var level = if (stack.isEmpty()) 0 else stack.last().second + 1
        if (body.isBlank() || info.startsInString) return level
        val first = firstCodeIndex(text, scan, info.start, info.end)
        if (first >= 0 && text[first] in BRACKET_CLOSING && stack.isNotEmpty() &&
            BRACKET_PARTNER[stack.last().first] == text[first]
        ) {
            level = stack.last().second
        }
        if (!info.startsInBlockComment && startsContinuation(body.trim())) level += 1
        return level
    }

    private fun updateBracketStack(
        text: String,
        scan: CodeScan,
        stack: ArrayDeque<Pair<Char, Int>>,
        info: LineInfo,
        level: Int,
    ) {
        for (i in info.start until info.end) {
            if (!scan.isCode(i)) continue
            when (val character = text[i]) {
                '(', '[', '{' -> stack.addLast(character to level)
                ')', ']', '}' -> if (stack.isNotEmpty() && BRACKET_PARTNER[stack.last().first] == character) {
                    stack.removeLast()
                }
            }
        }
    }

    private fun reformattedLine(
        body: String,
        info: LineInfo,
        level: Int,
        braceLanguage: Boolean,
        unit: String,
    ): String {
        // Whitespace inside a string literal is part of the value, so the line stays as it is.
        if (info.startsInString || info.endsInString) return body
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val indentLength = leadingWhitespaceLength(body)
        val existingIndent = body.substring(0, indentLength)
        val core = body.substring(indentLength).trimEnd(' ', '\t')
        val indent = when {
            !braceLanguage -> existingIndent
            info.startsInBlockComment && trimmed.startsWith("*") -> unit.repeat(level) + " "
            info.startsInBlockComment -> existingIndent
            else -> unit.repeat(level)
        }
        return indent + core
    }

    private fun startsContinuation(trimmed: String): Boolean = when {
        trimmed.startsWith("..") -> false
        trimmed.startsWith(".") -> true
        trimmed.startsWith("?.") -> true
        trimmed.startsWith("?:") -> true
        trimmed.startsWith("&&") -> true
        trimmed.startsWith("||") -> true
        else -> false
    }

    private fun blankLineDeletions(text: String, infos: List<LineInfo>): Set<Int> {
        val deletions = HashSet<Int>()
        var run = 0
        for (line in infos.indices) {
            val info = infos[line]
            val body = text.substring(info.start, info.end).trimEnd('\r')
            val blank = body.isBlank() && !info.startsInBlockComment && !info.startsInString
            if (blank) {
                run++
                if (run > 2) deletions.add(line)
            } else {
                run = 0
            }
        }
        return deletions
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
private const val INDENT_UNIT_LENGTH = 4
/** Characters that turn the `=` after them into a compound assignment or comparison. */
private val COMPOUND_ASSIGNMENT_CHARS = charArrayOf('=', '!', '+', '-', '*', '/', '%', '&', '|', '^')
private val BLOCK_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "try", "catch", "finally", "when", "switch",
    "synchronized", "fun", "class", "interface", "object", "init",
)
private val DECLARATION_KEYWORDS = setOf("class", "interface", "object", "fun")
private val CONTROL_KEYWORDS = setOf("if", "else", "for", "while", "do", "try", "catch", "finally")
private val IF_FAMILY = setOf("if", "else")
private val TRY_FAMILY = setOf("try", "catch", "finally")

/** A control block found by [EditActions.unwrap]; [keyword] is the word that opens it. */
private data class Block(val open: Int, val close: Int, val keyword: String)

/** What [EditActions.reformat] needs to know about a line before it rewrites it. */
private class LineInfo(
    val start: Int,
    val end: Int,
    val startsInBlockComment: Boolean,
    val startsInString: Boolean,
    val endsInString: Boolean,
)
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

    /** True when [index] is inside a line or block comment. */
    fun isComment(index: Int): Boolean = kinds[index] == COMMENT

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
