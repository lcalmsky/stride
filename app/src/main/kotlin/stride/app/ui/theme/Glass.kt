package stride.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
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
import stride.app.glass.LocalNativeGlass

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

private var tintPalette: Palette? = null
private var tintColor = Color.Unspecified
private val contentTintColor: Color
    get() {
        val palette = DesignTokens.palette
        if (tintPalette !== palette) {
            tintPalette = palette
            tintColor = blendOver(palette.surface.copy(alpha = DesignTokens.contentTintAlpha), palette.background)
        }
        return tintColor
    }

/** The empty editor field's luminance; a mirrored colour field never turns brighter than it. */
val editorFieldLuminance: Double get() = relativeLuminance(contentTintColor)

/**
 * Holds the cached field bitmap and the raster image drawn from it. Draw-phase data, not
 * snapshot state: the parent draws before its children, so a child can read the bitmap in the
 * same frame without a recomposition loop.
 */
class MeshBitmapHolder {
    var bitmap: ImageBitmap? = null
    var width: Int = 0
    var height: Int = 0
    var appearance: Appearance? = null
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
)

val LocalEditorInsets = staticCompositionLocalOf { EditorInsets() }

/** Test-only observation point: whether the last liquid glass surface used the shader. */
internal object GlassCacheStats {
    var lastSurfaceLiquid: Boolean = false
}

/**
 * Draws a cool graphite field with a gentle top-to-bottom luminance falloff into a cached bitmap,
 * and provides it to the subtree.
 */
@Composable
fun MeshBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // The native glass is the window background; an opaque field here would hide it.
    if (LocalNativeGlass.current) {
        Box(modifier) { content() }
        return
    }
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
            val appearance = AppearanceState.appearance
            if (holder.bitmap == null || holder.width != width || holder.height != height ||
                holder.appearance != appearance
            ) {
                holder.image?.close()
                val bitmap = buildFieldBitmap(width, height)
                holder.bitmap = bitmap
                holder.image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
                holder.width = width
                holder.height = height
                holder.appearance = appearance
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
        cache.update(
            size.width,
            size.height,
            radius,
            DesignTokens.slabShadow.toPx(),
            CACHE_GRID.toPx(),
            DesignTokens.slabShadowColor,
        )
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
 * A small capsule inset from its row or tab. A selected capsule is the selection pill: the white
 * lift, the accent tint and the crisp top rim. Hover draws the same pill lighter. There is no
 * edge line, so a selected item carries exactly one signal.
 */
fun Modifier.glassCapsule(
    shape: Shape,
    insetX: Dp = 6.dp,
    insetY: Dp = 2.dp,
    fillAlpha: Float = 0f,
    selectionAlpha: Float = 0f,
): Modifier = drawBehind {
    // A selected item uses its own alpha; hover is the same pill at a lighter strength.
    val pillAlpha = if (selectionAlpha > 0f) selectionAlpha else fillAlpha * HOVER_PILL_ALPHA
    if (pillAlpha <= 0f) return@drawBehind
    val left = insetX.toPx()
    val top = insetY.toPx()
    val innerWidth = size.width - left * 2f
    val innerHeight = size.height - top * 2f
    if (innerWidth <= 0f || innerHeight <= 0f) return@drawBehind
    drawSelectionPill(shape, Size(innerWidth, innerHeight), left, top, pillAlpha)
}

/**
 * The lens capsule on the selected tree row and the active tab: the backdrop bends inside a
 * [DesignTokens.capsuleBezel] rim, then the selection pill (lift, accent tint and top rim) sits on
 * top and is the only signal. The displacement fades to zero at the left and right ends, so
 * nothing smears there.
 */
fun Modifier.glassLensCapsule(
    shape: Shape,
    insetX: Dp = 6.dp,
    insetY: Dp = 2.dp,
    selectionAlpha: Float = 1f,
): Modifier = composed {
    val nativeGlass = LocalNativeGlass.current
    val cache = remember { BackdropFilterCache() }
    DisposableEffect(cache) {
        onDispose { cache.dispose() }
    }
    drawWithContent {
        val left = insetX.toPx()
        val top = insetY.toPx()
        val innerWidth = size.width - left * 2f
        val innerHeight = size.height - top * 2f
        if (innerWidth <= 0f || innerHeight <= 0f || selectionAlpha <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val innerSize = Size(innerWidth, innerHeight)
        if (nativeGlass) {
            // Nothing to bend over the native material; the selection pill stays.
            drawSelectionPill(shape, innerSize, left, top, selectionAlpha)
            drawContent()
            return@drawWithContent
        }
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
        drawSelectionPill(shape, innerSize, 0f, 0f, selectionAlpha)
        native.restore()
        drawContent()
    }
}

/** A popup selection row: the pill when selected, the same pill lighter on hover. */
@Composable
fun Modifier.selectionRow(selected: Boolean, hovered: Boolean): Modifier = glassCapsule(
    shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.radiusCapsule),
    insetX = 6.dp,
    insetY = 2.dp,
    fillAlpha = if (hovered && !selected) 1f else 0f,
    selectionAlpha = rememberSelectionAlpha(selected),
)

/** How much of the selection pill's strength a hovered but unselected item keeps. */
private const val HOVER_PILL_ALPHA = 0.45f

/**
 * The single signal of a selected item: the white lift under the accent tint, clipped to the
 * capsule, plus the crisp top rim. The alpha scales the whole pill so the selection can fade.
 */
private fun DrawScope.drawSelectionPill(
    shape: Shape,
    innerSize: Size,
    insetX: Float,
    insetY: Float,
    alpha: Float,
) {
    val offset = Offset(insetX, insetY)
    val path = capsulePath(shape, innerSize).apply { translate(offset) }
    clipPath(path) {
        val lift = DesignTokens.selectionPillLift
        val tint = DesignTokens.selectionPillTint
        drawRect(lift.copy(alpha = lift.alpha * alpha), topLeft = offset, size = innerSize)
        drawRect(tint.copy(alpha = tint.alpha * alpha), topLeft = offset, size = innerSize)
    }
    drawCapsuleRim(shape, innerSize, insetX, insetY, alpha)
}

private fun DrawScope.capsulePath(shape: Shape, size: Size): Path {
    val outline = shape.createOutline(size, layoutDirection, this)
    return when (outline) {
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Generic -> Path().apply { addPath(outline.path) }
    }
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
    var appearance: Appearance? = null
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
            this.endTaper == endTaper && this.backdropMix == backdropMix && this.downscale == downscale &&
            this.appearance == AppearanceState.appearance
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
        this.appearance = AppearanceState.appearance
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
 * On the native path the capsule is its own glass piece and this draws nothing.
 */
fun Modifier.capsuleGlass(shape: Shape): Modifier = composed {
    if (LocalNativeGlass.current) return@composed Modifier
    slabShadow(shape)
        .capsuleFrost(shape)
        .glassSlabRims(shape)
        .drawBehind {
            clipPath(capsulePath(shape, size)) {
                drawRect(DesignTokens.capsuleFill)
                drawRect(DesignTokens.capsuleLift)
            }
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
fun Modifier.sidebarGlass(shape: Shape): Modifier = composed {
    if (LocalNativeGlass.current) return@composed Modifier
    slabShadow(shape)
        .glassSlabRims(shape)
        .drawBehind {
            clipPath(capsulePath(shape, size)) {
                drawRect(DesignTokens.sidebarFill)
            }
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
    if (LocalNativeGlass.current) return@composed Modifier
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

/** Two raster surfaces reused across rebuilds, so a scroll step does not allocate them. */
internal class BandSurfaces {
    private var sharp: Surface? = null
    private var blurred: Surface? = null
    private var width = 0
    private var height = 0

    fun obtain(width: Int, height: Int): Pair<Surface, Surface> {
        val currentSharp = sharp
        val currentBlurred = blurred
        if (currentSharp != null && currentBlurred != null && this.width == width && this.height == height) {
            return currentSharp to currentBlurred
        }
        dispose()
        val info = ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL)
        sharp = Surface.makeRaster(info)
        blurred = Surface.makeRaster(info)
        this.width = width
        this.height = height
        return checkNotNull(sharp) to checkNotNull(blurred)
    }

    fun dispose() {
        sharp?.close()
        sharp = null
        blurred?.close()
        blurred = null
        width = 0
        height = 0
    }
}

/** One cached mirror of the content strip right of the sidebar; a frame only blits it. */
class MirroredStripCache {
    internal var key: Any? = null
    internal var image: Image? = null
    internal val surfaces = BandSurfaces()

    fun dispose() {
        image?.close()
        image = null
        key = null
        surfaces.dispose()
    }
}

/**
 * The background extension: the strip of real content right of [sourceLeft] is mirrored back
 * across that vertical axis and drawn into the element, blurred. [source] paints that content in
 * window coordinates; it is rendered into a small offscreen image once per [key] and blurred
 * once, so a static frame only blits it. Only the rounded shape at [shapeLeft]/[shapeWidth]
 * receives the field, so outside it the editor's own background stays untouched and no rectangle
 * shows. The element must cover the shape and be drawn after the content.
 */
fun Modifier.backgroundExtension(
    key: () -> Any,
    cache: MirroredStripCache,
    sourceLeft: Dp,
    sourceTop: Dp,
    sourceWidth: Dp,
    shapeLeft: Dp,
    shapeWidth: Dp,
    shapeRadius: Dp,
    luminanceCap: Double = Double.MAX_VALUE,
    blurRadius: Dp = DesignTokens.backgroundExtensionBlur,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Modifier = composed {
    // The source is painted in window coordinates while this modifier draws in the element's
    // local frame, so the root position converts the mirror axis into local coordinates.
    val rootLeft = remember { FloatArray(1) }
    this
        .onGloballyPositioned { rootLeft[0] = it.boundsInRoot().left }
        .drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val sourceLeftPx = sourceLeft.toPx()
            val sourceWidthPx = sourceWidth.toPx()
            val image = cache.imageFor(
                key = key(),
                width = size.width,
                height = size.height,
                sourceLeft = sourceLeftPx,
                sourceTop = sourceTop.toPx(),
                sourceWidth = sourceWidthPx,
                sigma = blurRadius.toPx() * BLUR_SIGMA_SCALE,
                luminanceCap = luminanceCap,
                source = source,
            ) ?: return@drawBehind
            val native = drawContext.canvas.nativeCanvas
            val radius = shapeRadius.toPx()
            native.save()
            native.clipRRect(
                RRect.makeLTRB(
                    shapeLeft.toPx(),
                    0f,
                    (shapeLeft + shapeWidth).toPx(),
                    size.height,
                    radius,
                ),
            )
            // Mirror across the sidebar's right edge: the source pixel at the axis lands on the
            // axis, and the content right of it folds left, at one screen pixel per source pixel.
            native.translate(sourceLeftPx - rootLeft[0], 0f)
            native.scale(-1f, 1f)
            native.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(sourceWidthPx, size.height),
                SamplingMode.LINEAR,
                null,
                true,
            )
            native.restore()
        }
}

private data class StripKey(
    val key: Any,
    val width: Int,
    val height: Int,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceWidth: Int,
    val sigma: Float,
    val luminanceCap: Double,
    val appearance: Appearance,
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
    luminanceCap: Double,
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
        luminanceCap = luminanceCap,
        appearance = AppearanceState.appearance,
    )
    if (next == this.key) return image
    // Close the previous snapshot before drawing into the shared surfaces: otherwise Skia copies
    // the surface on the first write so the old image keeps its pixels.
    image?.close()
    image = null
    val built = buildMirroredStrip(
        surfaces = surfaces,
        width = width,
        height = height,
        sourceLeft = sourceLeft,
        sourceTop = sourceTop,
        sourceWidth = sourceWidth,
        sigma = sigma,
        luminanceCap = luminanceCap,
        source = source,
    )
    this.key = next
    image = built
    return image
}

private fun buildMirroredStrip(
    surfaces: BandSurfaces,
    width: Float,
    height: Float,
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sigma: Float,
    luminanceCap: Double,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    if (sourceWidth <= 0f || width <= 0f || height <= 0f) return null
    val stripWidth = (width / MIRROR_DOWNSCALE).roundToInt().coerceAtLeast(1)
    val stripHeight = (height / MIRROR_DOWNSCALE).roundToInt().coerceAtLeast(1)
    val scale = stripWidth / sourceWidth
    val (sharp, blurred) = surfaces.obtain(stripWidth, stripHeight)
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
    val blurredImage = blurred.makeImageSnapshot()
    try {
        val result = clampFieldLuminance(blurredImage, luminanceCap)
        return result
    } finally {
        blurredImage.close()
    }
}

private fun srgbToLinear(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(value: Float): Float =
    if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f

/**
 * Caps the field's mean luminance at [cap]. Dense code would otherwise raise the mean until the
 * glass reads as a grey slab; subtracting one luminance level from every pixel keeps the code's
 * relative contrast, so changing the document still changes the pixels.
 */
private fun clampFieldLuminance(image: Image, cap: Double): Image? {
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap()
    try {
        bitmap.allocN32Pixels(width, height, false)
        if (!image.readPixels(bitmap)) return null
        val colors = IntArray(width * height)
        val luminances = DoubleArray(colors.size)
        var total = 0.0
        for (index in colors.indices) {
            val argb = bitmap.getColor(index % width, index / width)
            colors[index] = argb
            val luminance = relativeLuminance(Color(argb))
            luminances[index] = luminance
            total += luminance
        }
        val offset = if (total / colors.size > cap) luminanceFloor(luminances, cap) else 0.0
        val bytes = ByteArray(colors.size * 4)
        for (index in colors.indices) {
            val argb = colors[index]
            val at = index * 4
            // N32 is BGRA in memory, and every blurred pixel is opaque.
            bytes[at] = shiftedChannel(argb and 0xFF, offset)
            bytes[at + 1] = shiftedChannel((argb ushr 8) and 0xFF, offset)
            bytes[at + 2] = shiftedChannel((argb ushr 16) and 0xFF, offset)
            bytes[at + 3] = 0xFF.toByte()
        }
        if (!bitmap.installPixels(
                ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL),
                bytes,
                width * 4,
            )
        ) {
            return null
        }
        return Image.makeFromBitmap(bitmap)
    } finally {
        bitmap.close()
    }
}

/** The amount subtracted from every pixel so the field's mean luminance falls back to [cap]. */
private fun luminanceFloor(luminances: DoubleArray, cap: Double): Double {
    var low = 0.0
    var high = luminances.max()
    repeat(32) {
        val mid = (low + high) / 2
        var total = 0.0
        for (luminance in luminances) total += maxOf(0.0, luminance - mid)
        if (total / luminances.size > cap) low = mid else high = mid
    }
    return (low + high) / 2
}

private fun shiftedChannel(channel: Int, offset: Double): Byte {
    if (offset == 0.0) return channel.toByte()
    val linear = (srgbToLinear(channel / 255f) - offset).coerceAtLeast(0.0)
    return (linearToSrgb(linear.toFloat()).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}

/** One cached blurred copy of each scroll band, drawn by [scrollEdgeBands]. */
class EdgeBandCache {
    internal var key: Any? = null
    internal var top: Image? = null
    internal var bottom: Image? = null
    internal val topSurfaces = BandSurfaces()
    internal val bottomSurfaces = BandSurfaces()

    fun dispose() {
        top?.close()
        top = null
        bottom?.close()
        bottom = null
        key = null
        topSurfaces.dispose()
        bottomSurfaces.dispose()
    }
}

/**
 * The scroll edge effect: a blurred copy of the editor content, opaquely covering the top and
 * bottom bands and easing out [tail] beyond each band's inner edge, so the content under the
 * control rows softens toward the window edge without a cut line. [source] paints the editor
 * content in the element's coordinates at full resolution; each band is rendered into its own
 * small offscreen image and masked once per [key], so a static frame only blits them. Only the
 * bands and their tails are rendered, so the blurred area does not grow with the window. Nothing
 * is drawn left of [clipStart] (the gutter), where the sidebar's background extension owns the
 * pixels.
 */
fun Modifier.scrollEdgeBands(
    key: () -> Any,
    cache: EdgeBandCache,
    topBand: Dp,
    bottomBand: Dp,
    clipStart: Dp,
    blurRadius: Dp = DesignTokens.scrollEdgeBlur,
    tail: Dp = DesignTokens.scrollEdgeTail,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Modifier = drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val sigma = blurRadius.toPx() * BLUR_SIGMA_SCALE
    // The blur samples a few sigma past the tail; the strip carries that margin so the fade
    // still sees the real content.
    val margin = (sigma * EDGE_BLUR_MARGIN_SIGMAS).coerceAtMost(size.height)
    val topRows = (topBand.toPx() + tail.toPx() + margin).coerceAtMost(size.height)
    val bottomRows = (bottomBand.toPx() + tail.toPx() + margin).coerceAtMost(size.height)
    val bands = cache.imageFor(
        key = key(),
        width = size.width,
        height = size.height,
        topRows = topRows,
        bottomRows = bottomRows,
        topBand = topBand.toPx(),
        bottomBand = bottomBand.toPx(),
        tail = tail.toPx(),
        sigma = sigma,
        source = source,
    ) ?: return@drawBehind
    val native = drawContext.canvas.nativeCanvas
    native.save()
    native.clipRect(org.jetbrains.skia.Rect.makeLTRB(clipStart.toPx(), 0f, size.width, size.height))
    drawStrip(native, bands.first, 0f, size.width, topRows)
    drawStrip(native, bands.second, size.height - bottomRows, size.width, bottomRows)
    native.restore()
}

/** Draws a masked band strip, upscaled from its downscaled copy to the element's pixels. */
private fun drawStrip(
    native: org.jetbrains.skia.Canvas,
    image: Image,
    top: Float,
    width: Float,
    rows: Float,
) {
    if (rows <= 0f) return
    native.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeLTRB(0f, top, width, top + rows),
        SamplingMode.LINEAR,
        null,
        true,
    )
}

/** 1/[EDGE_BAND_DOWNSCALE] copy is blurred once; the full-size blit upsamples it back. */
private const val EDGE_BAND_DOWNSCALE = 16

/** How far past the tail (in blur sigmas) a strip keeps real content for the blur to sample. */
private const val EDGE_BLUR_MARGIN_SIGMAS = 3f

private data class EdgeBandKey(
    val key: Any,
    val width: Int,
    val height: Int,
    val topRows: Int,
    val bottomRows: Int,
    val topBand: Int,
    val bottomBand: Int,
    val tail: Int,
    val sigma: Int,
    val appearance: Appearance,
)

private fun EdgeBandCache.imageFor(
    key: Any,
    width: Float,
    height: Float,
    topRows: Float,
    bottomRows: Float,
    topBand: Float,
    bottomBand: Float,
    tail: Float,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Pair<Image, Image>? {
    val next = EdgeBandKey(
        key = key,
        width = width.roundToInt(),
        height = height.roundToInt(),
        topRows = topRows.roundToInt(),
        bottomRows = bottomRows.roundToInt(),
        topBand = topBand.roundToInt(),
        bottomBand = bottomBand.roundToInt(),
        tail = tail.roundToInt(),
        sigma = sigma.roundToInt(),
        appearance = AppearanceState.appearance,
    )
    if (next == this.key) {
        val cachedTop = top
        val cachedBottom = bottom
        return if (cachedTop != null && cachedBottom != null) cachedTop to cachedBottom else null
    }
    // Close the previous snapshots before drawing into the shared surfaces: otherwise Skia
    // copies the surface on the first write so the old image keeps its pixels.
    top?.close()
    top = null
    bottom?.close()
    bottom = null
    val opaque = Color.White.toArgb()
    val clear = Color.White.copy(alpha = 0f).toArgb()
    val builtTop = buildEdgeStrip(
        surfaces = topSurfaces,
        width = width,
        stripRows = topRows,
        stripTop = 0f,
        stops = floatArrayOf(0f, topBand, topBand + tail, topRows),
        alphas = intArrayOf(opaque, opaque, clear, clear),
        sigma = sigma,
        source = source,
    )
    val bottomTop = height - bottomRows
    val builtBottom = buildEdgeStrip(
        surfaces = bottomSurfaces,
        width = width,
        stripRows = bottomRows,
        stripTop = bottomTop,
        // Local positions: clear until the tail starts, opaque from the band's edge down.
        stops = floatArrayOf(0f, bottomRows - bottomBand - tail, bottomRows - bottomBand, bottomRows),
        alphas = intArrayOf(clear, clear, opaque, opaque),
        sigma = sigma,
        source = source,
    )
    this.key = next
    top = builtTop
    bottom = builtBottom
    return if (builtTop != null && builtBottom != null) builtTop to builtBottom else null
}

/**
 * Blurs and masks one band strip. [stops] are local element rows for [alphas], ascending; the
 * strip is painted with the source, blurred, then multiplied by that vertical alpha ramp.
 */
private fun buildEdgeStrip(
    surfaces: BandSurfaces,
    width: Float,
    stripRows: Float,
    stripTop: Float,
    stops: FloatArray,
    alphas: IntArray,
    sigma: Float,
    source: (androidx.compose.ui.graphics.Canvas) -> Unit,
): Image? {
    if (width <= 0f || stripRows <= 0f || stripTop < 0f) return null
    val scale = 1f / EDGE_BAND_DOWNSCALE
    val stripWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val stripHeight = (stripRows * scale).roundToInt().coerceAtLeast(1)
    val (sharp, blurred) = surfaces.obtain(stripWidth, stripHeight)
    // The field behind the code is opaque, so the band replaces the content instead of
    // stacking a second set of glyphs on top of the sharp ones.
    sharp.canvas.clear(contentTintColor.toArgb())
    sharp.canvas.scale(scale, scale)
    sharp.canvas.translate(0f, -stripTop)
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
    val positions = FloatArray(stops.size) { index ->
        (stops[index] * scale / stripHeight).coerceIn(0f, 1f)
    }
    val shader = Shader.makeLinearGradient(0f, 0f, 0f, stripHeight.toFloat(), alphas, positions)
    val mask = Paint().apply {
        blendMode = org.jetbrains.skia.BlendMode.DST_IN
        this.shader = shader
    }
    try {
        blurred.canvas.drawRect(Rect.makeWH(stripWidth.toFloat(), stripHeight.toFloat()), mask)
    } finally {
        mask.close()
        shader.close()
    }
    return blurred.makeImageSnapshot()
}

/**
 * An opaque near-[DesignTokens.surface] fill for content areas that must stay readable. It is
 * painted as one opaque color so it takes the opaque draw path.
 */
fun Modifier.contentTint(): Modifier = composed {
    if (LocalNativeGlass.current) return@composed Modifier
    background(contentTintColor)
}

/**
 * Clips an editor or tool-window body to [shape] minus the window-relative [holes]. The native
 * glass piece paints behind all Compose drawing, so code or console glyphs under a popup's own
 * glass would still show through it; this clip removes them. Nothing is filled: the surface
 * itself is the native glass piece.
 */
fun Modifier.contentClip(shape: Shape, holes: List<androidx.compose.ui.geometry.Rect>): Modifier = composed {
    val density = LocalDensity.current
    val origin = remember { floatArrayOf(0f, 0f) }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        origin[0] = bounds.left
        origin[1] = bounds.top
    }
    drawWithContent {
        val path = capsulePath(shape, size)
        if (holes.isNotEmpty()) {
            for (hole in holes) {
                val local = holeRectInLocal(hole, density.density, Offset(origin[0], origin[1]))
                if (local.right > local.left && local.bottom > local.top) {
                    path.addRoundRect(
                        RoundRect(
                            rect = local,
                            cornerRadius = CornerRadius(DesignTokens.radiusPopup.toPx()),
                        ),
                    )
                }
            }
            path.fillType = PathFillType.EvenOdd
        }
        clipPath(path) { this@drawWithContent.drawContent() }
    }
}

/**
 * A window-relative hole in dp (what [stride.app.glass.GlassHoles] stores) as the local drawing
 * rectangle in pixels. The origin is the drawing element's own window position in pixels, so a
 * hole and the content it must clip stay in the same space.
 */
internal fun holeRectInLocal(
    hole: androidx.compose.ui.geometry.Rect,
    density: Float,
    origin: Offset,
): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect(
    left = hole.left * density - origin.x,
    top = hole.top * density - origin.y,
    right = hole.right * density - origin.x,
    bottom = hole.bottom * density - origin.y,
)

/**
 * The modal scrim. On the native path the popup's own glass sits below the AWT content view, so
 * a full-window scrim would darken the popup's material too; each [holes] rectangle is cut out.
 * On the Skia path the popup is painted over the scrim, so the plain fill is unchanged.
 */
fun Modifier.scrimSurface(alpha: Float, holes: List<androidx.compose.ui.geometry.Rect>): Modifier = composed {
    if (!LocalNativeGlass.current) return@composed background(DesignTokens.scrim.copy(alpha = alpha))
    val color = DesignTokens.scrim.copy(alpha = alpha * DesignTokens.nativeScrimFactor)
    val density = LocalDensity.current
    val origin = remember { floatArrayOf(0f, 0f) }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        origin[0] = bounds.left
        origin[1] = bounds.top
    }
    drawBehind {
        val path = Path()
        path.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        for (hole in holes) {
            val local = holeRectInLocal(hole, density.density, Offset(origin[0], origin[1]))
            if (local.right > local.left && local.bottom > local.top) {
                path.addRoundRect(
                    RoundRect(
                        rect = local,
                        cornerRadius = CornerRadius(DesignTokens.radiusPopup.toPx()),
                    ),
                )
            }
        }
        path.fillType = PathFillType.EvenOdd
        drawPath(path, color)
    }
}

/**
 * The drop shadow around a floating glass panel. The Skia path draws it with Compose. The
 * native path must not: the panel's own Compose content is transparent over the system material,
 * so a Compose shadow paints its penumbra *inside* the panel, where it reads as an opaque fill
 * (and leaves the unshadowed umbra as an offset light rectangle). There the shadow lives on the
 * native glass view's layer instead; see [stride.app.glass.nativeGlass].
 */
fun Modifier.popupShadow(shape: Shape, elevation: Dp = 24.dp): Modifier = composed {
    if (LocalNativeGlass.current) this else shadow(elevation, shape, clip = false)
}

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

private data class SlabRimKey(
    val width: Int,
    val height: Int,
    val radius: Int,
    val stroke: Float,
    val innerInset: Int,
    val appearance: Appearance,
)

private data class SlabShadowKey(
    val width: Int,
    val height: Int,
    val radius: Int,
    val elevation: Float,
    val margin: Int,
    val color: Int,
)

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

    fun update(width: Float, height: Float, radius: Float, elevation: Float, grid: Float, color: Int) {
        val margin = (elevation * 2f).roundToInt().coerceAtLeast(4)
        val bakedW = quantize(width, grid).coerceAtLeast(1f)
        val bakedH = quantize(height, grid).coerceAtLeast(1f)
        val next = SlabShadowKey(
            bakedW.roundToInt(),
            bakedH.roundToInt(),
            radius.roundToInt(),
            elevation,
            margin,
            color,
        )
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
            this.color = color
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
            AppearanceState.appearance,
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
private fun DrawScope.drawCapsuleRim(
    shape: Shape,
    innerSize: Size,
    insetX: Float,
    insetY: Float,
    alpha: Float = 1f,
) {
    val outline = shape.createOutline(innerSize, layoutDirection, this)
    val shader = Shader.makeLinearGradient(
        0f,
        0f,
        0f,
        innerSize.height,
        intArrayOf(
            DesignTokens.capsuleRim.copy(alpha = DesignTokens.capsuleRim.alpha * alpha).toArgb(),
            DesignTokens.capsuleRim.copy(alpha = 0f).toArgb(),
        ),
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
            val input = try {
                ImageFilter.makeBlur(
                    sigma / downscale,
                    sigma / downscale,
                    FilterTileMode.CLAMP,
                    downscaleFilter,
                )
            } finally {
                downscaleFilter.close()
            }
            val runtime = try {
                ImageFilter.makeRuntimeShader(builder, "content", input)
            } finally {
                input.close()
            }
            try {
                ImageFilter.makeMatrixTransform(
                    Matrix33.makeScale(downscale.toFloat()),
                    SamplingMode.LINEAR,
                    runtime,
                )
            } finally {
                runtime.close()
            }
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
