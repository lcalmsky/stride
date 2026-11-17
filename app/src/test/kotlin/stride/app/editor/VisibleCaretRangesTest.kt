package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisibleCaretRangesTest {
    // "aaa\nbbb\nccc": line starts at 0, 4 and 8; line ends before the newline.
    private val lineOf: (Int) -> Int = { offset -> (offset / 4).coerceAtMost(2) }
    private val lineStart: (Int) -> Int = { line -> line * 4 }
    private val lineEnd: (Int) -> Int = { line -> line * 4 + 3 }

    private fun visible(carets: List<TextRange>, first: Int, last: Int): List<VisibleCaret> =
        visibleCaretRanges(carets, lineOf, lineStart, lineEnd, first, last)

    @Test
    fun `carets outside the visible lines are dropped`() {
        val carets = listOf(TextRange(0), TextRange(8, 11), TextRange(9))

        assertEquals(listOf(VisibleCaret(selection = null, bar = 0)), visible(carets, first = 0, last = 0))
    }

    @Test
    fun `a selection spanning the visible lines is clipped to them`() {
        val carets = listOf(TextRange(2, 9))

        assertEquals(
            listOf(VisibleCaret(selection = TextRange(4, 7), bar = null)),
            visible(carets, first = 1, last = 1),
        )
    }

    @Test
    fun `a collapsed caret contributes only its bar`() {
        val carets = listOf(TextRange(5))

        assertEquals(listOf(VisibleCaret(selection = null, bar = 5)), visible(carets, first = 1, last = 1))
    }

    @Test
    fun `a selection reaching the visible lines keeps both the clipped range and the bar`() {
        val carets = listOf(TextRange(1, 5))

        assertEquals(
            listOf(VisibleCaret(selection = TextRange(1, 5), bar = 5)),
            visible(carets, first = 0, last = 1),
        )
    }
}
