package stride.app.vcs

import stride.app.AppState

/** The Commit window, push and diff entry points; lane K replaces these bodies. */
fun openCommit(state: AppState): Boolean {
    state.statusMessage = "Commit is not implemented yet"
    return true
}

fun openPush(state: AppState): Boolean {
    state.statusMessage = "Push is not implemented yet"
    return true
}

fun showSelectedDiff(state: AppState): Boolean {
    state.statusMessage = "Show Diff is not implemented yet"
    return true
}
