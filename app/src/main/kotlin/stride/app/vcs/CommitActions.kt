package stride.app.vcs

import androidx.compose.runtime.snapshots.Snapshot
import java.io.IOException
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext

/** The id the data layer registers the Commit window under. */
internal const val COMMIT_TOOL_WINDOW_ID: String = "commit"

/** Opens the Commit window and asks for a refresh; outside a repository it reports the root. */
fun openCommit(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    val snapshot = git.snapshot
    if (snapshot.repository == null) {
        state.statusMessage = snapshot.error ?: notRepositoryMessage(session)
        return true
    }
    state.statusMessage = null
    state.toolWindows.activate(COMMIT_TOOL_WINDOW_ID)
    git.refresh()
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

/** Opens the selected row's working-tree diff; outside the Commit window it does nothing. */
fun showSelectedDiff(state: AppState): Boolean {
    if (state.focus != KeyContext.COMMIT_WINDOW) return false
    val session = state.session ?: return false
    val window = session.commit
    window.syncRows(commitRows(session))
    val row = window.selectedRow() ?: return false
    return openWorkingTreeDiff(state, row.path)
}

/** The window's list for the current snapshot and the open editors. */
internal fun commitRows(session: ProjectSession): List<CommitRow> = CommitModel.rows(
    changes = session.git.snapshot.changes,
    root = session.project.root,
    modifiedEditors = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .filter { it.isModified }
        .map { it.file.path },
)

/**
 * Saves the checked files' editors and commits them. Refusals and save failures land in the
 * window's error line before git runs; the commit itself runs off the UI thread.
 */
fun commitFromWindow(state: AppState) {
    val session = state.session ?: return
    val window = session.commit
    window.syncRows(commitRows(session))
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
    val editors: List<TextFileEditor> = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .filter { it.isModified && it.file.path.toAbsolutePath().normalize() in checked }
    for (editor in editors) {
        try {
            editor.save()
        } catch (e: IOException) {
            val reason = e.message ?: "cannot save ${editor.file.name}"
            refuseCommit(state, window, "Commit failed: $reason")
            return
        }
    }
    session.git.launch {
        val result = session.git.commit(paths, message)
        Snapshot.withMutableSnapshot {
            state.statusMessage = result.message
            if (result.ok) {
                if (window.message == message) window.message = ""
                window.error = null
                window.syncRows(commitRows(session))
            } else {
                window.error = result.message
            }
        }
        if (result.ok) session.git.refresh()
    }
}

private fun refuseCommit(state: AppState, window: CommitWindowState, message: String) {
    window.error = message
    state.statusMessage = message
}

internal fun notRepositoryMessage(session: ProjectSession): String =
    "Not a Git repository: ${session.project.root}"
