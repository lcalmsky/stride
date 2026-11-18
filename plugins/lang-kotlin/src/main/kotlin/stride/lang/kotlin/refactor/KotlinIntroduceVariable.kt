package stride.lang.kotlin.refactor

import stride.lang.NameCheck
import stride.lang.RefactoringContext
import stride.lang.RefactoringEdit
import stride.lang.RefactoringOption
import stride.lang.RefactoringStep
import stride.lang.TextEdit

/** R3 Introduce Variable; the rules and messages are in `docs/api-app.md` 「Refactorings」 R3. */
internal object KotlinIntroduceVariable {
    private const val BRACKETS = "Brackets do not match in this file"
    private const val NOT_IN_FUNCTION = "Introduce Variable works inside a function body"
    private const val EXPRESSION_BODY = "Expression body: convert it to a block body first"
    private const val SHARED_LINE = "The statement shares its line; put it on its own line first"
    private const val SELECT_EXPRESSION = "Select an expression"
    private const val NOT_A_NAME = "Not a valid name"
    private const val OPTION_LIMIT = 60

    fun prepare(context: RefactoringContext): RefactoringStep =
        prepare(kotlinCodeFor(context.text), context.selectionStart, context.selectionEnd)

    fun prepare(code: KotlinCode, selectionStart: Int, selectionEnd: Int): RefactoringStep {
        val text = code.text
        if (!code.bracketsMatch) return RefactoringStep.Refused(BRACKETS)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selection = if (end > start) trim(code, KtRange(start, end)) else null
        if (selection != null && !selection.isEmpty) {
            if (code.isInsideOpaqueText(selection.start)) return RefactoringStep.Refused(SELECT_EXPRESSION)
            return prepareFor(code, selection)
        }
        val caret = selectionStart.coerceIn(0, text.length)
        val token = code.tokenAt(caret) ?: (if (caret > 0) code.tokenAt(caret - 1) else null)
            ?: return RefactoringStep.Refused(SELECT_EXPRESSION)
        val candidates = candidates(code, token).filter { code.checkExpression(it) == null }
        if (candidates.isEmpty()) {
            return RefactoringStep.Refused(statementBlockRefusal(code, token.start) ?: SELECT_EXPRESSION)
        }
        if (candidates.size == 1) return prepareFor(code, candidates.single())
        val options = candidates.map { range ->
            RefactoringOption(optionText(code, range), listOf(range.asIntRange())) { prepareFor(code, range) }
        }
        return RefactoringStep.Choose("Expressions", options)
    }

    private fun prepareFor(code: KotlinCode, expression: KtRange): RefactoringStep {
        val statement = code.statementAt(expression.start)
        if (statement == null) {
            return RefactoringStep.Refused(statementBlockRefusal(code, expression.start) ?: SELECT_EXPRESSION)
        }
        if (code.checkExpression(expression) != null) return RefactoringStep.Refused(SELECT_EXPRESSION)
        if (!code.isAloneOnLine(statement)) return RefactoringStep.Refused(SHARED_LINE)
        val written = expressionOneLine(code, expression)
        if (code.isConditional(expression)) {
            return RefactoringStep.Refused("$written runs only in some cases here; a variable would run it every time")
        }
        if (!code.isStable(expression) && code.effectsBefore(expression).isNotEmpty()) {
            return RefactoringStep.Refused("Code before $written in this statement would run after it")
        }
        code.smartCastShapeReadBy(expression, code.lineStart(statement.start), expression.start)?.let { shape ->
            return RefactoringStep.Refused(
                "${shape.first.name} may be smart-cast at line ${code.lineIndex(shape.second.start) + 1}; " +
                    "the variable would keep its unchecked type",
            )
        }
        val others = occurrences(code, expression, statement)
        if (others.isEmpty()) return nameStep(code, expression, statement, emptyList())
        val all = (others + expression).sortedBy { it.start }
        val onlyThis = RefactoringOption("Replace this occurrence only", listOf(expression.asIntRange())) {
            nameStep(code, expression, statement, emptyList())
        }
        val replaceAll = RefactoringOption(
            "Replace all ${all.size} occurrences",
            all.map { it.asIntRange() },
        ) {
            nameStep(code, expression, statement, others)
        }
        return RefactoringStep.Choose("Occurrences", listOf(onlyThis, replaceAll))
    }

    private fun statementBlockRefusal(code: KotlinCode, offset: Int): String? {
        if (code.statementAt(offset) != null) return null
        val inExpressionBody = code.functions.any { it.expressionRange?.contains(offset) == true }
        return if (inExpressionBody) EXPRESSION_BODY else NOT_IN_FUNCTION
    }

    private fun nameStep(
        code: KotlinCode,
        expression: KtRange,
        statement: KtStatement,
        others: List<KtRange>,
    ): RefactoringStep {
        val scope = code.nameScope(expression.start)
        val suggestion = uniqueName(code, scope, suggestName(code, expression))
        return RefactoringStep.AskName("Introduce Variable", suggestion) { name ->
            when {
                !isValidKotlinIdentifier(name) || isHardKeyword(name) -> NameCheck.Invalid(NOT_A_NAME)
                scope != null && isNameUsedIn(code, scope, name) ->
                    NameCheck.Invalid("'$name' is already used in this function")
                else -> NameCheck.Valid(edit(code, expression, statement, others, name))
            }
        }
    }

    private fun edit(
        code: KotlinCode,
        expression: KtRange,
        statement: KtStatement,
        others: List<KtRange>,
        name: String,
    ): RefactoringEdit {
        val edits = ArrayList<TextEdit>()
        val lineStart = code.lineStart(statement.start)
        val indent = code.indentation(statement.start)
        val lineBreak = code.lineBreak(statement.start)
        val text = code.text.substring(expression.start, expression.end)
        val insertion = "$indent" + "val $name = $text" + lineBreak
        edits.add(TextEdit(lineStart, lineStart, insertion))
        for (range in others) edits.add(TextEdit(range.start, range.end, name))
        edits.add(TextEdit(expression.start, expression.end, name))
        val caret = caretAfter(expression.start, name.length, edits)
        return RefactoringEdit(edits.sortedBy { it.start }, caret, "Introduced variable $name")
    }

    private fun caretAfter(expressionStart: Int, replacementLength: Int, edits: List<TextEdit>): Int {
        var shift = 0
        for (edit in edits.sortedBy { it.start }) {
            if (edit.start < expressionStart) {
                shift += edit.replacement.length - (edit.end - edit.start)
            } else {
                break
            }
        }
        return expressionStart + shift + replacementLength
    }

    private fun occurrences(code: KotlinCode, expression: KtRange, statement: KtStatement): List<KtRange> {
        val stable = code.isStable(expression)
        val readOnly = code.isReadOnly(expression)
        if (!stable && !readOnly) return emptyList()
        val blockEnd = statement.block.end
        val firstEffect = if (stable) {
            Int.MAX_VALUE
        } else {
            code.effects.filter { it.offset >= statement.start && it.offset < blockEnd }
                .minOfOrNull { it.offset } ?: Int.MAX_VALUE
        }
        val template = tokensIn(code, expression)
        if (template.isEmpty()) return emptyList()
        val search = code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= statement.start && it.end <= blockEnd
        }
        val receiverName = receiverSensitiveName(code, expression)
        val insertion = code.lineStart(statement.start)
        val result = ArrayList<KtRange>()
        var index = 0
        while (index + template.size <= search.size) {
            val window = search.subList(index, index + template.size)
            if (window.map { it.text } != template.map { it.text }) {
                index++
                continue
            }
            val range = KtRange(window.first().start, window.last().end)
            if (range.start >= expression.start && range.end <= expression.end) {
                index += template.size
                continue
            }
            if (range.start < expression.end && expression.start < range.end) {
                index += template.size
                continue
            }
            if (!stable && range.start >= firstEffect) {
                index += template.size
                continue
            }
            if (!stable && deferredOccurrence(code, range, statement)) {
                index += template.size
                continue
            }
            if (receiverName != null && receiverScopeBetween(code, expression.start, range.start)) {
                index += template.size
                continue
            }
            if (code.smartCastShapeReadBy(range, insertion, range.start) != null) {
                index += template.size
                continue
            }
            if (code.checkExpression(range) == null && sameResolutions(code, template, window)) {
                result.add(range)
            }
            index += template.size
        }
        return result
    }

    /**
     * True when a read-only occurrence runs later or repeatedly: it sits below the statement's
     * block in a lambda, class body, loop body or local function body, or after a `while`, `do`
     * or `for` keyword of the statement.
     */
    private fun deferredOccurrence(code: KotlinCode, range: KtRange, statement: KtStatement): Boolean {
        val chain = code.blockChain(range.start)
        val own = chain.indexOfFirst { it === statement.block }
        if (own >= 0) {
            for (index in own + 1 until chain.size) {
                val block = chain[index]
                when {
                    block.kind == BlockKind.LAMBDA -> return true
                    block.kind == BlockKind.CLASS_BODY -> return true
                    block.kind == BlockKind.CONTROL_BODY && block.loopBody -> return true
                    block.kind == BlockKind.FUNCTION_BODY -> return true
                }
            }
        }
        for (function in code.functions) {
            if (function.container != FunctionContainer.LOCAL) continue
            if (function.contains(statement.start)) continue
            if (function.body?.contains(range.start) == true ||
                function.expressionRange?.contains(range.start) == true
            ) {
                return true
            }
        }
        val ownStatement = code.statementAt(range.start) ?: return false
        for (index in ownStatement.startTokenIndex..ownStatement.endTokenIndex) {
            val token = code.code[index]
            if (token.start >= range.start) break
            if (token.type == stride.lang.kotlin.KotlinTokenTypes.KEYWORD &&
                token.text in setOf("while", "do", "for")
            ) {
                return true
            }
        }
        return false
    }

    private fun tokensIn(code: KotlinCode, range: KtRange): List<KtToken> =
        code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= range.start && it.end <= range.end
        }

    /** True when every name plays the same role at both positions: a reference to the same declaration, or not a reference in both. */
    private fun sameResolutions(code: KotlinCode, left: List<KtToken>, right: List<KtToken>): Boolean {
        for (index in left.indices) {
            val a = left[index]
            val b = right[index]
            if (a.index < 0 || b.index < 0) continue
            if (!isNameToken(a) || !isNameToken(b)) continue
            val first = code.references.firstOrNull { it.token.index == a.index }
            val second = code.references.firstOrNull { it.token.index == b.index }
            if (first == null || second == null) {
                if (first != null || second != null) return false
                continue
            }
            if (first.resolved?.nameToken?.start != second.resolved?.nameToken?.start) return false
        }
        return true
    }

    private fun suggestName(code: KotlinCode, expression: KtRange): String {
        val tokens = tokensIn(code, expression)
        val first = tokens.firstOrNull() ?: return "value"
        if (tokens.size == 1) {
            return when {
                first.isStringBody -> "text"
                first.type == stride.lang.kotlin.KotlinTokenTypes.NUMBER -> "number"
                else -> "value"
            }
        }
        val last = tokens.last()
        val member = when {
            last.text == ")" -> {
                val open = code.matching(last.index)
                val callee = open?.let { code.tokenBefore(it.start) }
                callee?.takeIf { isNameToken(it) }
            }
            isNameToken(last) -> {
                val before = code.tokenBefore(last.start)
                if (before != null && before.type == stride.lang.kotlin.KotlinTokenTypes.DOT) last else null
            }
            else -> null
        }
        if (member != null) {
            val raw = member.text.removeSurrounding("`")
            if (raw.isNotEmpty()) {
                val dropGet = raw.length > 3 && raw.startsWith("get") && raw[3].isUpperCase()
                val base = if (dropGet) raw.substring(3) else raw
                val name = base.replaceFirstChar { it.lowercase() }
                if (isValidKotlinIdentifier(name) && !isHardKeyword(name)) return name
            }
        }
        return "value"
    }

    private fun uniqueName(code: KotlinCode, scope: KtRange?, base: String): String {
        if (scope == null) return base
        if (!isNameUsedIn(code, scope, base)) return base
        var counter = 2
        while (isNameUsedIn(code, scope, base + counter)) counter++
        return base + counter
    }

    private fun trim(code: KotlinCode, range: KtRange): KtRange {
        var start = range.start
        var end = range.end
        while (start < end && code.text[start].isWhitespace()) start++
        while (end > start && code.text[end - 1].isWhitespace()) end--
        return KtRange(start, end)
    }

    private fun expressionOneLine(code: KotlinCode, range: KtRange): String =
        shorten(code.text.substring(range.start, range.end).replace(Regex("\\s+"), " ").trim(), 40, "...")

    private fun optionText(code: KotlinCode, range: KtRange): String =
        shorten(code.text.substring(range.start, range.end).replace(Regex("\\s+"), " ").trim(), OPTION_LIMIT, "\u2026")

    private fun shorten(text: String, limit: Int, marker: String): String =
        if (text.length <= limit) text else text.substring(0, limit - marker.length) + marker


    private fun candidates(code: KotlinCode, caretToken: KtToken): List<KtRange> {
        val chain = chainCandidates(code, caretToken)
        if (chain.isEmpty()) return emptyList()
        val result = LinkedHashSet<KtRange>()
        result.addAll(chain)
        result.addAll(enclosing(code, chain.last()))
        val statement = code.statementAt(caretToken.start)
        if (statement != null) statementValue(code, statement)?.let { result.add(it) }
        return result.toList()
    }

    private fun chainCandidates(code: KotlinCode, caretToken: KtToken): List<KtRange> {
        var startToken = leftOperandToken(code, caretToken) ?: return emptyList()
        if (startToken.isStringBody) startToken = literalStartToken(code, startToken)
        if (!isChainStart(startToken)) return emptyList()
        var endToken = endOfOperandToken(code, startToken) ?: return emptyList()
        val ranges = ArrayList<KtRange>()
        ranges.add(KtRange(startToken.start, endToken.end))
        var guard = 0
        while (guard++ < 100) {
            if (enclosingStringToken(code, endToken) != null) break
            val next = code.tokenAfter(endToken.end) ?: break
            when {
                next.type == stride.lang.kotlin.KotlinTokenTypes.DOT -> {
                    val name = code.tokenAfter(next.end) ?: break
                    if (!isNameToken(name)) break
                    endToken = name
                    ranges.add(KtRange(startToken.start, endToken.end))
                }
                next.text == "?" && code.tokenAfter(next.end)?.type == stride.lang.kotlin.KotlinTokenTypes.DOT -> {
                    val dot = code.tokenAfter(next.end)!!
                    val name = code.tokenAfter(dot.end) ?: break
                    if (!isNameToken(name)) break
                    endToken = name
                    ranges.add(KtRange(startToken.start, endToken.end))
                }
                next.text == "(" || next.text == "[" -> {
                    val close = code.matching(next.index) ?: break
                    endToken = close
                    ranges.add(KtRange(startToken.start, endToken.end))
                }
                next.text == "!!" -> {
                    endToken = next
                    ranges.add(KtRange(startToken.start, endToken.end))
                }
                else -> break
            }
        }
        return ranges
    }

    private fun isChainStart(token: KtToken): Boolean =
        token.isStringBody || isNameToken(token) || token.type == stride.lang.kotlin.KotlinTokenTypes.NUMBER ||
            token.type == stride.lang.kotlin.KotlinTokenTypes.CHARACTER || token.text == "(" ||
            (token.type == stride.lang.kotlin.KotlinTokenTypes.KEYWORD && token.text == "this")

    /** The string literal that strictly contains [token] (a `$name` template identifier), if any. */
    private fun enclosingStringToken(code: KotlinCode, token: KtToken): KtToken? =
        code.tokens.firstOrNull { it.isStringBody && it.start < token.start && it.end > token.end }

    /** The leftmost operand token of the postfix expression ending at [token]. */
    private fun leftOperandToken(code: KotlinCode, token: KtToken): KtToken? {
        var current = token
        var guard = 0
        while (guard++ < 100) {
            if (current.text == ")" || current.text == "]") {
                val open = code.matching(current.index)
                if (open == null) return current
                val callee = code.tokenBefore(open.start) ?: return open
                if (callee.isStringBody || isNameToken(callee) || callee.text == ")" ||
                    callee.text == "]" || callee.text == ">"
                ) {
                    current = callee
                    continue
                }
                return open
            }
            val previous = code.tokenBefore(current.start) ?: return current
            if (previous.type == stride.lang.kotlin.KotlinTokenTypes.DOT) {
                var operand = code.tokenBefore(previous.start) ?: return current
                if (operand.text == "?") operand = code.tokenBefore(operand.start) ?: return current
                if (isChainStart(operand) || operand.text == ")" || operand.text == "]" || operand.text == "!!") {
                    current = operand
                    continue
                }
                return current
            }
            if (previous.text == "!!") {
                current = previous
                continue
            }
            return current
        }
        return current
    }

    /** The opening string piece of the literal a continuation piece belongs to. */
    private fun literalStartToken(code: KotlinCode, token: KtToken): KtToken {
        var piece = token
        var guard = 0
        while (!piece.text.startsWith("\"") && guard++ < 1000) {
            piece = code.tokens.lastOrNull { it.isStringBody && it.end <= piece.start } ?: return piece
        }
        return piece
    }

    private fun endOfOperandToken(code: KotlinCode, token: KtToken): KtToken? {
        if (token.isStringBody) return literalEndToken(code, token)
        return when (token.text) {
            "(" , "[" -> code.matching(token.index)
            else -> token
        }
    }

    /** The closing string piece of the literal starting at [token]. */
    private fun literalEndToken(code: KotlinCode, token: KtToken): KtToken {
        var piece = token
        var guard = 0
        while (guard++ < 1000) {
            val next = code.tokens.firstOrNull { it.isStringBody && it.start >= piece.end } ?: return piece
            val continues = piece.text.endsWith("\${") || next.start in code.rawContinuationStarts
            if (!continues) return piece
            piece = next
        }
        return piece
    }

    private fun enclosing(code: KotlinCode, chain: KtRange): List<KtRange> {
        val result = ArrayList<KtRange>()
        var current = chain
        var guard = 0
        while (guard++ < 50) {
            var openIndex = -1
            for (index in code.code.indices) {
                val token = code.code[index]
                if (token.text != "(" && token.text != "[") continue
                val close = code.matchingIndex(index)
                if (close < 0) continue
                if (token.start < current.start && code.code[close].end > current.end && index > openIndex) {
                    openIndex = index
                }
            }
            if (openIndex < 0) break
            val open = code.code[openIndex]
            val close = code.code[code.matchingIndex(openIndex)]
            if (open.text == "(" && isCallParentheses(code, openIndex)) {
                argumentContaining(code, openIndex, close.index, current)?.let { result.add(it) }
                val callee = calleeStart(code, openIndex) ?: break
                current = KtRange(callee.start, close.end)
            } else if (open.text == "(") {
                val parenthesized = KtRange(open.start, close.end)
                result.add(parenthesized)
                current = parenthesized
            } else {
                val callee = calleeStart(code, openIndex) ?: break
                current = KtRange(callee.start, close.end)
            }
        }
        return result
    }

    private fun calleeStart(code: KotlinCode, openIndex: Int): KtToken? {
        val previous = code.tokenBefore(code.code[openIndex].start) ?: return null
        if (previous.text == ">") {
            val open = angleOpenBefore(code, previous.index)
            val name = if (open > 0) code.code.getOrNull(open - 1) else null
            return name?.let { leftOperandToken(code, it) }
        }
        if (isChainStart(previous) || previous.text == ")" || previous.text == "]" || previous.text == "!!") {
            return leftOperandToken(code, previous)
        }
        return null
    }

    private fun isCallParentheses(code: KotlinCode, openIndex: Int): Boolean {
        val previous = code.code.getOrNull(openIndex - 1) ?: return false
        return isNameToken(previous) || previous.text == ")" || previous.text == "]" || previous.text == ">"
    }

    private fun argumentContaining(code: KotlinCode, openIndex: Int, closeIndex: Int, current: KtRange): KtRange? {
        val open = code.code[openIndex]
        val close = code.code[closeIndex]
        for (segment in topLevelSegments(code, open, close)) {
            if (segment.start <= current.start && current.end <= segment.end) return segment
        }
        return null
    }

    /** The `,`-separated segments between two brackets, over code and string tokens alike. */
    private fun topLevelSegments(code: KotlinCode, open: KtToken, close: KtToken): List<KtRange> {
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
            if (token.text == ",") {
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

    private fun statementValue(code: KotlinCode, statement: KtStatement): KtRange? {
        if (statement.startTokenIndex < 0) return statement.range
        var depth = 0
        var index = statement.startTokenIndex
        while (index <= statement.endTokenIndex) {
            val token = code.code[index]
            when {
                code.isOpener(token) -> depth++
                code.isCloser(token) -> depth--
                depth == 0 && token.text == "=" -> {
                    val next = code.tokenAfter(token.end) ?: return null
                    return KtRange(next.start, statement.end)
                }
                index == statement.startTokenIndex && token.text in setOf("return", "throw") -> {
                    val next = code.tokenAfter(token.end) ?: return null
                    return KtRange(next.start, statement.end)
                }
            }
            index++
        }
        return statement.range
    }
}
