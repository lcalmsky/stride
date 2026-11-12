package stride.app.glass

import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import javax.swing.SwingUtilities
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import stride.app.ui.theme.Appearance

/**
 * Exercises the real JNI library against a window that is never ordered front: no setVisible
 * call, only addNotify so the AWT peer (and its NSWindow) exists. The window hosts a real
 * SkiaLayer, so the transparency assertions cover the actual Compose drawing layer.
 */
class NativeGlassSmokeTest {
    @Test
    fun `attach sync and detach on an unshown window`() {
        System.setProperty("java.awt.headless", "false")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "headless AWT: native glass smoke test skipped")
        assumeTrue(NativeGlass.available, "native glass library unavailable: ${NativeGlass.failure}")
        assumeTrue(NativeGlass.supported(), "macOS 26 with NSGlassEffectView required")

        val window = try {
            ComposeWindow()
        } catch (error: HeadlessException) {
            assumeTrue(false, "headless AWT: ${error.message}")
            return
        }
        GlassRuntime.resetForTests()
        try {
            window.setBounds(200, 200, 640, 480)
            // ComposePanel may only be created on the AWT event dispatch thread.
            SwingUtilities.invokeAndWait {
                window.add(
                    ComposePanel().apply {
                        setContent { }
                    },
                )
                // Before the peer exists, exactly as Main does: the Skia layer must be created
                // transparent from its first frame.
                GlassRuntime.prepare(window)
                window.addNotify()
            }
            val insets = window.insets
            val contentHeight = (window.height - insets.top - insets.bottom).toDouble()
            GlassRuntime.attach(window)
            val handle = GlassRuntime.attachedHandle
            assertTrue(handle != 0L, "attach returned no handle for the unshown window")

            val pieces = listOf(
                GlassPiece("a", 10f, 20f, 100f, 30f, 15f),
                GlassPiece("b", 200f, 50f, 300f, 40f, 8f, style = GlassStyle.CLEAR),
            )
            assertEquals(2, NativeGlass.sync(handle, pieces))
            val state = requireNotNull(NativeGlass.state(handle)) { "no native state for the attached window" }
            System.err.println(
                "native-glass-smoke opaque=${state.opaque} windows=${state.windowCount} " +
                    "base=${state.baseView} views=${state.views.map { "${it.key}:${it.x},${it.y},${it.width},${it.height}" }}",
            )
            assertFalse(state.opaque, "the window must be non-opaque so the glass shows the desktop")
            assertTrue(state.baseView, "the full-window glass base is missing")
            assertEquals(listOf("a", "b"), state.views.map { it.key })
            assertFrame(state.views[0], x = 10.0, y = 20.0, width = 100.0, height = 30.0, container = contentHeight)
            assertFrame(state.views[1], x = 200.0, y = 50.0, width = 300.0, height = 40.0, container = contentHeight)

            System.err.println("native-glass-smoke layers:\n${NativeGlass.layerTree(handle)}")
            val layer = requireNotNull(state.layer) { "native state has no transparency block" }
            System.err.println(
                "native-glass-smoke layer class=${layer.className} opaque=${layer.opaque} " +
                    "backgroundAlpha=${layer.backgroundAlpha} ancestorsClear=${layer.ancestorsClear}/" +
                    "${layer.ancestorCount} windowBackgroundAlpha=${layer.windowBackgroundAlpha} " +
                    "containerBelowContent=${layer.containerBelowContent} " +
                    "contentViewOpaque=${layer.contentViewOpaque} containerOpaque=${layer.containerOpaque}",
            )
            assertTrue(layer.drawingLayer, "no Skia drawing layer under the content view")
            assertFalse(layer.opaque, "the Skia layer must be non-opaque (${layer.className})")
            assertEquals(0.0, layer.backgroundAlpha, 0.001, "the Skia layer background must be clear")
            assertTrue(
                layer.ancestorsClear,
                "a layer or view between the Skia layer and the window is opaque " +
                    "(${layer.ancestorCount} ancestors)",
            )
            assertEquals(0.0, layer.windowBackgroundAlpha, 0.001, "the window background must be clear")
            assertTrue(layer.containerBelowContent, "the glass container must sit below the content view")
            assertFalse(layer.contentViewOpaque, "the content view must be non-opaque")
            assertFalse(layer.containerOpaque, "the glass container must be non-opaque")

            NativeGlass.detach(handle)
            val after = requireNotNull(NativeGlass.state(handle))
            assertTrue(after.views.isEmpty(), "detach left ${after.views.size} glass views")
            assertFalse(after.baseView, "detach left the base glass view")
        } finally {
            GlassRuntime.detach()
            // ComposeWindow.dispose asserts the AWT event dispatch thread.
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    @Test
    fun `native appearance query and observer registration`() {
        assumeTrue(
            NativeAppearance.available,
            "native library unavailable (${NativeGlass.failure}): appearance query skipped",
        )
        val current = NativeAppearance.current()
        assertTrue(
            current == Appearance.DARK || current == Appearance.LIGHT,
            "effective appearance should be light or dark, was $current",
        )
        val registered = NativeAppearance.start { }
        System.err.println("native-appearance current=$current observer=$registered")
        assertTrue(registered, "the distributed theme notification observer did not register")
        NativeAppearance.stop()
    }

    private fun assertFrame(
        view: NativeGlassView,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        container: Double,
    ) {
        // Compose reports top-left points; the native frame origin is bottom-left.
        val nativeY = container - y - height
        System.err.println(
            "native-glass-smoke frame ${view.key} expected=($x,$nativeY,$width,$height) " +
                "actual=(${view.x},${view.y},${view.width},${view.height})",
        )
        assertEquals(x, view.x, 1.0, "${view.key} x")
        assertEquals(nativeY, view.y, 1.5, "${view.key} y")
        assertEquals(width, view.width, 1.0, "${view.key} width")
        assertEquals(height, view.height, 1.0, "${view.key} height")
        assertTrue(abs(view.cornerRadius - if (view.key == "a") 15.0 else 8.0) <= 0.51, "${view.key} corner radius")
    }
}
