package stride.app.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pill's strength through a hover↔selection handover. The app fades the selection over
 * `DesignTokens.neonFadeMillis`, so these frame values are what the drawing sees: a hovered row
 * keeps the hover pill until the selection fade takes over, and keeps it again as the fade ends.
 */
class SelectionPillAlphaTest {
    @Test
    fun `a hovered row that gains selection never drops below the hover pill`() {
        forEachFade(from = 0f, to = 1f) { selection ->
            val alpha = selectionPillAlpha(selection, fillAlpha = 1f)
            assertTrue(
                alpha >= HOVER_PILL_ALPHA,
                "a click on a hovered row dipped to %.3f at selection %.3f".format(alpha, selection),
            )
        }
    }

    @Test
    fun `a hovered row that loses selection never drops below the hover pill`() {
        forEachFade(from = 1f, to = 0f) { selection ->
            val alpha = selectionPillAlpha(selection, fillAlpha = 1f)
            assertTrue(
                alpha >= HOVER_PILL_ALPHA,
                "moving selection off a hovered row dipped to %.3f at selection %.3f"
                    .format(alpha, selection),
            )
        }
    }

    @Test
    fun `an unhovered row follows the selection fade alone`() {
        forEachFade(from = 0f, to = 1f) { selection ->
            assertEquals(
                selection,
                selectionPillAlpha(selection, fillAlpha = 0f),
                1e-6f,
                "an unhovered row must not hold a hover floor at selection %.3f".format(selection),
            )
        }
    }

    @Test
    fun `the stronger of the two strengths wins`() {
        assertEquals(HOVER_PILL_ALPHA, selectionPillAlpha(0f, fillAlpha = 1f), 1e-6f)
        assertEquals(HOVER_PILL_ALPHA, selectionPillAlpha(0.1f, fillAlpha = 1f), 1e-6f)
        assertEquals(1f, selectionPillAlpha(1f, fillAlpha = 1f), 1e-6f)
        assertEquals(0.5f, selectionPillAlpha(0.5f, fillAlpha = 1f), 1e-6f)
    }

    private fun forEachFade(from: Float, to: Float, block: (Float) -> Unit) {
        repeat(FRAMES) { frame ->
            block(from + (to - from) * frame / (FRAMES - 1).toFloat())
        }
    }

    private companion object {
        const val FRAMES = 16
    }
}
