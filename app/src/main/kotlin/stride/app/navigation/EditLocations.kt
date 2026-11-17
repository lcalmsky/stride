package stride.app.navigation

import stride.app.editor.TextChange
import java.nio.file.Path

/** One place a recorded edit touched: the file and the offset it started at. */
data class EditPlace(val path: Path, val offset: Int)

/**
 * The last edit places of one project session, newest first. Every text change shifts the places
 * of its file; a recorded change also adds one, replacing the newest place on the same file and
 * line so a word typed into one place stays one place.
 *
 * A place never stores the line it was recorded on: lines above it come and go without moving its
 * offset, so every line question is answered by [lineOf] against the file's current text.
 */
class EditLocations(private val maxPlaces: Int = MAX_PLACES) {
    private val items = ArrayList<EditPlace>()

    /** Index of the place the last walk returned; -1 while the walk is at its start. */
    private var walked = -1

    val places: List<EditPlace> get() = items

    /**
     * Records a change of `[offset, offset + oldLength)` replaced with [newLength] characters of
     * [path]'s text, where [lineOf] maps an offset of that text to its line. A place after the
     * range moves by the length difference, a place inside it moves to the range's new end. Only a
     * recorded change adds a place.
     */
    fun change(path: Path, offset: Int, oldLength: Int, newLength: Int, lineOf: (Int) -> Int, recorded: Boolean) {
        shift(path, offset, oldLength, newLength)
        if (recorded) record(path, offset, lineOf)
    }

    /**
     * Records several changes of [path]'s text as [change] does for one; [changes] ascend, each in
     * the coordinates of the text after the previous ones were applied, so every offset is already
     * its position in the final text. A recorded set adds one place, at [recordIndex]'s range —
     * the caret's range by default the first.
     */
    fun changes(
        path: Path,
        changes: List<TextChange>,
        lineOf: (Int) -> Int,
        recorded: Boolean,
        recordIndex: Int = 0,
    ) {
        for (change in changes) shift(path, change.offset, change.oldLength, change.newText.length)
        if (recorded && changes.isNotEmpty()) {
            record(path, changes[recordIndex.coerceIn(0, changes.lastIndex)].offset, lineOf)
        }
    }

    /**
     * The place the next jump goes to: the newest, or the one before it while the caret sits on
     * that place. [lineOf] maps an offset of the caret's file to its line, so a place whose lines
     * moved above it is still recognised as the caret's. Later calls keep walking back, and `null`
     * means the walk passed the oldest place.
     */
    fun next(currentPath: Path?, caretLine: Int?, lineOf: (Int) -> Int): EditPlace? {
        var index = if (walked < 0) 0 else walked + 1
        while (index < items.size && items[index].isUnder(currentPath, caretLine, lineOf)) index++
        if (index >= items.size) {
            walked = -1
            return null
        }
        walked = index
        return items[index]
    }

    /** Drops every place of [path], which a file that no longer exists reports. */
    fun drop(path: Path) {
        items.removeAll { it.path == path }
        walked = -1
    }

    private fun record(path: Path, offset: Int, lineOf: (Int) -> Int) {
        val line = lineOf(offset)
        val place = EditPlace(path, offset)
        val newest = items.firstOrNull()
        if (newest != null && newest.path == path && lineOf(newest.offset) == line) {
            items[0] = place
        } else {
            items.add(0, place)
            if (items.size > maxPlaces) items.removeAt(items.lastIndex)
        }
        walked = -1
    }

    private fun shift(path: Path, offset: Int, oldLength: Int, newLength: Int) {
        val delta = newLength - oldLength
        val end = offset + oldLength
        for (index in items.indices) {
            val place = items[index]
            if (place.path != path) continue
            val moved = when {
                place.offset > end -> place.offset + delta
                place.offset > offset -> offset + newLength
                else -> place.offset
            }
            if (moved != place.offset) items[index] = place.copy(offset = moved)
        }
    }

    private fun EditPlace.isUnder(path: Path?, line: Int?, lineOf: (Int) -> Int): Boolean =
        line != null && this.path == path && lineOf(offset) == line

    companion object {
        const val MAX_PLACES: Int = 50
    }
}
