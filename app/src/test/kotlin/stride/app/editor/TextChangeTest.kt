package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.lang.LineStateHighlighter
import stride.lang.kotlin.KotlinLexer
import stride.lang.kotlin.KotlinSyntaxHighlighter

class TextChangeTest {
    @Test
    fun `insert`() {
        assertEquals(TextChange(2, 0, "X"), computeTextChange("abc", "abXc"))
    }

    @Test
    fun `delete`() {
        assertEquals(TextChange(2, 1, ""), computeTextChange("abXc", "abc"))
    }

    @Test
    fun `replace`() {
        assertEquals(TextChange(6, 5, "there"), computeTextChange("hello world", "hello there"))
    }

    @Test
    fun `empty edges`() {
        assertEquals(TextChange(0, 0, "abc"), computeTextChange("", "abc"))
        assertEquals(TextChange(0, 3, ""), computeTextChange("abc", ""))
        assertEquals(TextChange(0, 0, ""), computeTextChange("", ""))
        assertEquals(TextChange(0, 0, ""), computeTextChange("abc", "abc"))
    }

    @Test
    fun `prefix and suffix never overlap`() {
        assertEquals(TextChange(2, 0, "a"), computeTextChange("aa", "aaa"))
        assertEquals(TextChange(2, 1, ""), computeTextChange("aaa", "aa"))
    }

    @Test
    fun `incremental edits match a fresh highlighter`() {
        var text = """
            fun main() {
                val x = 1
                /* block
                   comment */
                println("hi ${'$'}x")
            }
        """.trimIndent()
        val incremental = highlighter(text)

        val targets = listOf(
            "val x = 1" to "val xyz = 42",
            "val xyz = 42" to "val xyz = 42 + 8",
            "/* block" to "/* block comment",
            "   comment */" to "   comment */\n    val y = 2",
            "println(\"hi ${'$'}x\")" to "println(\"bye\")",
            "fun main() {" to "",
        )
        for ((old, new) in targets) {
            require(old in text)
            val next = text.replaceFirst(old, new)
            val change = computeTextChange(text, next)
            incremental.edit(change.offset, change.oldLength, change.newText)
            text = next
            assertEquals(highlighter(text).spans(), incremental.spans(), "after editing to:\n$text")
        }
    }

    private fun highlighter(text: String): LineStateHighlighter =
        LineStateHighlighter(KotlinLexer(), text, KotlinSyntaxHighlighter()::getTokenHighlights)
}
