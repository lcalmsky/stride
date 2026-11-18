package stride.lang.kotlin.generate

import stride.lang.kotlin.KotlinTokenTypes
import stride.lang.kotlin.refactor.BlockKind
import stride.lang.kotlin.refactor.KtBlock
import stride.lang.kotlin.refactor.KtRange
import stride.lang.kotlin.refactor.KtToken
import stride.lang.kotlin.refactor.KotlinCode
import stride.lang.kotlin.refactor.angleClose
import stride.lang.kotlin.refactor.expressionRangeAfter
import stride.lang.kotlin.refactor.isNameToken
import stride.lang.kotlin.refactor.parseParameter
import stride.lang.kotlin.refactor.parseTypeParameterNames

internal class SigParameter(
    val name: String,
    val nameToken: KtToken,
    val type: String?,
    val vararg: Boolean,
)

internal enum class SigContainer { TOP_LEVEL, CLASS, LOCAL }

/**
 * A `fun` declaration header. Unlike the refactoring model's `KtFunction` it accepts a declaration
 * without a body, which every abstract member of a supertype is.
 */
internal class SigFunction(
    val code: KotlinCode,
    val name: String,
    val nameToken: KtToken,
    val modifiers: Set<String>,
    val typeParameterNames: List<String>,
    val typeParametersText: String?,
    val receiver: String?,
    val parameters: List<SigParameter>,
    val returnType: String?,
    val whereText: String?,
    val body: KtBlock?,
    val expressionBody: KtRange?,
    val end: Int,
    val container: SigContainer,
) {
    val hasBody: Boolean get() = body != null || expressionBody != null
}

private val ANONYMOUS_FUN_PRECEDERS = setOf(
    "=", "(", ",", "return", ":", "->", "&&", "||", "?:", "!", "+", "-", "*", "/",
    "%", "[", "else", "throw", "as", "is", "in", "..",
)

internal fun signatureFunctionAt(code: KotlinCode, funIndex: Int): SigFunction? = parseSignatureFunction(code, funIndex)

/** The names declared by a `<…>` type parameter list; a bound after `:` does not hide the name. */
internal fun signatureTypeParameterNames(code: KotlinCode, openIndex: Int, closeIndex: Int): List<String> {
    val names = ArrayList<String>()
    var segmentStart = openIndex + 1
    var index = openIndex + 1
    var depth = 0
    while (index < closeIndex) {
        val token = code.code[index]
        when {
            code.isOpener(token) -> {
                val close = code.matchingIndex(index)
                index = if (close in (index + 1) until closeIndex) close + 1 else index + 1
                continue
            }
            token.text == "<" -> depth++
            token.text.isNotEmpty() && token.text.all { it == '>' } -> depth = (depth - token.text.length).coerceAtLeast(0)
            token.text == "," && depth == 0 -> {
                segmentName(code, segmentStart, index)?.let { names.add(it) }
                segmentStart = index + 1
            }
        }
        index++
    }
    segmentName(code, segmentStart, closeIndex)?.let { names.add(it) }
    return names
}

private fun segmentName(code: KotlinCode, startIndex: Int, endIndex: Int): String? {
    var index = startIndex
    while (index < endIndex) {
        val token = code.code[index]
        if (token.text == "@") {
            index = annotationEnd(code, index) + 1
            continue
        }
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            index++
            continue
        }
        if (isNameToken(token)) return token.text.removeSurrounding("`")
        index++
    }
    return null
}

private fun parseSignatureFunction(code: KotlinCode, funIndex: Int): SigFunction? {
    val funToken = code.code.getOrNull(funIndex) ?: return null
    var cursor = funIndex + 1
    var typeParametersText: String? = null
    var typeParameterNames: List<String> = emptyList()
    if (code.code.getOrNull(cursor)?.text == "<") {
        val close = angleClose(code, cursor)
        if (close < 0) return null
        typeParametersText = code.text.substring(code.code[cursor].start, code.code[close].end)
        typeParameterNames = signatureTypeParameterNames(code, cursor, close)
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
                    val open = stride.lang.kotlin.refactor.angleOpenBefore(code, scan - 1)
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

    var receiver: String? = null
    if (nameIndex > cursor) {
        var endIndex = nameIndex - 1
        if (code.code[endIndex].type == KotlinTokenTypes.DOT) endIndex--
        if (endIndex >= cursor) {
            receiver = code.text.substring(code.code[cursor].start, code.code[endIndex].end).trim()
        }
    }

    val parameters = ArrayList<SigParameter>()
    for (segment in parameterSegmentRanges(code, paramsOpen, paramsClose)) {
        val parsed = parseParameter(code, segment.first, segment.last + 1) ?: continue
        parameters.add(SigParameter(parsed.name, parsed.nameToken, parsed.type, parsed.vararg))
    }

    var bodyStart = paramsClose + 1
    var returnType: String? = null
    if (code.code.getOrNull(bodyStart)?.text == ":") {
        val from = bodyStart + 1
        var index = from
        while (index < code.code.size) {
            val token = code.code[index]
            if (token.text == "where" || token.text == "{" || token.text == "=" || token.text == ";" || token.text == "}") break
            if (index > from && code.splitsAfter(code.code[index - 1], token)) break
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
    var whereText: String? = null
    if (code.code.getOrNull(bodyStart)?.text == "where") {
        val from = bodyStart
        var index = bodyStart
        while (index < code.code.size) {
            val token = code.code[index]
            if (token.text == "{" || token.text == "=" || token.text == "}" || token.text == ";") break
            if (index > from && code.splitsAfter(code.code[index - 1], token)) break
            index++
        }
        if (index > from) whereText = code.text.substring(code.code[from].start, code.code[index - 1].end).trim()
        bodyStart = index
    }

    var body: KtBlock? = null
    var expressionBody: KtRange? = null
    var end = code.code.getOrNull(bodyStart - 1)?.end ?: funToken.end
    when (code.code.getOrNull(bodyStart)?.text) {
        "{" -> {
            body = code.blocks.firstOrNull { it.open.index == bodyStart }
            end = body?.close?.end ?: return null
        }
        "=" -> {
            val range = expressionRangeAfter(code, code.code[bodyStart].end) ?: return null
            expressionBody = range
            end = range.end
        }
        else -> Unit
    }

    val modifiers = LinkedHashSet<String>()
    var firstIndex = funIndex
    var back = funIndex - 1
    while (back >= 0) {
        val token = code.code[back]
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            modifiers.add(token.text)
            firstIndex = back
            back--
            continue
        }
        val annotation = annotationStartEndingAt(code, back)
        if (annotation >= 0) {
            firstIndex = annotation
            back = annotation - 1
            continue
        }
        break
    }

    val chain = code.blockChain(funToken.start)
    val innermost = chain.lastOrNull()
    val container = when {
        innermost == null -> SigContainer.TOP_LEVEL
        innermost.kind == BlockKind.CLASS_BODY -> SigContainer.CLASS
        else -> SigContainer.LOCAL
    }
    return SigFunction(
        code = code,
        name = nameToken.text.removeSurrounding("`"),
        nameToken = nameToken,
        modifiers = modifiers,
        typeParameterNames = typeParameterNames,
        typeParametersText = typeParametersText,
        receiver = receiver,
        parameters = parameters,
        returnType = returnType,
        whereText = whereText,
        body = body,
        expressionBody = expressionBody,
        end = end,
        container = container,
    )
}
