package stride.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.terminal.TerminalPalette
import stride.lang.ColorScheme

class DesignTokensContrastTest {
    @AfterEach
    fun restoreDark() {
        AppearanceState.set(Appearance.DARK)
    }

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
    fun `native glass tint keeps primary text over any desktop`() {
        // The system material is modelled as an overlay of the tint colour; a white desktop is
        // the brightest backdrop that can come through it. Secondary labels carry chromeTextStyle,
        // so they are asserted against their opaque shadow plate.
        val tint = Color(DesignTokens.nativeGlassTint)
        for ((name, color) in TEXT_TOKENS.filterKeys { it != "textSecondaryOnGlass" }) {
            for ((label, desktop) in mapOf("white" to Color.White, "black" to Color.Black)) {
                val surface = blendOver(tint, desktop)
                val ratio = contrastRatio(color, surface)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$name over $label desktop through the native tint = %.3f".format(ratio),
                )
            }
        }
        for ((label, desktop) in mapOf("white" to Color.White, "black" to Color.Black)) {
            val plate = blendOver(DesignTokens.chromeTextShadow.color, blendOver(tint, desktop))
            val ratio = contrastRatio(DesignTokens.textSecondaryOnGlass, plate)
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "textSecondaryOnGlass on its shadow over the $label desktop = %.3f".format(ratio),
            )
        }
    }

    @Test
    fun `text secondary on glass is the first muted to text step that clears 4_5`() {
        val found = firstStepClearing(worstStrongGlass)
        assertEquals(found.color, DarkPalette.textSecondaryOnGlass, "t=${found.t} hex=#%06X".format(found.color.value.toLong() and 0xFFFFFF))
        val ratio = contrastRatio(DarkPalette.textSecondaryOnGlass, worstStrongGlass)
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

    @Test
    fun `light text roles clear 4_5 on every light surface`() {
        val roles = linkedMapOf(
            "text" to LightPalette.text,
            "selectedText" to LightPalette.selectedText,
            "muted" to LightPalette.muted,
            "textSecondaryOnGlass" to LightPalette.textSecondaryOnGlass,
            "textOnBusyGlass" to LightPalette.textOnBusyGlass,
            "accent" to LightPalette.accent,
            "accent2" to LightPalette.accent2,
            "neonPink" to LightPalette.neonPink,
            "neonLime" to LightPalette.neonLime,
            "neonAmber" to LightPalette.neonAmber,
        )
        val surfaces = linkedMapOf(
            "editor surface" to LightPalette.surface,
            "field" to LightPalette.background,
            "worst strong glass" to blendOver(LightPalette.glassFillStrong, LightPalette.backgroundLift),
            "glass floor" to LightPalette.glassBase,
        )
        for ((name, color) in roles) {
            for ((surfaceName, surface) in surfaces) {
                val ratio = contrastRatio(color, surface)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$name on the light $surfaceName = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
                )
            }
        }
    }

    @Test
    fun `light selection reads instantly and its bar clears the UI contrast`() {
        val blended = blendOver(LightPalette.selection, LightPalette.surface)
        val selectionRatio = contrastRatio(blended, LightPalette.surface)
        assertTrue(selectionRatio >= 1.2, "blended selection on white = %.3f, too faint".format(selectionRatio))
        val glow = blendOver(LightPalette.accent2Glow, LightPalette.surface)
        assertTrue(contrastRatio(glow, LightPalette.surface) >= 1.1, "selection glow is invisible on light")
        val worstGlass = blendOver(LightPalette.glassFillStrong, LightPalette.backgroundLift)
        for ((name, color) in listOf("accent2" to LightPalette.accent2, "selectionBar" to LightPalette.selectionBar)) {
            for ((surfaceName, surface) in listOf("white" to LightPalette.surface, "glass" to worstGlass)) {
                val ratio = contrastRatio(color, surface)
                assertTrue(ratio >= DesignTokens.contrastUi, "$name on light $surfaceName = %.3f".format(ratio))
            }
        }
    }

    private data class SyntaxCase(
        val name: String,
        val scheme: ColorScheme,
        val surface: Color,
        val floor: Double,
    )

    @Test
    fun `syntax colours clear 4_5 on the light editor and keep the dark palette readable`() {
        // The dark keyword and comment sit at 4.3–4.1 from v1.3.0 and stay unchanged; the light
        // scheme is held to 4.5 on the white editor surface.
        val cases = listOf(
            SyntaxCase("light", ColorScheme.LIGHT, Color.White, DesignTokens.contrastText),
            SyntaxCase("dark", ColorScheme.DARK, Color(0xFF0F1117), 4.0),
        )
        for (case in cases) {
            for ((key, attributes) in case.scheme.attributes()) {
                val foreground = attributes.foreground ?: continue
                val ratio = contrastRatio(Color(foreground.toInt()), case.surface)
                assertTrue(
                    ratio >= case.floor,
                    "${case.name} $key = %.3f < %.1f on the editor surface".format(ratio, case.floor),
                )
            }
        }
    }

    private data class AnsiCase(
        val name: String,
        val appearance: Appearance,
        val background: Color,
        val floor: Double,
        val skip: Set<Int>,
    )

    @Test
    fun `ansi palettes stay readable on their terminal background`() {
        // Index 0 is the background itself on both palettes; the dark bright black (8) is the
        // dim grey of v1.3.0 and stays as it is.
        val cases = listOf(
            AnsiCase("light", Appearance.LIGHT, Color(0xFFF3F4F8), DesignTokens.contrastText, emptySet()),
            AnsiCase("dark", Appearance.DARK, Color(0xFF13141A), 4.0, setOf(0, 8)),
        )
        for (case in cases) {
            val palette = withAppearance(case.appearance) { TerminalPalette.ansi }
            palette.forEachIndexed { index, color ->
                if (index in case.skip) return@forEachIndexed
                val ratio = contrastRatio(color, case.background)
                assertTrue(
                    ratio >= case.floor,
                    "${case.name} ANSI $index = %.3f < %.1f".format(ratio, case.floor),
                )
            }
        }
    }

    private fun <T> withAppearance(appearance: Appearance, block: () -> T): T {
        val previous = AppearanceState.appearance
        AppearanceState.set(appearance)
        try {
            return block()
        } finally {
            AppearanceState.set(previous)
        }
    }

    private data class Step(val t: Double, val color: Color)

    private fun firstStepClearing(background: Color): Step {
        for (index in 0..100) {
            val t = index / 100.0
            val lerped = Color(
                (DarkPalette.muted.red + (DarkPalette.text.red - DarkPalette.muted.red) * t).toFloat(),
                (DarkPalette.muted.green + (DarkPalette.text.green - DarkPalette.muted.green) * t).toFloat(),
                (DarkPalette.muted.blue + (DarkPalette.text.blue - DarkPalette.muted.blue) * t).toFloat(),
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
            "text" to DarkPalette.text,
            "textSecondaryOnGlass" to DarkPalette.textSecondaryOnGlass,
            "accent2" to DarkPalette.accent2,
            "neonLime" to DarkPalette.neonLime,
            "neonAmber" to DarkPalette.neonAmber,
        )

        /** The brightest plausible backdrop: the centre of the neutral field. */
        private val worstMesh: Color = DarkPalette.backgroundLift

        private val worstStrongGlass: Color = blendOver(DarkPalette.glassFillStrong, worstMesh)
        private val worstPanelGlass: Color = blendOver(DarkPalette.glassFill, worstMesh)
    }
}
