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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.ui.LocalAnimationsEnabled

class GlassModifiersTest {
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
    fun `the selection capsule is one pill with no edge line at density 2`() {
        val pixels = pixelArray(400, 120, density = 2f) {
            Box(Modifier.fillMaxSize().background(DesignTokens.surface)) {
                Box(
                    Modifier
                        .size(200.dp, 30.dp)
                        .glassCapsule(
                            shape = RoundedCornerShape(DesignTokens.radiusCapsule),
                            selectionAlpha = 1f,
                        ),
                )
            }
        }
        // The capsule is inset 6/2 dp; the old 2 dp line sat at 7..9 dp, i.e. device px 14..19.
        val y = 30
        val edge = pixels[index(16, y, 400)]
        val interior = pixels[index(200, y, 400)]
        val calm = pixels[index(200, 90, 400)]
        val edgeLuma = relativeLuminance(Color(edge))
        val interiorLuma = relativeLuminance(Color(interior))
        System.err.println(
            "selection-pill edge=${hex(edge)} interior=${hex(interior)} calm=${hex(calm)}",
        )
        assertTrue(
            abs(edgeLuma - interiorLuma) < 0.02,
            "the pill's left edge %.3f must match its interior %.3f; a bar would differ".format(edgeLuma, interiorLuma),
        )
        assertTrue(
            interiorLuma > relativeLuminance(Color(calm)) + 0.005,
            "the selected capsule should lift above the calm row (%.4f vs %.4f)"
                .format(interiorLuma, relativeLuminance(Color(calm))),
        )
    }

    @Test
    fun `hover paints the selection pill lighter than the selected pill`() {
        val selected = pixelArray(400, 120, density = 2f) {
            Box(Modifier.fillMaxSize().background(DesignTokens.surface)) {
                Box(
                    Modifier
                        .size(200.dp, 30.dp)
                        .glassCapsule(
                            shape = RoundedCornerShape(DesignTokens.radiusCapsule),
                            selectionAlpha = 1f,
                        ),
                )
            }
        }
        val hover = pixelArray(400, 120, density = 2f) {
            Box(Modifier.fillMaxSize().background(DesignTokens.surface)) {
                Box(
                    Modifier
                        .size(200.dp, 30.dp)
                        .glassCapsule(
                            shape = RoundedCornerShape(DesignTokens.radiusCapsule),
                            fillAlpha = 1f,
                        ),
                )
            }
        }
        val selectedLuma = relativeLuminance(Color(selected[index(200, 30, 400)]))
        val hoverLuma = relativeLuminance(Color(hover[index(200, 30, 400)]))
        val calmLuma = relativeLuminance(Color(selected[index(200, 90, 400)]))
        System.err.println("selection-pill hover=%.4f selected=%.4f calm=%.4f".format(hoverLuma, selectedLuma, calmLuma))
        assertTrue(hoverLuma > calmLuma + 0.002, "hover should still step up from the calm row")
        assertTrue(hoverLuma < selectedLuma, "hover should be lighter than the selected pill")
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
                    .glassLensCapsule(RoundedCornerShape(DesignTokens.radiusCapsule), selectionAlpha = 1f),
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
            Box(Modifier.align(Alignment.Center).size(100.dp, 80.dp).backdropGlass())
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
                                key = { "mirror" },
                                cache = cache,
                                sourceLeft = 200.dp,
                                sourceTop = 0.dp,
                                sourceWidth = 100.dp,
                                shapeLeft = 0.dp,
                                shapeWidth = 100.dp,
                                shapeRadius = 0.dp,
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

    @Test
    fun `the background extension mirrors across the sidebar edge, not the element edge`() {
        val width = 400
        val height = 120
        val cache = MirroredStripCache()
        try {
            val pixels = pixelArray(width, height) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(
                        // The element right edge (250) is 50 px past the mirror axis (200), the
                        // sidebar's edge; the mirror must still be centred on 200.
                        Modifier
                            .offset(x = 100.dp)
                            .size(150.dp, 120.dp)
                            .backgroundExtension(
                                key = { "axis" },
                                cache = cache,
                                sourceLeft = 200.dp,
                                sourceTop = 0.dp,
                                sourceWidth = 100.dp,
                                shapeLeft = 0.dp,
                                shapeWidth = 150.dp,
                                shapeRadius = 0.dp,
                                blurRadius = 0.dp,
                            ) { canvas ->
                                canvas.drawRect(
                                    200f,
                                    0f,
                                    240f,
                                    120f,
                                    Paint().apply { color = Color.White },
                                )
                            },
                    )
                }
            }
            // Source 205 is white and folds to window 195; source 180 (left of the strip) folds to
            // 220 and must stay black.
            val inside = relativeLuminance(Color(pixels[index(195, 60, width)]))
            val beyond = relativeLuminance(Color(pixels[index(220, 60, width)]))
            System.err.println("extension axis inside=%.4f beyond=%.4f".format(inside, beyond))
            assertTrue(inside > 0.9, "the content right of the sidebar edge should mirror at 195: %.4f".format(inside))
            assertTrue(beyond < 0.1, "the axis must be the sidebar edge, content at 220 should stay black: %.4f".format(beyond))
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
