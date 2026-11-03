package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.blendOver
import stride.app.ui.theme.contrastRatio
import stride.app.ui.theme.relativeLuminance
import stride.core.services.Application
import stride.core.vfs.VFile

class TreeEditorGlassTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `tree panel shows the mesh through its glass`() {
        val state = AppState()
        try {
            state.openFolder(project("tree-project"))
            val pixels = renderAppPixels(state)
            val surface = DesignTokens.surface.toArgb()
            var widest = -1
            var bestX = -1
            var bestY = -1
            for (y in 40 until 64) {
                for (x in 120 until 276) {
                    val spread = spread(pixels[index(x, y)])
                    if (spread > widest) {
                        widest = spread
                        bestX = x
                        bestY = y
                    }
                }
            }
            val pixel = pixels[index(bestX, bestY)]
            System.err.println(
                "tree-glass widest=%d pixel=#%06X at (%d,%d)".format(
                    widest,
                    pixel and 0xFFFFFF,
                    bestX,
                    bestY,
                ),
            )
            assertTrue(widest >= 8, "widest channel spread in the tree header = $widest")
            assertTrue(
                channelDistance(pixel, surface) > 4,
                "tree pixel #%06X at (%d,%d) does not differ from surface".format(pixel and 0xFFFFFF, bestX, bestY),
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `editor body is the content tint over the same mesh pixel`() {
        val state = AppState()
        try {
            val root = project("tint-project")
            state.openFolder(root)
            requireNotNull(state.session).editors.openFile(VFile(root.resolve("src/Main.kt")))
            val app = renderAppPixels(state)
            val x = 900
            val y = 700
            val expected = blendOver(
                DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
                DesignTokens.background,
            )
            val actual = Color(app[index(x, y)])
            System.err.println(
                "editor-tint actual=#%02X%02X%02X expected=#%02X%02X%02X".format(
                    (actual.red * 255f).toInt(),
                    (actual.green * 255f).toInt(),
                    (actual.blue * 255f).toInt(),
                    (expected.red * 255f).toInt(),
                    (expected.green * 255f).toInt(),
                    (expected.blue * 255f).toInt(),
                ),
            )
            assertChannelClose(actual, expected, "editor pixel at ($x,$y)")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tree focus halo turns on when the tree has focus`() = runSkikoComposeUiTest(
        size = Size(1280f, 820f),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = project("focus-project")
            state.openFolder(root)
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val unfocused = captureToImage().pixels()
            // Rows are clickable (focusable) but Compose Desktop neither focuses on click nor
            // traverses with Tab, so a focus system requests focus through the semantics action.
            onNode(hasText("src") and hasClickAction()).requestFocus()
            waitForIdle()
            onNode(hasText("src") and hasClickAction()).assertIsFocused()
            val focused = captureToImage().pixels()

            for ((x, y) in listOf(200 to 42, 200 to 30)) {
                val before = Color(unfocused[index(x, y)])
                val after = Color(focused[index(x, y)])
                val green = channel(after) { green } - channel(before) { green }
                val blue = channel(after) { blue } - channel(before) { blue }
                val red = channel(after) { red } - channel(before) { red }
                assertTrue(
                    green > 1f && blue > 1f && red <= 0f,
                    "halo at ($x,$y): dR=%.2f dG=%.2f dB=%.2f".format(red, green, blue),
                )
                System.err.println("halo ($x,$y) dR=%.2f dG=%.2f dB=%.2f".format(red, green, blue))
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `goto line popup blurs the document behind it`() = runSkikoComposeUiTest(
        size = Size(1280f, 820f),
        density = Density(1f),
    ) {
        val root = temp.resolve("dense-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Dense.kt"), (1..80).joinToString("\n") { "#".repeat(200) })
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Dense.kt"))) as TextFileEditor
            // Keep the first line under the popup: the default end-of-file caret scrolls it away.
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val before = captureToImage().pixels()

            editor.gotoLineVisible = true
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("Go to line:")).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            val after = captureToImage().pixels()

            val label = onNodeWithText("Go to line:").fetchSemanticsNode().boundsInRoot
            val input = onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).fetchSemanticsNode().boundsInRoot
            val left = label.left.toInt() + 2
            val right = input.right.toInt() - 2
            val top = label.top.toInt() - 6
            val bottom = top + 1
            assertTrue(left < right && top < bottom, "popup band is empty: ($left,$top)-($right,$bottom)")

            val pattern = meanAdjacentDiff(before, left, right, top, bottom)
            val backdrop = meanAdjacentDiff(after, left, right, top, bottom)
            val ratio = backdrop / pattern
            val band = (left until right).map { x -> after[index(x, top)] and 0xFF }
            System.err.println(
                "goto-backdrop pattern=%.5f backdrop=%.5f ratio=%.4f band=%d..%d".format(
                    pattern,
                    backdrop,
                    ratio,
                    band.min(),
                    band.max(),
                ),
            )
            assertTrue(
                band.max() - band.min() >= 2,
                "blurred document should still vary inside the popup (band ${band.min()}..${band.max()})",
            )
            assertTrue(
                backdrop < pattern * 0.5,
                "backdrop mean %.5f should be below half the pattern mean %.5f (ratio %.3f)".format(
                    backdrop,
                    pattern,
                    ratio,
                ),
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `gutter line numbers clear 4_5 on the worst content tint`() {
        val worstMesh = listOf(DesignTokens.accent, DesignTokens.accent2, DesignTokens.neonPink)
            .map { blendOver(it.copy(alpha = DesignTokens.meshOrbAlpha), DesignTokens.background) }
            .maxBy { relativeLuminance(it) }
        val worstTint = blendOver(DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha), worstMesh)
        val ratio = contrastRatio(DesignTokens.muted, worstTint)
        System.err.println("gutter-contrast muted-on-worst-tint=%.3f".format(ratio))
        assertTrue(
            ratio >= DesignTokens.contrastText,
            "muted on the worst content tint = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `search pill shortcut hint clears 4_5 on its fill`() = runSkikoComposeUiTest(
        size = Size(1280f, 820f),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            state.openFolder(project("search-pill-project"))
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            // The pill is clickable, so the merged semantics node spans the whole row; measure the
            // unmerged text node to keep the hint's own pixels inside the search area. The same
            // glyphs also label the shortcut list further down, so the topmost node is the pill's.
            val bounds = onAllNodesWithText("⇧⇧", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val pixels = captureToImage().pixels()
            val fill = DesignTokens.background
            val left = bounds.left.toInt()
            val right = maxOf(bounds.right.toInt(), left + 1)
            val top = bounds.top.toInt()
            val bottom = maxOf(bounds.bottom.toInt(), top + 1)
            assertTrue(left < right && top < bottom, "hint bounds are empty: $bounds")
            var best = 0.0
            var bestPixel = 0
            var bestX = -1
            var bestY = -1
            for (y in top until bottom) {
                for (x in left until right) {
                    val pixel = pixels[index(x, y)]
                    val ratio = contrastRatio(Color(pixel), fill)
                    if (ratio > best) {
                        best = ratio
                        bestPixel = pixel
                        bestX = x
                        bestY = y
                    }
                }
            }
            System.err.println(
                "search-pill-hint contrast=%.3f pixel=#%06X at (%d,%d) fill=#%06X".format(
                    best,
                    bestPixel and 0xFFFFFF,
                    bestX,
                    bestY,
                    fill.toArgb() and 0xFFFFFF,
                ),
            )
            assertTrue(
                best >= DesignTokens.contrastText,
                "search pill hint contrast = %.3f < %.1f (pixel #%06X at (%d,%d))".format(
                    best,
                    DesignTokens.contrastText,
                    bestPixel and 0xFFFFFF,
                    bestX,
                    bestY,
                ),
            )
        } finally {
            state.dispose()
        }
    }

    private fun project(name: String): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "package demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        Files.writeString(root.resolve("src/Other.kt"), "package demo\n")
        return root
    }

    private fun renderAppPixels(state: AppState): IntArray {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(
                LocalAnimationsEnabled provides false,
                LocalWordmarkCaretBlink provides false,
            ) {
                App(state, onOpenFolder = {})
            }
        }
        return try {
            var nanos = 0L
            repeat(3) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            scene.render(nanos).pixels()
        } finally {
            scene.close()
        }
    }

    private fun org.jetbrains.skia.Image.pixels(): IntArray {
        val bitmap = toComposeImageBitmap()
        return IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(WIDTH * HEIGHT).also { readPixels(it) }

    private fun assertChannelClose(actual: Color, expected: Color, what: String) {
        val red = abs(actual.red * 255f - expected.red * 255f)
        val green = abs(actual.green * 255f - expected.green * 255f)
        val blue = abs(actual.blue * 255f - expected.blue * 255f)
        assertTrue(
            red <= 2f && green <= 2f && blue <= 2f,
            "%s: actual #%02X%02X%02X expected #%02X%02X%02X".format(
                what,
                (actual.red * 255f).toInt(),
                (actual.green * 255f).toInt(),
                (actual.blue * 255f).toInt(),
                (expected.red * 255f).toInt(),
                (expected.green * 255f).toInt(),
                (expected.blue * 255f).toInt(),
            ),
        )
    }

    private fun meanAdjacentDiff(pixels: IntArray, fromX: Int, toX: Int, fromY: Int, toY: Int): Double {
        var total = 0.0
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                total += abs(
                    relativeLuminance(Color(pixels[index(x + 1, y)])) -
                        relativeLuminance(Color(pixels[index(x, y)])),
                )
                count += 1
            }
        }
        return total / count
    }

    private fun channel(color: Color, pick: Color.() -> Float): Float = pick(color) * 255f

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    private fun spread(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return maxOf(red, green, blue) - minOf(red, green, blue)
    }

    private fun channelDistance(first: Int, second: Int): Int {
        val red = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val green = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val blue = abs((first and 0xFF) - (second and 0xFF))
        return maxOf(red, green, blue)
    }

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 820

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(TreeEditorGlassTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
