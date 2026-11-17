package stride.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import stride.app.editor.find.FindOptions
import stride.search.IndexedFile

/** The most hits one Find window tab keeps; the Find in Path popup keeps [FindInPathScanner.MAX_HITS]. */
const val FIND_WINDOW_MAX_HITS: Int = 1000

/** What a Find window tab searches for; REPLACE is reserved for the Replace in Path preview tab. */
enum class FindTabKind { USAGES, OCCURRENCES, REPLACE }

/** One Find window result tab: its search, the hits found so far and whether the scan is running. */
class FindTab(
    val id: Long,
    val kind: FindTabKind,
    val query: String,
    val options: FindOptions,
    val replacement: String?,
    val title: String,
) {
    var hits: List<FindInPathHit> by mutableStateOf(emptyList())
        internal set

    var searching: Boolean by mutableStateOf(true)
        internal set

    var truncated: Boolean by mutableStateOf(false)
        internal set

    /** An outcome line under the header; `null` when there is nothing to report. */
    var message: String? by mutableStateOf(null)
        internal set

    /** The line above the rows: the count, the running state or the truncation notice. */
    val header: String
        get() = when {
            searching -> "searching…"
            truncated -> "$FIND_WINDOW_MAX_HITS+ results (truncated)"
            else -> "${hits.size} results"
        }
}

/**
 * The Find tool window's tabs and their scans. Scans run on [scope], which the state owns, so
 * hiding the window leaves no tab stuck searching; [clear] cancels them and drops every tab.
 */
class FindToolWindowState(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val items = mutableStateListOf<FindTab>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private var nextId = 1L

    val tabs: List<FindTab> get() = items

    var selectedTabId: Long? by mutableStateOf(null)

    val selectedTab: FindTab? get() = items.firstOrNull { it.id == selectedTabId }

    fun select(tab: FindTab) {
        selectedTabId = tab.id
    }

    /**
     * Adds a tab for [query] and starts its scan. A tab with the same kind, query, options and
     * replacement is replaced in place and selected; past [MAX_TABS] the oldest tab is dropped.
     */
    fun addTab(
        kind: FindTabKind,
        query: String,
        options: FindOptions,
        title: String,
        files: List<IndexedFile>,
        replacement: String? = null,
        textOf: (Path) -> String? = { null },
    ): FindTab {
        val existing = items.indexOfFirst {
            it.kind == kind && it.query == query && it.options == options && it.replacement == replacement
        }
        val tab = FindTab(
            id = if (existing >= 0) items[existing].id else nextId++,
            kind = kind,
            query = query,
            options = options,
            replacement = replacement,
            title = title,
        )
        if (existing >= 0) {
            jobs.remove(tab.id)?.cancel()
            items[existing] = tab
        } else {
            if (items.size >= MAX_TABS) {
                val oldest = items.removeAt(0)
                jobs.remove(oldest.id)?.cancel()
            }
            items.add(tab)
        }
        selectedTabId = tab.id
        scan(tab, files, textOf)
        return tab
    }

    fun close(tab: FindTab) {
        val index = items.indexOf(tab)
        if (index < 0) return
        items.removeAt(index)
        jobs.remove(tab.id)?.cancel()
        if (selectedTabId == tab.id) {
            selectedTabId = items.getOrNull(index)?.id ?: items.lastOrNull()?.id
        }
    }

    /** Closes the selected tab; `null` when there is none. */
    fun closeSelected(): FindTab? {
        val tab = selectedTab ?: return null
        close(tab)
        return tab
    }

    /** Shows [message] under [tab]'s header; `null` clears it. */
    fun showMessage(tab: FindTab, message: String?) {
        tab.message = message
    }

    /** Drops [hit] from [tab] after it was replaced. */
    fun removeHit(tab: FindTab, hit: FindInPathHit) {
        tab.hits = tab.hits.filterNot { it === hit }
    }

    /** Ends a Replace All: the tab keeps the outcome line and no rows. */
    fun finishReplace(tab: FindTab, message: String) {
        tab.hits = emptyList()
        tab.truncated = false
        tab.message = message
    }

    /** Cancels the running scans and drops every tab. */
    fun clear() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
        items.clear()
        selectedTabId = null
    }

    private fun scan(tab: FindTab, files: List<IndexedFile>, textOf: (Path) -> String?) {
        tab.hits = emptyList()
        tab.searching = true
        tab.truncated = false
        // Lazy start so the job is in [jobs] before it can run: a cancelled or replaced tab
        // must not have its scan write back into the tab it no longer owns.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val hits = FindInPathScanner.scan(
                files = files,
                query = tab.query,
                options = tab.options,
                limit = FIND_WINDOW_MAX_HITS,
                textOf = textOf,
                shouldStop = { !isActive },
            )
            if (isActive) {
                Snapshot.withMutableSnapshot {
                    tab.hits = hits
                    tab.truncated = hits.size >= FIND_WINDOW_MAX_HITS
                    tab.searching = false
                }
            }
            coroutineContext[Job]?.let { jobs.remove(tab.id, it) }
        }
        jobs[tab.id] = job
        job.start()
    }

    companion object {
        const val MAX_TABS: Int = 10
    }
}
