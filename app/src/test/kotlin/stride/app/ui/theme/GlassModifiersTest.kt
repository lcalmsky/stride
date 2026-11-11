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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.ui.LocalAnimationsEnabled

class GlassModifiersTest {
    @Test
    fun `glass panel is the neutral fill over the field`() {
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
        for (y in 24 until 96) {
            for (x in 54 until 146) {
                val width = spread(withMesh[index(x, y, 200)])
                if (width > widest) {
                    widest = width
                    bestX = x
                    bestY = y
                }
            }
        }
        val lowest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.fieldBottom))
        val highest = relativeLuminance(blendOver(DesignTokens.glassFill, DesignTokens.backgroundLift))
        val luminance = relativeLuminance(Color(withMesh[index(bestX, bestY, 200)]))
        // The field carries the documented cool-graphite tint, so a few levels of channel spread
        // are expected; anything wider would be a hue rather than a neutral surface.
        assertTrue(widest <= 10, "widest channel spread inside the panel = $widest, expected neutral glass")
        assertTrue(
            luminance >= lowest - 0.002 && luminance <= highest + 0.002,
            "panel luminance %.4f is outside the glass band %.4f..%.4f".format(luminance, lowest, highest),
        )
        assertNotEquals(
            withoutMesh[index(bestX, bestY, 200)],
            withMesh[index(bestX, bestY, 200)],
            "the glass pixel should differ from a panel without the field",
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
        // (row 68, 2 px above) and inside it (row 86, 16 px below, past the 12 dp lens bezel) must
        // share the same phase, and the same holds for the columns outside and inside the left
        // edge. A scaled blur that anchors anywhere but the panel's top-left shows as a shift.
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
            inside = { x -> pixels[index(x, 86, 400)] },
            range = 100 until 300,
        )
        val vertical = profileShift(
            outside = { y -> pixels[index(70, y, 400)] },
            inside = { y -> pixels[index(86, y, 400)] },
            range = 90 until 210,
        )
        System.err.println("backdrop-align horizontal=$horizontal vertical=$vertical")
        // One pixel of correlation noise is allowed; the blur must not anchor off the panel corner.
        assertTrue(abs(horizontal) <= 1, "the blurred pattern inside the top edge is shifted horizontally by $horizontal")
        assertTrue(abs(vertical) <= 1, "the blurred pattern inside the left edge is shifted vertically by $vertical")
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

    @Test
    fun `glass corners stay symmetric over a uniform backdrop`() {
        GlassShader.resetForTests()
        val pixels = glassRectPixels()
        val left = 100
        val top = 80
        val right = 300
        val bottom = 220
        // Sample past the specular bezel so the top-left rim does not skew the corner comparison.
        val depth = 26
        val radius = 18
        val inset = (radius - (radius - depth) / kotlin.math.sqrt(2f)).toInt()
        val corners = listOf(
            "top-left" to (left + inset to top + inset),
            "top-right" to (right - inset to top + inset),
            "bottom-left" to (left + inset to bottom - inset),
            "bottom-right" to (right - inset to bottom - inset),
        )
        val means = corners.map { (name, point) ->
            val mean = blockMean(pixels, 400, point.first, point.second, 2)
            System.err.println("glass-corner %s (%d,%d) mean=%.4f".format(name, point.first, point.second, mean))
            name to mean
        }
        val lowest = means.minOf { it.second }
        val highest = means.maxOf { it.second }
        assertTrue(
            highest - lowest <= 0.01,
            "the four corner bezel regions should match: ${means.joinToString { "${it.first}=%.4f".format(it.second) }}",
        )
    }

    @Test
    fun `the thin popup glass shows the backdrop at about three to one`() {
        // A popup draws its scrim before the glass, so the glass sees the scrimmed backdrop.
        val pixels = pixelArray(400, 300) {
            Box(Modifier.fillMaxSize()) {
                Checkerboard(cellSize = 180f)
                Box(Modifier.fillMaxSize().background(DesignTokens.scrim))
                Box(
                    Modifier
                        .offset(40.dp, 4.dp)
                        .size(320.dp, 292.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .backdropGlass(RoundedCornerShape(18.dp)),
                )
            }
        }
        val light = blockMean(pixels, 400, 90, 90, 4)
        val dark = blockMean(pixels, 400, 270, 90, 4)
        val ratio = (maxOf(light, dark) + 0.05) / (minOf(light, dark) + 0.05)
        System.err.println("glass-thin light=%.4f dark=%.4f contrast=%.3f".format(light, dark, ratio))
        assertTrue(
            ratio >= 2.5 && ratio <= 4.0,
            "backdrop detail through the popup glass is %.3f:1, expected about 3:1 to 4:1".format(ratio),
        )
    }

    private fun glassRectPixels(): IntArray = pixelArray(400, 300) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
            Box(
                Modifier
                    .offset(100.dp, 80.dp)
                    .size(200.dp, 140.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .backdropGlass(RoundedCornerShape(18.dp)),
            )
        }
    }

    private fun blockMean(pixels: IntArray, width: Int, centerX: Int, centerY: Int, radius: Int): Double {
        var total = 0.0
        var count = 0
        for (y in centerY - radius..centerY + radius) {
            for (x in centerX - radius..centerX + radius) {
                total += relativeLuminance(Color(pixels[index(x, y, width)]))
                count += 1
            }
        }
        return total / count
    }

    @Test
    fun `the liquid glass SkSL compiles`() {
        GlassShader.resetForTests()
        try {
            assertNotNull(GlassShader.runtimeEffect(), "the liquid glass SkSL must compile")
        } finally {
            GlassShader.resetForTests()
        }
    }

    @Test
    fun `the specular rim is brighter on the top edge than on the bottom`() {
        val pixels = pixelArray(400, 240) {
            Box(Modifier.fillMaxSize().background(Color(0xFF202020)), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(200.dp, 100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .backdropGlass(RoundedCornerShape(16.dp)),
                )
            }
        }
        val top = relativeLuminance(Color(pixels[index(200, 71, 400)]))
        val middle = relativeLuminance(Color(pixels[index(200, 120, 400)]))
        val bottom = relativeLuminance(Color(pixels[index(200, 168, 400)]))
        System.err.println("specular top=%.4f middle=%.4f bottom=%.4f".format(top, middle, bottom))
        assertTrue(top > middle + 0.002, "the top rim %.4f should be brighter than the interior %.4f".format(top, middle))
        assertTrue(top > bottom + 0.004, "the top rim %.4f should be brighter than the bottom %.4f".format(top, bottom))
    }

    @Test
    fun `backdrop glass keeps its rounded clip to itself`() {
        val pixels = pixelArray(400, 200) {
            Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                Box(
                    Modifier
                        .offset(4.dp, 4.dp)
                        .size(120.dp, 80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .backdropGlass(RoundedCornerShape(12.dp)),
                )
                Box(Modifier.offset(250.dp, 120.dp).size(100.dp, 50.dp).background(Color.Red))
            }
        }
        val red = Color(pixels[index(300, 145, 400)])
        assertTrue(
            red.red > 0.9f && red.green < 0.1f && red.blue < 0.1f,
            "a sibling drawn after the glass must not inherit its rounded clip: $red",
        )
    }

    @Test
    fun `the neon selection line is 2 dp wide with a cyan halo at density 2`() {
        val pixels = pixelArray(400, 60, density = 2f) {
            Box(
                Modifier
                    .size(200.dp, 30.dp)
                    .background(DesignTokens.surface)
                    .glassCapsule(
                        shape = RoundedCornerShape(DesignTokens.radiusCapsule),
                        lineAlpha = 1f,
                    ),
            )
        }
        val y = 30
        // The capsule is inset 6 dp; the 2 dp line spans 7..9 dp, i.e. device px 14..17.
        var barPixels = 0
        for (x in 10..22) {
            val pixel = pixels[index(x, y, 400)]
            if (red(pixel) < green(pixel) && red(pixel) < blue(pixel) && blue(pixel) > 180) barPixels += 1
        }
        val glow = pixels[index(20, y, 400)]
        val fill = pixels[index(360, y, 400)]
        val glowCyan = green(glow) + blue(glow) - 2 * red(glow)
        val fillCyan = green(fill) + blue(fill) - 2 * red(fill)
        System.err.println(
            "neon-bar bar=$barPixels glow=${hex(glow)} glowCyan=$glowCyan fill=${hex(fill)} fillCyan=$fillCyan",
        )
        assertTrue(barPixels >= 4, "the crisp line should be 2 dp = 4 device px wide, was $barPixels")
        assertTrue(
            glowCyan > fillCyan + 40,
            "the halo right of the line should be cyan-tinted (glow $glowCyan vs fill $fillCyan)",
        )
    }

    @Test
    fun `the lens bends the backdrop inside the bezel`() {
        GlassShader.resetForTests()
        try {
            // Same shader path twice: only the displacement differs, so a flat lens cannot pass.
            GlassShader.strengthOverride = 0f
            val flat = bezelPixels()
            GlassShader.strengthOverride = 6f
            val bent = bezelPixels()
            var total = 0
            var count = 0
            for (y in 110 until 190) {
                for (x in 103 until 110) {
                    total += channelDistance(flat[index(x, y, 400)], bent[index(x, y, 400)])
                    count += 1
                }
            }
            val mean = total.toDouble() / count
            System.err.println("lens-bend flat-vs-bent mean=%.3f".format(mean))
            assertTrue(mean > 0.2, "the lens displacement should change the bezel backdrop (mean %.3f)".format(mean))
        } finally {
            GlassShader.resetForTests()
        }
    }

    @Test
    fun `the selection capsule bends its backdrop but not at the ends`() {
        GlassShader.resetForTests()
        try {
            GlassShader.strengthOverride = 0f
            val flat = capsulePixels()
            GlassShader.strengthOverride = DesignTokens.capsuleLensStrength.value
            val bent = capsulePixels()
            // Inner capsule x 106..294, y 102..128; the bezel is the outer 6 dp of its top edge.
            val middle = meanChannelDistance(flat, bent, 160, 240, 102, 107)
            val leftEnd = meanChannelDistance(flat, bent, 106, 110, 102, 107)
            val rightEnd = meanChannelDistance(flat, bent, 290, 294, 102, 107)
            System.err.println(
                "capsule-lens middle=%.3f leftEnd=%.3f rightEnd=%.3f".format(middle, leftEnd, rightEnd),
            )
            assertTrue(middle > 0.1, "the capsule lens should bend the backdrop (mean %.3f)".format(middle))
            assertTrue(
                maxOf(leftEnd, rightEnd) < middle * 0.5,
                "the capsule lens must fade out at its ends (middle %.3f, ends %.3f/%.3f)".format(
                    middle,
                    leftEnd,
                    rightEnd,
                ),
            )
        } finally {
            GlassShader.resetForTests()
        }
    }

    private fun capsulePixels(): IntArray = pixelArray(400, 240) {
        Box(Modifier.fillMaxSize()) {
            // A smooth vertical ramp turns the lens displacement into a stable signal; a hard
            // checker phases in and out at the capsule ends.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.White)))
            }
            Box(
                Modifier
                    .offset(100.dp, 100.dp)
                    .size(200.dp, 30.dp)
                    .glassLensCapsule(RoundedCornerShape(DesignTokens.radiusCapsule), lineAlpha = 1f),
            )
        }
    }

    private fun meanChannelDistance(
        first: IntArray,
        second: IntArray,
        fromX: Int,
        toX: Int,
        fromY: Int,
        toY: Int,
    ): Double {
        var total = 0
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                total += channelDistance(first[index(x, y, 400)], second[index(x, y, 400)])
                count += 1
            }
        }
        return total.toDouble() / count
    }

    @Test
    fun `the blur-only glass is used when the shader is unavailable`() {
        GlassShader.resetForTests()
        GlassShader.enabled = false
        try {
            GlassCacheStats.lastSurfaceLiquid = true
            val pixels = pixelArray(400, 300) {
                Box(Modifier.fillMaxSize()) {
                    Checkerboard()
                    Box(
                        Modifier
                            .offset(72.dp, 70.dp)
                            .size(256.dp, 160.dp)
                            .backdropGlass(RoundedCornerShape(16.dp)),
                    )
                }
            }
            val pattern = meanAdjacentDiff(pixels, 400, 10, 80, 44, 256)
            val backdrop = meanAdjacentDiff(pixels, 400, 104, 296, 44, 256)
            assertTrue(backdrop < pattern * 0.5, "the fallback still blurs (ratio ${backdrop / pattern})")
            assertFalse(GlassCacheStats.lastSurfaceLiquid, "the fallback must not report the liquid surface")
        } finally {
            GlassShader.resetForTests()
        }
    }

    private fun bezelPixels(): IntArray = pixelArray(400, 300) {
        Box(Modifier.fillMaxSize()) {
            Checkerboard(cellSize = 16f)
            Box(
                Modifier
                    .offset(100.dp, 100.dp)
                    .size(200.dp, 100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .backdropGlass(RoundedCornerShape(16.dp)),
            )
        }
    }

    @Composable
    private fun deterministicScene() {
        MeshBackground(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).glassPanel())
            Box(Modifier.align(Alignment.TopStart).size(60.dp, 40.dp).backdropGlass())
        }
    }

    @Test
    fun `background extension mirrors and blurs the strip right of its edge`() {
        val width = 400
        val height = 120
        val cache = MirroredStripCache()
        try {
            val pixels = pixelArray(width, height) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.offset(x = 250.dp).size(50.dp, 120.dp).background(Color.White))
                    // The element covers the target 100..200; its source strip is 200..300.
                    Box(
                        Modifier
                            .offset(x = 100.dp)
                            .size(100.dp, 120.dp)
                            .backgroundExtension(
                                key = "mirror",
                                cache = cache,
                                sourceLeft = 200.dp,
                                sourceTop = 0.dp,
                                sourceWidth = 100.dp,
                            ) { canvas ->
                                canvas.drawRect(
                                    250f,
                                    0f,
                                    300f,
                                    120f,
                                    Paint().apply { color = Color.White },
                                )
                            },
                    )
                }
            }
            // Mirrored: window 125 shows source 275 (white), window 175 shows source 225 (black).
            val mirroredWhite = relativeLuminance(Color(pixels[index(125, 60, width)]))
            val mirroredBlack = relativeLuminance(Color(pixels[index(175, 60, width)]))
            val source = relativeLuminance(Color(pixels[index(280, 60, width)]))
            System.err.println(
                "extension white=%.4f black=%.4f source=%.4f".format(mirroredWhite, mirroredBlack, source),
            )
            assertTrue(
                mirroredWhite > mirroredBlack + 0.05,
                "the extension should mirror the strip: white %.4f vs black %.4f"
                    .format(mirroredWhite, mirroredBlack),
            )
            assertTrue(source > 0.9, "the source strip should stay unpainted: %.4f".format(source))
        } finally {
            cache.dispose()
        }
    }

    private fun pixelArray(width: Int, height: Int, density: Float = 1f, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width, height, Density(density)) {
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

    private fun channelDistance(first: Int, second: Int): Int =
        maxOf(
            abs(red(first) - red(second)),
            abs(green(first) - green(second)),
            abs(blue(first) - blue(second)),
        )

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    private fun hex(pixel: Int): String = "#%06X".format(pixel and 0xFFFFFF)
}
