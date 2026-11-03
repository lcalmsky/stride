package stride.lang.json

import stride.lang.TokenType

object JsonTokenTypes {
    val STRING = TokenType("JSON:STRING")
    val NUMBER = TokenType("JSON:NUMBER")
    val KEYWORD = TokenType("JSON:KEYWORD")
    val BRACES = TokenType("JSON:BRACES")
    val BRACKETS = TokenType("JSON:BRACKETS")
    val COMMA = TokenType("JSON:COMMA")
    val OPERATION_SIGN = TokenType("JSON:OPERATION_SIGN")
    val BAD_CHARACTER = TokenType("JSON:BAD_CHARACTER")
}
