package stride.app.vcs

import androidx.compose.runtime.Composable
import stride.app.AppState

/** The VCS Operations quick list's contents; lane V replaces this. */
class VcsPopupRequest

fun openVcsPopup(state: AppState): Boolean {
    state.statusMessage = "VCS Operations is not implemented yet"
    return true
}

@Composable
fun VcsPopup(request: VcsPopupRequest, state: AppState, onClose: () -> Unit) {
}
