package stride.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesignTokensContrastTest {
    @Test
    fun `contrast helpers match the WCAG examples`() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.001)
        assertEquals(4.48, contrastRatio(Color(0xFF777777), Color.White), 0.005)
    }

    @Test
    fun `relative luminance switches formula at the 0_04045 threshold`() {
        // An 8-bit channel steps from 10/255 (below 0.04045) to 11/255 (above it).
        val below = 10.0 / 255.0
        assertEquals(below / 12.92, relativeLuminance(Color(10, 10, 10)), 1e-9)
        val above = 11.0 / 255.0
        assertEquals(((above + 0.055) / 1.055).pow(2.4), relativeLuminance(Color(11, 11, 11)), 1e-9)
    }

    @Test
    fun `blend over follows source over alpha compositing`() {
        val blended = blendOver(Color(1f, 0f, 0f, 0.5f), Color(0f, 0f, 1f, 1f))
        assertEquals(128f / 255f, blended.red, 1e-6f)
        assertEquals(0f, blended.green, 1e-6f)
        assertEquals(127f / 255f, blended.blue, 1e-6f)
        assertEquals(1f, blended.alpha, 1e-6f)
    }

    @Test
    fun `text tokens clear 4_5 on the worst strong glass`() {
        for ((name, color) in TEXT_TOKENS) {
            val ratio = contrastRatio(color, worstStrongGlass)
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "$name on worstStrongGlass = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        }
    }

    @Test
    fun `neon pink stays off only large text on glass`() {
        val onPanel = contrastRatio(DesignTokens.neonPink, worstPanelGlass)
        val onStrong = contrastRatio(DesignTokens.neonPink, worstStrongGlass)
        assertTrue(onPanel >= DesignTokens.contrastLarge, "neonPink on worstPanelGlass = %.3f".format(onPanel))
        assertTrue(onStrong < DesignTokens.contrastLarge, "neonPink on worstStrongGlass = %.3f".format(onStrong))
    }

    @Test
    fun `focus and selection markers clear the UI contrast`() {
        assertEquals(DesignTokens.accent2, DesignTokens.selectionBar)
        for ((name, color) in listOf("accent2" to DesignTokens.accent2, "selectionBar" to DesignTokens.selectionBar)) {
            val ratio = contrastRatio(color, worstStrongGlass)
            assertTrue(ratio >= DesignTokens.contrastUi, "$name on worstStrongGlass = %.3f".format(ratio))
        }
    }

    @Test
    fun `excluded tokens are absent and stay under body text contrast`() {
        val excluded = mapOf(
            "subtle" to DesignTokens.subtle,
            "accent" to DesignTokens.accent,
            "muted" to DesignTokens.muted,
        )
        for ((name, color) in excluded) {
            assertFalse(name in TEXT_TOKENS.keys, "$name must not be a body text token")
            val ratio = contrastRatio(color, worstStrongGlass)
            assertTrue(ratio < DesignTokens.contrastText, "$name on worstStrongGlass = %.3f".format(ratio))
        }
    }

    @Test
    fun `text secondary on glass is the first muted to text step that clears 4_5`() {
        val found = firstStepClearing(worstStrongGlass)
        assertEquals(found.color, DesignTokens.textSecondaryOnGlass, "t=${found.t} hex=#%06X".format(found.color.value.toLong() and 0xFFFFFF))
        val ratio = contrastRatio(DesignTokens.textSecondaryOnGlass, worstStrongGlass)
        assertTrue(ratio >= DesignTokens.contrastText, "textSecondaryOnGlass = %.3f".format(ratio))
    }

    @Test
    fun `mesh orbs never exceed the worst single orb luminance`() {
        val width = 1280
        val height = 820
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MeshBackground(Modifier.fillMaxSize()) {}
        }
        val pixels = IntArray(width * height)
        var image: org.jetbrains.skia.Image? = null
        try {
            scene.render(0L).close()
            image = scene.render(16_000_000L)
            image.toComposeImageBitmap().readPixels(pixels)
        } finally {
            image?.close()
            scene.close()
        }
        val brightest = pixels.maxOf { relativeLuminance(Color(it)) }
        val limit = relativeLuminance(worstMesh) + 0.002
        assertTrue(
            brightest <= limit,
            "rendered max luminance %.6f > worst orb %.6f + 0.002".format(brightest, relativeLuminance(worstMesh)),
        )
    }

    private data class Step(val t: Double, val color: Color)

    private fun firstStepClearing(background: Color): Step {
        for (index in 0..100) {
            val t = index / 100.0
            val lerped = Color(
                (DesignTokens.muted.red + (DesignTokens.text.red - DesignTokens.muted.red) * t).toFloat(),
                (DesignTokens.muted.green + (DesignTokens.text.green - DesignTokens.muted.green) * t).toFloat(),
                (DesignTokens.muted.blue + (DesignTokens.text.blue - DesignTokens.muted.blue) * t).toFloat(),
                alpha = 1f,
            )
            val rounded = Color(
                (lerped.red * 255f).roundToInt(),
                (lerped.green * 255f).roundToInt(),
                (lerped.blue * 255f).roundToInt(),
            )
            if (contrastRatio(rounded, background) >= DesignTokens.contrastText) {
                return Step(t, rounded)
            }
        }
        error("no muted→text step reaches the text contrast")
    }

    companion object {
        /**
         * Body text tokens. Excludes `subtle` and `accent` (below 4.5) and `neonPink` (large text or
         * graphics only). Decorative elements are never contrast assertions: `glassBorderTop`,
         * `glassBorderSide`, `glassBorderBottom` and every `*Glow` are decoration under WCAG 1.4.11.
         */
        private val TEXT_TOKENS = linkedMapOf(
            "text" to DesignTokens.text,
            "textSecondaryOnGlass" to DesignTokens.textSecondaryOnGlass,
            "accent2" to DesignTokens.accent2,
            "neonLime" to DesignTokens.neonLime,
            "neonAmber" to DesignTokens.neonAmber,
        )

        private val meshOrbs = listOf(
            DesignTokens.accent,
            DesignTokens.accent2,
            DesignTokens.neonPink,
        )

        /** The brightest of the three orbs blended over `background` (WCAG F83, brightest spot). */
        private val worstMesh: Color = meshOrbs
            .map { blendOver(it.copy(alpha = DesignTokens.meshOrbAlpha), DesignTokens.background) }
            .maxBy { relativeLuminance(it) }

        private val worstStrongGlass: Color = blendOver(DesignTokens.glassFillStrong, worstMesh)
        private val worstPanelGlass: Color = blendOver(DesignTokens.glassFill, worstMesh)
    }
}
