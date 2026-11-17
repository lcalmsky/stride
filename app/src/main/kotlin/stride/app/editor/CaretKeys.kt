package stride.app.editor

import androidx.compose.ui.input.key.Key

/** What an editor key does at every caret; [CaretKeys] produces these from a key event. */
internal sealed interface CaretKeyCommand {
    data class Move(val move: CaretMove, val extend: Boolean) : CaretKeyCommand
    data class ExtendBlock(val dLine: Int, val dCol: Int) : CaretKeyCommand
    data class Delete(val forward: Boolean, val word: Boolean) : CaretKeyCommand
    data object Paste : CaretKeyCommand
}

internal object CaretKeys {
    /**
     * The command [key] runs once the editor holds secondary carets, or `null` to leave the key
     * to the field. The window keymap runs first, so only strokes it does not map reach this.
     */
    fun commandFor(
        key: Key,
        meta: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        hasSecondary: Boolean,
        columnMode: Boolean,
    ): CaretKeyCommand? {
        if (!hasSecondary && !columnMode) return null
        // Control arrows, Home and End keep the field's own primary-only movement.
        if (ctrl) return null
        if (columnMode && shift && !alt && !meta) {
            return when (key) {
                Key.DirectionLeft -> CaretKeyCommand.ExtendBlock(0, -1)
                Key.DirectionRight -> CaretKeyCommand.ExtendBlock(0, 1)
                Key.DirectionUp -> CaretKeyCommand.ExtendBlock(-1, 0)
                Key.DirectionDown -> CaretKeyCommand.ExtendBlock(1, 0)
                else -> null
            }
        }
        if (!hasSecondary) return null
        // ⌘⌥←/→ are navigation, and ⌘↑/⌘↓ are the statement moves the keymap already owns.
        if (meta && alt) return null
        if (meta && (key == Key.DirectionUp || key == Key.DirectionDown)) return null
        if (key in UNMAPPED_KEYS) return null
        return when (key) {
            Key.DirectionLeft -> CaretKeyCommand.Move(
                when {
                    alt -> CaretMove.WORD_LEFT
                    meta -> CaretMove.LINE_START
                    else -> CaretMove.LEFT
                },
                shift,
            )
            Key.DirectionRight -> CaretKeyCommand.Move(
                when {
                    alt -> CaretMove.WORD_RIGHT
                    meta -> CaretMove.LINE_END
                    else -> CaretMove.RIGHT
                },
                shift,
            )
            Key.DirectionUp -> CaretKeyCommand.Move(CaretMove.UP, shift)
            Key.DirectionDown -> CaretKeyCommand.Move(CaretMove.DOWN, shift)
            Key.Backspace -> CaretKeyCommand.Delete(forward = false, word = alt)
            Key.Delete -> CaretKeyCommand.Delete(forward = true, word = alt)
            Key.V -> if (meta && !alt && !shift) CaretKeyCommand.Paste else null
            else -> null
        }
    }

    private val UNMAPPED_KEYS = setOf(
        Key.PageUp,
        Key.PageDown,
        Key.Home,
        Key.MoveHome,
        Key.MoveEnd,
    )
}
