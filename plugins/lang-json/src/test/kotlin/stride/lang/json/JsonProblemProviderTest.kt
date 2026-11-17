package stride.lang.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.Problem
import stride.lang.ProblemContext
import stride.lang.ProblemSeverity

class JsonProblemProviderTest {
    private val provider = JsonProblemProvider()

    private fun collect(text: String): List<Problem> = provider.collect(ProblemContext(text, "package.json"))

    @Test
    fun `valid JSON reports nothing`() {
        assertTrue(collect("{\"a\": [1, 2], \"b\": {\"c\": true}}").isEmpty())
    }

    @Test
    fun `an unclosed brace is reported`() {
        assertEquals(
            listOf(Problem(0, 1, ProblemSeverity.ERROR, "Unclosed '{'")),
            collect("{\"a\": [1, 2]"),
        )
    }

    @Test
    fun `a closing bracket without an opener is unexpected`() {
        assertEquals(
            listOf(Problem(6, 7, ProblemSeverity.ERROR, "Unexpected ']'")),
            collect("[1, 2]]"),
        )
    }

    @Test
    fun `brackets inside a string are not counted`() {
        assertTrue(collect("{\"a\": \"([{\"}").isEmpty())
    }

    @Test
    fun `a commented file reports nothing`() {
        assertTrue(collect("{\"a\": 1 // c").isEmpty())
    }
}
