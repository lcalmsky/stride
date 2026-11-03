package stride.app.ui.toolwindow

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.keys.KeyContext

class ToolWindowManagerTest {
    private fun manager(): ToolWindowManager = ToolWindowManager().apply {
        register(ToolWindow("run", "Run", KeyContext.RUN_WINDOW) { })
        register(ToolWindow("terminal", "Terminal", KeyContext.TERMINAL) { })
    }

    @Test
    fun `toggle opens a hidden window and asks for focus`() {
        val manager = manager()

        manager.toggle("run", focused = false)

        assertTrue(manager.visible)
        assertEquals("run", manager.activeId)
        assertEquals(1, manager.focusRequests)
    }

    @Test
    fun `toggle hides the active window when it is focused`() {
        val manager = manager()
        manager.activate("run")

        manager.toggle("run", focused = true)

        assertFalse(manager.visible)
    }

    @Test
    fun `toggle only asks for focus when the window is active but unfocused`() {
        val manager = manager()
        manager.activate("run")

        manager.toggle("run", focused = false)

        assertTrue(manager.visible)
        assertEquals("run", manager.activeId)
        assertEquals(2, manager.focusRequests)
    }

    @Test
    fun `hide leaves the active id in place`() {
        val manager = manager()
        manager.activate("terminal")

        manager.hide()

        assertFalse(manager.visible)
        assertEquals("terminal", manager.activeId)
    }

    @Test
    fun `activate switches the active tab`() {
        val manager = manager()
        manager.activate("run")

        manager.activate("terminal")

        assertTrue(manager.visible)
        assertEquals("terminal", manager.activeId)
        assertEquals("Terminal", manager.active()?.title)
        assertEquals(KeyContext.TERMINAL, manager.active()?.context)
    }

    @Test
    fun `registering the same id replaces the window`() {
        val manager = manager()

        manager.register(ToolWindow("run", "Run again", KeyContext.RUN_WINDOW) { })

        assertEquals(listOf("run", "terminal"), manager.windows.map { it.id })
        assertEquals("Run again", manager.windows.first().title)
    }

    @Test
    fun `height defaults to 240 and clamps to 120`() {
        val manager = manager()

        assertEquals(240.dp, manager.height)

        manager.height = 60.dp

        assertEquals(120.dp, manager.height)
    }

    @Test
    fun `active is null before anything is activated`() {
        assertNull(manager().active())
    }
}
