package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.LAYERED_SCROLL_ENTER_LINES
import stride.app.editor.TextFileEditor
import stride.app.ui.theme.UiFont
import stride.app.ui.theme.UiFontPreference
import stride.core.services.Application
import stride.core.vfs.VFile

/**
 * Measures the per-scroll-frame cost of the two editor viewport paths on 1 000, 5 000 and
 * 10 000-line documents. Every frame advances the scroll offset, so the numbers include the whole
 * repaint a wheel step causes, not a static frame. Three sequential runs per combination; the
 * median of their averages is reported.
 */
class ScrollFrameTimingTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the layered path is cheaper on a document past the threshold`() {
        val measured = SCENES.map { scene ->
            val layerFree = measure(scene.lines, layered = false, density = scene.density)
            val layered = measure(scene.lines, layered = true, density = scene.density)
            System.err.println(
                "scroll-frame lines=${scene.lines} density=${scene.density} " +
                    "layer-free=%.3f ms layered=%.3f ms".format(Locale.ROOT, layerFree, layered),
            )
            Triple(scene, layerFree, layered)
        }
        // The threshold scene sits at the ~8 ms scroll budget; the number is reported, not
        // asserted, because the harness baseline itself moves with machine load.
        val threshold = measured.first {
            it.first.lines == LAYERED_SCROLL_ENTER_LINES && it.first.density == 2f
        }
        System.err.println(
            "scroll-frame threshold=$LAYERED_SCROLL_ENTER_LINES layer-free=%.3f ms"
                .format(Locale.ROOT, threshold.second),
        )
        // Past the threshold the difference is large enough to be robust against harness noise.
        for ((scene, layerFree, layered) in measured) {
            val pastThreshold = scene.lines >= 2 * LAYERED_SCROLL_ENTER_LINES
            if (pastThreshold) {
                assertTrue(
                    layered < layerFree,
                    "at ${scene.lines} lines the layered scroll (%.3f ms) should beat the layer-free one " +
                        "(%.3f ms)".format(Locale.ROOT, layered, layerFree),
                )
            }
        }
    }

    private fun measure(lines: Int, layered: Boolean, density: Float): Double {
        val root = temp.resolve("scroll-$lines-$layered-$density")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Large.kt"), kotlinLines(lines))
        val state = AppState()
        var scene: ImageComposeScene? = null
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Large.kt"))) as TextFileEditor
            editor.scrollLayerOverride = layered
            scene = ImageComposeScene(2560, 1640, Density(density)) {
                CompositionLocalProvider(
                    LocalWordmarkCaretBlink provides false,
                    LocalAnimationsEnabled provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            var nanos = 0L
            repeat(WARMUP_FRAMES) {
                scene.render(nanos).close()
                nanos += FRAME_NANOS
            }
            check(editor.verticalScroll.layered == layered) {
                "the viewport did not take the forced ${if (layered) "layered" else "layer-free"} path"
            }
            val runs = DoubleArray(RUNS)
            for (run in runs.indices) {
                editor.verticalScroll.dispatchRawDelta(-1_000_000f)
                editor.verticalScroll.dispatchRawDelta(START_OFFSET_PX)
                repeat(SETTLE_FRAMES) {
                    scene.render(nanos).close()
                    nanos += FRAME_NANOS
                }
                var total = 0.0
                repeat(MEASURED_FRAMES) {
                    editor.verticalScroll.dispatchRawDelta(SCROLL_STEP_PX)
                    val begin = System.nanoTime()
                    scene.render(nanos).close()
                    total += (System.nanoTime() - begin) / 1_000_000.0
                    nanos += FRAME_NANOS
                }
                runs[run] = total / MEASURED_FRAMES
            }
            runs.sort()
            return runs[RUNS / 2]
        } finally {
            scene?.close()
            state.dispose()
        }
    }

    private fun kotlinLines(count: Int): String = buildString {
        repeat(count) { index ->
            if (index > 0) append('\n')
            append(
                when (index % 6) {
                    0 -> "    val value$index = compute($index, \"sample\") // case $index"
                    1 -> "    if (value$index > $index) return value$index else 0"
                    2 -> "    fun handle$index(input: String): Int = input.length + $index"
                    3 -> "    private val names$index = listOf(\"alpha\", \"beta\", \"gamma\")"
                    4 -> "    // Keeps the sample line realistic in length for the layout pass."
                    else -> "    println(\"line $index -> \${values[index % values.size]}\")"
                },
            )
        }
    }

    companion object {
        private const val RUNS = 3
        private const val WARMUP_FRAMES = 6
        private const val SETTLE_FRAMES = 4
        private const val MEASURED_FRAMES = 12
        private const val FRAME_NANOS = 16_000_000L
        private const val START_OFFSET_PX = 2_000f
        private const val SCROLL_STEP_PX = 160f

        private data class Scene(val lines: Int, val density: Float)

        private val SCENES = listOf(
            // The threshold sweep at the snapshot density (2×): the layer-free curve leaves the
            // budget between 1 000 and 1 200 lines.
            Scene(500, 2f),
            Scene(750, 2f),
            Scene(1_000, 2f),
            Scene(1_200, 2f),
            Scene(2_400, 2f),
            // The required document sizes. A 10 000-line file can only be laid out at density 1:
            // at 2× its 400 000 px content exceeds Compose's 262 143 px layout limit in the gutter.
            Scene(5_000, 2f),
            Scene(10_000, 1f),
        )

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            UiFont.preference = UiFontPreference(null)
            Application.resetForTests()
            Application.init(ScrollFrameTimingTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
