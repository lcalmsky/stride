package stride.app.editor

import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Editor scroll position in device pixels. The viewports measure the document and keep
 * [maxValue] current; the value is what the caret, the scrollbars and the chrome insets read.
 */
class EditorScrollState {
    var value: Int by mutableIntStateOf(0)
        private set
    var maxValue: Int by mutableIntStateOf(0)
        private set
    var viewportSize: Int by mutableIntStateOf(0)
        private set

    fun dispatchRawDelta(delta: Float): Float {
        val before = value
        val next = (before + delta).roundToInt().coerceIn(0, maxValue)
        if (next != before) value = next
        return (next - before).toFloat()
    }

    suspend fun scrollTo(target: Int) {
        value = target.coerceIn(0, maxValue)
    }

    internal fun updateBounds(contentSize: Int, viewportSize: Int) {
        this.viewportSize = viewportSize.coerceAtLeast(0)
        maxValue = (contentSize - viewportSize).coerceAtLeast(0)
        if (value > maxValue) value = maxValue
    }
}

/** The selected editor's vertical scroll state, so the chrome bars know when code is under them. */
val LocalEditorScrollState = staticCompositionLocalOf<EditorScrollState?> { null }

/** A [ScrollbarAdapter] over [EditorScrollState]. */
class EditorScrollbarAdapter(private val state: EditorScrollState) : ScrollbarAdapter {
    override val scrollOffset: Float get() = state.value.toFloat()

    override fun maxScrollOffset(containerSize: Int): Float =
        ((state.maxValue + state.viewportSize) - containerSize).coerceAtLeast(0).toFloat()

    override suspend fun scrollTo(containerSize: Int, scrollOffset: Float) {
        state.scrollTo(scrollOffset.roundToInt())
    }
}

/**
 * A vertical viewport that translates its content by [state] itself instead of placing it in a
 * graphics layer: `verticalScroll` uses a layer, and a later backdrop filter cannot sample it.
 */
@Composable
fun VerticalScrollViewport(
    state: EditorScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scrollable = rememberScrollableState { delta -> state.dispatchRawDelta(delta) }
    Layout(
        content = content,
        modifier = modifier
            .drawWithContent {
                clipRect(0f, 0f, size.width, size.height) { this@drawWithContent.drawContent() }
            }
            .scrollable(scrollable, Orientation.Vertical)
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange(
                    value = { state.value.toFloat() },
                    maxValue = { state.maxValue.toFloat() },
                )
            },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val viewport = constraints.maxHeight
        state.updateBounds(contentSize = placeable.height, viewportSize = viewport)
        layout(constraints.maxWidth, viewport) {
            placeable.place(0, -state.value)
        }
    }
}

/** The horizontal counterpart of [VerticalScrollViewport]; see it for why there is no layer. */
@Composable
fun HorizontalScrollViewport(
    state: EditorScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scrollable = rememberScrollableState { delta -> state.dispatchRawDelta(delta) }
    Layout(
        content = content,
        modifier = modifier
            .drawWithContent {
                clipRect(0f, 0f, size.width, size.height) { this@drawWithContent.drawContent() }
            }
            .scrollable(scrollable, Orientation.Horizontal)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(
                    value = { state.value.toFloat() },
                    maxValue = { state.maxValue.toFloat() },
                )
            },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
        )
        state.updateBounds(contentSize = placeable.width, viewportSize = constraints.maxWidth)
        layout(constraints.maxWidth, placeable.height) {
            placeable.place(-state.value, 0)
        }
    }
}

/** Adapts a [EditorScrollState] to the scrollbar composables. */
@Composable
fun rememberEditorScrollbarAdapter(state: EditorScrollState): ScrollbarAdapter {
    return remember(state) { EditorScrollbarAdapter(state) }
}
