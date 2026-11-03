package stride.app.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/** The terminal tool window content. Starts the project's shell the first time it is shown. */
@Composable
fun TerminalToolWindow(session: TerminalSession, modifier: Modifier = Modifier, focusRequests: Int = 0) {
    LaunchedEffect(Unit) {
        session.startDefault()
    }
    TerminalView(session, modifier, focusRequests)
}
