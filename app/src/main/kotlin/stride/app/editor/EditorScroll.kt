package stride.app.editor

import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    /**
     * Whether the viewport places its content in a render layer. A layered editor cannot be
     * sampled by the chrome bar filters, so the bars fall back to the cached glass slab.
     */
    var layered: Boolean by mutableStateOf(false)
        internal set

    private var pendingDelta = 0f

    fun dispatchRawDelta(delta: Float): Float {
        pendingDelta += delta
        val whole = pendingDelta.roundToInt()
        if (whole == 0) return 0f
        val before = value
        val next = (before + whole).coerceIn(0, maxValue)
        val applied = next - before
        // A clamped delta is dropped rather than kept as a remainder that would fight the
        // next gesture in the opposite direction.
        pendingDelta = if (applied == whole) pendingDelta - whole else 0f
        if (applied != 0) value = next
        return applied.toFloat()
    }

    suspend fun scrollTo(target: Int) {
        pendingDelta = 0f
        value = target.coerceIn(0, maxValue)
    }

    internal fun updateBounds(contentSize: Int, viewportSize: Int) {
        this.viewportSize = viewportSize.coerceAtLeast(0)
        maxValue = (contentSize - viewportSize).coerceAtLeast(0)
        if (value > maxValue) {
            value = maxValue
            pendingDelta = 0f
        }
    }
}

/**
 * The render-layer policy for scrolling. Below [LAYERED_SCROLL_ENTER_LINES] the editor translates
 * its content without a layer, so a chrome bar's backdrop filter can sample the code under it; at
 * or above it the per-frame cost of redrawing the whole document passes the frame budget, so the
 * content moves in a layer and the bars stop showing code through. The range has hysteresis: once
 * layered, a document must drop below [LAYERED_SCROLL_EXIT_LINES] before the editor returns to the
 * sampling path, so editing around the threshold does not flip the mode on every keystroke.
 */
const val LAYERED_SCROLL_ENTER_LINES = 1_000
const val LAYERED_SCROLL_EXIT_LINES = 800

internal fun shouldLayerScroll(lineCount: Int, currentlyLayered: Boolean): Boolean =
    if (currentlyLayered) {
        lineCount >= LAYERED_SCROLL_EXIT_LINES
    } else {
        lineCount >= LAYERED_SCROLL_ENTER_LINES
    }

/** The selected editor's vertical scroll state, so the chrome bars know when code is under them. */
val LocalEditorScrollState = staticCompositionLocalOf<EditorScrollState?> { null }

/**
 * A [ScrollbarAdapter] over [EditorScrollState]. [insetPx] is the chrome inset removed from the
 * scrollbar's track; without it the track would advertise more scroll than the state has.
 */
class EditorScrollbarAdapter(
    private val state: EditorScrollState,
    private val insetPx: Float = 0f,
) : ScrollbarAdapter {
    override val scrollOffset: Float get() = state.value.toFloat()

    override fun maxScrollOffset(containerSize: Int): Float =
        (state.maxValue + state.viewportSize - insetPx - containerSize).coerceAtLeast(0f)

    override suspend fun scrollTo(containerSize: Int, scrollOffset: Float) {
        state.scrollTo(scrollOffset.roundToInt())
    }
}

/**
 * A vertical viewport that translates its content by [state]. Without a layer [content] is
 * re-issued into the canvas on every offset change, which is what lets a later backdrop filter
 * sample the code under a bar; with [layered] set the placement uses a render layer instead, which
 * costs a constant per scroll step but cannot be sampled.
 */
@Composable
fun VerticalScrollViewport(
    state: EditorScrollState,
    modifier: Modifier = Modifier,
    layered: Boolean = false,
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
        state.layered = layered
        layout(constraints.maxWidth, viewport) {
            if (layered) {
                placeable.placeRelativeWithLayer(0, -state.value)
            } else {
                placeable.place(0, -state.value)
            }
        }
    }
}

/** The horizontal counterpart of [VerticalScrollViewport]; see it for the layer policy. */
@Composable
fun HorizontalScrollViewport(
    state: EditorScrollState,
    modifier: Modifier = Modifier,
    layered: Boolean = false,
    clipStart: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val scrollable = rememberScrollableState { delta -> state.dispatchRawDelta(delta) }
    Layout(
        content = content,
        modifier = modifier
            .drawWithContent {
                // The text is clipped at the gutter's right edge: horizontally scrolled glyphs must
                // never show left of it.
                clipRect(clipStart.toPx(), 0f, size.width, size.height) {
                    this@drawWithContent.drawContent()
                }
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
            if (layered) {
                placeable.placeRelativeWithLayer(-state.value, 0)
            } else {
                placeable.place(-state.value, 0)
            }
        }
    }
}

/** Adapts a [EditorScrollState] to the scrollbar composables. */
@Composable
fun rememberEditorScrollbarAdapter(state: EditorScrollState, insetPx: Float = 0f): ScrollbarAdapter {
    return remember(state, insetPx) { EditorScrollbarAdapter(state, insetPx) }
}
