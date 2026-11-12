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
import stride.app.ui.theme.DesignTokens

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
            GlassRuntime.attach(window)
            // The merged title bar is applied at attach time here (this test window had no
            // prepareTitleBar before the peer existed), which makes the content full-window.
            // Attach is asynchronous now, so AWT applies the style bits shortly after it returns;
            // wait for the content view to grow before measuring the piece frames.
            waitForMergedTitleBar(window)
            val contentHeight = window.height.toDouble()
            val handle = GlassRuntime.attachedHandle
            assertTrue(handle != 0L, "attach returned no handle for the unshown window")

            val tint = 0x9914151B.toInt()
            val editorTint = DesignTokens.editorGlassTint
            val pieces = listOf(
                GlassPiece("a", 10f, 20f, 100f, 30f, 15f, tint = tint),
                GlassPiece("b", 200f, 50f, 300f, 40f, 8f, style = GlassStyle.CLEAR),
                GlassPiece("popup", 100f, 120f, 400f, 200f, 18f, tint = tint, shadow = true),
                GlassPiece("editor-surface", 10f, 330f, 400f, 120f, 12f, tint = editorTint),
                GlassPiece("tool-window-surface", 420f, 330f, 200f, 120f, 12f, tint = editorTint),
            )
            assertEquals(5, NativeGlass.sync(handle, pieces))
            val state = requireNotNull(NativeGlass.state(handle)) { "no native state for the attached window" }
            System.err.println(
                "native-glass-smoke opaque=${state.opaque} windows=${state.windowCount} " +
                    "base=${state.baseView} views=${state.views.map { "${it.key}:${it.x},${it.y},${it.width},${it.height}" }}",
            )
            assertFalse(state.opaque, "the window must be non-opaque so the glass shows the desktop")
            assertTrue(state.baseView, "the full-window glass base is missing")
            assertEquals(
                listOf("a", "b", "editor-surface", "popup", "tool-window-surface"),
                state.views.map { it.key },
            )
            val byKey = state.views.associateBy { it.key }
            assertFrame(byKey.getValue("a"), x = 10.0, y = 20.0, width = 100.0, height = 30.0, container = contentHeight, radius = 15.0)
            assertFrame(byKey.getValue("b"), x = 200.0, y = 50.0, width = 300.0, height = 40.0, container = contentHeight, radius = 8.0)
            assertFrame(byKey.getValue("popup"), x = 100.0, y = 120.0, width = 400.0, height = 200.0, container = contentHeight, radius = 18.0)
            // The editor and tool-window pieces keep the regular material and the denser tint.
            for (piece in state.views.filter { it.key.startsWith("editor-surface") || it.key.startsWith("tool-window-surface") }) {
                assertEquals(0, piece.style, "${piece.key} must use the regular style")
                assertTint(editorTint, piece.tint)
                assertEquals(0.0, piece.shadowOpacity, 0.001, "${piece.key} carries no drop shadow")
            }
            // The popup's drop shadow is on its native layer; a Compose shadow would paint
            // inside the panel's transparent content.
            assertEquals(0.0, byKey.getValue("a").shadowOpacity, 0.001, "a chrome piece has no drop shadow")
            assertEquals(0.0, byKey.getValue("b").shadowOpacity, 0.001, "a clear piece has no drop shadow")
            assertTrue(byKey.getValue("popup").shadowOpacity > 0.0, "the popup piece carries a native drop shadow")
            System.err.println("native-glass-smoke shadow=${byKey.getValue("popup").shadowOpacity}")
            // The style and tint are read back from the material view: a silently swallowed KVC
            // write would show up here instead of only on screen.
            assertEquals(0, state.views[0].style, "regular style was not applied")
            assertTint(tint, state.views[0].tint)
            assertEquals(1, state.views[1].style, "clear style was not applied")
            System.err.println(
                "native-glass-smoke style/tint a=${state.views[0].style}/" +
                    "${state.views[0].tint?.toUInt()?.toString(16)} b=${state.views[1].style}/" +
                    "${state.views[1].tint?.toUInt()?.toString(16)}",
            )

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
            assertEquals(0, layer.restoredLayers, "nothing is restored while the window is attached")

            // Drag regions are top-left window points; the monitor is hit-tested through JNI.
            val dragRects = listOf(DragRect(0f, 0f, 300f, 28f), DragRect(400f, 0f, 200f, 28f))
            assertEquals(2, NativeGlass.dragRegions(handle, dragRects))
            assertTrue(NativeGlass.hitTestDragRegion(handle, 10.0, 10.0), "a point in the first strip")
            assertTrue(NativeGlass.hitTestDragRegion(handle, 450.0, 5.0), "a point in the second strip")
            assertFalse(NativeGlass.hitTestDragRegion(handle, 350.0, 10.0), "the gap between strips")
            assertFalse(NativeGlass.hitTestDragRegion(handle, 10.0, 100.0), "below the title row")
            assertEquals(1, NativeGlass.dragMonitorCount(), "one local mouse monitor per attached window")

            NativeGlass.detach(handle)
            assertEquals(0, NativeGlass.dragMonitorCount(), "detach left the mouse monitor installed")
            val after = requireNotNull(NativeGlass.state(handle))
            assertTrue(after.views.isEmpty(), "detach left ${after.views.size} glass views")
            assertFalse(after.baseView, "detach left the base glass view")
            assertTrue(after.opaque, "detach must restore the window's opaque flag")
            val restored = requireNotNull(after.layer) { "detach did not report the restored layer chain" }
            assertTrue(restored.restoredLayers > 0, "detach restored no layer flags")
            System.err.println(
                "native-glass-smoke restored windowOpaque=${after.opaque} " +
                    "layerOpaque=${restored.opaque} ancestorsClear=${restored.ancestorsClear} " +
                    "restoredLayers=${restored.restoredLayers}",
            )
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
        // The appearance exists only after AWT installed its application object; the contract
        // this test documents is that nothing creates one earlier.
        java.awt.Toolkit.getDefaultToolkit()
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

    private fun waitForMergedTitleBar(window: ComposeWindow) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            var merged = false
            SwingUtilities.invokeAndWait { merged = window.insets.top == 0 }
            if (merged) return
            Thread.sleep(10)
        }
    }

    private fun assertFrame(
        view: NativeGlassView,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        container: Double,
        radius: Double,
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
        assertTrue(abs(view.cornerRadius - radius) <= 0.51, "${view.key} corner radius")
    }

    private fun assertTint(expected: Int, actual: Int?) {
        val value = requireNotNull(actual) { "the tint colour was not read back from the material view" }
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val expectedChannel = (expected ushr shift) and 0xFF
            val actualChannel = (value ushr shift) and 0xFF
            assertTrue(
                abs(expectedChannel - actualChannel) <= 2,
                "tint channel ${shift / 8} expected $expectedChannel was $actualChannel " +
                    "(${value.toUInt().toString(16)})",
            )
        }
    }
}
