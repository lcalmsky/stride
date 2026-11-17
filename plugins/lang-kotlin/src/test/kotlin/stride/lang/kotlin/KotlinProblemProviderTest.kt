package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.Problem
import stride.lang.ProblemContext
import stride.lang.ProblemSeverity

class KotlinProblemProviderTest {
    private val provider = KotlinProblemProvider()

    private fun collect(text: String): List<Problem> = provider.collect(ProblemContext(text, "Main.kt"))

    @Test
    fun `code with matched brackets and used imports reports nothing`() {
        val text = "package demo\n\nimport demo.a.Clock\n\nfun main() {\n    Clock().tick(listOf(1, 2))\n}\n"
        assertTrue(collect(text).isEmpty())
    }

    @Test
    fun `brackets inside templates raw strings characters and literals are not counted`() {
        assertTrue(collect("val a = \"\${map[\"k\"]}\"\n").isEmpty())
        assertTrue(collect("val a = \"\${items.map { it }}\"\n").isEmpty())
        assertTrue(collect("val a = \"\"\"( [ {\"\"\"\n").isEmpty())
        assertTrue(collect("val c = '('\n").isEmpty())
        assertTrue(collect("val s = \"(\"\n").isEmpty())
    }

    @Test
    fun `brackets inside comments are not counted`() {
        assertTrue(collect("// ( [ {\nfun main() {}\n").isEmpty())
        assertTrue(collect("/* ( [ { */\n/** ( */\nfun main() {}\n").isEmpty())
        assertTrue(collect("/* ( /* ) */ ( */\nfun main() {}\n").isEmpty())
    }

    @Test
    fun `a closing brace without an opener is unexpected`() {
        assertEquals(
            listOf(Problem(19, 20, ProblemSeverity.ERROR, "Unexpected '}'")),
            collect("fun main() {\n    }\n}\n"),
        )
    }

    @Test
    fun `an opener left open at the end is unclosed`() {
        assertEquals(
            listOf(Problem(8, 9, ProblemSeverity.ERROR, "Unclosed '('")),
            collect("fun main(\n"),
        )
    }

    @Test
    fun `an unused import is a warning over its directive`() {
        assertEquals(
            listOf(Problem(0, 19, ProblemSeverity.WARNING, "Unused import directive")),
            collect("import demo.a.Alpha\n\nfun main() = 1\n"),
        )
    }

    @Test
    fun `an unused import range stops before a CRLF line break`() {
        assertEquals(
            listOf(Problem(0, 19, ProblemSeverity.WARNING, "Unused import directive")),
            collect("import demo.a.Alpha\r\n\r\nval x = 1\r\n"),
        )
    }

    @Test
    fun `a used aliased or star import is not reported`() {
        assertTrue(collect("import demo.a.Clock\n\nfun main() { Clock() }\n").isEmpty())
        assertTrue(collect("import demo.a.Clock as C\n\nfun main() { C() }\n").isEmpty())
        assertTrue(collect("import demo.a.*\n\nfun main() = 1\n").isEmpty())
    }

    @Test
    fun `an operator convention name and a template reference count as used`() {
        assertTrue(collect("import demo.Ops.getValue\n\nval bag = mapOf(1 to 2)\n").isEmpty())
        assertTrue(collect("import demo.Name\n\nval text = \"\$Name\"\n").isEmpty())
    }
}
