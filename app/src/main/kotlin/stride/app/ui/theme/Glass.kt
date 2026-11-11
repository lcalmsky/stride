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

/** [Modifier.blur]'s radius-to-sigma factor, matching Compose's `BlurSigmaScale`. */
private const val BLUR_SIGMA_SCALE = 0.57735f

/** Backdrop blur runs on a 1/this-resolution copy of the backdrop; see [backdropImageFilter]. */
private const val BACKDROP_BLUR_DOWNSCALE = 8

/** The lens blur runs on a 1/[LENS_DOWNSCALE] copy of the backdrop; the bend stays soft. */
private const val LENS_DOWNSCALE = 4

/** The lens displacement fades to zero over this distance from the outer rim. */
private val LENS_TAPER = 2.dp

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

/** The liquid glass filter, rebuilt only when the panel's size or corner radius changes. */
private class BackdropFilterCache {
    var width: Int = -1
    var height: Int = -1
    var radius: Int = -1
    var density: Float = -1f
    var filter: ImageFilter? = null
}

/**
 * Blurs the already-drawn backdrop under this element through the liquid glass shader, then
 * paints the rim and the content. When the shader is unavailable the previous blur-only glass is
 * used instead.
 */
fun Modifier.backdropGlass(shape: Shape = RectangleShape): Modifier = composed {
    val cache = remember { BackdropFilterCache() }
    DisposableEffect(cache) {
        onDispose {
            cache.filter?.close()
            cache.filter = null
        }
    }
    drawWithContent {
        val sigma = DesignTokens.glassBlurRadius.toPx() * BLUR_SIGMA_SCALE
        val liquidSigma = DesignTokens.liquidGlassBlurRadius.toPx() * BLUR_SIGMA_SCALE
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = when (outline) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            is Outline.Generic -> 0f
        }
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        val radiusKey = radius.roundToInt()
        if (cache.width != width || cache.height != height || cache.radius != radiusKey || cache.density != density) {
            cache.filter?.close()
            cache.filter = GlassShader.backdropFilter(
                width = size.width,
                height = size.height,
                radius = radius,
                sigma = liquidSigma,
                bezel = DesignTokens.glassBezel.toPx(),
                strength = DesignTokens.glassLensStrength.toPx(),
                aberration = DesignTokens.glassAberration.toPx(),
                taper = LENS_TAPER.toPx() / LENS_DOWNSCALE,
                baseRed = DesignTokens.glassBase.red,
                baseGreen = DesignTokens.glassBase.green,
                baseBlue = DesignTokens.glassBase.blue,
                backdropMix = DesignTokens.glassBackdropMix,
                chromaGain = DesignTokens.glassChromaGain,
                specular = DesignTokens.glassSpecular,
            )
            cache.width = width
            cache.height = height
            cache.radius = radiusKey
            cache.density = density
        }
        val liquid = cache.filter
        GlassCacheStats.lastSurfaceLiquid = liquid != null
        val native = drawContext.canvas.nativeCanvas
        native.save()
        when (outline) {
            is Outline.Rectangle -> native.clipRect(org.jetbrains.skia.Rect(0f, 0f, size.width, size.height))
            is Outline.Rounded -> native.clipPath(Path().apply { addRoundRect(outline.roundRect) }.asSkiaPath())
            is Outline.Generic -> native.clipPath(outline.path.asSkiaPath())
        }
        native.saveLayer(
            SaveLayerRec(
                org.jetbrains.skia.Rect(0f, 0f, size.width, size.height),
                null,
                liquid ?: backdropImageFilter(sigma),
            ),
        )
        if (liquid == null) {
            // Same frosted base as the shader path, over the blur-only backdrop.
            drawRect(DesignTokens.glassBase.copy(alpha = 1f - DesignTokens.glassBackdropMix), size = size)
            drawGlassBorder()
        }
        native.restore()
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
        baseRed: Float,
        baseGreen: Float,
        baseBlue: Float,
        backdropMix: Float,
        chromaGain: Float,
        specular: Float,
    ): ImageFilter? {
        val effect = runtimeEffect() ?: return null
        return runCatching {
            // The shader is evaluated on the 1/[LENS_DOWNSCALE] backdrop copy, so its geometry
            // uniforms and its outer-edge taper are expressed in that copy's pixels.
            val scale = 1f / LENS_DOWNSCALE
            val builder = RuntimeShaderBuilder(effect)
            builder.uniform("size", width * scale, height * scale)
            builder.uniform("origin", 0f, 0f)
            builder.uniform("radius", radius * scale)
            builder.uniform("bezel", bezel * scale)
            builder.uniform("strength", (strengthOverride ?: strength) * scale)
            builder.uniform("aberration", aberration * scale)
            builder.uniform("taper", taper)
            builder.uniform("baseColor", baseRed, baseGreen, baseBlue)
            builder.uniform("backdropMix", backdropMix)
            builder.uniform("chromaGain", chromaGain)
            builder.uniform("specular", specular)
            val downscale = ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(scale),
                SamplingMode.LINEAR,
                null,
            )
            val input = ImageFilter.makeBlur(
                sigma / LENS_DOWNSCALE,
                sigma / LENS_DOWNSCALE,
                FilterTileMode.CLAMP,
                downscale,
            )
            val runtime = ImageFilter.makeRuntimeShader(builder, "content", input)
            ImageFilter.makeMatrixTransform(
                Matrix33.makeScale(LENS_DOWNSCALE.toFloat()),
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
                sampleAt = fragCoord - n * (profile * strength);
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
