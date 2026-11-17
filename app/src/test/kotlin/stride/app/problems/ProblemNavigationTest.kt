package stride.app.problems

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.lang.ProblemSeverity

class ProblemNavigationTest {
    private fun problem(
        start: Int,
        end: Int,
        severity: ProblemSeverity = ProblemSeverity.ERROR,
        message: String = "p$start",
    ) = FileProblem(severity, message, ProblemSource.EDITOR, 0, start, start, end)

    @Test
    fun `forward walks errors before warnings`() {
        val problems = listOf(
            problem(2, 3, ProblemSeverity.WARNING, "warning"),
            problem(10, 12, ProblemSeverity.ERROR, "error"),
        )

        assertEquals("error", ProblemNavigation.target(problems, 0, forward = true)?.message)
    }

    @Test
    fun `without an error the walk uses the warnings`() {
        val problems = listOf(
            problem(2, 3, ProblemSeverity.WARNING, "first"),
            problem(10, 12, ProblemSeverity.WARNING, "second"),
        )

        assertEquals("first", ProblemNavigation.target(problems, 0, forward = true)?.message)
        assertEquals("second", ProblemNavigation.target(problems, 0, forward = false)?.message)
    }

    @Test
    fun `forward takes the first start after the caret and wraps to the top`() {
        val problems = listOf(problem(5, 6, message = "a"), problem(20, 21, message = "b"))

        assertEquals("b", ProblemNavigation.target(problems, 10, forward = true)?.message)
        assertEquals("a", ProblemNavigation.target(problems, 25, forward = true)?.message)
    }

    @Test
    fun `backward takes the last start before the caret and wraps to the bottom`() {
        val problems = listOf(problem(5, 6, message = "a"), problem(20, 21, message = "b"))

        assertEquals("a", ProblemNavigation.target(problems, 10, forward = false)?.message)
        assertEquals("b", ProblemNavigation.target(problems, 0, forward = false)?.message)
    }

    @Test
    fun `a caret inside a problem moves past its start`() {
        val problems = listOf(problem(5, 9, message = "a"))

        assertEquals("a", ProblemNavigation.target(problems, 7, forward = false)?.message)
        assertEquals("a", ProblemNavigation.target(problems, 7, forward = true)?.message)
    }

    @Test
    fun `a record without an offset is not reachable`() {
        val problems = listOf(
            FileProblem(ProblemSeverity.ERROR, "raw", ProblemSource.BUILD, 0, 0, -1, -1),
            problem(3, 4, message = "placed"),
        )

        assertEquals("placed", ProblemNavigation.target(problems, 0, forward = true)?.message)
        assertEquals(listOf("placed"), ProblemNavigation.at(problems, 3).map { it.message })
    }

    @Test
    fun `an empty list has no target or caret problem`() {
        assertNull(ProblemNavigation.target(emptyList(), 0, forward = true))
        assertNull(ProblemNavigation.target(emptyList(), 0, forward = false))
        assertEquals(emptyList<FileProblem>(), ProblemNavigation.at(emptyList(), 0))
    }

    @Test
    fun `at includes both ends of a problem and treats the caret as a position`() {
        val problems = listOf(problem(5, 9, message = "a"), problem(15, 15, message = "empty"))

        assertEquals(listOf("a"), ProblemNavigation.at(problems, 5).map { it.message })
        assertEquals(listOf("a"), ProblemNavigation.at(problems, 9).map { it.message })
        assertEquals(emptyList<String>(), ProblemNavigation.at(problems, 10).map { it.message })
        assertEquals(listOf("empty"), ProblemNavigation.at(problems, 15).map { it.message })
    }
}
