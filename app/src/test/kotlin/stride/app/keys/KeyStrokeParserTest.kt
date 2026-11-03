package stride.app.keys

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KeyStrokeParserTest {
    @Test
    fun `modifiers and keys parse from key strings`() {
        assertEquals(KeyStroke(Key.O, meta = true, shift = true), KeyStroke.parse("meta shift O"))
        assertEquals(KeyStroke(Key.R, ctrl = true, shift = true), KeyStroke.parse("control shift R"))
        assertEquals(KeyStroke(Key.F12, alt = true), KeyStroke.parse("alt F12"))
        assertEquals(KeyStroke(Key.Backspace, meta = true), KeyStroke.parse("meta BACK_SPACE"))
        assertEquals(KeyStroke(Key.Slash, meta = true), KeyStroke.parse("meta SLASH"))
        assertEquals(KeyStroke(Key.Tab, shift = true), KeyStroke.parse("shift TAB"))
        assertEquals(KeyStroke(Key.Tab, ctrl = true), KeyStroke.parse("ctrl TAB"))
        assertEquals(KeyStroke(Key.DirectionDown, alt = true, shift = true), KeyStroke.parse("alt shift down"))
    }

    @Test
    fun `unknown tokens are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { KeyStroke.parse("meta QUUX") }
        assertThrows(IllegalArgumentException::class.java) { KeyStroke.parse("hyper A") }
    }
}
