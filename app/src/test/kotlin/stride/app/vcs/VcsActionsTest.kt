package stride.app.vcs

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.KeyStroke
import stride.app.keys.Keymap
import stride.app.keys.MacKeymap
import stride.app.keys.dispatchAction

class VcsActionsTest {

    private val keymap = MacKeymap.keymap

    @Test
    fun `the vcs strokes resolve in their contexts`() {
        assertEquals(ActionId.COMMIT, action("meta K", KeyContext.GLOBAL))
        assertEquals(ActionId.PUSH, action("meta shift K", KeyContext.GLOBAL))
        assertEquals(ActionId.VCS_POPUP, action("control V", KeyContext.GLOBAL))
        assertEquals(ActionId.VCS_POPUP, action("control V", KeyContext.TERMINAL))
        assertEquals(ActionId.ACTIVATE_GIT_TOOL_WINDOW, action("meta 9", KeyContext.GLOBAL))
        assertEquals(ActionId.ACTIVATE_COMMIT_TOOL_WINDOW, action("meta 0", KeyContext.GLOBAL))
        assertEquals(ActionId.ROLLBACK_LINES, action("meta alt Z", KeyContext.EDITOR))
        assertEquals(ActionId.NEXT_CHANGE, action("control alt shift DOWN", KeyContext.EDITOR))
        assertEquals(ActionId.PREVIOUS_CHANGE, action("control alt shift UP", KeyContext.EDITOR))
        assertEquals(ActionId.SHOW_DIFF, action("meta D", KeyContext.COMMIT_WINDOW))
        assertEquals(ActionId.EDITOR_DUPLICATE, action("meta D", KeyContext.EDITOR))
        assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action("shift ESCAPE", KeyContext.COMMIT_WINDOW))
        assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action("shift ESCAPE", KeyContext.LIST_WINDOW))
    }

    @Test
    fun `no vcs stroke already had a binding in its context`() {
        val expected = mapOf(
            KeyStroke.parse("meta K") to KeyContext.GLOBAL,
            KeyStroke.parse("meta shift K") to KeyContext.GLOBAL,
            KeyStroke.parse("control V") to KeyContext.GLOBAL,
            KeyStroke.parse("meta 9") to KeyContext.GLOBAL,
            KeyStroke.parse("meta 0") to KeyContext.GLOBAL,
            KeyStroke.parse("meta alt Z") to KeyContext.EDITOR,
            KeyStroke.parse("control alt shift DOWN") to KeyContext.EDITOR,
            KeyStroke.parse("control alt shift UP") to KeyContext.EDITOR,
            KeyStroke.parse("meta D") to KeyContext.COMMIT_WINDOW,
        )
        for ((stroke, context) in expected) {
            val inContext = keymap.bindings[stroke].orEmpty().filter { it.context == context }
            assertEquals(1, inContext.size, "$stroke is bound ${inContext.size} times in $context")
        }
        assertEquals(2, keymap.bindings[KeyStroke.parse("meta D")].orEmpty().size)
    }

    @Test
    fun `the vcs popup declines its stroke in the terminal through the router`() {
        val state = AppState()
        try {
            val router = KeyRouter(Keymap.forPlatform(true))
            state.focus = KeyContext.TERMINAL

            val inTerminal = router.route(KeyEventType.DOWN, Key.V, ctrl = true, focus = KeyContext.TERMINAL)
            assertEquals(ActionId.VCS_POPUP, inTerminal)
            assertFalse(dispatchAction(inTerminal!!, state) { null })
            assertNull(state.statusMessage)

            state.focus = KeyContext.EDITOR
            val inEditor = router.route(KeyEventType.DOWN, Key.V, ctrl = true, focus = KeyContext.EDITOR)
            assertTrue(dispatchAction(inEditor!!, state) { null })
            assertNotNull(state.vcsPopup)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `the git and commit tool window actions toggle like the other windows`() {
        val state = AppState()
        try {
            state.focus = KeyContext.GLOBAL
            assertTrue(dispatchAction(ActionId.ACTIVATE_GIT_TOOL_WINDOW, state) { null })
            assertTrue(state.toolWindows.visible)
            assertEquals("git", state.toolWindows.activeId)
            assertEquals(KeyContext.LIST_WINDOW, state.toolWindows.windows.single { it.id == "git" }.context)

            state.focus = KeyContext.LIST_WINDOW
            assertTrue(dispatchAction(ActionId.ACTIVATE_GIT_TOOL_WINDOW, state) { null })
            assertFalse(state.toolWindows.visible)

            state.focus = KeyContext.GLOBAL
            assertTrue(dispatchAction(ActionId.ACTIVATE_COMMIT_TOOL_WINDOW, state) { null })
            assertTrue(state.toolWindows.visible)
            assertEquals("commit", state.toolWindows.activeId)
            assertEquals(KeyContext.COMMIT_WINDOW, state.toolWindows.windows.single { it.id == "commit" }.context)

            state.focus = KeyContext.COMMIT_WINDOW
            assertTrue(dispatchAction(ActionId.ACTIVATE_COMMIT_TOOL_WINDOW, state) { null })
            assertFalse(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `shift escape hides the commit window through the router before the window sees escape`() {
        val state = AppState()
        try {
            val router = KeyRouter(Keymap.forPlatform(true))
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            assertTrue(state.toolWindows.visible)
            state.focus = KeyContext.COMMIT_WINDOW

            val action = router.route(KeyEventType.DOWN, Key.Escape, shift = true, focus = KeyContext.COMMIT_WINDOW)
            assertEquals(ActionId.HIDE_ACTIVE_WINDOW, action)
            assertTrue(dispatchAction(action!!, state) { null })
            assertFalse(state.toolWindows.visible)

            assertNull(router.route(KeyEventType.DOWN, Key.Escape, focus = KeyContext.COMMIT_WINDOW))
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `the change actions need an editor`() {
        val state = AppState()
        try {
            assertFalse(dispatchAction(ActionId.ROLLBACK_LINES, state) { null })
            assertNull(state.statusMessage)
            assertFalse(dispatchAction(ActionId.NEXT_CHANGE, state) { null })
            assertNull(state.statusMessage)
            assertFalse(dispatchAction(ActionId.PREVIOUS_CHANGE, state) { null })
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `the commit, push and show diff actions need a project`() {
        val state = AppState()
        try {
            assertFalse(dispatchAction(ActionId.COMMIT, state) { null })
            assertFalse(dispatchAction(ActionId.PUSH, state) { null })
            assertFalse(dispatchAction(ActionId.SHOW_DIFF, state) { null })
            assertNull(state.statusMessage)
            assertFalse(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)
}
