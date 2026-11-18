package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

private val CHECK_CALLS = setOf("require", "check", "requireNotNull", "checkNotNull", "assert")

private val NULL_CHECKS = setOf("isNullOrEmpty", "isNullOrBlank")

private val EQUALITY_OPERATORS = setOf("==", "!=", "===", "!==")

private val OPENLIKE_MODIFIERS = setOf("open", "abstract", "sealed")

/**
 * The first R2 「Static types」 smart-cast shape for [local] whose reference starts in `[from, to)`,
 * or `null`. References are matched by resolution, so a same-name local elsewhere is not one. The
 * returned token is the operator, member name, `when` or check call that makes the shape, except
 * `x !is`, which returns the `!`.
 */
internal fun KotlinCode.smartCastShape(local: KtLocal, from: Int, to: Int): KtToken? =
    references
        .filter { it.resolved === local && it.token.start >= from && it.token.start < to }
        .mapNotNull { shapeToken(it.token) }
        .minByOrNull { it.start }

/** The first shape in `[from, to)` for a local that [range] reads, with that local, or `null`. */
internal fun KotlinCode.smartCastShapeReadBy(range: KtRange, from: Int, to: Int): Pair<KtLocal, KtToken>? {
    var best: Pair<KtLocal, KtToken>? = null
    for (reference in references) {
        if (reference.token.start < range.start || reference.token.end > range.end) continue
        val local = reference.resolved ?: continue
        val shape = smartCastShape(local, from, to) ?: continue
        if (best == null || shape.start < best.second.start) best = local to shape
    }
    return best
}

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

private fun KotlinCode.shapeToken(token: KtToken): KtToken? {
    val index = token.index
    val next = code.getOrNull(index + 1)
    if (next != null) {
        when {
            next.text == "is" || next.text == "as" -> return next
            next.text == "!" && code.getOrNull(index + 2)?.text == "is" -> return next
            next.text == "!!" || next.text == "?:" -> return next
            next.text in EQUALITY_OPERATORS && code.getOrNull(index + 2)?.text == "null" -> return next
            next.text == "." && code.getOrNull(index + 3)?.text == "(" &&
                code[index + 2].spelling() in NULL_CHECKS -> return code[index + 2]
        }
    }
    val previous = code.getOrNull(index - 1)
    if (previous != null && previous.text in EQUALITY_OPERATORS &&
        code.getOrNull(index - 2)?.text == "null"
    ) {
        return previous
    }
    whenSubject(token)?.let { return it }
    return checkCall(token)
}

private fun KotlinCode.whenSubject(token: KtToken): KtToken? {
    if (code.getOrNull(token.index + 1)?.text != ")") return null
    val open = code.getOrNull(token.index - 1) ?: return null
    if (open.text != "(") return null
    val keyword = code.getOrNull(token.index - 2) ?: return null
    if (keyword.type != KotlinTokenTypes.KEYWORD || keyword.text != "when") return null
    return keyword
}

private fun KotlinCode.checkCall(token: KtToken): KtToken? {
    var best: KtCall? = null
    for (call in calls) {
        if (call.kind != CallKind.PARENTHESES) continue
        val close = call.close ?: continue
        if (token.start <= call.open.start || token.start >= close.start) continue
        val callee = code.getOrNull(call.open.index - 1) ?: continue
        if (!isNameToken(callee) || callee.spelling() !in CHECK_CALLS) continue
        if (best == null || call.range.length < best.range.length) best = call
    }
    return best?.let { code.getOrNull(it.open.index - 1) }
}
