package stride.app.navigation

import stride.app.editor.FileEditor
import stride.app.editor.TextFileEditor
import java.nio.file.Path

/** A visited place: the file and the caret offset inside it. */
data class NavigationLocation(val path: Path, val offset: Int)

/**
 * Back and forward history. [push] drops every forward entry, ignores a repeat of the current
 * entry and keeps at most [MAX_ENTRIES]. Recording is suspended while a [back] or [forward]
 * target is restored, otherwise the selection change it causes would truncate the forward stack.
 */
class NavigationHistory(private val maxEntries: Int = MAX_ENTRIES) {
    private val entries = ArrayList<NavigationLocation>()
    private var index = -1
    private var recording = true

    val size: Int get() = entries.size

    val current: NavigationLocation? get() = entries.getOrNull(index)

    fun locations(): List<NavigationLocation> = entries.toList()

    fun push(location: NavigationLocation) {
        if (current == location) return
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
        entries.add(location)
        index = entries.lastIndex
        while (entries.size > maxEntries) {
            entries.removeAt(0)
            index--
        }
    }

    fun record(location: NavigationLocation) {
        if (recording) push(location)
    }

    /** Tracks the caret without adding an entry; a different file is left alone. */
    fun updateCurrent(path: Path, offset: Int) {
        val entry = current ?: return
        if (entry.path == path) entries[index] = entry.copy(offset = offset)
    }

    fun back(): NavigationLocation? {
        if (index <= 0) return null
        index--
        return entries[index]
    }

    fun forward(): NavigationLocation? {
        if (index < 0 || index >= entries.lastIndex) return null
        index++
        return entries[index]
    }

    /**
     * Removes the entry the last [back] or [forward] moved to, leaving the index on the entry
     * before it; used when that entry's file no longer exists.
     */
    fun dropCurrent() {
        if (index !in entries.indices) return
        entries.removeAt(index)
        index--
    }

    fun clear() {
        entries.clear()
        index = -1
    }

    /** Runs [block] with recording off, for restoring a history entry. */
    fun <T> withoutRecording(block: () -> T): T {
        val previous = recording
        recording = false
        try {
            return block()
        } finally {
            recording = previous
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 100
    }
}

fun navigationLocation(editor: FileEditor): NavigationLocation {
    val path = editor.file.path.toAbsolutePath().normalize()
    val offset = (editor as? TextFileEditor)?.value?.selection?.start ?: 0
    return NavigationLocation(path, offset)
}
