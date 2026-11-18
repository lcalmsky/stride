package stride.app.vcs

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            assertEquals("VCS Operations is not implemented yet", state.statusMessage)
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
    fun `the stub actions report themselves in the status bar`() {
        val state = AppState()
        try {
            assertTrue(dispatchAction(ActionId.COMMIT, state) { null })
            assertEquals("Commit is not implemented yet", state.statusMessage)
            assertTrue(dispatchAction(ActionId.PUSH, state) { null })
            assertEquals("Push is not implemented yet", state.statusMessage)
            assertTrue(dispatchAction(ActionId.SHOW_DIFF, state) { null })
            assertEquals("Show Diff is not implemented yet", state.statusMessage)
            assertTrue(dispatchAction(ActionId.ROLLBACK_LINES, state) { null })
            assertEquals("Rollback Lines is not implemented yet", state.statusMessage)
            assertTrue(dispatchAction(ActionId.NEXT_CHANGE, state) { null })
            assertEquals("Next Change is not implemented yet", state.statusMessage)
            assertTrue(dispatchAction(ActionId.PREVIOUS_CHANGE, state) { null })
            assertEquals("Previous Change is not implemented yet", state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    private fun action(stroke: String, context: KeyContext): ActionId? =
        keymap.actionFor(KeyStroke.parse(stroke), context)
}
