package stride.lang.json

import stride.lang.Lexer
import stride.lang.TokenType

class JsonLexer : Lexer {
    private var text: CharSequence = ""
    private var limit = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: TokenType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        text = buffer
        limit = endOffset
        pos = startOffset.coerceIn(0, endOffset)
        tokenType = null
        tokenStart = pos
        tokenEnd = pos
        skipWhitespace()
        scan()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): TokenType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        if (tokenType == null) return
        pos = tokenEnd
        skipWhitespace()
        tokenStart = pos
        tokenEnd = pos
        tokenType = null
        if (pos < limit) scan()
    }

    private fun skipWhitespace() {
        while (pos < limit && text[pos].isWhitespace()) pos++
    }

    private fun scan() {
        tokenStart = pos
        tokenEnd = pos
        tokenType = null
        if (pos >= limit) return
        when (val c = text[pos]) {
            '{', '}' -> emit(JsonTokenTypes.BRACES, pos + 1)
            '[', ']' -> emit(JsonTokenTypes.BRACKETS, pos + 1)
            ',' -> emit(JsonTokenTypes.COMMA, pos + 1)
            ':' -> emit(JsonTokenTypes.OPERATION_SIGN, pos + 1)
            '"' -> scanString()
            '-' -> scanNumberOrMinus()
            else -> when {
                c.isDigit() -> scanNumber()
                c.isLetter() -> scanWord()
                else -> emit(JsonTokenTypes.BAD_CHARACTER, pos + 1)
            }
        }
    }

    private fun scanString() {
        var i = pos + 1
        while (i < limit) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') break
            if (ch == '\\') {
                i = (i + 2).coerceAtMost(limit)
                continue
            }
            if (ch == '"') {
                i++
                break
            }
            i++
        }
        emit(JsonTokenTypes.STRING, i)
    }

    private fun scanNumberOrMinus() {
        if (pos + 1 >= limit || !text[pos + 1].isDigit()) {
            emit(JsonTokenTypes.BAD_CHARACTER, pos + 1)
            return
        }
        scanNumber()
    }

    private fun scanNumber() {
        var i = pos + 1
        while (i < limit && text[i].isDigit()) i++
        if (i + 1 < limit && text[i] == '.' && text[i + 1].isDigit()) {
            i++
            while (i < limit && text[i].isDigit()) i++
        }
        if (i < limit && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < limit && (text[j] == '+' || text[j] == '-')) j++
            if (j < limit && text[j].isDigit()) {
                i = j
                while (i < limit && text[i].isDigit()) i++
            }
        }
        emit(JsonTokenTypes.NUMBER, i)
    }

    private fun scanWord() {
        var i = pos + 1
        while (i < limit && text[i].isLetter()) i++
        val word = text.subSequence(pos, i).toString()
        val type = if (word == "true" || word == "false" || word == "null") {
            JsonTokenTypes.KEYWORD
        } else {
            JsonTokenTypes.BAD_CHARACTER
        }
        emit(type, i)
    }

    private fun emit(type: TokenType, end: Int) {
        tokenType = type
        tokenEnd = end.coerceAtMost(limit)
    }
}
