package stride.app.editor

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorScrollStateTest {
    @Test
    fun `sub-pixel wheel deltas accumulate instead of being dropped`() {
        val state = EditorScrollState()
        state.updateBounds(contentSize = 2_000, viewportSize = 500)

        var consumed = 0f
        var previous = 0
        repeat(5) {
            consumed += state.dispatchRawDelta(0.4f)
            assertTrue(state.value >= previous, "value went backwards to ${state.value}")
            assertTrue(state.value - previous <= 1, "one 0.4 px delta moved ${state.value - previous} px")
            previous = state.value
        }
        assertEquals(2, state.value, "five 0.4 px deltas should travel 2 px")
        assertTrue(abs(consumed - 2f) < 1e-4f, "consumed $consumed")

        repeat(2) { state.dispatchRawDelta(0.6f) }
        assertEquals(3, state.value, "two 0.6 px deltas add exactly one more pixel")
    }

    @Test
    fun `a delta clamped at the edge is not kept as a remainder`() {
        val state = EditorScrollState()
        state.updateBounds(contentSize = 600, viewportSize = 500)
        assertEquals(100, state.maxValue)

        state.dispatchRawDelta(60f)
        state.dispatchRawDelta(100f)
        assertEquals(100, state.value, "the second delta stops at maxValue")

        // The overflow must not fight a reverse gesture.
        state.dispatchRawDelta(-10f)
        assertEquals(90, state.value)
    }

    @Test
    fun `the adapter excludes the chrome inset from the track`() {
        val state = EditorScrollState()
        state.updateBounds(contentSize = 2_000, viewportSize = 500)
        val adapter = EditorScrollbarAdapter(state, insetPx = 84f)

        // The padded track is 84 px shorter than the viewport, so the adapter must not add
        // those insets to the scroll range: the thumb has to reach the end at maxValue.
        assertEquals(1_500f, adapter.maxScrollOffset(containerSize = 416))
    }

    @Test
    fun `the scroll layer switches at the threshold with hysteresis`() {
        assertFalse(shouldLayerScroll(lineCount = 100, currentlyLayered = false))
        assertFalse(shouldLayerScroll(lineCount = LAYERED_SCROLL_ENTER_LINES - 1, currentlyLayered = false))
        assertTrue(shouldLayerScroll(lineCount = LAYERED_SCROLL_ENTER_LINES, currentlyLayered = false))

        // Between the two thresholds the current mode is kept, so typing there does not flip it.
        assertTrue(shouldLayerScroll(lineCount = LAYERED_SCROLL_EXIT_LINES, currentlyLayered = true))
        assertTrue(
            shouldLayerScroll(lineCount = LAYERED_SCROLL_ENTER_LINES - 1, currentlyLayered = true),
        )
        assertFalse(shouldLayerScroll(lineCount = LAYERED_SCROLL_EXIT_LINES - 1, currentlyLayered = true))
    }
}
