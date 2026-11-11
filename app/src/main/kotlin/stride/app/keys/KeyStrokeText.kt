package stride.app.keys

import androidx.compose.ui.input.key.Key

/** macOS modifier glyphs, in the order macOS writes them. */
private const val CONTROL_GLYPH = "⌃"
private const val ALT_GLYPH = "⌥"
private const val SHIFT_GLYPH = "⇧"
private const val META_GLYPH = "⌘"

/** Formats [stroke] with macOS glyphs, for example `meta shift O` → `⇧⌘O`. */
fun formatKeyStroke(stroke: KeyStroke): String = buildString {
    if (stroke.ctrl) append(CONTROL_GLYPH)
    if (stroke.alt) append(ALT_GLYPH)
    if (stroke.shift) append(SHIFT_GLYPH)
    if (stroke.meta) append(META_GLYPH)
    append(keyLabel(stroke.key))
}

/** First stroke bound to [action], formatted; empty when the action has no shortcut. */
fun shortcutText(keymap: Keymap, action: ActionId): String =
    keymap.strokesFor(action).firstOrNull()?.let(::formatKeyStroke).orEmpty()

/** A short label for [key] such as `O`, `↑` or `F12`. */
fun keyLabel(key: Key): String = when (key) {
    Key.A -> "A"
    Key.B -> "B"
    Key.C -> "C"
    Key.D -> "D"
    Key.E -> "E"
    Key.F -> "F"
    Key.G -> "G"
    Key.H -> "H"
    Key.I -> "I"
    Key.J -> "J"
    Key.K -> "K"
    Key.L -> "L"
    Key.M -> "M"
    Key.N -> "N"
    Key.O -> "O"
    Key.P -> "P"
    Key.Q -> "Q"
    Key.R -> "R"
    Key.S -> "S"
    Key.T -> "T"
    Key.U -> "U"
    Key.V -> "V"
    Key.W -> "W"
    Key.X -> "X"
    Key.Y -> "Y"
    Key.Z -> "Z"
    Key.Zero -> "0"
    Key.One -> "1"
    Key.Two -> "2"
    Key.Three -> "3"
    Key.Four -> "4"
    Key.Five -> "5"
    Key.Six -> "6"
    Key.Seven -> "7"
    Key.Eight -> "8"
    Key.Nine -> "9"
    Key.Tab -> "Tab"
    Key.Backspace -> "⌫"
    Key.Delete -> "Del"
    Key.Escape -> "Esc"
    Key.Enter, Key.NumPadEnter -> "↩"
    Key.DirectionUp -> "↑"
    Key.DirectionDown -> "↓"
    Key.DirectionLeft -> "←"
    Key.DirectionRight -> "→"
    Key.Home -> "Home"
    Key.MoveEnd -> "End"
    Key.Slash -> "/"
    Key.NumPadDivide -> "/"
    Key.NumPadMultiply -> "*"
    Key.LeftBracket -> "["
    Key.RightBracket -> "]"
    Key.Insert -> "Ins"
    Key.F1 -> "F1"
    Key.F2 -> "F2"
    Key.F3 -> "F3"
    Key.F4 -> "F4"
    Key.F5 -> "F5"
    Key.F6 -> "F6"
    Key.F7 -> "F7"
    Key.F8 -> "F8"
    Key.F9 -> "F9"
    Key.F10 -> "F10"
    Key.F11 -> "F11"
    Key.F12 -> "F12"
    Key.NumPad0 -> "0"
    Key.NumPad1 -> "1"
    Key.NumPad2 -> "2"
    Key.NumPad3 -> "3"
    Key.NumPad4 -> "4"
    Key.NumPad5 -> "5"
    Key.NumPad6 -> "6"
    Key.NumPad7 -> "7"
    Key.NumPad8 -> "8"
    Key.NumPad9 -> "9"
    else -> "?"
}
