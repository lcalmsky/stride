package stride.lang.kotlin.refactor

import stride.lang.kotlin.KotlinTokenTypes

/**
 * The first name in [range] that a receiver scope could rebind, or `null`: `this`, `super`, or a
 * name that does not resolve to a local. Member names after `.`, `?.` or `::`, named arguments
 * and labels are not such names.
 */
internal fun receiverSensitiveName(code: KotlinCode, range: KtRange): String? {
    val references = code.references.associateBy { it.token.index }
    for (token in code.code) {
        if (token.start < range.start || token.end > range.end) continue
        if (token.type == KotlinTokenTypes.KEYWORD && (token.text == "this" || token.text == "super")) {
            return token.text
        }
        if (!isNameToken(token)) continue
        val reference = references[token.index] ?: continue
        if (reference.resolved == null) return reference.name
    }
    return null
}

/**
 * True when [inner] sits in a lambda, class body or local function body that does not hold
 * [outer], so code moved from [outer] to [inner] may mean another declaration (R2 receiver scopes).
 */
internal fun receiverScopeBetween(code: KotlinCode, outer: Int, inner: Int): Boolean {
    for (block in code.blockChain(inner)) {
        if (block.contains(outer)) continue
        when {
            block.kind == BlockKind.LAMBDA -> return true
            block.kind == BlockKind.CLASS_BODY -> return true
            block.kind == BlockKind.FUNCTION_BODY && isLocalFunctionBody(code, block) -> return true
        }
    }
    for (function in code.functions) {
        if (function.container != FunctionContainer.LOCAL) continue
        val body = function.expressionRange ?: continue
        if (body.contains(inner) && !body.contains(outer) && !function.contains(outer)) return true
    }
    return false
}

private fun isLocalFunctionBody(code: KotlinCode, block: KtBlock): Boolean =
    code.functions.any { it.body === block && it.container == FunctionContainer.LOCAL }
