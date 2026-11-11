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
    fun `neon pink clears only large text on every glass fill`() {
        val onPanel = contrastRatio(DesignTokens.neonPink, worstPanelGlass)
        val onStrong = contrastRatio(DesignTokens.neonPink, worstStrongGlass)
        assertTrue(onPanel >= DesignTokens.contrastLarge, "neonPink on worstPanelGlass = %.3f".format(onPanel))
        assertTrue(onStrong >= DesignTokens.contrastLarge, "neonPink on worstStrongGlass = %.3f".format(onStrong))
        assertTrue(onStrong < DesignTokens.contrastText, "neonPink on worstStrongGlass = %.3f".format(onStrong))
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
    fun `popup text on the unshadowed thin glass states its floor`() {
        // The worst read of the thin glass: a scrimmed white backdrop under glassBase +
        // glassBackdropMix. Primary text must still clear 4.5 without the shadow; the secondary
        // token cannot, which is why every popup label and input carries chromeTextStyle.
        val backdrop = blendOver(DesignTokens.scrim, Color.White)
        val surface = Color(
            (DesignTokens.glassBase.red + DesignTokens.glassBackdropMix * backdrop.red).coerceIn(0f, 1f),
            (DesignTokens.glassBase.green + DesignTokens.glassBackdropMix * backdrop.green).coerceIn(0f, 1f),
            (DesignTokens.glassBase.blue + DesignTokens.glassBackdropMix * backdrop.blue).coerceIn(0f, 1f),
        )
        val text = contrastRatio(DesignTokens.text, surface)
        val selected = contrastRatio(DesignTokens.selectedText, surface)
        val secondary = contrastRatio(DesignTokens.textSecondaryOnGlass, surface)
        System.err.println(
            "popup-floor text=%.3f selected=%.3f secondary=%.3f".format(text, selected, secondary),
        )
        // Floors without the shadow: only white still clears 4.5; body text is just below it and
        // the secondary token is far below. The shadowed assertions above are the 4.5 guarantee.
        assertTrue(text >= 3.85, "text on the unshadowed thin glass = %.3f, below its 3.85 floor".format(text))
        assertTrue(
            selected >= DesignTokens.contrastText,
            "selectedText on the unshadowed thin glass = %.3f".format(selected),
        )
        assertTrue(
            secondary >= 1.65,
            "textSecondaryOnGlass on the unshadowed thin glass = %.3f, below its 1.65 floor".format(secondary),
        )
    }

    @Test
    fun `popup text on its shadow plate clears 4_5`() {
        // The glass is thin, so the text is read against its own shadow plate on a bright
        // backdrop. The real rendered worst case is asserted in PopupGlassTest; this states the
        // model at the token level. Pure white and pure black move the surface the most.
        val backdrops = mapOf("brightest" to 1f, "darkest" to 0f)
        val tokens = mapOf(
            "text" to DesignTokens.text,
            "selectedText" to DesignTokens.selectedText,
            "textSecondaryOnGlass" to DesignTokens.textSecondaryOnGlass,
        )
        for ((token, color) in tokens) {
            for ((backdrop, value) in backdrops) {
                val surface = Color(
                    (DesignTokens.glassBase.red + DesignTokens.glassBackdropMix * value).coerceIn(0f, 1f),
                    (DesignTokens.glassBase.green + DesignTokens.glassBackdropMix * value).coerceIn(0f, 1f),
                    (DesignTokens.glassBase.blue + DesignTokens.glassBackdropMix * value).coerceIn(0f, 1f),
                )
                val shadowed = blendOver(DesignTokens.chromeTextShadow.color, surface)
                val ratio = contrastRatio(color, shadowed)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$token on the $backdrop glass under its shadow = %.3f < %.1f".format(
                        ratio,
                        DesignTokens.contrastText,
                    ),
                )
            }
        }
    }

    @Test
    fun `the field is cool graphite and stays inside its luminance band`() {
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
        var widestSpread = 0f
        var brightest = 0.0
        var darkest = 1.0
        for (pixel in pixels) {
            val color = Color(pixel)
            widestSpread = maxOf(
                widestSpread,
                maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue),
            )
            val luminance = relativeLuminance(color)
            brightest = maxOf(brightest, luminance)
            darkest = minOf(darkest, luminance)
        }
        // A very low-chroma blue-violet graphite: a few levels of spread are the described tint,
        // a wider one would read as a coloured wash rather than a neutral field.
        assertTrue(widestSpread * 255f <= 10f, "the field's widest channel spread = ${widestSpread * 255f}/255")
        assertTrue(
            brightest <= relativeLuminance(DesignTokens.backgroundLift) + 0.001,
            "rendered max luminance %.6f is above the lift %.6f".format(
                brightest,
                relativeLuminance(DesignTokens.backgroundLift),
            ),
        )
        assertTrue(
            darkest >= relativeLuminance(DesignTokens.fieldBottom) - 0.001,
            "rendered min luminance %.6f is below the field bottom %.6f".format(
                darkest,
                relativeLuminance(DesignTokens.fieldBottom),
            ),
        )
        assertTrue(brightest - darkest >= 0.002, "the falloff is too flat: %.6f".format(brightest - darkest))
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

        /** The brightest plausible backdrop: the centre of the neutral field. */
        private val worstMesh: Color = DesignTokens.backgroundLift

        private val worstStrongGlass: Color = blendOver(DesignTokens.glassFillStrong, worstMesh)
        private val worstPanelGlass: Color = blendOver(DesignTokens.glassFill, worstMesh)
    }
}
