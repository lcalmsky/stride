package stride.lang.kotlin

import stride.lang.TokenType

/** A lexer token with absolute offsets; string-template code is unfolded into its own tokens. */
internal data class CodeToken(val type: TokenType, val start: Int, val end: Int, val text: String)

internal fun codeTokens(text: String): List<CodeToken> = lexTokens(text, 0).map { it.token }

/** A lexer token plus the lexer state it started in, which tells regular and raw string bodies apart. */
private class LexedToken(val token: CodeToken, val state: Int)

private fun lexTokens(text: String, base: Int): List<LexedToken> {
    val lexer = KotlinLexer()
    lexer.start(text, 0, text.length, 0)
    val raw = ArrayList<LexedToken>()
    while (true) {
        val type = lexer.getTokenType() ?: break
        val start = lexer.getTokenStart()
        val end = lexer.getTokenEnd()
        raw.add(LexedToken(CodeToken(type, base + start, base + end, text.substring(start, end)), lexer.getState()))
        lexer.advance()
    }
    val result = ArrayList<LexedToken>(raw.size)
    var index = 0
    while (index < raw.size) {
        val lexed = raw[index]
        val token = lexed.token
        val rawBody = rawStringBody(lexed)
        if (token.type == KotlinTokenTypes.STRING && token.text.endsWith("\${")) {
            val localStart = token.end - base
            val localClose = templateClose(text, localStart)
            if (localClose >= 0) {
                result.add(lexed)
                if (rawBody != null) {
                    result.addAll(simpleTemplates(token, rawBody).map { LexedToken(it, lexed.state) })
                }
                result.addAll(lexTokens(text.substring(localStart, localClose), base + localStart))
                val localResume = localClose + 1
                index++
                while (index < raw.size && raw[index].token.start - base < localResume) index++
                continue
            }
        }
        if (rawBody != null) {
            result.add(lexed)
            result.addAll(simpleTemplates(token, rawBody).map { LexedToken(it, lexed.state) })
            index++
            continue
        }
        result.add(lexed)
        index++
    }
    return result
}

/** `true` for a raw string body, `false` for a regular one, `null` when [lexed] is not a string body. */
private fun rawStringBody(lexed: LexedToken): Boolean? {
    val text = lexed.token.text
    return when {
        lexed.state == KotlinLexer.STATE_RAW_STRING -> true
        lexed.state == KotlinLexer.STATE_STRING -> false
        lexed.state == KotlinLexer.STATE_NORMAL && text.startsWith("\"\"\"") -> true
        lexed.state == KotlinLexer.STATE_NORMAL && text.startsWith("\"") -> false
        else -> null
    }
}

/**
 * The `$name` and ``$`name` `` templates of one string body as identifier tokens. An escaped `\$`
 * in a regular string is not a template; a backslash has no meaning in a raw string.
 */
private fun simpleTemplates(token: CodeToken, raw: Boolean): List<CodeToken> {
    val text = token.text
    val result = ArrayList<CodeToken>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '$' || (!raw && isEscaped(text, index))) {
            index++
            continue
        }
        val nameStart = index + 1
        if (nameStart >= text.length) break
        if (text[nameStart] == '`') {
            var end = nameStart + 1
            while (end < text.length && text[end] != '`' && text[end] != '\n') end++
            if (end >= text.length || text[end] != '`' || end == nameStart + 1) {
                index++
                continue
            }
            result.add(
                CodeToken(
                    KotlinTokenTypes.IDENTIFIER,
                    token.start + nameStart,
                    token.start + end + 1,
                    text.substring(nameStart, end + 1),
                ),
            )
            index = end + 1
            continue
        }
        if (!isIdentifierStart(text[nameStart])) {
            index++
            continue
        }
        var end = nameStart + 1
        while (end < text.length && isIdentifierPart(text[end])) end++
        result.add(
            CodeToken(KotlinTokenTypes.IDENTIFIER, token.start + nameStart, token.start + end, text.substring(nameStart, end)),
        )
        index = end
    }
    return result
}

private fun isEscaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var previous = index - 1
    while (previous >= 0 && text[previous] == '\\') {
        backslashes++
        previous--
    }
    return backslashes % 2 == 1
}

private fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

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
