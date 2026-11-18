package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

private val CHECK_CALLS = setOf("require", "check", "requireNotNull", "checkNotNull", "assert")

private val NULL_CHECKS = setOf("isNullOrEmpty", "isNullOrBlank")

private val EQUALITY_OPERATORS = setOf("==", "!=", "===", "!==")

private val OPENLIKE_MODIFIERS = setOf("open", "abstract", "sealed")

/** A chain R2 「Static types」 narrows, its spelling, and the token of the shape that narrows it. */
internal class KtSmartCast(
    val chain: String,
    val token: KtToken,
    /** The local the chain is rooted at, `null` when its head is a member or a top-level name. */
    internal val root: KtLocal?,
    /** The narrowed chain's names, head first; a read is compared after the head by the same root. */
    internal val names: List<String>,
)

/** A name chain a refactoring reads, with the local its head resolves to, if any. */
internal class KtReadChain(
    internal val head: KtToken,
    internal val root: KtLocal?,
    internal val names: List<String>,
)

/**
 * The first R2 「Static types」 shape in `[from, to)` that narrows a read inside [range], or `null`.
 * The returned cast names the narrowed chain and carries the shape's marker token.
 */
internal fun KotlinCode.smartCastNarrowing(range: KtRange, from: Int, to: Int): KtSmartCast? {
    val reads = readChains().filter { range.contains(it.head.start) && it.head.end <= range.end }
    if (reads.isEmpty()) return null
    return smartCastsBetween(from, to).firstOrNull { cast -> reads.any { cast.narrows(it) } }
}

/** The first shape in `[from, to)` that narrows a read of [local] itself, or `null`. */
internal fun KotlinCode.smartCastShape(local: KtLocal, from: Int, to: Int): KtSmartCast? =
    smartCastsBetween(from, to).firstOrNull { it.root === local && it.names.size == 1 }

/**
 * True when this file declares [name] as a class a value of no subtype can reach: no `open`,
 * `abstract`, `sealed`, interface or type parameters (R5 「Arguments」).
 */
internal fun KotlinCode.isFinalClassInFile(name: String): Boolean {
    for (index in code.indices) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "class") continue
        val previous = code.getOrNull(index - 1)
        if (previous != null && (previous.type == KotlinTokenTypes.DOT || previous.text == "::")) continue
        val className = code.getOrNull(index + 1) ?: continue
        if (!isNameToken(className) || className.spelling() != name) continue
        if (code.getOrNull(index + 2)?.text == "<") return false
        if (hasOpenlikeModifier(index)) return false
        return true
    }
    return false
}

private fun KotlinCode.hasOpenlikeModifier(classIndex: Int): Boolean {
    var index = classIndex - 1
    while (index >= 0) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD || token.text !in KotlinCode.MODIFIER_WORDS) return false
        if (token.text in OPENLIKE_MODIFIERS) return true
        index--
    }
    return false
}

// --- the shapes -------------------------------------------------------------------------------

private fun KotlinCode.smartCastsBetween(from: Int, to: Int): List<KtSmartCast> {
    val all = smartCastCache ?: computeSmartCasts().also { smartCastCache = it }
    return all.filter { it.token.start >= from && it.token.start < to }
}

private class Chain(val tokens: List<KtToken>, val optional: List<Boolean>) {
    /** `optional[k]` connects [tokens]`[k]` and [tokens]`[k + 1]` with `?.` when true. */
    val names: List<String> = tokens.map { it.spelling() }
}

/** A shape's operand: its leading chain and whether the operand is exactly that chain. */
private class Operand(val chain: Chain, val whole: Boolean)

private fun KotlinCode.computeSmartCasts(): List<KtSmartCast> {
    val typeParameters = fileTypeParameters()
    val casts = ArrayList<KtSmartCast>()
    fun add(operand: Operand?, marker: KtToken, equality: Boolean) {
        operand ?: return
        casts.addAll(narrowedCasts(operand, marker, equality, typeParameters))
    }
    for (index in code.indices) {
        val token = code[index]
        if (nonExpressionRanges.any { it.contains(token.start) }) continue
        when {
            token.type == KotlinTokenTypes.KEYWORD && token.text == "is" -> {
                val bang = previousToken(token)?.takeIf { it.text == "!" }
                add(leftOperandBefore(bang ?: token), bang ?: token, false)
            }
            token.type == KotlinTokenTypes.KEYWORD && token.text == "as" -> add(leftOperandBefore(token), token, false)
            token.text == "!!" -> add(leftOperandBefore(token), token, false)
            token.text == "?:" -> add(leftOperandBefore(token), token, false)
            token.type == KotlinTokenTypes.OPERATION_SIGN && token.text in EQUALITY_OPERATORS -> {
                add(leftOperandBefore(token), token, true)
                add(rightOperandAfter(token), token, true)
            }
            token.type == KotlinTokenTypes.KEYWORD && token.text == "when" -> add(whenSubject(token), token, false)
            isNameToken(token) && token.spelling() in NULL_CHECKS &&
                code.getOrNull(index + 1)?.text == "(" -> add(nullCheckOperand(token), token, false)
            token.type == KotlinTokenTypes.OPERATION_SIGN && token.text == "=" -> add(assignmentOperand(token), token, false)
        }
    }
    for (call in calls) {
        if (call.kind != CallKind.PARENTHESES) continue
        val close = call.close ?: continue
        val callee = code.getOrNull(call.open.index - 1) ?: continue
        if (!isNameToken(callee) || callee.spelling() !in CHECK_CALLS) continue
        if (nonExpressionRanges.any { it.contains(callee.start) }) continue
        for (operand in checkCallOperands(call.open, close)) {
            casts.addAll(narrowedCasts(operand, callee, false, typeParameters))
        }
    }
    casts.sortBy { it.token.start }
    return casts
}

private fun KotlinCode.narrowedCasts(
    operand: Operand,
    marker: KtToken,
    equality: Boolean,
    typeParameters: Set<String>,
): List<KtSmartCast> {
    val chain = operand.chain
    val lengths = LinkedHashSet<Int>()
    if (operand.whole) lengths.add(chain.tokens.size)
    for (k in chain.optional.indices) if (chain.optional[k]) lengths.add(k + 1)
    return castChains(chain, lengths, marker, equality, typeParameters)
}

private fun KotlinCode.castChains(
    chain: Chain,
    lengths: Set<Int>,
    marker: KtToken,
    equality: Boolean,
    typeParameters: Set<String>,
): List<KtSmartCast> {
    if (lengths.isEmpty()) return emptyList()
    val head = chain.tokens.first()
    val root = references.firstOrNull { it.token.index == head.index }?.resolved
    return lengths.mapNotNull { length ->
        // The equality exception is about the local itself; a member chain keeps its own type.
        if (equality && root != null && length == 1 && !equalityNarrows(root, typeParameters)) return@mapNotNull null
        val names = chain.tokens.take(length).map { it.spelling() }
        KtSmartCast(names.joinToString("."), marker, root, names)
    }
}

/** An equality shape removes only nullability, so a non-nullable local that is no type parameter stays (R2). */
private fun KotlinCode.equalityNarrows(root: KtLocal, typeParameters: Set<String>): Boolean {
    val type = root.writtenType?.trim() ?: return true
    if (type.endsWith("?")) return true
    return type in typeParameters
}

private fun KotlinCode.fileTypeParameters(): Set<String> {
    val names = HashSet<String>()
    for (function in functions) names.addAll(function.typeParameters)
    for (index in code.indices) {
        val token = code[index]
        if (token.type != KotlinTokenTypes.KEYWORD) continue
        if (token.text != "class" && token.text != "interface" && token.text != "typealias") continue
        val nameIndex = index + 1
        if (code.getOrNull(nameIndex)?.let { isNameToken(it) } != true) continue
        val openIndex = nameIndex + 1
        if (code.getOrNull(openIndex)?.text != "<") continue
        val close = angleClose(this, openIndex)
        if (close < 0) continue
        names.addAll(parseTypeParameterNames(this, openIndex, close))
    }
    return names
}

// --- the operands -----------------------------------------------------------------------------

/**
 * The postfix expression ending before [operator]; groups and `!!` make it more than its leading
 * chain. A string literal is one operand token, so an operator right after one has no chain.
 */
private fun KotlinCode.leftOperandBefore(operator: KtToken): Operand? {
    var token = previousToken(operator) ?: return null
    var whole = true
    while (true) {
        when {
            token.isStringBody -> return null
            isNameToken(token) -> return Operand(chainEndingAt(token) ?: return null, whole)
            token.text == ")" || token.text == "]" || token.text == "}" -> {
                val open = matching(token.index) ?: return null
                whole = false
                token = previousToken(open) ?: return null
            }
            token.text == "!!" -> {
                whole = false
                token = previousToken(token) ?: return null
            }
            else -> return null
        }
    }
}

/** The chain beginning at [operator]'s right token, when the operand may continue past it. */
private fun KotlinCode.rightOperandAfter(operator: KtToken): Operand? {
    val token = nextToken(operator) ?: return null
    if (!isNameToken(token)) return null
    val chain = chainStartingAt(token) ?: return null
    val next = nextToken(chain.tokens.last())
    val whole = next == null || !continuesOperand(next)
    return Operand(chain, whole)
}

private fun continuesOperand(token: KtToken): Boolean {
    if (token.isStringBody) return false
    return when (token.text) {
        "(", "[", ".", "!!", "{" -> true
        else -> token.text == "?" && token.type == KotlinTokenTypes.OPERATION_SIGN
    }
}

private fun KotlinCode.whenSubject(keyword: KtToken): Operand? {
    val open = nextToken(keyword) ?: return null
    if (open.text != "(") return null
    val close = matching(open.index) ?: return null
    val first = nextToken(open) ?: return null
    if (!isNameToken(first)) return null
    val chain = chainStartingAt(first) ?: return null
    return Operand(chain, nextToken(chain.tokens.last()) === close)
}

private fun KotlinCode.nullCheckOperand(name: KtToken): Operand? {
    val dot = previousToken(name) ?: return null
    if (dot.type != KotlinTokenTypes.DOT) return null
    val beforeDot = previousToken(dot) ?: return null
    val end = if (beforeDot.text == "?") previousToken(beforeDot) ?: return null else beforeDot
    if (!isNameToken(end)) return null
    return Operand(chainEndingAt(end) ?: return null, true)
}

private fun KotlinCode.assignmentOperand(equals: KtToken): Operand? {
    val previous = previousToken(equals) ?: return null
    if (!isNameToken(previous)) return null
    val local = references.firstOrNull { it.token.index == previous.index }?.resolved ?: return null
    val assignable = local.kind == LocalKind.VAR || (local.kind == LocalKind.VAL && local.initializer == null)
    if (!assignable) return null
    return Operand(chainStartingAt(previous) ?: return null, true)
}

// --- chains -----------------------------------------------------------------------------------

/** The chain of names ending at [end], joined by `.` or `?.` with a leading `this.` dropped. */
private fun KotlinCode.chainEndingAt(end: KtToken): Chain? {
    if (!isNameToken(end)) return null
    val reversedTokens = arrayListOf(end)
    val reversedConnectors = ArrayList<Boolean>()
    var cursor = end
    while (true) {
        val dot = previousToken(cursor) ?: break
        if (dot.type != KotlinTokenTypes.DOT) break
        val beforeDot = previousToken(dot)
        val optional = beforeDot?.text == "?"
        val before = if (optional) previousToken(beforeDot) ?: return null else beforeDot ?: return null
        if (before.text == "this" && before.type == KotlinTokenTypes.KEYWORD) break
        if (!isNameToken(before)) return null
        reversedConnectors.add(optional)
        reversedTokens.add(before)
        cursor = before
    }
    val tokens = reversedTokens.reversed()
    val optional = reversedConnectors.reversed()
    val head = tokens.first()
    val beforeHead = previousToken(head)
    if (beforeHead != null) {
        if (beforeHead.type == KotlinTokenTypes.DOT || beforeHead.text == "::" || beforeHead.text == "@") return null
        if (beforeHead.text == "?" && previousToken(beforeHead)?.type == KotlinTokenTypes.DOT) return null
    }
    return Chain(tokens, optional)
}

/** The chain of names beginning at [first]; a leading `this.` is dropped. */
private fun KotlinCode.chainStartingAt(first: KtToken): Chain? {
    var cursor = first
    var headIsThis = false
    if (first.text == "this" && first.type == KotlinTokenTypes.KEYWORD) {
        headIsThis = true
    } else if (!isNameToken(first)) {
        return null
    }
    val tokens = ArrayList<KtToken>()
    val optional = ArrayList<Boolean>()
    if (!headIsThis) tokens.add(first)
    while (true) {
        val next = nextToken(cursor) ?: break
        val isOptional: Boolean
        val dot: KtToken
        if (next.type == KotlinTokenTypes.DOT) {
            isOptional = false
            dot = next
        } else if (next.text == "?" && nextToken(next)?.type == KotlinTokenTypes.DOT) {
            isOptional = true
            dot = nextToken(next)!!
        } else {
            break
        }
        val name = nextToken(dot) ?: return null
        if (!isNameToken(name)) return null
        if (tokens.isNotEmpty()) optional.add(isOptional)
        tokens.add(name)
        cursor = name
    }
    if (tokens.isEmpty()) return null
    return Chain(tokens, optional)
}

/** The arguments of a check call whose expression is exactly a name chain (R2 `<e>` inside them). */
private fun KotlinCode.checkCallOperands(open: KtToken, close: KtToken): List<Operand> {
    val operands = ArrayList<Operand>()
    var segmentStart = open.end
    var index = open.index + 1
    fun finish(endOffset: Int, separator: KtToken) {
        val first = tokenAfter(segmentStart) ?: return
        if (first.start >= endOffset) return
        if (!isNameToken(first)) return
        val chain = chainStartingAt(first) ?: return
        if (nextToken(chain.tokens.last()) !== separator) return
        operands.add(Operand(chain, true))
    }
    while (index < close.index) {
        val token = code[index]
        if (isOpener(token)) {
            val end = matchingIndex(index)
            if (end in (index + 1) until close.index) {
                index = end + 1
                continue
            }
        }
        if (token.text == ",") {
            finish(token.start, token)
            segmentStart = token.end
        }
        index++
    }
    finish(close.start, close)
    return operands
}

// --- the reads --------------------------------------------------------------------------------

private fun KotlinCode.readChains(): List<KtReadChain> =
    readChainCache ?: computeReadChains().also { readChainCache = it }

private val WRITE_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=", "++", "--")

/** True when [token] is written rather than read: an assignment or an increment target. */
private fun KotlinCode.isWriteTarget(token: KtToken): Boolean {
    val next = code.getOrNull(token.index + 1)
    if (next != null && next.text in WRITE_OPERATORS) return true
    val previous = code.getOrNull(token.index - 1)
    return previous != null && (previous.text == "++" || previous.text == "--")
}

private fun KotlinCode.computeReadChains(): List<KtReadChain> {
    val reads = ArrayList<KtReadChain>()
    for (reference in references) {
        if (isWriteTarget(reference.token)) continue
        val chain = chainStartingAt(reference.token) ?: continue
        reads.add(KtReadChain(reference.token, reference.resolved, chain.names))
    }
    for (token in code) {
        if (token.type != KotlinTokenTypes.KEYWORD || token.text != "this") continue
        val before = previousToken(token)
        if (before != null && (before.type == KotlinTokenTypes.DOT || before.text == "::" || before.text == "@")) continue
        if (nextToken(token)?.type != KotlinTokenTypes.DOT) continue
        val chain = chainStartingAt(token) ?: continue
        reads.add(KtReadChain(token, null, chain.names))
    }
    return reads
}

private fun KtSmartCast.narrows(read: KtReadChain): Boolean {
    if (root != null) {
        if (read.root !== root || read.names.size < names.size) return false
        return read.names.subList(1, names.size) == names.subList(1, names.size)
    }
    if (read.root != null || read.names.size < names.size) return false
    return read.names.subList(0, names.size) == names.subList(0, names.size)
}
