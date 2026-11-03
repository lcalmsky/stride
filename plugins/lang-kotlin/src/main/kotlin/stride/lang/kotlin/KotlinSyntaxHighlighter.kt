package stride.lang.kotlin

import stride.lang.Lexer
import stride.lang.SyntaxHighlighter
import stride.lang.SyntaxHighlighterFactory
import stride.lang.TextAttributesKey
import stride.lang.TokenType

class KotlinSyntaxHighlighter : SyntaxHighlighter {
    override fun getHighlightingLexer(): Lexer = KotlinLexer()

    override fun getTokenHighlights(tokenType: TokenType): Array<TextAttributesKey> = when (tokenType) {
        KotlinTokenTypes.KEYWORD -> arrayOf(TextAttributesKey.KEYWORD)
        KotlinTokenTypes.IDENTIFIER -> arrayOf(TextAttributesKey.IDENTIFIER)
        KotlinTokenTypes.NUMBER -> arrayOf(TextAttributesKey.NUMBER)
        KotlinTokenTypes.STRING -> arrayOf(TextAttributesKey.STRING)
        KotlinTokenTypes.CHARACTER -> arrayOf(TextAttributesKey.CHARACTER)
        KotlinTokenTypes.LINE_COMMENT -> arrayOf(TextAttributesKey.LINE_COMMENT)
        KotlinTokenTypes.BLOCK_COMMENT -> arrayOf(TextAttributesKey.BLOCK_COMMENT)
        KotlinTokenTypes.OPERATION_SIGN -> arrayOf(TextAttributesKey.OPERATION_SIGN)
        KotlinTokenTypes.BRACES -> arrayOf(TextAttributesKey.BRACES)
        KotlinTokenTypes.BRACKETS -> arrayOf(TextAttributesKey.BRACKETS)
        KotlinTokenTypes.PARENTHESES -> arrayOf(TextAttributesKey.PARENTHESES)
        KotlinTokenTypes.COMMA -> arrayOf(TextAttributesKey.COMMA)
        KotlinTokenTypes.DOT -> arrayOf(TextAttributesKey.DOT)
        KotlinTokenTypes.SEMICOLON -> arrayOf(TextAttributesKey.SEMICOLON)
        KotlinTokenTypes.BAD_CHARACTER -> arrayOf(TextAttributesKey.BAD_CHARACTER)
        else -> emptyArray()
    }
}

object KotlinSyntaxHighlighterFactory : SyntaxHighlighterFactory {
    override fun getSyntaxHighlighter(): SyntaxHighlighter = KotlinSyntaxHighlighter()
}
