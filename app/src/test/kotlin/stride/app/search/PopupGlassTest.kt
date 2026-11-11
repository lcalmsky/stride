package stride.app.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import stride.app.editor.find.FindBar
import stride.app.editor.find.FindBarState
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.MeshBackground
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.relativeLuminance
import stride.core.services.Project
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class PopupGlassTest {
    private val panelShape = RoundedCornerShape(DesignTokens.radiusPopup)

    @Test
    fun `popup fade keeps the backdrop blurred at the mid and final frames`() {
        val holder = AlphaHolder()
        val width = 400
        val height = 300
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalAnimationsEnabled provides true) { FadingScene(holder) }
        }
        var midPixels: IntArray? = null
        var midAlpha = -1f
        var midMillis = -1L
        var finalPixels: IntArray? = null
        var finalAlpha = -1f
        try {
            var nanos = 0L
            while (nanos <= 600_000_000L) {
                val image = scene.render(nanos)
                val pixels = IntArray(width * height)
                image.toComposeImageBitmap().readPixels(pixels)
                image.close()
                val alpha = holder.value
                if (midPixels == null && alpha >= 0.4f && alpha <= 0.6f) {
                    midPixels = pixels
                    midAlpha = alpha
                    midMillis = nanos / 1_000_000
                }
                if (nanos == 600_000_000L) {
                    finalPixels = pixels
                    finalAlpha = alpha
                }
                nanos += 8_000_000L
            }
        } finally {
            scene.close()
        }
        val mid = requireNotNull(midPixels) { "no frame with an alpha between 0.4 and 0.6" }
        val last = requireNotNull(finalPixels) { "no final frame" }
        val midPattern = meanAdjacentDiff(mid, width, 10, 80, 44, 256)
        val midBackdrop = meanAdjacentDiff(mid, width, 104, 296, 44, 256)
        val finalPattern = meanAdjacentDiff(last, width, 10, 80, 44, 256)
        val finalBackdrop = meanAdjacentDiff(last, width, 104, 296, 44, 256)
        System.err.println(
            "popup-fade mid t=${midMillis}ms a=$midAlpha backdrop=$midBackdrop pattern=$midPattern " +
                "ratio=${midBackdrop / midPattern}",
        )
        System.err.println(
            "popup-fade final a=$finalAlpha backdrop=$finalBackdrop pattern=$finalPattern " +
                "ratio=${finalBackdrop / finalPattern}",
        )
        assertEquals(1f, finalAlpha, "the fade ends at full alpha")
        assertTrue(
            midBackdrop < midPattern * 0.5,
            "mid-frame backdrop mean $midBackdrop should be below half the pattern mean $midPattern " +
                "(ratio ${midBackdrop / midPattern}, alpha $midAlpha)",
        )
        assertTrue(
            finalBackdrop < finalPattern * 0.5,
            "final backdrop mean $finalBackdrop should be below half the pattern mean $finalPattern",
        )
    }

    @Test
    fun `popup panel chain keeps the backdrop blur when a shadow and clip precede it`() {
        val withShadow = chainPixels(listOf(Cover.SHADOW, Cover.CLIP))
        val withoutShadow = chainPixels(listOf(Cover.CLIP))
        val withShadowRatio = backdropRatio(withShadow)
        val withoutShadowRatio = backdropRatio(withoutShadow)
        System.err.println("popup-chain shadow ratio=$withShadowRatio")
        System.err.println("popup-chain no-shadow ratio=$withoutShadowRatio")
        assertTrue(withShadowRatio < 0.5, "the shadow chain should still blur (ratio $withShadowRatio)")
        assertTrue(withoutShadowRatio < 0.5, "the bare chain should still blur (ratio $withoutShadowRatio)")
    }

    @Test
    fun `search popup panel shows the color drawn behind it`() {
        val search = SearchEverywhere(Project(Path.of("build/tmp/popup-glass-project")))
        val dark = popupPixels(Color(0xFF103050), search)
        val warm = popupPixels(Color(0xFF503010), search)
        val points = listOf(400 to 108, 450 to 160, 600 to 165)
        for ((x, y) in points) {
            val first = dark[index(x, y, 800)]
            val second = warm[index(x, y, 800)]
            System.err.println("popup-panel point=($x,$y) dark=${hex(first)} warm=${hex(second)} raised=${hex(DesignTokens.raised.toArgb())}")
            assertTrue(
                channelDistance(first, second) >= 8,
                "panel pixel ($x,$y) should follow the background color: ${hex(first)} vs ${hex(second)}",
            )
            assertTrue(
                channelDistance(first, DesignTokens.raised.toArgb()) > 2,
                "panel pixel ($x,$y) should not be the opaque raised color: ${hex(first)}",
            )
            assertTrue(
                channelDistance(second, DesignTokens.raised.toArgb()) > 2,
                "panel pixel ($x,$y) should not be the opaque raised color: ${hex(second)}",
            )
        }
    }

    @Test
    fun `popup interior shows the backdrop at about two to one`() {
        val search = SearchEverywhere(Project(Path.of("build/tmp/popup-detail-project")))
        val pixels = scenePixels(800, 400) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = 180f
                    var row = 0
                    var y = 0f
                    while (y < size.height) {
                        var col = 0
                        var x = 0f
                        while (x < size.width) {
                            drawRect(
                                if ((row + col) % 2 == 0) Color.White else Color.Black,
                                Offset(x, y),
                                Size(cell, cell),
                            )
                            x += cell
                            col += 1
                        }
                        y += cell
                        row += 1
                    }
                }
                SearchEverywherePopup(search = search, onChoose = {}, onClose = {}, initialQuery = "")
            }
        }
        // Panel interior at y=120: one sample over a white cell, one over a black cell.
        val light = relativeLuminance(Color(pixels[index(450, 120, 800)]))
        val dark = relativeLuminance(Color(pixels[index(630, 120, 800)]))
        val ratio = (maxOf(light, dark) + 0.05) / (minOf(light, dark) + 0.05)
        System.err.println("popup-detail light=%.4f dark=%.4f contrast=%.3f".format(light, dark, ratio))
        assertTrue(ratio >= 1.9, "backdrop detail through the popup is %.3f:1, expected at least 2:1".format(ratio))
        assertTrue(ratio <= 3.2, "backdrop detail through the popup is %.3f:1, expected about 3:1".format(ratio))
    }

    @Test
    fun `popup text clears 4_5 on a bright backdrop`() {
        val root = Path.of("build/tmp/recent-bright-project")
        val files = listOf(
            VFile(root.resolve("Alpha.kt")),
            VFile(root.resolve("Beta.kt")),
            VFile(root.resolve("Gamma.kt")),
        )
        val pixels = scenePixels(800, 400) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                RecentFilesPopup(files = files, projectRoot = root, onChoose = {}, onClose = {})
            }
        }
        // Row band of the first entry (header ends at 144, rows are 32 px tall).
        var best = 0.0
        for (y in 148 until 208) {
            for (x in 100 until 700) {
                val luma = relativeLuminance(Color(pixels[index(x, y, 800)]))
                if (luma < 0.15) continue
                var local = 1.0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val localLuma = relativeLuminance(Color(pixels[index(x + dx, y + dy, 800)]))
                        if (localLuma < local) local = localLuma
                    }
                }
                val ratio = (luma + 0.05) / (local + 0.05)
                if (ratio > best) best = ratio
            }
        }
        System.err.println("popup-text-bright best=%.3f".format(best))
        assertTrue(
            best >= DesignTokens.contrastText,
            "popup text on the bright glass tops out at %.3f:1".format(best),
        )
    }

    @Test
    fun `the selected recent row carries the neon line on its capsule edge`() {
        val root = Path.of("build/tmp/recent-glass-project")
        val files = listOf(
            VFile(root.resolve("Alpha.kt")),
            VFile(root.resolve("Beta.kt")),
            VFile(root.resolve("Gamma.kt")),
        )
        val pixels = scenePixels(800, 400) {
            Box(Modifier.fillMaxSize().background(DesignTokens.background)) {
                RecentFilesPopup(files = files, projectRoot = root, onChoose = {}, onClose = {})
            }
        }
        // Panel left is (800-680)/2=60; rows are inset 6, the line sits 2 px wide from x=67.
        // Header 96..144, rows 144..176 (calm), 176..208 (selected), 208..240.
        val line = pixels[index(68, 192, 800)]
        val calm = pixels[index(68, 160, 800)]
        System.err.println("recent-line selected=${hex(line)} calm=${hex(calm)}")
        val cyan = green(line) + blue(line) - 2 * red(line)
        val calmCyan = green(calm) + blue(calm) - 2 * red(calm)
        assertTrue(red(line) < green(line) && red(line) < blue(line), "the selected edge should be cyan: ${hex(line)}")
        assertTrue(
            cyan > calmCyan + 40,
            "the selected row's left edge should carry the cyan line (selected ${hex(line)}, calm ${hex(calm)})",
        )
    }

    @Test
    fun `find bar is the neutral glass fill instead of the flat surface`() {
        val pixels = scenePixels(600, 400) {
            MeshBackground(Modifier.fillMaxSize()) {
                FindBar(
                    state = FindBarState(),
                    editor = null,
                    onFocusContext = {},
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                )
            }
        }
        var widest = -1
        var widestX = -1
        var widestY = -1
        // Below the top rim and above the underline glow, so only the panel fill is measured.
        for (y in 6 until 18) {
            for (x in 60 until 400) {
                val spread = spread(pixels[index(x, y, 600)])
                if (spread > widest) {
                    widest = spread
                    widestX = x
                    widestY = y
                }
            }
        }
        val pixel = pixels[index(widestX, widestY, 600)]
        val lowest = relativeLuminance(chromeOver(DesignTokens.fieldBottom))
        val highest = relativeLuminance(chromeOver(DesignTokens.backgroundLift))
        val luminance = relativeLuminance(Color(pixel))
        System.err.println(
            "find-bar widest=$widest at ($widestX,$widestY) pixel=${hex(pixel)} luminance=%.4f band=%.4f..%.4f"
                .format(luminance, lowest, highest),
        )
        // The cooler chrome glass keeps the field's graphite tint; anything wider would be a hue.
        assertTrue(widest <= 15, "the find bar should stay neutral, widest channel spread = $widest")
        assertTrue(
            luminance >= lowest - 0.003 && luminance <= highest + 0.004,
            "the find bar luminance %.4f is outside the bar glass band %.4f..%.4f".format(luminance, lowest, highest),
        )
    }

    /** The chrome glass over a neutral field color: the base plus its share of the field. */
    private fun chromeOver(field: Color): Color = Color(
        (DesignTokens.glassBase.red + DesignTokens.chromeBackdropMix * field.red).coerceIn(0f, 1f),
        (DesignTokens.glassBase.green + DesignTokens.chromeBackdropMix * field.green).coerceIn(0f, 1f),
        (DesignTokens.glassBase.blue + DesignTokens.chromeBackdropMix * field.blue).coerceIn(0f, 1f),
    )

    @Test
    fun `popup draws without a fade when animations are disabled`() {
        val holder = AlphaHolder()
        val width = 400
        val height = 300
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) { FadingScene(holder) }
        }
        val first = IntArray(width * height)
        val later = IntArray(width * height)
        try {
            scene.render(0L).let { image ->
                image.toComposeImageBitmap().readPixels(first)
                image.close()
            }
            assertEquals(1f, holder.value, "the first frame should already be at full alpha")
            scene.render(500_000_000L).let { image ->
                image.toComposeImageBitmap().readPixels(later)
                image.close()
            }
        } finally {
            scene.close()
        }
        assertArrayEquals(first, later, "with animations disabled every frame renders the final state")
    }

    private class AlphaHolder {
        var value = -1f
    }

    private enum class Cover { SHADOW, CLIP }

    /**
     * The popup layout at mid fade: the scrim alpha is multiplied by [fade] on the drawing side and
     * only the panel contents carry an alpha layer, so `backdropGlass` keeps its real backdrop.
     */
    @Composable
    private fun FadingScene(holder: AlphaHolder) {
        val fade = if (!LocalAnimationsEnabled.current) {
            1f
        } else {
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                alpha.animateTo(
                    1f,
                    tween(DesignTokens.motionPopupMillis, easing = DesignTokens.standardEasing),
                )
            }
            alpha.value
        }
        holder.value = fade
        Box(Modifier.fillMaxSize()) {
            Checkerboard()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DesignTokens.scrim.copy(alpha = DesignTokens.scrim.alpha * fade)),
            ) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                        .width(200.dp)
                        .height(220.dp)
                        .then(panelChain(listOf(Cover.SHADOW, Cover.CLIP)))
                        .then(if (LocalAnimationsEnabled.current) Modifier.graphicsLayer { alpha = fade } else Modifier),
                ) {}
            }
        }
    }

    private fun panelChain(covers: List<Cover>): Modifier {
        var modifier: Modifier = Modifier
        if (Cover.SHADOW in covers) modifier = modifier.shadow(24.dp, panelShape, clip = false)
        if (Cover.CLIP in covers) modifier = modifier.clip(panelShape)
        return modifier.backdropGlass(panelShape)
    }

    private fun chainPixels(covers: List<Cover>): IntArray = scenePixels(400, 300) {
        Box(Modifier.fillMaxSize()) {
            Checkerboard()
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .width(200.dp)
                    .height(220.dp)
                    .then(panelChain(covers)),
            )
        }
    }

    private fun backdropRatio(pixels: IntArray): Double {
        val pattern = meanAdjacentDiff(pixels, 400, 10, 80, 44, 256)
        val backdrop = meanAdjacentDiff(pixels, 400, 104, 296, 44, 256)
        return backdrop / pattern
    }

    private fun popupPixels(backdrop: Color, search: SearchEverywhere): IntArray = scenePixels(800, 320) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(backdrop))
            SearchEverywherePopup(search = search, onChoose = {}, onClose = {}, initialQuery = "")
        }
    }

    @Composable
    private fun Checkerboard() {
        Canvas(Modifier.fillMaxSize()) {
            val cell = 4f
            var row = 0
            var y = 0f
            while (y < size.height) {
                var col = 0
                var x = 0f
                while (x < size.width) {
                    val color = if ((row + col) % 2 == 0) Color.White else Color.Black
                    drawRect(color, Offset(x, y), Size(cell, cell))
                    x += cell
                    col += 1
                }
                y += cell
                row += 1
            }
        }
    }

    private fun scenePixels(width: Int, height: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) { content() }
        }
        return try {
            scene.render(0L).close()
            val image = scene.render(16_000_000L)
            val bitmap = image.toComposeImageBitmap()
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels)
            image.close()
            pixels
        } finally {
            scene.close()
        }
    }

    private fun meanAdjacentDiff(pixels: IntArray, width: Int, fromX: Int, toX: Int, fromY: Int, toY: Int): Double {
        var total = 0.0
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                val left = relativeLuminance(Color(pixels[index(x, y, width)]))
                val right = relativeLuminance(Color(pixels[index(x + 1, y, width)]))
                total += abs(right - left)
                count += 1
            }
        }
        return total / count
    }

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun channelDistance(first: Int, second: Int): Int =
        maxOf(
            abs(red(first) - red(second)),
            abs(green(first) - green(second)),
            abs(blue(first) - blue(second)),
        )

    private fun spread(pixel: Int): Int =
        maxOf(red(pixel), green(pixel), blue(pixel)) - minOf(red(pixel), green(pixel), blue(pixel))

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    private fun hex(pixel: Int): String = "#%06X".format(pixel and 0xFFFFFF)
}
