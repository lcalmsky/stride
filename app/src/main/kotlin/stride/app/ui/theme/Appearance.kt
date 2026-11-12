package stride.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The system appearance the window follows. */
enum class Appearance { DARK, LIGHT }

/**
 * The effective appearance, read by every colour token. It is Compose state, so switching it
 * re-themes the window in place instead of rebuilding any editor document or scroll state.
 */
object AppearanceState {
    var appearance: Appearance by mutableStateOf(Appearance.DARK)
        private set

    fun set(value: Appearance) {
        appearance = value
    }
}
