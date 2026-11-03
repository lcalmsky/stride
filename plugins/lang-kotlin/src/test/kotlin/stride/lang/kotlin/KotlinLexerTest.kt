package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.TokenType

class KotlinLexerTest {
    private data class Token(val text: String, val type: TokenType, val start: Int)

    private val sample: String = listOf(
        "package demo",
        "",
        "// line comment",
        "/* outer /* inner */ still outer",
        "   continued */",
        "class Foo(private val x: Int = 0x1F) {",
        "    fun bar(): String = \"a\\n\$x\"",
        "    val raw = \"\"\"",
        "        raw \$x",
        "        \${y}",
        "    \"\"\"",
        "    val c = '\\n'",
        "    fun calc() = 1_000L + 3.14e-2F * 0b1010 / 0xFF",
        "}",
    ).joinToString("\n")

    private fun lex(text: String, startOffset: Int = 0, state: Int = 0): List<Token> {
        val lexer = KotlinLexer()
        lexer.start(text, startOffset, text.length, state)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.getTokenType() ?: break
            tokens.add(Token(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()), type, lexer.getTokenStart()))
            lexer.advance()
        }
        return tokens
    }

    private fun stateAt(text: String, offset: Int): Int {
        val lexer = KotlinLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            if (lexer.getTokenType() == null) return lexer.getState()
            if (lexer.getTokenStart() >= offset) return lexer.getState()
            lexer.advance()
        }
    }

    @Test
    fun `keywords and identifiers`() {
        val tokens = lex("package demo\nclass Foo(private val x: Int) { fun bar() = this }")

        assertEquals(
            listOf("package", "class", "private", "val", "fun", "this"),
            tokens.filter { it.type == KotlinTokenTypes.KEYWORD }.map { it.text },
        )
        assertEquals(
            listOf("demo", "Foo", "x", "Int", "bar"),
            tokens.filter { it.type == KotlinTokenTypes.IDENTIFIER }.map { it.text },
        )
    }

    @Test
    fun `numbers cover int long hex binary float and underscores`() {
        val tokens = lex("42 1_000L 0x1F 0xFFL 0b1010 3.14 3.14e-2F 1f")

        assertEquals(
            listOf("42", "1_000L", "0x1F", "0xFFL", "0b1010", "3.14", "3.14e-2F", "1f"),
            tokens.map { it.text },
        )
        assertTrue(tokens.all { it.type == KotlinTokenTypes.NUMBER }, tokens.toString())
    }

    @Test
    fun `line and block comments span lines`() {
        val tokens = lex("// one\n/* a /* b */ c\n d */ val x = 1")

        assertEquals(listOf("// one"), tokens.filter { it.type == KotlinTokenTypes.LINE_COMMENT }.map { it.text })
        assertEquals(
            listOf("/* a /* b */ c", " d */"),
            tokens.filter { it.type == KotlinTokenTypes.BLOCK_COMMENT }.map { it.text },
        )
        assertTrue(tokens.any { it.text == "val" && it.type == KotlinTokenTypes.KEYWORD })
    }

    @Test
    fun `strings and char literals`() {
        val tokens = lex("\"a\\n\$x\" '\\n'")

        assertEquals(listOf("\"a\\n\$x\""), tokens.filter { it.type == KotlinTokenTypes.STRING }.map { it.text })
        assertEquals(listOf("'\\n'"), tokens.filter { it.type == KotlinTokenTypes.CHARACTER }.map { it.text })
    }

    @Test
    fun `brace templates stay string tokens`() {
        val tokens = lex("\"a\${x}b\"")

        assertEquals(listOf("\"a\${", "x}", "b\""), tokens.map { it.text })
        assertTrue(tokens.all { it.type == KotlinTokenTypes.STRING })
    }

    @Test
    fun `raw strings emit one token per line`() {
        val tokens = lex("\"\"\"\n raw\n\"\"\"")

        assertEquals(listOf("\"\"\"", " raw", "\"\"\""), tokens.map { it.text })
        assertTrue(tokens.all { it.type == KotlinTokenTypes.STRING })
    }

    @Test
    fun `punctuation and operators`() {
        val tokens = lex("a.b, c; (d) [e] {f} x + y == z && w -> q")

        assertEquals(
            listOf(
                "." to KotlinTokenTypes.DOT,
                "," to KotlinTokenTypes.COMMA,
                ";" to KotlinTokenTypes.SEMICOLON,
                "(" to KotlinTokenTypes.PARENTHESES,
                ")" to KotlinTokenTypes.PARENTHESES,
                "[" to KotlinTokenTypes.BRACKETS,
                "]" to KotlinTokenTypes.BRACKETS,
                "{" to KotlinTokenTypes.BRACES,
                "}" to KotlinTokenTypes.BRACES,
                "+" to KotlinTokenTypes.OPERATION_SIGN,
                "==" to KotlinTokenTypes.OPERATION_SIGN,
                "&&" to KotlinTokenTypes.OPERATION_SIGN,
                "->" to KotlinTokenTypes.OPERATION_SIGN,
            ),
            tokens.filter { it.type != KotlinTokenTypes.IDENTIFIER }.map { it.text to it.type },
        )
    }

    @Test
    fun `a template opened on one line and closed on a later line survives a per-line resume`() {
        val text = listOf(
            "val text = \"prefix \${",
            "    if (flag) { 1 } else { 2 }",
            "} suffix\"",
            "val after = 1",
        ).joinToString("\n")

        val full = lex(text)

        assertEquals(
            listOf(
                "val" to KotlinTokenTypes.KEYWORD,
                "text" to KotlinTokenTypes.IDENTIFIER,
                "=" to KotlinTokenTypes.OPERATION_SIGN,
                "\"prefix \${" to KotlinTokenTypes.STRING,
                "    if (flag) { 1 } else { 2 }" to KotlinTokenTypes.STRING,
                "}" to KotlinTokenTypes.STRING,
                " suffix\"" to KotlinTokenTypes.STRING,
                "val" to KotlinTokenTypes.KEYWORD,
                "after" to KotlinTokenTypes.IDENTIFIER,
                "=" to KotlinTokenTypes.OPERATION_SIGN,
                "1" to KotlinTokenTypes.NUMBER,
            ),
            full.map { it.text to it.type },
        )

        val insideTemplate = text.indexOf('\n') + 1
        assertEquals(0x4002, stateAt(text, insideTemplate))

        var offset = insideTemplate
        while (offset < text.length) {
            val state = stateAt(text, offset)
            assertEquals(full.filter { it.start >= offset }, lex(text, offset, state), "resume at $offset with state $state")
            val newline = text.indexOf('\n', offset)
            if (newline < 0) break
            offset = newline + 1
        }
    }

    @Test
    fun `resuming at every line start reproduces the full token stream`() {
        val full = lex(sample)

        var offset = 0
        while (true) {
            val state = stateAt(sample, offset)
            val resumed = lex(sample, offset, state)
            assertEquals(full.filter { it.start >= offset }, resumed, "resume at $offset with state $state")
            if (offset >= sample.length) break
            val newline = sample.indexOf('\n', offset)
            if (newline < 0) break
            offset = newline + 1
        }
    }
}
