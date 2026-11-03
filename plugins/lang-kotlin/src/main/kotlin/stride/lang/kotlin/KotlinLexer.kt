package stride.lang.kotlin

import stride.lang.Lexer
import stride.lang.TokenType

class KotlinLexer : Lexer {
    private var text: CharSequence = ""
    private var limit = 0
    private var pos = 0
    private var state = STATE_NORMAL
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenState = STATE_NORMAL
    private var nextState = STATE_NORMAL
    private var tokenType: TokenType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        text = buffer
        limit = endOffset
        pos = startOffset.coerceIn(0, endOffset)
        state = initialState
        tokenType = null
        tokenStart = pos
        tokenEnd = pos
        tokenState = state
        nextState = state
        skipIgnored()
        scan()
    }

    override fun getState(): Int = tokenState

    override fun getTokenType(): TokenType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        if (tokenType == null) return
        pos = tokenEnd
        state = nextState
        skipIgnored()
        tokenStart = pos
        tokenEnd = pos
        tokenState = state
        nextState = state
        tokenType = null
        if (pos < limit) scan()
    }

    private fun skipIgnored() {
        while (pos < limit) {
            val c = text[pos]
            if (c == '\n' || c == '\r') {
                pos++
                continue
            }
            if (state == STATE_NORMAL && c.isWhitespace()) {
                pos++
                continue
            }
            break
        }
    }

    private fun scan() {
        tokenStart = pos
        tokenState = state
        tokenEnd = pos
        tokenType = null
        nextState = state
        if (pos >= limit) return
        when {
            state == STATE_RAW_STRING -> scanRawStringContent()
            state == STATE_STRING -> scanRegularStringContent()
            state >= STATE_TEMPLATE -> scanTemplateContent(state)
            state in STATE_BLOCK_COMMENT until STATE_RAW_STRING -> scanBlockCommentContent(state)
            else -> scanNormal()
        }
    }

    private fun scanNormal() {
        val c = text[pos]
        when {
            c == '/' && peek(pos + 1) == '/' -> scanLineComment()
            c == '/' && peek(pos + 1) == '*' -> scanBlockCommentStart()
            c == '"' -> {
                if (pos + 3 <= limit && text.startsWith("\"\"\"", pos)) scanRawStringStart()
                else scanRegularStringStart()
            }
            c == '\'' -> scanCharLiteral()
            c.isDigit() -> scanNumber()
            c == '`' -> scanBacktickIdentifier()
            isIdentifierStart(c) -> scanIdentifier()
            c == '{' -> emit(KotlinTokenTypes.BRACES, pos + 1, STATE_NORMAL)
            c == '}' -> emit(KotlinTokenTypes.BRACES, pos + 1, STATE_NORMAL)
            c == '[' -> emit(KotlinTokenTypes.BRACKETS, pos + 1, STATE_NORMAL)
            c == ']' -> emit(KotlinTokenTypes.BRACKETS, pos + 1, STATE_NORMAL)
            c == '(' -> emit(KotlinTokenTypes.PARENTHESES, pos + 1, STATE_NORMAL)
            c == ')' -> emit(KotlinTokenTypes.PARENTHESES, pos + 1, STATE_NORMAL)
            c == ',' -> emit(KotlinTokenTypes.COMMA, pos + 1, STATE_NORMAL)
            c == ';' -> emit(KotlinTokenTypes.SEMICOLON, pos + 1, STATE_NORMAL)
            c == '.' -> scanDot()
            c in OPERATOR_CHARS -> scanOperator()
            else -> emit(KotlinTokenTypes.BAD_CHARACTER, pos + 1, STATE_NORMAL)
        }
    }

    private fun scanLineComment() {
        var i = pos + 2
        while (i < limit && text[i] != '\n' && text[i] != '\r') i++
        emit(KotlinTokenTypes.LINE_COMMENT, i, STATE_NORMAL)
    }

    private fun scanBlockCommentStart() {
        var i = pos + 2
        var depth = 1
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') break
            if (ch == '/' && peek(i + 1) == '*') {
                depth++
                i += 2
                continue
            }
            if (ch == '*' && peek(i + 1) == '/') {
                depth--
                i += 2
                if (depth == 0) {
                    emit(KotlinTokenTypes.BLOCK_COMMENT, i, STATE_NORMAL)
                    return
                }
                continue
            }
            i++
        }
        emit(KotlinTokenTypes.BLOCK_COMMENT, i, blockCommentState(depth))
    }

    private fun scanBlockCommentContent(state: Int) {
        var depth = state - STATE_BLOCK_COMMENT + 1
        var i = pos
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') break
            if (ch == '/' && peek(i + 1) == '*') {
                depth++
                i += 2
                continue
            }
            if (ch == '*' && peek(i + 1) == '/') {
                depth--
                i += 2
                if (depth == 0) {
                    emit(KotlinTokenTypes.BLOCK_COMMENT, i, STATE_NORMAL)
                    return
                }
                continue
            }
            i++
        }
        emit(KotlinTokenTypes.BLOCK_COMMENT, i, blockCommentState(depth))
    }

    private fun scanRawStringStart() {
        var i = pos + 3
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                emit(KotlinTokenTypes.STRING, i, STATE_RAW_STRING)
                return
            }
            if (ch == '$' && peek(i + 1) == '{') {
                emit(KotlinTokenTypes.STRING, i + 2, templateState(1, raw = true))
                return
            }
            if (i + 3 <= limit && text.startsWith("\"\"\"", i)) {
                emit(KotlinTokenTypes.STRING, i + 3, STATE_NORMAL)
                return
            }
            i++
        }
        emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
    }

    private fun scanRawStringContent() {
        var i = pos
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                emit(KotlinTokenTypes.STRING, i, STATE_RAW_STRING)
                return
            }
            if (ch == '$' && peek(i + 1) == '{') {
                emit(KotlinTokenTypes.STRING, i + 2, templateState(1, raw = true))
                return
            }
            if (i + 3 <= limit && text.startsWith("\"\"\"", i)) {
                emit(KotlinTokenTypes.STRING, i + 3, STATE_NORMAL)
                return
            }
            i++
        }
        emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
    }

    private fun scanRegularStringStart() {
        var i = pos + 1
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
                return
            }
            if (ch == '\\') {
                i = if (i + 1 < limit && text[i + 1] != '\n' && text[i + 1] != '\r') i + 2 else i + 1
                continue
            }
            if (ch == '"') {
                emit(KotlinTokenTypes.STRING, i + 1, STATE_NORMAL)
                return
            }
            if (ch == '$' && peek(i + 1) == '{') {
                emit(KotlinTokenTypes.STRING, i + 2, templateState(1, raw = false))
                return
            }
            i++
        }
        emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
    }

    private fun scanRegularStringContent() {
        var i = pos
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
                return
            }
            if (ch == '\\') {
                i = if (i + 1 < limit && text[i + 1] != '\n' && text[i + 1] != '\r') i + 2 else i + 1
                continue
            }
            if (ch == '"') {
                emit(KotlinTokenTypes.STRING, i + 1, STATE_NORMAL)
                return
            }
            if (ch == '$' && peek(i + 1) == '{') {
                emit(KotlinTokenTypes.STRING, i + 2, templateState(1, raw = false))
                return
            }
            i++
        }
        emit(KotlinTokenTypes.STRING, i, STATE_NORMAL)
    }

    private fun scanTemplateContent(state: Int) {
        val raw = ((state - STATE_TEMPLATE) and 1) == 1
        var depth = (state - STATE_TEMPLATE) / 2
        var i = pos
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                emit(KotlinTokenTypes.STRING, i, templateState(depth, raw))
                return
            }
            if (ch == '{') {
                depth++
                i++
                continue
            }
            if (ch == '}') {
                depth--
                i++
                if (depth == 0) {
                    emit(KotlinTokenTypes.STRING, i, if (raw) STATE_RAW_STRING else STATE_STRING)
                    return
                }
                continue
            }
            i++
        }
        emit(KotlinTokenTypes.STRING, i, if (raw) STATE_RAW_STRING else STATE_STRING)
    }

    private fun scanCharLiteral() {
        var i = pos + 1
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') break
            if (ch == '\\') {
                i = if (i + 1 < limit && text[i + 1] != '\n' && text[i + 1] != '\r') i + 2 else i + 1
                continue
            }
            if (ch == '\'') {
                i++
                break
            }
            i++
        }
        emit(KotlinTokenTypes.CHARACTER, i, STATE_NORMAL)
    }

    private fun scanNumber() {
        var i = pos
        if (i + 1 < limit && text[i] == '0' && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
            i += 2
            while (i < limit && (text[i].isHexDigit() || text[i] == '_')) i++
            if (i < limit && (text[i] == 'L' || text[i] == 'l')) i++
            emit(KotlinTokenTypes.NUMBER, i, STATE_NORMAL)
            return
        }
        if (i + 1 < limit && text[i] == '0' && (text[i + 1] == 'b' || text[i + 1] == 'B')) {
            i += 2
            while (i < limit && (text[i] == '0' || text[i] == '1' || text[i] == '_')) i++
            if (i < limit && (text[i] == 'L' || text[i] == 'l')) i++
            emit(KotlinTokenTypes.NUMBER, i, STATE_NORMAL)
            return
        }
        while (i < limit && (text[i].isDigit() || text[i] == '_')) i++
        var isFloat = false
        if (i + 1 < limit && text[i] == '.' && text[i + 1].isDigit()) {
            isFloat = true
            i++
            while (i < limit && (text[i].isDigit() || text[i] == '_')) i++
        }
        if (i < limit && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < limit && (text[j] == '+' || text[j] == '-')) j++
            if (j < limit && text[j].isDigit()) {
                isFloat = true
                i = j
                while (i < limit && (text[i].isDigit() || text[i] == '_')) i++
            }
        }
        if (i < limit && (text[i] == 'f' || text[i] == 'F')) {
            isFloat = true
            i++
        } else if (!isFloat && i < limit && (text[i] == 'L' || text[i] == 'l')) {
            i++
        }
        emit(KotlinTokenTypes.NUMBER, i, STATE_NORMAL)
    }

    private fun scanBacktickIdentifier() {
        var i = pos + 1
        while (i < limit && text[i] != '`' && text[i] != '\n' && text[i] != '\r') i++
        if (i < limit && text[i] == '`') i++
        emit(KotlinTokenTypes.IDENTIFIER, i, STATE_NORMAL)
    }

    private fun scanIdentifier() {
        var i = pos + 1
        while (i < limit && isIdentifierPart(text[i])) i++
        val word = text.subSequence(pos, i).toString()
        emit(if (word in KEYWORDS) KotlinTokenTypes.KEYWORD else KotlinTokenTypes.IDENTIFIER, i, STATE_NORMAL)
    }

    private fun scanDot() {
        if (peek(pos + 1) == '.') {
            var i = pos + 1
            while (i < limit && text[i] == '.') i++
            emit(KotlinTokenTypes.OPERATION_SIGN, i, STATE_NORMAL)
        } else {
            emit(KotlinTokenTypes.DOT, pos + 1, STATE_NORMAL)
        }
    }

    private fun scanOperator() {
        var i = pos + 1
        while (i < limit && text[i] in OPERATOR_CHARS) i++
        emit(KotlinTokenTypes.OPERATION_SIGN, i, STATE_NORMAL)
    }

    // The state saturates so that every mode fits in a single Int.
    private fun blockCommentState(depth: Int): Int =
        STATE_BLOCK_COMMENT + (depth - 1).coerceIn(0, MAX_BLOCK_COMMENT_DEPTH - 1)

    private fun templateState(depth: Int, raw: Boolean): Int =
        STATE_TEMPLATE + depth * 2 + if (raw) 1 else 0

    private fun emit(type: TokenType, end: Int, next: Int) {
        tokenType = type
        tokenEnd = end.coerceAtMost(limit)
        nextState = next
    }

    private fun peek(index: Int): Char = if (index in 0 until limit) text[index] else '\u0000'

    private fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    private fun Char.isHexDigit(): Boolean =
        isDigit() || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val STATE_NORMAL = 0
        const val STATE_BLOCK_COMMENT = 1
        const val MAX_BLOCK_COMMENT_DEPTH = 0x0FFF
        const val STATE_RAW_STRING = 0x1000
        const val STATE_STRING = 0x2000
        const val STATE_TEMPLATE = 0x4000

        const val OPERATOR_CHARS = "+-*/%=!<>&|^~?:@#"

        val KEYWORDS: Set<String> = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
            "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally", "get",
            "import", "init", "param", "property", "receiver", "set", "setparam", "where",
            "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum",
            "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
            "noinline", "open", "operator", "out", "override", "private", "protected", "public",
            "reified", "sealed", "suspend", "tailrec", "vararg", "value", "context",
        )
    }
}
