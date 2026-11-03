package stride.lang

class TextAttributesKey(val externalName: String, val fallback: TextAttributesKey? = null) {
    override fun equals(other: Any?): Boolean =
        other is TextAttributesKey && other.externalName == externalName

    override fun hashCode(): Int = externalName.hashCode()

    override fun toString(): String = externalName

    companion object {
        val DEFAULT_TEXT = TextAttributesKey("DEFAULT_TEXT")
        val KEYWORD = TextAttributesKey("KEYWORD", DEFAULT_TEXT)
        val STRING = TextAttributesKey("STRING", DEFAULT_TEXT)
        val NUMBER = TextAttributesKey("NUMBER", DEFAULT_TEXT)
        val LINE_COMMENT = TextAttributesKey("LINE_COMMENT", DEFAULT_TEXT)
        val BLOCK_COMMENT = TextAttributesKey("BLOCK_COMMENT", DEFAULT_TEXT)
        val IDENTIFIER = TextAttributesKey("IDENTIFIER", DEFAULT_TEXT)
        val OPERATION_SIGN = TextAttributesKey("OPERATION_SIGN", DEFAULT_TEXT)
        val BRACES = TextAttributesKey("BRACES", DEFAULT_TEXT)
        val BRACKETS = TextAttributesKey("BRACKETS", DEFAULT_TEXT)
        val PARENTHESES = TextAttributesKey("PARENTHESES", DEFAULT_TEXT)
        val COMMA = TextAttributesKey("COMMA", DEFAULT_TEXT)
        val DOT = TextAttributesKey("DOT", DEFAULT_TEXT)
        val SEMICOLON = TextAttributesKey("SEMICOLON", DEFAULT_TEXT)
        val CHARACTER = TextAttributesKey("CHARACTER", STRING)
        val BAD_CHARACTER = TextAttributesKey("BAD_CHARACTER", DEFAULT_TEXT)
    }
}
