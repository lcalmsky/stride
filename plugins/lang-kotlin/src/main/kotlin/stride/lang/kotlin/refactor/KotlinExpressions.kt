package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

internal enum class CallKind { PARENTHESES, INDEX, LAMBDA }

/** One call site: the opening bracket and its partner. */
internal class KtCall(val open: KtToken, val close: KtToken?, val kind: CallKind) {
    val closeOffset: Int get() = close?.start ?: open.start

    val range: KtRange get() = KtRange(open.start, close?.end ?: open.end)
}

internal enum class EffectKind { CALL, ASSIGNMENT, INCREMENT }

/** A side effect and the offset at which it takes effect. */
internal class KtEffect(val kind: EffectKind, val offset: Int, val range: KtRange)

private val ASSIGNMENT_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=")

internal fun computeCalls(code: KotlinCode): List<KtCall> {
    val excluded = HashSet<Int>()
    for (function in code.functions) excluded.add(function.parameterListOpen.index)
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD) continue
        when (token.text) {
            "catch", "get", "set", "constructor" -> {
                if (token.text != "catch" && isDotBeforeToken(code, token.index)) continue
                val next = code.code.getOrNull(token.index + 1) ?: continue
                if (next.text == "(") excluded.add(next.index)
            }
            "class" -> {
                var index = token.index + 1
                val name = code.code.getOrNull(index)?.takeIf { isNameToken(it) } ?: continue
                index = name.index + 1
                if (code.code.getOrNull(index)?.text == "<") {
                    val close = angleClose(code, index)
                    if (close > 0) index = close + 1
                }
                val next = code.code.getOrNull(index) ?: continue
                if (next.text == "(") excluded.add(next.index)
            }
        }
    }

    val calls = ArrayList<KtCall>()
    for (token in code.code) {
        when (token.text) {
            "(" -> {
                if (token.index in excluded) continue
                if (insideNonExpression(code, token.start)) continue
                val previous = code.code.getOrNull(token.index - 1) ?: continue
                val call = when {
                    previous.text == ">" || previous.text == ")" || previous.text == "]" -> true
                    isNameToken(previous) -> true
                    else -> false
                }
                if (call) calls.add(KtCall(token, code.matching(token.index), CallKind.PARENTHESES))
            }
            "[" -> {
                val previous = code.previousToken(token) ?: continue
                val call = isNameToken(previous) || previous.text == ")" || previous.text == "]"
                if (call) calls.add(KtCall(token, code.matching(token.index), CallKind.INDEX))
            }
            "{" -> {
                val block = code.blocks.firstOrNull { it.open.index == token.index } ?: continue
                if (block.kind != BlockKind.LAMBDA) continue
                val previous = code.code.getOrNull(token.index - 1) ?: continue
                if (isNameToken(previous) || previous.text == ")") {
                    calls.add(KtCall(token, block.close, CallKind.LAMBDA))
                }
            }
        }
    }
    return calls
}

private fun isDotBeforeToken(code: KotlinCode, index: Int): Boolean {
    val before = code.code.getOrNull(index - 1) ?: return false
    return before.type == KotlinTokenTypes.DOT ||
        (before.text == "?" && code.code.getOrNull(index - 2)?.type == KotlinTokenTypes.DOT)
}

private fun insideNonExpression(code: KotlinCode, offset: Int): Boolean =
    code.nonExpressionRanges.any { it.contains(offset) }

internal fun computeEffects(code: KotlinCode): List<KtEffect> {
    val effects = ArrayList<KtEffect>()
    for (call in code.calls) effects.add(KtEffect(EffectKind.CALL, call.closeOffset, call.range))
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.OPERATION_SIGN) continue
        when {
            token.text == "++" || token.text == "--" ->
                effects.add(KtEffect(EffectKind.INCREMENT, token.start, token.range))
            token.text in ASSIGNMENT_OPERATORS -> {
                if (token.text == "=" && code.isDeclarationEquals(token.index)) continue
                if (insideNonExpression(code, token.start)) continue
                if (isNamedArgumentEquals(code, token.index)) continue
                val statement = code.statementAt(token.start)
                val offset = statement?.end ?: token.end
                effects.add(KtEffect(EffectKind.ASSIGNMENT, offset, token.range))
            }
        }
    }
    return effects
}

/**
 * True when the `=` at [index] initializes something (a val/var, a parameter default, an
 * expression body) rather than assigning to an existing variable.
 */
private fun KotlinCode.isDeclarationEquals(index: Int): Boolean {
    var current = index - 1
    var guard = 0
    while (current >= 0 && guard++ < 500) {
        val token = code[current]
        val next = code.getOrNull(current + 1)
        if (next != null && splitsAfter(token, next)) return false
        when {
            token.text == "}" || token.text == "{" || token.text == ";" || token.text == "=" ||
                token.text in ASSIGNMENT_OPERATORS || token.text == "->" -> return false
            isCloser(token) -> {
                val open = matchingIndex(current)
                if (open < 0) return false
                current = open - 1
                continue
            }
            token.type == KotlinTokenTypes.KEYWORD && token.text in setOf(
                "val", "var", "fun", "class", "object", "interface", "when", "for", "catch",
                "constructor",
            ) -> return true
            token.text == "(" || token.text == "," -> return true
        }
        current--
    }
    return false
}

private fun isNamedArgumentEquals(code: KotlinCode, equalsIndex: Int): Boolean {
    val previous = code.code.getOrNull(equalsIndex - 1) ?: return false
    if (!isNameToken(previous)) return false
    val before = code.code.getOrNull(equalsIndex - 2) ?: return false
    return before.text == "(" || before.text == "," || before.text == ":"
}

internal fun KotlinCode.callsIn(range: KtRange): List<KtCall> =
    calls.filter { it.open.start >= range.start && (it.close?.end ?: it.open.end) <= range.end }

internal fun KotlinCode.effectsIn(range: KtRange): List<KtEffect> =
    effects.filter { it.offset >= range.start && it.offset < range.end }

/** Calls and `++`/`--` in [range]'s statement that take effect before [offset]. */
internal fun KotlinCode.effectsBefore(range: KtRange): List<KtEffect> {
    val statement = statementAt(range.start) ?: return emptyList()
    return effects.filter {
        (it.kind == EffectKind.CALL || it.kind == EffectKind.INCREMENT) &&
            it.offset >= statement.start && it.offset < range.start
    }
}

internal enum class ExpressionProblem {
    BOUNDARY,
    BRACKETS,
    STATEMENT,
    SEMICOLON,
    COMMA,
    ASSIGNMENT,
    DECLARATION,
    OPERATOR_EDGE,
    MEMBER,
    CALL_CONTINUATION,
    TYPE_POSITION,
    ANNOTATION,
    CONTROL,
}

/** The first reason [range] fails the R3 expression rules, `null` when it passes them. */
internal fun KotlinCode.checkExpression(range: KtRange): ExpressionProblem? {
    if (range.isEmpty) return ExpressionProblem.BOUNDARY
    if (isInsideOpaqueText(range.start)) return ExpressionProblem.BOUNDARY
    val first = tokenStartingAt(range.start) ?: return ExpressionProblem.BOUNDARY
    val endToken = tokenEndingAt(range.end) ?: return ExpressionProblem.BOUNDARY
    val startTemplate = templateRangeOf(first)
    val endTemplate = templateRangeOf(endToken)
    if ((startTemplate == null) != (endTemplate == null)) return ExpressionProblem.BOUNDARY
    if (startTemplate != null && endTemplate != null && startTemplate.start != endTemplate.start) {
        return ExpressionProblem.BOUNDARY
    }
    val atoms = expressionAtoms(range) ?: return ExpressionProblem.BOUNDARY

    val statement = statementAt(range.start) ?: return ExpressionProblem.STATEMENT
    if (range.end > statement.end) return ExpressionProblem.STATEMENT
    for (token in code) {
        if (token.start < range.start || token.end > range.end) continue
        if (token.index in templateTokens) continue
        if (statementAt(token.start) !== statement) return ExpressionProblem.STATEMENT
    }

    for (token in code) {
        if (token.start < range.start || token.end > range.end) continue
        when (token.text) {
            "(", "[", "{" -> {
                val close = matchingIndex(token.index)
                if (close < 0 || code[close].start >= range.end) return ExpressionProblem.BRACKETS
            }
            ")", "]", "}" -> {
                val open = matchingIndex(token.index)
                if (open < 0 || code[open].start < range.start) return ExpressionProblem.BRACKETS
            }
        }
    }

    var depth = 0
    for (atom in atoms) {
        when (atom.text) {
            "(", "[", "{" -> depth++
            ")", "]", "}" -> depth--
            else -> if (depth <= 0) {
                if (atom.text == ";") return ExpressionProblem.SEMICOLON
                if (atom.text == "=" || atom.text == ":") return ExpressionProblem.ASSIGNMENT
                if (atom.token.type == KotlinTokenTypes.KEYWORD &&
                    atom.text in setOf("val", "var", "fun", "class", "object", "interface", "typealias")
                ) {
                    return ExpressionProblem.DECLARATION
                }
            }
        }
    }

    for (token in code) {
        if (token.start < range.start || token.end > range.end) continue
        if (token.index in declarationTokens) return ExpressionProblem.DECLARATION
    }

    if (first.text == "(" || first.text == "[") {
        val beforeGroup = tokenBefore(range.start)
        if (beforeGroup != null && (isNameToken(beforeGroup) || beforeGroup.text == ")" ||
                beforeGroup.text == "]" || beforeGroup.text == ">")
        ) {
            return ExpressionProblem.CALL_CONTINUATION
        }
    }
    if (first.type == KotlinTokenTypes.KEYWORD && first.text in NON_EXPRESSION_STARTS) {
        return ExpressionProblem.STATEMENT
    }
    if (first.type == KotlinTokenTypes.KEYWORD && first.text == "if" && !hasTopLevelElse(atoms)) {
        return ExpressionProblem.STATEMENT
    }

    for (atom in atoms) {
        if (atom.token.type != KotlinTokenTypes.KEYWORD) continue
        if (atom.text == "return" && isLabelReturnInsideLambda(atom.token, range)) continue
        if (atom.text in setOf("return", "break", "continue", "throw")) return ExpressionProblem.CONTROL
    }

    if (!startsOperand(first, range)) return ExpressionProblem.OPERATOR_EDGE
    if (first.text == "!" && atoms.size > 1 && atoms[1].text in KEYWORD_OPERATORS) {
        return ExpressionProblem.OPERATOR_EDGE
    }
    val last = atoms.lastOrNull() ?: return ExpressionProblem.BOUNDARY
    if (!endsOperand(last.token)) return ExpressionProblem.OPERATOR_EDGE
    if (last.text == "?" && atoms.size > 1 && atoms[atoms.size - 2].text == "as") {
        return ExpressionProblem.OPERATOR_EDGE
    }
    if (typeArgumentProblem(this, range)) return ExpressionProblem.BRACKETS

    val before = tokenBefore(range.start)
    if (before != null) {
        if (before.type == KotlinTokenTypes.DOT || before.text == "::") return ExpressionProblem.MEMBER
        if (before.text == "?" && tokenBefore(before.start)?.type == KotlinTokenTypes.DOT) {
            return ExpressionProblem.MEMBER
        }
    }
    val after = tokenAfter(range.end)
    if (after != null) {
        if (after.text == "(" || after.text == "[" || after.text == "=" ||
            after.text in setOf("+=", "-=", "*=", "/=", "%=", "++", "--")
        ) {
            return ExpressionProblem.CALL_CONTINUATION
        }
        if (after.text == "{") {
            val block = blocks.firstOrNull { it.open.index == after.index }
            if (block?.kind == BlockKind.LAMBDA) return ExpressionProblem.CALL_CONTINUATION
        }
        if (after.text == "<" && isGenericCallAt(this, after.index)) return ExpressionProblem.CALL_CONTINUATION
    }

    if (before != null) {
        if (before.text == ":" || before.text == "as" || before.text == "is") {
            return ExpressionProblem.TYPE_POSITION
        }
        if (before.text == "?" && tokenBefore(before.start)?.text == "as") return ExpressionProblem.TYPE_POSITION
        if (before.text == "is" && tokenBefore(before.start)?.text == "!") return ExpressionProblem.TYPE_POSITION
    }
    if (isInsideTypeArguments(this, range.start)) return ExpressionProblem.TYPE_POSITION
    if (hasTopLevelComma(atoms)) return ExpressionProblem.COMMA

    if (nonExpressionRanges.any { it.start < range.end && range.start < it.end }) {
        return ExpressionProblem.ANNOTATION
    }
    return null
}

/** True when [token] starts an operand, so a selection may start there (R3 「Expression rules」). */
private fun KotlinCode.startsOperand(token: KtToken, range: KtRange): Boolean {
    if (token.isStringBody) return token.text.startsWith("\"")
    when (token.type) {
        KotlinTokenTypes.NUMBER, KotlinTokenTypes.CHARACTER -> return true
        KotlinTokenTypes.OPERATION_SIGN -> return when (token.text) {
            "!", "++", "--" -> true
            "-", "+" -> tokenBefore(range.start)?.let { !endsOperand(it) } ?: true
            else -> false
        }
    }
    if (token.text == "(" || token.text == "{" || token.text == "this" || token.text == "super" ||
        token.text == "if" || token.text == "when" || token.text == "try" || token.text == "object"
    ) {
        return true
    }
    if (!isNameToken(token)) return false
    return references.any { it.token.index == token.index } || isMemberName(token)
}

/** True when [token] ends an operand, so a selection may end there (R3 「Expression rules」). */
private fun KotlinCode.endsOperand(token: KtToken): Boolean {
    if (token.isStringBody) return true
    when (token.type) {
        KotlinTokenTypes.NUMBER, KotlinTokenTypes.CHARACTER -> return true
        KotlinTokenTypes.OPERATION_SIGN -> return token.text == "!!" || token.text == "++" || token.text == "--"
    }
    if (token.text == ")" || token.text == "]" || token.text == "}" || token.text == "this" ||
        token.text == "true" || token.text == "false" || token.text == "null"
    ) {
        return true
    }
    return isNameToken(token) && token.text !in KEYWORD_OPERATORS
}

private fun KotlinCode.isMemberName(token: KtToken): Boolean {
    val before = code.getOrNull(token.index - 1) ?: return false
    if (before.type == KotlinTokenTypes.DOT) return true
    return before.text == "?" && code.getOrNull(token.index - 2)?.type == KotlinTokenTypes.DOT
}

/**
 * True when a `<…>` type argument list crosses the selection's boundary: opened inside and closed
 * outside, or the reverse (R3 「Expression rules」).
 */
private fun typeArgumentProblem(code: KotlinCode, range: KtRange): Boolean {
    for (index in code.code.indices) {
        if (code.code[index].text != "<") continue
        if (!isTypeArgumentOpen(code, index)) continue
        val close = typeArgumentClose(code, index)
        if (close < 0) continue
        val openInside = code.code[index].start >= range.start && code.code[index].end <= range.end
        val closeInside = code.code[close].start >= range.start && code.code[close].end <= range.end
        if (openInside != closeInside) return true
    }
    return false
}

/** Tokens that can follow the `>` closing a type argument list. */
private val TYPE_ARGUMENT_FOLLOWERS = setOf(
    "(", ")", "[", "]", "{", "}", ".", ",", ":", "=", ";", "?", ">", "where", "::",
)

/** Keywords whose `<…>` holds type parameters, so a name may follow the closing `>`. */
private val TYPE_PARAMETER_HEAD_KEYWORDS = setOf("fun", "class", "interface", "object", "typealias")

/**
 * True when the `<` at [openIndex] opens a type argument or type parameter list rather than a
 * comparison. The token after the matching `>` decides: type lists always lead into a call, a
 * member, a type position or a parameter name. With [atLineEnd] a list closed at the end of its
 * line is a type list whatever starts the next line (R2 「Statements」).
 */
internal fun isTypeArgumentOpen(code: KotlinCode, openIndex: Int, atLineEnd: Boolean = false): Boolean {
    val previous = code.code.getOrNull(openIndex - 1) ?: return false
    val close = typeArgumentClose(code, openIndex)
    if (close <= openIndex) return false
    val after = code.code.getOrNull(close + 1)
    val followed = after == null || after.text in TYPE_ARGUMENT_FOLLOWERS ||
        (atLineEnd && code.lineIndex(after.start) > code.lineIndex(code.code[close].start))
    if (isNameToken(previous) || previous.text == ">" || previous.text == "]" || previous.text == ")") {
        return followed
    }
    if (previous.type == KotlinTokenTypes.KEYWORD && previous.text in TYPE_PARAMETER_HEAD_KEYWORDS) {
        return followed || (after != null && (isNameToken(after) || after.text == "where"))
    }
    return false
}

/**
 * The index of the token closing the `<` at [openIndex], or `-1`. A lexer token can merge the `>`
 * with what follows (`?>`, `>>`), so any token holding `>` counts.
 */
internal fun typeArgumentClose(code: KotlinCode, openIndex: Int): Int {
    var depth = 0
    var index = openIndex
    while (index < code.code.size) {
        val text = code.code[index].text
        var opens = 0
        var closes = 0
        for (char in text) {
            if (char == '<') opens++
            if (char == '>') closes++
        }
        depth += opens
        if (closes > 0) {
            depth -= closes
            if (depth <= 0) return index
        }
        index++
    }
    return -1
}

/** The `${…}` template whose code holds [token], or `null` outside template code. */
private fun KotlinCode.templateRangeOf(token: KtToken): KtRange? {
    if (!token.isCode) return null
    return templateExpressionRanges.firstOrNull { it.contains(token.start) }
}

private fun KotlinCode.isLabelReturnInsideLambda(token: KtToken, range: KtRange): Boolean {
    val next = tokenAfter(token.end) ?: return false
    if (next.text != "@") return false
    return blocks.any {
        it.kind == BlockKind.LAMBDA && it.open.start >= range.start &&
            (it.close?.end ?: it.open.end) <= range.end && it.contains(token.start)
    }
}

private fun isGenericCallAt(code: KotlinCode, openIndex: Int): Boolean {
    val close = angleClose(code, openIndex)
    if (close < 0) return false
    return code.code.getOrNull(close + 1)?.text == "("
}

private fun isInsideTypeArguments(code: KotlinCode, offset: Int): Boolean {
    for (index in code.code.indices) {
        val token = code.code[index]
        if (token.text != "<") continue
        val previous = code.code.getOrNull(index - 1) ?: continue
        val generic = isNameToken(previous) || previous.text == ">" || previous.text == "]" || previous.text == ")"
        if (!generic) continue
        val close = angleClose(code, index)
        if (close < 0) continue
        if (token.start < offset && offset < code.code[close].start) return true
    }
    return false
}

internal fun KotlinCode.tokenStartingAt(offset: Int): KtToken? =
    tokens.firstOrNull { it.start == offset && (it.isCode || it.isStringBody) }

internal fun KotlinCode.tokenEndingAt(offset: Int): KtToken? =
    tokens.lastOrNull { it.end == offset && (it.isCode || it.isStringBody) }

internal fun KotlinCode.tokenBefore(offset: Int): KtToken? =
    tokens.lastOrNull { it.end <= offset && (it.isCode || it.isStringBody) }

internal fun KotlinCode.tokenAfter(offset: Int): KtToken? =
    tokens.firstOrNull { it.start >= offset && (it.isCode || it.isStringBody) }

internal fun KotlinCode.isInsideOpaqueText(offset: Int): Boolean {
    for (token in tokens) {
        if (offset <= token.start || offset >= token.end) continue
        if (token.isComment) return true
        if (token.isStringBody) {
            val codeStartsHere = code.any { it.start == offset }
            if (!codeStartsHere) return true
        }
    }
    return false
}

private enum class AtomKind { NAME, LITERAL, OPERATOR, KEYWORD, OTHER }

private class ExprAtom(val token: KtToken, val text: String, val kind: AtomKind)

/**
 * The expression tokens of [range]: string literals collapse into one atom and template code is
 * left out unless the range starts in it. `null` when a code token straddles the range boundary.
 */
private fun KotlinCode.expressionAtoms(range: KtRange): List<ExprAtom>? {
    val templateCode = tokenStartingAt(range.start)?.let { templateRangeOf(it) } != null
    val atoms = ArrayList<ExprAtom>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.end <= range.start || token.start >= range.end) {
            index++
            continue
        }
        if (token.start < range.start || token.end > range.end) {
            if (token.isStringBody || token.isComment) {
                index++
                continue
            }
            return null
        }
        when {
            token.isComment -> index++
            token.isStringBody -> {
                if (!token.text.startsWith("\"")) {
                    index++
                    continue
                }
                var end = token.end
                var pieceIndex = index
                while (tokens[pieceIndex].text.endsWith("\${")) {
                    var next = pieceIndex + 1
                    while (next < tokens.size && !(tokens[next].isStringBody && tokens[next].start >= end)) next++
                    if (next >= tokens.size) break
                    pieceIndex = next
                    end = tokens[next].end
                    if (tokens[next].text.endsWith("\${")) continue
                    break
                }
                atoms.add(ExprAtom(token, "\"\"", AtomKind.LITERAL))
                index = pieceIndex + 1
            }
            token.index in templateTokens && !templateCode -> index++
            else -> {
                val kind = when {
                    isNameToken(token) -> AtomKind.NAME
                    token.type == KotlinTokenTypes.NUMBER || token.type == KotlinTokenTypes.CHARACTER ->
                        AtomKind.LITERAL
                    token.type == KotlinTokenTypes.OPERATION_SIGN -> AtomKind.OPERATOR
                    token.type == KotlinTokenTypes.KEYWORD -> AtomKind.KEYWORD
                    else -> AtomKind.OTHER
                }
                atoms.add(ExprAtom(token, token.text, kind))
                index++
            }
        }
    }
    return atoms
}

/** Keywords that act as operators, so an expression may not start or end with them. */
private val KEYWORD_OPERATORS = setOf("as", "is", "in")

/** Keywords that start a statement rather than an expression. */
private val NON_EXPRESSION_STARTS = setOf("while", "for", "do")

/** True when every top-level `if` of the expression has an `else` at the same depth. */
private fun hasTopLevelElse(atoms: List<ExprAtom>): Boolean {
    var depth = 0
    var ifs = 0
    var elses = 0
    for (atom in atoms) {
        when (atom.text) {
            "(", "[", "{" -> depth++
            ")", "]", "}" -> depth--
            "if" -> if (depth == 0) ifs++
            "else" -> if (depth == 0) elses++
        }
    }
    return ifs <= elses
}

private fun hasTopLevelComma(atoms: List<ExprAtom>): Boolean {
    var depth = 0
    for (atom in atoms) {
        when (atom.text) {
            "(", "[", "{" -> depth++
            ")", "]", "}" -> depth--
            "," -> if (depth <= 0) return true
        }
    }
    return false
}

private val STABLE_OPERATORS = setOf(
    "+", "-", "*", "<", ">", "<=", ">=", "==", "!=", "===", "!==", "&&", "||", "!", "?:", "..",
)

/** R2 stability: only literals, `this`, booleans, `null`, stable locals and the listed operators. */
internal fun KotlinCode.isStable(range: KtRange): Boolean {
    if (!templatesStable(range)) return false
    val atoms = expressionAtoms(range) ?: return false
    val parser = StableParser(this, atoms)
    return parser.parse()
}

private class StableParser(private val code: KotlinCode, private val atoms: List<ExprAtom>) {
    private var index = 0

    fun parse(): Boolean {
        if (atoms.isEmpty()) return false
        if (!expression()) return false
        return index == atoms.size
    }

    private fun expression(): Boolean {
        if (!primary()) return false
        while (index < atoms.size) {
            val atom = atoms[index]
            if (atom.kind == AtomKind.OPERATOR && atom.text in STABLE_OPERATORS) {
                index++
                if (!primary()) return false
            } else {
                break
            }
        }
        return true
    }

    private fun primary(): Boolean {
        if (index >= atoms.size) return false
        val atom = atoms[index]
        if (atom.text == "(") {
            index++
            if (!expression()) return false
            if (index >= atoms.size || atoms[index].text != ")") return false
            index++
            return true
        }
        if (atom.kind == AtomKind.LITERAL) {
            index++
            return true
        }
        if (atom.kind == AtomKind.KEYWORD && atom.text in setOf("this", "null", "true", "false")) {
            index++
            return true
        }
        if (atom.kind == AtomKind.OPERATOR && atom.text in setOf("!", "-", "+")) {
            index++
            return primary()
        }
        if (atom.kind == AtomKind.NAME) {
            val reference = code.references.firstOrNull { it.token.index == atom.token.index }
            if (reference == null || !isStableLocal(reference.resolved)) return false
            index++
            return true
        }
        return false
    }
}

private fun isStableLocal(local: KtLocal?): Boolean {
    if (local == null) return false
    return when (local.kind) {
        LocalKind.VAL, LocalKind.PARAMETER, LocalKind.LAMBDA_PARAMETER, LocalKind.FOR,
        LocalKind.CATCH, LocalKind.WHEN_SUBJECT,
        -> true
        else -> false
    }
}

/** R2 read-only: no calls, no `!!` and no indexing; vars and members may be read. */
internal fun KotlinCode.isReadOnly(range: KtRange): Boolean {
    if (callsIn(range).isNotEmpty()) return false
    for (token in code) {
        if (token.start < range.start || token.end > range.end) continue
        if (token.index in templateTokens) continue
        when (token.text) {
            "!!", "[", "++", "--", "=", "+=", "-=", "*=", "/=", "%=" -> return false
        }
        if (token.type == KotlinTokenTypes.KEYWORD &&
            token.text in setOf("return", "break", "continue", "throw")
        ) {
            return false
        }
    }
    for (block in blocks) {
        if (block.kind == BlockKind.LAMBDA && block.open.start >= range.start &&
            (block.close?.end ?: block.open.end) <= range.end
        ) {
            return false
        }
    }
    for (template in templateExpressionRanges) {
        if (template.start > range.start && template.end < range.end && !isReadOnly(template)) return false
    }
    return true
}

/** R5 atomic substitution: one name, literal, `this`, postfix chain, or one parenthesized group. */
internal fun KotlinCode.isAtomic(range: KtRange): Boolean {
    val first = tokenStartingAt(range.start) ?: return false
    if (first.isStringBody) {
        val atoms = expressionAtoms(range) ?: return false
        return atoms.size == 1 && atoms[0].kind == AtomKind.LITERAL
    }
    if (first.text == "(") {
        val close = matching(first.index) ?: return false
        return close.end == range.end
    }
    if (!isChainPrimary(first)) return false
    var index = first.index + 1
    val lastIndex = code.indexOfLast { it.end <= range.end && it.start >= range.start }
    if (lastIndex < first.index) return false
    while (index <= lastIndex) {
        val token = code[index]
        when {
            token.text == "." -> {
                val name = code.getOrNull(index + 1) ?: return false
                if (!isNameToken(name)) return false
                index += 2
            }
            token.text == "?" && code.getOrNull(index + 1)?.text == "." -> {
                val name = code.getOrNull(index + 2) ?: return false
                if (!isNameToken(name)) return false
                index += 3
            }
            token.text == "(" -> {
                val close = matchingIndex(index)
                if (close < 0) return false
                index = close + 1
            }
            token.text == "[" -> {
                val close = matchingIndex(index)
                if (close < 0) return false
                index = close + 1
            }
            token.text == "!!" -> index++
            else -> return false
        }
    }
    return index > lastIndex && code[lastIndex].end == range.end
}

private fun isChainPrimary(token: KtToken): Boolean =
    isNameToken(token) || token.type == KotlinTokenTypes.NUMBER || token.type == KotlinTokenTypes.CHARACTER ||
        (token.type == KotlinTokenTypes.KEYWORD && token.text in setOf("this", "true", "false", "null"))

/** True when every template in [range] is stable: `$name` reads a val or parameter and `${…}` is stable. */
internal fun KotlinCode.templatesStable(range: KtRange): Boolean {
    for (token in code) {
        if (token.index !in simpleTemplateTokens) continue
        if (token.start < range.start || token.end > range.end) continue
        val reference = references.firstOrNull { it.token.index == token.index } ?: return false
        if (!isStableLocal(reference.resolved)) return false
    }
    for (template in templateExpressionRanges) {
        if (template.start > range.start && template.end < range.end && !isStable(template)) return false
    }
    return true
}

/** R2 conditional position, with the three places judged from their opening parenthesis. */
internal fun KotlinCode.isConditional(range: KtRange): Boolean {
    val statement = statementAt(range.start) ?: return true
    if (statement.startTokenIndex < 0) return false
    return conditionalFrom(statement.startTokenIndex, statement.endTokenIndex, range.start)
}

/**
 * True when a marker makes [offset] conditional, scanning the code tokens [fromIndex] to
 * [endIndex]. The first `if` condition, a `when` subject and a `for` iterable are scanned from
 * their opening parenthesis, so markers inside them still count (R2).
 */
internal fun KotlinCode.conditionalFrom(fromIndex: Int, endIndex: Int, offset: Int): Boolean {
    val exception = firstIfConditionOpen(fromIndex, endIndex, offset)
        .takeIf { it >= 0 }
        ?: whenSubjectOpen(fromIndex, endIndex, offset).takeIf { it >= 0 }
        ?: forIterableOpen(fromIndex, endIndex, offset).takeIf { it >= 0 }
    return hasConditionalMarker(exception?.plus(1) ?: fromIndex, offset)
}

/** True when the code tokens from [fromIndex] up to [offset] hold a conditional marker. */
internal fun KotlinCode.hasConditionalMarker(fromIndex: Int, offset: Int): Boolean {
    var index = fromIndex
    while (index < code.size) {
        val token = code[index]
        if (token.end > offset) break
        if (isOpener(token)) {
            val close = matchingIndex(index)
            if (close >= 0 && code[close].end <= offset) {
                index = close + 1
                continue
            }
        }
        when {
            token.text in CONDITIONAL_MARKERS -> return true
            token.text == "?" && code.getOrNull(index + 1)?.type == KotlinTokenTypes.DOT -> return true
            token.text == "{" && blocks.firstOrNull { it.open.index == index }?.kind == BlockKind.WHEN_BODY -> return true
            token.type == KotlinTokenTypes.KEYWORD && token.text in CONDITIONAL_KEYWORDS -> return true
        }
        index++
    }
    return false
}

private val CONDITIONAL_MARKERS = setOf("&&", "||", "?:", "else", "->")

private val CONDITIONAL_KEYWORDS = setOf("if", "while", "for", "do", "try", "catch", "finally")

/** Code token index of the `(` holding [offset] in the first `if` after [fromIndex], or `-1`. */
private fun KotlinCode.firstIfConditionOpen(fromIndex: Int, endIndex: Int, offset: Int): Int {
    for (index in fromIndex..endIndex) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "if") continue
        val open = code.getOrNull(index + 1) ?: return -1
        if (open.text != "(") return -1
        val close = matching(open.index) ?: return -1
        return if (offset > open.start && offset < close.end) open.index else -1
    }
    return -1
}

private fun KotlinCode.whenSubjectOpen(fromIndex: Int, endIndex: Int, offset: Int): Int {
    for (index in fromIndex..endIndex) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "when") continue
        val open = code.getOrNull(index + 1) ?: continue
        if (open.text != "(") continue
        val close = matching(open.index) ?: continue
        if (offset > open.start && offset < close.end) return open.index
    }
    return -1
}

private fun KotlinCode.forIterableOpen(fromIndex: Int, endIndex: Int, offset: Int): Int {
    for (index in fromIndex..endIndex) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "for") continue
        val open = code.getOrNull(index + 1) ?: continue
        if (open.text != "(") continue
        val close = matching(open.index) ?: continue
        if (offset <= open.start || offset >= close.end) continue
        var inIndex = -1
        var i = open.index + 1
        while (i < close.index) {
            if (code[i].text == "in") {
                inIndex = i
                break
            }
            if (isOpener(code[i])) {
                val group = matchingIndex(i)
                if (group in (i + 1) until close.index) {
                    i = group + 1
                    continue
                }
            }
            i++
        }
        if (inIndex >= 0 && offset > code[inIndex].end) return open.index
    }
    return -1
}

internal fun isValidKotlinIdentifier(name: String): Boolean =
    name.isNotEmpty() && name.any { it != '_' } && (name[0] == '_' || name[0].isLetter()) &&
        name.all { it == '_' || it.isLetterOrDigit() } &&
        name.all { it.code < 128 }

internal fun isHardKeyword(name: String): Boolean = name in KotlinCode.HARD_KEYWORDS

/**
 * The function body, initializer or outermost lambda whose name check applies at [offset].
 * `null` when the offset is not inside a statement block.
 */
internal fun KotlinCode.nameScope(offset: Int): KtRange? {
    val chain = blockChain(offset).filter { it.isStatementBlock }
    if (chain.isEmpty()) return null
    val functionIndex = chain.indexOfLast { it.kind == BlockKind.FUNCTION_BODY || it.kind == BlockKind.INITIALIZER }
    val lambdaIndex = chain.indexOfFirst { it.kind == BlockKind.LAMBDA }
    val block = when {
        lambdaIndex >= 0 && lambdaIndex > functionIndex -> chain[lambdaIndex]
        functionIndex >= 0 -> chain[functionIndex]
        lambdaIndex >= 0 -> chain[lambdaIndex]
        else -> chain.last()
    }
    return KtRange(block.start, block.end)
}
