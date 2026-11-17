package stride.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
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
 * The Replace in Path state of one Find window tab: whether a plan is running, the plan waiting
 * for the confirmation and the confirmation text. It lives in [FindToolWindowState] instead of
 * composition, so switching tabs while a plan runs neither drops its confirmation nor re-enables
 * the buttons.
 */
class ReplaceTabState {
    var busy: Boolean by mutableStateOf(false)
        internal set

    var pendingPlan: ReplacePlan? by mutableStateOf(null)
        internal set

    var confirmation: String? by mutableStateOf(null)
        internal set
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
    private val replaceStates = ConcurrentHashMap<Long, ReplaceTabState>()
    private val replaceJobs = ConcurrentHashMap<Long, Job>()
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
            replaceJobs.remove(tab.id)?.cancel()
            replaceStates.remove(tab.id)
            items[existing] = tab
        } else {
            if (items.size >= MAX_TABS) {
                val oldest = items.removeAt(0)
                jobs.remove(oldest.id)?.cancel()
                replaceJobs.remove(oldest.id)?.cancel()
                replaceStates.remove(oldest.id)
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
        replaceJobs.remove(tab.id)?.cancel()
        replaceStates.remove(tab.id)
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

    /** The replace state of [tab]; kept here so it outlives the composition that started a plan. */
    fun replaceState(tab: FindTab): ReplaceTabState =
        replaceStates.computeIfAbsent(tab.id) { ReplaceTabState() }

    /**
     * Plans Replace All for [tab] on [scope] and stores the confirmation on its replace state.
     * A plan that finishes while another tab is shown still reaches this tab's state.
     */
    fun startReplaceAll(tab: FindTab, controller: FindReplaceController) {
        val current = replaceState(tab)
        if (current.busy) return
        current.busy = true
        tab.message = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val plan = withContext(Dispatchers.Default) { controller.planAll(tab) }
                Snapshot.withMutableSnapshot {
                    when {
                        plan.error != null -> tab.message = plan.error
                        plan.occurrences == 0 -> tab.message = "No occurrences to replace"
                        else -> {
                            current.pendingPlan = plan
                            current.confirmation = ReplaceInPath.confirmation(
                                plan,
                                tab.query,
                                tab.replacement.orEmpty(),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Snapshot.withMutableSnapshot {
                    tab.message = "Replace failed: ${t.message ?: t::class.simpleName}"
                }
            } finally {
                Snapshot.withMutableSnapshot { current.busy = false }
                coroutineContext[Job]?.let { replaceJobs.remove(tab.id, it) }
            }
        }
        replaceJobs[tab.id] = job
        job.start()
    }

    /**
     * Records the outcome of a Replace Selected on [file]: the file's rows are replaced with
     * [hits] only when something was replaced, so a skipped row stays in the list for a retry.
     */
    fun finishReplaceSelected(tab: FindTab, file: VFile, outcome: ReplaceOutcome, hits: List<FindInPathHit>) {
        tab.message = outcome.message
        if (outcome.replaced > 0) refreshFileHits(tab, file, hits)
    }

    /** Replaces [tab]'s hits of [file] with [hits] from a fresh scan of just that file. */
    fun refreshFileHits(tab: FindTab, file: VFile, hits: List<FindInPathHit>) {
        val target = file.path.toAbsolutePath().normalize()
        val others = tab.hits.filterNot { it.file.path.toAbsolutePath().normalize() == target }
        tab.hits = (others + hits).sortedWith(compareBy({ it.relativePath }, { it.start }))
    }

    /** Ends a Replace All: the tab keeps the outcome line and no rows. */
    fun finishReplace(tab: FindTab, message: String) {
        tab.hits = emptyList()
        tab.truncated = false
        tab.message = message
    }

    /** Cancels the running scans and replace plans and drops every tab. */
    fun clear() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
        for (job in replaceJobs.values) job.cancel()
        replaceJobs.clear()
        replaceStates.clear()
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
