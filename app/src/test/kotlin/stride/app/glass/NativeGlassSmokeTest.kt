package stride.app.glass

import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the real JNI library against a window that is never ordered front: no setVisible
 * call, only addNotify so the AWT peer (and its NSWindow) exists.
 */
class NativeGlassSmokeTest {
    @Test
    fun `attach sync and detach on an unshown window`() {
        System.setProperty("java.awt.headless", "false")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "headless AWT: native glass smoke test skipped")
        assumeTrue(NativeGlass.available, "native glass library unavailable: ${NativeGlass.failure}")
        assumeTrue(NativeGlass.supported(), "macOS 26 with NSGlassEffectView required")

        val frame = try {
            Frame("stride-native-glass-smoke")
        } catch (error: HeadlessException) {
            assumeTrue(false, "headless AWT: ${error.message}")
            return
        }
        try {
            frame.setBounds(200, 200, 640, 480)
            frame.add(Canvas())
            frame.addNotify()
            val insets = frame.insets
            val contentHeight = (frame.height - insets.top - insets.bottom).toDouble()
            val handle = NativeGlass.attach(frame)
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

            NativeGlass.detach(handle)
            val after = requireNotNull(NativeGlass.state(handle))
            assertTrue(after.views.isEmpty(), "detach left ${after.views.size} glass views")
            assertFalse(after.baseView, "detach left the base glass view")
        } finally {
            frame.dispose()
        }
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
