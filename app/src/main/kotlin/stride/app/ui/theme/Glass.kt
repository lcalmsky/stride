package stride.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.Canvas.SaveLayerRec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/** [Modifier.blur]'s radius-to-sigma factor, matching Compose's `BlurSigmaScale`. */
private const val BLUR_SIGMA_SCALE = 0.57735f

/** Backdrop blur runs on a 1/this-resolution copy of the backdrop; see [backdropImageFilter]. */
private const val BACKDROP_BLUR_DOWNSCALE = 8

private val contentTintColor = blendOver(
    DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
    DesignTokens.background,
)

/**
 * Holds the cached mesh bitmap and the raster image drawn from it. Draw-phase data, not snapshot
 * state: the parent draws before its children, so a child can read the bitmap in the same frame
 * without a recomposition loop.
 */
class MeshBitmapHolder {
    var bitmap: ImageBitmap? = null
    var width: Int = 0
    var height: Int = 0
    var version: Int = 0
    var image: Image? = null
}

/** The mesh behind the current window, or `null` when no [MeshBackground] is above. */
val LocalMeshBitmap = staticCompositionLocalOf<MeshBitmapHolder?> { null }

/**
 * Test-only observation point: how many times [glassPanel] actually ran a blur, and whether its
 * last cached slice image was opaque.
 */
internal object GlassCacheStats {
    var panelBlurCount: Int = 0
    var lastSliceOpaque: Boolean = false
}

/**
 * Draws a solid [DesignTokens.background] with three radial orbs (accent top-left, accent2
 * bottom-right, neonPink bottom-center) into a cached bitmap, and provides it to the subtree.
 */
@Composable
fun MeshBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val holder = remember { MeshBitmapHolder() }
    DisposableEffect(holder) {
        onDispose {
            holder.image?.close()
            holder.image = null
        }
    }
    Box(
        modifier.drawWithCache {
            val width = size.width.roundToInt().coerceAtLeast(1)
            val height = size.height.roundToInt().coerceAtLeast(1)
            if (holder.bitmap == null || holder.width != width || holder.height != height) {
                holder.image?.close()
                val bitmap = buildMeshBitmap(width, height)
                holder.bitmap = bitmap
                holder.image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
                holder.width = width
                holder.height = height
                holder.version += 1
            }
            onDrawBehind {
                holder.image?.let { image ->
                    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawImage(image, 0f, 0f) }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalMeshBitmap provides holder) { content() }
    }
}

private fun buildMeshBitmap(width: Int, height: Int): ImageBitmap {
    val image = ImageBitmap(width, height, hasAlpha = false)
    val skia = Canvas(image).nativeCanvas
    skia.clear(DesignTokens.background.toArgb())
    val radius = min(width.toFloat(), height.toFloat()) * DesignTokens.meshOrbRadiusFactor
    val orbs = listOf(
        DesignTokens.accent to Point(0f, 0f),
        DesignTokens.accent2 to Point(width.toFloat(), height.toFloat()),
        DesignTokens.neonPink to Point(width / 2f, height.toFloat()),
    )
    val area = Rect(0f, 0f, width.toFloat(), height.toFloat())
    for ((color, center) in orbs) {
        val shader = Shader.makeRadialGradient(
            center,
            radius,
            intArrayOf(
                color.copy(alpha = DesignTokens.meshOrbAlpha).toArgb(),
                color.copy(alpha = 0f).toArgb(),
            ),
        )
        val paint = Paint().apply { this.shader = shader }
        skia.drawRect(area, paint)
        paint.close()
        shader.close()
    }
    image.asSkiaBitmap().setImmutable()
    return image
}

private data class GlassBounds(val left: Int, val top: Int, val width: Int, val height: Int)

private class GlassPanelState {
    var bounds by mutableStateOf<GlassBounds?>(null)

    fun update(position: Offset, width: Int, height: Int) {
        val next = GlassBounds(position.x.roundToInt(), position.y.roundToInt(), width, height)
        if (bounds != next) bounds = next
    }
}

private data class GlassKey(
    val width: Int,
    val height: Int,
    val meshWidth: Int,
    val meshHeight: Int,
    val left: Int,
    val top: Int,
    val meshVersion: Int,
)

private class GlassPanelCache {
    var key: GlassKey? = null
    var image: Image? = null
}

/**
 * A cached glass panel: its slice of the mesh blurred once together with the
 * [DesignTokens.glassFill] fill, and the three-step border. The blur is cached by panel size, mesh
 * size, root position and mesh version.
 */
fun Modifier.glassPanel(shape: Shape = RectangleShape): Modifier = composed {
    val mesh = LocalMeshBitmap.current
    val state = remember { GlassPanelState() }
    val cache = remember { GlassPanelCache() }
    DisposableEffect(cache) {
        onDispose {
            cache.image?.close()
            cache.image = null
            cache.key = null
        }
    }
    this
        .onGloballyPositioned { coordinates ->
            state.update(coordinates.boundsInRoot().topLeft, coordinates.size.width, coordinates.size.height)
        }
        .drawBehind { drawGlassPanel(state.bounds, shape, mesh, cache) }
}

private fun DrawScope.drawGlassPanel(
    bounds: GlassBounds?,
    shape: Shape,
    mesh: MeshBitmapHolder?,
    cache: GlassPanelCache,
) {
    val slice = if (bounds != null && mesh?.bitmap != null && mesh.width > 0 && mesh.height > 0) {
        val key = GlassKey(
            width = size.width.roundToInt(),
            height = size.height.roundToInt(),
            meshWidth = mesh.width,
            meshHeight = mesh.height,
            left = bounds.left,
            top = bounds.top,
            meshVersion = mesh.version,
        )
        if (cache.key != key) {
            cache.image?.close()
            cache.image = glassSliceImage(mesh, bounds, size, DesignTokens.glassBlurRadius.toPx() * BLUR_SIGMA_SCALE)
            cache.key = key
            GlassCacheStats.panelBlurCount += 1
        }
        cache.image
    } else {
        null
    }

    clipToShape(shape) {
        if (slice != null) {
            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawImage(slice, 0f, 0f) }
        } else {
            drawRect(DesignTokens.glassFill, size = size)
        }
        drawGlassBorder()
    }
}

private fun glassSliceImage(mesh: MeshBitmapHolder, bounds: GlassBounds, size: Size, sigma: Float): Image {
    val width = size.width.roundToInt().coerceAtLeast(1)
    val height = size.height.roundToInt().coerceAtLeast(1)
    val sourceLeft = max(0f, bounds.left.toFloat())
    val sourceTop = max(0f, bounds.top.toFloat())
    val sourceRight = min(mesh.width.toFloat(), bounds.left + width.toFloat())
    val sourceBottom = min(mesh.height.toFloat(), bounds.top + height.toFloat())

    val surface = Surface.makeRaster(ImageInfo(width, height, ColorType.N32, ColorAlphaType.OPAQUE))
    surface.canvas.clear(DesignTokens.background.toArgb())
    if (sourceRight > sourceLeft && sourceBottom > sourceTop) {
        val image = Image.makeFromBitmap(checkNotNull(mesh.bitmap).asSkiaBitmap())
        val paint = Paint().apply {
            imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
        }
        try {
            surface.canvas.drawImageRect(
                image,
                Rect(sourceLeft, sourceTop, sourceRight, sourceBottom),
                Rect(
                    sourceLeft - bounds.left,
                    sourceTop - bounds.top,
                    sourceRight - bounds.left,
                    sourceBottom - bounds.top,
                ),
                paint,
                true,
            )
        } finally {
            paint.close()
            image.close()
        }
    }
    val fill = Paint().apply { color = DesignTokens.glassFill.toArgb() }
    try {
        surface.canvas.drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), fill)
        val snapshot = surface.makeImageSnapshot()
        GlassCacheStats.lastSliceOpaque = snapshot.isOpaque
        return snapshot
    } finally {
        fill.close()
        surface.close()
    }
}

/**
 * Blurs the already-drawn backdrop under this element with Skia's `saveLayer` backdrop filter,
 * then paints [DesignTokens.glassFillStrong] and the border over it. The filter shrinks the
 * backdrop, blurs the small copy and scales it back, which keeps the full-resolution layer copy out
 * of reach without changing the visible result.
 */
fun Modifier.backdropGlass(shape: Shape = RectangleShape): Modifier = drawWithContent {
    val sigma = DesignTokens.glassBlurRadius.toPx() * BLUR_SIGMA_SCALE
    val outline = shape.createOutline(size, layoutDirection, this)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        when (outline) {
            is Outline.Rectangle -> native.clipRect(Rect(0f, 0f, size.width, size.height))
            is Outline.Rounded -> native.clipPath(Path().apply { addRoundRect(outline.roundRect) }.asSkiaPath())
            is Outline.Generic -> native.clipPath(outline.path.asSkiaPath())
        }
        native.saveLayer(
            SaveLayerRec(
                Rect(0f, 0f, size.width, size.height),
                null,
                backdropImageFilter(sigma),
            ),
        )
    }
    drawRect(DesignTokens.glassFillStrong, size = size)
    drawGlassBorder()
    drawContent()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.restore()
        native.restore()
    }
}

private fun backdropImageFilter(sigma: Float): ImageFilter {
    val downscale = ImageFilter.makeMatrixTransform(
        Matrix33.makeScale(1f / BACKDROP_BLUR_DOWNSCALE),
        SamplingMode.LINEAR,
        null,
    )
    val blur = ImageFilter.makeBlur(
        sigma / BACKDROP_BLUR_DOWNSCALE,
        sigma / BACKDROP_BLUR_DOWNSCALE,
        FilterTileMode.CLAMP,
        downscale,
    )
    return ImageFilter.makeMatrixTransform(
        Matrix33.makeScale(BACKDROP_BLUR_DOWNSCALE.toFloat()),
        SamplingMode.LINEAR,
        blur,
    )
}

/**
 * An opaque near-[DesignTokens.surface] fill for content areas that must stay readable. It is
 * painted as one opaque color so it takes the opaque draw path; at [DesignTokens.contentTintAlpha]
 * the L0 mesh underneath would move a channel by at most 4/255 anyway, so it is not shown.
 */
fun Modifier.contentTint(): Modifier = background(contentTintColor)

/** The top, side and bottom hairlines of a glass panel. */
fun Modifier.glassBorder(shape: Shape = RectangleShape): Modifier = drawBehind {
    clipToShape(shape) { drawGlassBorder() }
}

private fun DrawScope.drawGlassBorder() {
    val stroke = 1.dp.toPx()
    drawRect(DesignTokens.glassBorderTop, topLeft = Offset.Zero, size = Size(size.width, stroke))
    drawRect(
        DesignTokens.glassBorderBottom,
        topLeft = Offset(0f, size.height - stroke),
        size = Size(size.width, stroke),
    )
    if (size.height > stroke * 2f) {
        val side = Size(stroke, size.height - stroke * 2f)
        drawRect(DesignTokens.glassBorderSide, topLeft = Offset(0f, stroke), size = side)
        drawRect(DesignTokens.glassBorderSide, topLeft = Offset(size.width - stroke, stroke), size = side)
    }
}

private fun DrawScope.clipToShape(shape: Shape, block: DrawScope.() -> Unit) {
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> clipRect(
            outline.rect.left,
            outline.rect.top,
            outline.rect.right,
            outline.rect.bottom,
            block = block,
        )
        is Outline.Rounded -> clipPath(Path().apply { addRoundRect(outline.roundRect) }, block = block)
        is Outline.Generic -> clipPath(outline.path, block = block)
    }
}
