package stride.app.keys

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KeyRouterTest {
    private var now = 0L

    private fun router(isMac: Boolean = true) = KeyRouter(isMac, clock = { now })

    @Test
    fun `double shift opens search everywhere on the second release`() {
        val router = router()

        assertNull(router.route(KeyEventType.DOWN, Key.ShiftLeft))
        now = 100
        assertNull(router.route(KeyEventType.UP, Key.ShiftLeft))
        now = 200
        assertNull(router.route(KeyEventType.DOWN, Key.ShiftRight))
        now = 250
        assertEquals(ActionId.SEARCH_EVERYWHERE, router.route(KeyEventType.UP, Key.ShiftRight))
    }

    @Test
    fun `another key between shifts cancels the sequence`() {
        val router = router()

        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        now = 100
        router.route(KeyEventType.UP, Key.ShiftLeft)
        now = 150
        router.route(KeyEventType.DOWN, Key.A)
        now = 160
        router.route(KeyEventType.UP, Key.A)
        now = 200
        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        now = 250
        assertNull(router.route(KeyEventType.UP, Key.ShiftLeft))
    }

    @Test
    fun `repeated shift down events keep the sequence`() {
        val router = router()

        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        now = 100
        router.route(KeyEventType.UP, Key.ShiftLeft)
        now = 150
        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        router.route(KeyEventType.DOWN, Key.ShiftLeft)
        now = 200
        assertEquals(ActionId.SEARCH_EVERYWHERE, router.route(KeyEventType.UP, Key.ShiftLeft))
    }

    @Test
    fun `shortcuts follow the platform modifier`() {
        val mac = router(isMac = true)
        assertEquals(ActionId.OPEN_FOLDER, mac.route(KeyEventType.DOWN, Key.O, meta = true))
        assertEquals(ActionId.SAVE, mac.route(KeyEventType.DOWN, Key.S, meta = true))
        assertEquals(ActionId.CLOSE_TAB, mac.route(KeyEventType.DOWN, Key.W, meta = true))
        assertNull(mac.route(KeyEventType.DOWN, Key.O, ctrl = true))

        val other = router(isMac = false)
        assertEquals(ActionId.OPEN_FOLDER, other.route(KeyEventType.DOWN, Key.O, ctrl = true))
        assertEquals(ActionId.SAVE, other.route(KeyEventType.DOWN, Key.S, ctrl = true))
        assertEquals(ActionId.CLOSE_TAB, other.route(KeyEventType.DOWN, Key.W, ctrl = true))
        assertNull(other.route(KeyEventType.DOWN, Key.O, meta = true))
    }

    @Test
    fun `double control does not open search everywhere`() {
        val router = router()

        router.route(KeyEventType.DOWN, Key.CtrlLeft)
        now = 100
        router.route(KeyEventType.UP, Key.CtrlLeft)
        now = 150
        router.route(KeyEventType.DOWN, Key.CtrlLeft)
        now = 200
        assertNull(router.route(KeyEventType.UP, Key.CtrlLeft))
    }
}
