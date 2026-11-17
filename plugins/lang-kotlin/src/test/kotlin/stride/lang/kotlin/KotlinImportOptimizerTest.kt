package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinImportOptimizerTest {
    private val optimizer = KotlinImportOptimizer()

    private fun optimize(text: String): String {
        var result = text
        for (edit in optimizer.optimize(text).sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    @Test
    fun `an unused import is removed and the rest is sorted`() {
        val text = "package demo\n\nimport demo.b.Beta\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n"

        assertEquals(
            "package demo\n\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n",
            optimize(text),
        )
    }

    @Test
    fun `a duplicate import is removed`() {
        val text = "package demo\n\nimport demo.a.Alpha\nimport demo.a.Alpha\n\nfun main() { Alpha() }\n"

        assertEquals("package demo\n\nimport demo.a.Alpha\n\nfun main() { Alpha() }\n", optimize(text))
    }

    @Test
    fun `imports follow the default layout of other java javax kotlin then aliases`() {
        val text = "package demo\n\n" +
            "import demo.z.Zed\n" +
            "import kotlin.io.path.Path\n" +
            "import javax.swing.JFrame\n" +
            "import java.util.List\n" +
            "import demo.a.Alpha as A\n\n" +
            "fun main() {\n    Zed(); Path(\"\"); JFrame(); List.of(); A()\n}\n"

        assertEquals(
            "package demo\n\n" +
                "import demo.z.Zed\n" +
                "import java.util.List\n" +
                "import javax.swing.JFrame\n" +
                "import kotlin.io.path.Path\n" +
                "import demo.a.Alpha as A\n\n" +
                "fun main() {\n    Zed(); Path(\"\"); JFrame(); List.of(); A()\n}\n",
            optimize(text),
        )
    }

    @Test
    fun `blank lines inside the block collapse into one block`() {
        val text = "package demo\n\nimport demo.a.Alpha\n\nimport demo.b.Beta\n\nfun main() { Alpha(); Beta() }\n"

        assertEquals(
            "package demo\n\nimport demo.a.Alpha\nimport demo.b.Beta\n\nfun main() { Alpha(); Beta() }\n",
            optimize(text),
        )
    }

    @Test
    fun `a comment inside the block keeps the order and only its unused line goes`() {
        val text = "package demo\n\nimport demo.b.Beta\n// keep\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n"

        assertEquals(
            "package demo\n\n// keep\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n",
            optimize(text),
        )
    }

    @Test
    fun `a comment inside the block leaves an optimized block untouched`() {
        val text = "package demo\n\nimport demo.b.Beta\n// keep\nimport demo.a.Alpha\n\nfun main() { Alpha(); Beta() }\n"

        assertEquals(text, optimize(text))
    }

    @Test
    fun `removing every import collapses the blank line`() {
        val text = "package demo\n\nimport demo.a.Alpha\n\nfun main() {\n}\n"

        assertEquals("package demo\n\nfun main() {\n}\n", optimize(text))
    }

    @Test
    fun `an optimized file reports no edits`() {
        val text = "package demo\n\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n"

        assertTrue(optimizer.optimize(text).isEmpty())
    }

    @Test
    fun `a star import is kept`() {
        val text = "package demo\n\nimport demo.a.*\n\nfun main() {\n}\n"

        assertTrue(optimizer.optimize(text).isEmpty())
    }

    @Test
    fun `a name used only in a KDoc link still counts as unused`() {
        val text = "package demo\n\nimport demo.a.Alpha\n\n/** See [Alpha]. */\nfun main() {\n}\n"

        assertEquals(
            "package demo\n\n/** See [Alpha]. */\nfun main() {\n}\n",
            optimize(text),
        )
    }

    @Test
    fun `a file without imports reports no edits`() {
        assertTrue(optimizer.optimize("package demo\n\nfun main() {}\n").isEmpty())
    }
}
