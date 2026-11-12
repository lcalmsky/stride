package stride.app.glass

import androidx.compose.ui.awt.ComposeWindow
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The v1.4.0 launch hang: while AppKit answers an accessibility or menu request it runs AWT's
 * nested run loop and waits for the event dispatch thread, and the GCD main queue is not
 * serviced there. A bridge entry that waited for the main queue deadlocked. The hook holds the
 * AppKit thread in that same mode, then every bridge entry is called from the EDT and must
 * return within two seconds.
 */
class NativeGlassDeadlockTest {
    @Test
    fun `every bridge entry returns while the AppKit thread waits on the event thread`() {
        System.setProperty("java.awt.headless", "false")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "headless AWT: deadlock regression skipped")
        assumeTrue(NativeGlass.available, "native glass library unavailable: ${NativeGlass.failure}")
        assumeTrue(NativeGlass.supported(), "macOS 26 with NSGlassEffectView required")

        val window = ComposeWindow()
        try {
            SwingUtilities.invokeAndWait {
                window.setBounds(200, 200, 640, 480)
                window.addNotify()
            }
            val handle = NativeGlass.nextHandle()
            val pieces = listOf(GlassPiece("a", 10f, 20f, 100f, 30f, 15f))
            assertTrue(NativeGlass.testHoldMainThread(), "the AppKit thread did not enter the nested run loop")

            onEventThread("supported") { assertTrue(NativeGlass.supported()) }
            onEventThread("attach") { assertTrue(NativeGlass.attach(handle, window)) }
            onEventThread("sync") { assertEquals(1, NativeGlass.sync(handle, pieces)) }
            onEventThread("setDragRects") {
                assertEquals(1, NativeGlass.dragRegions(handle, listOf(DragRect(0f, 0f, 300f, 28f))))
            }
            onEventThread("hitTestDragRegion") {
                assertTrue(NativeGlass.hitTestDragRegion(handle, 10.0, 10.0))
            }
            onEventThread("dragMonitorCount") { assertEquals(1, NativeGlass.dragMonitorCount()) }
            onEventThread("state") {
                val state = checkNotNull(NativeGlass.state(handle)) { "no state while AppKit is held" }
                assertEquals(listOf("a"), state.views.map { it.key })
            }
            onEventThread("appearance") {
                assertNotNull(NativeAppearance.current(), "the appearance query gave no answer")
            }
            onEventThread("detach") { NativeGlass.detach(handle) }
        } finally {
            NativeGlass.testReleaseMainThread()
            // ComposeWindow.dispose asserts the AWT event dispatch thread.
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun onEventThread(name: String, block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val started = System.nanoTime()
        SwingUtilities.invokeLater {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                done.countDown()
            }
        }
        val finished = done.await(2, TimeUnit.SECONDS)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(finished, "$name did not return within 2 s (${millis} ms)")
        failure?.let { throw it }
    }
}
