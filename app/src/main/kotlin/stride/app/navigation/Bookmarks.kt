package stride.app.navigation

import androidx.compose.runtime.mutableStateListOf
import java.nio.file.Path

/** One bookmark: a line of a file, with the text the line had when it was set. */
data class Bookmark(val path: Path, val relativePath: String, val line: Int, val lineText: String)

/** Per-project bookmarks, kept in memory for the life of the session. */
class BookmarkManager {
    private val items = mutableStateListOf<Bookmark>()

    /** Compose snapshot list, in the order the bookmarks were set. */
    val entries: List<Bookmark> get() = items

    /** Adds the bookmark, or removes the one on the same line; returns `true` when it was added. */
    fun toggle(bookmark: Bookmark): Boolean {
        val existing = items.indexOfFirst { it.path == bookmark.path && it.line == bookmark.line }
        return if (existing >= 0) {
            items.removeAt(existing)
            false
        } else {
            items.add(bookmark)
            true
        }
    }

    fun remove(bookmark: Bookmark) {
        items.removeAll { it == bookmark }
    }

    /** Moves the bookmarks at or below [old] below [new], each keeping its line (P8). */
    fun move(old: Path, new: Path, root: Path) {
        val oldKey = old.toAbsolutePath().normalize()
        val newKey = new.toAbsolutePath().normalize()
        val rootKey = root.toAbsolutePath().normalize()
        for (index in items.indices) {
            val bookmark = items[index]
            val path = bookmark.path.toAbsolutePath().normalize()
            if (!path.startsWith(oldKey)) continue
            val moved = newKey.resolve(oldKey.relativize(path)).normalize()
            items[index] = bookmark.copy(path = moved, relativePath = rootKey.relativize(moved).toString())
        }
    }

    fun linesOf(path: Path): Set<Int> = items.filter { it.path == path }.map { it.line }.toSet()

    fun isEmpty(): Boolean = items.isEmpty()

    fun clear() = items.clear()

    companion object {
        /** The 0-based line a character offset falls on. */
        fun lineIndex(text: String, offset: Int): Int {
            var line = 0
            for (index in 0 until offset.coerceIn(0, text.length)) if (text[index] == '\n') line++
            return line
        }

        /** The text of the 0-based [line], without its indentation or line ending. */
        fun lineText(text: String, line: Int): String {
            var start = 0
            var current = 0
            while (current < line) {
                val newline = text.indexOf('\n', start)
                if (newline < 0) return ""
                start = newline + 1
                current++
            }
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            return text.substring(start, end).trim()
        }
    }
}
