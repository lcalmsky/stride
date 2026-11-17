package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurroundWithTest {
    private val kotlinTemplates = SurroundWith.templatesFor("kt")
    private val javaTemplates = SurroundWith.templatesFor("java")

    private fun kotlinTemplate(label: String): SurroundTemplate = kotlinTemplates.first { it.label == label }

    private fun javaTemplate(label: String): SurroundTemplate = javaTemplates.first { it.label == label }

    @Test
    fun `the if template wraps the caret line and puts the caret in the condition`() {
        val result = SurroundWith.surround("val x = 1", TextRange(0), kotlinTemplate("if"))

        assertEquals("if () {\n    val x = 1\n}", result.text)
        assertEquals("if (".length, result.selection.start)
        assertTrue(result.selection.collapsed)
    }

    @Test
    fun `the header keeps the first line's indentation and the body gains one unit`() {
        val text = "fun main() {\n    val x = 1\n}"
        val caret = text.indexOf("val x")

        val result = SurroundWith.surround(text, TextRange(caret), kotlinTemplate("if"))

        assertEquals("fun main() {\n    if () {\n        val x = 1\n    }\n}", result.text)
        assertEquals(result.text.indexOf("if (") + "if (".length, result.selection.start)
    }

    @Test
    fun `the do while template puts the caret in the trailing while condition`() {
        val result = SurroundWith.surround("val x = 1", TextRange(0), kotlinTemplate("do / while"))

        assertEquals("do {\n    val x = 1\n} while ()", result.text)
        assertEquals(result.text.length - 1, result.selection.start)
    }

    @Test
    fun `the catch template selects Exception`() {
        val result = SurroundWith.surround("val x = 1", TextRange(0), kotlinTemplate("try / catch"))

        assertEquals("try {\n    val x = 1\n} catch (e: Exception) {\n}", result.text)
        assertEquals("Exception", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun `the finally template puts the caret on the empty finally line`() {
        val result = SurroundWith.surround("val x = 1", TextRange(0), kotlinTemplate("try / finally"))

        assertEquals("try {\n    val x = 1\n} finally {\n\n}", result.text)
        assertTrue(result.selection.collapsed)
        assertEquals(3, result.text.take(result.selection.start).count { it == '\n' })
    }

    @Test
    fun `the combined template selects Exception in the catch clause`() {
        val result = SurroundWith.surround("val x = 1", TextRange(0), kotlinTemplate("try / catch / finally"))

        assertEquals("try {\n    val x = 1\n} catch (e: Exception) {\n} finally {\n\n}", result.text)
        assertEquals("Exception", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun `the run template keeps the body as it is and maps the caret`() {
        val text = "    val x = 1"
        val caret = text.length

        val result = SurroundWith.surround(text, TextRange(caret), kotlinTemplate("run { }"))

        assertEquals("    run {\n    val x = 1\n    }", result.text)
        assertEquals(caret + "    run {\n".length, result.selection.start)
    }

    @Test
    fun `a blank line in the body is not indented`() {
        val text = "val a = 1\n\nval b = 2"

        val result = SurroundWith.surround(text, TextRange(0, text.length), kotlinTemplate("if"))

        assertEquals("if () {\n    val a = 1\n\n    val b = 2\n}", result.text)
    }

    @Test
    fun `a selection ending at the next line's start leaves that line out`() {
        val text = "val a = 1\nval b = 2"

        val result = SurroundWith.surround(text, TextRange(0, text.indexOf("val b")), kotlinTemplate("if"))

        assertEquals("if () {\n    val a = 1\n}\nval b = 2", result.text)
    }

    @Test
    fun `only the target lines are replaced`() {
        val text = "a\nval x = 1\nb"

        val result = SurroundWith.surround(text, TextRange(text.indexOf("val x")), kotlinTemplate("if"))

        assertEquals("a\nif () {\n    val x = 1\n}\nb", result.text)
    }

    @Test
    fun `the java table adds synchronized and a plain block with the java catch clause`() {
        assertEquals(
            listOf(
                "if", "if / else", "while", "do / while", "for", "try / catch", "try / finally",
                "try / catch / finally", "synchronized", "{ }",
            ),
            javaTemplates.map { it.label },
        )
        assertEquals(emptyList<SurroundTemplate>(), SurroundWith.templatesFor("txt"))
        assertEquals(9, kotlinTemplates.size)
    }

    @Test
    fun `the java catch template uses the java parameter form`() {
        val result = SurroundWith.surround("int x = 1;", TextRange(0), javaTemplate("try / catch"))

        assertTrue(result.text.contains("catch (Exception e) {"))
        assertEquals("Exception", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test
    fun `a crlf file keeps its line endings`() {
        val text = "val x = 1\r\nval y = 2\r\n"

        val result = SurroundWith.surround(text, TextRange(0), kotlinTemplate("if"))

        assertEquals("if () {\r\n    val x = 1\r\n}\r\nval y = 2\r\n", result.text)
        assertEquals("if (".length, result.selection.start)
    }

    @Test
    fun `synchronized and block templates place the caret and the body`() {
        val synchronized = SurroundWith.surround("int x = 1;", TextRange(0), javaTemplate("synchronized"))
        assertEquals("synchronized () {\n    int x = 1;\n}", synchronized.text)
        assertEquals("synchronized (".length, synchronized.selection.start)

        val block = SurroundWith.surround("int x = 1;", TextRange(7), javaTemplate("{ }"))
        assertEquals("{\nint x = 1;\n}", block.text)
        assertEquals(7 + "{\n".length, block.selection.start)
    }
}
