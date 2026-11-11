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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeCanvas
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import stride.app.editor.LocalEditorScrollState

/** [Modifier.blur]'s radius-to-sigma factor, matching Compose's `BlurSigmaScale`. */
private const val BLUR_SIGMA_SCALE = 0.57735f

/** Backdrop blur runs on a 1/this-resolution copy of the backdrop; see [backdropImageFilter]. */
private const val BACKDROP_BLUR_DOWNSCALE = 8

/** The lens blur runs on a 1/[LENS_DOWNSCALE] copy of the backdrop; the bend stays soft. */
private const val LENS_DOWNSCALE = 4

/** Capsules frost the band they float over at a coarser scale; only the soft field is needed. */
private const val CAPSULE_DOWNSCALE = 8

/** The lens displacement fades to zero over this distance from the outer rim. */
private val LENS_TAPER = 2.dp

/** Slab size buckets: a splitter drag rebuilds the baked shadow and rim at most every 4 dp. */
private val CACHE_GRID = 4.dp

private fun quantize(value: Float, grid: Float): Float =
    if (grid <= 0f) value else (value / grid).roundToInt() * grid

private val contentTintColor = blendOver(
    DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
    DesignTokens.background,
)

/**
 * Holds the cached field bitmap and the raster image drawn from it. Draw-phase data, not
 * snapshot state: the parent draws before its children, so a child can read the bitmap in the
 * same frame without a recomposition loop.
 */
class MeshBitmapHolder {
    var bitmap: ImageBitmap? = null
    var width: Int = 0
    var height: Int = 0
    var version: Int = 0
    var image: Image? = null
}

/** The field behind the current window, or `null` when no [MeshBackground] is above. */
val LocalMeshBitmap = staticCompositionLocalOf<MeshBitmapHolder?> { null }

/**
 * How far the floating control rows (and the find bar between them) overlay the editor
 * viewport, and where the text column starts beside the sidebar. The editor keeps drawing under
 * them; these insets only keep the first and last lines and the caret reachable.
 */
data class EditorInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val left: Dp = 0.dp,
    val right: Dp = 0.dp,
)

val LocalEditorInsets = staticCompositionLocalOf { EditorInsets() }

/**
 * Test-only observation point: how many times [glassPanel] actually ran a blur, whether its
 * last cached slice image was opaque, and whether the last liquid glass surface used the shader.
 */
internal object GlassCacheStats {
    var panelBlurCount: Int = 0
    var lastSliceOpaque: Boolean = false
    var lastSurfaceLiquid: Boolean = false
}

/**
 * Draws a cool graphite field with a gentle top-to-bottom luminance falloff into a cached bitmap,
 * and provides it to the subtree.
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
                val bitmap = buildFieldBitmap(width, height)
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

private fun buildFieldBitmap(width: Int, height: Int): ImageBitmap {
    val image = ImageBitmap(width, height, hasAlpha = false)
    val skia = Canvas(image).nativeCanvas
    val area = Rect.makeLTRB(0f, 0f, width.toFloat(), height.toFloat())
    val shader = Shader.makeLinearGradient(
        0f,
        0f,
        0f,
        height.toFloat(),
        intArrayOf(
            DesignTokens.backgroundLift.toArgb(),
            DesignTokens.background.toArgb(),
            DesignTokens.fieldBottom.toArgb(),
        ),
        floatArrayOf(0f, 0.55f, 1f),
    )
    val paint = Paint().apply { this.shader = shader }
    try {
        skia.drawRect(area, paint)
    } finally {
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
 * A cached glass panel: its slice of the field blurred once together with the
 * [DesignTokens.glassFill] fill, and the hairline rim. The blur is cached by panel size, field
 * size, root position and field version.
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
        .drawBehind {
            drawGlassPanel(state.bounds, shape, mesh, cache)
        }
}

/**
 * The crisp rim of a floating slab: one specular stroke brighter at the top and the left and
 * fading to a faint line at the bottom, plus a faint inner lower rim. It draws over the slab
 * fill and its content, so put it before the fill modifier in the chain. All Skia objects are
 * cached for the slab's size; a frame only draws three paths.
 */
fun Modifier.glassSlabRims(shape: Shape): Modifier = composed {
    val cache = remember { SlabRimCache() }
    DisposableEffect(cache) { onDispose { cache.dispose() } }
    drawWithContent {
        drawContent()
        drawSlabRims(shape, cache)
    }
}

/**
 * The soft neutral shadow under a floating slab. Unlike `Modifier.shadow` it does not create a
 * render layer: the blurred rounded rect is baked into one image per slab size and every frame
 * only draws that image, so static slabs cost a single image blit. The size is bucketed to a
 * 4 dp grid, so dragging a splitter rebuilds the image only every 4 dp instead of every frame.
 */
fun Modifier.slabShadow(shape: Shape): Modifier = composed {
    val cache = remember { SlabShadowCache() }
    DisposableEffect(cache) { onDispose { cache.dispose() } }
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = when (outline) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            is Outline.Generic -> 0f
        }
        cache.update(size.width, size.height, radius, DesignTokens.slabShadow.toPx(), CACHE_GRID.toPx())
        cache.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
    }
}

/** A soft dark band behind a label run on a bar that has editor content under it. */
fun Modifier.labelScrim(active: Boolean): Modifier = drawBehind {
    if (!active) return@drawBehind
    val inset = DesignTokens.chromeLabelScrimInset.toPx()
    val width = size.width - inset * 2f
    val height = size.height - inset * 2f
    if (width <= 0f || height <= 0f) return@drawBehind
    drawRoundRect(
        DesignTokens.chromeLabelScrim,
        topLeft = Offset(inset, inset),
        size = Size(width, height),
        cornerRadius = CornerRadius(DesignTokens.radiusCapsule.toPx()),
    )
}

/**
 * A small capsule inset from its row or tab: a neutral interior lift, a crisp top rim and, when
 * [lineAlpha] is above zero, the neon line on [lineEdge]. Capsules never bend the backdrop, so
 * guide lines and text behind them stay out of the selection.
 */
fun Modifier.glassCapsule(
    shape: Shape,
    insetX: Dp = 6.dp,
    insetY: Dp = 2.dp,
    fillAlpha: Float = 0f,
    lineEdge: CapsuleEdge = CapsuleEdge.LEFT,
    lineAlpha: Float = 1f,
): Modifier = drawBehind {
    if (fillAlpha <= 0f && lineAlpha <= 0f) return@drawBehind
    val left = insetX.toPx()
    val top = insetY.toPx()
    val innerWidth = size.width - left * 2f
    val innerHeight = size.height - top * 2f
    if (innerWidth <= 0f || innerHeight <= 0f) return@drawBehind
    val innerSize = Size(innerWidth, innerHeight)
    val path = capsulePath(shape, innerSize).apply { translate(Offset(left, top)) }
    clipPath(path) {
        if (fillAlpha > 0f) {
            drawRect(
                DesignTokens.hoverFill.copy(alpha = DesignTokens.hoverFill.alpha * fillAlpha),
                topLeft = Offset(left, top),
                size = innerSize,
            )
        }
        if (lineAlpha > 0f) {
            drawRect(DesignTokens.capsuleLift, topLeft = Offset(left, top), size = innerSize)
            drawCapsuleRim(shape, innerSize, left, top)
            drawNeonLine(innerSize, left, top, lineEdge, lineAlpha)
        }
    }
}

/**
 * The lens capsule on the selected tree row and the active tab: the backdrop bends inside a
 * [DesignTokens.capsuleBezel] rim, then the neutral lift, the crisp top rim and the neon line sit
 * on top. The displacement fades to zero at the left and right ends, so nothing smears there.
 */
fun Modifier.glassLensCapsule(
    shape: Shape,
    insetX: Dp = 6.dp,
    insetY: Dp = 2.dp,
    lineEdge: CapsuleEdge = CapsuleEdge.LEFT,
    lineAlpha: Float = 1f,
): Modifier = composed {
    val cache = remember { BackdropFilterCache() }
    DisposableEffect(cache) {
        onDispose { cache.dispose() }
    }
    drawWithContent {
        val left = insetX.toPx()
        val top = insetY.toPx()
        val innerWidth = size.width - left * 2f
        val innerHeight = size.height - top * 2f
        if (innerWidth <= 0f || innerHeight <= 0f || lineAlpha <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val innerSize = Size(innerWidth, innerHeight)
        val outline = shape.createOutline(innerSize, layoutDirection, this)
        val radius = when (outline) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            is Outline.Generic -> 0f
        }
        val liquid = cache.filterFor(
            width = innerWidth,
            height = innerHeight,
            radius = radius,
            density = density,
            sigma = DesignTokens.liquidGlassBlurRadius.toPx() * BLUR_SIGMA_SCALE,
            bezel = DesignTokens.capsuleBezel.toPx(),
            strength = DesignTokens.capsuleLensStrength.toPx(),
            aberration = DesignTokens.glassAberration.toPx(),
            taper = LENS_TAPER.toPx() / LENS_DOWNSCALE,
            endTaper = DesignTokens.capsuleEndTaper.toPx() / LENS_DOWNSCALE,
            backdropMix = DesignTokens.glassBackdropMix,
            downscale = LENS_DOWNSCALE,
        )
        GlassCacheStats.lastSurfaceLiquid = liquid != null
        val native = drawContext.canvas.nativeCanvas
        native.save()
        native.translate(left, top)
        cache.path?.let { native.clipPath(it) } ?: native.clipPath(capsulePath(shape, innerSize).asSkiaPath())
        if (liquid != null) {
            cache.layer?.let { native.saveLayer(it) } ?: native.saveLayer(
                SaveLayerRec(org.jetbrains.skia.Rect(0f, 0f, innerWidth, innerHeight), null, liquid),
            )
            native.restore()
        } else {
            drawRect(DesignTokens.glassBase.copy(alpha = 1f - DesignTokens.glassBackdropMix), size = innerSize)
        }
        drawRect(DesignTokens.capsuleLift, size = innerSize)
        drawCapsuleRim(shape, innerSize, 0f, 0f)
        drawNeonLine(innerSize, 0f, 0f, lineEdge, lineAlpha)
        native.restore()
        drawContent()
    }
}

enum class CapsuleEdge { LEFT, BOTTOM }

/** A popup selection row: the capsule and neon line when selected, a faint step on hover. */
@Composable
fun Modifier.selectionRow(selected: Boolean, hovered: Boolean): Modifier = glassCapsule(
    shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.radiusCapsule),
    insetX = 6.dp,
    insetY = 2.dp,
    fillAlpha = if (hovered && !selected) 1f else 0f,
    lineEdge = CapsuleEdge.LEFT,
    lineAlpha = rememberNeonAlpha(selected),
)

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
            cache.image = glassSliceImage(
                mesh = mesh,
                bounds = bounds,
                size = size,
                sigma = DesignTokens.glassBlurRadius.toPx() * BLUR_SIGMA_SCALE,
            )
            cache.key = key
            GlassCacheStats.panelBlurCount += 1
        }
        cache.image
    } else {
        null
    }

    val innerPath = capsulePath(shape, size)
    clipPath(innerPath) {
        if (slice != null) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImage(slice, 0f, 0f)
            }
        } else {
            drawRect(DesignTokens.glassFill, size = size)
        }
        drawHairlineRim(shape, size, 0f, 0f)
    }
}

private fun DrawScope.capsulePath(shape: Shape, size: Size): Path {
    val outline = shape.createOutline(size, layoutDirection, this)
    return when (outline) {
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Generic -> Path().apply { addPath(outline.path) }
    }
}

/** The cached panel image: the blurred field slice with the [DesignTokens.glassFill] fill. */
private fun glassSliceImage(
    mesh: MeshBitmapHolder,
    bounds: GlassBounds,
    size: Size,
    sigma: Float,
): Image {
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
                org.jetbrains.skia.Rect(sourceLeft, sourceTop, sourceRight, sourceBottom),
                org.jetbrains.skia.Rect(
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
    val base = try {
        surface.canvas.drawRect(org.jetbrains.skia.Rect(0f, 0f, width.toFloat(), height.toFloat()), fill)
        surface.makeImageSnapshot()
    } finally {
        fill.close()
        surface.close()
    }
    GlassCacheStats.lastSliceOpaque = base.isOpaque
    return base
}

/** The liquid glass filter, rebuilt only when the panel's size or lens geometry changes. */
private class BackdropFilterCache {
    var width: Int = -1
    var height: Int = -1
    var radius: Int = -1
    var density: Float = -1f
    var bezel: Float = -1f
    var strength: Float = -1f
    var endTaper: Float = -1f
    var backdropMix: Float = -1f
    var downscale: Int = -1
    var filter: ImageFilter? = null
    var fallback: ImageFilter? = null
    var path: org.jetbrains.skia.Path? = null
    var layer: SaveLayerRec? = null

    fun dispose() {
        filter?.close()
        filter = null
        fallback?.close()
        fallback = null
        path?.close()
        path = null
        layer = null
    }

    fun filterFor(
        width: Float,
        height: Float,
        radius: Float,
        density: Float,
        sigma: Float,
        bezel: Float,
        strength: Float,
        aberration: Float,
        taper: Float,
        endTaper: Float,
        backdropMix: Float,
        downscale: Int,
    ): ImageFilter? {
        val widthKey = width.roundToInt()
        val heightKey = height.roundToInt()
        val radiusKey = radius.roundToInt()
        val unchanged = this.width == widthKey && this.height == heightKey && this.radius == radiusKey &&
            this.density == density && this.bezel == bezel && this.strength == strength &&
            this.endTaper == endTaper && this.backdropMix == backdropMix && this.downscale == downscale
        if (unchanged) return filter
        filter?.close()
        fallback?.close()
        path?.close()
        filter = GlassShader.backdropFilter(
            width = width,
            height = height,
            radius = radius,
            sigma = sigma,
            bezel = bezel,
            strength = strength,
            aberration = aberration,
            taper = taper,
            endTaper = endTaper,
            downscale = downscale,
            baseRed = DesignTokens.glassBase.red,
            baseGreen = DesignTokens.glassBase.green,
            baseBlue = DesignTokens.glassBase.blue,
            backdropMix = backdropMix,
            chromaGain = DesignTokens.glassChromaGain,
            specular = DesignTokens.glassSpecular,
        )
        this.width = widthKey
        this.height = heightKey
        this.radius = radiusKey
        this.density = density
        this.bezel = bezel
        this.strength = strength
        this.endTaper = endTaper
        this.backdropMix = backdropMix
        this.downscale = downscale
        fallback = if (filter == null) backdropImageFilter(sigma) else null
        path = org.jetbrains.skia.Path().apply {
            if (radius > 0f) {
                addRRect(RRect.makeLTRB(0f, 0f, width, height, radius))
            } else {
                addRect(org.jetbrains.skia.Rect.makeWH(width, height))
            }
        }
        layer = SaveLayerRec(
            org.jetbrains.skia.Rect.makeWH(width, height),
            null,
            filter ?: fallback,
        )
        return filter
    }
}

/**
 * Blurs the already-drawn backdrop under this element through the liquid glass shader, then
 * paints the rim and the content. When the shader is unavailable the previous blur-only glass is
 * used instead.
 */
fun Modifier.backdropGlass(shape: Shape = RectangleShape): Modifier = liquidBackdrop(
    shape,
    lensBezel = DesignTokens.glassBezel,
    lensStrength = DesignTokens.glassLensStrength,
    blurRadius = DesignTokens.liquidGlassBlurRadius,
    backdropMix = DesignTokens.glassBackdropMix,
    downscale = LENS_DOWNSCALE,
)

/**
 * A floating control capsule: its backdrop blurred through the liquid lens, the specular rim,
 * the soft shadow and the tint, so code under it reads as a soft impression rather than glyphs.
 */
fun Modifier.capsuleGlass(shape: Shape): Modifier = slabShadow(shape)
    .capsuleFrost(shape)
    .glassSlabRims(shape)
    .drawBehind {
        clipPath(capsulePath(shape, size)) {
            drawRect(DesignTokens.capsuleFill)
            drawRect(DesignTokens.capsuleLift)
        }
    }

/**
 * The capsule's own backdrop pass. A layered editor viewport cannot be sampled, so in that mode
 * the capsule keeps a cached frost fill instead of reading the backdrop.
 */
private fun Modifier.capsuleFrost(shape: Shape): Modifier = composed {
    val layered = LocalEditorScrollState.current?.layered == true
    val cache = remember { BackdropFilterCache() }
    DisposableEffect(cache) {
        onDispose { cache.dispose() }
    }
    if (layered) {
        Modifier.drawBehind {
            clipPath(capsulePath(shape, size)) {
                drawRect(DesignTokens.glassBase.copy(alpha = 0.94f))
            }
        }
    } else {
        drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = when (outline) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                is Outline.Rectangle -> 0f
                is Outline.Generic -> 0f
            }
            val liquid = cache.filterFor(
                width = size.width,
                height = size.height,
                radius = radius,
                density = density,
                sigma = DesignTokens.capsuleFrostBlur.toPx() * BLUR_SIGMA_SCALE,
                bezel = DesignTokens.capsuleBezel.toPx(),
                strength = DesignTokens.capsuleLensStrength.toPx(),
                aberration = DesignTokens.glassAberration.toPx(),
                taper = LENS_TAPER.toPx() / CAPSULE_DOWNSCALE,
                endTaper = DesignTokens.capsuleEndTaper.toPx() / CAPSULE_DOWNSCALE,
                backdropMix = DesignTokens.glassBackdropMix,
                downscale = CAPSULE_DOWNSCALE,
            )
            GlassCacheStats.lastSurfaceLiquid = liquid != null
            val native = drawContext.canvas.nativeCanvas
            native.save()
            cache.path?.let { native.clipPath(it) } ?: native.clipPath(capsulePath(shape, size).asSkiaPath())
            if (liquid != null) {
                cache.layer?.let { native.saveLayer(it) } ?: native.saveLayer(
                    SaveLayerRec(org.jetbrains.skia.Rect(0f, 0f, size.width, size.height), null, liquid),
                )
                native.restore()
            } else {
                drawRect(DesignTokens.glassBase.copy(alpha = 1f - DesignTokens.glassBackdropMix), size = size)
            }
            native.restore()
            drawContent()
        }
    }
}

/**
 * The floating sidebar: a translucent tint over the background extension, so the mirrored,
 * blurred content shows through it with the same specular rim and shadow as the capsules.
 */
fun Modifier.sidebarGlass(shape: Shape): Modifier = slabShadow(shape)
    .glassSlabRims(shape)
    .drawBehind {
        clipPath(capsulePath(shape, size)) {
            drawRect(DesignTokens.sidebarFill)
        }
    }


private fun Modifier.liquidBackdrop(
    shape: Shape,
    lensBezel: Dp,
    lensStrength: Dp,
    blurRadius: Dp,
    backdropMix: Float,
    downscale: Int,
    border: Boolean = false,
): Modifier = composed {
    val cache = remember { BackdropFilterCache() }
    DisposableEffect(cache) {
        onDispose { cache.dispose() }
    }
    drawWithContent {
        val sigma = DesignTokens.glassBlurRadius.toPx() * BLUR_SIGMA_SCALE
        val liquidSigma = blurRadius.toPx() * BLUR_SIGMA_SCALE
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = when (outline) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            is Outline.Generic -> 0f
        }
        val liquid = cache.filterFor(
            width = size.width,
            height = size.height,
            radius = radius,
            density = density,
            sigma = liquidSigma,
            bezel = lensBezel.toPx(),
            strength = lensStrength.toPx(),
            aberration = DesignTokens.glassAberration.toPx(),
            taper = LENS_TAPER.toPx() / LENS_DOWNSCALE,
            endTaper = 0f,
            backdropMix = backdropMix,
            downscale = downscale,
        )
        GlassCacheStats.lastSurfaceLiquid = liquid != null
        val native = drawContext.canvas.nativeCanvas
        native.save()
        cache.path?.let { native.clipPath(it) } ?: when (outline) {
            is Outline.Rectangle -> native.clipRect(org.jetbrains.skia.Rect(0f, 0f, size.width, size.height))
            is Outline.Rounded -> native.clipPath(Path().apply { addRoundRect(outline.roundRect) }.asSkiaPath())
            is Outline.Generic -> native.clipPath(outline.path.asSkiaPath())
        }
        cache.layer?.let { native.saveLayer(it) } ?: run {
            native.saveLayer(
                SaveLayerRec(
                    org.jetbrains.skia.Rect(0f, 0f, size.width, size.height),
                    null,
                    liquid ?: backdropImageFilter(sigma),
                ),
            )
        }
        if (liquid == null) {
            // Same frosted base as the shader path, over the blur-only backdrop. The border is
            // drawn once after the layer for both paths.
            drawRect(DesignTokens.glassBase.copy(alpha = 1f - backdropMix), size = size)
        }
        native.restore()
        if (border) drawGlassBorder()
        drawContent()
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

/** The floating sidebar's placement, so the editor can mirror its real content behind the glass. */
data class SidebarChrome(val left: Dp, val width: Dp, val top: Dp, val bottom: Dp)

val LocalSidebarChrome = staticCompositionLocalOf<SidebarChrome?> { null }

/** One cached mirror of the content strip right of the sidebar; a frame only blits it. */
class MirroredStripCache {
    internal var key: Any? = null
    internal var image: Image? = null

    fun dispose() {
        image?.close()
        image = null
        key = null
    }
}

/**
 * The background extension: the strip of real content right of this element is mirrored back
 * across its edge and drawn into the element, blurred. [source] paints that content in window
 * coordinates; it is rendered into a small offscreen image once per [key] and blurred once, so a
 * static frame only blits it. The element must cover the sidebar and be drawn after the content.
 */
fun Modifier.backgroundExtension(
    key: Any,
    cache: MirroredStripCache,
    sourceLeft: Dp,
    sourceTop: Dp,
    sourceWidth: Dp,
    blurRadius: Dp = DesignTokens.backgroundExtensionBlur,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Modifier = drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val image = cache.imageFor(
        key = key,
        width = size.width,
        height = size.height,
        sourceLeft = sourceLeft.toPx(),
        sourceTop = sourceTop.toPx(),
        sourceWidth = sourceWidth.toPx(),
        sigma = blurRadius.toPx() * BLUR_SIGMA_SCALE,
        source = source,
    ) ?: return@drawBehind
    val native = drawContext.canvas.nativeCanvas
    native.save()
    native.clipRect(org.jetbrains.skia.Rect(0f, 0f, size.width, size.height))
    // The image's left edge is the content adjacent to the element's right edge; the flip
    // reverses it across that edge.
    native.translate(size.width, 0f)
    native.scale(-1f, 1f)
    native.drawImageRect(
        image,
        org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        org.jetbrains.skia.Rect.makeWH(size.width, size.height),
        SamplingMode.LINEAR,
        null,
        true,
    )
    native.restore()
}

private data class StripKey(
    val key: Any,
    val width: Int,
    val height: Int,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceWidth: Int,
    val sigma: Float,
)

/**
 * 1/[MIRROR_DOWNSCALE] copy of the source strip. Only a very low-frequency colour field may
 * survive: at this scale the line rhythm and glyph shapes are gone even before the blur.
 */
private const val MIRROR_DOWNSCALE = 16

private fun MirroredStripCache.imageFor(
    key: Any,
    width: Float,
    height: Float,
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    val next = StripKey(
        key = key,
        width = width.roundToInt(),
        height = height.roundToInt(),
        sourceLeft = sourceLeft.roundToInt(),
        sourceTop = sourceTop.roundToInt(),
        sourceWidth = sourceWidth.roundToInt(),
        sigma = sigma,
    )
    if (next == this.key) return image
    val built = buildMirroredStrip(width, height, sourceLeft, sourceTop, sourceWidth, sigma, source)
    dispose()
    this.key = next
    image = built
    return image
}

private fun buildMirroredStrip(
    width: Float,
    height: Float,
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    if (sourceWidth <= 0f || width <= 0f || height <= 0f) return null
    val stripWidth = (width / MIRROR_DOWNSCALE).roundToInt().coerceAtLeast(1)
    val stripHeight = (height / MIRROR_DOWNSCALE).roundToInt().coerceAtLeast(1)
    val scale = stripWidth / sourceWidth
    val sharp = Surface.makeRaster(ImageInfo(stripWidth, stripHeight, ColorType.N32, ColorAlphaType.PREMUL))
    val blurred = Surface.makeRaster(ImageInfo(stripWidth, stripHeight, ColorType.N32, ColorAlphaType.PREMUL))
    try {
        sharp.canvas.clear(contentTintColor.toArgb())
        sharp.canvas.scale(scale, scale)
        sharp.canvas.translate(-sourceLeft, -sourceTop)
        source(sharp.canvas.asComposeCanvas())
        val snapshot = sharp.makeImageSnapshot()
        val paint = Paint().apply {
            imageFilter = ImageFilter.makeBlur(sigma * scale, sigma * scale, FilterTileMode.CLAMP)
        }
        try {
            blurred.canvas.clear(0)
            blurred.canvas.drawImage(snapshot, 0f, 0f, paint)
        } finally {
            paint.close()
            snapshot.close()
        }
        return blurred.makeImageSnapshot()
    } finally {
        sharp.close()
        blurred.close()
    }
}

/** One cached blurred copy of the editor viewport, drawn by [scrollEdgeBands]. */
class EdgeBandCache {
    internal var key: Any? = null
    internal var image: Image? = null

    fun dispose() {
        image?.close()
        image = null
        key = null
    }
}

/**
 * The scroll edge effect: a blurred copy of the editor content, opaquely covering the top and
 * bottom bands and easing out [tail] beyond each band's inner edge, so the content under the
 * control rows softens toward the window edge without a cut line. [source] paints the editor
 * content in the element's coordinates at full resolution; it is rendered into a small offscreen
 * image and masked once per [key], so a static frame only blits it. Nothing is drawn left of
 * [clipStart] (the gutter), where the sidebar's background extension owns the pixels.
 */
fun Modifier.scrollEdgeBands(
    key: Any,
    cache: EdgeBandCache,
    topBand: Dp,
    bottomBand: Dp,
    clipStart: Dp,
    blurRadius: Dp = DesignTokens.scrollEdgeBlur,
    tail: Dp = DesignTokens.scrollEdgeTail,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Modifier = drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val image = cache.imageFor(
        key = key,
        width = size.width,
        height = size.height,
        topBand = topBand.toPx(),
        bottomBand = bottomBand.toPx(),
        tail = tail.toPx(),
        sigma = blurRadius.toPx() * BLUR_SIGMA_SCALE,
        source = source,
    ) ?: return@drawBehind
    val native = drawContext.canvas.nativeCanvas
    native.save()
    native.clipRect(org.jetbrains.skia.Rect.makeLTRB(clipStart.toPx(), 0f, size.width, size.height))
    // Only the two bands (plus their tails) are visible; blitting the whole viewport would
    // upscale a full window of pixels every frame.
    blitBand(
        native, image, 0f, (topBand + tail).toPx().coerceAtMost(size.height), size.width, size.height,
    )
    blitBand(
        native,
        image,
        (size.height - (bottomBand + tail).toPx()).coerceAtLeast(0f),
        size.height,
        size.width,
        size.height,
    )
    native.restore()
}

/** Draws the `top..bottom` slice of the band image, upscaled to the element's `top..bottom`. */
private fun blitBand(
    native: org.jetbrains.skia.Canvas,
    image: Image,
    top: Float,
    bottom: Float,
    width: Float,
    height: Float,
) {
    if (bottom <= top) return
    val scaleY = image.height / height
    native.drawImageRect(
        image,
        Rect.makeLTRB(0f, top * scaleY, image.width.toFloat(), bottom * scaleY),
        Rect.makeLTRB(0f, top, width, bottom),
        SamplingMode.LINEAR,
        null,
        true,
    )
}

/** 1/[EDGE_BAND_DOWNSCALE] copy is blurred once; the full-size blit upsamples it back. */
private const val EDGE_BAND_DOWNSCALE = 8

private data class EdgeBandKey(
    val key: Any,
    val width: Int,
    val height: Int,
    val topBand: Int,
    val bottomBand: Int,
    val tail: Int,
    val sigma: Int,
)

private fun EdgeBandCache.imageFor(
    key: Any,
    width: Float,
    height: Float,
    topBand: Float,
    bottomBand: Float,
    tail: Float,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    val next = EdgeBandKey(
        key = key,
        width = width.roundToInt(),
        height = height.roundToInt(),
        topBand = topBand.roundToInt(),
        bottomBand = bottomBand.roundToInt(),
        tail = tail.roundToInt(),
        sigma = sigma.roundToInt(),
    )
    if (next == this.key) return image
    val built = buildEdgeBand(width, height, topBand, bottomBand, tail, sigma, source)
    dispose()
    this.key = next
    image = built
    return built
}

private fun buildEdgeBand(
    width: Float,
    height: Float,
    topBand: Float,
    bottomBand: Float,
    tail: Float,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    if (width <= 0f || height <= 0f) return null
    val scale = 1f / EDGE_BAND_DOWNSCALE
    val bandWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val bandHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val sharp = Surface.makeRaster(ImageInfo(bandWidth, bandHeight, ColorType.N32, ColorAlphaType.PREMUL))
    val blurred = Surface.makeRaster(ImageInfo(bandWidth, bandHeight, ColorType.N32, ColorAlphaType.PREMUL))
    try {
        // The field behind the code is opaque, so the band replaces the content instead of
        // stacking a second set of glyphs on top of the sharp ones.
        sharp.canvas.clear(contentTintColor.toArgb())
        sharp.canvas.scale(scale, scale)
        source(sharp.canvas.asComposeCanvas())
        val snapshot = sharp.makeImageSnapshot()
        val paint = Paint().apply {
            imageFilter = ImageFilter.makeBlur(sigma * scale, sigma * scale, FilterTileMode.CLAMP)
        }
        try {
            blurred.canvas.clear(0)
            blurred.canvas.drawImage(snapshot, 0f, 0f, paint)
        } finally {
            paint.close()
            snapshot.close()
        }
        maskEdgeBands(blurred, width, height, topBand, bottomBand, tail)
        return blurred.makeImageSnapshot()
    } finally {
        sharp.close()
        blurred.close()
    }
}

/** Keeps the blurred copy under the bands, fading it out [tail] past each band's inner edge. */
private fun maskEdgeBands(
    surface: Surface,
    width: Float,
    height: Float,
    topBand: Float,
    bottomBand: Float,
    tail: Float,
) {
    val scale = 1f / EDGE_BAND_DOWNSCALE
    val h = (height * scale).coerceAtLeast(1f)
    val topEnd = ((topBand + tail) * scale / h).coerceIn(0f, 1f)
    val bottomStart = ((height - bottomBand - tail) * scale / h).coerceIn(0f, 1f)
    if (topEnd > bottomStart) return // The bands meet in a short window: keep the field opaque.
    val topFull = (topBand * scale / h).coerceIn(0f, topEnd)
    val bottomFull = ((height - bottomBand) * scale / h).coerceIn(bottomStart, 1f)
    val opaque = Color.White.toArgb()
    val clear = Color.White.copy(alpha = 0f).toArgb()
    val shader = Shader.makeLinearGradient(
        0f, 0f, 0f, h,
        intArrayOf(opaque, opaque, clear, clear, opaque, opaque),
        floatArrayOf(0f, topFull, topEnd, bottomStart, bottomFull, 1f),
    )
    val paint = Paint().apply {
        blendMode = org.jetbrains.skia.BlendMode.DST_IN
        this.shader = shader
    }
    try {
        surface.canvas.drawRect(Rect.makeWH(width * scale, h), paint)
    } finally {
        paint.close()
        shader.close()
    }
}

/**
 * An opaque near-[DesignTokens.surface] fill for content areas that must stay readable. It is
 * painted as one opaque color so it takes the opaque draw path.
 */
fun Modifier.contentTint(): Modifier = background(contentTintColor)

/** The top, side and bottom hairlines of a glass slab. */
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

private fun DrawScope.drawHairlineRim(shape: Shape, innerSize: Size, insetX: Float, insetY: Float) {
    val outline = shape.createOutline(innerSize, layoutDirection, this)
    val paint = Paint().apply {
        isAntiAlias = true
        color = DesignTokens.glassBorderTop.toArgb()
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeWidth = 1.dp.toPx()
    }
    try {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(insetX, insetY)
            when (outline) {
                is Outline.Rounded -> {
                    val rect = outline.roundRect
                    canvas.nativeCanvas.drawRRect(
                        RRect.makeLTRB(
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom,
                            rect.topLeftCornerRadius.x,
                            rect.topLeftCornerRadius.y,
                        ),
                        paint,
                    )
                }
                is Outline.Rectangle -> canvas.nativeCanvas.drawRect(
                    org.jetbrains.skia.Rect(0f, 0f, innerSize.width, innerSize.height),
                    paint,
                )
                is Outline.Generic -> canvas.nativeCanvas.drawPath(outline.path.asSkiaPath(), paint)
            }
            canvas.nativeCanvas.restore()
        }
    } finally {
        paint.close()
    }
}

private data class SlabRimKey(val width: Int, val height: Int, val radius: Int, val stroke: Float, val innerInset: Int)

private data class SlabShadowKey(val width: Int, val height: Int, val radius: Int, val elevation: Float, val margin: Int)

/** One baked soft shadow image per slab size; see [slabShadow]. */
private class SlabShadowCache {
    private var key: SlabShadowKey? = null
    var image: Image? = null
    var margin: Int = 0
    private var bakedWidth = 0f
    private var bakedHeight = 0f

    fun dispose() {
        image?.close()
        image = null
        key = null
    }

    fun update(width: Float, height: Float, radius: Float, elevation: Float, grid: Float) {
        val margin = (elevation * 2f).roundToInt().coerceAtLeast(4)
        val bakedW = quantize(width, grid).coerceAtLeast(1f)
        val bakedH = quantize(height, grid).coerceAtLeast(1f)
        val next = SlabShadowKey(bakedW.roundToInt(), bakedH.roundToInt(), radius.roundToInt(), elevation, margin)
        if (next == key) return
        dispose()
        key = next
        val w = bakedW.roundToInt() + margin * 2
        val h = bakedH.roundToInt() + margin * 2
        if (w <= 0 || h <= 0) return
        val surface = Surface.makeRaster(ImageInfo(w, h, ColorType.N32, ColorAlphaType.PREMUL))
        val path = org.jetbrains.skia.Path().apply {
            addRRect(
                RRect.makeLTRB(
                    margin.toFloat(),
                    margin + elevation * 0.3f,
                    (margin + bakedW).toFloat(),
                    (margin + bakedH + elevation * 0.3f).toFloat(),
                    radius,
                ),
            )
        }
        val paint = Paint().apply {
            isAntiAlias = true
            color = SHADOW_COLOR
            maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(
                org.jetbrains.skia.FilterBlurMode.NORMAL,
                elevation * 0.55f,
            )
        }
        try {
            surface.canvas.clear(0)
            // Only the outside of the shape casts a shadow; a translucent fill must not show a
            // black plate behind it.
            surface.canvas.clipPath(path, org.jetbrains.skia.ClipMode.DIFFERENCE)
            surface.canvas.drawPath(path, paint)
            image = surface.makeImageSnapshot()
            this.margin = margin
            bakedWidth = bakedW
            bakedHeight = bakedH
        } finally {
            paint.close()
            path.close()
            surface.close()
        }
    }

    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        val image = image ?: return
        if (width == bakedWidth && height == bakedHeight) {
            canvas.drawImage(image, -margin.toFloat(), -margin.toFloat())
        } else {
            canvas.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeLTRB(
                    -margin.toFloat(),
                    -margin.toFloat(),
                    width + margin,
                    height + margin,
                ),
            )
        }
    }

    private companion object {
        const val SHADOW_COLOR = 0x8C000000.toInt()
    }
}

/** The three rim paths and their gradient paints, rebuilt only when the slab size changes. */
private class SlabRimCache {
    private var key: SlabRimKey? = null
    private var outerPath: org.jetbrains.skia.Path? = null
    private var outerPaint: Paint? = null
    private var leftPath: org.jetbrains.skia.Path? = null
    private var leftPaint: Paint? = null
    private var innerPath: org.jetbrains.skia.Path? = null
    private var innerPaint: Paint? = null
    private var shaders: List<Shader> = emptyList()
    private var bakedWidth = 0f
    private var bakedHeight = 0f

    fun dispose() {
        outerPaint?.close()
        outerPaint = null
        leftPaint?.close()
        leftPaint = null
        innerPaint?.close()
        innerPaint = null
        outerPath?.close()
        outerPath = null
        leftPath?.close()
        leftPath = null
        innerPath?.close()
        innerPath = null
        shaders.forEach { it.close() }
        shaders = emptyList()
        key = null
    }

    fun update(width: Float, height: Float, radius: Float, stroke: Float, innerInset: Float, grid: Float) {
        val bakedW = quantize(width, grid).coerceAtLeast(1f)
        val bakedH = quantize(height, grid).coerceAtLeast(1f)
        val next = SlabRimKey(
            bakedW.roundToInt(),
            bakedH.roundToInt(),
            radius.roundToInt(),
            stroke,
            innerInset.roundToInt(),
        )
        if (next == key) return
        dispose()
        key = next
        bakedWidth = bakedW
        bakedHeight = bakedH
        if (bakedW <= stroke || bakedH <= stroke) return
        val half = stroke / 2f
        val outerRadius = (radius - half).coerceAtLeast(0f)
        val outerShader = Shader.makeLinearGradient(
            0f, 0f, 0f, bakedH,
            intArrayOf(DesignTokens.slabRimTop.toArgb(), DesignTokens.slabRimTop.toArgb(), DesignTokens.slabRimBottom.toArgb()),
            floatArrayOf(0f, 0.35f, 1f),
        )
        val leftShader = Shader.makeLinearGradient(
            0f, 0f, 0f, bakedH * 0.55f,
            intArrayOf(DesignTokens.slabRimLeft.toArgb(), DesignTokens.slabRimLeft.copy(alpha = 0f).toArgb()),
            floatArrayOf(0f, 1f),
        )
        val inner = innerInset.coerceAtMost(min(bakedW, bakedH) / 2f - stroke)
        val innerRadius = (radius - inner).coerceAtLeast(1f)
        val innerShader = Shader.makeLinearGradient(
            0f, 0f, 0f, bakedH,
            intArrayOf(
                DesignTokens.slabInnerRim.copy(alpha = 0f).toArgb(),
                DesignTokens.slabInnerRim.copy(alpha = 0f).toArgb(),
                DesignTokens.slabInnerRim.toArgb(),
            ),
            floatArrayOf(0f, 0.62f, 1f),
        )
        shaders = listOf(outerShader, leftShader, innerShader)
        outerPath = org.jetbrains.skia.Path().apply {
            addRRect(RRect.makeLTRB(half, half, bakedW - half, bakedH - half, outerRadius))
        }
        outerPaint = strokePaint(outerShader, stroke)
        leftPath = org.jetbrains.skia.Path().apply {
            moveTo(half, outerRadius.coerceAtMost(bakedH / 2f))
            lineTo(half, bakedH - outerRadius.coerceAtMost(bakedH / 2f))
        }
        leftPaint = strokePaint(leftShader, stroke)
        if (inner > 0f && bakedW - inner * 2f > 0f && bakedH - inner * 2f > 0f) {
            innerPath = org.jetbrains.skia.Path().apply {
                addRRect(
                    RRect.makeLTRB(inner, inner, bakedW - inner, bakedH - inner, innerRadius),
                )
            }
            innerPaint = strokePaint(innerShader, stroke)
        }
    }

    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float) {
        val scaleX = if (bakedWidth > 0f) width / bakedWidth else 1f
        val scaleY = if (bakedHeight > 0f) height / bakedHeight else 1f
        val scaled = scaleX != 1f || scaleY != 1f
        if (scaled) {
            canvas.save()
            canvas.scale(scaleX, scaleY)
        }
        outerPath?.let { path -> outerPaint?.let { canvas.drawPath(path, it) } }
        leftPath?.let { path -> leftPaint?.let { canvas.drawPath(path, it) } }
        innerPath?.let { path -> innerPaint?.let { canvas.drawPath(path, it) } }
        if (scaled) canvas.restore()
    }

    private fun strokePaint(shader: Shader, width: Float): Paint = Paint().apply {
        isAntiAlias = true
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeWidth = width
        this.shader = shader
    }
}

private fun DrawScope.drawSlabRims(shape: Shape, cache: SlabRimCache) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        is Outline.Rectangle -> 0f
        is Outline.Generic -> 0f
    }
    cache.update(
        width = size.width,
        height = size.height,
        radius = radius,
        stroke = 1.dp.toPx(),
        innerInset = DesignTokens.slabInnerRimInset.toPx(),
        grid = CACHE_GRID.toPx(),
    )
    drawIntoCanvas { canvas -> cache.draw(canvas.nativeCanvas, size.width, size.height) }
}

/** The crisp top rim of a capsule: lit at the top edge and fading down the sides. */
private fun DrawScope.drawCapsuleRim(shape: Shape, innerSize: Size, insetX: Float, insetY: Float) {
    val outline = shape.createOutline(innerSize, layoutDirection, this)
    val shader = Shader.makeLinearGradient(
        0f,
        0f,
        0f,
        innerSize.height,
        intArrayOf(DesignTokens.capsuleRim.toArgb(), DesignTokens.capsuleRim.copy(alpha = 0f).toArgb()),
        floatArrayOf(0f, 0.7f),
    )
    val paint = Paint().apply {
        isAntiAlias = true
        this.shader = shader
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeWidth = 1.dp.toPx()
    }
    try {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(insetX, insetY)
            when (outline) {
                is Outline.Rounded -> {
                    val rect = outline.roundRect
                    native.drawRRect(
                        RRect.makeLTRB(
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom,
                            rect.topLeftCornerRadius.x,
                            rect.topLeftCornerRadius.y,
                        ),
                        paint,
                    )
                }
                is Outline.Rectangle -> native.drawRect(
                    org.jetbrains.skia.Rect(0f, 0f, innerSize.width, innerSize.height),
                    paint,
                )
                is Outline.Generic -> native.drawPath(outline.path.asSkiaPath(), paint)
            }
            native.restore()
        }
    } finally {
        paint.close()
        shader.close()
    }
}

/** A restrained neon line on the capsule edge: a soft glow and one crisp 2 dp line. */
private fun DrawScope.drawNeonLine(
    innerSize: Size,
    insetX: Float,
    insetY: Float,
    edge: CapsuleEdge,
    alpha: Float,
) {
    val color = DesignTokens.selectionBar
    val width = DesignTokens.selectionBarWidth.toPx()
    val glow = Paint().apply {
        isAntiAlias = true
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeWidth = 7.dp.toPx()
        this.color = color.copy(alpha = 0.32f * alpha).toArgb()
        maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, 3.5.dp.toPx())
    }
    val crisp = Paint().apply {
        isAntiAlias = true
        mode = org.jetbrains.skia.PaintMode.STROKE
        strokeWidth = width
        this.color = color.copy(alpha = alpha).toArgb()
    }
    try {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(insetX, insetY)
            if (edge == CapsuleEdge.LEFT) {
                val x = width / 2f + 1.dp.toPx()
                val top = 2.dp.toPx()
                val bottom = innerSize.height - 2.dp.toPx()
                native.drawLine(x, top, x, bottom, glow)
                native.drawLine(x, top, x, bottom, crisp)
            } else {
                val y = innerSize.height - width / 2f - 1.dp.toPx()
                val start = 6.dp.toPx()
                val end = innerSize.width - 6.dp.toPx()
                native.drawLine(start, y, end, y, glow)
                native.drawLine(start, y, end, y, crisp)
            }
            native.restore()
        }
    } finally {
        glow.close()
        crisp.close()
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

/**
 * The liquid glass SkSL. The backdrop arrives as `content` (already blurred), the shader lifts
 * its saturation and brightness, tints it neutral, bends it inward inside the bezel with a
 * convex profile and a sub-pixel chromatic split, and lights a specular rim from the top-left.
 */
internal object GlassShader {
    /** Test seam: `false` forces the blur-only fallback. */
    var enabled: Boolean = true

    /** Test seam: overrides the lens strength (device px) when non-null. */
    var strengthOverride: Float? = null

    private var cached: RuntimeEffect? = null
    private var compiled = false

    internal fun runtimeEffect(): RuntimeEffect? {
        if (!enabled) return null
        if (!compiled) {
            cached = runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull()
            compiled = true
        }
        return cached
    }

    fun backdropFilter(
        width: Float,
        height: Float,
        radius: Float,
        sigma: Float,
        bezel: Float,
        strength: Float,
        aberration: Float,
        taper: Float,
        endTaper: Float,
        downscale: Int,
        baseRed: Float,
        baseGreen: Float,
        baseBlue: Float,
        backdropMix: Float,
        chromaGain: Float,
        specular: Float,
    ): ImageFilter? {
        val effect = runtimeEffect() ?: return null
        return runCatching {
            // The shader is evaluated on a 1/[downscale] backdrop copy, so its geometry uniforms
            // and its outer-edge taper are expressed in that copy's pixels.
            val scale = 1f / downscale
            val builder = RuntimeShaderBuilder(effect)
            builder.uniform("size", width * scale, height * scale)
            builder.uniform("origin", 0f, 0f)
            builder.uniform("radius", radius * scale)
            builder.uniform("bezel", bezel * scale)
            builder.uniform("strength", (strengthOverride ?: strength) * scale)
            builder.uniform("aberration", aberration * scale)
            builder.uniform("taper", taper)
            builder.uniform("endTaper", endTaper)
            builder.uniform("baseColor", baseRed, baseGreen, baseBlue)
            builder.uniform("backdropMix", backdropMix)
            builder.uniform("chromaGain", chromaGain)
            builder.uniform("specular", specular)
            val downscaleFilter = ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(scale),
                SamplingMode.LINEAR,
                null,
            )
            val input = ImageFilter.makeBlur(
                sigma / downscale,
                sigma / downscale,
                FilterTileMode.CLAMP,
                downscaleFilter,
            )
            val runtime = ImageFilter.makeRuntimeShader(builder, "content", input)
            ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(downscale.toFloat()),
                SamplingMode.LINEAR,
                runtime,
            )
        }.getOrNull()
    }

    internal fun resetForTests() {
        enabled = true
        strengthOverride = null
        cached = null
        compiled = false
    }

    private val SKSL = """
        uniform shader content;
        uniform float2 size;
        uniform float2 origin;
        uniform float radius;
        uniform float bezel;
        uniform float strength;
        uniform float aberration;
        uniform float taper;
        uniform float endTaper;
        uniform float3 baseColor;
        uniform float backdropMix;
        uniform float chromaGain;
        uniform float specular;

        const half3 LUMA = half3(0.2126, 0.7152, 0.0722);

        float sdRoundRect(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        // Frosted base: the backdrop keeps its hue at low gain and its detail at even lower gain.
        half3 frosted(half3 backdrop) {
            float luma = dot(backdrop, LUMA);
            half3 chroma = backdrop - half3(luma);
            return max(baseColor + chroma * chromaGain + half3(luma * backdropMix), half3(0.0));
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = size * 0.5;
            float r = min(radius, min(halfSize.x, halfSize.y));
            float2 c = fragCoord - origin - halfSize;
            float d = sdRoundRect(c, halfSize, r);

            // 1 at the outer rim, 0 at the inner end of the bezel.
            float t = clamp(1.0 + d / bezel, 0.0, 1.0);

            float2 n = float2(0.0);
            float2 sampleAt = fragCoord;
            if (t > 0.0) {
                n = float2(
                    sdRoundRect(c + float2(1.0, 0.0), halfSize, r) - sdRoundRect(c - float2(1.0, 0.0), halfSize, r),
                    sdRoundRect(c + float2(0.0, 1.0), halfSize, r) - sdRoundRect(c - float2(0.0, 1.0), halfSize, r)
                );
                n = n / max(length(n), 0.0001);

                // Zero at the outer 2 dp and at the inner bezel edge, peaking mid-bezel.
                float mid = clamp(-d / bezel, 0.0, 1.0);
                float profile = 4.0 * mid * (1.0 - mid) * smoothstep(0.0, taper, -d);
                // Capsules fade the bend to zero at their left and right ends.
                float endProfile = 1.0;
                if (endTaper > 0.0) {
                    endProfile = smoothstep(0.0, endTaper, min(fragCoord.x, size.x - fragCoord.x));
                }
                sampleAt = fragCoord - n * (profile * endProfile * strength);
            }

            half3 col = frosted(content.eval(sampleAt).rgb);
            if (t > 0.0 && aberration > 0.05) {
                float split = aberration * t;
                if (split > 0.05) {
                    col.r = frosted(content.eval(sampleAt + n * split).rgb).r;
                    col.b = frosted(content.eval(sampleAt - n * split).rgb).b;
                }
            }

            // Thin specular rim lit from the top-left; fades along the sides and at the bottom.
            float facing = max(dot(n, normalize(float2(-0.55, -0.85))), 0.0);
            float rim = smoothstep(0.72, 1.0, t) * smoothstep(0.0, taper, -d);
            col += half3(rim * facing * facing * specular);

            float bottomShade = smoothstep(0.6, 1.0, t) * max(n.y, 0.0);
            col *= 1.0 - 0.12 * bottomShade;

            return half4(col, 1.0);
        }
    """.trimIndent()
}
