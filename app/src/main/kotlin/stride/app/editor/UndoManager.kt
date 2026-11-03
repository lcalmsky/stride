package stride.app.editor

import androidx.compose.ui.text.TextRange

data class UndoEntry(
    val offset: Int,
    val oldText: String,
    val newText: String,
    val selectionBefore: TextRange,
    val selectionAfter: TextRange,
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
            undoStack[undoStack.lastIndex] = Timed(
                previous.entry.copy(
                    newText = previous.entry.newText + entry.newText,
                    selectionAfter = entry.selectionAfter,
                ),
                previous.time,
            )
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

    private fun canMerge(previous: Timed, next: UndoEntry, time: Long): Boolean =
        previous.entry.oldText.isEmpty() &&
            next.oldText.isEmpty() &&
            next.offset == previous.entry.offset + previous.entry.newText.length &&
            next.newText.length == 1 &&
            !next.newText[0].isWhitespace() &&
            time - previous.time <= MERGE_MILLIS

    companion object {
        const val MERGE_MILLIS = 500L
    }
}
