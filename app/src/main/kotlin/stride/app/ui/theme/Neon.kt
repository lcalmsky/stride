package stride.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import stride.app.ui.LocalAnimationsEnabled

enum class NeonEdge { TOP, BOTTOM }

/** A glow that bleeds outwards from [shape]. Nothing is drawn when [alpha] is 0. */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = DesignTokens.glowRadius,
    shape: Shape = RectangleShape,
    alpha: Float = 1f,
): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    drawBlurredShape(shape, color.glow(alpha), radius.toPx() / 2f)
}

/** A crisp 1dp outline plus the same color's glow. */
fun Modifier.neonBorder(
    color: Color,
    shape: Shape,
    radius: Dp = DesignTokens.glowRadius,
    alpha: Float = 1f,
): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    val stroke = 1.dp.toPx()
    val glowPaint = strokePaint(color.glow(alpha), stroke * 2f).apply {
        asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius.toPx() / 2f)
    }
    drawShape(shape, glowPaint, inset = stroke)
    drawShape(shape, strokePaint(color.copy(alpha = color.alpha * alpha), stroke), inset = stroke / 2f)
}

/** A glowing [thickness]-wide line on the bottom edge, or the top edge via [edge]. */
fun Modifier.neonUnderline(
    color: Color,
    thickness: Dp = 2.dp,
    radius: Dp = DesignTokens.glowRadius,
    alpha: Float = 1f,
    edge: NeonEdge = NeonEdge.BOTTOM,
): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    val width = thickness.toPx()
    val y = if (edge == NeonEdge.TOP) width / 2f else size.height - width / 2f
    val start = Offset(0f, y)
    val end = Offset(size.width, y)
    val glowPaint = strokePaint(color.glow(alpha), width * 2f).apply {
        asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius.toPx() / 2f)
    }
    drawIntoCanvas { it.drawLine(start, end, glowPaint) }
    drawIntoCanvas { it.drawLine(start, end, strokePaint(color.copy(alpha = color.alpha * alpha), width)) }
}

/** The focused panel's top neon line. Nothing is drawn when [active] is false. */
fun Modifier.focusHalo(active: Boolean, color: Color = DesignTokens.accent2): Modifier =
    if (active) {
        neonUnderline(color, thickness = 1.dp, radius = DesignTokens.glowRadiusLarge, edge = NeonEdge.TOP)
    } else {
        this
    }

/** The 3dp neon bar on a selected row's left edge, with its glow. */
fun Modifier.neonSelectionBar(
    color: Color = DesignTokens.selectionBar,
    alpha: Float = 1f,
): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    val width = DesignTokens.selectionBarWidth.toPx()
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = color.glow(alpha)
        asFrameworkPaint().maskFilter =
            MaskFilter.makeBlur(FilterBlurMode.NORMAL, DesignTokens.glowRadius.toPx() / 2f)
    }
    drawIntoCanvas {
        it.drawRect(0f, 0f, width, size.height, paint)
    }
    drawRect(color.copy(alpha = color.alpha * alpha), topLeft = Offset.Zero, size = Size(width, size.height))
}

/**
 * A three-layer glow behind [shape]: a tight core, a mid halo and a wide bloom. Each layer's
 * alpha is multiplied by [alpha]; nothing is drawn when [alpha] is 0 or less.
 */
fun Modifier.neonGlowLayered(
    color: Color,
    shape: Shape = RectangleShape,
    alpha: Float = 1f,
): Modifier = drawBehind {
    if (alpha <= 0f) return@drawBehind
    drawBlurredShape(
        shape,
        color.copy(alpha = color.alpha * DesignTokens.glowCoreAlpha * alpha),
        DesignTokens.glowCoreSigma.toPx(),
    )
    drawBlurredShape(
        shape,
        color.copy(alpha = color.alpha * DesignTokens.glowHaloAlpha * alpha),
        DesignTokens.glowHaloSigma.toPx(),
    )
    drawBlurredShape(
        shape,
        color.copy(alpha = color.alpha * DesignTokens.glowBloomAlpha * alpha),
        DesignTokens.glowBloomSigma.toPx(),
    )
}

/** Selection fade. Instant 0f/1f when [LocalAnimationsEnabled] is false. */
@Composable
fun rememberNeonAlpha(active: Boolean): Float {
    if (!LocalAnimationsEnabled.current) return if (active) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(DesignTokens.neonFadeMillis),
        label = "neon-alpha",
    )
    return alpha
}

private fun Color.glow(alpha: Float): Color = copy(alpha = this.alpha * 0.8f * alpha)

private fun strokePaint(color: Color, width: Float): Paint = Paint().apply {
    isAntiAlias = true
    style = PaintingStyle.Stroke
    strokeWidth = width
    this.color = color
}

private fun DrawScope.drawBlurredShape(shape: Shape, color: Color, sigma: Float) {
    if (color.alpha <= 0f || sigma <= 0f) return
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = color
        asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
    }
    drawShape(shape, paint)
}

private fun DrawScope.drawShape(shape: Shape, paint: Paint, inset: Float = 0f) {
    val outlineSize = Size(
        (size.width - inset * 2f).coerceAtLeast(1f),
        (size.height - inset * 2f).coerceAtLeast(1f),
    )
    val outline = shape.createOutline(outlineSize, layoutDirection, this)
    drawIntoCanvas { canvas ->
        if (inset != 0f) {
            canvas.save()
            canvas.translate(inset, inset)
        }
        when (outline) {
            is Outline.Rectangle -> canvas.drawRect(outline.rect, paint)
            is Outline.Rounded -> {
                val rect = outline.roundRect
                canvas.drawRoundRect(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    rect.topLeftCornerRadius.x,
                    rect.topLeftCornerRadius.y,
                    paint,
                )
            }
            is Outline.Generic -> canvas.drawPath(outline.path, paint)
        }
        if (inset != 0f) canvas.restore()
    }
}
