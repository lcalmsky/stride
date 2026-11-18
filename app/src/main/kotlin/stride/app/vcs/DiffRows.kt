package stride.app.vcs

/**
 * One row of the unified diff. [Code] carries the 1-based line numbers of the side it exists on,
 * a sign (`-`, `+`, or blank) and the line without a trailing `\r`. [Fold] hides an unchanged run
 * and becomes its lines when expanded. [Message] stands in for the whole file when one side cannot
 * be compared.
 */
sealed interface DiffRow {
    data class Code(
        val oldNumber: Int?,
        val newNumber: Int?,
        val sign: Char,
        val text: String,
    ) : DiffRow

    data class Fold(val lines: List<Code>) : DiffRow {
        val count: Int get() = lines.size
    }

    data class Message(val text: String) : DiffRow
}

/** Builds the diff popup's rows from a [DiffFile]; pure, so the folding rules are unit-tested. */
object DiffRows {

    const val CONTEXT_LINES: Int = 3
    const val FOLD_THRESHOLD: Int = 6

    fun build(file: DiffFile): List<DiffRow> {
        val old = when (val base = file.old) {
            is BaseText.Text -> splitContent(base.content)
            BaseText.Untracked -> emptyList()
            is BaseText.Unavailable -> return listOf(DiffRow.Message(base.reason))
        }
        val new = when (val current = file.new) {
            is BaseText.Text -> splitContent(current.content)
            BaseText.Untracked -> emptyList()
            is BaseText.Unavailable -> return listOf(DiffRow.Message(current.reason))
        }
        val hunks = LineDiff.diff(old, new) ?: listOf(LineHunk(0, old.size, 0, new.size))
        if (hunks.isEmpty()) {
            return List(old.size) { index -> DiffRow.Code(index + 1, index + 1, ' ', old[index]) }
        }
        val rows = ArrayList<DiffRow>()
        var oldIndex = 0
        var newIndex = 0
        for (hunk in hunks) {
            appendContext(rows, old, oldIndex, newIndex, hunk.oldStart - oldIndex, atStart = rows.isEmpty(), atEnd = false)
            for (index in hunk.oldStart until hunk.oldStart + hunk.oldCount) {
                rows += DiffRow.Code(index + 1, null, '-', old[index])
            }
            for (index in hunk.newStart until hunk.newStart + hunk.newCount) {
                rows += DiffRow.Code(null, index + 1, '+', new[index])
            }
            oldIndex = hunk.oldStart + hunk.oldCount
            newIndex = hunk.newStart + hunk.newCount
        }
        appendContext(rows, old, oldIndex, newIndex, old.size - oldIndex, atStart = rows.isEmpty(), atEnd = true)
        return rows
    }

    /** [rows] with the fold at [index] replaced by its lines; other rows are unchanged. */
    fun expand(rows: List<DiffRow>, index: Int): List<DiffRow> {
        val fold = rows.getOrNull(index) as? DiffRow.Fold ?: return rows
        return buildList(rows.size + fold.count - 1) {
            addAll(rows.subList(0, index))
            addAll(fold.lines)
            addAll(rows.subList(index + 1, rows.size))
        }
    }

    /** The index of the first row of every changed run. */
    fun hunkStarts(rows: List<DiffRow>): List<Int> {
        val starts = ArrayList<Int>()
        var inHunk = false
        rows.forEachIndexed { index, row ->
            val changed = row is DiffRow.Code && row.sign != ' '
            if (changed && !inHunk) starts += index
            inHunk = changed
        }
        return starts
    }

    /** The start of the hunk after (or before) [from], or `null` at either end. */
    fun nextHunk(rows: List<DiffRow>, from: Int, forward: Boolean): Int? {
        val starts = hunkStarts(rows)
        return if (forward) starts.firstOrNull { it > from } else starts.lastOrNull { it < from }
    }

    /** The row of the 0-based [line] on the new side, or the nearest row above it. */
    fun rowAtLine(rows: List<DiffRow>, line: Int): Int {
        var fallback = 0
        for (index in rows.indices) {
            val number = (rows[index] as? DiffRow.Code)?.newNumber ?: continue
            if (number == line + 1) return index
            if (number <= line + 1) fallback = index
        }
        return fallback
    }

    /** An empty blob has no lines; a document of `""` is one empty line, but a diff shows none. */
    private fun splitContent(text: String): List<String> = if (text.isEmpty()) emptyList() else LineDiff.split(text)

    /**
     * Adds an unchanged run. A run longer than [FOLD_THRESHOLD] keeps [CONTEXT_LINES] lines next to
     * the change and folds the rest; at the start or end of the file only the inner three are kept,
     * and the run away from either edge keeps three on both sides of the fold row.
     */
    private fun appendContext(
        rows: MutableList<DiffRow>,
        old: List<String>,
        oldStart: Int,
        newStart: Int,
        count: Int,
        atStart: Boolean,
        atEnd: Boolean,
    ) {
        if (count <= 0) return
        fun context(offset: Int): DiffRow.Code =
            DiffRow.Code(oldStart + offset + 1, newStart + offset + 1, ' ', old[oldStart + offset])
        fun slice(from: Int, to: Int): List<DiffRow.Code> = (from until to).map(::context)
        when {
            count <= FOLD_THRESHOLD || (atStart && atEnd) -> rows += slice(0, count)
            atStart -> {
                rows += DiffRow.Fold(slice(0, count - CONTEXT_LINES))
                rows += slice(count - CONTEXT_LINES, count)
            }
            atEnd -> {
                rows += slice(0, CONTEXT_LINES)
                rows += DiffRow.Fold(slice(CONTEXT_LINES, count))
            }
            else -> {
                rows += slice(0, CONTEXT_LINES)
                rows += DiffRow.Fold(slice(CONTEXT_LINES, count - CONTEXT_LINES))
                rows += slice(count - CONTEXT_LINES, count)
            }
        }
    }
}
