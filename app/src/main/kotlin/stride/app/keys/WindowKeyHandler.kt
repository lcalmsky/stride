package stride.app.keys

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType as ComposeKeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import stride.app.AppState
import java.nio.file.Path

/**
 * Handles a window preview key event: routes it through [router] and dispatches the action.
 * Returns `true` when the event is consumed. [chooseFolder] is the window-side folder
 * chooser; it is a parameter so tests can run without a real dialog.
 */
fun handleWindowKeyEvent(
    event: KeyEvent,
    router: KeyRouter,
    state: AppState,
    chooseFolder: () -> Path?,
): Boolean {
    val type = when (event.type) {
        ComposeKeyEventType.KeyDown -> KeyEventType.DOWN
        ComposeKeyEventType.KeyUp -> KeyEventType.UP
        else -> null
    }
    if (type == null) {
        return false
    }
    val action = router.route(
        type = type,
        key = event.key,
        meta = event.isMetaPressed,
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
        focus = state.focus,
    ) ?: return false
    return dispatchAction(action, state, chooseFolder)
}
