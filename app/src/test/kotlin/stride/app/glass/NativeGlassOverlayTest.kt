package stride.app.glass

import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens

/**
 * The stacking and appearance contract of the native glass, on a window that is never ordered
 * front: the registry's list order is the container's subview order, so an overlay (a popup)
 * sits above every surface it covers even when it was reported before them, and the window's
 * appearance follows the palette.
 */
class NativeGlassOverlayTest {
    @Test
    fun `overlays stack above the surfaces and the window follows the palette`() {
        System.setProperty("java.awt.headless", "false")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "headless AWT: native glass overlay test skipped")
        assumeTrue(NativeGlass.available, "native glass library unavailable: ${NativeGlass.failure}")
        assumeTrue(NativeGlass.supported(), "macOS 26 with NSGlassEffectView required")
        AppearanceState.set(Appearance.DARK)

        val window = try {
            ComposeWindow()
        } catch (error: HeadlessException) {
            assumeTrue(false, "headless AWT: ${error.message}")
            return
        }
        GlassRuntime.resetForTests()
        try {
            window.setBounds(200, 200, 640, 480)
            SwingUtilities.invokeAndWait {
                window.add(ComposePanel().apply { setContent { } })
                GlassRuntime.prepare(window)
                window.addNotify()
            }
            GlassRuntime.attach(window)
            waitForMergedTitleBar(window)
            val handle = GlassRuntime.attachedHandle
            assertTrue(handle != 0L, "attach returned no handle for the unshown window")
            val tint = DesignTokens.nativeGlassTint

            fun piece(key: String, x: Float, overlay: Boolean = false, shadow: Boolean = false) =
                GlassPiece(key, x, 20f, 100f, 30f, 8f, tint = tint, shadow = shadow, overlay = overlay)

            // The registry sends the surfaces first and the overlay last; the native side stacks
            // in exactly that list order.
            NativeGlass.sync(
                handle,
                listOf(
                    piece("editor-surface", 10f),
                    piece("sidebar", 200f),
                    piece("popup", 400f, overlay = true, shadow = true),
                ),
            )
            val layered = state(handle) { it.views.size == 3 }
            val byKey = layered.views.associateBy { it.key }
            assertTrue(
                byKey.getValue("editor-surface").zIndex < byKey.getValue("sidebar").zIndex,
                "the surfaces keep the report order: ${byKey.mapValues { it.value.zIndex }}",
            )
            assertTrue(
                byKey.getValue("sidebar").zIndex < byKey.getValue("popup").zIndex,
                "the popup must sit above the sidebar: ${byKey.mapValues { it.value.zIndex }}",
            )
            assertTrue(
                byKey.getValue("editor-surface").zIndex < byKey.getValue("popup").zIndex,
                "the popup must sit above the editor surface: ${byKey.mapValues { it.value.zIndex }}",
            )
            assertTrue(byKey.getValue("popup").shadowBelow, "the popup's shadow must sit below its own view")

            // A surface that appears later, in the middle of the list, still stacks below the
            // popup: a new piece is not dropped in directly above the base view.
            NativeGlass.sync(
                handle,
                listOf(
                    piece("editor-surface", 10f),
                    piece("sidebar", 200f),
                    piece("find-bar", 300f),
                    piece("popup", 400f, overlay = true, shadow = true),
                ),
            )
            val rerun = state(handle) { it.views.size == 4 }.views.associateBy { it.key }
            val highest = rerun.getValue("popup").zIndex
            for (key in listOf("editor-surface", "sidebar", "find-bar")) {
                assertTrue(
                    rerun.getValue(key).zIndex < highest,
                    "$key must stay below the popup: ${rerun.mapValues { it.value.zIndex }}",
                )
            }
            assertTrue(
                rerun.getValue("sidebar").zIndex < rerun.getValue("find-bar").zIndex,
                "the two surfaces keep their report order: ${rerun.mapValues { it.value.zIndex }}",
            )

            NativeGlass.setAppearance(handle, Appearance.DARK)
            val dark = state(handle) {
                it.layer?.windowAppearance == 2 && it.layer?.glassAppearance == 2
            }
            System.err.println(
                "native-glass-overlay dark window=${dark.layer?.windowAppearance} " +
                    "glass=${dark.layer?.glassAppearance}",
            )
            assertTrue(dark.layer?.windowAppearance == 2, "the window did not take the dark appearance")
            assertTrue(dark.layer?.glassAppearance == 2, "the glass base did not take the dark appearance")

            NativeGlass.setAppearance(handle, Appearance.LIGHT)
            val light = state(handle) {
                it.layer?.windowAppearance == 1 && it.layer?.glassAppearance == 1
            }
            System.err.println(
                "native-glass-overlay light window=${light.layer?.windowAppearance} " +
                    "glass=${light.layer?.glassAppearance}",
            )
            assertTrue(light.layer?.windowAppearance == 1, "the window did not take the light appearance")
            assertTrue(light.layer?.glassAppearance == 1, "the glass base did not take the light appearance")
        } finally {
            GlassRuntime.detach()
            AppearanceState.set(Appearance.DARK)
            // ComposeWindow.dispose asserts the AWT event dispatch thread.
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    /** The sync and the appearance change are queued; poll the reported state until it settles. */
    private fun state(handle: Long, predicate: (NativeGlassState) -> Boolean): NativeGlassState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var last: NativeGlassState? = null
        while (System.nanoTime() < deadline) {
            last = NativeGlass.state(handle)
            if (last != null && predicate(last)) return last
            Thread.sleep(20)
        }
        return requireNotNull(last) { "no native state for the attached window" }
    }

    private fun waitForMergedTitleBar(window: ComposeWindow) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            var merged = false
            SwingUtilities.invokeAndWait { merged = window.insets.top == 0 }
            if (merged) return
            Thread.sleep(10)
        }
    }
}
