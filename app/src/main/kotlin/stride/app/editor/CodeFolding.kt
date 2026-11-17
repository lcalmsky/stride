package stride.app.editor

import androidx.compose.ui.text.input.OffsetMapping

/** The text a collapsed range shows in place of its hidden content. */
internal const val FOLD_PLACEHOLDER: String = "..."

/** Extensions whose files have fold candidates. */
internal val BRACE_EXTENSIONS = setOf("kt", "kts", "java", "json")

/** A collapsible range: the hidden text is [hiddenStart, hiddenEnd) in document offsets. */
internal data class FoldRegion(val hiddenStart: Int, val hiddenEnd: Int)

/** Outer regions first, then by start; the order [effectiveFolds] and the actions rely on. */
internal val FOLD_REGION_ORDER: Comparator<FoldRegion> =
    compareBy<FoldRegion> { it.hiddenStart }.thenByDescending { it.hiddenEnd }

/**
 * The fold candidates of a document, derived from [CodeScan] without a parse tree: multi-line
 * brace and bracket pairs, multi-line block comments, and runs of two or more import lines.
 */
internal object FoldRegions {
    fun candidates(text: String): List<FoldRegion> {
        if (text.isEmpty()) return emptyList()
        val scan = CodeScan(text)
        val starts = MultiCaret.lineStarts(text)
        fun lineOf(offset: Int): Int = MultiCaret.lineIndexAt(starts, offset)
        val found = ArrayList<FoldRegion>()
        for ((open, close) in scan.bracketPairs()) {
            if (text[open] != '{' && text[open] != '[') continue
            if (lineOf(open) == lineOf(close)) continue
            found.add(FoldRegion(open + 1, close))
        }
        for (range in scan.blockComments) {
            val newline = text.indexOf('\n', range.first)
            if (newline < 0 || newline > range.last) continue
            val prefix = if (text.startsWith("/**", range.first)) 3 else 2
            found.add(FoldRegion(range.first + prefix, range.last - 1))
        }
        found.addAll(importRegions(text, scan, starts))
        return laminar(found.filter { it.hiddenEnd > it.hiddenStart })
    }

    /** Keeps the candidates that are nested in or disjoint from every earlier kept one. */
    private fun laminar(regions: List<FoldRegion>): List<FoldRegion> {
        val sorted = regions.sortedWith(FOLD_REGION_ORDER)
        val kept = ArrayList<FoldRegion>(sorted.size)
        for (region in sorted) {
            val partial = kept.any { other ->
                other.hiddenStart < region.hiddenEnd && region.hiddenStart < other.hiddenEnd &&
                    !(contains(other, region) || contains(region, other))
            }
            if (!partial) kept.add(region)
        }
        return kept
    }

    private fun importRegions(text: String, scan: CodeScan, starts: IntArray): List<FoldRegion> {
        val regions = ArrayList<FoldRegion>()
        var keywordStart = -1
        var lastLineEnd = -1
        var imports = 0
        fun flush() {
            if (imports >= 2 && keywordStart >= 0) {
                regions.add(FoldRegion(keywordStart + "import ".length, lastLineEnd))
            }
            keywordStart = -1
            lastLineEnd = -1
            imports = 0
        }
        for (line in starts.indices) {
            val lineStart = starts[line]
            var lineEnd = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
            if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd--
            var first = lineStart
            while (first < lineEnd && (text[first] == ' ' || text[first] == '\t')) first++
            if (first >= lineEnd) continue
            val isImport = text.startsWith("import", first) &&
                first + "import".length < lineEnd &&
                (text[first + "import".length] == ' ' || text[first + "import".length] == '\t') &&
                scan.isCode(first)
            if (!isImport) {
                flush()
                continue
            }
            if (imports == 0) keywordStart = first
            imports++
            lastLineEnd = lineEnd
        }
        flush()
        return regions
    }

    private fun contains(outer: FoldRegion, inner: FoldRegion): Boolean =
        outer.hiddenStart <= inner.hiddenStart && inner.hiddenEnd <= outer.hiddenEnd
}

/** The folds that are not contained in another one; only these are drawn. */
internal fun effectiveFolds(folded: List<FoldRegion>): List<FoldRegion> {
    if (folded.isEmpty()) return emptyList()
    val sorted = folded.sortedWith(FOLD_REGION_ORDER)
    val kept = ArrayList<FoldRegion>(sorted.size)
    for (region in sorted) {
        if (kept.any { outer -> outer.hiddenStart <= region.hiddenStart && region.hiddenEnd <= outer.hiddenEnd }) continue
        kept.add(region)
    }
    return kept
}

/**
 * The document text as the field lays it out: every effective fold replaced by the
 * [FOLD_PLACEHOLDER], plus the offsets and rows that map between the two.
 */
internal class FoldView private constructor(
    val docLength: Int,
    val folds: List<FoldRegion>,
    private val viewStarts: IntArray,
    private val deltas: IntArray,
    val visibleLines: IntArray,
) : OffsetMapping {
    val isEmpty: Boolean get() = folds.isEmpty()

    /** Length of the folded text [toView] maps [docLength] onto. */
    val viewLength: Int get() = docLength + deltas[deltas.size - 1]

    /** Rows the folded text occupies; the caller uses the document line count when [isEmpty]. */
    val rowCount: Int get() = visibleLines.size

    override fun originalToTransformed(offset: Int): Int = toView(offset)

    override fun transformedToOriginal(offset: Int): Int = toDoc(offset)

    fun toView(docOffset: Int): Int {
        if (folds.isEmpty()) return docOffset
        val offset = docOffset.coerceIn(0, docLength)
        val index = lastIndexAtOrBefore(docStartArray, offset)
        if (index < 0) return offset
        val fold = folds[index]
        return if (offset < fold.hiddenEnd) viewStarts[index] else offset + deltas[index + 1]
    }

    fun toDoc(viewOffset: Int): Int {
        if (folds.isEmpty()) return viewOffset
        val offset = viewOffset.coerceIn(0, viewLength)
        val index = lastIndexAtOrBefore(viewStarts, offset)
        if (index < 0) return offset
        val start = viewStarts[index]
        return when {
            offset < start + 2 -> folds[index].hiddenStart
            offset == start + 2 -> folds[index].hiddenEnd
            else -> offset - deltas[index + 1]
        }
    }

    /** View offset where fold [index]'s placeholder starts. */
    fun placeholderStart(index: Int): Int = viewStarts[index]

    /** The row a document line is drawn on; a hidden line takes its fold's row. */
    fun rowOf(docLine: Int): Int {
        if (isEmpty) return docLine
        val index = lastIndexAtOrBefore(visibleLines, docLine)
        return index.coerceAtLeast(0)
    }

    /** The row of a visible line, or `null` when the line's start is hidden. */
    fun visibleRowOf(docLine: Int): Int? {
        if (isEmpty) return docLine
        val index = lastIndexAtOrBefore(visibleLines, docLine)
        return if (index >= 0 && visibleLines[index] == docLine) index else null
    }

    /** The document line whose start begins [row]. */
    fun lineOfRow(row: Int): Int {
        if (isEmpty) return row
        return visibleLines[row.coerceIn(0, visibleLines.size - 1)]
    }

    /** The fold whose placeholder covers [viewOffset], or -1. */
    internal fun foldIndexAtView(viewOffset: Int): Int {
        val index = lastIndexAtOrBefore(viewStarts, viewOffset)
        if (index < 0) return -1
        return if (viewOffset - viewStarts[index] < FOLD_PLACEHOLDER.length) index else -1
    }

    /** The fold whose hidden text holds [offset], or -1. */
    internal fun foldIndexContaining(offset: Int): Int {
        val index = lastIndexAtOrBefore(docStartArray, offset)
        if (index < 0) return -1
        return if (offset < folds[index].hiddenEnd) index else -1
    }

    /** The fold whose hidden text ends at or after [offset], or -1. */
    internal fun foldIndexEndingAt(offset: Int): Int {
        var low = 0
        var high = folds.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (folds[mid].hiddenStart < offset) low = mid else high = mid - 1
        }
        if (folds.isEmpty() || folds[0].hiddenStart >= offset) return -1
        return if (offset <= folds[low].hiddenEnd) low else -1
    }

    /** The document starts of every fold, in the same order as [folds]. */
    private val docStartArray: IntArray = IntArray(folds.size) { folds[it].hiddenStart }

    /** The last index of [values] whose entry is at most [value], or -1. */
    private fun lastIndexAtOrBefore(values: IntArray, value: Int): Int {
        if (values.isEmpty() || values[0] > value) return -1
        var low = 0
        var high = values.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (values[mid] <= value) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        val NONE: FoldView = FoldView(0, emptyList(), IntArray(0), intArrayOf(0), IntArray(0))

        /** [NONE] when [folded] has no usable region; otherwise the view of [text]'s maximal folds. */
        fun of(text: String, folded: List<FoldRegion>): FoldView {
            if (folded.isEmpty()) return NONE
            val valid = folded.filter { it.hiddenStart >= 0 && it.hiddenStart < it.hiddenEnd && it.hiddenEnd <= text.length }
            val effective = effectiveFolds(valid)
            if (effective.isEmpty()) return NONE
            val viewStarts = IntArray(effective.size)
            val deltas = IntArray(effective.size + 1)
            var total = 0
            for (index in effective.indices) {
                val fold = effective[index]
                viewStarts[index] = fold.hiddenStart + total
                deltas[index] = total
                total += FOLD_PLACEHOLDER.length - (fold.hiddenEnd - fold.hiddenStart)
            }
            deltas[effective.size] = total
            val starts = MultiCaret.lineStarts(text)
            val hidden = BooleanArray(starts.size)
            for (fold in effective) {
                val first = MultiCaret.lineIndexAt(starts, fold.hiddenStart) + 1
                val last = MultiCaret.lineIndexAt(starts, fold.hiddenEnd)
                for (line in first..last) if (line in hidden.indices) hidden[line] = true
            }
            val visible = ArrayList<Int>(starts.size)
            for (line in hidden.indices) if (!hidden[line]) visible.add(line)
            return FoldView(text.length, effective, viewStarts, deltas, visible.toIntArray())
        }
    }
}
