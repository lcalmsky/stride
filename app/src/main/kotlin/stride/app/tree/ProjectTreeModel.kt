package stride.app.tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.core.Disposable
import stride.core.messages.MessageBusConnection
import stride.core.services.Project
import stride.core.vfs.VFS_CHANGES
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import stride.core.vfs.VfsListener
import java.awt.EventQueue
import java.nio.file.Path

/** One visible line of the project tree. */
data class TreeRow(val file: VFile, val depth: Int, val isExpanded: Boolean)

/**
 * Lazy, flattening model of the project tree.
 *
 * A directory's children are read only on its first expansion and cached until a VFS event
 * invalidates them. [childrenLoader] exists so tests can observe when a directory is read.
 */
class ProjectTreeModel(
    root: VFile,
    private val providers: List<TreeStructureProvider>,
    project: Project? = null,
    private val childrenLoader: (VFile) -> List<VFile> = { it.children() },
) : Disposable {
    val root: VFile = VFile(root.path.toAbsolutePath().normalize())

    private val expanded = HashSet<Path>()
    private val childrenCache = HashMap<Path, List<VFile>>()
    private var rowsState by mutableStateOf<List<TreeRow>>(emptyList())
    private val connection: MessageBusConnection? = project?.messageBus?.connect(project)?.also { c ->
        c.subscribe(VFS_CHANGES, object : VfsListener {
            override fun after(events: List<VFileEvent>) = handleEvents(events)
        })
    }

    private var disposed = false

    /**
     * The row the keyboard last selected, or null while the highlighted editor's file is shown
     * instead; Compose state, normalized like the model's keys.
     */
    var selected: Path? by mutableStateOf(null)
        private set

    init {
        expanded.add(key(root))
        rebuild()
    }

    /** Visible rows, root first. Reading this inside a composable subscribes it to refresh. */
    fun rows(): List<TreeRow> = rowsState

    fun select(file: VFile) {
        selected = key(file)
    }

    /**
     * Carries the expanded entries, the cached children and the selection at or below [old] to
     * the same place below [new], as a rename or move does.
     */
    fun moved(old: Path, new: Path) {
        val oldKey = old.toAbsolutePath().normalize()
        val newKey = new.toAbsolutePath().normalize()
        fun remap(path: Path): Path =
            if (path.startsWith(oldKey)) newKey.resolve(oldKey.relativize(path)).normalize() else path
        val remappedExpanded = expanded.mapTo(HashSet()) { remap(it) }
        expanded.clear()
        expanded.addAll(remappedExpanded)
        val remappedCache = HashMap<Path, List<VFile>>()
        for ((directory, children) in childrenCache) {
            remappedCache[remap(directory)] = children.map { VFile(remap(key(it))) }
        }
        childrenCache.clear()
        childrenCache.putAll(remappedCache)
        selected = selected?.let { remap(it) }
        rebuild()
    }

    fun expand(file: VFile) {
        if (expanded.add(key(file))) rebuild()
    }

    fun collapse(file: VFile) {
        if (expanded.remove(key(file))) rebuild()
    }

    fun toggle(file: VFile) {
        if (!file.isDirectory) return
        if (key(file) in expanded) collapse(file) else expand(file)
    }

    /**
     * Expands every ancestor of [file] so its row becomes visible; `false` when the file is
     * outside the root or does not exist below it.
     */
    fun reveal(file: VFile): Boolean {
        val target = key(file)
        val rootKey = key(root)
        if (target == rootKey) return true
        if (!target.startsWith(rootKey)) return false
        var changed = false
        if (expanded.add(rootKey)) changed = true
        var ancestor: Path? = target.parent
        while (ancestor != null && ancestor != rootKey) {
            if (expanded.add(ancestor)) changed = true
            ancestor = ancestor.parent
        }
        if (changed) rebuild()
        return rowsState.any { key(it.file) == target }
    }

    /**
     * Drops the cached children of every event file's parent directory, then rebuilds. Called
     * off the dispatch thread (the VFS watcher), the work goes to [EventQueue.invokeLater].
     */
    fun handleEvents(events: List<VFileEvent>) {
        if (EventQueue.isDispatchThread()) {
            handleEventsOnUi(events)
        } else {
            EventQueue.invokeLater { handleEventsOnUi(events) }
        }
    }

    private fun handleEventsOnUi(events: List<VFileEvent>) {
        if (disposed) return
        var changed = false
        for (event in events) {
            val parent = event.file.path.toAbsolutePath().normalize().parent ?: continue
            if (childrenCache.remove(parent) != null) changed = true
        }
        if (changed) rebuild()
    }

    /** Files below [directory] after every provider ran, read lazily and cached. */
    fun children(directory: VFile): List<VFile> {
        val key = key(directory)
        childrenCache[key]?.let { return it }
        val raw = childrenLoader(directory)
        var modified = raw
        for (provider in providers) modified = provider.modify(directory, modified)
        childrenCache[key] = modified
        return modified
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        connection?.dispose()
    }

    private fun rebuild() {
        val result = ArrayList<TreeRow>()
        fun visit(file: VFile, depth: Int) {
            val isExpanded = key(file) in expanded
            result.add(TreeRow(file, depth, isExpanded))
            if (isExpanded) {
                for (child in children(file)) visit(child, depth + 1)
            }
        }
        visit(root, 0)
        rowsState = result
    }

    private fun key(file: VFile): Path = file.path.toAbsolutePath().normalize()
}
