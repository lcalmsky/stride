package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The text and carets a multi-caret operation produces; the last caret is the primary one. */
internal data class CaretEdit(
    val text: String,
    val carets: List<TextRange>,
    val composition: TextRange? = null,
    val typing: Boolean = false,
)

internal enum class CaretMove { LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END }

/** The carets an occurrence command found and whether the whole-word flag is now on. */
internal data class OccurrenceResult(
    val carets: List<TextRange>,
    val wholeWords: Boolean,
    val found: Boolean,
)

/**
 * Pure operations over a text and its caret list. Carets are always given and returned in
 * addition order, so the last one is the primary caret the text field owns.
 */
internal object MultiCaret {

    fun normalize(carets: List<TextRange>): List<TextRange> {
        if (carets.size <= 1) return carets
        val ordered = carets.indices.sortedWith(compareBy({ carets[it].min }, { carets[it].max }, { it }))
        val groups = ArrayList<Group>()
        for (index in ordered) {
            val caret = carets[index]
            val last = groups.lastOrNull()
            if (last != null && last.merges(caret)) {
                last.min = minOf(last.min, caret.min)
                last.max = maxOf(last.max, caret.max)
                last.ends.add(caret.end)
                if (index >= last.survivor) last.survivor = index
            } else {
                groups.add(Group(caret.min, caret.max, hashSetOf(caret.end), index))
            }
        }
        // The survivor is the latest-added member, so its direction and order are kept.
        return groups.sortedBy { it.survivor }.map { group ->
            val survivor = carets[group.survivor]
            if (survivor.start > survivor.end) TextRange(group.max, group.min)
            else TextRange(group.min, group.max)
        }
    }

    private class Entry(val index: Int, val caret: TextRange, val primary: Boolean)

    private class Group(var min: Int, var max: Int, val ends: MutableSet<Int>, var survivor: Int) {
        /** Whether [caret] overlaps this group or shares its caret position. */
        fun merges(caret: TextRange): Boolean {
            if (caret.end in ends) return true
            if (min < max && !caret.collapsed && maxOf(min, caret.min) < minOf(max, caret.max)) return true
            if (caret.collapsed && min < caret.min && caret.min < max) return true
            return min == max && caret.min < min && min < caret.max
        }
    }

    /**
     * Replays the primary caret's change into every secondary caret; `null` when the change
     * cannot be modelled, in which case the caller drops the secondary carets.
     */
    fun replicate(
        oldText: String,
        oldPrimary: TextRange,
        oldComposition: TextRange?,
        newValue: TextFieldValue,
        secondaries: List<TextRange>,
    ): CaretEdit? {
        val newText = newValue.text
        if (newText == oldText) return null
        val lo = minOf(oldPrimary.min, oldComposition?.min ?: oldPrimary.min)
        val hi = maxOf(oldPrimary.max, oldComposition?.max ?: oldPrimary.max)
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        val rs = minOf(prefix, lo)
        var suffix = 0
        // Measured from the truncated prefix: the raw prefix can pass `lo`, and a suffix that
        // overlaps it would widen the change past the caret.
        val maxSuffix = minOf(oldText.length - rs, newText.length - rs)
        while (suffix < maxSuffix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        val suffixLength = minOf(suffix, oldText.length - hi)
        val re = oldText.length - suffixLength
        if (rs > re || rs > oldPrimary.min || re < oldPrimary.max) return null
        val inserted = newText.substring(rs, newText.length - suffixLength)
        val a = oldPrimary.min - rs
        val b = re - oldPrimary.max
        val newPrimary = clamp(newValue.selection, newText.length)

        val entries = ArrayList<Entry>(secondaries.size + 1)
        secondaries.forEachIndexed { index, caret -> entries.add(Entry(index, caret, false)) }
        entries.add(Entry(secondaries.size, oldPrimary, true))
        entries.sortWith(compareBy({ it.caret.min }, { it.caret.max }, { it.index }))

        val results = arrayOfNulls<TextRange>(secondaries.size + 1)
        val applied = ArrayList<TextRange>()
        var delta = 0
        var lastAppliedEnd = -1
        var lastAppliedResult: TextRange? = null
        var primaryShift = 0
        var primaryApplied = false
        for (entry in entries) {
            val caret = entry.caret
            // Clamped, not skipped: a caret left of the primary still repeats a shorter part of
            // the change instead of losing it.
            val rawStart = (caret.min - a).coerceIn(0, oldText.length)
            val rawEnd = (caret.max + b).coerceIn(0, oldText.length)
            if (!entry.primary && rs < re && rawStart < re && rs < rawEnd) {
                results[entry.index] = newPrimary
                continue
            }
            if (!entry.primary && rawStart < lastAppliedEnd) {
                results[entry.index] = lastAppliedResult ?: TextRange((caret.end + delta).coerceIn(0, newText.length))
                continue
            }
            results[entry.index] = TextRange(
                rawStart + delta + (newPrimary.min - rs),
                rawStart + delta + (newPrimary.max - rs),
            )
            applied.add(TextRange(rawStart, rawEnd))
            delta += inserted.length - (rawEnd - rawStart)
            lastAppliedEnd = rawEnd
            lastAppliedResult = results[entry.index]
            if (entry.primary) {
                primaryShift = delta - (inserted.length - (rawEnd - rawStart))
                primaryApplied = true
            }
        }
        if (!primaryApplied) return null

        // `applied` is recorded in ascending order and the edits never overlap, so one pass
        // rebuilds the document instead of one full copy per caret.
        val builder = StringBuilder(oldText.length)
        var cursor = 0
        for (range in applied) {
            builder.append(oldText, cursor, range.min)
            builder.append(inserted)
            cursor = range.max
        }
        builder.append(oldText, cursor, oldText.length)
        val result = builder.toString()
        val composition = newValue.composition?.let { comp ->
            TextRange(
                (comp.min + primaryShift).coerceIn(0, result.length),
                (comp.max + primaryShift).coerceIn(0, result.length),
            )
        }
        val typing = inserted.length == 1 && !inserted[0].isWhitespace() &&
            a == 0 && b == 0 && oldPrimary.collapsed && secondaries.all { it.collapsed } &&
            oldComposition == null && newValue.composition == null
        val carets = (0..secondaries.size).map { results[it] ?: newPrimary }
        return CaretEdit(result, normalize(carets.map { clamp(it, result.length) }), composition, typing)
    }

    fun move(
        text: String,
        carets: List<TextRange>,
        move: CaretMove,
        extend: Boolean,
        desiredColumns: List<Int>?,
    ): Pair<List<TextRange>, List<Int>?> {
        val vertical = move == CaretMove.UP || move == CaretMove.DOWN
        val moved = ArrayList<TextRange>(carets.size)
        val columns = if (vertical) ArrayList<Int>(carets.size) else null
        val starts = lineStarts(text)
        carets.forEachIndexed { index, caret ->
            val saved = desiredColumns?.getOrNull(index)
            when (move) {
                CaretMove.LEFT -> moved.add(horizontal(text, caret, extend, forward = false, word = false))
                CaretMove.RIGHT -> moved.add(horizontal(text, caret, extend, forward = true, word = false))
                CaretMove.WORD_LEFT -> moved.add(horizontal(text, caret, extend, forward = false, word = true))
                CaretMove.WORD_RIGHT -> moved.add(horizontal(text, caret, extend, forward = true, word = true))
                CaretMove.LINE_START -> {
                    val target = starts[lineIndexAt(starts, caret.end)]
                    moved.add(if (extend) TextRange(caret.start, target) else TextRange(target))
                }
                CaretMove.LINE_END -> {
                    val target = lineEnd(text, caret.end)
                    moved.add(if (extend) TextRange(caret.start, target) else TextRange(target))
                }
                CaretMove.UP, CaretMove.DOWN -> {
                    val up = move == CaretMove.UP
                    val anchor = if (extend) caret.start else caret.end
                    val line = lineIndexAt(starts, caret.end)
                    val baseStart = starts[line]
                    val baseEnd = lineEnd(text, baseStart)
                    val column = saved ?: codePointCount(text, baseStart, baseEnd, caret.end)
                    val targetLine = if (up) line - 1 else line + 1
                    val movedCaret = if (targetLine < 0 || targetLine >= starts.size) {
                        caret
                    } else {
                        val targetStart = starts[targetLine]
                        val targetEnd = lineEnd(text, targetStart)
                        val offset = offsetForColumn(text, targetStart, targetEnd, column)
                        if (extend) TextRange(anchor, offset) else TextRange(offset)
                    }
                    moved.add(movedCaret)
                    columns!!.add(column)
                }
            }
        }
        val normalized = normalize(moved)
        val keptColumns = if (columns != null && normalized.size == carets.size) columns else null
        return normalized to keptColumns
    }

    fun delete(text: String, carets: List<TextRange>, forward: Boolean, word: Boolean): CaretEdit {
        val deletions = carets.map { caret ->
            when {
                !caret.collapsed -> TextRange(caret.min, caret.max)
                word && forward -> TextRange(caret.end, wordEnd(text, caret.end))
                word -> TextRange(wordStart(text, caret.end), caret.end)
                forward -> TextRange(caret.end, nextCodePoint(text, caret.end))
                else -> TextRange(previousCodePoint(text, caret.end), caret.end)
            }
        }
        val merged = mergeRanges(deletions)
        val newText = removeRanges(text, merged)
        val mapped = carets.map { TextRange(mapRemoved(it.end, merged).coerceIn(0, newText.length)) }
        return CaretEdit(newText, normalize(mapped))
    }

    fun paste(text: String, carets: List<TextRange>, clip: String): CaretEdit {
        val lines = clipLines(clip)
        val distributed = carets.size >= 2 && lines.size == carets.size
        val order = carets.indices.sortedBy { carets[it].min }
        val inserted = arrayOfNulls<String>(carets.size)
        for ((position, index) in order.withIndex()) {
            inserted[index] = if (distributed) lines[position] else clip
        }
        val newPositions = IntArray(carets.size)
        var delta = 0
        for (index in order) {
            val caret = carets[index]
            val value = inserted[index] ?: clip
            newPositions[index] = caret.min + delta + value.length
            delta += value.length - (caret.max - caret.min)
        }
        val builder = StringBuilder(text.length)
        var cursor = 0
        for (index in order) {
            val caret = carets[index]
            if (caret.min < cursor) continue
            builder.append(text, cursor, caret.min)
            builder.append(inserted[index] ?: clip)
            cursor = caret.max
        }
        builder.append(text, cursor, text.length)
        val newText = builder.toString()
        val mapped = carets.indices.map { TextRange(newPositions[it].coerceIn(0, newText.length)) }
        return CaretEdit(newText, normalize(mapped))
    }

    /** The text to copy and, for a cut, the document ranges it came from. */
    fun copyText(text: String, carets: List<TextRange>): Pair<String, List<IntRange>> {
        val selected = carets.filter { !it.collapsed }.sortedBy { it.min }
        if (selected.isNotEmpty()) {
            val ranges = selected.map { it.min..(it.max - 1) }
            return ranges.joinToString("\n") { text.substring(it.first, it.last + 1) } to ranges
        }
        val starts = lineStarts(text)
        val seen = HashSet<Int>()
        val ranges = ArrayList<IntRange>()
        for (caret in carets.sortedBy { it.min }) {
            val start = starts[lineIndexAt(starts, caret.min)]
            var end = lineEnd(text, start)
            if (end < text.length) end++
            if (seen.add(start)) ranges.add(start until end)
        }
        return ranges.joinToString("") { text.substring(it.first, it.last + 1) } to ranges
    }

    fun deleteRanges(text: String, carets: List<TextRange>, ranges: List<IntRange>): CaretEdit {
        val merged = mergeRanges(ranges.map { TextRange(it.first, it.last + 1) })
        val newText = removeRanges(text, merged)
        val mapped = carets.map { TextRange(mapRemoved(it.end, merged).coerceIn(0, newText.length)) }
        return CaretEdit(newText, normalize(mapped))
    }

    private fun removeRanges(text: String, ranges: List<TextRange>): String {
        if (ranges.isEmpty()) return text
        val builder = StringBuilder(text.length - ranges.sumOf { it.max - it.min })
        var cursor = 0
        for (range in ranges) {
            builder.append(text, cursor, range.min)
            cursor = range.max
        }
        builder.append(text, cursor, text.length)
        return builder.toString()
    }

    fun selectNextOccurrence(text: String, carets: List<TextRange>, wholeWords: Boolean): OccurrenceResult {
        if (carets.isEmpty()) return OccurrenceResult(carets, wholeWords, false)
        val selected = carets.filter { !it.collapsed }
        if (selected.isEmpty()) {
            var found = false
            val words = carets.map { caret ->
                EditActions.wordAt(text, caret.end)?.also { found = true } ?: caret
            }
            return OccurrenceResult(words, true, found)
        }
        val primary = carets.last()
        val source = if (!primary.collapsed) primary else selected.last()
        val word = text.substring(source.min, source.max)
        val match = findNext(text, word, primary.end, carets, wholeWords)
            ?: return OccurrenceResult(carets, wholeWords, false)
        val caret = if (primary.start <= primary.end) {
            TextRange(match.first, match.last + 1)
        } else {
            TextRange(match.last + 1, match.first)
        }
        return OccurrenceResult(carets + caret, wholeWords, true)
    }

    fun selectAllOccurrences(text: String, carets: List<TextRange>, wholeWords: Boolean): OccurrenceResult {
        if (carets.isEmpty()) return OccurrenceResult(carets, wholeWords, false)
        val primary = carets.last()
        val word: String
        val whole: Boolean
        if (primary.collapsed) {
            val at = EditActions.wordAt(text, primary.end) ?: return OccurrenceResult(carets, wholeWords, false)
            word = text.substring(at.min, at.max)
            whole = true
        } else {
            word = text.substring(primary.min, primary.max)
            whole = wholeWords
        }
        if (word.isEmpty()) return OccurrenceResult(carets, wholeWords, false)
        val matches = ArrayList<IntRange>()
        var index = text.indexOf(word)
        while (index >= 0) {
            val range = index until index + word.length
            if (matchesWord(text, range, whole)) matches.add(range)
            index = text.indexOf(word, index + 1)
        }
        if (matches.isEmpty()) return OccurrenceResult(carets, wholeWords, false)
        // A collapsed caret that `wordAt` read as the word sits at either boundary; the end one
        // is not an overlap by the strict test, so it is accepted here exclusively.
        val primaryMatch = matches.firstOrNull { match ->
            if (primary.collapsed) {
                match.first <= primary.end && primary.end <= match.last + 1
            } else {
                overlaps(match, primary)
            }
        } ?: matches.first()
        val ordered = matches.filter { it != primaryMatch }.map { directed(it, primary) } +
            directed(primaryMatch, primary)
        return OccurrenceResult(ordered, whole, true)
    }

    fun unselectOccurrence(carets: List<TextRange>): List<TextRange> = when {
        carets.size > 1 -> carets.dropLast(1)
        carets.isEmpty() -> carets
        else -> listOf(TextRange(carets.last().end))
    }

    fun cloneCaret(text: String, carets: List<TextRange>, above: Boolean, column: Int?): List<TextRange> {
        if (carets.isEmpty()) return carets
        val extreme = (if (above) carets.minByOrNull { it.end } else carets.maxByOrNull { it.end })
            ?: return carets
        val starts = lineStarts(text)
        val line = lineIndexAt(starts, extreme.end)
        val targetLine = if (above) line - 1 else line + 1
        if (targetLine < 0 || targetLine >= starts.size) return carets
        val start = starts[targetLine]
        val end = lineEnd(text, start)
        val targetColumn = column ?: run {
            val extremeStart = starts[line]
            codePointCount(text, extremeStart, lineEnd(text, extremeStart), extreme.end)
        }
        val offset = offsetForColumn(text, start, end, targetColumn)
        if (carets.any { it.end == offset }) return carets
        return carets + TextRange(offset)
    }

    fun block(text: String, anchorLine: Int, anchorCol: Int, leadLine: Int, leadCol: Int): List<TextRange> {
        val starts = lineStarts(text)
        val lastLine = starts.size - 1
        val from = anchorLine.coerceIn(0, lastLine)
        val to = leadLine.coerceIn(0, lastLine)
        val step = if (to >= from) 1 else -1
        val lowCol = minOf(anchorCol, leadCol)
        val highCol = maxOf(anchorCol, leadCol)
        val carets = ArrayList<TextRange>()
        var line = from
        while (true) {
            val start = starts[line]
            val end = lineEnd(text, start)
            val low = offsetForColumn(text, start, end, lowCol)
            val high = offsetForColumn(text, start, end, highCol)
            carets.add(if (leadCol < anchorCol) TextRange(high, low) else TextRange(low, high))
            if (line == to) break
            line += step
        }
        return carets
    }

    fun toggleCaret(text: String, carets: List<TextRange>, offset: Int): List<TextRange> {
        val position = offset.coerceIn(0, text.length)
        val hit = carets.indexOfFirst { caret ->
            if (caret.collapsed) {
                caret.end == position
            } else {
                caret.min <= position && position <= caret.max
            }
        }
        return when {
            hit < 0 -> carets + TextRange(position)
            carets.size == 1 -> carets
            else -> carets.filterIndexed { index, _ -> index != hit }
        }
    }

    /**
     * Runs [fn] once per caret from the bottom up. A line-based run touches each group of carets
     * whose lines overlap once, through the group's latest-added caret.
     */
    fun perCaret(
        text: String,
        carets: List<TextRange>,
        lineBased: Boolean,
        fn: (String, TextRange) -> EditResult,
    ): CaretEdit {
        if (carets.isEmpty()) return CaretEdit(text, carets)
        val starts = lineStarts(text)
        val groups = if (lineBased) groupByLines(starts, carets) else carets.indices.map { listOf(it) }
        val current = carets.toMutableList()
        var currentText = text
        var lastApplied: TextRange? = null
        val ordered = groups.sortedByDescending { group ->
            group.maxOf { lineIndexAt(starts, carets[it].min) }
        }
        for (group in ordered) {
            val representative = group.max()
            val result = fn(currentText, current[representative])
            if (result.text == currentText) continue
            val change = computeTextChange(currentText, result.text)
            val region = TextRange(change.offset, change.offset + change.oldLength)
            // Groups run bottom-up, so only the previous region can still be in the way.
            if (lastApplied != null && maxOf(lastApplied.min, region.min) < minOf(lastApplied.max, region.max)) {
                continue
            }
            val newSelection = clamp(result.selection, result.text.length)
            val delta = change.newText.length - change.oldLength
            fun mapped(offset: Int): Int = when {
                offset >= region.max -> offset + delta
                offset > region.min -> newSelection.min
                else -> offset
            }
            for (index in current.indices) {
                if (index == representative) continue
                val caret = current[index]
                current[index] = TextRange(mapped(caret.start), mapped(caret.end))
            }
            current[representative] = newSelection
            lastApplied = region
            currentText = result.text
        }
        return CaretEdit(currentText, normalize(current))
    }

    /** The line a [column] lands on in [start]..[end], counting a code point as one column. */
    fun offsetForColumn(text: String, start: Int, end: Int, column: Int): Int {
        var index = start
        var count = 0
        while (index < end && count < column) {
            index += Character.charCount(text.codePointAt(index))
            count++
        }
        return index.coerceAtMost(end)
    }

    fun columnAt(text: String, offset: Int): Int {
        val start = lineStart(text, offset)
        return codePointCount(text, start, lineEnd(text, start), offset)
    }

    fun lineIndexOf(text: String, offset: Int): Int = lineIndexAt(lineStarts(text), offset)

    /** The offset every line starts at; one scan per operation, then binary searches. */
    fun lineStarts(text: String): IntArray {
        var count = 1
        for (character in text) if (character == '\n') count++
        val starts = IntArray(count)
        var line = 1
        for (index in text.indices) {
            if (text[index] == '\n') starts[line++] = index + 1
        }
        return starts
    }

    /** The line holding [offset], found by binary search over [starts]. */
    fun lineIndexAt(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    fun lineCountOf(text: String): Int {
        var count = 1
        for (character in text) if (character == '\n') count++
        return count
    }

    private fun horizontal(text: String, caret: TextRange, extend: Boolean, forward: Boolean, word: Boolean): TextRange {
        if (!extend && !caret.collapsed) return TextRange(if (forward) caret.max else caret.min)
        val target = when {
            word && forward -> wordEnd(text, caret.end)
            word -> wordStart(text, caret.end)
            forward -> nextCodePoint(text, caret.end)
            else -> previousCodePoint(text, caret.end)
        }
        return if (extend) TextRange(caret.start, target) else TextRange(target)
    }

    private fun findNext(
        text: String,
        word: String,
        after: Int,
        carets: List<TextRange>,
        wholeWords: Boolean,
    ): IntRange? {
        if (word.isEmpty()) return null
        val start = after.coerceIn(0, text.length)
        var index = text.indexOf(word, start)
        while (index >= 0) {
            val range = index until index + word.length
            if (matchesWord(text, range, wholeWords) && carets.none { overlaps(range, it) }) return range
            index = text.indexOf(word, index + 1)
        }
        index = text.indexOf(word)
        while (index >= 0 && index < start) {
            val range = index until index + word.length
            if (matchesWord(text, range, wholeWords) && carets.none { overlaps(range, it) }) return range
            index = text.indexOf(word, index + 1)
        }
        return null
    }

    private fun matchesWord(text: String, range: IntRange, wholeWords: Boolean): Boolean {
        if (!wholeWords) return true
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        return (before == null || !EditActions.isIdentifierPart(before)) &&
            (after == null || !EditActions.isIdentifierPart(after))
    }

    private fun overlaps(range: IntRange, caret: TextRange): Boolean =
        if (caret.collapsed) {
            range.first <= caret.min && caret.min < range.last + 1
        } else {
            maxOf(range.first, caret.min) < minOf(range.last + 1, caret.max)
        }

    private fun directed(range: IntRange, primary: TextRange): TextRange =
        if (primary.start <= primary.end) {
            TextRange(range.first, range.last + 1)
        } else {
            TextRange(range.last + 1, range.first)
        }

    private fun groupByLines(starts: IntArray, carets: List<TextRange>): List<List<Int>> {
        val groups = ArrayList<MutableList<Int>>()
        var groupLastLine = -1
        for (index in carets.indices.sortedBy { carets[it].min }) {
            val caret = carets[index]
            val first = lineIndexAt(starts, caret.min)
            val lastOffset = if (caret.max > caret.min) caret.max - 1 else caret.min
            val last = lineIndexAt(starts, lastOffset)
            val current = groups.lastOrNull()
            if (current != null && first <= groupLastLine) {
                current.add(index)
                groupLastLine = maxOf(groupLastLine, last)
            } else {
                groups.add(mutableListOf(index))
                groupLastLine = last
            }
        }
        return groups
    }

    private fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
        val sorted = ranges.sortedWith(compareBy({ it.min }, { it.max }))
        val merged = ArrayList<TextRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.min <= last.max) {
                merged[merged.lastIndex] = TextRange(last.min, maxOf(last.max, range.max))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun mapRemoved(offset: Int, removed: List<TextRange>): Int {
        var shift = 0
        for (range in removed) {
            when {
                offset >= range.max -> shift += range.max - range.min
                offset > range.min -> return range.min - shift
                else -> return offset - shift
            }
        }
        return offset - shift
    }

    private fun clamp(range: TextRange, length: Int): TextRange =
        TextRange(range.start.coerceIn(0, length), range.end.coerceIn(0, length))

    private fun nextCodePoint(text: String, offset: Int): Int {
        if (offset >= text.length) return text.length
        return (offset + Character.charCount(text.codePointAt(offset))).coerceAtMost(text.length)
    }

    private fun previousCodePoint(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        return (offset - Character.charCount(text.codePointBefore(offset))).coerceAtLeast(0)
    }

    private fun wordStart(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index > 0 && text[index - 1].isWhitespace()) index--
        if (index > 0 && EditActions.isIdentifierPart(text[index - 1])) {
            while (index > 0 && EditActions.isIdentifierPart(text[index - 1])) index--
        } else {
            while (index > 0 && !text[index - 1].isWhitespace() && !EditActions.isIdentifierPart(text[index - 1])) index--
        }
        return index
    }

    private fun wordEnd(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index < text.length && text[index].isWhitespace()) index++
        if (index < text.length && EditActions.isIdentifierPart(text[index])) {
            while (index < text.length && EditActions.isIdentifierPart(text[index])) index++
        } else {
            while (index < text.length && !text[index].isWhitespace() && !EditActions.isIdentifierPart(text[index])) index++
        }
        return index
    }

    private fun codePointCount(text: String, start: Int, end: Int, offset: Int): Int {
        var index = start
        var count = 0
        val limit = offset.coerceIn(start, end)
        while (index < limit) {
            index += Character.charCount(text.codePointAt(index))
            count++
        }
        return count
    }

    private fun lineStart(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index > 0 && text[index - 1] != '\n') index--
        return index
    }

    /** The end of the line, before the `\r` of a CRLF pair. */
    private fun lineEnd(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index < text.length && text[index] != '\n') index++
        if (index > 0 && index < text.length && text[index - 1] == '\r') index--
        return index
    }

    private fun clipLines(clip: String): List<String> {
        val trimmed = when {
            clip.endsWith("\r\n") -> clip.dropLast(2)
            clip.endsWith("\n") -> clip.dropLast(1)
            else -> clip
        }
        return trimmed.split(Regex("\r\n|\n"))
    }
}
