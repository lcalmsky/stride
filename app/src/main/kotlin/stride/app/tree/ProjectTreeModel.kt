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

    init {
        expanded.add(key(root))
        rebuild()
    }

    /** Visible rows, root first. Reading this inside a composable subscribes it to refresh. */
    fun rows(): List<TreeRow> = rowsState

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

    /** Drops the cached children of every event file's parent directory, then rebuilds. */
    fun handleEvents(events: List<VFileEvent>) {
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
