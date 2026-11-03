package stride.lang.json

import stride.lang.Lexer
import stride.lang.SyntaxHighlighter
import stride.lang.SyntaxHighlighterFactory
import stride.lang.TextAttributesKey
import stride.lang.TokenType

class JsonSyntaxHighlighter : SyntaxHighlighter {
    override fun getHighlightingLexer(): Lexer = JsonLexer()

    override fun getTokenHighlights(tokenType: TokenType): Array<TextAttributesKey> = when (tokenType) {
        JsonTokenTypes.STRING -> arrayOf(TextAttributesKey.STRING)
        JsonTokenTypes.NUMBER -> arrayOf(TextAttributesKey.NUMBER)
        JsonTokenTypes.KEYWORD -> arrayOf(TextAttributesKey.KEYWORD)
        JsonTokenTypes.BRACES -> arrayOf(TextAttributesKey.BRACES)
        JsonTokenTypes.BRACKETS -> arrayOf(TextAttributesKey.BRACKETS)
        JsonTokenTypes.COMMA -> arrayOf(TextAttributesKey.COMMA)
        JsonTokenTypes.OPERATION_SIGN -> arrayOf(TextAttributesKey.OPERATION_SIGN)
        JsonTokenTypes.BAD_CHARACTER -> arrayOf(TextAttributesKey.BAD_CHARACTER)
        else -> emptyArray()
    }
}

object JsonSyntaxHighlighterFactory : SyntaxHighlighterFactory {
    override fun getSyntaxHighlighter(): SyntaxHighlighter = JsonSyntaxHighlighter()
}
