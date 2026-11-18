package stride.app.vcs

import androidx.compose.runtime.snapshots.Snapshot
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Path
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.ReloadSummary
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext

/** The id the data layer registers the Commit window under. */
internal const val COMMIT_TOOL_WINDOW_ID: String = "commit"

/**
 * Opens the Commit window and asks for a refresh. A cold snapshot is left to that refresh before
 * it reports the root, because until then it cannot tell a new project from a non-repository one.
 */
fun openCommit(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    state.statusMessage = null
    state.toolWindows.activate(COMMIT_TOOL_WINDOW_ID)
    git.refresh()
    val snapshot = git.snapshot
    if (snapshot.repository != null) return true
    if (snapshot.error != null) {
        state.statusMessage = snapshot.error
        return true
    }
    git.launch {
        val refreshed = git.refreshNow()
        if (refreshed.repository == null) {
            Snapshot.withMutableSnapshot {
                state.statusMessage = refreshed.error ?: notRepositoryMessage(session)
            }
        }
    }
    return true
}

/** Asks the service what a push would do; a refused plan goes to the status bar. */
fun openPush(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    git.launch {
        when (val plan = git.pushPlan()) {
            is PushPlan.Refused -> Snapshot.withMutableSnapshot { state.statusMessage = plan.message }
            is PushPlan.Ready -> Snapshot.withMutableSnapshot {
                state.statusMessage = null
                state.pushRequest = PushRequest(plan)
            }
        }
    }
    return true
}

/** Asks the service what an update would do; a refused plan goes to the status bar. */
fun openUpdate(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    val unsaved = modifiedEditorPaths(session).size
    git.launch {
        when (val plan = git.updatePlan(unsaved)) {
            is UpdatePlan.Refused -> Snapshot.withMutableSnapshot { state.statusMessage = plan.message }
            is UpdatePlan.Ready -> Snapshot.withMutableSnapshot { state.updateRequest = UpdateRequest(plan) }
        }
    }
    return true
}

/** Fetches every remote; the outcome replaces the status bar's message. */
fun fetchFromVcs(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    git.launch {
        val result = git.fetch()
        Snapshot.withMutableSnapshot { state.statusMessage = result.message }
    }
    return true
}

/** Commits and pushes from the Commit window; anywhere else it opens that window first. */
fun commitAndPush(state: AppState): Boolean {
    if (state.session == null) return false
    if (state.focus == KeyContext.COMMIT_WINDOW) {
        commitFromWindow(state, push = true)
        return true
    }
    return openCommit(state)
}

/** Opens the selected row's working-tree diff; outside the Commit window it does nothing. */
fun showSelectedDiff(state: AppState): Boolean {
    if (state.focus != KeyContext.COMMIT_WINDOW) return false
    val session = state.session ?: return false
    val window = session.commit
    window.syncRows(commitRows(session))
    val row = window.selectedRow() ?: return false
    return openWorkingTreeDiff(state, row.path)
}

/** The project root git itself works in; a project opened through a symlink is the same place. */
internal fun realProjectRoot(root: Path): Path {
    val lexical = root.toAbsolutePath().normalize()
    return runCatching { lexical.toRealPath() }.getOrDefault(lexical)
}

/** git reports real paths, so an editor under a symlinked project root is moved to the same form. */
private fun realPathOf(path: Path, lexicalRoot: Path, realRoot: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    return if (lexicalRoot != realRoot && absolute.startsWith(lexicalRoot)) {
        realRoot.resolve(lexicalRoot.relativize(absolute)).normalize()
    } else {
        absolute
    }
}

/** The open editors with unsaved text, as paths git would report them. */
internal fun modifiedEditorPaths(session: ProjectSession): List<Path> {
    val lexicalRoot = session.project.root.toAbsolutePath().normalize()
    val realRoot = realProjectRoot(lexicalRoot)
    return session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .filter { it.isModified }
        .map { realPathOf(it.file.path, lexicalRoot, realRoot) }
}

/** The window's list for the current snapshot and the answers git has given about its editors. */
internal fun commitRows(session: ProjectSession, window: CommitWindowState = session.commit): List<CommitRow> {
    val snapshot = session.git.snapshot
    val head = snapshot.branch?.head
    val changes = snapshot.changes.associate { it.path.toAbsolutePath().normalize() to it.kind }
    return CommitModel.rows(
        changes = snapshot.changes,
        root = realProjectRoot(session.project.root),
        modifiedEditors = modifiedEditorPaths(session),
        tracked = { path -> window.tracked(path, head, changes[path]) },
    )
}

/**
 * Asks git which modified editors it tracks and records each answer for the window. Runs off the
 * UI thread; a path is left out of the list until its answer arrives.
 */
internal suspend fun resolveTracked(session: ProjectSession, window: CommitWindowState) {
    val snapshot = session.git.snapshot
    val head = snapshot.branch?.head
    val changes = snapshot.changes.associate { it.path.toAbsolutePath().normalize() to it.kind }
    Snapshot.withMutableSnapshot { window.dropStaleTracked(head, changes) }
    for (path in modifiedEditorPaths(session)) {
        if (path in changes) continue
        if (window.tracked(path, head, null) != null) continue
        val tracked = when (session.git.baseText(path)) {
            is BaseText.Text, is BaseText.Unavailable -> true
            else -> false
        }
        Snapshot.withMutableSnapshot { window.recordTracked(path, head, null, tracked) }
    }
}

/**
 * Saves the checked files' editors and commits them. Refusals and save failures land in the
 * window's error line before git runs; the commit itself runs off the UI thread. With [push] a
 * successful commit asks for the push plan and opens the push popup, or refuses in the status bar.
 */
fun commitFromWindow(state: AppState, push: Boolean = false) {
    val session = state.session ?: return
    val window = session.commit
    if (window.committing || session.git.busy != null) return
    window.syncRows(commitRows(session, window))
    window.error = null
    val message = window.message
    if (message.isBlank()) {
        refuseCommit(state, window, "Enter a commit message")
        return
    }
    val paths = window.checkedPaths()
    if (paths.isEmpty()) {
        refuseCommit(state, window, "No changes to commit")
        return
    }
    val checked = paths.toSet()
    val lexicalRoot = session.project.root.toAbsolutePath().normalize()
    val realRoot = realProjectRoot(lexicalRoot)
    val editors: List<TextFileEditor> = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .filter { it.isModified && realPathOf(it.file.path, lexicalRoot, realRoot) in checked }
    for (editor in editors) {
        try {
            editor.save()
        } catch (e: IOException) {
            val reason = e.message ?: "cannot save ${editor.file.name}"
            refuseCommit(state, window, "Commit failed: $reason")
            return
        }
    }
    window.committing = true
    session.git.launch {
        try {
            val result = session.git.commit(paths, message)
            Snapshot.withMutableSnapshot {
                state.statusMessage = result.message
                if (result.ok) {
                    if (window.message == message) window.message = ""
                    window.error = null
                    window.inclusion.forget(paths)
                    window.syncRows(commitRows(session, window))
                } else {
                    window.error = result.message
                }
            }
            if (result.ok) {
                session.git.refresh()
                if (push) openPushAfterCommit(state, session, result)
            }
        } finally {
            Snapshot.withMutableSnapshot { window.committing = false }
        }
    }
}

private suspend fun openPushAfterCommit(state: AppState, session: ProjectSession, commit: GitWriteResult) {
    when (val plan = session.git.pushPlan()) {
        is PushPlan.Ready -> Snapshot.withMutableSnapshot {
            state.statusMessage = commit.message
            state.pushRequest = PushRequest(plan)
        }
        is PushPlan.Refused -> Snapshot.withMutableSnapshot {
            state.statusMessage = "${commit.message}; ${plan.message}"
        }
    }
}

/**
 * Runs [plan] with [strategy]: a merge or rebase refuses while an editor has unsaved text, the
 * other strategies save the project's unsaved editors first. After git runs the open editors read
 * the disk again, and their kept unsaved text goes into the result message.
 */
internal fun runUpdate(state: AppState, plan: UpdatePlan.Ready, strategy: UpdateStrategy) {
    val session = state.session ?: return
    val git = session.git
    val changed = modifiedEditorPaths(session).size
    if (strategy != UpdateStrategy.FAST_FORWARD && changed > 0) {
        state.statusMessage = cleanTreeMessage(changed)
        return
    }
    val lexicalRoot = session.project.root.toAbsolutePath().normalize()
    val realRoot = realProjectRoot(lexicalRoot)
    val editors = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .filter { it.isModified && realPathOf(it.file.path, lexicalRoot, realRoot).startsWith(realRoot) }
    for (editor in editors) {
        try {
            editor.save()
        } catch (e: IOException) {
            state.statusMessage = "Update failed: ${e.message ?: "cannot save ${editor.file.name}"}"
            return
        }
    }
    git.launch {
        val result = git.update(plan, strategy)
        val summary = reloadEditors(session)
        val kept = summary?.keptUnsaved ?: 0
        val message = if (result.ok && kept > 0) {
            "${result.message}; $kept editors with unsaved text were not reloaded"
        } else {
            result.message
        }
        Snapshot.withMutableSnapshot { state.statusMessage = message }
    }
}

/** The editors take their text on the UI thread, and git runs on the service's IO scope. */
private fun reloadEditors(session: ProjectSession): ReloadSummary? = runCatching {
    var summary: ReloadSummary? = null
    EventQueue.invokeAndWait { summary = session.editors.reloadChangedFromDisk() }
    summary
}.getOrNull()

private fun cleanTreeMessage(changed: Int): String =
    "Update needs a clean working tree to merge or rebase: commit your $changed changed files first"

private fun refuseCommit(state: AppState, window: CommitWindowState, message: String) {
    window.error = message
    state.statusMessage = message
}

internal fun notRepositoryMessage(session: ProjectSession): String =
    "Not a Git repository: ${session.project.root}"
