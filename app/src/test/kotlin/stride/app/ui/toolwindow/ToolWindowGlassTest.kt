package stride.app.ui.toolwindow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.KeyContext
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.RunToolWindow
import stride.app.run.StartedProcess
import stride.app.terminal.TerminalSession
import stride.app.terminal.TerminalView
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.MeshBackground
import stride.app.ui.theme.blendOver
import stride.app.ui.theme.contrastRatio
import stride.app.ui.theme.relativeLuminance
import stride.core.services.Project

@ExtendWith(FixedUiFontExtension::class)
class ToolWindowGlassTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `tool window content is a content plane with one floating switcher capsule`() {
        val toolWindows = managerWith("test", "Test") { modifier -> Box(modifier) }
        val pixels = renderScene(SCENE, SCENE) {
            MeshBackground(Modifier.fillMaxSize()) {
                ToolWindowPanel(
                    toolWindows,
                    onFocusContext = {},
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(120.dp),
                )
            }
        }
        // Far from the switcher: the panel paints the editor's content plane, not a slab.
        val plane = Color(pixels[index(380, 320, SCENE)])
        val expected = blendOver(
            DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
            DesignTokens.background,
        )
        System.err.println("tool-window plane=%s expected=%s".format(hex(plane), hex(expected)))
        assertTrue(
            channelDistance(plane.toArgb(), expected.toArgb()) <= 2,
            "the tool window should be the content plane: %s vs %s".format(hex(plane), hex(expected)),
        )
        // Inside the switcher capsule: glass over that plane, so the pixel differs from it.
        val capsule = Color(pixels[index(35, 294, SCENE)])
        // The capsule's top specular rim and its flat interior: a flat fill would draw both the
        // same, the capsule lens does not.
        val rim = Color(pixels[index(10, 289, SCENE)])
        val interior = Color(pixels[index(10, 301, SCENE)])
        System.err.println(
            "tool-window switcher=%s rim=%s interior=%s".format(hex(capsule), hex(rim), hex(interior)),
        )
        assertTrue(
            channelDistance(capsule.toArgb(), plane.toArgb()) > 12,
            "the switcher should be a floating capsule, not the plane: %s vs %s".format(
                hex(capsule),
                hex(plane),
            ),
        )
        assertTrue(
            relativeLuminance(rim) > relativeLuminance(interior) + 0.01,
            "the capsule's top rim should be a specular highlight, not a flat fill: %s vs %s".format(
                hex(rim),
                hex(interior),
            ),
        )
    }

    @Test
    fun `terminal empty cell is the content plane`() {
        val root = Files.createDirectories(temp.resolve("terminal-project"))
        val project = Project(root)
        val session = TerminalSession(project)
        try {
            val toolWindows = managerWith("terminal", "Terminal", KeyContext.TERMINAL) { modifier ->
                TerminalView(session, modifier)
            }
            val rendered = renderScene(SCENE, SCENE) {
                MeshBackground(Modifier.fillMaxSize()) {
                    ToolWindowPanel(toolWindows, onFocusContext = {}, modifier = Modifier.fillMaxSize())
                }
            }
            assertContentPlane("terminal", rendered, 380, 380)
        } finally {
            session.dispose()
            project.dispose()
        }
    }

    @Test
    fun `run console empty cell is the content plane`() {
        val root = Files.createDirectories(temp.resolve("run-project"))
        val project = Project(root)
        val runManager = RunManager(project)
        runManager.starter = ProcessStarter { StartedProcess(FakeProcess(ByteArray(0), 0), "gradle test") }
        runManager.run(RunConfiguration("demo.RunTest", root, emptyList(), RunKind.TEST))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runManager.current?.exitCode == null) Thread.sleep(10)
        try {
            val toolWindows = managerWith("run", "Run", KeyContext.RUN_WINDOW) { modifier ->
                RunToolWindow(runManager, modifier)
            }
            val rendered = renderScene(SCENE, SCENE) {
                MeshBackground(Modifier.fillMaxSize()) {
                    ToolWindowPanel(toolWindows, onFocusContext = {}, modifier = Modifier.fillMaxSize())
                }
            }
            assertContentPlane("run console", rendered, 380, 380)
        } finally {
            runManager.dispose()
            project.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `status glyphs draw no halo`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("glow-project"))
        val project = Project(root)
        val runManager = RunManager(project)
        val events = listOf(
            "##stride[test start class='demo.GlowTest' name='passes once']",
            "##stride[test pass class='demo.GlowTest' name='passes once' duration='3']",
            "##stride[test start class='demo.GlowTest' name='fails once']",
            "##stride[test fail class='demo.GlowTest' name='fails once' duration='4' message='boom' " +
                "stack='at demo.GlowTest.fails(GlowTest.kt:12)']",
            "##stride[test start class='demo.GlowTest' name='fails twice']",
            "##stride[test fail class='demo.GlowTest' name='fails twice' duration='5' message='bang' " +
                "stack='at demo.GlowTest.fails(GlowTest.kt:16)']",
        ).joinToString("\n") + "\n"
        runManager.starter = ProcessStarter {
            StartedProcess(FakeProcess(events.toByteArray(), 1), "gradle test")
        }
        runManager.run(RunConfiguration("demo.GlowTest", root, emptyList(), RunKind.TEST))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runManager.current?.exitCode == null) Thread.sleep(10)
        try {
            setContent {
                CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                    Box(Modifier.fillMaxSize().background(DesignTokens.surface)) {
                        RunToolWindow(runManager, Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()

            val row = onNodeWithText("fails twice", useUnmergedTree = true)
            val clicked = row.fetchSemanticsNode().boundsInRoot
            row.performClick()
            waitForIdle()

            val glyphs = onAllNodesWithText("✕", useUnmergedTree = true).fetchSemanticsNodes()
            val ordered = glyphs.map { it.boundsInRoot }.sortedBy { abs(it.center.y - clicked.center.y) }
            assertTrue(ordered.size >= 2, "the class glyph and two failed method glyphs")
            val selected = ordered[0]
            val unselected = ordered[1]

            val image = onRoot().captureToImage().toPixelMap()
            val selectedPixel = image[sampleX(selected), sampleY(selected)]
            val unselectedPixel = image[sampleX(unselected), sampleY(unselected)]
            System.err.println(
                "glyph-glow selected=%s unselected=%s".format(selectedPixel, unselectedPixel),
            )
            val selectedPinkness = selectedPixel.red - selectedPixel.green
            val unselectedPinkness = unselectedPixel.red - unselectedPixel.green
            assertTrue(
                selectedPinkness <= unselectedPinkness + 0.02f,
                "selected glyph halo $selectedPixel should not be pinker than unselected $unselectedPixel",
            )
        } finally {
            runManager.dispose()
            project.dispose()
        }
    }

    @Test
    fun `run status colors clear 4_5 on the content plane`() {
        // The run content is no longer on glass: it paints the same opaque plane as the editor.
        val plane = blendOver(
            DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
            DesignTokens.background,
        )
        val texts = linkedMapOf(
            "text" to DesignTokens.text,
            "muted" to DesignTokens.muted,
            "accent2" to DesignTokens.accent2,
            "neonLime" to DesignTokens.neonLime,
        )
        for ((name, color) in texts) {
            val ratio = contrastRatio(color, plane)
            System.err.println("run-status %s=%.3f".format(name, ratio))
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "$name on the content plane = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        }
        // The failed-status word sits on the black 45% label band.
        val banded = blendOver(DesignTokens.chromeLabelScrim, plane)
        val pink = contrastRatio(DesignTokens.neonPink, banded)
        System.err.println("run-status neonPink-on-band=%.3f".format(pink))
        assertTrue(
            pink >= DesignTokens.contrastText,
            "neonPink on the status band = %.3f < %.1f".format(pink, DesignTokens.contrastText),
        )
    }

    private fun managerWith(
        id: String,
        title: String,
        context: KeyContext = KeyContext.GLOBAL,
        content: @Composable (Modifier) -> Unit,
    ): ToolWindowManager = ToolWindowManager().apply {
        register(ToolWindow(id, title, context, content))
        activate(id)
    }

    private fun assertContentPlane(name: String, rendered: IntArray, x: Int, y: Int) {
        val actual = Color(rendered[index(x, y, SCENE)])
        val expected = blendOver(
            DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
            DesignTokens.background,
        )
        System.err.println("%s pixel=%s expected=%s".format(name, hex(actual), hex(expected)))
        assertTrue(
            channelDistance(actual.toArgb(), expected.toArgb()) <= 2,
            "$name should paint the content plane: %s vs %s at ($x, $y)".format(
                hex(actual),
                hex(expected),
            ),
        )
    }

    private fun renderScene(width: Int, height: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) { content() }
        }
        return try {
            var nanos = 0L
            repeat(2) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            val image = scene.render(nanos)
            val bitmap = image.toComposeImageBitmap()
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels)
            image.close()
            pixels
        } finally {
            scene.close()
        }
    }

    private fun sampleX(bounds: Rect): Int = (bounds.right + 3f).toInt()
    private fun sampleY(bounds: Rect): Int = bounds.center.y.toInt()

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun spread(pixel: Int): Int = maxOf(red(pixel), green(pixel), blue(pixel)) - minOf(red(pixel), green(pixel), blue(pixel))

    private fun channelDistance(pixel: Int, other: Int): Int =
        maxOf(
            abs(red(pixel) - red(other)),
            abs(green(pixel) - green(other)),
            abs(blue(pixel) - blue(other)),
        )

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF
    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF
    private fun hex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private companion object {
        const val SCENE = 400
    }
}
