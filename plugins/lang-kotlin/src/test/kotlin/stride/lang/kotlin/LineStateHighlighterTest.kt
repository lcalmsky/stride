package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.lang.LineStateHighlighter
import java.util.Random

class LineStateHighlighterTest {
    private val sample: String = listOf(
        "package demo",
        "",
        "/* outer /* inner */ still outer",
        "   continued */",
        "class Foo {",
        "    fun bar(): String = \"value \$name\"",
        "    val raw = \"\"\"",
        "        raw text",
        "        \${y}",
        "    \"\"\"",
        "    val c = '\\n'",
        "}",
    ).joinToString("\n")

    private fun newHighlighter(text: String): LineStateHighlighter {
        val syntax = KotlinSyntaxHighlighter()
        return LineStateHighlighter(syntax.getHighlightingLexer(), text, syntax::getTokenHighlights)
    }

    @Test
    fun `random edits keep spans equal to a full relex`() {
        val random = Random(20261102L)
        val text = StringBuilder(sample)
        val incremental = newHighlighter(text.toString())

        repeat(200) { iteration ->
            val offset = random.nextInt(text.length + 1)
            val maxOldLength = minOf(4, text.length - offset)
            val oldLength = if (maxOldLength > 0) random.nextInt(maxOldLength + 1) else 0
            val inserted = buildString {
                repeat(random.nextInt(4)) { append(ALPHABET[random.nextInt(ALPHABET.size)]) }
            }

            text.replace(offset, offset + oldLength, inserted)
            incremental.edit(offset, oldLength, inserted)

            assertEquals(
                newHighlighter(text.toString()).spans(),
                incremental.spans(),
                "iteration $iteration: edit($offset, $oldLength, \"$inserted\") in ${text.length} chars",
            )
        }
    }

    @Test
    fun `single line edit relexes only the edited line`() {
        val text = "package demo\n\nfun main() {\n    val x = 1\n}\n"
        val incremental = newHighlighter(text)
        val offset = text.indexOf('1')

        incremental.edit(offset, 1, "42")

        assertEquals(1, incremental.lastRelexedLineCount)
        assertEquals(newHighlighter("package demo\n\nfun main() {\n    val x = 42\n}\n").spans(), incremental.spans())
    }

    @Test
    fun `edit that inserts a line keeps spans aligned`() {
        val text = "val a = 1\nval b = 2\nval c = 3\n"
        val incremental = newHighlighter(text)
        val offset = text.indexOf("val b")

        incremental.edit(offset, 0, "// added\n")

        assertEquals(newHighlighter("val a = 1\n// added\nval b = 2\nval c = 3\n").spans(), incremental.spans())
    }

    private companion object {
        val ALPHABET: CharArray = "abcxyz019 \n\"'/*\${}.-=_`\$".toCharArray()
    }
}
