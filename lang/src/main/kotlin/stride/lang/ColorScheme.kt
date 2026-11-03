package stride.lang

class TextAttributes(
    val foreground: Long? = null,
    val background: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

class ColorScheme(
    val name: String,
    private val attributes: Map<TextAttributesKey, TextAttributes>,
) {
    fun getAttributes(key: TextAttributesKey): TextAttributes? {
        var current: TextAttributesKey? = key
        while (current != null) {
            attributes[current]?.let { return it }
            current = current.fallback
        }
        return null
    }

    fun attributes(): Map<TextAttributesKey, TextAttributes> = attributes

    companion object {
        const val DARK_BACKGROUND = 0xFF0F1117L
        const val DARK_ACCENT = 0xFF7C5CFFL
        const val DARK_ACCENT_2 = 0xFF00D1FFL

        private const val DARK_TEXT = 0xFFE6E8F0L
        private const val DARK_MUTED = 0xFF6E7686L
        private const val DARK_PUNCTUATION = 0xFFA8B0C2L
        private const val DARK_STRING = 0xFF8FE388L

        private const val LIGHT_TEXT = 0xFF1F2430L
        private const val LIGHT_KEYWORD = 0xFF5B3FD6L
        private const val LIGHT_STRING = 0xFF18794EL
        private const val LIGHT_NUMBER = 0xFF0A6FA8L
        private const val LIGHT_COMMENT = 0xFF6B7280L
        private const val LIGHT_PUNCTUATION = 0xFF4A5162L
        private const val LIGHT_BAD = 0xFFD92D20L

        val DARK: ColorScheme = ColorScheme(
            "Stride Dark",
            mapOf(
                TextAttributesKey.DEFAULT_TEXT to TextAttributes(DARK_TEXT, DARK_BACKGROUND),
                TextAttributesKey.KEYWORD to TextAttributes(DARK_ACCENT, bold = true),
                TextAttributesKey.STRING to TextAttributes(DARK_STRING),
                TextAttributesKey.CHARACTER to TextAttributes(DARK_STRING),
                TextAttributesKey.NUMBER to TextAttributes(DARK_ACCENT_2),
                TextAttributesKey.LINE_COMMENT to TextAttributes(DARK_MUTED, italic = true),
                TextAttributesKey.BLOCK_COMMENT to TextAttributes(DARK_MUTED, italic = true),
                TextAttributesKey.IDENTIFIER to TextAttributes(DARK_TEXT),
                TextAttributesKey.OPERATION_SIGN to TextAttributes(DARK_ACCENT),
                TextAttributesKey.BRACES to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.BRACKETS to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.PARENTHESES to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.COMMA to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.DOT to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.SEMICOLON to TextAttributes(DARK_PUNCTUATION),
                TextAttributesKey.BAD_CHARACTER to TextAttributes(0xFFFF6B6BL, background = 0xFF3A1414L),
            ),
        )

        val LIGHT: ColorScheme = ColorScheme(
            "Stride Light",
            mapOf(
                TextAttributesKey.DEFAULT_TEXT to TextAttributes(LIGHT_TEXT, 0xFFFFFFFFL),
                TextAttributesKey.KEYWORD to TextAttributes(LIGHT_KEYWORD, bold = true),
                TextAttributesKey.STRING to TextAttributes(LIGHT_STRING),
                TextAttributesKey.CHARACTER to TextAttributes(LIGHT_STRING),
                TextAttributesKey.NUMBER to TextAttributes(LIGHT_NUMBER),
                TextAttributesKey.LINE_COMMENT to TextAttributes(LIGHT_COMMENT, italic = true),
                TextAttributesKey.BLOCK_COMMENT to TextAttributes(LIGHT_COMMENT, italic = true),
                TextAttributesKey.IDENTIFIER to TextAttributes(LIGHT_TEXT),
                TextAttributesKey.OPERATION_SIGN to TextAttributes(LIGHT_KEYWORD),
                TextAttributesKey.BRACES to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.BRACKETS to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.PARENTHESES to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.COMMA to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.DOT to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.SEMICOLON to TextAttributes(LIGHT_PUNCTUATION),
                TextAttributesKey.BAD_CHARACTER to TextAttributes(LIGHT_BAD, background = 0xFFFFE4E1L),
            ),
        )
    }
}
