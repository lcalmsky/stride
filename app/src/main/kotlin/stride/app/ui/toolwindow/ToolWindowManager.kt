package stride.app.ui.toolwindow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.keys.KeyContext

/** One bottom-docked tool window: a tab title, the focus context its panel reports, and its content. */
class ToolWindow(
    val id: String,
    val title: String,
    val context: KeyContext,
    val content: @Composable (Modifier) -> Unit,
)

/** The registered tool windows and the state of the single bottom panel that shows them. */
class ToolWindowManager {
    private val registered = mutableStateListOf<ToolWindow>()

    val windows: List<ToolWindow> get() = registered

    var activeId: String? by mutableStateOf(null)

    var visible: Boolean by mutableStateOf(false)

    var height: Dp
        get() = heightState
        set(value) {
            heightState = value.coerceAtLeast(MIN_HEIGHT)
        }

    var focusRequests: Int by mutableStateOf(0)

    private var heightState by mutableStateOf(DEFAULT_HEIGHT)

    /** Registers [window], replacing a window with the same id. */
    fun register(window: ToolWindow) {
        val index = registered.indexOfFirst { it.id == window.id }
        if (index >= 0) registered[index] = window else registered.add(window)
    }

    fun activate(id: String) {
        visible = true
        activeId = id
        focusRequests++
    }

    /** The ActivateToolWindow rule: open, hide only when already active and focused, else ask for focus. */
    fun toggle(id: String, focused: Boolean) {
        when {
            !visible || activeId != id -> activate(id)
            focused -> hide()
            else -> focusRequests++
        }
    }

    fun hide() {
        visible = false
    }

    fun active(): ToolWindow? = registered.firstOrNull { it.id == activeId }

    companion object {
        val DEFAULT_HEIGHT: Dp = 240.dp
        val MIN_HEIGHT: Dp = 120.dp
    }
}
