package stride.app.navigation

import java.nio.file.Path

/** One place a recorded edit touched: the file, the offset it started at and the line it was on. */
data class EditPlace(val path: Path, val offset: Int, val line: Int)

/**
 * The last edit places of one project session, newest first. Every text change shifts the places
 * of its file; a recorded change also adds one, replacing the newest place on the same file and
 * line so a word typed into one place stays one place.
 */
class EditLocations(private val maxPlaces: Int = MAX_PLACES) {
    private val items = ArrayList<EditPlace>()

    /** Index of the place the last walk returned; -1 while the walk is at its start. */
    private var walked = -1

    val places: List<EditPlace> get() = items

    /**
     * Records a change of `[offset, offset + oldLength)` replaced with [newLength] characters on
     * [line] of [path]. A place after the range moves by the length difference, a place inside it
     * moves to the range's new end. Only a recorded change adds a place.
     */
    fun change(path: Path, offset: Int, oldLength: Int, newLength: Int, line: Int, recorded: Boolean) {
        shift(path, offset, oldLength, newLength)
        if (!recorded) return
        val place = EditPlace(path, offset, line)
        val newest = items.firstOrNull()
        if (newest != null && newest.path == path && newest.line == line) {
            items[0] = place
        } else {
            items.add(0, place)
            if (items.size > maxPlaces) items.removeAt(items.lastIndex)
        }
        walked = -1
    }

    /**
     * The place the next jump goes to: the newest, or the one before it while the caret sits on
     * that place. Later calls keep walking back, and `null` means the walk passed the oldest place.
     */
    fun next(currentPath: Path?, caretLine: Int?): EditPlace? {
        var index = if (walked < 0) 0 else walked + 1
        while (index < items.size && items[index].isUnder(currentPath, caretLine)) index++
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

    private fun EditPlace.isUnder(path: Path?, line: Int?): Boolean =
        line != null && this.path == path && this.line == line

    companion object {
        const val MAX_PLACES: Int = 50
    }
}
