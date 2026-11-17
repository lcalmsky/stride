package stride.lang.kotlin.refactor

import stride.lang.RefactoringContext
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit
import stride.lang.kotlin.KotlinTokenTypes

/** R5 Inline; the rules and messages are in `docs/api-app.md` 「Refactorings」 R5. */
internal object KotlinInline {
    private const val BRACKETS = "Brackets do not match in this file"
    private const val NOT_TARGET = "Inline works on local variables and functions declared in this file"

    fun prepare(context: RefactoringContext): RefactoringStep {
        val code = KotlinCode(context.text)
        if (!code.bracketsMatch) return RefactoringStep.Refused(BRACKETS)
        val start = minOf(context.selectionStart, context.selectionEnd).coerceIn(0, code.text.length)
        val end = maxOf(context.selectionStart, context.selectionEnd).coerceIn(0, code.text.length)
        val token = targetToken(code, start, end) ?: return RefactoringStep.Refused(NOT_TARGET)
        return prepareAt(code, token)
    }

    private fun targetToken(code: KotlinCode, start: Int, end: Int): KtToken? {
        var from = start
        var to = end
        if (to > from) {
            while (from < to && code.text[from].isWhitespace()) from++
            while (to > from && code.text[to - 1].isWhitespace()) to--
            if (to > from) {
                val token = code.tokenAt(from) ?: return null
                if (token.start != from || token.end != to || !isNameToken(token)) return null
                return token
            }
        }
        val caret = from.coerceIn(0, code.text.length)
        val token = code.tokenAt(caret) ?: (if (caret > 0) code.tokenAt(caret - 1) else null) ?: return null
        return token.takeIf { isNameToken(it) }
    }

    private fun prepareAt(code: KotlinCode, token: KtToken): RefactoringStep {
        val reference = code.references.firstOrNull { it.token.index == token.index }
        val resolved = reference?.resolved
        if (resolved != null) {
            if (resolved.kind != LocalKind.VAL && resolved.kind != LocalKind.VAR) return RefactoringStep.Refused(NOT_TARGET)
            return prepareVariable(code, resolved)
        }
        val declaredLocal = code.locals.firstOrNull {
            it.nameToken.index == token.index && (it.kind == LocalKind.VAL || it.kind == LocalKind.VAR)
        }
        if (declaredLocal != null) return prepareVariable(code, declaredLocal)
        val declared = code.functions.firstOrNull { it.nameToken.index == token.index }
        if (declared != null) {
            if (declared.container == FunctionContainer.LOCAL) return RefactoringStep.Refused(NOT_TARGET)
            return KotlinInlineFunction.prepare(code, declared, null)
        }
        if (!isCallee(code, token)) return RefactoringStep.Refused(NOT_TARGET)
        val name = token.text.removeSurrounding("`")
        val candidates = code.functions.filter { it.name == name }
        if (candidates.size != 1) return RefactoringStep.Refused(NOT_TARGET)
        val function = candidates.single()
        if (function.container == FunctionContainer.LOCAL) return RefactoringStep.Refused(NOT_TARGET)
        return KotlinInlineFunction.prepare(code, function, token)
    }

    private fun isCallee(code: KotlinCode, token: KtToken): Boolean {
        val next = nextCodeToken(code, token.end) ?: return false
        if (next.text != "(") return false
        val previous = previousCodeToken(code, token.start) ?: return true
        if (previous.text == "::") return false
        if (previous.type == KotlinTokenTypes.DOT) {
            val before = previousCodeToken(code, previous.start) ?: return false
            return before.text == "this"
        }
        return true
    }

    private fun prepareVariable(code: KotlinCode, local: KtLocal): RefactoringStep {
        val name = local.name
        if (local.kind == LocalKind.VAR) return RefactoringStep.Refused("$name is a var; only vals are inlined")
        val statement = local.declarationStatement
        val initializer = local.initializer
        if (local.destructured || local.delegated || initializer == null || statement == null) {
            return RefactoringStep.Refused("$name has no initializer to inline")
        }
        val writtenType = local.writtenType
        if (writtenType != null && !fitsWrittenType(code, writtenType, initializer)) {
            return RefactoringStep.Refused("$name declares type $writtenType; the inlined expression could get a different type")
        }
        val uses = code.references.filter { it.resolved === local }.sortedBy { it.token.start }
        if (uses.isEmpty()) return RefactoringStep.Refused("$name is never used")
        if (declaredInNestedScope(code, local)) {
            return RefactoringStep.Refused("$name is declared again in a nested scope")
        }
        if (!code.isAloneOnLine(statement)) {
            return RefactoringStep.Refused("The declaration shares its line with other code")
        }
        if (code.isStable(initializer)) {
            variableCapture(code, local, initializer, uses)?.let {
                return RefactoringStep.Refused("Inlining would make $it refer to another declaration")
            }
        } else {
            if (uses.size != 1) {
                return RefactoringStep.Refused("$name is used ${uses.size} times and its initializer is not stable")
            }
            val use = uses.single()
            if (deferredAfterDeclaration(code, local.nameToken.start, use.range)) {
                return RefactoringStep.Refused("$name's initializer would run later or repeatedly")
            }
            if (code.isConditional(use.range) || controlBodyAfterDeclaration(code, local.nameToken.start, use.range)) {
                return RefactoringStep.Refused("$name's initializer would run only in some cases")
            }
            if (code.effects.any { it.offset >= statement.end && it.offset < use.range.start }) {
                return RefactoringStep.Refused("Code between the declaration and the use of $name would run before its initializer")
            }
            variableCapture(code, local, initializer, uses)?.let {
                return RefactoringStep.Refused("Inlining would make $it refer to another declaration")
            }
        }
        return RefactoringStep.Apply(variableEdit(code, local, statement, initializer, uses))
    }

    private fun declaredInNestedScope(code: KotlinCode, local: KtLocal): Boolean {
        val block = code.blockAt(local.nameToken.start) ?: return false
        return code.locals.any { other ->
            other !== local && other.name == local.name && other.nameToken.start > local.nameToken.start &&
                code.blockChain(other.nameToken.start).any { it === block }
        }
    }

    /**
     * True when the use sits in a lambda, loop body, local function or object expression that does
     * not hold the declaration, so the initializer would run later or more than once.
     */
    private fun deferredAfterDeclaration(code: KotlinCode, declarationOffset: Int, use: KtRange): Boolean {
        for (block in code.blockChain(use.start)) {
            if (block.contains(declarationOffset)) continue
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

    private fun controlBodyAfterDeclaration(code: KotlinCode, declarationOffset: Int, use: KtRange): Boolean {
        for (block in code.blockChain(use.start)) {
            if (block.kind != BlockKind.CONTROL_BODY) continue
            if (block.contains(declarationOffset)) continue
            if (block.open.start > declarationOffset) return true
        }
        return false
    }

    private fun variableCapture(code: KotlinCode, local: KtLocal, initializer: KtRange, uses: List<KtReference>): String? {
        val inner = code.references.filter { it.token.start >= initializer.start && it.token.end <= initializer.end }
        for (reference in inner) {
            val declared = reference.resolved
            if (declared != null && initializer.contains(declared.nameToken.start)) continue
            for (use in uses) {
                val target = resolveLocal(code, reference.name, use.token.start, excluding = local)
                if (target?.nameToken?.start != declared?.nameToken?.start) return reference.name
            }
        }
        return null
    }

    private fun variableEdit(
        code: KotlinCode,
        local: KtLocal,
        statement: KtStatement,
        initializer: KtRange,
        uses: List<KtReference>,
    ): RefactoringEdit {
        val text = code.text.substring(initializer.start, initializer.end)
        val atomic = code.isAtomic(initializer)
        val edits = ArrayList<TextEdit>()
        for (use in uses) {
            edits.add(TextEdit(use.token.start, use.token.end, substitutionText(code, use, text, atomic)))
        }
        val removal = lineRemoval(code, statement.start, statement.end)
        edits.add(TextEdit(removal.start, removal.end, ""))
        val caret = caretAfterEdits(uses.first().token.start, edits)
        val count = uses.size
        val word = if (count == 1) "use" else "uses"
        return RefactoringEdit(edits.sortedBy { it.start }, caret, "Inlined ${local.name} ($count $word)")
    }

    /** The reference becomes [sourceText], parenthesized unless it is atomic or fills a whole slot. */
    internal fun substitutionText(
        code: KotlinCode,
        use: KtReference,
        sourceText: String,
        sourceAtomic: Boolean,
        wholeBody: Boolean = false,
    ): String = when (use.usage) {
        TemplateUsage.SIMPLE -> "{$sourceText}"
        else -> when {
            wholeBody -> sourceText
            fillsWholeSlot(code, use.range) -> sourceText
            sourceAtomic -> sourceText
            else -> "($sourceText)"
        }
    }

    /** R5 substitution: the places where parentheses around the substituted text are not needed. */
    internal fun fillsWholeSlot(code: KotlinCode, range: KtRange): Boolean =
        fillsWholeStatement(code, range) || fillsWholeArgument(code, range) ||
            fillsRightOfEquals(code, range) || fillsAfterReturn(code, range) ||
            fillsWholeCondition(code, range) || fillsWholeTemplate(code, range)

    internal fun isWholeStatement(code: KotlinCode, range: KtRange): Boolean {
        val statement = code.statementAt(range.start) ?: return false
        return statement.start == range.start && statement.end == range.end
    }

    private fun fillsWholeStatement(code: KotlinCode, range: KtRange): Boolean = isWholeStatement(code, range)

    private fun fillsWholeArgument(code: KotlinCode, range: KtRange): Boolean {
        var best = -1
        var bestLength = Int.MAX_VALUE
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.text != "(" && token.text != "[") continue
            val closeIndex = code.matchingIndex(index)
            if (closeIndex < 0) continue
            val close = code.code[closeIndex]
            if (range.start <= token.start || range.end >= close.end) continue
            val previous = code.code.getOrNull(index - 1) ?: continue
            val call = when (token.text) {
                "(" -> previous.text == ")" || previous.text == "]" || previous.text == ">" || isNameToken(previous)
                else -> isNameToken(previous) || previous.text == ")" || previous.text == "]"
            }
            if (!call) continue
            val length = close.end - token.start
            if (length < bestLength) {
                bestLength = length
                best = index
            }
        }
        if (best < 0) return false
        val closeIndex = code.matchingIndex(best)
        val close = code.code[closeIndex]
        return segments(code, code.code[best], close).any { it.start == range.start && it.end == range.end }
    }

    private fun fillsRightOfEquals(code: KotlinCode, range: KtRange): Boolean {
        val statement = code.statementAt(range.start) ?: return false
        val previous = previousCodeToken(code, range.start) ?: return false
        if (previous.text != "=") return false
        if (statement.end == range.end) return true
        val next = nextCodeToken(code, range.end) ?: return true
        return next.text == "," || next.text == ")" || next.text == "]" || next.text == "}"
    }

    private fun fillsAfterReturn(code: KotlinCode, range: KtRange): Boolean {
        val statement = code.statementAt(range.start) ?: return false
        val first = code.code.getOrNull(statement.startTokenIndex) ?: return false
        if (first.text != "return") return false
        val value = nextCodeToken(code, first.end) ?: return false
        return value.start == range.start && statement.end == range.end
    }

    private fun fillsWholeCondition(code: KotlinCode, range: KtRange): Boolean {
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.text != "(") continue
            val head = code.code.getOrNull(index - 1) ?: continue
            if (head.type != KotlinTokenTypes.KEYWORD) continue
            if (head.text != "if" && head.text != "when" && head.text != "while") continue
            val closeIndex = code.matchingIndex(index)
            if (closeIndex < 0) continue
            val close = code.code[closeIndex]
            if (range.start < token.end || range.end > close.start) continue
            val first = nextCodeToken(code, token.end) ?: continue
            val last = previousCodeToken(code, close.start) ?: continue
            if (first.start == range.start && last.end == range.end) return true
        }
        return false
    }

    private fun fillsWholeTemplate(code: KotlinCode, range: KtRange): Boolean {
        for (template in code.templateExpressionRanges) {
            if (range.start < template.start || range.end > template.end) continue
            val content = code.code.filter { it.start >= template.start && it.end <= template.end }
            if (content.isEmpty()) continue
            if (content.first().start == range.start && content.last().end == range.end) return true
        }
        return false
    }

    /** The `,`-separated top-level ranges between two brackets, comments left out. */
    internal fun segments(code: KotlinCode, open: KtToken, close: KtToken): List<KtRange> {
        val syntax = code.tokens.filter { !it.isComment }
        val result = ArrayList<KtRange>()
        var startOffset = -1
        var endOffset = -1
        fun emit() {
            if (startOffset >= 0 && endOffset > startOffset) result.add(KtRange(startOffset, endOffset))
            startOffset = -1
            endOffset = -1
        }
        var index = syntax.indexOfFirst { it.start >= open.end }
        while (index < syntax.size) {
            val token = syntax[index]
            if (token.start >= close.start) break
            if (token.type == KotlinTokenTypes.COMMA) {
                emit()
                index++
                continue
            }
            if (startOffset < 0) startOffset = token.start
            endOffset = maxOf(endOffset, token.end)
            if (code.isOpener(token)) {
                val partner = code.matching(token.index)
                if (partner != null && partner.start < close.start) {
                    endOffset = maxOf(endOffset, partner.end)
                    index++
                    while (index < syntax.size && syntax[index].start < partner.end) index++
                    continue
                }
            }
            index++
        }
        emit()
        return result
    }

    internal fun previousCodeToken(code: KotlinCode, offset: Int): KtToken? = code.code.lastOrNull { it.end <= offset }

    internal fun nextCodeToken(code: KotlinCode, offset: Int): KtToken? = code.code.firstOrNull { it.start >= offset }

    internal fun resolveLocal(code: KotlinCode, name: String, offset: Int, excluding: KtLocal?): KtLocal? {
        var best: KtLocal? = null
        for (local in code.locals) {
            if (local === excluding) continue
            if (local.name != name) continue
            if (!local.contains(offset)) continue
            if (best == null || local.scope.length < best.scope.length ||
                (local.scope.length == best.scope.length && local.nameToken.start > best.nameToken.start)
            ) {
                best = local
            }
        }
        return best
    }

    internal fun caretAfterEdits(offset: Int, edits: List<TextEdit>): Int {
        var shift = 0
        for (edit in edits.sortedBy { it.start }) {
            if (edit.end <= offset) {
                shift += edit.replacement.length - (edit.end - edit.start)
            } else if (edit.start < offset) {
                return edit.start + shift
            }
        }
        return offset + shift
    }

    /** [range]'s text with [replacements], which must not overlap, written over it. */
    internal fun replaceRange(code: KotlinCode, range: KtRange, replacements: List<Pair<KtRange, String>>): String {
        val builder = StringBuilder()
        var cursor = range.start
        for ((replacementRange, text) in replacements.sortedBy { it.first.start }) {
            builder.append(code.text, cursor, replacementRange.start).append(text)
            cursor = replacementRange.end
        }
        builder.append(code.text, cursor, range.end)
        return builder.toString()
    }

    /** Whether a piece of text is an atomic expression, judged on its own. */
    internal fun isAtomicText(text: String): Boolean {
        if (text.isEmpty()) return false
        val code = KotlinCode(text)
        return code.bracketsMatch && code.isAtomic(KtRange(0, text.length))
    }

    internal fun oneLine(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= 40) collapsed else collapsed.substring(0, 37) + "..."
    }

    internal fun consumeBreak(code: KotlinCode, end: Int): Int {
        val text = code.text
        return when {
            end < text.length && text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n' -> end + 2
            end < text.length && (text[end] == '\n' || text[end] == '\r') -> end + 1
            else -> end
        }
    }

    /** The whole lines of `[startOffset, endOffset]` with their line break. */
    internal fun lineRemoval(code: KotlinCode, startOffset: Int, endOffset: Int): KtRange {
        val start = code.lineStart(startOffset)
        val end = consumeBreak(code, code.lineEnd(endOffset))
        return KtRange(start, end)
    }

    private fun fitsWrittenType(code: KotlinCode, type: String, range: KtRange): Boolean =
        constructorType(code, range) == type || literalType(code, range) == type

    internal fun fitsDeclaredType(code: KotlinCode, type: String, range: KtRange): Boolean =
        fitsWrittenType(code, type, range)

    /** The type text of a constructor call filling exactly [range], or `null`. */
    private fun constructorType(code: KotlinCode, range: KtRange): String? {
        val first = code.code.firstOrNull { it.start >= range.start } ?: return null
        if (first.start != range.start || !isNameToken(first)) return null
        var index = first.index + 1
        var typeEnd = first
        val next = code.code.getOrNull(index)
        if (next != null && next.text == "<") {
            val close = angleClose(code, index)
            if (close < 0) return null
            typeEnd = code.code[close]
            index = close + 1
        }
        val open = code.code.getOrNull(index) ?: return null
        if (open.text != "(") return null
        val closeIndex = code.matchingIndex(index)
        if (closeIndex < 0) return null
        val close = code.code[closeIndex]
        if (close.end != range.end) return null
        return code.text.substring(first.start, typeEnd.end).trim()
    }

    /** The type name of a literal filling exactly [range], or `null`. */
    internal fun literalType(code: KotlinCode, range: KtRange): String? {
        val all = code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= range.start && it.end <= range.end
        }
        if (all.isEmpty()) return null
        val first = all.first()
        if (first.isStringBody) {
            if (first.start != range.start || all.last().end != range.end) return null
            if (!first.text.startsWith("\"")) return null
            if (all.any { it.isCode && it.index !in code.templateTokens }) return null
            return "String"
        }
        val tokens = all.filter { it.isCode && it.index !in code.templateTokens }
        if (tokens.size == 1) return literalTypeOf(tokens.single())
        if (tokens.size == 2 && tokens[0].type == KotlinTokenTypes.OPERATION_SIGN &&
            (tokens[0].text == "-" || tokens[0].text == "+") &&
            tokens[1].type == KotlinTokenTypes.NUMBER &&
            tokens[0].start == range.start && tokens[0].end == tokens[1].start && tokens[1].end == range.end
        ) {
            return numberType(tokens[1].text)
        }
        return null
    }

    private fun literalTypeOf(token: KtToken): String? = when (token.type) {
        KotlinTokenTypes.CHARACTER -> "Char"
        KotlinTokenTypes.NUMBER -> numberType(token.text)
        KotlinTokenTypes.KEYWORD -> if (token.text == "true" || token.text == "false") "Boolean" else null
        else -> null
    }

    private fun numberType(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.endsWith("l") -> "Long"
            lower.endsWith("f") -> "Float"
            lower.startsWith("0x") || lower.startsWith("0b") -> "Int"
            lower.contains('.') || lower.contains('e') -> "Double"
            else -> "Int"
        }
    }

    /** True when [range] is a comparison or logical expression, so its type is Boolean. */
    internal fun isBooleanExpression(code: KotlinCode, range: KtRange): Boolean {
        var depth = 0
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.start < range.start || token.end > range.end) continue
            if (token.index in code.templateTokens) continue
            when {
                token.text == "(" || token.text == "[" || token.text == "{" -> {
                    depth++
                    continue
                }
                token.text == ")" || token.text == "]" || token.text == "}" -> {
                    depth--
                    continue
                }
            }
            if (depth != 0) continue
            if (token.type != KotlinTokenTypes.OPERATION_SIGN) continue
            if (token.text == "<" && genericAngle(code, index)) continue
            if (token.text == ">" && closesGenericAngle(code, index)) continue
            if (token.text in BOOLEAN_OPERATORS) return true
        }
        return false
    }

    private fun genericAngle(code: KotlinCode, index: Int): Boolean {
        val close = angleClose(code, index)
        if (close < 0) return false
        return code.code.getOrNull(close + 1)?.text == "("
    }

    private fun closesGenericAngle(code: KotlinCode, index: Int): Boolean {
        val open = angleOpenBefore(code, index)
        if (open < 0) return false
        return code.code.getOrNull(index + 1)?.text == "("
    }

    private val BOOLEAN_OPERATORS = setOf("==", "!=", "===", "!==", "<", ">", "<=", ">=", "&&", "||", "!")
}
