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
    fun `tool window tab strip is the glass fill over the neutral field`() {
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
        val pixel = pixels[index(380, 295, SCENE)]
        val spread = spread(pixel)
        val luminance = relativeLuminance(Color(pixel))
        val lowest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.fieldBottom))
        val highest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.backgroundLift))
        System.err.println(
            "tab-strip pixel=(%d,%d,%d) spread=%d luminance=%.4f band=%.4f..%.4f".format(
                red(pixel),
                green(pixel),
                blue(pixel),
                spread,
                luminance,
                lowest,
                highest,
            ),
        )
        // The cool-graphite field keeps a few levels of spread; a wider one would be a hue.
        assertTrue(spread <= 12, "tab strip channel spread = $spread, expected neutral glass")
        assertTrue(
            luminance >= lowest - 0.002 && luminance <= highest + 0.002,
            "tab strip luminance %.4f is outside the fill band %.4f..%.4f".format(luminance, lowest, highest),
        )
    }

    @Test
    fun `terminal empty cell is the glass fill over the mesh`() {
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
            assertGlassOverMesh("terminal", rendered, 380, 380)
        } finally {
            session.dispose()
            project.dispose()
        }
    }

    @Test
    fun `run console empty cell is the glass fill over the mesh`() {
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
            assertGlassOverMesh("run console", rendered, 380, 380)
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
    fun `run status colors clear 4_5 on the painted glass slab`() {
        // The panel is glass now, not the removed content tint: its brightest slice is glassFill
        // over the field's lift. This is the surface the run text is actually painted on.
        val slab = blendOver(DesignTokens.glassFill, DesignTokens.backgroundLift)
        val texts = linkedMapOf(
            "text" to DesignTokens.text,
            "muted" to DesignTokens.muted,
            "accent2" to DesignTokens.accent2,
            "neonLime" to DesignTokens.neonLime,
        )
        for ((name, color) in texts) {
            val ratio = contrastRatio(color, slab)
            System.err.println("run-status %s=%.3f".format(name, ratio))
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "$name on the glass slab = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        }
        // The failed-status word sits on the black 45% label band, the chrome bars' local
        // compensation; neonPink alone is 4.09:1 on the slab.
        val banded = blendOver(DesignTokens.chromeLabelScrim, slab)
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

    private fun assertGlassOverMesh(name: String, rendered: IntArray, x: Int, y: Int) {
        val actual = Color(rendered[index(x, y, SCENE)])
        val luminance = relativeLuminance(actual)
        val lowest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.fieldBottom))
        val highest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.backgroundLift))
        System.err.println(
            "%s glass pixel=(%d,%d,%d) luminance=%.4f band=%.4f..%.4f".format(
                name,
                (actual.red * 255f).toInt(),
                (actual.green * 255f).toInt(),
                (actual.blue * 255f).toInt(),
                luminance,
                lowest,
                highest,
            ),
        )
        assertTrue(
            luminance >= lowest - 0.002 && luminance <= highest + 0.004,
            "$name glass luminance %.4f is outside the fill band %.4f..%.4f at ($x, $y)".format(
                luminance,
                lowest,
                highest,
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

    private companion object {
        const val SCENE = 400
    }
}
