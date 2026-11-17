package stride.app.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CaretKeysTest {

    private fun command(
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        hasSecondary: Boolean = true,
        columnMode: Boolean = false,
    ) = CaretKeys.commandFor(key, meta, ctrl, alt, shift, hasSecondary, columnMode)

    @Test
    fun `without secondary carets every key stays with the field`() {
        assertNull(command(Key.DirectionLeft, hasSecondary = false))
        assertNull(command(Key.Backspace, hasSecondary = false))
        assertNull(command(Key.V, meta = true, hasSecondary = false))
        assertNull(command(Key.DirectionDown, shift = true, hasSecondary = false))
    }

    @Test
    fun `arrows move every caret and shift extends`() {
        assertEquals(CaretKeyCommand.Move(CaretMove.LEFT, false), command(Key.DirectionLeft))
        assertEquals(CaretKeyCommand.Move(CaretMove.RIGHT, false), command(Key.DirectionRight))
        assertEquals(CaretKeyCommand.Move(CaretMove.UP, false), command(Key.DirectionUp))
        assertEquals(CaretKeyCommand.Move(CaretMove.DOWN, false), command(Key.DirectionDown))
        assertEquals(CaretKeyCommand.Move(CaretMove.RIGHT, true), command(Key.DirectionRight, shift = true))
        assertEquals(CaretKeyCommand.Move(CaretMove.UP, true), command(Key.DirectionUp, shift = true))
    }

    @Test
    fun `option moves by word and command by line`() {
        assertEquals(CaretKeyCommand.Move(CaretMove.WORD_LEFT, false), command(Key.DirectionLeft, alt = true))
        assertEquals(CaretKeyCommand.Move(CaretMove.WORD_RIGHT, true), command(Key.DirectionRight, alt = true, shift = true))
        assertEquals(CaretKeyCommand.Move(CaretMove.LINE_START, false), command(Key.DirectionLeft, meta = true))
        assertEquals(CaretKeyCommand.Move(CaretMove.LINE_END, true), command(Key.DirectionRight, meta = true, shift = true))
    }

    @Test
    fun `control arrows, command up and down, and the paging keys are left alone`() {
        assertNull(command(Key.DirectionLeft, ctrl = true))
        assertNull(command(Key.DirectionDown, ctrl = true, shift = true))
        assertNull(command(Key.DirectionUp, meta = true))
        assertNull(command(Key.DirectionDown, meta = true, shift = true))
        assertNull(command(Key.PageUp))
        assertNull(command(Key.PageDown))
        assertNull(command(Key.MoveHome))
        assertNull(command(Key.MoveEnd))
        assertNull(command(Key.Home))
        assertNull(command(Key.DirectionLeft, meta = true, alt = true))
    }

    @Test
    fun `delete keys act at every caret and option deletes a word`() {
        assertEquals(CaretKeyCommand.Delete(forward = false, word = false), command(Key.Backspace))
        assertEquals(CaretKeyCommand.Delete(forward = false, word = true), command(Key.Backspace, alt = true))
        assertEquals(CaretKeyCommand.Delete(forward = true, word = false), command(Key.Delete))
        assertEquals(CaretKeyCommand.Delete(forward = true, word = true), command(Key.Delete, alt = true))
    }

    @Test
    fun `command v pastes at every caret`() {
        assertEquals(CaretKeyCommand.Paste, command(Key.V, meta = true))
        assertNull(command(Key.V, meta = true, shift = true))
        assertNull(command(Key.V))
    }

    @Test
    fun `column mode without secondary carets intercepts shift arrows only`() {
        assertEquals(
            CaretKeyCommand.ExtendBlock(0, -1),
            command(Key.DirectionLeft, shift = true, hasSecondary = false, columnMode = true),
        )
        assertEquals(
            CaretKeyCommand.ExtendBlock(-1, 0),
            command(Key.DirectionUp, shift = true, hasSecondary = false, columnMode = true),
        )
        assertEquals(
            CaretKeyCommand.ExtendBlock(1, 0),
            command(Key.DirectionDown, shift = true, hasSecondary = false, columnMode = true),
        )
        assertNull(command(Key.DirectionUp, hasSecondary = false, columnMode = true))
        assertNull(command(Key.Backspace, hasSecondary = false, columnMode = true))
        assertNull(command(Key.DirectionUp, shift = true, alt = true, hasSecondary = false, columnMode = true))
    }

    @Test
    fun `column mode with secondary carets still extends the block on shift arrows`() {
        assertEquals(
            CaretKeyCommand.ExtendBlock(0, 1),
            command(Key.DirectionRight, shift = true, columnMode = true),
        )
        assertEquals(
            CaretKeyCommand.Move(CaretMove.LEFT, false),
            command(Key.DirectionLeft, columnMode = true),
        )
    }

    // pointer gestures

    @Test
    fun `option shift toggles and option alone drags a block`() {
        assertEquals(CaretPointerGesture.TOGGLE, caretPointerGesture(false, false, alt = true, shift = true, columnMode = false))
        assertEquals(CaretPointerGesture.BLOCK, caretPointerGesture(false, false, alt = true, shift = false, columnMode = false))
        assertEquals(CaretPointerGesture.BLOCK, caretPointerGesture(false, false, alt = false, shift = false, columnMode = true))
        assertNull(caretPointerGesture(false, false, alt = false, shift = true, columnMode = true))
        assertNull(caretPointerGesture(true, false, alt = true, shift = true, columnMode = false))
        assertNull(caretPointerGesture(false, true, alt = true, shift = false, columnMode = false))
        assertNull(caretPointerGesture(false, false, alt = false, shift = false, columnMode = false))
    }

    // virtual columns

    @Test
    fun `virtual columns continue right of the line`() {
        assertEquals(5, virtualColumn(40f, 40f, 5, 10f))
        assertEquals(7, virtualColumn(60f, 40f, 5, 10f))
        assertEquals(6, virtualColumn(50f, 40f, 5, 10f))
        assertEquals(5, virtualColumn(20f, 40f, 5, 10f))
        assertEquals(5, virtualColumn(60f, 40f, 5, 0f))
    }

    // secondary lines

    @Test
    fun `secondary caret lines are distinct and sorted`() {
        val lines = secondaryCaretLines(
            "one\ntwo\nthree",
            listOf(TextRange(8), TextRange(0), TextRange(6)),
        )
        assertArrayEquals(intArrayOf(0, 1, 2), lines)
        assertArrayEquals(intArrayOf(), secondaryCaretLines("one", emptyList()))
    }

    @Test
    fun `the first line at or after a row skips earlier entries`() {
        val lines = intArrayOf(2, 5, 9)
        assertEquals(0, firstLineAtOrAfter(lines, 0))
        assertEquals(1, firstLineAtOrAfter(lines, 3))
        assertEquals(2, firstLineAtOrAfter(lines, 9))
        assertEquals(3, firstLineAtOrAfter(lines, 12))
    }
}
