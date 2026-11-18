package stride.app.vcs

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.ui.theme.DarkPalette
import stride.app.ui.theme.LightPalette
import stride.app.ui.theme.POPUP_PLATE_ALPHA
import stride.app.ui.theme.Palette
import stride.app.ui.theme.blendOver
import stride.app.ui.theme.contrastRatio

/**
 * The diff popup draws its code text in the palette's [Palette.text] over the added and removed
 * tints, which themselves sit on the popup plate over either desktop. Every combination must keep
 * the 4.5:1 of popup text; the rendered side is registered in `PopupNativeLegibilityTest`.
 */
class DiffTintTest {

    @Test
    fun `code text clears 4_5 on the added tint over both desktops and palettes`() = assertTint(
        tint = { it.vcsAdded },
        alpha = DIFF_ADDED_TINT_ALPHA,
    )

    @Test
    fun `code text clears 4_5 on the removed tint over both desktops and palettes`() = assertTint(
        tint = { it.vcsDeleted },
        alpha = DIFF_DELETED_TINT_ALPHA,
    )

    private fun assertTint(tint: (Palette) -> Color, alpha: Float) {
        for ((appearance, palette) in listOf("dark" to DarkPalette, "light" to LightPalette)) {
            for ((desktopName, desktop) in listOf("white" to Color.White, "black" to Color.Black)) {
                val plate = blendOver(palette.glassBase.copy(alpha = POPUP_PLATE_ALPHA), desktop)
                val surface = blendOver(tint(palette).copy(alpha = alpha), plate)
                val ratio = contrastRatio(palette.text, surface)
                assertTrue(
                    ratio >= 4.5,
                    "$appearance over $desktopName: text on the tint = %.3f".format(ratio),
                )
            }
        }
    }
}
