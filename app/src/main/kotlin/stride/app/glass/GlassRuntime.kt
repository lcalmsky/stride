package stride.app.glass

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.util.logging.Logger
import javax.swing.JComponent
import org.jetbrains.skiko.SkiaLayer

/** Window-relative dp rectangles of panels whose native glass must show through opaque content. */
object GlassHoles {
    private val holes = mutableStateMapOf<String, Rect>()

    fun set(key: String, rect: Rect) {
        if (holes[key] != rect) holes[key] = rect
    }

    fun remove(key: String) {
        holes.remove(key)
    }

    /** Read during composition so a popup opening recomposes the surfaces under it. */
    fun snapshot(): List<Rect> = holes.values.toList()
}

/**
 * Process-wide native glass state. [prepare] runs before the window is shown so the Skia
 * layer is created transparent; [attach] runs once the AWT peer (and its NSWindow) exists.
 */
object GlassRuntime {
    private val log: Logger = Logger.getLogger("stride.app")
    private var handle = 0L
    private var prepared = false
    private var transparentLayer: SkiaLayer? = null

    /** Compose observes this to switch the Skia glass pass off. */
    val active = mutableStateOf(false)

    /** The native handle of the attached window; tests read it to query the native state. */
    internal val attachedHandle: Long get() = handle

    fun prepare(window: Window) {
        if (prepared) return
        prepared = true
        if (!NativeGlass.supported()) {
            val reason = NativeGlass.failure ?: "NSGlassEffectView unavailable"
            log.info("native glass: off ($reason), Skia glass unchanged")
            return
        }
        // The Compose panel builds its SkiaLayer when the window's peer is created, which is
        // after this call. The layer must be transparent before its redrawer is created, so a
        // hierarchy listener catches it at insertion time instead of after the first frame.
        applyComposeTransparency(window, window)
        window.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.HIERARCHY_CHANGED.toLong() == 0L) return@addHierarchyListener
            val container = event.component as? Container ?: window
            applyComposeTransparency(container, window)
        }
    }

    fun attach(window: Window) {
        if (handle != 0L || !NativeGlass.supported()) return
        if (transparentLayer == null) {
            log.info("native glass: off (Compose layer not found), Skia glass unchanged")
            return
        }
        val attached = NativeGlass.attach(window)
        if (attached == 0L) {
            log.info("native glass: off (window not found), Skia glass unchanged")
            return
        }
        handle = attached
        GlassRegistry.shared.attach(attached)
        active.value = true
        log.info("native glass: on")
    }

    fun detach() {
        if (handle == 0L) return
        GlassRegistry.shared.detach()
        NativeGlass.detach(handle)
        handle = 0L
        active.value = false
    }

    internal fun resetForTests() {
        detach()
        transparentLayer = null
        prepared = false
    }

    fun beginDrag() {
        NativeGlass.beginDrag(handle)
    }

    fun zoom() {
        NativeGlass.zoom(handle)
    }

    private fun applyComposeTransparency(container: Container, window: Window): Boolean {
        val layer = findSkiaLayer(container) ?: return false
        if (!layer.transparency) layer.transparency = true
        // The transparent layer must not sit on an opaque Swing parent that paints its
        // background over it.
        var parent: Component? = layer
        while (parent != null && parent !== window) {
            val component = parent
            if (component is JComponent) {
                component.isOpaque = false
                component.background = Color(0, 0, 0, 0)
            }
            parent = component.parent
        }
        transparentLayer = layer
        return true
    }

    private fun findSkiaLayer(container: Container): SkiaLayer? {
        if (container is SkiaLayer) return container
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) findSkiaLayer(child)?.let { return it }
        }
        return null
    }
}
