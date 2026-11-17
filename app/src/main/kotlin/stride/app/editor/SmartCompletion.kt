package stride.app.editor

/** What the caret's identifier prefix is expected to name. */
internal enum class CompletionPosition { TYPE, EXPRESSION }

/**
 * Judges from the code before the identifier prefix whether a type or an expression belongs
 * there: a type after `:`, `as`, `is` and inside generic arguments, an expression elsewhere.
 */
internal object SmartCompletion {
    fun positionAt(text: String, caret: Int): CompletionPosition {
        val prefixStart = EditActions.identifierPrefix(text, caret).first
        val scan = CodeScan(text)
        var index = prefixStart - 1
        while (index >= 0 && text[index].isWhitespace()) index--
        if (index < 0 || !scan.isCode(index)) return CompletionPosition.EXPRESSION
        return when (text[index]) {
            '<' -> if (isGenericOpen(text, index)) CompletionPosition.TYPE else CompletionPosition.EXPRESSION
            ',' -> if (insideOpenAngle(text, index, scan)) CompletionPosition.TYPE else CompletionPosition.EXPRESSION
            ':' -> if (index > 0 && text[index - 1] in "?:") {
                CompletionPosition.EXPRESSION
            } else {
                CompletionPosition.TYPE
            }
            '?' -> if (wordBefore(text, index) == "as") CompletionPosition.TYPE else CompletionPosition.EXPRESSION
            else -> if (wordBefore(text, index + 1) in TYPE_WORDS) CompletionPosition.TYPE else CompletionPosition.EXPRESSION
        }
    }

    private val TYPE_WORDS = setOf("as", "is")

    private fun wordBefore(text: String, end: Int): String {
        var last = end - 1
        while (last >= 0 && text[last].isWhitespace()) last--
        if (last < 0 || !isIdentifierPart(text[last])) return ""
        var start = last
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        return text.substring(start, last + 1)
    }

    /** Whether the `,` at [index] sits inside a `<` that is still open in the same statement. */
    private fun insideOpenAngle(text: String, index: Int, scan: CodeScan): Boolean {
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            if (scan.isCode(i)) {
                when (text[i]) {
                    '>' -> if (i == 0 || text[i - 1] != '-') depth++
                    '<' -> if (depth > 0) depth-- else return isGenericOpen(text, i)
                    '(', ')', '{', '}', ';' -> return false
                }
            }
            i--
        }
        return false
    }

    /** A `<` right after an identifier character or `>` opens generic arguments; after a space it compares. */
    private fun isGenericOpen(text: String, index: Int): Boolean =
        index > 0 && (isIdentifierPart(text[index - 1]) || text[index - 1] == '>')

    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
