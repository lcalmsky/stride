package stride.app.glass

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.holeRectInLocal
import stride.core.services.Application

/**
 * Renders the real [App] with the native glass composition path forced on. No native library is
 * involved: the system material is absent, so every pixel the image carries is Compose drawing.
 * That makes an alpha of zero inside a glass piece mean "only the system material is there",
 * which is exactly the native-path rule the popups and the editor must obey.
 */
@ExtendWith(FixedUiFontExtension::class)
class GlassCompositionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `an empty search popup draws no fill inside its glass`() {
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
                            0,
                            rendered.pixels[index(x, y)].ushr(24),
                            "popup ($x,$y) carries a Compose fill; the system material must show through",
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

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 820
        private const val HEADER_HEIGHT = 52f

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
