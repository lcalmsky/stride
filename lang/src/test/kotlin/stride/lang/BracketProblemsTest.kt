package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BracketProblemsTest {
    private fun find(text: String): List<Problem> =
        BracketProblems.find(text.mapIndexedNotNull { index, c -> if (c in "()[]{}") index to c else null })

    @Test
    fun `matched brackets report nothing`() {
        assertTrue(find("").isEmpty())
        assertTrue(find("fun main() { val x = listOf(1, [2]) }").isEmpty())
        assertTrue(find("({[]})").isEmpty())
    }

    @Test
    fun `a closing bracket without an opener is unexpected`() {
        assertEquals(
            listOf(Problem(0, 1, ProblemSeverity.ERROR, "Unexpected ')'")),
            find(")"),
        )
    }

    @Test
    fun `a closer skips the openers above its match and reports them as unclosed`() {
        assertEquals(
            listOf(Problem(2, 3, ProblemSeverity.ERROR, "Unclosed '['")),
            find("(a[1)"),
        )
    }

    @Test
    fun `a closing bracket with no match anywhere leaves the stack alone`() {
        assertEquals(
            listOf(Problem(2, 3, ProblemSeverity.ERROR, "Unexpected '}'")),
            find("(a})"),
        )
    }

    @Test
    fun `an opener still on the stack at the end is unclosed`() {
        assertEquals(
            listOf(Problem(0, 1, ProblemSeverity.ERROR, "Unclosed '('")),
            find("(a"),
        )
    }

    @Test
    fun `an opener above a match and an opener left at the end are both unclosed`() {
        assertEquals(
            listOf(
                Problem(1, 2, ProblemSeverity.ERROR, "Unclosed '['"),
                Problem(4, 5, ProblemSeverity.ERROR, "Unclosed '{'"),
            ),
            find("([a){b"),
        )
    }

    @Test
    fun `results sort by start`() {
        assertEquals(
            listOf(
                Problem(0, 1, ProblemSeverity.ERROR, "Unclosed '('"),
                Problem(2, 3, ProblemSeverity.ERROR, "Unexpected '}'"),
            ),
            find("(a}"),
        )
    }
}
