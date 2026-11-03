package stride.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.ui.LocalAnimationsEnabled

class GlassModifiersTest {
    @Test
    fun `glass panel picks up the color of the mesh behind it`() {
        val withMesh = pixelArray(200, 120) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            }
        }
        val withoutMesh = pixelArray(200, 120) {
            Box(Modifier.fillMaxSize().background(DesignTokens.background), contentAlignment = Alignment.Center) {
                Box(Modifier.size(100.dp, 80.dp).glassPanel())
            }
        }
        var widest = -1
        var bestX = -1
        var bestY = -1
        for (y in 22 until 98) {
            for (x in 52 until 148) {
                val width = spread(withMesh[index(x, y, 200)])
                if (width > widest) {
                    widest = width
                    bestX = x
                    bestY = y
                }
            }
        }
        assertTrue(widest >= 8, "widest channel spread inside the panel = $widest")
        assertNotEquals(
            withoutMesh[index(bestX, bestY, 200)],
            withMesh[index(bestX, bestY, 200)],
            "the glass pixel should differ from a panel without mesh",
        )
    }

    @Test
    fun `glass top border is brighter than the bottom border`() {
        val pixels = pixelArray(200, 120) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            }
        }
        val top = relativeLuminance(Color(pixels[index(100, 20, 200)]))
        val bottom = relativeLuminance(Color(pixels[index(100, 99, 200)]))
        assertTrue(top > bottom, "top border %.4f should be brighter than bottom %.4f".format(top, bottom))
    }

    @Test
    fun `backdrop glass blurs the sibling behind it`() {
        val pixels = pixelArray(200, 120) {
            Box(Modifier.fillMaxSize()) {
                Checkerboard()
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(100.dp)
                        .fillMaxHeight()
                        .backdropGlass(),
                )
            }
        }
        val pattern = meanAdjacentDiff(pixels, 200, 4, 96, 2, 118)
        val backdrop = meanAdjacentDiff(pixels, 200, 104, 198, 2, 118)
        val ratio = backdrop / pattern
        assertTrue(
            backdrop < pattern * 0.5,
            "backdrop mean %.4f should be below half the pattern mean %.4f (ratio %.3f)".format(backdrop, pattern, ratio),
        )
    }

    @Test
    fun `glass rendering is deterministic`() {
        val first = pixelArray(200, 120) { deterministicScene() }
        val second = pixelArray(200, 120) { deterministicScene() }
        assertArrayEquals(first, second, "two fresh scenes should render the same pixels")
    }

    @Test
    fun `glass panel blurs once per size and position`() {
        GlassCacheStats.panelBlurCount = 0
        renderFrames(200, 120, 30) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            }
        }
        assertEquals(1, GlassCacheStats.panelBlurCount, "a stable panel blurs once across 30 frames")
        renderFrames(300, 200, 3) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            }
        }
        assertEquals(2, GlassCacheStats.panelBlurCount, "a new scene size blurs again once")
    }

    @Test
    fun `content tint over the mesh is the opaque tint color`() {
        val pixels = pixelArray(200, 120) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).contentTint())
            }
        }
        val expected = blendOver(
            DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
            DesignTokens.background,
        )
        val actual = Color(pixels[index(100, 60, 200)])
        val red = abs(actual.red * 255f - expected.red * 255f)
        val green = abs(actual.green * 255f - expected.green * 255f)
        val blue = abs(actual.blue * 255f - expected.blue * 255f)
        assertTrue(
            red <= 1f && green <= 1f && blue <= 1f,
            "content tint over the mesh should be the opaque blend, not the mesh: " +
                "dR=%.2f dG=%.2f dB=%.2f".format(red, green, blue),
        )
    }

    @Test
    fun `cached glass panel slice is an opaque image`() {
        GlassCacheStats.lastSliceOpaque = false
        renderFrames(200, 120, 3) {
            MeshBackground(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            }
        }
        assertTrue(GlassCacheStats.lastSliceOpaque, "the cached panel slice should be opaque")
    }

    @Test
    fun `backdrop blur stays aligned with the panel edge`() {
        // Panel at (72,70) 256×160 over an 80 px checkerboard. The pattern outside the top edge
        // (row 68, 2 px above) and inside it (row 72, 2 px below) must share the same phase, and
        // the same holds for the columns 2 px outside and inside the left edge. A scaled blur that
        // anchors anywhere but the panel's top-left would show up as a non-zero shift.
        val pixels = pixelArray(400, 300) {
            Box(Modifier.fillMaxSize()) {
                Checkerboard(cellSize = 80f)
                Box(
                    Modifier
                        .offset(72.dp, 70.dp)
                        .size(256.dp, 160.dp)
                        .backdropGlass(),
                )
            }
        }
        val horizontal = profileShift(
            outside = { x -> pixels[index(x, 68, 400)] },
            inside = { x -> pixels[index(x, 72, 400)] },
            range = 100 until 300,
        )
        val vertical = profileShift(
            outside = { y -> pixels[index(70, y, 400)] },
            inside = { y -> pixels[index(74, y, 400)] },
            range = 90 until 210,
        )
        System.err.println("backdrop-align horizontal=$horizontal vertical=$vertical")
        assertEquals(0, horizontal, "the blurred pattern inside the top edge is shifted horizontally")
        assertEquals(0, vertical, "the blurred pattern inside the left edge is shifted vertically")
    }

    private fun profileShift(outside: (Int) -> Int, inside: (Int) -> Int, range: IntRange): Int {
        val outer = DoubleArray(range.count()) { relativeLuminance(Color(outside(range.first + it))) }
        val inner = DoubleArray(range.count()) { relativeLuminance(Color(inside(range.first + it))) }
        val outerMean = outer.average()
        val innerMean = inner.average()
        var bestShift = 0
        var best = -1.0
        for (shift in -24..24) {
            var sum = 0.0
            for (i in outer.indices) {
                val j = i + shift
                if (j in inner.indices) sum += (outer[i] - outerMean) * (inner[j] - innerMean)
            }
            if (abs(sum) > best) {
                best = abs(sum)
                bestShift = shift
            }
        }
        return bestShift
    }

    @Composable
    private fun Checkerboard(cellSize: Float = 4f) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = cellSize
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

    @Composable
    private fun deterministicScene() {
        MeshBackground(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            Box(Modifier.align(Alignment.TopStart).size(60.dp, 40.dp).backdropGlass())
        }
    }

    private fun pixelArray(width: Int, height: Int, content: @Composable () -> Unit): IntArray {
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

    private fun renderFrames(width: Int, height: Int, frames: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) { content() }
        }
        try {
            var nanos = 0L
            repeat(frames) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
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

    private fun spread(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return maxOf(red, green, blue) - minOf(red, green, blue)
    }
}
