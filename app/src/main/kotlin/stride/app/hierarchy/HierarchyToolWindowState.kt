package stride.app.hierarchy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import stride.app.ui.toolwindow.ListWindowRow

/**
 * One line of the Hierarchy window's visible tree: a node at [depth], or the [searching] and
 * [error] rows that stand in for its children while they load. `node` is `null` on those two rows.
 */
data class HierarchyRow(
    val node: HierarchyNode?,
    val depth: Int,
    val expandable: Boolean = false,
    val expanded: Boolean = false,
    val searching: Boolean = false,
    val error: String? = null,
)

/**
 * The Hierarchy tool window's tree, its expansion state and the children loaded so far. The state
 * owns [scope], so replacing the tree or clearing the window cancels loads the previous tree
 * started; a node's `children` runs at most once and its result is kept across collapses. Every
 * read of [rows] rebuilds the depth-first list from the expanded nodes, which is what the window
 * draws.
 */
class HierarchyToolWindowState(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class NodeEntry(
        val expanded: Boolean = false,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val children: List<HierarchyNode> = emptyList(),
        val error: String? = null,
    )

    private val entries = mutableStateMapOf<HierarchyNode, NodeEntry>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private var generation = 0L

    /** The shown tree; `null` until the first hierarchy is opened and after [clear]. */
    var tree: HierarchyTree? by mutableStateOf(null)
        private set

    /** The visible tree in depth-first order: expanded nodes followed by their children. */
    val rows: List<HierarchyRow>
        get() {
            val tree = tree ?: return emptyList()
            val output = ArrayList<HierarchyRow>()
            fun visit(node: HierarchyNode, depth: Int) {
                val entry = entries[node]
                val expandable = isExpandable(node, entry)
                val expanded = expandable && entry?.expanded == true
                output += HierarchyRow(
                    node = node,
                    depth = depth,
                    expandable = expandable,
                    expanded = expanded,
                )
                if (!expanded) return
                when {
                    entry.loading -> output += HierarchyRow(null, depth + 1, searching = true)
                    entry.error != null ->
                        output += HierarchyRow(null, depth + 1, error = "Could not load: ${entry.error}")
                    else -> entry.children.forEach { visit(it, depth + 1) }
                }
            }
            tree.roots.forEach { visit(it, 0) }
            return output
        }

    /** [rows] as the list rows the window draws, with the expansion marker in the primary text. */
    val listRows: List<ListWindowRow>
        get() = rows.map { row ->
            when {
                row.searching -> ListWindowRow(AnnotatedString("Searching…"))
                row.error != null -> ListWindowRow(AnnotatedString(row.error))
                else -> {
                    val node = row.node ?: return@map ListWindowRow(AnnotatedString(""))
                    val marker = when {
                        !row.expandable -> ""
                        row.expanded -> "▾ "
                        else -> "▸ "
                    }
                    ListWindowRow(
                        primary = AnnotatedString(marker + node.text),
                        secondary = node.detail?.let { AnnotatedString(it) },
                        iconName = node.iconName,
                        indent = row.depth,
                        accent = node.accent,
                    )
                }
            }
        }

    /** Shows [tree], starting the root nodes that open themselves; loads of the previous tree stop. */
    fun show(tree: HierarchyTree) {
        generation++
        cancelLoads()
        entries.clear()
        this.tree = tree
        tree.roots.forEach { node -> if (node.expanded) expand(node) }
    }

    /** Drops the tree and stops its loads; the registered window draws its empty state. */
    fun clear() {
        generation++
        cancelLoads()
        entries.clear()
        tree = null
    }

    /**
     * →: expands a collapsed node, or moves to its first child when it is already open. A leaf
     * consumes the key without moving. Returns the index the selection keeps or takes.
     */
    fun moveRight(index: Int): Int {
        val row = rows.getOrNull(index) ?: return index
        val node = row.node ?: return index
        if (!row.expandable) return index
        if (!row.expanded) {
            expand(node)
            return index
        }
        val first = rows.getOrNull(index + 1)
        return if (first?.node != null && first.depth == row.depth + 1) index + 1 else index
    }

    /**
     * ←: collapses an expanded node, or moves to its parent when it is collapsed or a leaf. A root
     * consumes the key without moving. Returns the index the selection keeps or takes.
     */
    fun moveLeft(index: Int): Int {
        val row = rows.getOrNull(index) ?: return index
        val node = row.node ?: return index
        if (row.expanded) {
            collapse(node)
            return index
        }
        if (row.depth == 0) return index
        for (candidate in index - 1 downTo 0) {
            val above = rows[candidate]
            if (above.node != null && above.depth == row.depth - 1) return candidate
        }
        return index
    }

    /** Flips the expansion of the node on [index]; the action for Enter on a node without a location. */
    fun toggle(index: Int) {
        val row = rows.getOrNull(index) ?: return
        val node = row.node ?: return
        when {
            !row.expandable -> return
            row.expanded -> collapse(node)
            else -> expand(node)
        }
    }

    private fun expand(node: HierarchyNode) {
        val entry = entries[node] ?: NodeEntry()
        if (entry.expanded) return
        entries[node] = entry.copy(expanded = true)
        val loader = node.children ?: return
        if (entry.loaded || entry.loading) return
        entries[node] = entry.copy(expanded = true, loading = true)
        val startedFor = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                Result.success(loader())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            Snapshot.withMutableSnapshot {
                if (startedFor != generation) return@withMutableSnapshot
                val current = entries[node] ?: return@withMutableSnapshot
                val children = result.getOrNull()
                entries[node] = current.copy(
                    loading = false,
                    loaded = true,
                    children = children ?: emptyList(),
                    error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
                )
                children?.filter { it.expanded }?.forEach { expand(it) }
            }
        }
        jobs += job
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }

    private fun collapse(node: HierarchyNode) {
        val entry = entries[node] ?: return
        entries[node] = entry.copy(expanded = false)
    }

    private fun cancelLoads() {
        for (job in jobs) job.cancel()
        jobs.clear()
    }

    private fun isExpandable(node: HierarchyNode, entry: NodeEntry?): Boolean {
        if (node.children == null) return false
        val loadedEmpty = entry?.loaded == true && entry.error == null && entry.children.isEmpty()
        return !loadedEmpty
    }
}
