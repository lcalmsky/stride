package stride.app

import stride.app.actions.MenuRow
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * M7: the folders opened before, newest first, kept in memory and, when [node] is not `null`,
 * in the `stride` preferences key. A `null` node keeps them in this process only.
 */
internal class RecentProjects(val node: Preferences? = defaultStore()) {
    private val entries: MutableList<Path> = read()

    /** The stored folders, newest first. */
    fun entries(): List<Path> = entries.toList()

    /** Records [path] as the newest folder; a path containing a newline is not stored. */
    fun record(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if ('\n' in normalized.toString()) return
        entries.remove(normalized)
        entries.add(0, normalized)
        trim()
        write()
    }

    /** Drops [path] from the list, if it is there. */
    fun remove(path: Path) {
        if (entries.remove(path.toAbsolutePath().normalize())) write()
    }

    private fun read(): MutableList<Path> {
        val stored = runCatching { node?.get(KEY, null) }.getOrNull() ?: return mutableListOf()
        val result = ArrayList<Path>()
        for (line in stored.split('\n')) {
            if (line.isEmpty()) continue
            val path = runCatching { Path.of(line).toAbsolutePath().normalize() }.getOrNull() ?: continue
            if (path !in result) result.add(path)
        }
        return result.take(MAX_ENTRIES).toMutableList()
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        while (entries.isNotEmpty() && encoded().length > Preferences.MAX_VALUE_LENGTH) {
            entries.removeAt(entries.lastIndex)
        }
    }

    private fun encoded(): String = entries.joinToString("\n") { it.toString() }

    private fun write() {
        runCatching {
            node?.put(KEY, encoded())
            node?.flush()
        }
    }

    companion object {
        const val NODE: String = "stride"
        const val KEY: String = "recentProjects"
        const val MAX_ENTRIES: Int = 20

        /** Set in the test JVM so no test touches the user's real list. */
        const val DISABLE_DEFAULT_STORE: String = "stride.recentProjects.disableDefaultStore"

        fun defaultStore(): Preferences? =
            if (System.getProperty(DISABLE_DEFAULT_STORE) == "true") {
                null
            } else {
                runCatching { Preferences.userRoot().node(NODE) }.getOrNull()
            }
    }
}

/**
 * M7: File › Recent Projects' rows — folders opened before, without the open folder. A name that
 * occurs twice is shown with its parent, shortened under the home folder.
 */
internal fun recentProjectRows(state: AppState): List<MenuRow.Command> {
    val current = state.session?.project?.root?.toAbsolutePath()?.normalize()
    val paths = state.recentProjects.entries().filter { it != current }
    val names = paths.map { folderName(it) }
    val repeated = names.groupingBy { it }.eachCount()
    return paths.mapIndexed { index, path ->
        val name = names[index]
        val text = if ((repeated[name] ?: 0) > 1) "$name (${shortenedParent(path)})" else name
        MenuRow.Command(text = text, shortcut = "", enabled = true) {
            if (!Files.isDirectory(path)) {
                state.recentProjects.remove(path)
                state.statusMessage = "Folder not found: $path"
            } else {
                runCatching { state.openFolder(path) }
                    .onFailure { state.statusMessage = it.message ?: "Could not open $path" }
            }
        }
    }
}

private fun folderName(path: Path): String = path.fileName?.toString() ?: path.toString()

private fun shortenedParent(path: Path): String {
    val parent = path.parent ?: return path.toString()
    val home = runCatching { Path.of(System.getProperty("user.home")).toAbsolutePath().normalize() }
        .getOrNull()
        ?: return parent.toString()
    return when {
        parent == home -> "~"
        parent.startsWith(home) -> "~/" + home.relativize(parent).toString()
        else -> parent.toString()
    }
}
