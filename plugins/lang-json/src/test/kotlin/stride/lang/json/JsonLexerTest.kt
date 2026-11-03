package stride.lang.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.lang.TokenType

class JsonLexerTest {
    private data class Token(val text: String, val type: TokenType, val start: Int)

    private val sample: String = listOf(
        "{",
        "  \"name\": \"stride\",",
        "  \"numbers\": [1, -2.5e+3],",
        "  \"ok\": true,",
        "  \"nothing\": null",
        "}",
    ).joinToString("\n")

    private fun lex(text: String, startOffset: Int = 0): List<Token> {
        val lexer = JsonLexer()
        lexer.start(text, startOffset, text.length, 0)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.getTokenType() ?: break
            tokens.add(Token(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()), type, lexer.getTokenStart()))
            lexer.advance()
        }
        return tokens
    }

    @Test
    fun `strings numbers keywords and punctuation`() {
        val tokens = lex("{\"a\": [1, -2.5e+3, true, false, null]}")

        assertEquals(listOf("\"a\""), tokens.filter { it.type == JsonTokenTypes.STRING }.map { it.text })
        assertEquals(listOf("1", "-2.5e+3"), tokens.filter { it.type == JsonTokenTypes.NUMBER }.map { it.text })
        assertEquals(
            listOf("true", "false", "null"),
            tokens.filter { it.type == JsonTokenTypes.KEYWORD }.map { it.text },
        )
        assertEquals(
            listOf(
                "{" to JsonTokenTypes.BRACES,
                ":" to JsonTokenTypes.OPERATION_SIGN,
                "[" to JsonTokenTypes.BRACKETS,
                "," to JsonTokenTypes.COMMA,
                "," to JsonTokenTypes.COMMA,
                "," to JsonTokenTypes.COMMA,
                "," to JsonTokenTypes.COMMA,
                "]" to JsonTokenTypes.BRACKETS,
                "}" to JsonTokenTypes.BRACES,
            ),
            tokens
                .filter { it.type in setOf(JsonTokenTypes.BRACES, JsonTokenTypes.BRACKETS, JsonTokenTypes.COMMA, JsonTokenTypes.OPERATION_SIGN) }
                .map { it.text to it.type },
        )
    }

    @Test
    fun `invalid characters become bad character tokens`() {
        val tokens = lex("nul # @ -x")

        assertEquals(listOf("nul", "#", "@", "-", "x"), tokens.map { it.text })
        assertEquals(
            listOf(
                JsonTokenTypes.BAD_CHARACTER,
                JsonTokenTypes.BAD_CHARACTER,
                JsonTokenTypes.BAD_CHARACTER,
                JsonTokenTypes.BAD_CHARACTER,
                JsonTokenTypes.BAD_CHARACTER,
            ),
            tokens.map { it.type },
        )
    }

    @Test
    fun `resuming at every line start reproduces the full token stream`() {
        val full = lex(sample)

        var offset = 0
        while (true) {
            assertEquals(full.filter { it.start >= offset }, lex(sample, offset), "resume at $offset")
            if (offset >= sample.length) break
            val newline = sample.indexOf('\n', offset)
            if (newline < 0) break
            offset = newline + 1
        }
    }
}
