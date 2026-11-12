package stride.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.glass.LocalNativeGlass
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.relativeLuminance
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.search.FileNameIndex

/**
 * The Search Everywhere popup on both drawing paths: one selection pill per selected item and no
 * underline under the selected tab or bar on the selected row's left edge. The offscreen native
 * render has no system material, so the native leg checks only that structure; the pill and label
 * contrast rest on the token model in `DesignTokensContrastTest`.
 */
@ExtendWith(FixedUiFontExtension::class)
class SearchSelectionPillTest {
    @TempDir
    lateinit var temp: Path

    @AfterEach
    fun restoreDark() {
        AppearanceState.set(Appearance.DARK)
    }

    @Test
    fun `the selected tab and row carry one pill on the Skia path and its structure on the native path`() {
        val root = temp.resolve("selection-pill-project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/AlphaOne.kt"), "package demo\n\nclass AlphaOne\n")
        Files.writeString(root.resolve("src/AlphaTwo.kt"), "package demo\n\nclass AlphaTwo\n")
        val project = Project(root)
        try {
            assertTrue(project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            for (native in listOf(false, true)) {
                for (appearance in listOf(Appearance.DARK, Appearance.LIGHT)) {
                    val pixels = popupPixels(project, appearance, native)
                    val label = "${if (native) "native" else "skia"} $appearance"
                    assertTabHasNoUnderline(label, pixels)
                    assertRowHasNoEdgeBar(label, pixels)
                    // No panel exists behind the native material, so its contrast is the token
                    // model's job; here it would only measure the raw desktop.
                    if (!native) assertPillReadsAgainstThePanel(label, pixels)
                }
            }
        } finally {
            project.dispose()
        }
    }

    private fun assertTabHasNoUnderline(label: String, pixels: IntArray) {
        // The selected chip is "All": 16 dp into the popup, 12 dp padding, then the label. Its
        // pill is centred in the 30 dp row, so find the rows that differ from the tab-row panel
        // instead of assuming the chip's height.
        val left = popupLeft + 22
        val right = popupLeft + 52
        val panel = bandMean(pixels, popupLeft + 140, popupLeft + 180, TAB_TOP + 1, TAB_TOP + 2)
        val pill = (TAB_TOP until TAB_BOTTOM)
            .map { y -> y to bandMean(pixels, left, right, y, y) }
            .filter { (_, mean) -> abs(mean - panel) > 0.01 }
        assertTrue(pill.size >= 8, "$label: the selected tab drew no pill (${pill.size} rows differ from the panel)")
        // The label glyphs sit in the middle rows; compare the bottom edge with the clear fill
        // just below the pill's top rim.
        val interior = pill.slice(2..4).map { it.second }.average()
        val bottom = pill.slice(pill.size - 2..pill.lastIndex).map { it.second }.average()
        val interiorSpread = bandSpread(pixels, left, right, pill[2].first, pill[4].first)
        val bottomSpread = bandSpread(pixels, left, right, pill[pill.size - 2].first, pill.last().first)
        System.err.println(
            "selection-pill $label tab rows=${pill.map { it.first }} interior=%.4f bottom=%.4f spread=%.4f/%.4f"
                .format(interior, bottom, interiorSpread, bottomSpread),
        )
        assertTrue(
            abs(interior - bottom) < 0.04,
            "$label: the selected tab's bottom edge differs from its pill (%.4f vs %.4f); an underline is drawn"
                .format(bottom, interior),
        )
        assertTrue(
            abs(interiorSpread - bottomSpread) < 0.05,
            "$label: the selected tab's bottom edge is more saturated than its pill (%.4f vs %.4f)"
                .format(bottomSpread, interiorSpread),
        )
    }

    private fun assertRowHasNoEdgeBar(label: String, pixels: IntArray) {
        // Rows are inset 6/2 dp; the old 2 dp bar sat at 7..9 dp. The band is on the row's
        // vertical centre (ROW_TOP+14..+18), where the pill's straight side shows any bar that
        // the 8 dp rounded corner would clip at the top of the row.
        val edge = bandMean(pixels, popupLeft + 7, popupLeft + 9, ROW_TOP + 14, ROW_TOP + 18)
        val interior = bandMean(pixels, 300, 400, ROW_TOP + 14, ROW_TOP + 18)
        val edgeSpread = bandSpread(pixels, popupLeft + 7, popupLeft + 9, ROW_TOP + 14, ROW_TOP + 18)
        val interiorSpread = bandSpread(pixels, 300, 400, ROW_TOP + 14, ROW_TOP + 18)
        System.err.println(
            "selection-pill $label row edge=%.4f interior=%.4f spread=%.4f/%.4f"
                .format(edge, interior, edgeSpread, interiorSpread),
        )
        assertTrue(
            abs(edge - interior) < 0.03,
            "$label: the selected row's left edge differs from its pill (%.4f vs %.4f); a bar is drawn"
                .format(edge, interior),
        )
        assertTrue(
            abs(edgeSpread - interiorSpread) < 0.05,
            "$label: the left edge is more saturated than the pill (%.4f vs %.4f)"
                .format(edgeSpread, interiorSpread),
        )
    }

    private fun assertPillReadsAgainstThePanel(label: String, pixels: IntArray) {
        // Inside the selected chip but left of its label, so no glyph affects the sample.
        val tabPill = bandMean(pixels, popupLeft + 20, popupLeft + 26, TAB_TOP + 12, TAB_TOP + 16)
        // The group header above the first result is plain panel, with no label past x=300.
        val panel = bandMean(pixels, 300, 400, GROUP_TOP + 8, GROUP_TOP + 11)
        val rowPill = bandMean(pixels, 300, 400, ROW_TOP + 3, ROW_TOP + 7)
        val tabRatio = ratio(tabPill, panel)
        val rowRatio = ratio(rowPill, panel)
        System.err.println(
            "selection-pill $label tabRatio=%.3f rowRatio=%.3f".format(tabRatio, rowRatio),
        )
        assertTrue(tabRatio >= 1.15, "$label: the tab pill reads at only %.3f against its row".format(tabRatio))
        assertTrue(rowRatio >= 1.15, "$label: the row pill reads at only %.3f against the panel".format(rowRatio))
    }

    private fun popupPixels(project: Project, appearance: Appearance, native: Boolean): IntArray {
        AppearanceState.set(appearance)
        val search = SearchEverywhere(project)
        val desktop = if (appearance == Appearance.LIGHT) Color.White else Color.Black
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(
                LocalAnimationsEnabled provides false,
                LocalNativeGlass provides native,
            ) {
                Box(Modifier.fillMaxSize().background(desktop)) {
                    SearchEverywherePopup(
                        search = search,
                        onChoose = {},
                        onClose = {},
                        initialQuery = "Alpha",
                    )
                }
            }
        }
        return try {
            var nanos = 0L
            var last: org.jetbrains.skia.Image? = null
            val start = System.nanoTime()
            while ((System.nanoTime() - start) / 1_000_000 < 800) {
                last?.close()
                last = scene.render(nanos)
                nanos += 16_000_000L
                Thread.sleep(10)
            }
            val image = requireNotNull(last) { "no frame was rendered" }
            val bitmap = image.toComposeImageBitmap()
            val pixels = IntArray(WIDTH * HEIGHT)
            bitmap.readPixels(pixels)
            image.close()
            pixels
        } finally {
            scene.close()
        }
    }

    private fun bandMean(pixels: IntArray, left: Int, right: Int, top: Int, bottom: Int): Double {
        var total = 0.0
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                total += relativeLuminance(Color(pixels[index(x, y)]))
                count++
            }
        }
        return total / count
    }

    /** The mean channel spread, so a saturated bar shows even when its luminance is near the pill's. */
    private fun bandSpread(pixels: IntArray, left: Int, right: Int, top: Int, bottom: Int): Double {
        var total = 0.0
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val color = Color(pixels[index(x, y)])
                total += (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue))
                count++
            }
        }
        return total / count
    }

    private fun ratio(first: Double, second: Double): Double =
        (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    companion object {
        private const val WIDTH = 800
        private const val HEIGHT = 400
        private const val POPUP_TOP = 96
        private const val TAB_TOP = POPUP_TOP + 52
        private const val TAB_BOTTOM = TAB_TOP + 30
        private const val GROUP_TOP = TAB_BOTTOM
        private const val ROW_TOP = GROUP_TOP + 22

        private val popupLeft: Int get() = ((WIDTH - DesignTokens.popupWidth.value) / 2f).toInt()

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(SearchSelectionPillTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
