package stride.search

/**
 * One call of a name in a file's text. [start] until [end] is the name. [receiver] is `null` for an
 * unqualified call, else the expression before the `.`: `this`, `super`, a simple name, or `"?"` for
 * anything longer (a call result, an index, a chain). [isReference] marks a `::name` callable
 * reference, whose [receiver] is the name before `::` or `null`.
 */
data class CallSite(
    val name: String,
    val start: Int,
    val end: Int,
    val receiver: String? = null,
    val isReference: Boolean = false,
)

/**
 * Finds calls by text in Kotlin and Java sources, outside comments, strings and characters. A call
 * is an identifier followed, after optional spaces on the same line, by `(`, by balanced type
 * arguments and `(`, or in Kotlin by a trailing lambda `{`; or an identifier after `::`. The `{` of
 * a `by <expression>` in a class header opens the class body, not a call. Keywords (`if`, `when`,
 * `for`, `while`, `catch`, `return`, `super`, `this`, `object`, ...), annotations (`@Name(`), Java
 * `new` and the names of declarations themselves (`fun name(`, `class Name(`, a property accessor
 * `get()`/`set()` after modifiers only, a Java method or constructor header even when the outline
 * misses it, `constructor(`) are not calls. No types are resolved here.
 */
object CallSites {
    /** The calls of [name] in [text], in text order. */
    fun find(fileName: String, text: String, name: String): List<CallSite> =
        inRange(fileName, text, 0, text.length).filter { it.name == name }

    /** Every call inside `[start, end)` of [text], in text order. */
    fun inRange(fileName: String, text: String, start: Int, end: Int): List<CallSite> {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kotlin = extension == DeclarationParser.KOTLIN_EXTENSIONS ||
            extension == DeclarationParser.KOTLIN_SCRIPT_EXTENSION
        if (!kotlin && extension != DeclarationParser.JAVA_EXTENSION) return emptyList()
        val sanitized = sanitizeSource(text)
        val declarationNames = if (kotlin) {
            emptySet()
        } else {
            DeclarationParser.outline(fileName, text).map { it.nameStart }.toSet()
        }
        val calls = ArrayList<CallSite>()
        for (match in IDENTIFIER.findAll(sanitized)) {
            val siteStart = match.range.first
            val siteEnd = match.range.last + 1
            if (siteStart < start || siteEnd > end) continue
            if (siteStart > 0 && isIdentifierChar(sanitized[siteStart - 1])) continue
            val written = match.value
            val name = DeclarationParser.unquote(written)
            if (name.isEmpty() || name in KEYWORDS) continue
            val preceding = sanitized.getOrNull(siteStart - 1)
            if (preceding == '@') continue
            if (preceding == ':' && sanitized.getOrNull(siteStart - 2) == ':') {
                val receiver = receiverBefore(siteStart - 3, sanitized)
                calls.add(CallSite(name, siteStart, siteEnd, receiver.takeUnless { it == "?" }, isReference = true))
                continue
            }
            if (!isCallAt(sanitized, siteStart, siteEnd, kotlin)) continue
            if (siteStart in declarationNames || isDeclarationName(sanitized, siteStart, name, kotlin)) continue
            if (isAfterNew(sanitized, siteStart)) continue
            calls.add(CallSite(name, siteStart, siteEnd, receiverFor(siteStart - 1, sanitized)))
        }
        return calls
    }

    private fun isCallAt(text: String, start: Int, end: Int, kotlin: Boolean): Boolean {
        var i = end
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length) return false
        when {
            text[i] == '(' -> return true
            text[i] == '<' && i == end -> {
                val close = typeArgumentEnd(text, i) ?: return false
                var after = close + 1
                while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
                return after < text.length && text[after] == '('
            }
            // In a class header the `{` after `by <expression>` opens the class body.
            kotlin && text[i] == '{' -> return !isClassHeader(text, start)
        }
        return false
    }

    /** `true` when [offset] is on the first or a continued line of a Kotlin class header. */
    private fun isClassHeader(text: String, offset: Int): Boolean {
        var start = lineStartOf(text, offset)
        while (start > 0) {
            val previousEnd = start - 1
            val previousStart = lineStartOf(text, previousEnd)
            val previous = text.substring(previousStart, previousEnd).trim()
            if (!previous.endsWith(",") && !previous.endsWith(":") && !previous.endsWith("where") &&
                !ANNOTATION_LINE.matches(previous)
            ) {
                break
            }
            start = previousStart
        }
        val header = text.substring(start, lineEndOf(text, start)).trim()
        return CLASS_HEADER.containsMatchIn(header)
    }

    private fun lineStartOf(text: String, offset: Int): Int =
        (text.lastIndexOf('\n', offset - 1) + 1).coerceAtLeast(0)

    private fun lineEndOf(text: String, start: Int): Int {
        val end = text.indexOf('\n', start)
        return if (end < 0) text.length else end
    }

    /** The index of the `>` that closes a type argument list opened at [start], on the same line. */
    private fun typeArgumentEnd(text: String, start: Int): Int? {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '\n' -> return null
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * The receiver written before the `.` at [dot], where [dot] is the index of the `.` (or of the
     * `?` of a `?.`).
     */
    private fun receiverFor(dot: Int, text: String): String? {
        if (dot < 0 || text.getOrNull(dot) != '.') return null
        val safeCall = text.getOrNull(dot - 1) == '?'
        val expressionEnd = if (safeCall) dot - 2 else dot - 1
        return receiverBefore(expressionEnd, text)
    }

    /**
     * The receiver expression that ends at [end]: a simple identifier whose own first character is
     * not itself preceded by `.`, `)`, `]` or another chain marker, else `"?"`.
     */
    private fun receiverBefore(end: Int, text: String): String {
        if (end < 0) return "?"
        if (!isIdentifierChar(text[end])) return "?"
        var begin = end
        while (begin > 0 && isIdentifierChar(text[begin - 1])) begin--
        val token = text.substring(begin, end + 1)
        return when (text.getOrNull(begin - 1)) {
            '.', ')', ']', '?', '!' -> "?"
            else -> DeclarationParser.unquote(token)
        }
    }

    private fun isAfterNew(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        var wordStart = i
        while (wordStart >= 0 && isIdentifierChar(text[wordStart])) wordStart--
        return text.substring(wordStart + 1, i + 1) == "new"
    }

    private fun isDeclarationName(text: String, start: Int, name: String, kotlin: Boolean): Boolean {
        var boundary = 0
        for (i in start - 1 downTo 0) {
            when (text[i]) {
                '{', '}', ';', '\n' -> {
                    boundary = i + 1
                    break
                }
            }
        }
        val prefix = text.substring(boundary, start)
        if (kotlin) {
            if (KOTLIN_DECLARATION_PREFIX.containsMatchIn(prefix.trim())) return true
            if ((name == "get" || name == "set") && ACCESSOR_PREFIX.matches(prefix.trim())) return true
            val funIndex = lastWordIndex(prefix, "fun")
            if (funIndex >= 0) {
                val after = prefix.substring(funIndex + 3).trim()
                if (FUN_AFTER.containsMatchIn(after)) return true
            }
        } else {
            if (JAVA_DECLARATION_PREFIX.containsMatchIn(prefix.trim())) return true
            if (isJavaDeclarationHead(text, boundary, start)) return true
        }
        return false
    }

    /**
     * `true` when the Java name at [start] heads a method or constructor declaration: the whole
     * head before it is a written declaration, not an expression.
     */
    private fun isJavaDeclarationHead(text: String, boundary: Int, start: Int): Boolean {
        val end = lineEndOf(text, start)
        val segment = text.substring(boundary, end)
        val localStart = start - boundary
        for (match in listOf(JAVA_METHOD.find(segment), JAVA_METHOD_START.find(segment))) {
            if (match == null) continue
            if (match.groups[2]!!.range.first != localStart) continue
            if (match.groupValues[1] in EXPRESSION_WORDS) continue
            return true
        }
        return CONSTRUCTOR_HEAD.matches(segment.substring(0, localStart))
    }

    private fun lastWordIndex(text: String, word: String): Int {
        var result = -1
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + word.length)
            if ((before == null || !isIdentifierChar(before)) && (after == null || !isIdentifierChar(after))) {
                result = index
            }
            index = text.indexOf(word, index + 1)
        }
        return result
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private val IDENTIFIER = Regex("""`[^`]+`|[A-Za-z_$][\w$]*""")

    private val KEYWORDS = setOf(
        "if", "when", "for", "while", "do", "catch", "try", "return", "throw", "super", "this",
        "object", "fun", "class", "interface", "constructor", "init", "else", "in", "is", "as",
        "typeof", "switch", "synchronized", "new", "assert", "sizeof", "case", "default", "finally",
        "break", "continue", "package", "import", "val", "var", "typealias", "static",
    )

    private val FUN_AFTER = Regex("""^(?:<[^>]*>\s*)?(?:[\w$.<>?]+\s*\.\s*)?$""")

    private val KOTLIN_DECLARATION_PREFIX = Regex(
        """^(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:(?:public|private|protected|internal|open|abstract|final|sealed|""" +
            """data|enum|annotation|value|inner|inline|companion|expect|actual|external)\s+)*""" +
            """(?:class|interface|object|typealias)\s*$"""
    )

    private val JAVA_DECLARATION_PREFIX = Regex(
        """^(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:(?:public|private|protected|static|final|abstract|sealed|""" +
            """non-sealed|strictfp)\s+)*(?:class|interface|enum|record)\s*$"""
    )

    private val ACCESSOR_PREFIX = Regex(
        """^(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:(?:public|private|protected|internal|inline|override|""" +
            """external|open|final|abstract)\b\s*)*$"""
    )

    private val CLASS_HEADER = Regex(
        """^(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:(?:public|private|protected|internal|open|abstract|final|""" +
            """sealed|data|enum|annotation|value|inner|inline|companion|expect|actual|external)\s+)*""" +
            """(?:class|interface|object)\s+"""
    )

    private val ANNOTATION_LINE = Regex("""^(?:@[\w$.]+(?:\([^()]*\))?\s*)+$""")

    private val CONSTRUCTOR_HEAD = Regex(
        """^(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:(?:public|private|protected)\s+)+$"""
    )

    private val EXPRESSION_WORDS = setOf(
        "return", "new", "throw", "else", "case", "yield", "assert", "if", "for", "while", "do",
        "switch", "try", "catch", "synchronized", "instanceof",
    )
}
