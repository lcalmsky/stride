package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

/** One declared parameter: name, written type text, `vararg` and default value range. */
internal class KtParameter(
    val name: String,
    val nameToken: KtToken,
    val type: String?,
    val vararg: Boolean,
    val defaultRange: KtRange?,
) {
    val range: KtRange get() = nameToken.range
}

internal enum class FunctionContainer { TOP_LEVEL, CLASS, LOCAL }

/** A `fun` declaration header with everything Extract Method and Inline ask about it. */
internal class KtFunction(
    val funToken: KtToken,
    val name: String,
    val nameToken: KtToken,
    val modifiers: Set<String>,
    val annotations: List<String>,
    val typeParameters: List<String>,
    val receiver: String?,
    val receiverRange: KtRange?,
    /** The `(` opening the parameter list; calls never start there. */
    val parameterListOpen: KtToken,
    val parameters: List<KtParameter>,
    val returnType: String?,
    /** The FUNCTION_BODY block for a block body, `null` for `= expression`. */
    val body: KtBlock?,
    /** The expression range when the body is written `= expression`. */
    val expressionRange: KtRange?,
    /** Line start of the declaration without KDoc and annotations. */
    val declarationStart: Int,
    /** Line start including the KDoc and annotation lines. */
    val declarationStartWithDocs: Int,
    val declarationEnd: Int,
    val container: FunctionContainer,
    val containerBlock: KtBlock?,
) {
    /** The offsets the body occupies: the block content or the `= expression` range. */
    val bodyRange: KtRange?
        get() = body?.let { KtRange(it.start, it.end) } ?: expressionRange

    fun contains(offset: Int): Boolean = offset >= declarationStart && offset <= declarationEnd

    val isInterfaceMember: Boolean
        get() = containerBlock?.isInterfaceBody == true
}

private val ANONYMOUS_FUN_PRECEDERS = setOf(
    "=", "(", ",", "return", ":", "->", "&&", "||", "?:", "?", "!", "+", "-", "*", "/",
    "%", "[", "else", "throw", "as", "is", "in", "..",
)

internal fun computeFunctions(code: KotlinCode): List<KtFunction> {
    val result = ArrayList<KtFunction>()
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "fun") continue
        val previous = code.code.getOrNull(token.index - 1)
        if (previous != null && previous.text in ANONYMOUS_FUN_PRECEDERS) continue
        val parsed = parseFunction(code, token.index) ?: continue
        result.add(parsed)
        code.keywordTokens.add(parsed.nameToken.index)
        parsed.parameters.forEach { code.keywordTokens.add(it.nameToken.index) }
    }
    return result
}

private fun parseFunction(code: KotlinCode, funIndex: Int): KtFunction? {
    val funToken = code.code[funIndex]
    var cursor = funIndex + 1
    val typeParameters = ArrayList<String>()
    if (code.code.getOrNull(cursor)?.text == "<") {
        val close = angleClose(code, cursor)
        if (close < 0) return null
        typeParameters.addAll(parseTypeParameterNames(code, cursor, close))
        cursor = close + 1
    }

    var paramsOpen = -1
    var nameIndex = -1
    var scan = cursor
    while (scan < code.code.size) {
        val token = code.code[scan]
        when {
            token.text == "{" || token.text == "=" || token.text == ";" || token.text == "}" -> break
            token.text == "(" -> {
                val previous = code.code.getOrNull(scan - 1)
                if (previous != null && previous.text == ">") {
                    val open = angleOpenBefore(code, scan - 1)
                    val name = if (open > 0) code.code.getOrNull(open - 1) else null
                    if (name != null && isNameToken(name)) {
                        paramsOpen = scan
                        nameIndex = open - 1
                        break
                    }
                }
                if (previous != null && isNameToken(previous)) {
                    paramsOpen = scan
                    nameIndex = scan - 1
                    break
                }
                val close = code.matchingIndex(scan)
                if (close < 0) return null
                scan = close + 1
            }
            code.isOpener(token) -> {
                val close = code.matchingIndex(scan)
                if (close < 0) return null
                scan = close + 1
            }
            code.isCloser(token) -> return null
            else -> scan++
        }
    }
    if (paramsOpen < 0 || nameIndex < 0) return null
    val paramsClose = code.matchingIndex(paramsOpen)
    if (paramsClose < 0) return null

    val nameToken = code.code[nameIndex]
    val name = nameToken.text.removeSurrounding("`")
    var receiver: String? = null
    var receiverRange: KtRange? = null
    if (nameIndex > cursor) {
        var endIndex = nameIndex - 1
        if (code.code[endIndex].type == KotlinTokenTypes.DOT) endIndex--
        if (endIndex >= cursor) {
            val start = code.code[cursor].start
            val end = code.code[endIndex].end
            receiver = code.text.substring(start, end).trim()
            receiverRange = KtRange(start, end)
        }
    }

    val parameters = parseParameterList(code, paramsOpen, paramsClose)

    var bodyStart = paramsClose + 1
    var returnType: String? = null
    if (code.code.getOrNull(bodyStart)?.text == ":") {
        val from = bodyStart + 1
        var index = from
        while (index < code.code.size) {
            val token = code.code[index]
            if (token.text == "where" || token.text == "{" || token.text == "=" || token.text == ";" || token.text == "}") break
            if (code.isOpener(token)) {
                val close = code.matchingIndex(index)
                if (close < 0) return null
                index = close + 1
            } else {
                index++
            }
        }
        if (index > from) returnType = code.text.substring(code.code[from].start, code.code[index - 1].end).trim()
        bodyStart = index
    }
    if (code.code.getOrNull(bodyStart)?.text == "where") {
        while (bodyStart < code.code.size && code.code[bodyStart].text != "{" && code.code[bodyStart].text != "=") bodyStart++
    }

    var body: KtBlock? = null
    var expressionRange: KtRange? = null
    var declarationEnd = funToken.end
    val bodyToken = code.code.getOrNull(bodyStart)
    when {
        bodyToken == null -> return null
        bodyToken.text == "{" -> {
            body = code.blocks.firstOrNull { it.open.index == bodyStart && it.kind == BlockKind.FUNCTION_BODY }
                ?: return null
            declarationEnd = body.close?.end ?: return null
        }
        bodyToken.text == "=" -> {
            val range = expressionRangeAfter(code, bodyToken.end) ?: return null
            expressionRange = range
            declarationEnd = range.end
        }
        else -> return null
    }

    val starts = declarationStarts(code, funIndex)

    val chain = code.blockChain(funToken.start)
    val innermost = chain.lastOrNull()
    val container = when {
        innermost == null -> FunctionContainer.TOP_LEVEL
        innermost.kind == BlockKind.CLASS_BODY -> FunctionContainer.CLASS
        else -> FunctionContainer.LOCAL
    }

    return KtFunction(
        funToken = funToken,
        name = name,
        nameToken = nameToken,
        modifiers = starts.modifiers,
        annotations = starts.annotations,
        typeParameters = typeParameters,
        receiver = receiver,
        receiverRange = receiverRange,
        parameterListOpen = code.code[paramsOpen],
        parameters = parameters,
        returnType = returnType,
        body = body,
        expressionRange = expressionRange,
        declarationStart = starts.declarationStart,
        declarationStartWithDocs = starts.declarationStartWithDocs,
        declarationEnd = declarationEnd,
        container = container,
        containerBlock = if (container == FunctionContainer.CLASS) innermost else null,
    )
}

private class DeclarationStarts(
    val modifiers: Set<String>,
    val annotations: List<String>,
    val declarationStart: Int,
    val declarationStartWithDocs: Int,
)

private fun declarationStarts(code: KotlinCode, funIndex: Int): DeclarationStarts {
    val modifiers = LinkedHashSet<String>()
    val annotations = ArrayList<String>()
    var cursor = funIndex
    var firstTokenIndex = funIndex
    while (true) {
        val token = code.code.getOrNull(cursor - 1) ?: break
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            modifiers.add(token.text)
            code.keywordTokens.add(token.index)
            code.modifierTokens.add(token.index)
            firstTokenIndex = cursor - 1
            cursor--
            continue
        }
        val annotationStart = annotationStartEndingAt(code, cursor - 1)
        if (annotationStart >= 0) {
            annotations.add(0, code.text.substring(code.code[annotationStart].start, code.code[cursor - 1].end))
            for (index in annotationStart..cursor - 1) code.keywordTokens.add(code.code[index].index)
            firstTokenIndex = annotationStart
            cursor = annotationStart
            continue
        }
        break
    }
    val declarationStart = code.lineStart(code.code[firstTokenIndex].start)
    var earliest = code.code[firstTokenIndex].start
    val firstToken = code.code[firstTokenIndex]
    val before = code.tokens.lastOrNull { it.index < 0 && it.isComment && it.end <= firstToken.start }
    if (before != null && before.type == KotlinTokenTypes.BLOCK_COMMENT && before.text.startsWith("/**") &&
        code.isBlank(before.end, firstToken.start)
    ) {
        earliest = before.start.coerceAtMost(earliest)
    }
    return DeclarationStarts(modifiers, annotations, declarationStart, code.lineStart(earliest))
}

/** Index of the `@` opening the annotation whose last token is [endIndex], or `-1`. */
private fun annotationStartEndingAt(code: KotlinCode, endIndex: Int): Int {
    if (endIndex < 0) return -1
    val token = code.code[endIndex]
    if (token.text == ")") {
        val open = code.matchingIndex(endIndex)
        if (open < 0) return -1
        return annotationStartEndingAt(code, open - 1)
    }
    if (!isNameToken(token)) return -1
    val before = code.code.getOrNull(endIndex - 1) ?: return -1
    if (before.text == "@") return endIndex - 1
    if (before.text == ":") {
        val target = code.code.getOrNull(endIndex - 2) ?: return -1
        val at = code.code.getOrNull(endIndex - 3) ?: return -1
        if (isNameToken(target) && at.text == "@") return endIndex - 3
    }
    return -1
}

internal fun parseParameterList(code: KotlinCode, openIndex: Int, closeIndex: Int): List<KtParameter> {
    val result = ArrayList<KtParameter>()
    var segmentStart = openIndex + 1
    var index = openIndex + 1
    var angleDepth = 0
    while (index < closeIndex) {
        val token = code.code[index]
        when {
            code.isOpener(token) -> {
                val close = code.matchingIndex(index)
                if (close in (index + 1) until closeIndex) {
                    index = close + 1
                } else {
                    index++
                }
                continue
            }
            token.text == "<" -> angleDepth++
            token.text == ">" -> if (angleDepth > 0) angleDepth--
            token.text.isNotEmpty() && token.text.all { it == '>' } -> angleDepth = (angleDepth - token.text.length).coerceAtLeast(0)
            token.text == "," && angleDepth == 0 -> {
                parseParameter(code, segmentStart, index)?.let { result.add(it) }
                segmentStart = index + 1
            }
        }
        index++
    }
    parseParameter(code, segmentStart, closeIndex)?.let { result.add(it) }
    return result
}

private val PARAMETER_MODIFIERS = setOf("vararg", "noinline", "crossinline")

private fun parseParameter(code: KotlinCode, startIndex: Int, endIndex: Int): KtParameter? {
    if (startIndex >= endIndex) return null
    var colon = -1
    var defaultValue = -1
    var vararg = false
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        when {
            code.isOpener(token) -> {
                val close = code.matchingIndex(index)
                index = if (close in (index + 1) until endIndex) close + 1 else index + 1
                continue
            }
            token.text == ":" && colon < 0 && defaultValue < 0 -> colon = index
            token.text == "=" && defaultValue < 0 -> defaultValue = index
            token.text == "vararg" -> vararg = true
        }
        index++
    }
    val nameLimit = if (colon >= 0) colon else if (defaultValue >= 0) defaultValue else endIndex
    var nameToken: KtToken? = null
    for (i in startIndex until nameLimit) {
        val token = code.code[i]
        if (isNameToken(token) && token.text !in PARAMETER_MODIFIERS) nameToken = token
    }
    if (nameToken == null) {
        for (i in (if (colon >= 0) colon + 1 else startIndex) until endIndex) {
            val token = code.code[i]
            if (isNameToken(token) && token.text !in PARAMETER_MODIFIERS) {
                nameToken = token
                break
            }
        }
    }
    nameToken ?: return null
    val type = if (colon >= 0) {
        val typeEnd = if (defaultValue >= 0) defaultValue else endIndex
        if (typeEnd > colon + 1) code.text.substring(code.code[colon + 1].start, code.code[typeEnd - 1].end).trim() else null
    } else {
        null
    }
    val defaultRange = if (defaultValue >= 0 && endIndex > defaultValue + 1) {
        code.tokenAfter(code.code[defaultValue].end)?.let { KtRange(it.start, code.code[endIndex - 1].end) }
    } else {
        null
    }
    return KtParameter(nameToken.text.removeSurrounding("`"), nameToken, type, vararg, defaultRange)
}

internal fun parseTypeParameterNames(code: KotlinCode, openIndex: Int, closeIndex: Int): List<String> {
    val names = ArrayList<String>()
    var segmentStart = openIndex + 1
    var index = openIndex + 1
    var depth = 0
    fun finish(endExclusive: Int) {
        var i = segmentStart
        while (i < endExclusive) {
            val token = code.code[i]
            if (token.text == "(") {
                val close = code.matchingIndex(i)
                i = if (close in (i + 1) until endExclusive) close + 1 else i + 1
                continue
            }
            if (isNameToken(token) && token.text !in PARAMETER_MODIFIERS) {
                if (code.code.getOrNull(i + 1)?.text != ":") names.add(token.text.removeSurrounding("`"))
                return
            }
            i++
        }
    }
    while (index < closeIndex) {
        val token = code.code[index]
        when {
            code.isOpener(token) -> {
                val close = code.matchingIndex(index)
                index = if (close in (index + 1) until closeIndex) close + 1 else index + 1
                continue
            }
            token.text == "<" -> depth++
            token.text == ">" -> if (depth > 0) depth--
            token.text.isNotEmpty() && token.text.all { it == '>' } -> depth = (depth - token.text.length).coerceAtLeast(0)
            token.text == "," && depth == 0 -> {
                finish(index)
                segmentStart = index + 1
            }
        }
        index++
    }
    finish(closeIndex)
    return names
}

/** The index of the `}` matching the `<` at [openIndex], counting merged `>` tokens, or `-1`. */
internal fun angleClose(code: KotlinCode, openIndex: Int): Int {
    var depth = 0
    var index = openIndex
    while (index < code.code.size) {
        val token = code.code[index]
        when {
            token.text == "<" -> depth++
            token.text == ">" || (token.text.isNotEmpty() && token.text.all { it == '>' }) -> {
                depth -= token.text.length
                if (depth <= 0) return index
            }
        }
        index++
    }
    return -1
}

/** The index of the `<` matching the `>` at [closeIndex], or `-1`. */
internal fun angleOpenBefore(code: KotlinCode, closeIndex: Int): Int {
    var depth = 0
    var index = closeIndex
    while (index >= 0) {
        val token = code.code[index]
        when {
            token.text == ">" || (token.text.isNotEmpty() && token.text.all { it == '>' }) -> depth += token.text.length
            token.text == "<" -> {
                depth--
                if (depth <= 0) return index
            }
        }
        index--
    }
    return -1
}

/**
 * The range of an expression body starting after [afterOffset]: the same newline and `;`
 * boundaries a statement uses, and the enclosing `}`.
 */
internal fun expressionRangeAfter(code: KotlinCode, afterOffset: Int): KtRange? {
    val syntax = code.tokens.filter { !it.isComment }
    var index = syntax.indexOfFirst { it.start >= afterOffset }
    if (index < 0) return null
    var startOffset = -1
    var lastEnd = -1
    var lastToken: KtToken? = null
    while (index < syntax.size) {
        val token = syntax[index]
        if (token.text == ";" || token.text == "}") break
        if (lastToken != null && code.splitsAfter(lastToken, token)) break
        if (startOffset < 0) startOffset = token.start
        lastEnd = maxOf(lastEnd, token.end)
        lastToken = token
        if (code.isOpener(token)) {
            val close = code.matching(token.index)
            if (close != null) {
                lastEnd = maxOf(lastEnd, close.end)
                lastToken = close
                index++
                while (index < syntax.size && syntax[index].start < close.end) index++
                continue
            }
        }
        index++
    }
    return if (startOffset >= 0 && lastEnd > startOffset) KtRange(startOffset, lastEnd) else null
}
