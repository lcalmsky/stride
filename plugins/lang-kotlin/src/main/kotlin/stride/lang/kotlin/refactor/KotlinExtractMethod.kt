package stride.lang.kotlin.refactor

import stride.lang.NameCheck
import stride.lang.RefactoringContext
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit
import stride.lang.kotlin.KotlinTokenTypes

/** R4 Extract Method; the rules and messages are in `docs/api-app.md` 「Refactorings」 R4. */
internal object KotlinExtractMethod {
    private const val BRACKETS = "Brackets do not match in this file"
    private const val NO_SELECTION = "Select the code to extract"
    private const val SHAPE = "Select whole statements or one expression"
    private const val LAMBDA = "The selection is inside a lambda; this and it may mean something else"
    private const val LOCAL_FUNCTION = "The selection is inside a local function"
    private const val NOT_IN_FUNCTION = "Extract Method works inside a function body"
    private const val EXPRESSION_BODY = "Expression body: convert it to a block body first"
    private const val BLOCK_VALUE = "The selection is the block's value; extract one expression"
    private const val RETURN = "The selection contains return"
    private const val LOOP = "The selection contains break or continue for a loop outside it"
    private const val PLACEMENT = "The function's last line holds other code; put it on its own line first"
    private const val NOT_A_NAME = "Not a valid name"
    private const val FOUR_SPACES = "    "
    private val ASSIGNMENTS = setOf("=", "+=", "-=", "*=", "/=", "%=")
    private val LABEL_USERS = setOf("return", "break", "continue", "this", "super")
    private val LITERAL_KEYWORDS = setOf("true", "false", "null")

    fun prepare(context: RefactoringContext): RefactoringStep =
        prepare(kotlinCodeFor(context.text), context.selectionStart, context.selectionEnd)

    fun prepare(code: KotlinCode, selectionStart: Int, selectionEnd: Int): RefactoringStep {
        if (!code.bracketsMatch) return RefactoringStep.Refused(BRACKETS)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, code.text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, code.text.length)
        val selection = trim(code, KtRange(start, end))
        if (selection.isEmpty) return RefactoringStep.Refused(NO_SELECTION)

        val place = enclosing(code, selection.start)
        place.refusal?.let { return RefactoringStep.Refused(it) }
        val enclosing = place.enclosing!!
        if (code.isInsideOpaqueText(selection.start)) return RefactoringStep.Refused(SHAPE)
        placementRefusal(code, enclosing)?.let { return RefactoringStep.Refused(it) }

        val statements = statementsCovering(code, selection)
        if (statements != null) {
            if (valueBlock(code, statements.first().block) && includesBlockValue(code, statements)) {
                if (statements.size == 1 && code.checkExpression(selection) == null) {
                    return expressionStep(code, selection, enclosing)
                }
                return RefactoringStep.Refused(BLOCK_VALUE)
            }
            return statementsStep(code, selection, enclosing)
        }
        if (code.checkExpression(selection) != null) {
            if (enclosing.body == null && code.statementAt(selection.start) == null) {
                return RefactoringStep.Refused(EXPRESSION_BODY)
            }
            // A lambda or anonymous function is expression-shaped but takes its type from its place.
            if (!isLambdaOrAnonymousFunction(code, selection)) return RefactoringStep.Refused(SHAPE)
        }
        return expressionStep(code, selection, enclosing)
    }

    private fun trim(code: KotlinCode, range: KtRange): KtRange {
        var start = range.start
        var end = range.end
        while (start < end && code.text[start].isWhitespace()) start++
        while (end > start && code.text[end - 1].isWhitespace()) end--
        return KtRange(start, end)
    }

    // --- where the selection sits -------------------------------------------------------------

    private class Enclosing(val function: KtFunction, val body: KtBlock?, val indent: String, val unit: String)

    private class Place(val enclosing: Enclosing? = null, val refusal: String? = null)

    private fun enclosing(code: KotlinCode, offset: Int): Place {
        val chain = code.blockChain(offset)
        val functionBlock = chain.lastOrNull { it.kind == BlockKind.FUNCTION_BODY }
        if (functionBlock != null) {
            val function = code.functions.firstOrNull { it.body === functionBlock }
                ?: return Place(refusal = NOT_IN_FUNCTION)
            if (function.container == FunctionContainer.LOCAL) return Place(refusal = LOCAL_FUNCTION)
            betweenRefusal(chain.filter { it.open.start > functionBlock.open.start })?.let {
                return Place(refusal = it)
            }
            return Place(enclosing = bound(code, function, functionBlock))
        }
        val expressionFunction = code.functions
            .filter { it.expressionRange?.contains(offset) == true }
            .minByOrNull { it.expressionRange!!.length }
            ?: return Place(refusal = NOT_IN_FUNCTION)
        if (expressionFunction.container == FunctionContainer.LOCAL) return Place(refusal = LOCAL_FUNCTION)
        for (block in chain) {
            if (block.open.start <= expressionFunction.funToken.start) continue
            if (block.kind == BlockKind.CLASS_BODY || block.kind == BlockKind.INITIALIZER) {
                return Place(refusal = NOT_IN_FUNCTION)
            }
        }
        // A block inside the expression (`when` branch, `if` block, lambda) is still that body.
        return Place(refusal = EXPRESSION_BODY)
    }

    private fun betweenRefusal(between: List<KtBlock>): String? {
        for (block in between.sortedByDescending { it.open.start }) {
            when (block.kind) {
                BlockKind.LAMBDA -> return LAMBDA
                BlockKind.INITIALIZER, BlockKind.CLASS_BODY -> return NOT_IN_FUNCTION
                BlockKind.FUNCTION_BODY -> return LOCAL_FUNCTION
                BlockKind.CONTROL_BODY, BlockKind.WHEN_BODY -> Unit
            }
        }
        return null
    }

    private fun bound(code: KotlinCode, function: KtFunction, body: KtBlock?): Enclosing {
        val indent = code.indentation(function.funToken.start)
        return Enclosing(function, body, indent, indentUnit(code, function, body, indent))
    }

    /** The first body line's indentation beyond the function's own, four spaces when unknown. */
    private fun indentUnit(code: KotlinCode, function: KtFunction, body: KtBlock?, indent: String): String {
        if (body == null) return FOUR_SPACES
        val first = code.code.firstOrNull { it.start > body.open.start && it.start < body.end } ?: return FOUR_SPACES
        if (code.lineStart(first.start) <= body.open.start) return FOUR_SPACES
        val bodyIndent = code.indentation(first.start)
        if (bodyIndent.length > indent.length && bodyIndent.startsWith(indent)) {
            val unit = bodyIndent.substring(indent.length)
            if (unit.isNotEmpty()) return unit
        }
        return FOUR_SPACES
    }

    /** The new function follows the enclosing function's line, which holds nothing else (R4 Placement). */
    private fun placementRefusal(code: KotlinCode, enclosing: Enclosing): String? {
        if (enclosing.body == null) return null
        return if (code.lineHoldsCodeAfter(enclosing.function.declarationEnd)) PLACEMENT else null
    }

    private fun statementsCovering(code: KotlinCode, selection: KtRange): List<KtStatement>? {
        for (block in code.blocks) {
            if (!block.isStatementBlock) continue
            val blockStatements = code.statements.filter { it.block === block }.sortedBy { it.start }
            val first = blockStatements.indexOfFirst { it.start == selection.start }
            if (first < 0) continue
            var last = first
            while (last + 1 < blockStatements.size && blockStatements[last + 1].end <= selection.end) last++
            val covered = blockStatements.subList(first, last + 1)
            if (covered.last().end == selection.end) return covered
            if (selection.end > covered.last().end && trailingSelectionOnly(code, covered.last().end, selection.end)) {
                return covered
            }
        }
        return null
    }

    /** True when only `;` and same-line comments follow the last statement inside the selection. */
    private fun trailingSelectionOnly(code: KotlinCode, from: Int, to: Int): Boolean {
        val tail = code.text.substring(from, to)
        if ('\n' in tail || '\r' in tail) return false
        var index = from
        while (index < to) {
            when {
                code.text[index].isWhitespace() -> index++
                code.text[index] == ';' -> index++
                code.text.startsWith("//", index) -> return true
                code.text.startsWith("/*", index) -> {
                    val close = code.text.indexOf("*/", index + 2)
                    if (close < 0 || close + 2 > to) return false
                    index = close + 2
                }
                else -> return false
            }
        }
        return true
    }

    /** True when the control construct owning [block] stands in an expression position and yields a value. */
    private fun valueBlock(code: KotlinCode, block: KtBlock): Boolean {
        if (block.kind != BlockKind.CONTROL_BODY) return false
        val keyword = valueKeyword(code, block) ?: return false
        return keywordInExpressionPosition(code, keyword)
    }

    private fun keywordInExpressionPosition(code: KotlinCode, keyword: KtToken): Boolean {
        val statement = code.statements.firstOrNull {
            it.startTokenIndex >= 0 && code.code[it.startTokenIndex].index == keyword.index
        } ?: return true
        return usesStatementValue(code, statement)
    }

    /** A statement's value is used when it ends a block whose own value is used. */
    private fun usesStatementValue(code: KotlinCode, statement: KtStatement): Boolean {
        val block = statement.block
        val last = code.statements.filter { it.block === block }.maxByOrNull { it.start } ?: return false
        if (last.start != statement.start) return false
        return when (block.kind) {
            BlockKind.LAMBDA -> true
            BlockKind.CONTROL_BODY -> valueBlock(code, block)
            else -> false
        }
    }

    private fun valueKeyword(code: KotlinCode, block: KtBlock): KtToken? {
        var keyword = owningKeyword(code, block) ?: return null
        var guard = 0
        while (guard++ < 100) {
            val before = code.code.getOrNull(keyword.index - 1) ?: return keyword
            if (before.text != "else") return keyword
            keyword = scanBackFor(code, before.index, "if") ?: return keyword
        }
        return keyword
    }

    private fun owningKeyword(code: KotlinCode, block: KtBlock): KtToken? = when (block.headerKeyword) {
        "if" -> headerKeywordBefore(code, block)
        "when" -> whenKeyword(code, block)
        "else" -> scanBackFor(code, block.open.index, "if")
        "catch" -> scanBackFor(code, block.open.index, "try")
        "try" -> code.code.getOrNull(block.open.index - 1)?.takeIf { it.text == "try" }
        else -> null
    }

    private fun whenKeyword(code: KotlinCode, block: KtBlock): KtToken? {
        val body = block.parent ?: return null
        if (body.kind != BlockKind.WHEN_BODY) return null
        val previous = code.code.getOrNull(body.open.index - 1) ?: return null
        if (previous.text == "when") return previous
        return headerKeywordBefore(code, body)?.takeIf { it.text == "when" }
    }

    private fun headerKeywordBefore(code: KotlinCode, block: KtBlock): KtToken? {
        val close = code.code.getOrNull(block.open.index - 1) ?: return null
        if (close.text != ")") return null
        val open = code.matchingIndex(close.index)
        if (open < 0) return null
        return code.code.getOrNull(open - 1)
    }

    /** The nearest [word] keyword before [index], skipping every balanced bracket group. */
    private fun scanBackFor(code: KotlinCode, index: Int, word: String): KtToken? {
        var cursor = index - 1
        while (cursor >= 0) {
            val token = code.code[cursor]
            if (code.isCloser(token)) {
                val open = code.matchingIndex(cursor)
                if (open < 0) return null
                cursor = open - 1
                continue
            }
            if (token.type == KotlinTokenTypes.KEYWORD && token.text == word) return token
            cursor--
        }
        return null
    }

    private fun includesBlockValue(code: KotlinCode, statements: List<KtStatement>): Boolean {
        val last = code.statements.filter { it.block === statements.first().block }.maxByOrNull { it.start }
            ?: return false
        return statements.last().start == last.start
    }

    // --- the two shapes -----------------------------------------------------------------------

    private fun statementsStep(
        code: KotlinCode,
        selection: KtRange,
        enclosing: Enclosing,
    ): RefactoringStep {
        val inputs = when (val result = collectInputs(code, selection, enclosing.function)) {
            is InputResult.Refused -> return RefactoringStep.Refused(result.reason)
            is InputResult.Found -> result.parameters
        }
        typeParameterRefusal(code, selection, enclosing.function, inputs)?.let {
            return RefactoringStep.Refused(it)
        }
        val outputs = when (val result = collectOutputs(code, selection)) {
            is OutputResult.Refused -> return RefactoringStep.Refused(result.reason)
            is OutputResult.Found -> result.outputs
        }
        exitsRefusal(code, selection)?.let { return RefactoringStep.Refused(it) }
        unreadRefusal(code, selection)?.let { return RefactoringStep.Refused(it) }
        val output = outputs.singleOrNull()
        return nameStep(code) { name ->
            statementEdit(code, selection, enclosing, inputs, output, name)
        }
    }

    private fun expressionStep(code: KotlinCode, selection: KtRange, enclosing: Enclosing): RefactoringStep {
        val inputs = when (val result = collectInputs(code, selection, enclosing.function)) {
            is InputResult.Refused -> return RefactoringStep.Refused(result.reason)
            is InputResult.Found -> result.parameters
        }
        typeParameterRefusal(code, selection, enclosing.function, inputs)?.let {
            return RefactoringStep.Refused(it)
        }
        val outputs = when (val result = collectOutputs(code, selection)) {
            is OutputResult.Refused -> return RefactoringStep.Refused(result.reason)
            is OutputResult.Found -> result.outputs
        }
        if (outputs.isNotEmpty()) {
            return RefactoringStep.Refused(
                "The selection changes ${outputs.single().name}; extract whole statements instead",
            )
        }
        exitsRefusal(code, selection)?.let { return RefactoringStep.Refused(it) }
        unreadRefusal(code, selection)?.let { return RefactoringStep.Refused(it) }
        if (contextTyped(code, selection) || !selfTyped(code, selection)) {
            return RefactoringStep.Refused(
                "Cannot infer the type of ${writtenExpression(code, selection)} without a compiler",
            )
        }
        return nameStep(code) { name ->
            expressionEdit(code, selection, enclosing, inputs, name)
        }
    }

    /**
     * True when the expression takes its type from where it sits: only literals and operators,
     * or a lambda or anonymous function itself (R4 Expression type).
     */
    private fun contextTyped(code: KotlinCode, range: KtRange): Boolean {
        if (isLambdaOrAnonymousFunction(code, range)) return true
        val tokens = code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= range.start && it.end <= range.end
        }
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            token.isStringBody || token.type == KotlinTokenTypes.NUMBER ||
                token.type == KotlinTokenTypes.CHARACTER || token.type == KotlinTokenTypes.PARENTHESES ||
                token.type == KotlinTokenTypes.BRACKETS || token.type == KotlinTokenTypes.BRACES ||
                token.type == KotlinTokenTypes.OPERATION_SIGN ||
                (token.type == KotlinTokenTypes.KEYWORD && token.text in LITERAL_KEYWORDS)
        }
    }

    private fun isLambdaOrAnonymousFunction(code: KotlinCode, range: KtRange): Boolean {
        if (code.tokenStartingAt(range.start)?.text == "fun") return true
        return code.blocks.any {
            it.kind == BlockKind.LAMBDA && it.open.start == range.start &&
                (it.close?.end ?: it.open.end) == range.end
        }
    }

    /**
     * False when the expression holds a call without a receiver whose type arguments are not
     * written: its result type may come from the caller's expected type, which a new function
     * does not have. A member call is bound by its receiver's type and a constructor by its name.
     */
    private fun selfTyped(code: KotlinCode, range: KtRange): Boolean {
        for (call in code.callsIn(range)) {
            if (call.kind == CallKind.INDEX) continue
            val callee = code.tokenBefore(call.open.start) ?: continue
            if (!isNameToken(callee)) continue
            val before = code.tokenBefore(callee.start)
            if (before != null && before.type == KotlinTokenTypes.DOT) continue
            val raw = callee.text.removeSurrounding("`")
            val hasArguments = call.kind == CallKind.LAMBDA ||
                (call.close != null && call.close!!.index > call.open.index + 1)
            if (raw.isNotEmpty() && raw.first().isUpperCase() && hasArguments) continue
            return false
        }
        return true
    }

    private fun writtenExpression(code: KotlinCode, range: KtRange): String {
        val text = code.text.substring(range.start, range.end).replace(Regex("\\s+"), " ").trim()
        return if (text.length <= 40) text else text.substring(0, 37) + "..."
    }

    // --- inputs -------------------------------------------------------------------------------

    private class Parameter(val name: String, val type: String)

    private sealed interface InputResult {
        class Refused(val reason: String) : InputResult

        class Found(val parameters: List<Parameter>) : InputResult
    }

    private fun collectInputs(code: KotlinCode, selection: KtRange, function: KtFunction): InputResult {
        val references = code.references
            .filter { it.token.start >= selection.start && it.token.end <= selection.end }
            .sortedBy { it.token.start }
        val seen = LinkedHashSet<KtLocal>()
        val parameters = ArrayList<Parameter>()
        for (reference in references) {
            val local = reference.resolved ?: continue
            if (local.nameToken.start >= selection.start && local.nameToken.end <= selection.end) continue
            if (local.kind == LocalKind.LOCAL_FUN || local.kind == LocalKind.LOCAL_CLASS) {
                return InputResult.Refused("The selection uses local ${local.name}")
            }
            if (local.delegated) return InputResult.Refused("${local.name} is a delegated local")
            if (local.kind == LocalKind.VAR && capturedInLambda(code, selection, reference.token.start)) {
                return InputResult.Refused("The selection captures var ${local.name} in a lambda")
            }
            if (!seen.add(local)) continue
            val declared = function.parameters.firstOrNull { it.nameToken.index == local.nameToken.index }
            if (declared != null && declared.vararg) {
                return InputResult.Refused("${local.name} is a vararg parameter")
            }
            val type = typeOf(code, local) ?: return InputResult.Refused(inferMessage(local.name))
            parameters.add(Parameter(local.nameToken.text, type))
        }
        return InputResult.Found(parameters)
    }

    /** True when [offset] sits in a lambda, anonymous function, object expression or local function of the selection. */
    private fun capturedInLambda(code: KotlinCode, selection: KtRange, offset: Int): Boolean =
        code.blockChain(offset).any {
            it.open.start >= selection.start &&
                (it.kind == BlockKind.LAMBDA || it.kind == BlockKind.FUNCTION_BODY ||
                    it.kind == BlockKind.CLASS_BODY)
        }

    private fun typeOf(code: KotlinCode, local: KtLocal): String? {
        // A destructured local's written type is the whole tuple's, not this name's type.
        if (local.destructured) return null
        local.writtenType?.let { return it }
        val initializer = local.initializer ?: return null
        return literalType(code, initializer) ?: constructorType(code, initializer)
    }

    private fun literalType(code: KotlinCode, range: KtRange): String? {
        val tokens = code.tokens.filter {
            !it.isComment && (it.isCode || it.isStringBody) && it.start >= range.start && it.end <= range.end
        }
        if (tokens.isEmpty()) return null
        if (tokens.first().isStringBody && tokens.first().text.startsWith("\"") && tokens.last().isStringBody) {
            return "String"
        }
        val token = tokens.singleOrNull() ?: return null
        return when {
            token.type == KotlinTokenTypes.CHARACTER -> "Char"
            token.type == KotlinTokenTypes.NUMBER -> numberType(token.text)
            token.type == KotlinTokenTypes.KEYWORD && (token.text == "true" || token.text == "false") -> "Boolean"
            else -> null
        }
    }

    private fun numberType(text: String): String? {
        when {
            text.any { it == 'u' || it == 'U' } -> return null
            // An `F` is a hex digit, so the radix decides before the suffix does.
            text.startsWith("0x") || text.startsWith("0X") ->
                return integerType(text.substring(2).replace("_", "").removeSuffix("L").removeSuffix("l"), 16)
            text.startsWith("0b") || text.startsWith("0B") ->
                return integerType(text.substring(2).replace("_", "").removeSuffix("L").removeSuffix("l"), 2)
            text.endsWith("L") || text.endsWith("l") -> return "Long"
            text.endsWith("F") || text.endsWith("f") -> return "Float"
            text.contains('.') || text.contains('e') || text.contains('E') -> return "Double"
        }
        return integerType(text.replace("_", ""), 10)
    }

    /** `Int` inside its range, `Long` past it, `null` when the literal does not fit a signed 64-bit value. */
    private fun integerType(digits: String, radix: Int): String? {
        val value = digits.toLongOrNull(radix)
        if (value != null) return if (value > Int.MAX_VALUE) "Long" else "Int"
        val bits = if (radix == 16) 16 else if (radix == 2) 64 else return null
        return if (digits.isNotEmpty() && digits.length <= bits) "Long" else null
    }

    private fun constructorType(code: KotlinCode, range: KtRange): String? {
        val start = code.code.indexOfFirst { it.start == range.start }.takeIf { it >= 0 } ?: return null
        val name = code.code[start]
        if (!isNameToken(name)) return null
        val raw = name.text.removeSurrounding("`")
        if (raw.isEmpty() || !raw.first().isUpperCase()) return null
        var typeEnd = name.end
        var next = start + 1
        var writtenTypeArguments = false
        if (code.code.getOrNull(next)?.text == "<") {
            val close = angleClose(code, next)
            if (close < 0) return null
            typeEnd = code.code[close].end
            next = close + 1
            writtenTypeArguments = true
        }
        if (!writtenTypeArguments && !classWithoutTypeParameters(code, raw)) return null
        val open = code.code.getOrNull(next) ?: return null
        if (open.text != "(") return null
        val close = code.matchingIndex(open.index)
        if (close < 0) return null
        if (code.code[close].end != range.end) return null
        return code.text.substring(name.start, typeEnd)
    }

    private fun inferMessage(name: String) =
        "Cannot infer the type of $name without a compiler; declare it explicitly"

    /** True when this file declares a class of [name] without type parameters. */
    private fun classWithoutTypeParameters(code: KotlinCode, name: String): Boolean {
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.type != KotlinTokenTypes.KEYWORD || token.text != "class") continue
            val previous = code.code.getOrNull(index - 1)
            if (previous != null && (previous.type == KotlinTokenTypes.DOT || previous.text == "::")) continue
            val className = code.code.getOrNull(index + 1) ?: continue
            if (!isNameToken(className) || className.text.removeSurrounding("`") != name) continue
            if (code.code.getOrNull(index + 2)?.text == "<") continue
            return true
        }
        return false
    }

    // --- type parameters ----------------------------------------------------------------------

    private fun typeParameterRefusal(
        code: KotlinCode,
        selection: KtRange,
        function: KtFunction,
        inputs: List<Parameter>,
    ): String? {
        if (function.typeParameters.isEmpty()) return null
        for (name in function.typeParameters) {
            val used = code.code.any {
                it.start >= selection.start && it.end <= selection.end &&
                    isNameToken(it) && it.text.removeSurrounding("`") == name
            } || inputs.any { namesWord(it.type, name) } || (function.receiver?.let { namesWord(it, name) } == true)
            if (used) return "The selection uses the type parameter $name"
        }
        return null
    }

    private fun namesWord(text: String, word: String): Boolean {
        var index = 0
        while (index < text.length) {
            if (text[index] == '_' || text[index].isLetter()) {
                var end = index + 1
                while (end < text.length && (text[end] == '_' || text[end].isLetterOrDigit())) end++
                if (text.substring(index, end) == word) return true
                index = end
            } else {
                index++
            }
        }
        return false
    }

    // --- outputs ------------------------------------------------------------------------------

    private class Output(val local: KtLocal, val type: String, val declared: Boolean, val offset: Int) {
        val name: String get() = local.nameToken.text
    }

    private sealed interface OutputResult {
        class Refused(val reason: String) : OutputResult

        class Found(val outputs: List<Output>) : OutputResult
    }

    private fun collectOutputs(code: KotlinCode, selection: KtRange): OutputResult {
        val declared = ArrayList<Pair<KtLocal, Int>>()
        for (local in code.locals) {
            if (local.kind != LocalKind.VAL && local.kind != LocalKind.VAR) continue
            if (local.nameToken.start < selection.start || local.nameToken.end > selection.end) continue
            if (code.references.any { it.resolved === local && it.token.start >= selection.end }) {
                declared.add(local to local.nameToken.start)
            }
        }
        val assigned = ArrayList<Pair<KtLocal, Int>>()
        val seen = HashSet<KtLocal>()
        for (token in code.code) {
            if (token.start < selection.start || token.end > selection.end) continue
            val target = assignmentTarget(code, token) ?: continue
            val reference = code.references.firstOrNull { it.token.index == target } ?: continue
            val local = reference.resolved ?: continue
            // `val x += y` is `x.plusAssign(y)`, not an assignment; only a var can be assigned.
            if (local.kind != LocalKind.VAR) continue
            if (local.nameToken.start >= selection.start) continue
            if (seen.add(local)) assigned.add(local to token.start)
        }
        val all = (declared + assigned).sortedBy { it.second }
        if (all.size >= 2) {
            return OutputResult.Refused(changedMessage(all.map { it.first.name }))
        }
        for (local in code.locals) {
            if (local.kind != LocalKind.LOCAL_FUN && local.kind != LocalKind.LOCAL_CLASS) continue
            if (local.nameToken.start < selection.start || local.nameToken.end > selection.end) continue
            if (code.references.any { it.resolved === local && it.token.start >= selection.end }) {
                return OutputResult.Refused("${local.name} is declared in the selection and used after it")
            }
        }
        val pair = all.minByOrNull { it.second } ?: return OutputResult.Found(emptyList())
        val local = pair.first
        if (local.delegated) return OutputResult.Refused("${local.name} is a delegated local")
        val type = typeOf(code, local) ?: return OutputResult.Refused(inferMessage(local.name))
        val isDeclared = declared.any { it.first === local }
        return OutputResult.Found(listOf(Output(local, type, isDeclared, pair.second)))
    }

    private fun assignmentTarget(code: KotlinCode, token: KtToken): Int? {
        if (token.type != KotlinTokenTypes.OPERATION_SIGN) return null
        if (token.text in ASSIGNMENTS) return token.index - 1
        if (token.text != "++" && token.text != "--") return null
        val before = code.code.getOrNull(token.index - 1)
        val after = code.code.getOrNull(token.index + 1)
        return when {
            before != null && isNameToken(before) -> before.index
            after != null && isNameToken(after) -> after.index
            else -> null
        }
    }

    private fun changedMessage(names: List<String>): String {
        val shown = names.take(3)
        val joined = if (shown.size == 2) {
            "${shown[0]} and ${shown[1]}"
        } else {
            shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
        return "The selection changes $joined, which are used later"
    }

    // --- exits --------------------------------------------------------------------------------

    private fun exitsRefusal(code: KotlinCode, selection: KtRange): String? {
        val loops = loops(code)
        for (token in code.code) {
            if (token.start < selection.start || token.end > selection.end) continue
            when (token.text) {
                "return" -> if (!returnAllowed(code, selection, token)) return RETURN
                "break", "continue" -> if (!loopAllowed(code, selection, token, loops)) return LOOP
            }
        }
        return labelRefusal(code, selection, loops)
    }

    /** A `return@`, `this@` or `super@` label has to name a construct written inside the selection. */
    private fun labelRefusal(code: KotlinCode, selection: KtRange, loops: List<Loop>): String? {
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.start < selection.start || token.end > selection.end) continue
            if (token.text !in LABEL_USERS) continue
            if (code.code.getOrNull(index + 1)?.text != "@") continue
            val label = code.code.getOrNull(index + 2) ?: continue
            if (!isNameToken(label)) continue
            val name = label.text.removeSurrounding("`")
            if (!labelWrittenInside(code, selection, loops, name)) {
                return "The selection uses the label @$name from outside it"
            }
        }
        return null
    }

    private fun labelWrittenInside(code: KotlinCode, selection: KtRange, loops: List<Loop>, label: String): Boolean {
        for (block in code.blocks) {
            if (block.open.start < selection.start || (block.close?.end ?: block.open.end) > selection.end) continue
            if (block.kind != BlockKind.LAMBDA && block.kind != BlockKind.FUNCTION_BODY) continue
            if (blockLabel(code, block) == label) return true
        }
        for (loop in loops) {
            if (loop.start < selection.start || loop.bodyEnd > selection.end) continue
            val name = code.code.getOrNull(loop.keywordIndex - 2) ?: continue
            if (code.code.getOrNull(loop.keywordIndex - 1)?.text != "@") continue
            if (isNameToken(name) && name.text.removeSurrounding("`") == label) return true
        }
        return code.locals.any {
            it.kind == LocalKind.LOCAL_FUN && it.name == label &&
                it.nameToken.start >= selection.start && it.nameToken.end <= selection.end
        }
    }

    /** The label a lambda or anonymous function declares: `label@` before it or the callee that takes it. */
    private fun blockLabel(code: KotlinCode, block: KtBlock): String? {
        val before = code.code.getOrNull(block.open.index - 1) ?: return null
        if (before.text == "@") {
            val name = code.code.getOrNull(block.open.index - 2) ?: return null
            return if (isNameToken(name)) name.text.removeSurrounding("`") else null
        }
        if (before.text == "fun") {
            if (code.code.getOrNull(block.open.index - 2)?.text != "@") return null
            val name = code.code.getOrNull(block.open.index - 3) ?: return null
            return if (isNameToken(name)) name.text.removeSurrounding("`") else null
        }
        if (isNameToken(before)) return before.text.removeSurrounding("`")
        if (before.text != ")") return null
        val open = code.matchingIndex(before.index)
        if (open < 0) return null
        var callee = code.code.getOrNull(open - 1) ?: return null
        if (callee.text == ">") {
            val angleOpen = angleOpenBefore(code, open - 1)
            if (angleOpen <= 0) return null
            callee = code.code.getOrNull(angleOpen - 1) ?: return null
        }
        return if (isNameToken(callee)) callee.text.removeSurrounding("`") else null
    }

    private fun returnAllowed(code: KotlinCode, selection: KtRange, token: KtToken): Boolean {
        val block = code.blockChain(token.start)
            .filter {
                it.open.start >= selection.start &&
                    (it.kind == BlockKind.FUNCTION_BODY || it.kind == BlockKind.LAMBDA)
            }
            .maxByOrNull { it.open.start } ?: return false
        return when (block.kind) {
            BlockKind.FUNCTION_BODY -> true
            BlockKind.LAMBDA -> code.code.getOrNull(token.index + 1)?.text == "@"
            else -> false
        }
    }

    private class Loop(val keywordIndex: Int, val start: Int, val bodyStart: Int, val bodyEnd: Int)

    private fun loops(code: KotlinCode): List<Loop> {
        val result = ArrayList<Loop>()
        for (index in code.code.indices) {
            val token = code.code[index]
            if (token.type != KotlinTokenTypes.KEYWORD) continue
            if (token.text != "for" && token.text != "while" && token.text != "do") continue
            if (token.text == "while" && trailingWhile(code, token)) continue
            val bodyIndex = if (token.text == "do") {
                index + 1
            } else {
                val open = code.code.getOrNull(index + 1) ?: continue
                if (open.text != "(") continue
                val close = code.matchingIndex(open.index)
                if (close < 0) continue
                close + 1
            }
            val body = code.code.getOrNull(bodyIndex) ?: continue
            val bodyStart: Int
            val bodyEnd: Int
            if (body.text == "{") {
                val block = code.blocks.firstOrNull { it.open.index == body.index && it.kind == BlockKind.CONTROL_BODY }
                    ?: continue
                bodyStart = block.open.start
                bodyEnd = block.close?.end ?: body.end
            } else {
                bodyStart = body.start
                bodyEnd = maxOf(code.statementAt(body.start)?.end ?: body.end, body.end)
            }
            result.add(Loop(index, token.start, bodyStart, bodyEnd))
        }
        return result
    }

    private fun trailingWhile(code: KotlinCode, token: KtToken): Boolean {
        val statement = code.statementAt(token.start)
        val first = statement?.let { code.code.getOrNull(it.startTokenIndex) }
        if (first?.text == "do") return true
        val previous = code.code.getOrNull(token.index - 1) ?: return false
        if (previous.text != "}") return false
        val open = code.matchingIndex(previous.index)
        if (open < 0) return false
        val block = code.blocks.firstOrNull { it.open.index == open }
        return block?.kind == BlockKind.CONTROL_BODY && block.headerKeyword == "do"
    }

    private fun loopAllowed(code: KotlinCode, selection: KtRange, token: KtToken, loops: List<Loop>): Boolean {
        if (code.code.getOrNull(token.index + 1)?.text == "@") {
            val label = code.code.getOrNull(token.index + 2)?.text ?: return false
            val loop = labeledLoop(code, selection, label, loops) ?: return false
            return loop.start >= selection.start && loop.bodyEnd <= selection.end && loop.bodyStart <= token.start
        }
        val loop = loops.filter { it.bodyStart <= token.start && token.start <= it.bodyEnd }
            .maxByOrNull { it.start } ?: return false
        return loop.start >= selection.start && loop.bodyEnd <= selection.end
    }

    private fun labeledLoop(code: KotlinCode, selection: KtRange, label: String, loops: List<Loop>): Loop? {
        for (index in code.code.indices) {
            val token = code.code[index]
            if (!isNameToken(token) || token.text.removeSurrounding("`") != label) continue
            if (token.start < selection.start || token.end > selection.end) continue
            if (code.code.getOrNull(index + 1)?.text != "@") continue
            val keyword = code.code.getOrNull(index + 2) ?: continue
            if (keyword.text != "for" && keyword.text != "while" && keyword.text != "do") continue
            return loops.firstOrNull { it.keywordIndex == keyword.index }
        }
        return null
    }

    /** A name token in the selection that the model cannot place (R2 「Unread spellings」). */
    private fun unreadRefusal(code: KotlinCode, selection: KtRange): String? {
        val token = code.unreadNameTokens(selection).firstOrNull() ?: return null
        return "${token.spelling()} appears where this refactoring cannot read it"
    }

    // --- name ---------------------------------------------------------------------------------

    private fun nameStep(code: KotlinCode, build: (String) -> RefactoringEdit): RefactoringStep =
        RefactoringStep.AskName("Extract Method", suggestedName(code)) { name ->
            when {
                !isValidKotlinIdentifier(name) || isHardKeyword(name) -> NameCheck.Invalid(NOT_A_NAME)
                isNameUsedInFile(code, name) -> NameCheck.Invalid("'$name' is already used in this file")
                else -> NameCheck.Valid(build(name))
            }
        }

    private fun suggestedName(code: KotlinCode): String {
        if (!isNameUsedInFile(code, "extracted")) return "extracted"
        var counter = 2
        while (isNameUsedInFile(code, "extracted$counter")) counter++
        return "extracted$counter"
    }

    private fun isNameUsedInFile(code: KotlinCode, name: String): Boolean =
        code.code.any { it.spelling() == name && it.index !in code.modifierTokens }

    // --- edits --------------------------------------------------------------------------------

    private fun statementEdit(
        code: KotlinCode,
        selection: KtRange,
        enclosing: Enclosing,
        inputs: List<Parameter>,
        output: Output?,
        name: String,
    ): RefactoringEdit {
        val lineBreak = code.lineBreak(enclosing.function.declarationEnd)
        val insertAt = code.lineEnd(enclosing.function.declarationEnd)
        val replacement = callText(name, inputs, output)
        val functionText = statementFunction(code, enclosing, name, inputs, output, selection, lineBreak)
        val edits = listOf(
            TextEdit(selection.start, selection.end, replacement),
            TextEdit(insertAt, insertAt, lineBreak + lineBreak + functionText),
        )
        val caret = selection.start + replacement.indexOf("$name(")
        return RefactoringEdit(edits, caret, "Extracted method $name")
    }

    private fun expressionEdit(
        code: KotlinCode,
        selection: KtRange,
        enclosing: Enclosing,
        inputs: List<Parameter>,
        name: String,
    ): RefactoringEdit {
        val lineBreak = code.lineBreak(enclosing.function.declarationEnd)
        val insertAt = code.lineEnd(enclosing.function.declarationEnd)
        val replacement = "$name(${inputs.joinToString(", ") { it.name }})"
        val functionText = expressionFunction(code, enclosing, name, inputs, selection, lineBreak)
        val edits = listOf(
            TextEdit(selection.start, selection.end, replacement),
            TextEdit(insertAt, insertAt, lineBreak + lineBreak + functionText),
        )
        return RefactoringEdit(edits, selection.start, "Extracted method $name")
    }

    private fun callText(name: String, inputs: List<Parameter>, output: Output?): String {
        val call = "$name(${inputs.joinToString(", ") { it.name }})"
        return when {
            output == null -> call
            output.declared -> "${if (output.local.kind == LocalKind.VAR) "var" else "val"} ${output.name} = $call"
            else -> "${output.name} = $call"
        }
    }

    private fun statementFunction(
        code: KotlinCode,
        enclosing: Enclosing,
        name: String,
        inputs: List<Parameter>,
        output: Output?,
        selection: KtRange,
        lineBreak: String,
    ): String {
        val base = enclosing.indent + enclosing.unit
        val body = ArrayList<String>()
        if (output != null && !output.declared) body.add(base + "var ${output.name} = ${output.name}")
        body.addAll(reindented(code, selection.start, selection.end, base))
        if (output != null) body.add(base + "return ${output.name}")
        val returnPart = if (output != null) ": ${output.type}" else ""
        val lines = ArrayList<String>()
        lines.addAll(annotationLines(enclosing))
        lines.add(declaration(enclosing, name, inputs) + returnPart + " {")
        lines.addAll(body)
        lines.add(enclosing.indent + "}")
        return lines.joinToString(lineBreak)
    }

    private fun expressionFunction(
        code: KotlinCode,
        enclosing: Enclosing,
        name: String,
        inputs: List<Parameter>,
        selection: KtRange,
        lineBreak: String,
    ): String {
        val base = enclosing.indent + enclosing.unit
        val lines = linesOf(code, selection.start, selection.end)
        val continuation = lines.drop(1)
        val common = sharedPrefix(
            continuation.filter { it.second.isNotBlank() && it.first !in code.rawContinuationStarts }
                .map { (_, line) -> line.takeWhile { char -> char == ' ' || char == '\t' } },
        )
        val result = ArrayList<String>()
        result.addAll(annotationLines(enclosing))
        result.add(declaration(enclosing, name, inputs) + " = " + lines.first().second)
        for ((offset, line) in continuation) {
            result.add(
                when {
                    offset in code.rawContinuationStarts -> line
                    line.isBlank() -> ""
                    else -> base + line.removePrefix(common)
                },
            )
        }
        return result.joinToString(lineBreak)
    }

    private fun declaration(enclosing: Enclosing, name: String, inputs: List<Parameter>): String {
        val function = enclosing.function
        val suspend = if ("suspend" in function.modifiers) "suspend " else ""
        val receiver = function.receiver?.let { "$it." } ?: ""
        val parameters = inputs.joinToString(", ") { "${it.name}: ${it.type}" }
        return enclosing.indent + "private " + suspend + "fun " + receiver + name + "(" + parameters + ")"
    }

    private fun annotationLines(enclosing: Enclosing): List<String> =
        enclosing.function.annotations.filter { isComposable(it) }.map { enclosing.indent + it }

    private fun isComposable(annotation: String): Boolean {
        val name = annotation.removePrefix("@").substringBefore('(').substringBefore(':')
        return name.substringAfterLast('.') == "Composable"
    }

    /**
     * The lines of [start]..[end] with their offsets. A line that starts inside a string is kept as
     * written and left out of the common indentation, so a raw string's value does not change.
     */
    private fun linesOf(code: KotlinCode, start: Int, end: Int): List<Pair<Int, String>> {
        val lines = ArrayList<Pair<Int, String>>()
        var lineStart = start
        var index = start
        while (index < end) {
            val char = code.text[index]
            if (char == '\r' || char == '\n') {
                lines.add(lineStart to code.text.substring(lineStart, index))
                index += if (char == '\r' && index + 1 < end && code.text[index + 1] == '\n') 2 else 1
                lineStart = index
                continue
            }
            index++
        }
        if (lineStart < end || lines.isEmpty()) lines.add(lineStart to code.text.substring(lineStart, end))
        return lines
    }

    private fun reindented(code: KotlinCode, start: Int, end: Int, base: String): List<String> {
        val lines = linesOf(code, start, end)
        val indents = ArrayList<String>()
        indents.add(code.indentation(start))
        for ((offset, line) in lines.drop(1)) {
            if (line.isBlank() || offset in code.rawContinuationStarts) continue
            indents.add(line.takeWhile { it == ' ' || it == '\t' })
        }
        val common = sharedPrefix(indents)
        return lines.mapIndexed { index, (offset, line) ->
            when {
                index == 0 -> base + line
                offset in code.rawContinuationStarts -> line
                line.isBlank() -> ""
                else -> base + line.removePrefix(common)
            }
        }
    }

    private fun sharedPrefix(values: List<String>): String {
        if (values.isEmpty()) return ""
        var prefix = values.first()
        for (value in values.drop(1)) {
            var index = 0
            while (index < prefix.length && index < value.length && prefix[index] == value[index]) index++
            prefix = prefix.substring(0, index)
        }
        return prefix
    }
}
