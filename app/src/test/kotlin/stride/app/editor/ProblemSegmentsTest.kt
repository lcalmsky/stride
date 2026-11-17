package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProblemSegmentsTest {
    // "aaa\nbbb\nccc": line starts at 0, 4 and 8; line ends before the newline.
    private val lineOf: (Int) -> Int = { offset -> (offset / 4).coerceAtMost(2) }
    private val lineStart: (Int) -> Int = { line -> line * 4 }
    private val lineEnd: (Int) -> Int = { line -> line * 4 + 3 }

    private fun segments(from: Int, to: Int, first: Int = 0, last: Int = 2, limit: Int = 11): List<ProblemSegment> =
        problemSegments(from, to, limit, first, last, lineOf, lineStart, lineEnd)

    @Test
    fun `an empty range at a line end keeps one piece on the range's line`() {
        assertEquals(listOf(ProblemSegment(0, 3, 3)), segments(from = 3, to = 3))
    }

    @Test
    fun `an empty range on an empty line keeps one piece`() {
        val emptyLineOf: (Int) -> Int = { offset -> if (offset < 4) 0 else 1 }
        val emptyLineStart: (Int) -> Int = { line -> if (line == 0) 0 else 4 }
        val emptyLineEnd: (Int) -> Int = { line -> if (line == 0) 3 else 4 }

        assertEquals(
            listOf(ProblemSegment(1, 4, 4)),
            problemSegments(4, 4, 5, 0, 1, emptyLineOf, emptyLineStart, emptyLineEnd),
        )
    }

    @Test
    fun `a range over two lines keeps two pieces`() {
        assertEquals(listOf(ProblemSegment(0, 2, 3), ProblemSegment(1, 4, 6)), segments(from = 2, to = 6))
    }

    @Test
    fun `a range whose second line piece is empty keeps one piece`() {
        val emptyLineOf: (Int) -> Int = { offset -> if (offset < 4) 0 else 1 }
        val emptyLineStart: (Int) -> Int = { line -> if (line == 0) 0 else 4 }
        val emptyLineEnd: (Int) -> Int = { line -> if (line == 0) 3 else 4 }

        assertEquals(
            listOf(ProblemSegment(0, 2, 3)),
            problemSegments(2, 5, 5, 0, 1, emptyLineOf, emptyLineStart, emptyLineEnd),
        )
    }
}
