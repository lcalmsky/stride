package stride.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.jediterm.terminal.TerminalColor
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.editor.glassColorScheme
import stride.app.editor.glassGutterColor
import stride.app.glass.GlassRuntime
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
    fun `focus markers clear the UI contrast`() {
        val ratio = contrastRatio(DesignTokens.accent2, worstStrongGlass)
        assertTrue(ratio >= DesignTokens.contrastUi, "accent2 on worstStrongGlass = %.3f".format(ratio))
    }

    @Test
    fun `the selection pill is one light signal over any desktop in both palettes`() {
        // The native material is the palette's tint over the desktop; the pill is the white lift
        // and the accent tint over that. It must read as a pill (1.15) and keep the selected label
        // at body contrast on it.
        val desktops = mapOf("white" to Color.White, "black" to Color.Black)
        for ((appearance, palette) in listOf(Appearance.DARK to DarkPalette, Appearance.LIGHT to LightPalette)) {
            val tint = Color(palette.nativeGlassTint)
            for ((label, desktop) in desktops) {
                val base = blendOver(tint, desktop)
                val pill = blendOver(palette.selectionPillTint, blendOver(palette.selectionPillLift, base))
                val pillRatio = contrastRatio(pill, base)
                System.err.println("selection-pill $appearance $label desktop ratio=%.3f".format(pillRatio))
                assertTrue(
                    pillRatio >= 1.15,
                    "$appearance pill over the $label desktop = %.3f, below 1.15".format(pillRatio),
                )
                for ((name, color) in listOf("text" to palette.text, "selectedText" to palette.selectedText)) {
                    val ratio = contrastRatio(color, pill)
                    assertTrue(
                        ratio >= DesignTokens.contrastText,
                        "$appearance $name on the pill over the $label desktop = %.3f".format(ratio),
                    )
                }
            }
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
    fun `native glass tint keeps primary text over any desktop in both palettes`() {
        // The system material is modelled as an overlay of the palette's tint colour; a white
        // and a black desktop are the extremes that can come through it. Secondary labels carry
        // chromeTextStyle, so they are asserted against their opaque shadow plate.
        val desktops = mapOf("white" to Color.White, "black" to Color.Black)
        for ((appearance, palette) in listOf(Appearance.DARK to DarkPalette, Appearance.LIGHT to LightPalette)) {
            val tint = Color(palette.nativeGlassTint)
            for ((name, color) in listOf("text" to palette.text, "selectedText" to palette.selectedText)) {
                for ((label, desktop) in desktops) {
                    val ratio = contrastRatio(color, blendOver(tint, desktop))
                    assertTrue(
                        ratio >= DesignTokens.contrastText,
                        "$appearance $name over $label desktop through the native tint = %.3f".format(ratio),
                    )
                }
            }
            for ((label, desktop) in desktops) {
                val plate = blendOver(palette.chromeTextShadow.color, blendOver(tint, desktop))
                val ratio = contrastRatio(palette.textSecondaryOnGlass, plate)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$appearance textSecondaryOnGlass on its shadow over the $label desktop = %.3f".format(ratio),
                )
            }
        }
        // Accent labels are body text on glass too, so each palette's accents must clear 4.5 over
        // both desktop extremes. The light palette is darkened for the near-white floor its tint
        // leaves over a dark desktop.
        for ((appearance, palette) in listOf(Appearance.DARK to DarkPalette, Appearance.LIGHT to LightPalette)) {
            val tint = Color(palette.nativeGlassTint)
            for ((name, color) in listOf(
                "accent2" to palette.accent2,
                "neonLime" to palette.neonLime,
                "neonAmber" to palette.neonAmber,
            )) {
                for ((label, desktop) in desktops) {
                    val ratio = contrastRatio(color, blendOver(tint, desktop))
                    assertTrue(
                        ratio >= DesignTokens.contrastText,
                        "$appearance $name over $label desktop through the native tint = %.3f".format(ratio),
                    )
                }
            }
        }
    }

    @Test
    fun `glass code console and terminal text clear 4_5 on every stacked plate in both palettes`() {
        val previous = GlassRuntime.active.value
        GlassRuntime.active.value = true
        try {
            for (appearance in listOf(Appearance.DARK, Appearance.LIGHT)) {
                AppearanceState.set(appearance)
                val palette = paletteOf(appearance)
                val plates = editorGlassPlates(palette)
                val floor = editorGlassFloor(palette)
                fun assertOnEveryPlate(name: String, color: Color): Double {
                    var minimum = Double.MAX_VALUE
                    for (plate in plates) {
                        val ratio = contrastRatio(color, plate)
                        minimum = minOf(minimum, ratio)
                        assertTrue(
                            ratio >= DesignTokens.contrastText,
                            "$appearance $name on glass plate #%02X%02X%02X = %.3f < %.1f".format(
                                (plate.red * 255f).roundToInt(),
                                (plate.green * 255f).roundToInt(),
                                (plate.blue * 255f).roundToInt(),
                                ratio,
                                DesignTokens.contrastText,
                            ),
                        )
                    }
                    return minimum
                }
                var codeMinimum = Double.MAX_VALUE
                for ((name, attributes) in glassColorScheme(appearance).attributes()) {
                    val foreground = attributes.foreground ?: continue
                    val background = attributes.background
                    if (background != null) {
                        val ratio = contrastRatio(Color(foreground.toInt()), Color(background.toInt()))
                        assertTrue(
                            ratio >= DesignTokens.contrastText,
                            "$appearance code $name on its own plate = %.3f < %.1f".format(
                                ratio,
                                DesignTokens.contrastText,
                            ),
                        )
                    } else {
                        codeMinimum = minOf(codeMinimum, assertOnEveryPlate("code $name", Color(foreground.toInt())))
                    }
                }
                val gutterMinimum = assertOnEveryPlate("gutter", glassGutterColor(appearance))
                val console = mapOf(
                    "text" to DesignTokens.text,
                    "muted" to DesignTokens.mutedOnGlass,
                    "accent2" to DesignTokens.accent2OnGlass,
                    "neonPink" to DesignTokens.neonPinkOnGlass,
                    "neonLime" to DesignTokens.neonLimeOnGlass,
                    "neonAmber" to DesignTokens.neonAmberOnGlass,
                )
                var consoleMinimum = Double.MAX_VALUE
                for ((name, color) in console) {
                    consoleMinimum = minOf(consoleMinimum, assertOnEveryPlate("console $name", color))
                }
                assertOnEveryPlate("terminal foreground", TerminalPalette.foreground)
                val ansi = TerminalPalette.ansi
                assertEquals(16, ansi.size)
                var ansiMinimum = Double.MAX_VALUE
                for ((index, color) in ansi.withIndex()) {
                    ansiMinimum = minOf(ansiMinimum, assertOnEveryPlate("terminal ANSI $index", color))
                }
                System.err.println(
                    "glass-text $appearance floor=#%02X%02X%02X".format(
                        (floor.red * 255f).roundToInt(),
                        (floor.green * 255f).roundToInt(),
                        (floor.blue * 255f).roundToInt(),
                    ) +
                        " plates=${plates.size}" +
                        " code-min=%.2f gutter-min=%.2f console-min=%.2f ansi-min=%.2f".format(
                            codeMinimum,
                            gutterMinimum,
                            consoleMinimum,
                            ansiMinimum,
                        ),
                )
            }
        } finally {
            GlassRuntime.active.value = previous
            AppearanceState.set(Appearance.DARK)
        }
    }

    @Test
    fun `ansi backgrounds keep the base colours on glass and inverse video swaps the lists`() {
        val previous = GlassRuntime.active.value
        GlassRuntime.active.value = true
        try {
            AppearanceState.set(Appearance.DARK)
            val original = Color(0xFF0F1117)
            assertEquals(original, TerminalPalette.originalAnsi[0])
            assertEquals(original, TerminalPalette.backgroundOf(TerminalColor.index(0)))
            val readable = TerminalPalette.foregroundOf(TerminalColor.index(0))
            assertFalse(readable == original, "the ANSI 0 foreground must move on the glass")
            val floor = editorGlassFloor(DarkPalette)
            assertTrue(
                contrastRatio(readable, floor) >= DesignTokens.contrastText,
                "the ANSI 0 foreground on the glass = %.3f".format(contrastRatio(readable, floor)),
            )
            assertEquals(
                Color.Transparent,
                TerminalPalette.defaultCellBackground,
                "the default cell background must stay transparent on the glass",
            )
            val (normalForeground, normalBackground) =
                TerminalPalette.cellColors(TerminalColor.index(0), null, inverse = false)
            assertEquals(readable, normalForeground)
            assertEquals(Color.Transparent, normalBackground)
            val (inverseForeground, inverseBackground) =
                TerminalPalette.cellColors(TerminalColor.index(0), null, inverse = true)
            assertEquals(DarkPalette.background, inverseForeground, "the inverse default glyph")
            assertEquals(original, inverseBackground, "the inverse ANSI 0 block stays dark")
            val swapped = TerminalPalette.cellColors(TerminalColor.index(1), TerminalColor.index(0), inverse = true)
            assertEquals(original, swapped.first, "the original ANSI 0 becomes the inverse glyph")
            assertEquals(TerminalPalette.originalAnsi[1], swapped.second, "the original ANSI 1 becomes the block")
        } finally {
            GlassRuntime.active.value = previous
            AppearanceState.set(Appearance.DARK)
        }
    }

    @Test
    fun `glass selection current line and find match keep their plates and text`() {
        for (appearance in listOf(Appearance.DARK, Appearance.LIGHT)) {
            val palette = paletteOf(appearance)
            val base = editorGlassBase(palette)
            val selection = blendOver(palette.selection, base)
            val currentLine = blendOver(palette.currentLine, base)
            val match = blendOver(palette.neonPink.copy(alpha = GLASS_FIND_MATCH_ALPHA), base)
            val otherMatch = blendOver(palette.neonAmber.copy(alpha = GLASS_FIND_MATCH_ALPHA), base)
            assertTrue(
                contrastRatio(selection, base) >= 1.2,
                "$appearance selection plate on the glass = %.3f".format(contrastRatio(selection, base)),
            )
            assertTrue(
                contrastRatio(currentLine, base) >= 1.03,
                "$appearance current line on the glass = %.3f".format(contrastRatio(currentLine, base)),
            )
            for ((name, plate) in listOf("match" to match, "other match" to otherMatch)) {
                assertTrue(
                    contrastRatio(plate, base) >= 1.1,
                    "$appearance find $name on the glass = %.3f".format(contrastRatio(plate, base)),
                )
            }
            assertTrue(
                contrastRatio(palette.accent2, base) >= DesignTokens.contrastUi,
                "$appearance caret on the glass = %.3f".format(contrastRatio(palette.accent2, base)),
            )
            val defaultText = Color(
                requireNotNull(
                    glassColorScheme(appearance).getAttributes(stride.lang.TextAttributesKey.DEFAULT_TEXT),
                ).foreground!!.toInt(),
            )
            // The stacked plates are the ones that really paint (selection + match + glow); the
            // text must clear 4.5 on each of them, not only on a single overlay over the floor.
            for ((index, plate) in editorGlassPlates(palette).withIndex()) {
                val ratio = contrastRatio(defaultText, plate)
                assertTrue(
                    ratio >= DesignTokens.contrastText,
                    "$appearance code on plate $index (#%06X) = %.3f".format(
                        plate.value.toLong() and 0xFFFFFF,
                        ratio,
                    ),
                )
            }
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
    fun `light selection reads instantly and stays light on light glass`() {
        val blended = blendOver(LightPalette.selection, LightPalette.surface)
        val selectionRatio = contrastRatio(blended, LightPalette.surface)
        assertTrue(selectionRatio >= 1.2, "blended selection on white = %.3f, too faint".format(selectionRatio))
        // The pill lifts toward white and only tints with the accent; a dark slab would sit far
        // below the near-white luminance band.
        for ((surfaceName, surface) in listOf("white" to LightPalette.surface, "field" to LightPalette.background)) {
            val pill = blendOver(
                LightPalette.selectionPillTint,
                blendOver(LightPalette.selectionPillLift, surface),
            )
            assertTrue(
                relativeLuminance(pill) >= 0.6,
                "the light pill on $surfaceName is too dark: %.4f".format(relativeLuminance(pill)),
            )
            assertTrue(
                contrastRatio(pill, surface) >= 1.15,
                "the light pill on $surfaceName = %.3f".format(contrastRatio(pill, surface)),
            )
            val ratio = contrastRatio(LightPalette.accent2, surface)
            assertTrue(ratio >= DesignTokens.contrastUi, "accent2 on light $surfaceName = %.3f".format(ratio))
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
        // The dark keyword and comment sit at 4.3–4.1 from v1.3.0 and stay unchanged; that 4.0
        // floor is a legacy exception to WCAG AA kept for pixel-identical dark snapshots. The
        // light scheme is held to 4.5 on the white editor surface.
        val cases = listOf(
            SyntaxCase("light", ColorScheme.LIGHT, Color.White, DesignTokens.contrastText),
            SyntaxCase("dark (legacy v1.3.0 exception)", ColorScheme.DARK, Color(0xFF0F1117), LEGACY_SYNTAX_FLOOR),
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
        // Index 0 is the background itself on both palettes. The dark palette's 4.0 floor and the
        // excluded bright black (8) are the legacy v1.3.0 exception: that dim grey stays as it is
        // so the dark terminal keeps its pixel-identical colours.
        val cases = listOf(
            AnsiCase("light", Appearance.LIGHT, Color(0xFFF3F4F8), DesignTokens.contrastText, emptySet()),
            AnsiCase("dark (legacy v1.3.0 exception)", Appearance.DARK, Color(0xFF13141A), LEGACY_ANSI_FLOOR, LEGACY_ANSI_SKIPS),
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
        /** Legacy exception: the dark syntax palette's v1.3.0 keyword/comment values. */
        private const val LEGACY_SYNTAX_FLOOR = 4.0

        /** Legacy exception: the dark ANSI palette's v1.3.0 values. */
        private const val LEGACY_ANSI_FLOOR = 4.0
        private val LEGACY_ANSI_SKIPS = setOf(0, 8)

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
