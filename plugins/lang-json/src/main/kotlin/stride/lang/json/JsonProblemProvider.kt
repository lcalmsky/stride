package stride.lang.json

import stride.lang.BracketProblems
import stride.lang.Problem
import stride.lang.ProblemContext
import stride.lang.ProblemProvider
import stride.lang.TokenType

/** Bracket pairing of a strict JSON file, judged from its tokens alone. */
class JsonProblemProvider : ProblemProvider {
    override fun collect(context: ProblemContext): List<Problem> {
        val tokens = tokens(context.text)
        if (tokens.any { it.type == JsonTokenTypes.BAD_CHARACTER && it.text.startsWith("/") }) return emptyList()
        val brackets = tokens
            .filter { it.type == JsonTokenTypes.BRACES || it.type == JsonTokenTypes.BRACKETS }
            .map { it.start to it.text[0] }
        return BracketProblems.find(brackets)
    }

    private data class JsonToken(val type: TokenType, val start: Int, val text: String)

    private fun tokens(text: String): List<JsonToken> {
        val lexer = JsonLexer()
        lexer.start(text, 0, text.length, 0)
        val result = ArrayList<JsonToken>()
        while (true) {
            val type = lexer.getTokenType() ?: break
            val start = lexer.getTokenStart()
            result.add(JsonToken(type, start, text.substring(start, lexer.getTokenEnd())))
            lexer.advance()
        }
        return result
    }
}
