package stride.app.glass

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Reports this element's window-relative bounds to the native glass registry. On the Skia path
 * (tests and pre-macOS 26) it does nothing. [hole] also carves this rectangle out of the opaque
 * content surface below it, so the glass shows through a popup over the editor.
 */
fun Modifier.nativeGlass(
    key: String,
    shape: Shape,
    style: GlassStyle = GlassStyle.REGULAR,
    tint: Int? = null,
    hole: Boolean = false,
    shadow: Boolean = false,
): Modifier = composed {
    if (!LocalNativeGlass.current) return@composed this
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val registry = GlassRegistry.shared
    DisposableEffect(key) {
        onDispose {
            registry.remove(key)
            if (hole) GlassHoles.remove(key)
        }
    }
    onGloballyPositioned { coordinates ->
        val size = coordinates.size
        if (size.width <= 0 || size.height <= 0) return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        val width = bounds.width / density.density
        val height = bounds.height / density.density
        val outline = shape.createOutline(
            Size(size.width.toFloat(), size.height.toFloat()),
            layoutDirection,
            density,
        )
        val radiusPx = when (outline) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Generic -> 0f
            is Outline.Rectangle -> 0f
        }
        registry.report(
            GlassPiece(
                key = key,
                x = bounds.left / density.density,
                y = bounds.top / density.density,
                width = width,
                height = height,
                cornerRadius = effectiveCornerRadius(radiusPx / density.density, width, height),
                style = style,
                tint = tint,
                shadow = shadow,
            ),
        )
        if (hole) {
            GlassHoles.set(
                key,
                Rect(
                    bounds.left / density.density,
                    bounds.top / density.density,
                    bounds.right / density.density,
                    bounds.bottom / density.density,
                ),
            )
        }
    }
}

/**
 * Reports the empty strip of the merged title row. The native mouse monitor starts the window
 * drag from a real press on this strip; the capsules over it are subtracted as blockers.
 */
fun Modifier.windowDragRegion(key: String = "title-row"): Modifier = composed {
    if (!LocalNativeGlass.current) return@composed this
    val density = LocalDensity.current
    val registry = GlassRegistry.shared
    DisposableEffect(key) {
        onDispose { registry.removeDragRegion(key) }
    }
    onGloballyPositioned { coordinates ->
        val size = coordinates.size
        if (size.width <= 0 || size.height <= 0) return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        registry.reportDragRegion(
            key,
            DragRect(
                x = bounds.left / density.density,
                y = bounds.top / density.density,
                width = bounds.width / density.density,
                height = bounds.height / density.density,
            ),
        )
    }
}
