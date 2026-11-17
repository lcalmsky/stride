package stride.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The native popup plate model: [DesignTokens.popupPlate] over the two desktop extremes, with
 * the selection and hover pills on top of it. The rendered check is `PopupNativeLegibilityTest`;
 * this states the token maths that holds for every popup.
 */
class PopupPlateContrastTest {
    private val desktops = listOf("white" to Color.White, "black" to Color.Black)

    private val textRoles = listOf(
        "text" to { palette: Palette -> palette.text },
        "textOnBusyGlass" to { palette: Palette -> palette.textOnBusyGlass },
        "textSecondaryOnGlass" to { palette: Palette -> palette.textSecondaryOnGlass },
        "accent2" to { palette: Palette -> palette.accent2 },
        "neonAmber" to { palette: Palette -> palette.neonAmber },
        "accentOnPopupPlate" to { palette: Palette -> accentOnPopupPlate(palette) },
    )

    private fun plate(palette: Palette): Color = palette.glassBase.copy(alpha = POPUP_PLATE_ALPHA)

    private fun pill(palette: Palette, over: Color, scale: Float = 1f): Color {
        val lift = palette.selectionPillLift.copy(alpha = palette.selectionPillLift.alpha * scale)
        val tint = palette.selectionPillTint.copy(alpha = palette.selectionPillTint.alpha * scale)
        return blendOver(tint, blendOver(lift, over))
    }

    @Test
    fun `every popup text role clears 4_5 on the plate over either desktop`() {
        for ((appearance, palette) in palettes()) {
            for ((desktopName, desktop) in desktops) {
                val surface = blendOver(plate(palette), desktop)
                for ((role, color) in textRoles) {
                    val ratio = contrastRatio(color(palette), surface)
                    assertTrue(
                        ratio >= DesignTokens.contrastText,
                        "$appearance $role on the plate over $desktopName = %.3f".format(ratio),
                    )
                }
            }
        }
    }

    @Test
    fun `the selected label clears 4_5 on the pill over the plate`() {
        for ((appearance, palette) in palettes()) {
            for ((desktopName, desktop) in desktops) {
                val surface = pill(palette, blendOver(plate(palette), desktop))
                for ((role, color) in listOf(
                    "selectedText" to palette.selectedText,
                    "textOnBusyGlass" to palette.textOnBusyGlass,
                )) {
                    val ratio = contrastRatio(color, surface)
                    assertTrue(
                        ratio >= DesignTokens.contrastText,
                        "$appearance $role on the pill over the $desktopName desktop = %.3f".format(ratio),
                    )
                }
            }
        }
    }

    @Test
    fun `the hovered label clears 4_5 on the hover pill over the plate`() {
        for ((appearance, palette) in palettes()) {
            for ((desktopName, desktop) in desktops) {
                val surface = pill(palette, blendOver(plate(palette), desktop), HOVER_PILL_ALPHA)
                val ratio = contrastRatio(palette.text, surface)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$appearance text on the hover pill over the $desktopName desktop = %.3f".format(ratio),
                )
            }
        }
    }

    @Test
    fun `the plate floor is the desktop that is worse for the palette's polarity`() {
        for ((appearance, palette) in palettes()) {
            val worst = desktops.minBy { (_, desktop) ->
                textRoles.minOf { (_, color) -> contrastRatio(color(palette), blendOver(plate(palette), desktop)) }
            }
            val floor = popupPlateFloor(palette)
            assertEquals(
                blendOver(plate(palette), worst.second),
                floor,
                "$appearance: the floor is not the plate over the ${worst.first} desktop",
            )
        }
    }

    private fun palettes(): List<Pair<Appearance, Palette>> =
        listOf(Appearance.DARK to DarkPalette, Appearance.LIGHT to LightPalette)
}
