package stride.search

/** What a declaration's own text says about its type. */
sealed interface DeclaredType {
    /**
     * An explicit type, as written without type arguments or `?`: `x: Foo`, a parameter `x: Foo`, a
     * function's return type `fun f(): Foo`, or a Java `Foo x` / `Foo f(...)`. `x: a.b.Foo<T>?` is
     * `a.b.Foo`.
     */
    data class Explicit(val written: String) : DeclaredType

    /**
     * No explicit type, but the whole initializer is a call `Foo(...)` or `a.Foo(...)` of a name
     * starting with an upper-case letter. Only the caller can tell a constructor from a function of
     * that name, so it confirms [written] is a class before using it.
     */
    data class ConstructorCall(val written: String) : DeclaredType

    /** Anything else: the type needs inference a compiler would do. [reason] is shown in the status bar. */
    data class Unknown(val reason: String) : DeclaredType
}

/** Reads declared types from Kotlin and Java text without resolving anything. */
object TypeReferences {
    /**
     * The name offset of the nearest declaration of [name] before [offset] that is in scope at
     * [offset] inside the enclosing member ([DeclarationParser.outline]): a local `val`/`var`, a
     * parameter of the enclosing function or of an enclosing lambda (typed or not), a `for` or
     * `catch` variable; in Java a local, parameter, `for` or `catch` variable. A declaration inside a
     * block that has closed before [offset] is out of scope. `null` when there is none, or when
     * [offset] is not inside a member.
     */
    fun localDeclaration(fileName: String, text: String, offset: Int, name: String): Int? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kotlin = extension == DeclarationParser.KOTLIN_EXTENSIONS ||
            extension == DeclarationParser.KOTLIN_SCRIPT_EXTENSION
        if (!kotlin && extension != DeclarationParser.JAVA_EXTENSION) return null
        val member = DeclarationParser.outline(fileName, text).memberAt(offset) ?: return null
        if (offset <= member.nameStart) return null
        val sanitized = sanitizeSource(text)
        val candidates = ArrayList<LocalCandidate>()
        if (kotlin) {
            kotlinLocals(sanitized, member, offset, name, candidates)
        } else {
            javaLocals(sanitized, member, offset, name, candidates)
        }
        return candidates
            .filter { it.nameStart in (member.nameStart + 1) until offset && offset < it.scopeEnd }
            .maxByOrNull { it.nameStart }
            ?.nameStart
    }

    /**
     * The type declared by the declaration whose name starts at [nameStart]: a property, local,
     * parameter, `for` or `catch` variable, or a function (its return type). A function type, a
     * destructuring declaration, an untyped lambda parameter, Java `var` and every initializer
     * other than a [DeclaredType.ConstructorCall] give [DeclaredType.Unknown] with
     * `"Cannot infer the type of <name> without a compiler; declare it explicitly"`.
     */
    fun declaredType(fileName: String, text: String, nameStart: Int): DeclaredType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kotlin = extension == DeclarationParser.KOTLIN_EXTENSIONS ||
            extension == DeclarationParser.KOTLIN_SCRIPT_EXTENSION
        if (!kotlin && extension != DeclarationParser.JAVA_EXTENSION) return unknown("")
        val sanitized = sanitizeSource(text)
        val name = identifierAt(sanitized, nameStart)
        if (name.isEmpty()) return unknown("")
        return if (kotlin) kotlinDeclaredType(sanitized, nameStart, name) else javaDeclaredType(sanitized, nameStart, name)
    }

    private fun kotlinLocals(
        text: String,
        member: OutlineMember,
        offset: Int,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        val regionEnd = offset.coerceAtMost(member.end)
        for (match in KOTLIN_LOCAL.findAll(text.substring(member.start, regionEnd))) {
            if (DeclarationParser.unquote(match.groupValues[1]) != name) continue
            val start = member.start + match.groups[1]!!.range.first
            if (start <= member.nameStart) continue
            candidates.add(LocalCandidate(start, enclosingBlockClose(text, start)))
        }
        if (member.kind == OutlineKind.FUNCTION || member.kind == OutlineKind.CONSTRUCTOR || member.kind == OutlineKind.CLASS) {
            addKotlinParameters(text, member, name, candidates)
        }
        addKotlinLambdas(text, member, offset, name, candidates)
        addKotlinForAndCatch(text, member, offset, name, candidates)
    }

    private fun addKotlinParameters(
        text: String,
        member: OutlineMember,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        val open = parameterListOpen(text, member, kotlin = true) ?: return
        val close = matchingDelimiter(text, open, '(', ')') ?: return
        if (close <= open) return
        val region = text.substring(open + 1, close)
        for (match in KOTLIN_PARAMETER.findAll(region)) {
            if (DeclarationParser.unquote(match.groupValues[1]) != name) continue
            val absolute = open + 1 + match.groups[1]!!.range.first
            if (braceDepth(region, match.range.first) != 0) continue
            candidates.add(LocalCandidate(absolute, member.end))
        }
    }

    private fun addKotlinLambdas(
        text: String,
        member: OutlineMember,
        offset: Int,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        var open = text.indexOf('{', member.start)
        while (open in 0 until offset) {
            val close = matchingDelimiter(text, open, '{', '}')
            if (close != null && offset < close) {
                val arrow = lambdaArrow(text, open, close)
                if (arrow != null) {
                    val params = text.substring(open + 1, arrow)
                    for ((paramName, index) in lambdaParameterNames(params)) {
                        if (paramName == name) {
                            candidates.add(LocalCandidate(open + 1 + index, close))
                        }
                    }
                }
            }
            open = text.indexOf('{', open + 1)
        }
    }

    private fun addKotlinForAndCatch(
        text: String,
        member: OutlineMember,
        offset: Int,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        for (match in KOTLIN_FOR.findAll(text.substring(member.start))) {
            val open = member.start + match.range.first + match.value.indexOf('(')
            val close = matchingDelimiter(text, open, '(', ')') ?: continue
            val inside = text.substring(open + 1, close)
            val inIndex = topLevelWordIndex(inside, "in")
            if (inIndex < 0) continue
            val left = inside.substring(0, inIndex).trim()
            val names = if (left.startsWith("(")) {
                left.trim('(', ')', ' ').split(',').map { it.trim() to left.indexOf(it.trim()) }
            } else {
                listOf(left to inside.indexOf(left))
            }
            for ((candidate, index) in names) {
                if (candidate != name || candidate.isEmpty()) continue
                candidates.add(LocalCandidate(open + 1 + index, blockAfter(text, close, member.end)))
            }
        }
        for (match in KOTLIN_CATCH.findAll(text.substring(member.start))) {
            val open = member.start + match.range.first + match.value.indexOf('(')
            val close = matchingDelimiter(text, open, '(', ')') ?: continue
            val inside = text.substring(open + 1, close)
            val colon = inside.indexOf(':')
            val candidate = (if (colon >= 0) inside.substring(0, colon) else inside).trim()
            if (candidate != name) continue
            val index = inside.indexOf(candidate)
            candidates.add(LocalCandidate(open + 1 + index, blockAfter(text, close, member.end)))
        }
    }

    private fun javaLocals(
        text: String,
        member: OutlineMember,
        offset: Int,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        val regionEnd = offset.coerceAtMost(member.end)
        val region = text.substring(member.start, regionEnd)
        for (match in JAVA_LOCAL.findAll(region)) {
            if (match.groupValues[2] != name) continue
            if (match.groupValues[1] in JAVA_KEYWORDS) continue
            val start = member.start + match.groups[2]!!.range.first
            if (start <= member.nameStart) continue
            candidates.add(LocalCandidate(start, enclosingBlockClose(text, start)))
        }
        if (member.kind == OutlineKind.FUNCTION || member.kind == OutlineKind.CONSTRUCTOR || member.kind == OutlineKind.CLASS) {
            addJavaParameters(text, member, name, candidates)
        }
        for (match in JAVA_FOR_EACH.findAll(region)) {
            if (match.groupValues[2] != name) continue
            val start = member.start + match.groups[2]!!.range.first
            val close = text.indexOf(')', start)
            if (close < 0) continue
            candidates.add(LocalCandidate(start, blockAfter(text, close, member.end)))
        }
        for (match in JAVA_CATCH.findAll(region)) {
            if (match.groupValues[2] != name) continue
            val start = member.start + match.groups[2]!!.range.first
            val close = text.indexOf(')', start)
            if (close < 0) continue
            candidates.add(LocalCandidate(start, blockAfter(text, close, member.end)))
        }
    }

    private fun addJavaParameters(
        text: String,
        member: OutlineMember,
        name: String,
        candidates: MutableList<LocalCandidate>,
    ) {
        val open = parameterListOpen(text, member, kotlin = false) ?: return
        val close = matchingDelimiter(text, open, '(', ')') ?: return
        for (segment in splitParameters(text.substring(open + 1, close))) {
            val match = JAVA_PARAMETER_END.find(segment.value) ?: continue
            if (match.groupValues[1] != name) continue
            candidates.add(LocalCandidate(open + 1 + segment.start + match.groups[1]!!.range.first, member.end))
        }
    }

    private fun kotlinDeclaredType(text: String, nameStart: Int, name: String): DeclaredType {
        val end = nameEndAt(text, nameStart)
        var i = end
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i < text.length && text[i] == '(') {
            val close = matchingDelimiter(text, i, '(', ')') ?: return unknown(name)
            var j = close + 1
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == ':') {
                return explicitOrUnknown(text, j + 1, name)
            }
            return unknown(name)
        }
        return when {
            i < text.length && text[i] == ':' -> explicitOrUnknown(text, i + 1, name)
            i < text.length && text[i] == '=' -> constructorCallOrUnknown(text, i + 1, name)
            else -> unknown(name)
        }
    }

    private fun javaDeclaredType(text: String, nameStart: Int, name: String): DeclaredType {
        var boundary = 0
        var angle = 0
        var paren = 0
        var bracket = 0
        for (i in nameStart - 1 downTo 0) {
            val c = text[i]
            when {
                c == '>' -> angle++
                c == '<' -> angle--
                c == ')' -> paren++
                c == ']' -> bracket++
                angle == 0 && paren == 0 && bracket == 0 &&
                    (c == ';' || c == '{' || c == '}' || c == ',' || c == '\n' || c == '(' || c == '[') -> {
                    boundary = i + 1
                    break
                }
                c == '(' -> if (paren > 0) paren--
                c == '[' -> if (bracket > 0) bracket--
            }
        }
        val prefix = stripJavaPrefix(text.substring(boundary, nameStart))
        if (prefix.isEmpty()) return unknown(name)
        val cleaned = cleanTypeName(prefix).removeSuffix("...")
        if (cleaned.isEmpty() || cleaned == "var") return unknown(name)
        return DeclaredType.Explicit(cleaned)
    }

    private fun explicitOrUnknown(text: String, typeStart: Int, name: String): DeclaredType {
        val typeText = readKotlinType(text, typeStart)
        if (typeText.contains("->")) return unknown(name)
        val cleaned = cleanTypeName(stripLeadingAnnotations(typeText))
        if (cleaned.isEmpty()) return unknown(name)
        return DeclaredType.Explicit(cleaned)
    }

    private fun constructorCallOrUnknown(text: String, expressionStart: Int, name: String): DeclaredType {
        val end = expressionEnd(text, expressionStart)
        val expression = text.substring(expressionStart, end).trim().removeSuffix(";").trimEnd()
        val match = CONSTRUCTOR_CALL.find(expression) ?: return unknown(name)
        val written = match.groupValues[1]
        val open = match.range.last
        if (matchingDelimiter(expression, open, '(', ')') != expression.lastIndex) return unknown(name)
        if (written.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return unknown(name)
        return DeclaredType.ConstructorCall(written)
    }

    private fun readKotlinType(text: String, start: Int): String {
        var angle = 0
        var paren = 0
        var bracket = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> {
                    if (angle == 0 && paren == 0 && bracket == 0) return text.substring(start, i)
                    if (paren > 0) paren--
                }
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '=', ',', '{', '}', ';', '\n' -> if (angle == 0 && paren == 0 && bracket == 0) {
                    return text.substring(start, i)
                }
            }
            i++
        }
        return text.substring(start)
    }

    /** The `(` of the parameter list a member declares, skipping a class header's `:` and body. */
    private fun parameterListOpen(text: String, member: OutlineMember, kotlin: Boolean): Int? {
        var i = member.nameEnd
        var angle = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '<' -> angle++
                c == '>' -> if (angle > 0) angle--
                angle == 0 && c == '(' -> return i
                angle == 0 && (c == ':' || c == '{' || c == ';' || c == '\n') -> return null
                angle == 0 && !kotlin && (matchesWordAtLocal(text, i, "extends") || matchesWordAtLocal(text, i, "implements")) -> return null
            }
            i++
        }
        return null
    }

    private fun matchesWordAtLocal(text: String, index: Int, word: String): Boolean {
        if (!text.startsWith(word, index)) return false
        val before = text.getOrNull(index - 1) ?: ' '
        val after = text.getOrNull(index + word.length) ?: ' '
        return !before.isLetterOrDigit() && before != '_' && after.isLetterOrDigit().not() && after != '_'
    }

    private fun expressionEnd(text: String, start: Int): Int {
        var paren = 0
        var angle = 0
        var bracket = 0
        var brace = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '{' -> brace++
                '}' -> if (brace > 0) brace--
                ';' -> if (paren == 0 && angle == 0 && bracket == 0) return i
                '\n' -> if (paren == 0 && angle == 0 && bracket == 0 && brace == 0) return i
            }
            i++
        }
        return text.length
    }

    private fun stripJavaPrefix(prefix: String): String {
        var rest = stripLeadingAnnotations(prefix)
        while (true) {
            val match = JAVA_PREFIX_MODIFIER.find(rest) ?: break
            rest = stripLeadingAnnotations(rest.substring(match.range.last + 1).trimStart())
        }
        return rest.trim()
    }

    private fun identifierAt(text: String, start: Int): String {
        if (start < 0 || start >= text.length) return ""
        if (text[start] == '`') {
            val close = text.indexOf('`', start + 1)
            return if (close > start) text.substring(start + 1, close) else ""
        }
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
        return text.substring(start, end)
    }

    private fun nameEndAt(text: String, nameStart: Int): Int {
        if (text.getOrNull(nameStart) == '`') {
            val close = text.indexOf('`', nameStart + 1)
            return if (close > nameStart) close + 1 else nameStart + 1
        }
        var end = nameStart
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
        return end
    }

    private fun unknown(name: String): DeclaredType.Unknown =
        DeclaredType.Unknown("Cannot infer the type of $name without a compiler; declare it explicitly")

    private class LocalCandidate(val nameStart: Int, val scopeEnd: Int)

    private class ParameterSegment(val value: String, val start: Int)

    private fun splitParameters(region: String): List<ParameterSegment> {
        val result = ArrayList<ParameterSegment>()
        var angle = 0
        var paren = 0
        var bracket = 0
        var start = 0
        var i = 0
        while (i < region.length) {
            when (region[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                ',' -> if (angle == 0 && paren == 0 && bracket == 0) {
                    result.add(ParameterSegment(region.substring(start, i), start))
                    start = i + 1
                }
            }
            i++
        }
        if (start <= region.length) result.add(ParameterSegment(region.substring(start), start))
        return result
    }

    private fun lambdaArrow(text: String, open: Int, close: Int): Int? {
        var i = open + 1
        while (i < close) {
            when (text[i]) {
                '{', '}', ';' -> return null
                '-' -> if (text.getOrNull(i + 1) == '>') return i
            }
            i++
        }
        return null
    }

    private fun lambdaParameterNames(params: String): List<Pair<String, Int>> {
        if (params.contains('=') || params.contains('(')) return emptyList()
        val result = ArrayList<Pair<String, Int>>()
        var start = 0
        var angle = 0
        var i = 0
        fun addSegment(end: Int) {
            val segment = params.substring(start, end)
            val colon = segment.indexOf(':')
            if (colon < 0) {
                val trimmed = segment.trim()
                if (trimmed.isNotEmpty() && trimmed.all { it.isLetterOrDigit() || it == '_' || it == '$' }) {
                    result.add(trimmed to start + segment.indexOf(trimmed))
                }
            } else {
                val candidate = segment.substring(0, colon).trim()
                if (candidate.isNotEmpty() && candidate.all { it.isLetterOrDigit() || it == '_' || it == '$' }) {
                    result.add(candidate to start + segment.indexOf(candidate))
                }
            }
        }
        while (i < params.length) {
            when (params[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                ',' -> if (angle == 0) {
                    addSegment(i)
                    start = i + 1
                }
            }
            i++
        }
        addSegment(params.length)
        return result
    }

    private fun braceDepth(text: String, upTo: Int): Int {
        var depth = 0
        for (i in 0 until upTo.coerceAtMost(text.length)) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    private fun blockAfter(text: String, closeParen: Int, fallback: Int): Int {
        var i = closeParen + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && text[i] == '{') {
            return matchingDelimiter(text, i, '{', '}') ?: fallback
        }
        return fallback
    }

    /** The offset of the `}` that closes the innermost `{` still open at [position], or `Int.MAX_VALUE`. */
    private fun enclosingBlockClose(text: String, position: Int): Int {
        val stack = ArrayDeque<Int>()
        for (i in 0 until position.coerceAtMost(text.length)) {
            when (text[i]) {
                '{' -> stack.addLast(i)
                '}' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val open = stack.lastOrNull() ?: return Int.MAX_VALUE
        return matchingDelimiter(text, open, '{', '}') ?: Int.MAX_VALUE
    }

    private val KOTLIN_LOCAL = Regex("""\b(?:val|var)\s+(?:<[^>]*>\s+)?(`[^`]+`|[A-Za-z_]\w*)""")

    private val KOTLIN_PARAMETER = Regex("""(`[^`]+`|[A-Za-z_]\w*)\s*:""")

    private val KOTLIN_FOR = Regex("""\bfor\s*\(""")

    private val KOTLIN_CATCH = Regex("""\bcatch\s*\(""")

    private val JAVA_LOCAL = Regex("""(?:^|[;{}()\n])\s*(?:final\s+)?(?:@\w+(?:\([^)]*\))?\s+)*([A-Za-z_$][\w$.<>\[\]]*)\s+([A-Za-z_$][\w$]*)\s*(?:=|;)""")

    private val JAVA_PARAMETER_END = Regex("""([A-Za-z_$][\w$]*)\s*(?:\[\])?\s*$""")

    private val JAVA_FOR_EACH = Regex("""\bfor\s*\(\s*(?:final\s+)?([A-Za-z_$][\w$.<>\[\]]*)\s+([A-Za-z_$][\w$]*)\s*:""")

    private val JAVA_CATCH = Regex("""\bcatch\s*\(\s*(?:final\s+)?([A-Za-z_$][\w$.<>\[\]]*)\s+([A-Za-z_$][\w$]*)\s*\)""")

    private val CONSTRUCTOR_CALL = Regex("""^([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*\(""")

    private val JAVA_PREFIX_MODIFIER = Regex(
        """^(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp|transient|volatile|sealed|non-sealed)\s+"""
    )

    private val JAVA_KEYWORDS = setOf(
        "if", "for", "while", "do", "switch", "case", "default", "else", "return", "throw", "new",
        "assert", "try", "catch", "finally", "synchronized", "yield", "import", "package", "break",
        "continue", "instanceof", "extends", "implements", "throws", "var", "this", "super", "true",
        "false", "null",
    )
}
