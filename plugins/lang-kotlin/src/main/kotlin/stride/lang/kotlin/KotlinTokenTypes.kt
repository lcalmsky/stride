package stride.lang.kotlin

import stride.lang.TokenType

object KotlinTokenTypes {
    val KEYWORD = TokenType("Kotlin:KEYWORD")
    val IDENTIFIER = TokenType("Kotlin:IDENTIFIER")
    val NUMBER = TokenType("Kotlin:NUMBER")
    val STRING = TokenType("Kotlin:STRING")
    val CHARACTER = TokenType("Kotlin:CHARACTER")
    val LINE_COMMENT = TokenType("Kotlin:LINE_COMMENT")
    val BLOCK_COMMENT = TokenType("Kotlin:BLOCK_COMMENT")
    val OPERATION_SIGN = TokenType("Kotlin:OPERATION_SIGN")
    val BRACES = TokenType("Kotlin:BRACES")
    val BRACKETS = TokenType("Kotlin:BRACKETS")
    val PARENTHESES = TokenType("Kotlin:PARENTHESES")
    val COMMA = TokenType("Kotlin:COMMA")
    val DOT = TokenType("Kotlin:DOT")
    val SEMICOLON = TokenType("Kotlin:SEMICOLON")
    val BAD_CHARACTER = TokenType("Kotlin:BAD_CHARACTER")
}
