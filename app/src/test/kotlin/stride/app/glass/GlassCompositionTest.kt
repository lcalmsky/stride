package stride.app.glass

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.holeRectInLocal
import stride.core.services.Application
import stride.core.vfs.VFile

/**
 * Renders the real [App] with the native glass composition path forced on. No native library is
 * involved: the system material is absent, so every pixel the image carries is Compose drawing.
 * A popup panel carries the palette plate there (alpha [PLATE_ALPHA]), which bounds the material's
 * brightness; the editor and tool-window bodies draw nothing and leave the material alone.
 */
@ExtendWith(FixedUiFontExtension::class)
class GlassCompositionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `an empty search popup carries the palette plate over its glass`() {
        val state = AppState()
        nativePath {
            appearance(Appearance.LIGHT) {
                try {
                    state.openFolder(project("empty-search-project"))
                    state.searchVisible = true
                    val rendered = render(state, initialQuery = "")
                    val popup = popupRect()
                    System.err.println("glass-composition popup=$popup")
                    for ((x, y) in listOf(306 to 122, 900 to 160, 800 to 196, 950 to 196)) {
                        assertEquals(
                            PLATE_ALPHA.toDouble(),
                            rendered.pixels[index(x, y)].ushr(24).toDouble(),
                            2.0,
                            "popup ($x,$y) must carry the palette plate over the system material",
                        )
                    }
                    // The popup itself is a reported glass piece at the expected window rectangle.
                    System.err.println(
                        "glass-composition pieces=" +
                            rendered.pieces.joinToString { "${it.key}:${it.x},${it.y},${it.width},${it.height}" },
                    )
                    val piece = rendered.pieces.firstOrNull { it.key == "popup-search" }
                    assertTrue(piece != null, "the popup reported no native glass piece")
                    assertEquals(300f, requireNotNull(piece).x, 0.5f)
                    assertEquals(96f, piece.y, 0.5f)
                    assertEquals(680f, piece.width, 0.5f)
                    // A grid over the popup: the plate covers every sample; only glyph ink may
                    // differ from it, and no sample may be left to the system material.
                    assertPlateGrid(
                        rendered,
                        popup.left.toInt() + 6,
                        popup.top.toInt() + 6,
                        popup.right.toInt() - 6,
                        popup.bottom.toInt() - 6,
                        step = 8,
                        what = "search popup",
                    )
                } finally {
                    state.dispose()
                }
            }
        }
    }

    @Test
    fun `the editor and the tool window body are native glass pieces`() {
        val state = AppState()
        nativePath {
            appearance(Appearance.LIGHT) {
                try {
                    val root = project("glass-editor-project")
                    state.openFolder(root)
                    state.session?.editors?.openFile(VFile(root.resolve("src/Main.kt")))
                    state.toolWindows.activate("run")
                    val rendered = render(state, initialQuery = "")
                    System.err.println(
                        "glass-composition editor pieces=" +
                            rendered.pieces.joinToString { "${it.key}:${it.x},${it.y},${it.width},${it.height}:${it.style}:${it.tint}" },
                    )
                    val editor = rendered.pieces.firstOrNull { it.key == "editor-surface" }
                    val toolWindow = rendered.pieces.firstOrNull { it.key == "tool-window-surface" }
                    assertTrue(editor != null, "the editor reported no native glass piece")
                    assertTrue(toolWindow != null, "the tool window body reported no native glass piece")
                    for (piece in listOf(requireNotNull(editor), requireNotNull(toolWindow))) {
                        assertEquals(GlassStyle.REGULAR, piece.style, "${piece.key} must use the regular material")
                        assertEquals(DesignTokens.editorGlassTint, piece.tint, "${piece.key} must use the editor tint")
                    }
                    assertEquals(300f, editor.x, 0.5f, "the editor glass starts at the content column")
                    // No Compose fill: the sidebar row background, the editor field and the tool
                    // window body are the native material alone. A grid, not a few points, so an
                    // opaque plane anywhere in the region is caught.
                    assertNoFillGrid(rendered, 30, 420, 270, 780, step = 24, what = "tree body")
                    assertNoFillGrid(rendered, 400, 300, 1200, 540, step = 32, what = "editor field")
                    assertNoFillGrid(
                        rendered,
                        400,
                        700,
                        1200,
                        790,
                        step = 24,
                        what = "tool window body",
                        maxFilled = 0.02,
                    )
                } finally {
                    state.dispose()
                }
            }
        }
    }

    @Test
    fun `the goto line popup draws its text outside the editor clip`() {
        val state = AppState()
        nativePath {
            appearance(Appearance.LIGHT) {
                try {
                    val root = project("goto-line-project")
                    state.openFolder(root)
                    val session = requireNotNull(state.session)
                    val editor = session.editors.openFile(VFile(root.resolve("src/Main.kt"))) as TextFileEditor
                    editor.gotoLineVisible = true
                    val rendered = render(state, initialQuery = "")
                    val piece = rendered.pieces.firstOrNull { it.key == "popup-goto-line" }
                    assertTrue(piece != null, "the goto line popup reported no native glass piece")
                    val rect = requireNotNull(piece)
                    System.err.println("glass-composition goto-line=$rect")
                    // The clip used to cut the popup's own hole out of its own text; the label
                    // and the input must reach the frame, and every other sample must be the
                    // palette plate or the antialiased edge of glyph ink on it.
                    var text = 0
                    var plate = 0
                    var blend = 0
                    var accent = 0
                    var foreign = 0
                    var firstForeign = ""
                    var sampled = 0
                    for (y in (rect.y + 4).toInt() until (rect.y + rect.height - 4).toInt()) {
                        for (x in (rect.x + 4).toInt() until (rect.x + rect.width - 4).toInt()) {
                            sampled++
                            // Glyph ink, matched by colour: the palette plate under it is opaque
                            // but tinted, so an alpha threshold would count the whole panel.
                            val pixel = rendered.pixels[index(x, y)]
                            when {
                                isGlyphInk(pixel) -> text++
                                isPlate(pixel) -> plate++
                                isGlyphBlend(pixel) -> blend++
                                isAccentDecoration(pixel) -> accent++
                                else -> {
                                    foreign++
                                    if (firstForeign.isEmpty()) {
                                        firstForeign = "($x,$y)=#%08X".format(pixel)
                                    }
                                }
                            }
                        }
                    }
                    System.err.println(
                        "glass-composition goto-line text=$text plate=$plate blend=$blend " +
                            "accent=$accent foreign=$foreign/$sampled $firstForeign",
                    )
                    assertTrue(text > 40, "the goto line popup drew no text ($text of $sampled samples)")
                    assertEquals(
                        0,
                        foreign,
                        "the goto line popup carried a fill that is neither plate, glyph ink, " +
                            "nor an accent decoration ($foreign of $sampled samples, first $firstForeign)",
                    )
                    // The glyphs cover well under half the panel; the plate covers the rest.
                    assertTrue(
                        text.toDouble() / sampled < 0.6,
                        "the goto line popup drew text over the whole panel ($text of $sampled samples)",
                    )
                } finally {
                    state.dispose()
                }
            }
        }
    }

    @Test
    fun `the editor clip removes code from under an open popup`() {
        val state = AppState()
        nativePath {
            appearance(Appearance.LIGHT) {
                try {
                    val root = denseProject("clip-project")
                    state.openFolder(root)
                    val session = requireNotNull(state.session)
                    val editor = session.editors.openFile(VFile(root.resolve("Dense.kt"))) as TextFileEditor
                    editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
                    state.searchVisible = true
                    val rendered = render(state, initialQuery = "")
                    // Outside the popup the dense document paints glyphs.
                    var glyphsOutside = 0
                    for (y in 300 until 320) {
                        for (x in 500 until 900) {
                            if (rendered.pixels[index(x, y)].ushr(24) > 0) glyphsOutside++
                        }
                    }
                    // Inside the popup's hint bar, where the clipped code would otherwise land,
                    // the plate is uniform: code under it would make the pixels opaque and vary.
                    var leaked = 0
                    for (y in 186 until 200) {
                        for (x in 800 until 950) {
                            val alpha = rendered.pixels[index(x, y)].ushr(24)
                            if (alpha < PLATE_ALPHA - 2 || alpha > PLATE_ALPHA + 2) leaked++
                        }
                    }
                    System.err.println("glass-composition clip outside=$glyphsOutside inside=$leaked")
                    assertTrue(glyphsOutside > 100, "the editor drew no code outside the popup ($glyphsOutside)")
                    assertEquals(0, leaked, "code shows through the popup's plate ($leaked pixels)")
                } finally {
                    state.dispose()
                }
            }
        }
    }

    @Test
    fun `the popup hole maps into the drawing element's own space`() {
        val hole = Rect(left = 300f, top = 96f, right = 980f, bottom = 206f)
        // The window root draws at the origin.
        assertEquals(Rect(600f, 192f, 1960f, 412f), holeRectInLocal(hole, density = 2f, Offset.Zero))
        // A surface whose own top-left is the popup's window position.
        assertEquals(
            Rect(0f, 0f, 1360f, 220f),
            holeRectInLocal(hole, density = 2f, Offset(600f, 192f)),
        )
        // Any other origin moves the hole by exactly that distance.
        assertEquals(
            Rect(-100f, -100f, 1260f, 120f),
            holeRectInLocal(hole, density = 2f, Offset(700f, 292f)),
        )
    }

    /**
     * Samples a grid over a region and requires that at most [maxFilled] of the samples carry
     * an opaque Compose fill ([FILL_ALPHA]). Counting only alpha above the threshold ignores
     * glyph text shadows; an opaque plane (the regression was alpha 38) still fills the grid.
     */
    private fun assertNoFillGrid(
        rendered: Rendered,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int,
        what: String,
        maxFilled: Double = 0.0,
    ) {
        var filled = 0
        var total = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                total++
                if (rendered.pixels[index(x, y)].ushr(24) >= FILL_ALPHA) filled++
                x += step
            }
            y += step
        }
        val fraction = filled.toDouble() / total
        System.err.println("glass-composition fill $what=$filled/$total")
        assertTrue(fraction <= maxFilled, "$what carries a Compose fill ($filled of $total samples)")
    }

    /** Every sample is the plate or glyph ink on it; none is left transparent. */
    private fun assertPlateGrid(
        rendered: Rendered,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int,
        what: String,
    ) {
        var plate = 0
        var ink = 0
        var clear = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val alpha = rendered.pixels[index(x, y)].ushr(24)
                when {
                    alpha == 0 -> clear++
                    alpha in (PLATE_ALPHA - 2)..(PLATE_ALPHA + 2) -> plate++
                    else -> ink++
                }
                x += step
            }
            y += step
        }
        System.err.println("glass-composition plate $what=$plate ink=$ink clear=$clear")
        assertEquals(0, clear, "$what left samples to the system material")
        assertTrue(plate > ink, "$what: the plate covers only $plate samples (ink $ink)")
        val inkShare = ink.toDouble() / (plate + ink)
        assertTrue(
            ink <= 0.35 * (plate + ink),
            "$what: ink covers %.2f of the filled samples (ink $ink, plate $plate)".format(inkShare),
        )
    }

    private fun nativePath(block: () -> Unit) {
        GlassRuntime.active.value = true
        try {
            block()
        } finally {
            GlassRuntime.active.value = false
            AppearanceState.set(Appearance.DARK)
        }
    }

    private fun appearance(appearance: Appearance, block: () -> Unit) {
        AppearanceState.set(appearance)
        block()
    }

    /** One rendered frame plus the glass pieces that were composed for it. */
    private class Rendered(val pixels: IntArray, val pieces: List<GlassPiece>)

    private fun render(state: AppState, initialQuery: String): Rendered {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(
                LocalAnimationsEnabled provides false,
                LocalWordmarkCaretBlink provides false,
            ) {
                App(state, onOpenFolder = {}, searchInitialQuery = initialQuery)
            }
        }
        return try {
            var nanos = 0L
            repeat(3) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            val image = scene.render(nanos)
            val pixels = IntArray(WIDTH * HEIGHT)
            image.toComposeImageBitmap().readPixels(pixels)
            image.close()
            // Read before the scene closes: disposing the composition removes the pieces.
            Rendered(pixels, GlassRegistry.shared.reported)
        } finally {
            scene.close()
        }
    }

    /** The empty-query popup: the header, tab row and hint bar, and no result rows. */
    private fun popupRect(): Rect {
        val width = DesignTokens.popupWidth.value
        val height = HEADER_HEIGHT + DesignTokens.popupTabHeight.value + DesignTokens.popupFooterHeight.value
        val left = (WIDTH - width) / 2f
        return Rect(left, DesignTokens.popupTop.value, left + width, DesignTokens.popupTop.value + height)
    }

    private fun project(name: String): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "package demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        return root
    }

    /** Long lines so any leaked editor row shows glyph pixels across the popup's width. */
    private fun denseProject(name: String): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root)
        Files.writeString(root.resolve("Dense.kt"), (1..120).joinToString("\n") { "#".repeat(300) })
        return root
    }

    /** A core glyph pixel: the text tokens are drawn opaque and exactly, so a fill cannot match. */
    private fun isGlyphInk(pixel: Int): Boolean {
        if (pixel.ushr(24) != 0xFF) return false
        val color = Color(pixel)
        return glyphTokens().any { token -> channelsMatch(color, token, 6) }
    }

    /** The plate as the transparent scene composites it: [PLATE_ALPHA] over the glass base colour. */
    private fun isPlate(pixel: Int): Boolean {
        if (pixel.ushr(24) !in (PLATE_ALPHA - 2)..(PLATE_ALPHA + 2)) return false
        return channelsMatch(Color(pixel), DesignTokens.popupPlate, 6)
    }

    /** An antialiased glyph edge: the plate mixed with one of the popup's text tokens. */
    private fun isGlyphBlend(pixel: Int): Boolean {
        val color = Color(pixel)
        val plate = DesignTokens.popupPlate
        return glyphTokens().any { token -> onSegment(color, plate, token, 8) }
    }

    /** The neon border and the input cursor are accent strokes the popup itself draws on top. */
    private fun isAccentDecoration(pixel: Int): Boolean =
        onSegment(Color(pixel), DesignTokens.popupPlate, DesignTokens.accent, 8)

    private fun glyphTokens(): List<Color> = listOf(DesignTokens.text, DesignTokens.textSecondaryOnGlass)

    private fun channelsMatch(first: Color, second: Color, tolerance: Int): Boolean {
        val limit = tolerance / 255f
        return kotlin.math.abs(first.red - second.red) <= limit &&
            kotlin.math.abs(first.green - second.green) <= limit &&
            kotlin.math.abs(first.blue - second.blue) <= limit
    }

    /** Whether [sample] lies on the channel-wise line from [from] to [to], within [tolerance]. */
    private fun onSegment(sample: Color, from: Color, to: Color, tolerance: Int): Boolean {
        val limit = tolerance / 255f
        for (step in 0..100) {
            val t = step / 100f
            val red = from.red + (to.red - from.red) * t
            val green = from.green + (to.green - from.green) * t
            val blue = from.blue + (to.blue - from.blue) * t
            if (kotlin.math.abs(sample.red - red) <= limit &&
                kotlin.math.abs(sample.green - green) <= limit &&
                kotlin.math.abs(sample.blue - blue) <= limit
            ) {
                return true
            }
        }
        return false
    }

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 820
        private const val HEADER_HEIGHT = 52f

        /** Alpha from which a sample counts toward the fill budget; text shadows stay below it. */
        private const val FILL_ALPHA = 32

        /** 0.90 plate over a transparent image, as the offscreen render composites it. */
        private const val PLATE_ALPHA = 230

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(GlassCompositionTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
