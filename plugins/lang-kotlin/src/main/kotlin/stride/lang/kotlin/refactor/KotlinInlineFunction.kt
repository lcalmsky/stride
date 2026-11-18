package stride.lang.kotlin.refactor

import stride.lang.RefactoringEdit
import stride.lang.RefactoringOption
import stride.lang.RefactoringStep
import stride.lang.TextEdit
import stride.lang.kotlin.KotlinTokenTypes

/** R5 Inline for functions declared in a class body or at the top level. */
internal object KotlinInlineFunction {
    private const val NOT_TARGET = "Inline works on local variables and functions declared in this file"

    private class Body(val range: KtRange, val statementBody: Boolean)

    private class CallSite(
        val calleeToken: KtToken,
        val calleeStart: Int,
        /** `null` for a call that passes only a trailing lambda. */
        val open: KtToken?,
        val close: KtToken?,
        val trailing: KtBlock?,
    ) {
        val end: Int get() = trailing?.close?.end ?: close?.end ?: calleeToken.end

        val range: KtRange get() = KtRange(calleeStart, end)
    }

    private class Uses(val calls: List<CallSite>, val references: List<KtToken>, val otherObjects: List<KtToken>)

    private class Binding(
        val parameter: KtParameter,
        val range: KtRange,
        val order: Int,
        val uses: List<KtReference>,
        val stable: Boolean,
    )

    private sealed interface CallPlan {
        class Ready(val range: KtRange, val replacement: String) : CallPlan

        class Refused(val reason: String) : CallPlan
    }

    private class Rendered(val range: KtRange, val replacement: String)

    private class RenderedAll(val topLevel: List<Rendered>, val failure: String?)

    fun prepare(code: KotlinCode, function: KtFunction, callToken: KtToken?): RefactoringStep {
        val name = function.name
        val body = bodyOf(code, function) ?: return RefactoringStep.Refused("$name has a body of several statements")
        refusal(code, function, body)?.let { return RefactoringStep.Refused(it) }
        val uses = scanUses(code, function)
        if (callToken != null) {
            val site = uses.calls.firstOrNull { it.calleeToken.index == callToken.index }
                ?: return RefactoringStep.Refused(NOT_TARGET)
            val plan = planCall(code, function, body, site, emptyList())
            if (plan is CallPlan.Refused) return RefactoringStep.Refused(plan.reason)
            plan as CallPlan.Ready
            val single = RefactoringEdit(
                listOf(TextEdit(plan.range.start, plan.range.end, plan.replacement)),
                plan.range.start,
                "Inlined a call of $name",
            )
            val declarationRefusal = declarationChecks(code, function, uses)
            val all = if (declarationRefusal == null) renderAll(code, function, body, uses.calls) else RenderedAll(emptyList(), declarationRefusal)
            if (all.failure != null) return RefactoringStep.Apply(single)
            val allEdit = allEdit(code, function, uses, all)
            val highlights = uses.calls.map { it.range.asIntRange() } +
                listOf(KtRange(function.declarationStartWithDocs, function.declarationEnd).asIntRange())
            return RefactoringStep.Choose(
                "Inline $name",
                listOf(
                    RefactoringOption("Inline this call only", listOf(plan.range.asIntRange())) {
                        RefactoringStep.Apply(single)
                    },
                    RefactoringOption("Inline all and remove $name", highlights) {
                        RefactoringStep.Apply(allEdit)
                    },
                ),
            )
        }
        declarationChecks(code, function, uses)?.let { return RefactoringStep.Refused(it) }
        val all = renderAll(code, function, body, uses.calls)
        all.failure?.let { return RefactoringStep.Refused(it) }
        return RefactoringStep.Apply(allEdit(code, function, uses, all))
    }

    private fun declarationChecks(code: KotlinCode, function: KtFunction, uses: Uses): String? {
        val name = function.name
        if (!function.modifiers.contains("private")) {
            return "$name is not private; other files may call it. Put the caret on a call to inline only that call"
        }
        if (uses.references.isNotEmpty()) return "$name is used as a reference"
        if (uses.otherObjects.isNotEmpty()) return "$name is also called on other objects"
        if (uses.calls.isEmpty()) return "$name is never used"
        if (unreadSpelling(code, function, uses) != null) {
            return "$name appears where this refactoring cannot read it"
        }
        if (sharesItsLine(code, function)) return "The declaration shares its line with other code"
        return null
    }

    /** R5 「Modes」: removal deletes whole lines, so the declaration may not share them with code. */
    private fun sharesItsLine(code: KotlinCode, function: KtFunction): Boolean {
        val firstLine = code.lineIndex(function.declarationStartWithDocs)
        val lastLine = code.lineIndex(function.declarationEnd)
        for (token in code.code) {
            if (token.start >= function.declarationStartWithDocs && token.end <= function.declarationEnd) continue
            val line = code.lineIndex(token.start)
            if (line != firstLine && line != lastLine) continue
            if (token.text == ";" && line == lastLine) continue
            return true
        }
        return code.lineHoldsCodeAfter(function.declarationEnd, allowSemicolon = true)
    }

    /** A token spelled like the function that is neither its declaration nor a use read above. */
    private fun unreadSpelling(code: KotlinCode, function: KtFunction, uses: Uses): KtToken? {
        val read = HashSet<Int>()
        read.add(function.nameToken.index)
        for (call in uses.calls) read.add(call.calleeToken.index)
        for (reference in uses.references) read.add(reference.index)
        for (token in uses.otherObjects) read.add(token.index)
        return code.code.firstOrNull { it.spelling() == function.name && it.index !in read }
    }

    private fun bodyOf(code: KotlinCode, function: KtFunction): Body? {
        function.expressionRange?.let { return Body(it, false) }
        val block = function.body ?: return null
        val statements = code.statements.filter { it.block === block }
        if (statements.size != 1) return null
        val statement = statements.single()
        if (statement.startTokenIndex < 0) return null
        val first = code.code.getOrNull(statement.startTokenIndex) ?: return null
        if (first.text == "return") {
            val value = code.nextToken(first) ?: return null
            if (value.start >= statement.end) return null
            return Body(KtRange(value.start, statement.end), false)
        }
        val returnType = function.returnType
        if (returnType != null && returnType != "Unit") return null
        if (isDeclarationStatement(code, statement)) return null
        return Body(KtRange(statement.start, statement.end), true)
    }

    private fun isDeclarationStatement(code: KotlinCode, statement: KtStatement): Boolean {
        val first = code.code.getOrNull(statement.startTokenIndex) ?: return true
        val next = code.code.getOrNull(statement.startTokenIndex + 1)
        if (next != null && next.text == "@") return true
        if (first.text == "@") return true
        if (first.type == KotlinTokenTypes.KEYWORD && first.text in KotlinCode.MODIFIER_WORDS) return true
        if (first.text in DECLARATION_HEADS) return true
        if (first.text == "object") {
            val name = code.code.getOrNull(statement.startTokenIndex + 1)
            return name != null && isNameToken(name)
        }
        return false
    }

    private fun refusal(code: KotlinCode, function: KtFunction, body: Body): String? {
        val name = function.name
        if (function.modifiers.any { it == "open" || it == "override" || it == "abstract" } || function.isInterfaceMember) {
            return "Calls of $name may reach another implementation"
        }
        if (function.typeParameters.isNotEmpty()) return "$name is generic"
        if (function.receiver != null) return "$name is an extension function"
        if (function.parameters.any { it.vararg }) return "$name has a vararg parameter"
        if (bodyCalls(code, body.range, name)) return "$name is recursive"
        if (returnsInsideBody(code, body.range, name)) return "$name returns from inside its body"
        val returnType = function.returnType
        if (returnType != null && returnType != "Unit" &&
            !KotlinInline.fitsDeclaredType(code, returnType, body.range) &&
            !(returnType == "Boolean" && KotlinInline.isBooleanExpression(code, body.range))
        ) {
            return "$name declares type $returnType; the inlined expression could get a different type"
        }
        return null
    }

    private fun bodyCalls(code: KotlinCode, range: KtRange, name: String): Boolean {
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.start < range.start || token.end > range.end) continue
            if (!isNameToken(token) || token.text.removeSurrounding("`") != name) continue
            val next = code.code.getOrNull(index + 1) ?: continue
            if (next.text != "(") continue
            val previous = code.code.getOrNull(index - 1)
            if (previous != null && previous.type == KotlinTokenTypes.DOT) {
                val before = code.code.getOrNull(index - 2)
                if (before?.text == "this") return true
                continue
            }
            return true
        }
        return false
    }

    private fun returnsInsideBody(code: KotlinCode, range: KtRange, name: String): Boolean {
        val labels = lambdaLabels(code, range)
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.start < range.start || token.end > range.end) continue
            if (token.type != KotlinTokenTypes.KEYWORD || token.text != "return") continue
            val at = code.code.getOrNull(index + 1)
            if (at == null || at.text != "@") return true
            val label = code.code.getOrNull(index + 2)
            if (label == null || !isNameToken(label)) return true
            val written = label.text.removeSurrounding("`")
            if (written == name || written !in labels) return true
        }
        return false
    }

    /** The labels of lambdas written in [range], written and implicit; `return@label` may name one. */
    private fun lambdaLabels(code: KotlinCode, range: KtRange): Set<String> {
        val labels = HashSet<String>()
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.start < range.start || token.end > range.end) continue
            if (!isNameToken(token)) continue
            if (code.code.getOrNull(index + 1)?.text != "@") continue
            val block = code.blocks.firstOrNull { it.open.index == index + 2 } ?: continue
            if (block.kind == BlockKind.LAMBDA) labels.add(token.text.removeSurrounding("`"))
        }
        for (block in code.blocks) {
            if (block.kind != BlockKind.LAMBDA) continue
            if (block.open.start < range.start) continue
            if ((block.close?.end ?: block.open.end) > range.end) continue
            implicitLambdaLabel(code, block)?.let { labels.add(it) }
        }
        return labels
    }

    /** The implicit label of a lambda: the name of the function it is passed to. */
    private fun implicitLambdaLabel(code: KotlinCode, block: KtBlock): String? {
        val previous = KotlinInline.previousCodeToken(code, block.open.start) ?: return null
        val name = when {
            isNameToken(previous) -> previous
            previous.text == ")" -> {
                val open = code.matching(previous.index)?.takeIf { it.text == "(" } ?: return null
                val callee = KotlinInline.previousCodeToken(code, open.start) ?: return null
                callee.takeIf { isNameToken(it) }
            }
            else -> null
        } ?: return null
        return name.text.removeSurrounding("`")
    }

    private fun scanUses(code: KotlinCode, function: KtFunction): Uses {
        val name = function.name
        val calls = ArrayList<CallSite>()
        val references = ArrayList<KtToken>()
        val otherObjects = ArrayList<KtToken>()
        for (token in code.code) {
            if (token.index == function.nameToken.index) continue
            if (!isNameToken(token) || token.text.removeSurrounding("`") != name) continue
            if (token.index in code.keywordTokens) continue
            if (code.nonExpressionRanges.any { it.contains(token.start) }) continue
            if (token.start in function.declarationStartWithDocs until function.declarationEnd) {
                references.add(token)
                continue
            }
            val previous = KotlinInline.previousCodeToken(code, token.start)
            val next = KotlinInline.nextCodeToken(code, token.end)
            if (previous?.text == "@" || next?.text == "@") continue
            if (previous?.text == "::") {
                references.add(token)
                continue
            }
            if (code.references.firstOrNull { it.token.index == token.index }?.resolved != null) continue
            if (previous?.type == KotlinTokenTypes.DOT) {
                val before = KotlinInline.previousCodeToken(code, previous.start)
                if (before?.text == "this") {
                    callSiteAt(code, token, before.start)?.let { calls.add(it) } ?: references.add(token)
                } else {
                    when {
                        next?.text == "(" -> otherObjects.add(token)
                        trailingLambda(code, token) != null -> otherObjects.add(token)
                        else -> references.add(token)
                    }
                }
                continue
            }
            callSiteAt(code, token, token.start)?.let { calls.add(it) } ?: references.add(token)
        }
        return Uses(calls.sortedBy { it.range.start }, references, otherObjects)
    }

    /** A call of the name at [token], with parentheses, a trailing lambda, or only a trailing lambda. */
    private fun callSiteAt(code: KotlinCode, token: KtToken, calleeStart: Int): CallSite? {
        val next = KotlinInline.nextCodeToken(code, token.end)
        if (next?.text == "(") return callSite(code, token, calleeStart, next)
        val lambda = trailingLambda(code, token) ?: return null
        return CallSite(token, calleeStart, null, null, lambda)
    }

    private fun callSite(code: KotlinCode, token: KtToken, calleeStart: Int, open: KtToken): CallSite? {
        val close = code.matching(open.index) ?: return null
        var trailing: KtBlock? = null
        val after = KotlinInline.nextCodeToken(code, close.end)
        if (after != null && after.text == "{" && !hasLineBreak(code.text, close.end, after.start)) {
            trailing = code.blocks.firstOrNull { it.open.index == after.index && it.kind == BlockKind.LAMBDA }
        }
        return CallSite(token, calleeStart, open, close, trailing)
    }

    /**
     * The lambda [token] passes without parentheses: `{` right after it on the same or the next
     * line, with nothing but whitespace between.
     */
    internal fun trailingLambda(code: KotlinCode, token: KtToken): KtBlock? {
        val next = KotlinInline.nextCodeToken(code, token.end) ?: return null
        if (next.text != "{") return null
        val block = code.blocks.firstOrNull { it.open.index == next.index } ?: return null
        if (block.kind != BlockKind.LAMBDA) return null
        val between = code.text.substring(token.end, next.start)
        if (between.any { !it.isWhitespace() }) return null
        var breaks = 0
        var index = 0
        while (index < between.length) {
            if (between[index] == '\r') {
                breaks++
                index++
                if (index < between.length && between[index] == '\n') index++
                continue
            }
            if (between[index] == '\n') breaks++
            index++
        }
        return if (breaks <= 1) block else null
    }

    private fun hasLineBreak(text: String, from: Int, to: Int): Boolean {
        for (index in from until to.coerceAtMost(text.length)) {
            if (text[index] == '\n' || text[index] == '\r') return true
        }
        return false
    }

    private fun renderAll(code: KotlinCode, function: KtFunction, body: Body, calls: List<CallSite>): RenderedAll {
        if (calls.isEmpty()) return RenderedAll(emptyList(), null)
        val sorted = calls.sortedWith(compareBy({ it.range.start }, { -it.range.end }))
        val parents = IntArray(sorted.size) { index ->
            val range = sorted[index].range
            var parent = -1
            var parentLength = Int.MAX_VALUE
            for (other in sorted.indices) {
                if (other == index) continue
                val candidate = sorted[other].range
                if (!strictlyHolds(candidate, range)) continue
                val length = candidate.length
                if (length < parentLength) {
                    parentLength = length
                    parent = other
                }
            }
            parent
        }
        val children = sorted.indices.map { parent -> sorted.indices.filter { parents[it] == parent } }
        val rendered = arrayOfNulls<Rendered>(sorted.size)
        var failure: String? = null
        fun render(index: Int): Rendered? {
            if (failure != null) return null
            val childRenders = ArrayList<Rendered>()
            for (child in children[index]) {
                childRenders.add(render(child) ?: return null)
            }
            return when (val plan = planCall(code, function, body, sorted[index], childRenders)) {
                is CallPlan.Refused -> {
                    failure = plan.reason
                    null
                }
                is CallPlan.Ready -> {
                    val result = Rendered(plan.range, plan.replacement)
                    rendered[index] = result
                    result
                }
            }
        }
        for (index in sorted.indices) {
            if (parents[index] < 0) render(index)
            if (failure != null) break
        }
        if (failure != null) return RenderedAll(emptyList(), failure)
        val topLevel = sorted.indices.filter { parents[it] < 0 }.mapNotNull { rendered[it] }.sortedBy { it.range.start }
        return RenderedAll(topLevel, null)
    }

    private fun strictlyHolds(outer: KtRange, inner: KtRange): Boolean =
        inner.start >= outer.start && inner.end <= outer.end && (inner.start > outer.start || inner.end < outer.end)

    private fun allEdit(code: KotlinCode, function: KtFunction, uses: Uses, all: RenderedAll): RefactoringEdit {
        val edits = all.topLevel.map { TextEdit(it.range.start, it.range.end, it.replacement) }.toMutableList()
        val removal = functionRemoval(code, function)
        edits.add(TextEdit(removal.start, removal.end, ""))
        val first = all.topLevel.minOfOrNull { it.range.start } ?: removal.start
        val count = uses.calls.size
        val message = if (count == 1) "Inlined a call of ${function.name}" else "Inlined $count calls of ${function.name}"
        return RefactoringEdit(edits.sortedBy { it.start }, KotlinInline.caretAfterEdits(first, edits), message)
    }

    private fun planCall(
        code: KotlinCode,
        function: KtFunction,
        body: Body,
        site: CallSite,
        nested: List<Rendered>,
    ): CallPlan {
        val line = code.lineIndex(site.calleeStart) + 1
        val bindings = bindArguments(code, function, site)
            ?: return CallPlan.Refused("The call at line $line uses default arguments")
        if (function.container == FunctionContainer.CLASS) {
            val innermost = code.blockChain(site.calleeStart).lastOrNull { it.kind == BlockKind.CLASS_BODY }
            if (innermost?.open?.index != function.containerBlock?.open?.index) {
                return CallPlan.Refused("The call at line $line is in another class")
            }
        }
        if (body.statementBody && !KotlinInline.isWholeStatement(code, site.range)) {
            return CallPlan.Refused("The call at line $line uses the result of ${function.name}")
        }
        for (binding in bindings) {
            if (binding.stable) continue
            val argument = KotlinInline.oneLine(code.text.substring(binding.range.start, binding.range.end))
            if (binding.uses.isEmpty()) return CallPlan.Refused("$argument would no longer run")
            if (binding.uses.size != 1) {
                return CallPlan.Refused("The argument $argument would run ${binding.uses.size} times or only in some cases")
            }
            val use = binding.uses.single().range
            if (deferredInBody(code, body, use) || conditionalInBody(code, body.range.start, use.start)) {
                return CallPlan.Refused("The argument $argument would run 1 times or only in some cases")
            }
        }
        val effectful = bindings.filter { !it.stable }.sortedBy { it.order }
        var previous = -1
        val bodyEffects = code.effectsIn(body.range)
        for (binding in effectful) {
            val useStart = binding.uses.single().range.start
            if (useStart <= previous) {
                return CallPlan.Refused("Arguments of the call at line $line would run in a different order")
            }
            previous = useStart
            if (bodyEffects.any { it.offset < useStart }) {
                return CallPlan.Refused("Arguments of the call at line $line would run in a different order")
            }
        }
        argumentTypeRefusal(code, bindings, site)?.let { return CallPlan.Refused(it) }
        bodyNameCapture(code, body, site)?.let {
            return CallPlan.Refused("$it means something else at the call at line $line")
        }
        argumentNameCapture(code, body, bindings)?.let {
            return CallPlan.Refused("$it means something else at the call at line $line")
        }
        receiverSensitiveName(code, body.range)?.let { name ->
            if (receiverScopeBetween(code, function.nameToken.start, site.calleeStart)) {
                return CallPlan.Refused("$name could mean something else at line $line")
            }
        }
        val replacements = ArrayList<Pair<KtRange, String>>()
        var atomic = code.isAtomic(body.range)
        for (binding in bindings) {
            val source = argumentSource(code, binding, nested)
            if (binding.uses.any { it.range == body.range } && !source.second) atomic = false
            for (use in binding.uses) {
                replacements.add(
                    use.range to KotlinInline.substitutionText(
                        code, use, source.first, source.second, wholeBody = use.range == body.range,
                    ),
                )
            }
        }
        val substituted = KotlinInline.replaceRange(code, body.range, replacements)
        val wrap = !atomic && !KotlinInline.fillsWholeSlot(code, site.range)
        val text = if (wrap) "($substituted)" else substituted
        if (site.calleeToken.index in code.templateTokens && text.any { it == '\n' || it == '\r' }) {
            return CallPlan.Refused("The inlined code spans several lines and cannot go into a string template")
        }
        return CallPlan.Ready(site.range, text)
    }

    private enum class ArgumentType { FIXED, SUFFIXED, EXACT }

    private val LITERAL_KEYWORDS = setOf("true", "false", "null")

    /**
     * Each argument must have its parameter's type as the text shows it, since the inlined body
     * resolves by the argument's static type (R5 「Arguments」).
     */
    private fun argumentTypeRefusal(
        code: KotlinCode,
        bindings: List<Binding>,
        site: CallSite,
    ): String? {
        val functionStart = enclosingFunctionStart(code, site.calleeStart)
        for (binding in bindings) {
            val type = binding.parameter.type
                ?: return "${binding.parameter.name} appears where this refactoring cannot read it"
            val argument = KotlinInline.oneLine(code.text.substring(binding.range.start, binding.range.end))
            val narrower = "The argument $argument may have a narrower type than ${binding.parameter.name}: $type"
            when (argumentType(code, type)) {
                ArgumentType.FIXED -> Unit
                ArgumentType.SUFFIXED -> {
                    if (literalOperatorsOnly(code, binding.range) &&
                        !hasNumericSuffix(code, binding.range, type)
                    ) {
                        return narrower
                    }
                }
                ArgumentType.EXACT -> {
                    val reference = singleReference(code, binding.range)
                    val local = reference?.resolved
                    if (local != null && local.writtenType?.trim() == type.trim()) {
                        val cast = code.smartCastShape(local, functionStart, site.calleeStart) ?: continue
                        return "${cast.chain} may be smart-cast at line ${code.lineIndex(cast.token.start) + 1}; " +
                            "the inlined expression could get a different type"
                    }
                    if (KotlinInline.fitsDeclaredType(code, type, binding.range)) continue
                    return narrower
                }
            }
        }
        return null
    }

    private fun argumentType(code: KotlinCode, type: String): ArgumentType {
        val trimmed = type.trim()
        if (trimmed.endsWith("?")) return ArgumentType.EXACT
        when (trimmed) {
            "Int", "Boolean", "Char", "String", "Double" -> return ArgumentType.FIXED
            "Long", "Short", "Byte", "Float" -> return ArgumentType.SUFFIXED
        }
        return if (code.isFinalClassInFile(trimmed)) ArgumentType.FIXED else ArgumentType.EXACT
    }

    /** True when [offset] sits in the body of the innermost function holding it, or at the top level. */
    private fun enclosingFunctionStart(code: KotlinCode, offset: Int): Int =
        code.functions
            .filter { it.bodyRange?.contains(offset) == true }
            .maxByOrNull { it.bodyRange!!.start }
            ?.funToken
            ?.start ?: 0

    private fun literalOperatorsOnly(code: KotlinCode, range: KtRange): Boolean {
        val tokens = code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= range.start && it.end <= range.end
        }
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            when (token.type) {
                KotlinTokenTypes.NUMBER, KotlinTokenTypes.CHARACTER, KotlinTokenTypes.OPERATION_SIGN,
                KotlinTokenTypes.PARENTHESES, KotlinTokenTypes.BRACKETS,
                -> true
                else -> token.isStringBody ||
                    (token.type == KotlinTokenTypes.KEYWORD && token.text in LITERAL_KEYWORDS)
            }
        }
    }

    private fun hasNumericSuffix(code: KotlinCode, range: KtRange, type: String): Boolean {
        val suffix = when (type.trim()) {
            "Long" -> "l"
            "Float" -> "f"
            else -> return false
        }
        return code.tokens.any { token ->
            token.type == KotlinTokenTypes.NUMBER && token.start >= range.start && token.end <= range.end &&
                numberCarries(token.text, suffix)
        }
    }

    private fun numberCarries(text: String, suffix: String): Boolean {
        val lower = text.lowercase()
        if (!lower.endsWith(suffix)) return false
        if (suffix == "f" && (lower.startsWith("0x") || lower.startsWith("0b"))) return false
        return true
    }

    private fun singleReference(code: KotlinCode, range: KtRange): KtReference? {
        val tokens = code.code.filter { it.start >= range.start && it.end <= range.end }
        val token = tokens.singleOrNull() ?: return null
        if (!isNameToken(token)) return null
        return code.references.firstOrNull { it.token.index == token.index }
    }

    private fun argumentSource(
        code: KotlinCode,
        binding: Binding,
        nested: List<Rendered>,
    ): Pair<String, Boolean> {
        val inside = nested.filter { it.range.start >= binding.range.start && it.range.end <= binding.range.end }
        if (inside.isEmpty()) {
            return code.text.substring(binding.range.start, binding.range.end) to code.isAtomic(binding.range)
        }
        val text = KotlinInline.replaceRange(code, binding.range, inside.map { it.range to it.replacement })
        return text to KotlinInline.isAtomicText(text)
    }

    private fun bindArguments(code: KotlinCode, function: KtFunction, site: CallSite): List<Binding>? {
        val parameters = function.parameters
        val open = site.open
        val close = site.close
        val segments = if (open != null && close != null) KotlinInline.segments(code, open, close) else emptyList()
        val assigned = arrayOfNulls<KtRange>(parameters.size)
        val orders = IntArray(parameters.size) { -1 }
        val positional = ArrayList<Pair<KtRange, Int>>()
        for ((order, segment) in segments.withIndex()) {
            val named = namedSegment(code, segment)
            if (named != null) {
                val index = parameters.indexOfFirst { it.name == named.first }
                if (index < 0 || assigned[index] != null) return null
                assigned[index] = named.second
                orders[index] = order
            } else {
                positional.add(segment to order)
            }
        }
        var next = 0
        for ((segment, order) in positional) {
            while (next < parameters.size && assigned[next] != null) next++
            if (next >= parameters.size) return null
            assigned[next] = segment
            orders[next] = order
            next++
        }
        val trailing = site.trailing
        if (trailing != null) {
            if (parameters.isEmpty()) return null
            val last = parameters.size - 1
            if (assigned[last] != null) return null
            val close = trailing.close ?: return null
            assigned[last] = KtRange(trailing.open.start, close.end)
            orders[last] = segments.size
        }
        if (assigned.any { it == null }) return null
        return parameters.mapIndexed { index, parameter ->
            val range = assigned[index] ?: return null
            val local = code.locals.firstOrNull {
                it.kind == LocalKind.PARAMETER && it.nameToken.index == parameter.nameToken.index
            }
            val uses = if (local == null) emptyList() else code.references.filter { it.resolved === local }
            Binding(parameter, range, orders[index], uses, code.isStable(range))
        }
    }

    private fun namedSegment(code: KotlinCode, segment: KtRange): Pair<String, KtRange>? {
        val tokens = code.code.filter { it.start >= segment.start && it.end <= segment.end }
        if (tokens.size < 2) return null
        if (!isNameToken(tokens[0]) || tokens[1].text != "=") return null
        val value = code.nextToken(tokens[1]) ?: return null
        if (value.start >= segment.end) return null
        return tokens[0].text.removeSurrounding("`") to KtRange(value.start, segment.end)
    }

    private fun deferredInBody(code: KotlinCode, body: Body, range: KtRange): Boolean {
        for (block in code.blockChain(range.start)) {
            if (block.open.start < body.range.start || block.open.start >= body.range.end) continue
            when {
                block.kind == BlockKind.LAMBDA -> return true
                block.kind == BlockKind.CONTROL_BODY && block.loopBody -> return true
                block.kind == BlockKind.CLASS_BODY && block.headerKeyword == "object" -> return true
                block.kind == BlockKind.FUNCTION_BODY && isLocalFunctionBody(code, block) -> return true
            }
        }
        return false
    }

    private fun isLocalFunctionBody(code: KotlinCode, block: KtBlock): Boolean =
        code.functions.any { it.body === block && it.container == FunctionContainer.LOCAL }

    private fun conditionalInBody(code: KotlinCode, from: Int, to: Int): Boolean {
        val fromIndex = code.code.indexOfFirst { it.start >= from }
        if (fromIndex < 0) return false
        val endIndex = code.code.indexOfLast { it.start < to }.coerceAtLeast(fromIndex)
        return code.conditionalFrom(fromIndex, endIndex, to)
    }

    private fun bodyNameCapture(code: KotlinCode, body: Body, site: CallSite): String? {
        for (reference in code.references) {
            if (reference.token.start < body.range.start || reference.token.end > body.range.end) continue
            val resolved = reference.resolved
            if (resolved != null) {
                if (resolved.kind == LocalKind.PARAMETER) continue
                if (body.range.contains(resolved.nameToken.start)) continue
                continue
            }
            if (KotlinInline.resolveLocal(code, reference.name, site.calleeStart, excluding = null) != null) {
                return reference.name
            }
        }
        return null
    }

    private fun argumentNameCapture(code: KotlinCode, body: Body, bindings: List<Binding>): String? {
        for (binding in bindings) {
            val references = code.references.filter {
                it.token.start >= binding.range.start && it.token.end <= binding.range.end
            }
            for (reference in references) {
                val resolved = reference.resolved
                if (resolved != null && binding.range.contains(resolved.nameToken.start)) continue
                for (use in binding.uses) {
                    val declaration = bodyDeclarationAt(code, body, use.token.start)
                    if (declaration != null && declaration.name == reference.name) return reference.name
                }
            }
        }
        return null
    }

    private fun bodyDeclarationAt(code: KotlinCode, body: Body, offset: Int): KtLocal? {
        var best: KtLocal? = null
        for (local in code.locals) {
            if (local.nameToken.start < body.range.start || local.nameToken.start >= body.range.end) continue
            if (!local.contains(offset)) continue
            if (best == null || local.scope.length < best.scope.length ||
                (local.scope.length == best.scope.length && local.nameToken.start > best.nameToken.start)
            ) {
                best = local
            }
        }
        return best
    }

    private fun functionRemoval(code: KotlinCode, function: KtFunction): KtRange {
        var start = function.declarationStartWithDocs
        var end = KotlinInline.consumeBreak(code, code.lineEnd(function.declarationEnd))
        if (end < code.text.length) {
            val nextLineEnd = code.lineEnd(end)
            if (code.isBlank(end, nextLineEnd)) {
                end = KotlinInline.consumeBreak(code, nextLineEnd)
                return KtRange(start, end)
            }
        }
        if (start > 0) {
            val previousStart = code.lineStart(start - 1)
            if (previousStart < start && code.isBlank(previousStart, start)) start = previousStart
        }
        return KtRange(start, end)
    }

    private val DECLARATION_HEADS = setOf(
        "val", "var", "fun", "class", "interface", "typealias",
        "return", "break", "continue", "for", "while", "do",
    )
}
