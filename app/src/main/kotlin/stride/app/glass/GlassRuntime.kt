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
import javax.swing.RootPaneContainer
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
    private var attempts = 0
    private var failed = false
    private var titleBarApplied = false

    /** Compose observes this to switch the Skia glass pass off. */
    val active = mutableStateOf(false)

    /** The native handle of the attached window; tests read it to query the native state. */
    internal val attachedHandle: Long get() = handle

    /** Whether the native path exists at all: the library loaded and NSGlassEffectView is there. */
    internal val nativeEligible: Boolean get() = NativeGlass.supported()

    /**
     * Applies the merged title-bar client properties before the peer is created. They are only
     * touched when [nativeEligible]; a later failed attach reverts them at runtime.
     */
    fun prepareTitleBar(window: Window) {
        if (chromePlan(eligible = nativeEligible, attached = false, failed = false).titleBarProperties) {
            setTitleBar(window, merged = true)
        }
    }

    fun prepare(window: Window) {
        if (prepared) return
        prepared = true
        if (!nativeEligible) {
            val reason = NativeGlass.failure ?: "NSGlassEffectView unavailable"
            log.info("native glass: off ($reason), Skia glass unchanged")
            failed = true
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
            // The Skia layer's insertion is the retry signal when the first attach ran too early.
            if (handle == 0L) attach(window)
        }
    }

    fun attach(window: Window) {
        if (handle != 0L) return
        if (!nativeEligible) {
            fail(window, NativeGlass.failure ?: "NSGlassEffectView unavailable")
            return
        }
        if (transparentLayer == null) {
            retry(window, "Compose layer not found")
            return
        }
        val attached = NativeGlass.attach(window)
        if (attached == 0L) {
            retry(window, "window not found")
            return
        }
        handle = attached
        attempts = 0
        failed = false
        val plan = chromePlan(eligible = true, attached = true, failed = false)
        if (plan.titleBarProperties) setTitleBar(window, merged = true)
        GlassRegistry.shared.attach(attached)
        active.value = plan.mergedTitleRow
        log.info("native glass: on")
    }

    fun detach() {
        attempts = 0
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
        attempts = 0
        failed = false
        titleBarApplied = false
    }

    private fun retry(window: Window, reason: String) {
        attempts++
        if (attempts < MAX_ATTACH_ATTEMPTS) return
        fail(window, reason)
    }

    private fun fail(window: Window, reason: String) {
        if (!failed) {
            failed = true
            log.info("native glass: off ($reason), Skia glass unchanged")
        }
        if (!chromePlan(eligible = nativeEligible, attached = false, failed = true).titleBarProperties) {
            setTitleBar(window, merged = false)
        }
        active.value = false
        attempts = 0
    }

    private fun setTitleBar(window: Window, merged: Boolean) {
        if (titleBarApplied == merged) return
        val root = (window as? RootPaneContainer)?.rootPane ?: return
        root.putClientProperty("apple.awt.fullWindowContent", merged)
        root.putClientProperty("apple.awt.transparentTitleBar", merged)
        root.putClientProperty("apple.awt.windowTitleVisible", !merged)
        titleBarApplied = merged
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

    private const val MAX_ATTACH_ATTEMPTS = 32
}
