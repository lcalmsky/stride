package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.IntentionContext
import stride.lang.TextEdit

class KotlinValVarIntentionTest {
    private val provider = KotlinValVarIntention()

    private fun collect(text: String, caret: Int = 0) =
        provider.collect(IntentionContext(text, caret, "Main.kt"))

    private fun apply(text: String, edits: List<TextEdit>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    @Test
    fun `val becomes var`() {
        val text = "fun main() {\n    val count = 1\n}\n"
        val actions = collect(text, text.indexOf("val"))

        assertEquals(listOf("Change 'val' to 'var'"), actions.map { it.text })
        assertEquals("fun main() {\n    var count = 1\n}\n", apply(text, actions.single().edits))
    }

    @Test
    fun `var becomes val`() {
        val text = "fun main() {\n    var count = 1\n}\n"
        val actions = collect(text, text.indexOf("var"))

        assertEquals(listOf("Change 'var' to 'val'"), actions.map { it.text })
        assertEquals("fun main() {\n    val count = 1\n}\n", apply(text, actions.single().edits))
    }

    @Test
    fun `only the caret line is considered`() {
        val text = "val first = 1\nvar second = 2\n"
        val secondLine = text.indexOf("var")

        val actions = collect(text, secondLine)

        assertEquals(listOf("Change 'var' to 'val'"), actions.map { it.text })
        assertEquals("val first = 1\nval second = 2\n", apply(text, actions.single().edits))
    }

    @Test
    fun `the first val on the line is the one that changes`() {
        val text = "val first = 1\nfun main() { val a = 1; var b = 2 }\n"
        val actions = collect(text, text.indexOf("fun main"))

        assertEquals(listOf("Change 'val' to 'var'"), actions.map { it.text })
        assertEquals(
            "val first = 1\nfun main() { var a = 1; var b = 2 }\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `const val and lateinit var are skipped`() {
        assertTrue(collect("const val LIMIT = 1\n").isEmpty())
        assertTrue(collect("lateinit var name: String\n").isEmpty())
    }

    @Test
    fun `a keyword in a comment is ignored`() {
        assertTrue(collect("// val x = 1\n").isEmpty())
        assertTrue(collect("/* var y = 2 */\n").isEmpty())
    }

    @Test
    fun `an overriding var does not become val`() {
        val text = "class A : B() {\n    override var x: Int = 0\n}\n"
        assertTrue(collect(text, text.indexOf("var")).isEmpty())
    }

    @Test
    fun `an overriding val still becomes var`() {
        val text = "class A : B() {\n    override val x: Int = 0\n}\n"
        val actions = collect(text, text.indexOf("val"))

        assertEquals(listOf("Change 'val' to 'var'"), actions.map { it.text })
    }

    @Test
    fun `a val bound by when is skipped`() {
        val text = "fun main() {\n    when (val r = compute()) {\n        else -> r\n    }\n}\n"
        assertTrue(collect(text, text.indexOf("val")).isEmpty())
    }

    @Test
    fun `a delegated property is skipped`() {
        assertTrue(collect("val text by lazy { \"x\" }\n").isEmpty())
    }

    @Test
    fun `accessor declarations are skipped`() {
        assertTrue(collect("val x: Int get() = 1\n").isEmpty())
        assertTrue(collect("var y: Int = 0 set(value) {\n    field = value\n}\n").isEmpty())
    }

    @Test
    fun `a constructor property is offered`() {
        val text = "class A(val x: Int)\n"
        val actions = collect(text, text.indexOf("val"))

        assertEquals(listOf("Change 'val' to 'var'"), actions.map { it.text })
    }
}
