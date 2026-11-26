package stride.app.vcs

import androidx.compose.runtime.snapshots.Snapshot
import java.awt.EventQueue
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import stride.app.AppState
import stride.app.ProjectSession

/** Distinguishes one opening of the branches popup from the next, so a late read lands nowhere. */
private val branchesTokens = AtomicLong()

internal fun nextBranchesToken(): Long = branchesTokens.incrementAndGet()

private const val NOTHING_TO_BRANCH_FROM = "Nothing to branch from: the branch has no commits yet"

/**
 * Opens the branches popup on the list page. A project without a snapshot yet is refreshed once
 * first, because until then it cannot tell a new project from a folder outside a repository.
 */
fun openBranches(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    state.statusMessage = null
    val snapshot = git.snapshot
    if (snapshot.error != null) {
        state.statusMessage = snapshot.error
        return true
    }
    if (snapshot.repository != null) {
        openList(state, session)
        return true
    }
    git.launch {
        val fresh = git.refreshNow()
        var token: Long? = null
        Snapshot.withMutableSnapshot {
            if (fresh.repository == null) {
                state.statusMessage = fresh.error ?: notRepositoryMessage(session)
            } else {
                val request = BranchesRequest(token = nextBranchesToken())
                state.branchesPopup = request
                token = request.token
            }
        }
        token?.let { loadBranches(state, session, it) }
    }
    return true
}

/** Opens the popup directly on the name page with HEAD as the start, as Find Action does. */
fun openNewBranch(state: AppState): Boolean {
    val session = state.session ?: return false
    val git = session.git
    state.statusMessage = null
    val snapshot = git.snapshot
    if (snapshot.error != null) {
        state.statusMessage = snapshot.error
        return true
    }
    if (snapshot.repository != null) {
        openNamePage(state, session, snapshot.branch?.head)
        return true
    }
    git.launch {
        val fresh = git.refreshNow()
        var token: Long? = null
        Snapshot.withMutableSnapshot {
            if (fresh.repository == null) {
                state.statusMessage = fresh.error ?: notRepositoryMessage(session)
            } else if (fresh.branch?.head == null) {
                state.statusMessage = NOTHING_TO_BRANCH_FROM
            } else {
                val request = BranchesRequest(
                    token = nextBranchesToken(),
                    page = BranchesPage.NAME,
                    namePage = BranchesNamePage.New(null, "HEAD"),
                    openedDirectly = true,
                )
                state.branchesPopup = request
                token = request.token
            }
        }
        token?.let { loadBranches(state, session, it) }
    }
    return true
}

private fun openList(state: AppState, session: ProjectSession) {
    val request = BranchesRequest(token = nextBranchesToken())
    state.branchesPopup = request
    loadBranches(state, session, request.token)
}

private fun openNamePage(state: AppState, session: ProjectSession, head: String?) {
    if (head == null) {
        state.statusMessage = NOTHING_TO_BRANCH_FROM
        return
    }
    val request = BranchesRequest(
        token = nextBranchesToken(),
        page = BranchesPage.NAME,
        namePage = BranchesNamePage.New(null, "HEAD"),
        openedDirectly = true,
    )
    state.branchesPopup = request
    loadBranches(state, session, request.token)
}

/** Reads the branch list into the popup that carries [token], and into no other one. */
internal fun loadBranches(state: AppState, session: ProjectSession, token: Long) {
    session.git.launch {
        val list = session.git.branches()
        Snapshot.withMutableSnapshot {
            val current = state.branchesPopup
            if (current != null && current.token == token) {
                state.branchesPopup = current.copy(list = list)
            }
        }
    }
}

/**
 * Saves the project's unsaved editors on the UI thread, as V19 step 1 does before a checkout.
 * Returns the reason a save failed, or `null` when every editor was saved.
 */
internal fun saveProjectEditors(session: ProjectSession): String? {
    for (editor in saveableEditors(session)) {
        try {
            editor.save()
        } catch (e: IOException) {
            return e.message ?: "cannot save ${editor.file.name}"
        }
    }
    return null
}

/** V19: save, checkout, and reload the editors whatever the result; the kept text goes in the message. */
internal fun runCheckout(state: AppState, target: BranchTarget) {
    val session = state.session ?: return
    val git = session.git
    saveProjectEditors(session)?.let {
        state.statusMessage = "Checkout failed: $it"
        return
    }
    git.launch {
        val result = git.checkout(target)
        val kept = reloadEditors(session)?.keptUnsaved ?: 0
        Snapshot.withMutableSnapshot { state.statusMessage = withReloadTail(result, kept) }
    }
}

/**
 * V20's two forms of a new branch. Only a checked-out branch from another start is a checkout:
 * it saves first, and from HEAD or with `Checkout branch` off nothing on disk is written.
 */
internal fun runCreateBranch(
    state: AppState,
    name: String,
    start: BranchTarget?,
    checkout: Boolean,
) {
    val session = state.session ?: return
    val git = session.git
    if (checkout && start != null) {
        saveProjectEditors(session)?.let {
            state.statusMessage = "New branch failed: $it"
            return
        }
    }
    git.launch {
        val result = git.createBranch(name, start, checkout)
        if (!checkout) {
            Snapshot.withMutableSnapshot { state.statusMessage = result.message }
            return@launch
        }
        val kept = reloadEditors(session)?.keptUnsaved ?: 0
        Snapshot.withMutableSnapshot { state.statusMessage = withReloadTail(result, kept) }
    }
}

internal fun runRenameBranch(state: AppState, old: String, new: String) {
    val session = state.session ?: return
    session.git.launch {
        val result = session.git.renameBranch(old, new)
        Snapshot.withMutableSnapshot { state.statusMessage = result.message }
    }
}

/**
 * The menu's Delete: a merged branch is deleted at once, an unmerged one turns this popup into
 * its delete page, and a refusal stays on the status bar. [onClose] runs on the UI thread.
 */
internal fun runDeleteBranch(state: AppState, name: String, onClose: () -> Unit) {
    val session = state.session ?: return
    val git = session.git
    val token = state.branchesPopup?.token
    git.launch {
        when (val plan = git.deletePlan(name)) {
            is DeletePlan.Refused -> Snapshot.withMutableSnapshot { state.statusMessage = plan.message }
            is DeletePlan.Merged -> {
                runCatching { EventQueue.invokeAndWait { onClose() } }
                val result = git.deleteBranch(plan.name, plan.id, force = false)
                Snapshot.withMutableSnapshot { state.statusMessage = result.message }
            }
            is DeletePlan.Unmerged -> Snapshot.withMutableSnapshot {
                val current = state.branchesPopup
                if (current != null && current.token == token) {
                    state.branchesPopup = current.copy(
                        page = BranchesPage.DELETE,
                        delete = plan,
                        selected = 0,
                    )
                }
            }
        }
    }
}

/** The delete page's `↩`: force-deletes the branch whose id the page showed. */
internal fun runForceDeleteBranch(state: AppState, name: String, id: String) {
    val session = state.session ?: return
    session.git.launch {
        val result = session.git.deleteBranch(name, id, force = true)
        Snapshot.withMutableSnapshot { state.statusMessage = result.message }
    }
}

private fun withReloadTail(result: GitWriteResult, kept: Int): String =
    if (result.ok && kept > 0) {
        "${result.message}; $kept editors with unsaved text were not reloaded"
    } else {
        result.message
    }
