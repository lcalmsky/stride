package stride.app.keys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/** One key with its held modifiers. */
data class KeyStroke(
    val key: Key,
    val meta: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    companion object {
        /** Parses a key stroke string such as `meta shift O`. */
        fun parse(text: String): KeyStroke {
            val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            require(tokens.isNotEmpty()) { "Empty key stroke" }
            var meta = false
            var ctrl = false
            var alt = false
            var shift = false
            for (token in tokens.dropLast(1)) {
                when (token.uppercase()) {
                    "META" -> meta = true
                    "CONTROL", "CTRL" -> ctrl = true
                    "ALT" -> alt = true
                    "SHIFT" -> shift = true
                    else -> throw IllegalArgumentException("Unknown modifier token: $token")
                }
            }
            return KeyStroke(keyOf(tokens.last()), meta, ctrl, alt, shift)
        }

        fun of(event: KeyEvent): KeyStroke = KeyStroke(
            event.key,
            meta = event.isMetaPressed,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
        )

        private val WHITESPACE = Regex("\\s+")

        private fun keyOf(token: String): Key = when (token.uppercase()) {
            "A" -> Key.A
            "B" -> Key.B
            "C" -> Key.C
            "D" -> Key.D
            "E" -> Key.E
            "F" -> Key.F
            "G" -> Key.G
            "H" -> Key.H
            "I" -> Key.I
            "J" -> Key.J
            "K" -> Key.K
            "L" -> Key.L
            "M" -> Key.M
            "N" -> Key.N
            "O" -> Key.O
            "P" -> Key.P
            "Q" -> Key.Q
            "R" -> Key.R
            "S" -> Key.S
            "T" -> Key.T
            "U" -> Key.U
            "V" -> Key.V
            "W" -> Key.W
            "X" -> Key.X
            "Y" -> Key.Y
            "Z" -> Key.Z
            "0" -> Key.Zero
            "1" -> Key.One
            "2" -> Key.Two
            "3" -> Key.Three
            "4" -> Key.Four
            "5" -> Key.Five
            "6" -> Key.Six
            "7" -> Key.Seven
            "8" -> Key.Eight
            "9" -> Key.Nine
            "TAB" -> Key.Tab
            "BACK_SPACE" -> Key.Backspace
            "DELETE" -> Key.Delete
            "ESCAPE" -> Key.Escape
            "ENTER" -> Key.Enter
            "UP" -> Key.DirectionUp
            "DOWN" -> Key.DirectionDown
            "LEFT" -> Key.DirectionLeft
            "RIGHT" -> Key.DirectionRight
            "HOME" -> Key.Home
            "END" -> Key.MoveEnd
            "SLASH" -> Key.Slash
            "DIVIDE" -> Key.NumPadDivide
            "MULTIPLY" -> Key.NumPadMultiply
            "OPEN_BRACKET" -> Key.LeftBracket
            "CLOSE_BRACKET" -> Key.RightBracket
            "INSERT" -> Key.Insert
            "F1" -> Key.F1
            "F2" -> Key.F2
            "F3" -> Key.F3
            "F4" -> Key.F4
            "F5" -> Key.F5
            "F6" -> Key.F6
            "F7" -> Key.F7
            "F8" -> Key.F8
            "F9" -> Key.F9
            "F10" -> Key.F10
            "F11" -> Key.F11
            "F12" -> Key.F12
            "NUMPAD0" -> Key.NumPad0
            "NUMPAD1" -> Key.NumPad1
            "NUMPAD2" -> Key.NumPad2
            "NUMPAD3" -> Key.NumPad3
            "NUMPAD4" -> Key.NumPad4
            "NUMPAD5" -> Key.NumPad5
            "NUMPAD6" -> Key.NumPad6
            "NUMPAD7" -> Key.NumPad7
            "NUMPAD8" -> Key.NumPad8
            "NUMPAD9" -> Key.NumPad9
            else -> throw IllegalArgumentException("Unknown key token: $token")
        }
    }
}
