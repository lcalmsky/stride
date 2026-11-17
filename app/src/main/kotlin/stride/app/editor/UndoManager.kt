package stride.app.editor

import androidx.compose.ui.text.TextRange

data class UndoEntry(
    val offset: Int,
    val oldText: String,
    val newText: String,
    val selectionBefore: TextRange,
    val selectionAfter: TextRange,
    val secondaryBefore: List<TextRange> = emptyList(),
    val secondaryAfter: List<TextRange> = emptyList(),
    val typing: Boolean = false,
    /**
     * The replaced ranges of a multi-range entry, ascending and sequential; `null` for a single
     * range. Undo reports their inverse and redo the stored ones, so a place between two ranges is
     * not moved to the merged end.
     */
    val pieces: List<TextChange>? = null,
)

/** Two stacks of [UndoEntry]; consecutive single-character insertions merge within [MERGE_MILLIS]. */
class UndoManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = 1_000,
) {
    private class Timed(val entry: UndoEntry, val time: Long)

    private val undoStack = ArrayList<Timed>()
    private val redoStack = ArrayList<Timed>()
    private var breakNext = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records a new change, clears the redo stack and merges into the previous entry when allowed. */
    fun record(entry: UndoEntry) {
        val time = clock()
        redoStack.clear()
        val previous = undoStack.lastOrNull()
        val merge = !breakNext && previous != null && canMerge(previous, entry, time)
        breakNext = false
        if (merge && previous != null) {
            undoStack[undoStack.lastIndex] = Timed(merged(previous.entry, entry), previous.time)
        } else {
            undoStack.add(Timed(entry, time))
            if (undoStack.size > limit) undoStack.removeAt(0)
        }
    }

    /** Moves the last entry to the redo stack and returns it, or `null` when nothing is left. */
    fun undo(): UndoEntry? {
        val timed = undoStack.removeLastOrNull() ?: return null
        redoStack.add(timed)
        breakNext = true
        return timed.entry
    }

    fun redo(): UndoEntry? {
        val timed = redoStack.removeLastOrNull() ?: return null
        undoStack.add(timed)
        breakNext = true
        return timed.entry
    }

    /** Keeps the next recorded change from merging into the previous one. */
    fun breakMerge() {
        breakNext = true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        breakNext = false
    }

    private fun canMerge(previous: Timed, next: UndoEntry, time: Long): Boolean {
        if (time - previous.time > MERGE_MILLIS) return false
        if (previous.entry.typing && next.typing) return canMergeTyping(previous.entry, next)
        return previous.entry.oldText.isEmpty() &&
            next.oldText.isEmpty() &&
            next.offset == previous.entry.offset + previous.entry.newText.length &&
            next.newText.length == 1 &&
            !next.newText[0].isWhitespace()
    }

    private fun canMergeTyping(previous: UndoEntry, next: UndoEntry): Boolean {
        if (previous.secondaryBefore.isEmpty() || previous.secondaryAfter.isEmpty()) return false
        if (next.secondaryBefore.isEmpty() || next.secondaryAfter.isEmpty()) return false
        if (next.selectionBefore != previous.selectionAfter) return false
        if (next.secondaryBefore != previous.secondaryAfter) return false
        val start = next.offset - previous.offset
        return start >= 0 &&
            start + next.oldText.length <= previous.newText.length
    }

    private fun merged(previous: UndoEntry, next: UndoEntry): UndoEntry {
        if (!(previous.typing && next.typing)) {
            return previous.copy(
                newText = previous.newText + next.newText,
                selectionAfter = next.selectionAfter,
                secondaryAfter = next.secondaryAfter,
                pieces = null,
            )
        }
        val distance = next.offset - previous.offset
        val tail = previous.newText.substring(distance + next.oldText.length)
        return previous.copy(
            newText = previous.newText.substring(0, distance) + next.newText + tail,
            selectionAfter = next.selectionAfter,
            secondaryAfter = next.secondaryAfter,
            typing = true,
            pieces = mergedPieces(previous.pieces, next.pieces),
        )
    }

    /**
     * Two merged typing entries keep one piece per caret: the next keystroke sits right after the
     * previous character the same caret wrote, so the piece takes the next entry's offset and both
     * characters. A count mismatch drops the pieces and the merged range is reported instead.
     */
    private fun mergedPieces(previous: List<TextChange>?, next: List<TextChange>?): List<TextChange>? {
        if (previous == null || next == null || previous.size != next.size) return null
        return previous.indices.map { index ->
            TextChange(
                offset = next[index].offset - previous[index].newText.length,
                oldLength = previous[index].oldLength,
                newText = previous[index].newText + next[index].newText,
            )
        }
    }

    companion object {
        const val MERGE_MILLIS = 500L
    }
}
