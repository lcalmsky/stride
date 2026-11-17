package stride.lang.kotlin

import stride.lang.TokenType

/** A lexer token with absolute offsets; string-template code is unfolded into its own tokens. */
internal data class CodeToken(val type: TokenType, val start: Int, val end: Int, val text: String)

internal fun codeTokens(text: String): List<CodeToken> = lexCode(text, 0)

private fun lexCode(text: String, base: Int): List<CodeToken> {
    val lexer = KotlinLexer()
    lexer.start(text, 0, text.length, 0)
    val raw = ArrayList<CodeToken>()
    while (true) {
        val type = lexer.getTokenType() ?: break
        val start = lexer.getTokenStart()
        val end = lexer.getTokenEnd()
        raw.add(CodeToken(type, base + start, base + end, text.substring(start, end)))
        lexer.advance()
    }
    val result = ArrayList<CodeToken>(raw.size)
    var index = 0
    while (index < raw.size) {
        val token = raw[index]
        if (token.type == KotlinTokenTypes.STRING && token.text.endsWith("\${")) {
            val localStart = token.end - base
            val localClose = templateClose(text, localStart)
            if (localClose >= 0) {
                result.add(token)
                result.addAll(lexCode(text.substring(localStart, localClose), base + localStart))
                val localResume = localClose + 1
                index++
                while (index < raw.size && raw[index].start - base < localResume) index++
                continue
            }
        }
        result.add(token)
        index++
    }
    return result
}

/** The index of the `}` closing the template opened just before [from], or `-1`. */
private fun templateClose(text: String, from: Int): Int {
    var depth = 0
    var index = from
    while (index < text.length) {
        when (text[index]) {
            '{' -> {
                depth++
                index++
            }
            '}' -> {
                if (depth == 0) return index
                depth--
                index++
            }
            '"' -> index = skipString(text, index)
            '\'' -> index = skipChar(text, index)
            '/' -> {
                val next = text.getOrNull(index + 1)
                index = when (next) {
                    '/' -> {
                        var end = index
                        while (end < text.length && text[end] != '\n') end++
                        end
                    }
                    '*' -> skipBlockComment(text, index)
                    else -> index + 1
                }
            }
            else -> index++
        }
    }
    return -1
}

private fun skipString(text: String, start: Int): Int {
    if (text.startsWith("\"\"\"", start)) {
        val close = text.indexOf("\"\"\"", start + 3)
        return if (close < 0) text.length else close + 3
    }
    var index = start + 1
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index += 2
            '"' -> return index + 1
            '\n' -> return index
            else -> index++
        }
    }
    return text.length
}

private fun skipChar(text: String, start: Int): Int {
    var index = start + 1
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index += 2
            '\'' -> return index + 1
            '\n' -> return index
            else -> index++
        }
    }
    return text.length
}

private fun skipBlockComment(text: String, start: Int): Int {
    var depth = 0
    var index = start
    while (index < text.length) {
        when {
            text.startsWith("/*", index) -> {
                depth++
                index += 2
            }
            text.startsWith("*/", index) -> {
                depth--
                index += 2
                if (depth == 0) return index
            }
            else -> index++
        }
    }
    return text.length
}
