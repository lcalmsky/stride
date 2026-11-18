package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

internal enum class LocalKind {
    VAL,
    VAR,
    PARAMETER,
    LAMBDA_PARAMETER,
    FOR,
    CATCH,
    WHEN_SUBJECT,
    LOCAL_FUN,
    LOCAL_CLASS,
}

/** One local declaration with the scope its name lives in. */
internal class KtLocal(
    val name: String,
    val nameToken: KtToken,
    val kind: LocalKind,
    /** `[start, end)` offsets where the name resolves to this declaration. */
    val scope: KtRange,
    val writtenType: String?,
    val initializer: KtRange?,
    val destructured: Boolean,
    val delegated: Boolean,
    val lateinit: Boolean,
    val declarationStatement: KtStatement?,
) {
    val nameRange: KtRange get() = nameToken.range

    fun contains(offset: Int): Boolean = scope.contains(offset)
}

/** Where a reference token sits: plain code, `$name`, or `${…}` template code. */
internal enum class TemplateUsage { NONE, SIMPLE, EXPRESSION }

internal class KtReference(
    val token: KtToken,
    val name: String,
    val usage: TemplateUsage,
    /** The local the name resolves to, `null` for a member, top-level declaration or import. */
    val resolved: KtLocal?,
) {
    val range: KtRange get() = token.range
}

internal fun computeLocals(code: KotlinCode): List<KtLocal> {
    val locals = ArrayList<KtLocal>()

    for (function in code.functions) {
        val scope = function.bodyRange ?: continue
        for (parameter in function.parameters) {
            locals.add(
                KtLocal(
                    name = parameter.name,
                    nameToken = parameter.nameToken,
                    kind = LocalKind.PARAMETER,
                    scope = scope,
                    writtenType = parameter.type,
                    initializer = null,
                    destructured = false,
                    delegated = false,
                    lateinit = false,
                    declarationStatement = null,
                ),
            )
        }
    }

    collectAccessorParameters(code, locals)
    collectLambdaParameters(code, locals)
    collectDeclarationsInStatements(code, locals)
    collectForVariables(code, locals)
    collectCatchParameters(code, locals)
    collectWhenSubjects(code, locals)

    for (local in locals) code.keywordTokens.add(local.nameToken.index)
    return locals
}

private fun collectAccessorParameters(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD) continue
        if (token.text != "get" && token.text != "set" && token.text != "constructor") continue
        if (isDotBefore(code, token.index)) continue
        val open = code.code.getOrNull(token.index + 1) ?: continue
        if (open.text != "(") continue
        val closeIndex = code.matchingIndex(open.index)
        if (closeIndex < 0) continue
        val block = code.blocks.firstOrNull { it.open.index == closeIndex + 1 } ?: continue
        if (block.kind != BlockKind.FUNCTION_BODY) continue
        val scope = KtRange(block.start, block.end)
        for (parameter in parseParameterList(code, open.index, closeIndex)) {
            locals.add(
                KtLocal(
                    parameter.name, parameter.nameToken, LocalKind.PARAMETER, scope, parameter.type,
                    null, false, false, false, null,
                ),
            )
        }
    }
}

private fun isDotBefore(code: KotlinCode, index: Int): Boolean {
    val before = code.code.getOrNull(index - 1) ?: return false
    return before.type == KotlinTokenTypes.DOT ||
        (before.text == "?" && code.code.getOrNull(index - 2)?.type == KotlinTokenTypes.DOT)
}

private fun collectLambdaParameters(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (block in code.blocks) {
        if (block.kind != BlockKind.LAMBDA) continue
        val closeIndex = block.close?.index ?: continue
        val scope = KtRange(block.start, block.end)
        val arrow = findTopLevel(code, block.open.index + 1, closeIndex, "->")
        if (arrow < 0) {
            locals.add(
                KtLocal(
                    "it", block.open, LocalKind.LAMBDA_PARAMETER,
                    KtRange(block.open.start, block.end), null, null, false, false, false, null,
                ),
            )
            continue
        }
        for ((start, end) in splitTopLevel(code, block.open.index + 1, arrow, ",")) {
            collectLambdaParameterSegment(code, start, end, scope, locals)
        }
    }
}

private fun collectLambdaParameterSegment(
    code: KotlinCode,
    startIndex: Int,
    endIndex: Int,
    scope: KtRange,
    locals: MutableList<KtLocal>,
) {
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (token.text == "(") {
            val close = code.matchingIndex(index)
            if (close in (index + 1) until endIndex) {
                for ((start, end) in splitTopLevel(code, index + 1, close, ",")) {
                    val name = lastNameBeforeType(code, start, end)
                    if (name != null && name.text != "_") {
                        locals.add(
                            KtLocal(
                                name.text.removeSurrounding("`"), name, LocalKind.LAMBDA_PARAMETER, scope,
                                null, null, true, false, false, null,
                            ),
                        )
                    }
                }
                index = close + 1
                continue
            }
        }
        index++
    }
    val direct = lastNameBeforeType(code, startIndex, endIndex)
    if (direct != null && direct.text != "_") {
        val type = typeTextAfterColon(code, startIndex, endIndex)
        locals.add(
            KtLocal(
                direct.text.removeSurrounding("`"), direct, LocalKind.LAMBDA_PARAMETER, scope, type,
                null, false, false, false, null,
            ),
        )
    }
}

private fun lastNameBeforeType(code: KotlinCode, startIndex: Int, endIndex: Int): KtToken? {
    var colon = -1
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (code.isOpener(token)) {
            val close = code.matchingIndex(index)
            if (close in (index + 1) until endIndex) {
                index = close + 1
                continue
            }
        }
        if (token.text == ":" && colon < 0) colon = index
        index++
    }
    val limit = if (colon >= 0) colon else endIndex
    var name: KtToken? = null
    for (i in startIndex until limit) {
        val token = code.code[i]
        if (isNameToken(token) && token.text !in PARAMETER_MODIFIERS) name = token
    }
    return name
}

private val PARAMETER_MODIFIERS = setOf("vararg", "noinline", "crossinline")

private fun typeTextAfterColon(code: KotlinCode, startIndex: Int, endIndex: Int): String? {
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (code.isOpener(token)) {
            val close = code.matchingIndex(index)
            if (close in (index + 1) until endIndex) {
                index = close + 1
                continue
            }
        }
        if (token.text == ":") {
            val from = index + 1
            if (from < endIndex) return code.text.substring(code.code[from].start, code.code[endIndex - 1].end).trim()
            return null
        }
        index++
    }
    return null
}

private fun collectDeclarationsInStatements(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (statement in code.statements) {
        if (statement.startTokenIndex < 0) continue
        var index = statement.startTokenIndex
        var lateinit = false
        while (index <= statement.endTokenIndex) {
            val token = code.code[index]
            if (token.text == "@") {
                index = annotationEnd(code, index) + 1
                continue
            }
            if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
                if (token.text == "lateinit") lateinit = true
                index++
                continue
            }
            break
        }
        if (index > statement.endTokenIndex) continue
        val head = code.code[index]
        when {
            head.text == "val" || head.text == "var" ->
                collectValVar(code, index, statement, lateinit, locals)
            head.text == "fun" -> {
                val function = code.functions.firstOrNull { it.funToken.index == index } ?: continue
                locals.add(
                    KtLocal(
                        function.name, function.nameToken, LocalKind.LOCAL_FUN,
                        KtRange(function.declarationStart, statement.block.end), null, null, false, false, false,
                        statement,
                    ),
                )
            }
            head.text == "class" || head.text == "object" || head.text == "interface" -> {
                val name = code.code.getOrNull(index + 1)?.takeIf { isNameToken(it) } ?: continue
                code.keywordTokens.add(name.index)
                locals.add(
                    KtLocal(
                        name.text.removeSurrounding("`"), name, LocalKind.LOCAL_CLASS,
                        KtRange(code.code[statement.startTokenIndex].start, statement.block.end), null, null,
                        false, false, false, statement,
                    ),
                )
            }
        }
    }
}

private fun collectValVar(
    code: KotlinCode,
    valIndex: Int,
    statement: KtStatement,
    lateinit: Boolean,
    locals: MutableList<KtLocal>,
) {
    val keyword = code.code[valIndex]
    var index = valIndex + 1
    val names = ArrayList<KtToken>()
    var destructured = false
    var type: String? = null
    var delegated = false
    var initializer: KtRange? = null

    if (code.code.getOrNull(index)?.text == "(") {
        destructured = true
        val open = index
        val close = code.matchingIndex(open)
        if (close < 0) return
        for ((start, end) in splitTopLevel(code, open + 1, close, ",")) {
            lastNameBeforeType(code, start, end)?.let { if (it.text != "_") names.add(it) }
        }
        index = close + 1
        if (code.code.getOrNull(index)?.text == ":") {
            val end = findTopLevel(code, index + 1, statement.endTokenIndex + 1, "=")
            val byIndex = findTopLevel(code, index + 1, statement.endTokenIndex + 1, "by")
            val stop = listOf(end, byIndex).filter { it >= 0 }.minOrNull() ?: (statement.endTokenIndex + 1)
            if (stop > index + 1) type = code.text.substring(code.code[index + 1].start, code.code[stop - 1].end).trim()
            index = stop
        }
    } else {
        val name = code.code.getOrNull(index)?.takeIf { isNameToken(it) }
        if (name == null) return
        names.add(name)
        index++
        if (code.code.getOrNull(index)?.text == ":") {
            val equals = findTopLevel(code, index + 1, statement.endTokenIndex + 1, "=")
            val by = findTopLevel(code, index + 1, statement.endTokenIndex + 1, "by")
            val stop = listOf(equals, by).filter { it >= 0 }.minOrNull() ?: (statement.endTokenIndex + 1)
            if (stop > index + 1) type = code.text.substring(code.code[index + 1].start, code.code[stop - 1].end).trim()
            index = stop
        }
    }

    val assign = code.code.getOrNull(index)
    if (assign != null) {
        if (assign.text == "=") {
            initializer = code.tokenAfter(assign.end)?.let { KtRange(it.start, statement.end) }
        } else if (assign.text == "by") {
            delegated = true
            initializer = code.tokenAfter(assign.end)?.let { KtRange(it.start, statement.end) }
        }
    }

    for (name in names) {
        locals.add(
            KtLocal(
                name.text.removeSurrounding("`"), name,
                if (keyword.text == "val") LocalKind.VAL else LocalKind.VAR,
                KtRange(statement.end, statement.block.end), type, initializer, destructured, delegated,
                lateinit, statement,
            ),
        )
    }
}

private fun collectForVariables(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "for") continue
        val open = code.code.getOrNull(token.index + 1) ?: continue
        if (open.text != "(") continue
        val closeIndex = code.matchingIndex(open.index)
        if (closeIndex < 0) continue
        val inIndex = findTopLevel(code, open.index + 1, closeIndex, "in")
        if (inIndex < 0) continue
        val names = ArrayList<KtToken>()
        val first = code.code.getOrNull(open.index + 1)
        if (first != null && first.text == "(") {
            val close = code.matchingIndex(first.index)
            if (close in (first.index + 1) until closeIndex) {
                for ((start, end) in splitTopLevel(code, first.index + 1, close, ",")) {
                    lastNameBeforeType(code, start, end)?.let { if (it.text != "_") names.add(it) }
                }
            }
        } else {
            lastNameBeforeType(code, open.index + 1, inIndex)?.let { if (it.text != "_") names.add(it) }
        }
        val destructured = first?.text == "("
        val scope = bodyScopeAfter(code, closeIndex)
        for (name in names) {
            locals.add(
                KtLocal(
                    name.text.removeSurrounding("`"), name, LocalKind.FOR, scope, null, null,
                    destructured, false, false, null,
                ),
            )
        }
    }
}

private fun collectCatchParameters(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "catch") continue
        val open = code.code.getOrNull(token.index + 1) ?: continue
        if (open.text != "(") continue
        val closeIndex = code.matchingIndex(open.index)
        if (closeIndex < 0) continue
        val name = lastNameBeforeType(code, open.index + 1, closeIndex) ?: continue
        val type = typeTextAfterColon(code, open.index + 1, closeIndex)
        val scope = bodyScopeAfter(code, closeIndex)
        locals.add(
            KtLocal(name.text.removeSurrounding("`"), name, LocalKind.CATCH, scope, type, null, false, false, false, null),
        )
    }
}

private fun collectWhenSubjects(code: KotlinCode, locals: MutableList<KtLocal>) {
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "when") continue
        val open = code.code.getOrNull(token.index + 1) ?: continue
        if (open.text != "(") continue
        val closeIndex = code.matchingIndex(open.index)
        if (closeIndex < 0) continue
        val first = code.code.getOrNull(open.index + 1) ?: continue
        if (first.text != "val" && first.text != "var") continue
        val equals = findTopLevel(code, open.index + 1, closeIndex, "=")
        if (equals < 0) continue
        val name = lastNameBeforeType(code, open.index + 2, equals) ?: continue
        val type = typeTextAfterColon(code, open.index + 2, equals)
        val body = code.blocks.firstOrNull { it.open.index == closeIndex + 1 && it.kind == BlockKind.WHEN_BODY }
        val scope = body?.let { KtRange(it.start, it.end) } ?: KtRange(code.code[closeIndex].end, code.code[closeIndex].end)
        val initializer = if (equals + 1 < closeIndex) {
            code.tokenAfter(code.code[equals].end)?.let { KtRange(it.start, code.code[closeIndex].start) }
        } else {
            null
        }
        locals.add(
            KtLocal(
                name.text.removeSurrounding("`"), name, LocalKind.WHEN_SUBJECT, scope,
                type, initializer, false, false, false, null,
            ),
        )
    }
}

/** Content range of the block or statement right after the closing `)` at [closeIndex]. */
private fun bodyScopeAfter(code: KotlinCode, closeIndex: Int): KtRange {
    val next = code.code.getOrNull(closeIndex + 1)
    val block = code.blocks.firstOrNull { it.open.index == closeIndex + 1 }
    if (next != null && next.text == "{" && block != null) return KtRange(block.start, block.end)
    val statement = code.statementAt(code.code[closeIndex].start)
    val end = statement?.end ?: (next?.end ?: code.code[closeIndex].end)
    val start = next?.start ?: end
    return KtRange(start, end)
}

private fun findTopLevel(code: KotlinCode, startIndex: Int, endIndex: Int, text: String): Int {
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (token.text == text) return index
        if (code.isOpener(token)) {
            val close = code.matchingIndex(index)
            if (close in (index + 1) until endIndex) {
                index = close + 1
                continue
            }
        }
        index++
    }
    return -1
}

private fun splitTopLevel(
    code: KotlinCode,
    startIndex: Int,
    endIndex: Int,
    separator: String,
): List<Pair<Int, Int>> {
    val result = ArrayList<Pair<Int, Int>>()
    var segmentStart = startIndex
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (code.isOpener(token)) {
            val close = code.matchingIndex(index)
            if (close in (index + 1) until endIndex) {
                index = close + 1
                continue
            }
        }
        if (token.text == separator) {
            result.add(segmentStart to index)
            segmentStart = index + 1
        }
        index++
    }
    result.add(segmentStart to endIndex)
    return result
}

/** Index of the last token of the annotation starting at [atIndex]. */
private fun annotationEnd(code: KotlinCode, atIndex: Int): Int {
    var index = atIndex + 1
    val first = code.code.getOrNull(index) ?: return atIndex
    if (!isNameToken(first)) return atIndex
    index++
    if (code.code.getOrNull(index)?.text == ":") {
        index++
        if (code.code.getOrNull(index)?.let { isNameToken(it) } == true) index++
    }
    if (code.code.getOrNull(index)?.text == "(") {
        val close = code.matchingIndex(index)
        if (close >= 0) index = close + 1
    }
    return index - 1
}

internal fun computeReferences(code: KotlinCode): List<KtReference> {
    markNonExpressionRegions(code)
    markDeclarationNames(code)
    for (token in code.tokens) {
        if (token.type != KotlinTokenTypes.STRING) continue
        for (inner in code.code) {
            if (inner.start >= token.start && inner.end <= token.end) code.templateTokens.add(inner.index)
        }
        if (token.text.endsWith("\${")) {
            val next = code.tokens.firstOrNull { it.type == KotlinTokenTypes.STRING && it.start >= token.end }
            code.templateExpressionRanges.add(KtRange(token.end, next?.start ?: code.text.length))
        }
    }

    val references = ArrayList<KtReference>()
    for (token in code.code) {
        if (!isNameToken(token)) continue
        if (token.index in code.keywordTokens) continue
        val previous = code.code.getOrNull(token.index - 1)
        if (previous != null && (previous.type == KotlinTokenTypes.DOT || previous.text == "::" || previous.text == "@")) continue
        if (isNamedArgument(code, token)) continue
        val next = code.code.getOrNull(token.index + 1)
        if (next != null && next.text == "@") continue
        val usage = when {
            token.index in code.templateTokens -> TemplateUsage.SIMPLE
            code.templateExpressionRanges.any { it.contains(token.start) } -> TemplateUsage.EXPRESSION
            else -> TemplateUsage.NONE
        }
        if (usage == TemplateUsage.SIMPLE) code.simpleTemplateTokens.add(token.index)
        if (usage != TemplateUsage.NONE) code.templateTokens.add(token.index)
        val name = token.text.removeSurrounding("`")
        references.add(KtReference(token, name, usage, resolveLocal(code.locals, name, token)))
    }
    return references
}

private fun markDeclarationNames(code: KotlinCode) {
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD) continue
        when {
            token.text == "class" || token.text == "interface" || token.text == "object" ||
                token.text == "typealias" -> {
                val next = code.code.getOrNull(token.index + 1)
                if (next != null && isNameToken(next)) {
                    code.keywordTokens.add(next.index)
                    code.declarationNameTokens.add(next.index)
                }
            }
            token.text == "val" || token.text == "var" -> markPropertyNames(code, token)
            token.text == "catch" || token.text == "constructor" || token.text == "init" ||
                token.text == "get" || token.text == "set" -> {
                val next = code.code.getOrNull(token.index + 1)
                if (next != null && (next.text == "(" || next.text == "{")) code.keywordTokens.add(token.index)
            }
            token.text == "by" -> {
                if (!isDotBeforeToken(code, token.index)) code.keywordTokens.add(token.index)
            }
            token.text in KotlinCode.MODIFIER_WORDS -> {
                if (isModifierWord(code, token)) {
                    code.keywordTokens.add(token.index)
                    code.modifierTokens.add(token.index)
                }
            }
        }
    }
}

/** Declaration keywords a modifier word can precede. */
private val DECLARATION_KEYWORDS_AFTER_MODIFIER = setOf(
    "fun", "val", "var", "class", "interface", "object", "constructor", "typealias", "init",
    "get", "set",
)

/** Modifier words that belong to a parameter (`vararg x:`). */
private val PARAMETER_MODIFIER_WORDS = setOf("vararg", "noinline", "crossinline")

/** Modifier words that belong to a type parameter (`reified T`, `out T`). */
private val TYPE_PARAMETER_MODIFIER_WORDS = setOf("reified", "out")

/**
 * True when the modifier word at [token] modifies a declaration, a parameter or a type parameter
 * rather than being a name (R2 「Locals and references」).
 */
private fun isModifierWord(code: KotlinCode, token: KtToken): Boolean {
    val next = code.code.getOrNull(token.index + 1)
    if (next != null) {
        if (next.text == "@") return true
        if (next.type == KotlinTokenTypes.KEYWORD &&
            (next.text in DECLARATION_KEYWORDS_AFTER_MODIFIER || next.text in KotlinCode.MODIFIER_WORDS)
        ) {
            return !code.splitsAfter(token, next)
        }
        if (token.text in PARAMETER_MODIFIER_WORDS && isNameToken(next) &&
            code.code.getOrNull(next.index + 1)?.text == ":"
        ) {
            return true
        }
    }
    return token.text in TYPE_PARAMETER_MODIFIER_WORDS && insideTypeParameterList(code, token.index)
}

/** True when the token at [index] sits in the `<…>` type parameter list of a declaration. */
private fun insideTypeParameterList(code: KotlinCode, index: Int): Boolean {
    var depth = 0
    var cursor = index - 1
    while (cursor >= 0) {
        val token = code.code[cursor]
        val closes = token.text.count { it == '>' }
        when {
            closes > 0 -> depth += closes
            token.text == "<" -> {
                if (depth == 0) return isTypeArgumentOpen(code, cursor)
                depth--
            }
            token.text == "{" || token.text == "}" || token.text == ";" -> return false
        }
        cursor--
    }
    return false
}

private fun markPropertyNames(code: KotlinCode, keyword: KtToken) {
    val next = code.code.getOrNull(keyword.index + 1) ?: return
    if (next.text == "(") {
        val close = code.matchingIndex(next.index)
        if (close < 0) return
        for (index in next.index + 1 until close) {
            val token = code.code[index]
            if (!isNameToken(token)) continue
            val previous = code.code.getOrNull(index - 1)
            if (previous?.text != ":") {
                code.keywordTokens.add(token.index)
                code.declarationNameTokens.add(token.index)
            }
        }
    } else if (isNameToken(next)) {
        code.keywordTokens.add(next.index)
        code.declarationNameTokens.add(next.index)
    }
}

private fun isDotBeforeToken(code: KotlinCode, index: Int): Boolean {
    val before = code.code.getOrNull(index - 1) ?: return false
    return before.type == KotlinTokenTypes.DOT ||
        (before.text == "?" && code.code.getOrNull(index - 2)?.type == KotlinTokenTypes.DOT)
}

private fun markNonExpressionRegions(code: KotlinCode) {
    val seenAt = HashSet<Int>()
    for (token in code.code) {
        if (token.text == "@" && seenAt.add(token.start)) {
            val end = annotationEnd(code, token.index)
            if (end > token.index) {
                code.nonExpressionRanges.add(KtRange(token.start, code.code[end].end))
                for (index in token.index..end) code.keywordTokens.add(code.code[index].index)
            }
        }
        if (token.type == KotlinTokenTypes.KEYWORD && (token.text == "import" || token.text == "package")) {
            val lineStart = code.lineStart(token.start)
            val lineEnd = code.lineEnd(token.start)
            code.nonExpressionRanges.add(KtRange(lineStart, lineEnd))
            for (index in token.index until code.code.size) {
                val member = code.code[index]
                if (member.start >= lineEnd) break
                code.keywordTokens.add(member.index)
            }
        }
    }
}

private fun isNamedArgument(code: KotlinCode, token: KtToken): Boolean {
    val next = code.code.getOrNull(token.index + 1) ?: return false
    if (next.text != "=") return false
    val previous = code.code.getOrNull(token.index - 1) ?: return false
    return previous.text == "(" || previous.text == ","
}

private fun resolveLocal(locals: List<KtLocal>, name: String, token: KtToken): KtLocal? {
    var best: KtLocal? = null
    for (local in locals) {
        if (local.name != name) continue
        if (local.nameToken.index == token.index) continue
        if (!local.contains(token.start)) continue
        if (best == null || local.scope.length < best.scope.length ||
            (local.scope.length == best.scope.length && local.nameToken.start > best.nameToken.start)
        ) {
            best = local
        }
    }
    return best
}

/** The name a token spells: its text without the backticks a Kotlin identifier may carry. */
internal fun KtToken.spelling(): String = text.removeSurrounding("`")

/**
 * True when a code token other than a modifier in [scope] spells [name], so a local of that name
 * would give the spelling a second meaning (R3 「Name check」).
 */
internal fun isNameUsedIn(code: KotlinCode, scope: KtRange, name: String): Boolean =
    code.code.any {
        scope.contains(it.start) && it.spelling() == name && it.index !in code.modifierTokens
    }

/**
 * Code tokens in [scope] spelled [name] that the model cannot place: not a reference, a
 * declaration, a member name after `.`, `?.` or `::`, a named argument, a label or a modifier
 * (R2 「Unread spellings」).
 */
internal fun KotlinCode.unreadSpellings(name: String, scope: KtRange): List<KtToken> {
    val referenceTokens = references.mapTo(HashSet()) { it.token.index }
    return code.filter { token ->
        scope.contains(token.start) && token.spelling() == name &&
            token.index !in referenceTokens &&
            token.index !in declarationTokens &&
            token.index !in declarationNameTokens &&
            token.index !in modifierTokens &&
            !isPlaceableTokenName(token)
    }
}

/** True when [token] is a member name, a named argument, a label or a label use rather than a bare name. */
private fun KotlinCode.isPlaceableTokenName(token: KtToken): Boolean {
    val before = code.getOrNull(token.index - 1)
    if (before != null) {
        if (before.type == KotlinTokenTypes.DOT || before.text == "::") return true
        if (before.text == "?" && code.getOrNull(token.index - 2)?.type == KotlinTokenTypes.DOT) return true
        if (before.text == "@") {
            val user = code.getOrNull(token.index - 2)
            return user != null && user.type == KotlinTokenTypes.KEYWORD && user.text in LABEL_USER_KEYWORDS
        }
    }
    val after = code.getOrNull(token.index + 1) ?: return false
    if (after.text == "@") return true
    if (after.text != "=") return false
    return before == null || before.text == "(" || before.text == ","
}

/** Keywords that take a label after `@`. */
private val LABEL_USER_KEYWORDS = setOf("return", "break", "continue", "this", "super")
