package stride.lang.kotlin.refactor

import stride.lang.TokenType
import stride.lang.kotlin.KotlinTokenTypes
import stride.lang.kotlin.codeTokens

/** An offset range `[start, end)` in the prepared text. */
internal data class KtRange(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start

    val length: Int get() = end - start

    fun contains(offset: Int): Boolean = offset in start until end

    fun asIntRange(): IntRange = start until end
}

/** One lexer token. [index] is the position in [KotlinCode.code] for code tokens and `-1` otherwise. */
internal class KtToken(
    val index: Int,
    val type: TokenType,
    val start: Int,
    val end: Int,
    val text: String,
) {
    val isComment: Boolean = type == KotlinTokenTypes.LINE_COMMENT || type == KotlinTokenTypes.BLOCK_COMMENT

    val isStringBody: Boolean = type == KotlinTokenTypes.STRING

    /** Tokens a refactoring may edit: everything but comments and string bodies (template code included). */
    val isCode: Boolean = !isComment && !isStringBody

    val range: KtRange get() = KtRange(start, end)

    override fun toString(): String = "$text@$start"
}

internal enum class BlockKind {
    FUNCTION_BODY,
    INITIALIZER,
    CONTROL_BODY,
    WHEN_BODY,
    CLASS_BODY,
    LAMBDA,
}

/** A code `{…}` pair. [start] and [end] are the content offsets, between the braces. */
internal class KtBlock(
    val kind: BlockKind,
    val open: KtToken,
    val close: KtToken?,
    val parent: KtBlock?,
    /** The keyword the header ended with, when one was found (`if`, `do`, `class`, `fun`, …). */
    val headerKeyword: String?,
    val loopBody: Boolean,
) {
    val start: Int get() = open.end

    val end: Int get() = close?.start ?: open.start

    val range: KtRange get() = KtRange(open.start, close?.end ?: open.end)

    val isStatementBlock: Boolean
        get() = kind == BlockKind.FUNCTION_BODY || kind == BlockKind.INITIALIZER ||
            kind == BlockKind.CONTROL_BODY || kind == BlockKind.LAMBDA

    val isInterfaceBody: Boolean get() = kind == BlockKind.CLASS_BODY && headerKeyword == "interface"

    fun contains(offset: Int): Boolean = offset >= start && offset <= end

    override fun toString(): String = "$kind(${open.start}..${close?.start})"
}

/** One statement of a statement block; [start] and [end] cover its first and last code tokens. */
internal class KtStatement(
    val block: KtBlock,
    val start: Int,
    val end: Int,
    val startTokenIndex: Int,
    val endTokenIndex: Int,
) {
    val range: KtRange get() = KtRange(start, end)

    fun contains(offset: Int): Boolean = offset >= start && offset < end
}

/**
 * A read-only model of one Kotlin document built from `KotlinLexer` tokens: code and comment
 * tokens, brackets, `{…}` blocks, statements, function headers, locals, references, calls,
 * effects and the expression judgments the refactorings share. Construct it once for one text
 * and ask many questions; nothing is lexed again.
 */
internal class KotlinCode(val text: String) {
    val tokens: List<KtToken>

    /** Code tokens only, sorted by offset and non-overlapping; [KtToken.index] indexes this list. */
    val code: List<KtToken>

    private val lineStarts: IntArray

    /** Code token index → matching bracket token index, `-1` when unmatched. */
    val bracketPairs: IntArray

    val bracketsMatch: Boolean

    val blocks: List<KtBlock>

    val statements: List<KtStatement>

    val functions: List<KtFunction>

    val locals: List<KtLocal>

    val references: List<KtReference>

    val calls: List<KtCall>

    val effects: List<KtEffect>

    /** The file's R2 smart casts, built on first use (see `KotlinSmartCasts.kt`). */
    internal var smartCastCache: List<KtSmartCast>? = null

    /** The name chains the file reads, built on first use (see `KotlinSmartCasts.kt`). */
    internal var readChainCache: List<KtReadChain>? = null

    /** Code token indices that a reference scan must not treat as references. */
    internal val keywordTokens: MutableSet<Int> = HashSet()

    /** Code token indices of declared names: locals, parameters, destructured and function names. */
    internal val declarationTokens: MutableSet<Int> = HashSet()

    /** Code token indices of class, interface, object, type alias and property names. */
    internal val declarationNameTokens: MutableSet<Int> = HashSet()

    /** Code token indices of modifier words that modify a declaration rather than name anything. */
    internal val modifierTokens: MutableSet<Int> = HashSet()

    /** Code token indices of a name attached to a number literal, which is part of it (`1u`, `2UL`). */
    internal val numberSuffixTokens: MutableSet<Int> = HashSet()

    /** `start until end` ranges of annotations, imports and package directives. */
    internal val nonExpressionRanges: MutableList<KtRange> = ArrayList()

    /** Code token indices that sit inside a string template: `$name` or `${…}`. */
    internal val templateTokens: MutableSet<Int> = HashSet()

    /** Code token indices of `$name` templates. */
    internal val simpleTemplateTokens: MutableSet<Int> = HashSet()

    /** Text ranges of `${…}` bodies, the offsets between the opening and the closing string pieces. */
    internal val templateExpressionRanges: MutableList<KtRange> = ArrayList()

    /** Starts of raw-string pieces that continue a literal from a previous line. */
    internal val rawContinuationStarts: Set<Int>

    /** Code token index → true when the token is template code inside a string literal. */
    private val insideStringTokens: BooleanArray

    /** Code token index → the token before it, string bodies included. */
    private val previousTokens: Array<KtToken?>

    /** Code token index → the token after it, string bodies included. */
    private val nextTokens: Array<KtToken?>

    init {
        val raw = codeTokens(text).sortedBy { it.start }
        var index = 0
        tokens = raw.map { token ->
            val codeIndex = if (isCodeType(token.type)) index++ else -1
            KtToken(codeIndex, token.type, token.start, token.end, token.text)
        }
        code = tokens.filter { it.isCode }
        for (index in 1 until code.size) {
            val previous = code[index - 1]
            val token = code[index]
            if (previous.type == KotlinTokenTypes.NUMBER && previous.end == token.start && isNameToken(token)) {
                numberSuffixTokens.add(token.index)
            }
        }

        lineStarts = buildLineStarts(text)
        rawContinuationStarts = rawContinuations(tokens)
        insideStringTokens = buildInsideStringTokens()
        previousTokens = buildPreviousTokens()
        nextTokens = buildNextTokens()
        val pairs = IntArray(code.size) { -1 }
        var matched = true
        val stack = ArrayList<Int>()
        for (i in code.indices) {
            when (code[i].text) {
                "(", "[", "{" -> stack.add(i)
                ")", "]", "}" -> {
                    val opened = if (stack.isEmpty()) -1 else stack.removeAt(stack.size - 1)
                    if (opened < 0 || clashing(code[opened].text, code[i].text)) {
                        matched = false
                    } else {
                        pairs[opened] = i
                        pairs[i] = opened
                    }
                }
            }
        }
        if (stack.isNotEmpty()) matched = false
        bracketPairs = pairs
        bracketsMatch = matched

        blocks = computeBlocks(this)
        statements = computeStatements(this)
        functions = computeFunctions(this)
        locals = computeLocals(this)
        references = computeReferences(this)
        calls = computeCalls(this)
        effects = computeEffects(this)
        for (local in locals) declarationTokens.add(local.nameToken.index)
        for (function in functions) declarationTokens.add(function.nameToken.index)
    }

    fun tokenAt(offset: Int): KtToken? {
        var low = 0
        var high = code.size - 1
        var found: KtToken? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val token = code[middle]
            if (token.start <= offset) {
                found = token
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found?.takeIf { offset < it.end }
    }

    /** The bracket partner of code token [index], or `null`. */
    fun matching(index: Int): KtToken? {
        val partner = bracketPairs.getOrNull(index) ?: -1
        return code.getOrNull(partner)
    }

    fun matchingIndex(index: Int): Int = bracketPairs.getOrNull(index) ?: -1

    fun isOpener(token: KtToken): Boolean = token.text == "(" || token.text == "[" || token.text == "{"

    fun isCloser(token: KtToken): Boolean = token.text == ")" || token.text == "]" || token.text == "}"

    /** The innermost block holding [offset], or `null` outside every block. */
    fun blockAt(offset: Int): KtBlock? = blocks.lastOrNull { it.contains(offset) }

    /** Blocks holding [offset], outermost first. */
    fun blockChain(offset: Int): List<KtBlock> = blocks.filter { it.contains(offset) }.sortedBy { it.open.start }

    /** The innermost statement holding [offset], or `null`. */
    fun statementAt(offset: Int): KtStatement? = statements.lastOrNull { it.contains(offset) }

    fun lineStart(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.size - 1
        var answer = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= bounded) {
                answer = lineStarts[middle]
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }

    fun lineEnd(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        var index = bounded
        while (index < text.length && text[index] != '\n' && text[index] != '\r') index++
        return index
    }

    fun lineIndex(offset: Int): Int {
        val start = lineStart(offset)
        var low = 0
        var high = lineStarts.size - 1
        var answer = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= start) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }

    fun isBlank(from: Int, to: Int): Boolean {
        if (to <= from) return true
        for (index in from until to.coerceAtMost(text.length)) {
            if (!text[index].isWhitespace()) return false
        }
        return true
    }

    /** The whitespace before the first token on [offset]'s line. */
    fun indentation(offset: Int): String {
        val start = lineStart(offset)
        var index = start
        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
        return text.substring(start, index)
    }

    /** The line separator the text uses, `\n` when it has none yet. */
    fun lineBreak(offset: Int = 0): String {
        val lineEnd = lineEnd(offset)
        return if (lineEnd < text.length && text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n') "\r\n" else "\n"
    }

    /**
     * True when no code token other than the statement itself sits on its lines, before or after it.
     * With [trailingSemicolon] a closing `;` right after the statement counts as part of it.
     */
    fun isAloneOnLine(statement: KtStatement, trailingSemicolon: Boolean = false): Boolean {
        if (!isBlank(lineStart(statement.start), statement.start)) return false
        val lineEnd = lineEnd(statement.end)
        var after = statement.end
        if (trailingSemicolon && after < lineEnd && text[after] == ';') after++
        var index = after
        while (index < lineEnd) {
            if (!text[index].isWhitespace()) {
                return isCommentOnly(index, lineEnd)
            }
            index++
        }
        return true
    }

    private fun isCommentOnly(from: Int, to: Int): Boolean {
        var index = from
        while (index < to) {
            when {
                text[index].isWhitespace() -> index++
                text.startsWith("//", index) -> return true
                text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2)
                    // A block comment that closes after this line leaves code (its `*/`) behind.
                    if (end < 0 || end + 2 > to) return false
                    index = end + 2
                }
                else -> return false
            }
        }
        return true
    }

    /**
     * True when something besides whitespace, a line comment or a comment closing on this line
     * follows [offset] on its line; with [allowSemicolon] a closing `;` counts as part of it.
     */
    internal fun lineHoldsCodeAfter(offset: Int, allowSemicolon: Boolean = false): Boolean {
        val end = lineEnd(offset)
        var index = offset
        while (index < end) {
            when {
                text[index].isWhitespace() -> index++
                allowSemicolon && text[index] == ';' -> index++
                text.startsWith("//", index) -> return false
                text.startsWith("/*", index) -> {
                    val close = text.indexOf("*/", index + 2)
                    if (close < 0 || close + 2 > end) return true
                    index = close + 2
                }
                else -> return true
            }
        }
        return false
    }

    /** True when the newline between the two code tokens ends a statement. */
    internal fun splitsAfter(previous: KtToken, next: KtToken): Boolean {
        if (previous.text == ";") return true
        if (!hasNewline(previous.end, next.start)) return false
        if (next.isStringBody && next.start in rawContinuationStarts) return false
        if (templateExpressionRanges.any { previous.end >= it.start && previous.end <= it.end && next.start <= it.end }) {
            return false
        }
        if (next.text == "{") return !joinsBlockOnNextLine(previous)
        if (cannotEndExpression(previous)) return false
        if (continuesExpression(next)) return false
        if (lineHoldsOnlyAnnotations(previous)) return false
        if (isControlHeaderEnd(previous)) return false
        if (isInfixNameAtLineEnd(previous)) return false
        return true
    }

    /**
     * True when Kotlin reads a `{` on the next line as the block of the line ending with
     * [previous]: a call's argument list, a declaration or control header, a member name after
     * `.` or `?.`, a line that cannot end an expression, or an infix call's right operand
     * (R2 「Statements」). After a literal, a string, `]`, `!!`, a grouping `)` or a lambda it
     * starts a statement of its own.
     */
    private fun joinsBlockOnNextLine(previous: KtToken): Boolean {
        if (previous.text == "->") return true
        if (endsNullableType(previous)) {
            if (lineHoldsDeclarationHeader(previous)) return true
        } else if (closesTypeArguments(previous)) {
            return true
        }
        if (previous.text == ")" && closesCallOrHeader(previous)) return true
        if (isControlHeaderEnd(previous)) return true
        if (previous.type == KotlinTokenTypes.KEYWORD && previous.text == "init") return true
        if (cannotEndExpression(previous)) return true
        if (isInfixNameAtLineEnd(previous)) return true
        if (lineHoldsOnlyAnnotations(previous)) return true
        if (!isNameToken(previous)) return false
        if (previousToken(previous)?.type == KotlinTokenTypes.DOT) return true
        return lineHoldsDeclarationHeader(previous)
    }

    /** True when the `)` closes a call's argument list or a control or declaration header. */
    private fun closesCallOrHeader(close: KtToken): Boolean {
        val openIndex = matchingIndex(close.index)
        if (openIndex < 0 || code[openIndex].text != "(") return false
        val head = code.getOrNull(openIndex - 1) ?: return false
        if (isNameToken(head)) return true
        if (head.type == KotlinTokenTypes.KEYWORD && head.text in CONTROL_PARENTHESES) return true
        return head.text == ")" || head.text == "]" || closesTypeArguments(head)
    }

    /**
     * True when [token] is the `>` closing type arguments or type parameters, also when the lexer
     * merged it with `*`, `?` or another `>` (`>>`, `*>`, `?>`, `>?`).
     */
    private fun closesTypeArguments(token: KtToken): Boolean =
        token.type == KotlinTokenTypes.OPERATION_SIGN && token.text.any { it == '>' } &&
            token.text.all { it == '>' || it == '*' || it == '?' }

    /** True when [token] ends a nullable type: a `?`, or a merged type close followed by `?`. */
    private fun endsNullableType(token: KtToken): Boolean =
        token.text == "?" || (token.text.endsWith("?") && closesTypeArguments(token))

    /** True when the token at [index] is a function type's `->`, whose `>` closes no list. */
    private fun isFunctionTypeArrow(index: Int): Boolean =
        code[index].text == "->" && code.getOrNull(index - 1)?.text == ")"

    /**
     * True when the line holding [token] is a `fun`, `class`, `object`, `interface` or `init`
     * header with no `=` before [token], so a `{` on the next line is its body.
     */
    private fun lineHoldsDeclarationHeader(token: KtToken): Boolean {
        val start = lineStart(token.start)
        var index = token.index - 1
        while (index >= 0) {
            val candidate = code[index]
            if (candidate.start < start) return false
            if (isCloser(candidate)) {
                val open = matchingIndex(index)
                if (open < 0) return false
                index = open - 1
                continue
            }
            if (candidate.text == "=" || candidate.text == ";" || candidate.text == "{" || candidate.text == "}") {
                return false
            }
            if (candidate.type == KotlinTokenTypes.KEYWORD && candidate.text in DECLARATION_HEADER_KEYWORDS) {
                return true
            }
            index--
        }
        return false
    }

    /**
     * True when the line ends with a name Kotlin reads as an infix call (`a to`, `0 until`), so the
     * next line is its right operand: the name is no keyword and the token right before it on the
     * same line ends an operand (R2 「Statements」).
     */
    private fun isInfixNameAtLineEnd(previous: KtToken): Boolean {
        if (previous.type != KotlinTokenTypes.IDENTIFIER) return false
        if (insideStringTokens.getOrElse(previous.index) { false }) return false
        // A string literal is one token spanning its template code, so its end is the head.
        val head = previousToken(previous) ?: return false
        if (hasNewline(head.end, previous.start)) return false
        // `1u` is a number with a suffix the lexer splits, not `1 u <next line>`.
        if (previous.index in numberSuffixTokens) return false
        if (!endsOperandBeforeInfix(head)) return false
        // `0 until rows`: the name before `rows` is already an infix operator, so `rows` completes
        // an operand and the next line is not its right side.
        if (isNameToken(head) && inInfixOperatorPosition(head.index)) return false
        return true
    }

    private fun inInfixOperatorPosition(index: Int): Boolean {
        val before = previousTokens.getOrNull(index) ?: return false
        return endsOperandBeforeInfix(before)
    }

    /**
     * The token before each code token with string bodies included: a string literal is one token
     * whose end comes after the template code it holds, so the latest end wins.
     */
    private fun buildPreviousTokens(): Array<KtToken?> {
        val visible = tokens.filter { !it.isComment }
        val previous = arrayOfNulls<KtToken>(code.size)
        var next = 0
        var bestEnd = -1
        var best: KtToken? = null
        for (token in code) {
            while (next < visible.size && visible[next].start < token.start) {
                val candidate = visible[next]
                if (candidate.end > bestEnd) {
                    bestEnd = candidate.end
                    best = candidate
                }
                next++
            }
            previous[token.index] = best?.takeIf { it.end <= token.start }
        }
        return previous
    }

    /**
     * The token right before [token], comments skipped. A string literal counts as one token: the
     * piece that ends it, so the code before the literal is not its neighbour.
     */
    internal fun previousToken(token: KtToken): KtToken? = previousTokens.getOrNull(token.index)

    /**
     * The token right after [token], comments skipped. A string literal counts as one token: the
     * piece that starts it, so the code after the literal is not its neighbour.
     */
    internal fun nextToken(token: KtToken): KtToken? = nextTokens.getOrNull(token.index)

    private fun buildNextTokens(): Array<KtToken?> {
        val visible = tokens.filter { !it.isComment }
        val next = arrayOfNulls<KtToken>(code.size)
        var index = 0
        for (token in code) {
            while (index < visible.size && visible[index].start < token.end) index++
            next[token.index] = visible.getOrNull(index)
        }
        return next
    }

    private fun buildInsideStringTokens(): BooleanArray {
        val strings = tokens.filter { it.isStringBody }
        val inside = BooleanArray(code.size)
        var stringIndex = 0
        for (token in code) {
            while (stringIndex < strings.size && strings[stringIndex].end <= token.start) stringIndex++
            if (stringIndex >= strings.size) break
            val string = strings[stringIndex]
            if (string.start <= token.start && string.end >= token.end) inside[token.index] = true
        }
        return inside
    }

    private fun endsOperandBeforeInfix(token: KtToken): Boolean {
        when (token.type) {
            KotlinTokenTypes.NUMBER, KotlinTokenTypes.CHARACTER, KotlinTokenTypes.STRING -> return true
            KotlinTokenTypes.OPERATION_SIGN -> return token.text == "!!"
        }
        if (token.text == "]" || token.text == "}" || token.text == "this" ||
            token.text == "true" || token.text == "false" || token.text == "null"
        ) {
            return true
        }
        if (token.text == ")") return !isControlHeaderEnd(token)
        if (!isNameToken(token)) return false
        if (token.index in modifierTokens) return false
        if (token.text == "by" || token.text == "where") return false
        if (code.getOrNull(token.index - 1)?.text == "@") return false
        return code.getOrNull(token.index + 1)?.text != "@"
    }

    private fun hasNewline(from: Int, to: Int): Boolean {
        if (to <= from) return false
        for (index in from until to.coerceAtMost(text.length)) {
            if (text[index] == '\n' || text[index] == '\r') return true
        }
        return false
    }

    private fun cannotEndExpression(token: KtToken): Boolean = when (token.type) {
        KotlinTokenTypes.DOT, KotlinTokenTypes.COMMA -> true
        KotlinTokenTypes.PARENTHESES, KotlinTokenTypes.BRACKETS -> token.text == "(" || token.text == "["
        KotlinTokenTypes.OPERATION_SIGN ->
            token.text != "++" && token.text != "--" && token.text != "!!" && !endsTypeSyntax(token)
        KotlinTokenTypes.KEYWORD -> token.text == "as" || token.text == "is" || token.text == "in"
        else -> false
    }

    /**
     * True when [token] is type syntax that can end an expression: the `?` after a type, or the
     * `>` closing a type argument or type parameter list. A comparison `>` stays an operator
     * (R2 「Statements」).
     */
    private fun endsTypeSyntax(token: KtToken): Boolean {
        if (token.text == "?") return true
        if (token.text == "->" || !token.text.contains('>')) return false
        val open = typeOpenFor(token.index)
        if (open < 0 || !holdsTypeSyntax(open, token.index)) return false
        return isTypeArgumentOpen(this, open, atLineEnd = true)
    }

    /** True when only type syntax sits between the `<` at [openIndex] and [closeIndex]. */
    private fun holdsTypeSyntax(openIndex: Int, closeIndex: Int): Boolean {
        for (index in openIndex + 1 until closeIndex) {
            val token = code[index]
            when (token.type) {
                KotlinTokenTypes.NUMBER, KotlinTokenTypes.STRING, KotlinTokenTypes.CHARACTER -> return false
                KotlinTokenTypes.OPERATION_SIGN ->
                    if (token.text != "->" && token.text.any { it != '<' && it != '>' && it != '?' && it != '*' }) {
                        return false
                    }
                else -> Unit
            }
        }
        return true
    }

    /** The `<` whose type list the `>` in the token at [closeIndex] closes, or `-1`. */
    private fun typeOpenFor(closeIndex: Int): Int {
        var depth = 0
        for (index in closeIndex downTo 0) {
            val token = code[index]
            if (token.text == "{" || token.text == "}" || token.text == ";") return -1
            if (isFunctionTypeArrow(index)) continue
            var opens = 0
            var closes = 0
            for (char in token.text) {
                if (char == '<') opens++
                if (char == '>') closes++
            }
            depth += closes - opens
            if (opens > 0 && depth <= 0) return index
        }
        return -1
    }

    private fun continuesExpression(next: KtToken): Boolean {
        if (next.text == "." || next.text == "?." || next.text == "?:" || next.text == "&&" ||
            next.text == "||" || next.text == "->" || next.text == ":" || next.text == "::"
        ) {
            return true
        }
        if (next.text == "?" && code.getOrNull(next.index + 1)?.type == KotlinTokenTypes.DOT) return true
        if (next.type == KotlinTokenTypes.KEYWORD && next.text in CONTINUATION_KEYWORDS) return true
        if (next.text == "while" && closesDoBlockBefore(next)) return true
        return false
    }

    private fun closesDoBlockBefore(whileToken: KtToken): Boolean {
        val previous = code.getOrNull(whileToken.index - 1) ?: return false
        if (previous.text != "}") return false
        val open = matching(previous.index) ?: return false
        val block = blocks.firstOrNull { it.open.start == open.start } ?: return false
        return block.kind == BlockKind.CONTROL_BODY && block.headerKeyword == "do"
    }

    /** `else`, `try`, `do` and `finally` cannot end an expression: the line after them continues the statement. */
    private fun isControlHeaderEnd(token: KtToken): Boolean {
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in CONTROL_HEADER_KEYWORDS) return true
        if (token.text != ")") return false
        val open = matching(token.index) ?: return false
        val head = code.getOrNull(open.index - 1) ?: return false
        if (head.type != KotlinTokenTypes.KEYWORD) return false
        if (head.text == "while" && trailingWhileOfDo(head)) return false
        return head.text in setOf("if", "for", "while", "catch")
    }

    /** True when the `while` keyword ends a `do … while (…)` statement rather than opening a loop. */
    private fun trailingWhileOfDo(whileToken: KtToken): Boolean {
        val previous = code.getOrNull(whileToken.index - 1) ?: return false
        if (previous.text != "}") return false
        val open = matching(previous.index) ?: return false
        val block = blocks.firstOrNull { it.open.start == open.start } ?: return false
        return block.kind == BlockKind.CONTROL_BODY && block.headerKeyword == "do"
    }

    /** True when the line holding [token] consists only of annotations. */
    private fun lineHoldsOnlyAnnotations(token: KtToken): Boolean {
        val start = lineStart(token.start)
        val end = lineEnd(token.start)
        val onLine = code.filter { it.start in start until end }
        if (onLine.isEmpty()) return false
        var i = 0
        while (i < onLine.size) {
            if (onLine[i].text != "@") return false
            i++
            if (i + 1 < onLine.size && onLine[i + 1].text == ":") i += 2
            if (i >= onLine.size) return false
            if (!isNameToken(onLine[i])) return false
            i++
            if (i < onLine.size && onLine[i].text == "(") {
                val close = matchingIndex(onLine[i].index)
                if (close < 0) return false
                val closeToken = code[close]
                while (i < onLine.size && onLine[i].start <= closeToken.start) i++
            }
        }
        return true
    }

    private fun clashing(open: String, close: String): Boolean = !(
        (open == "(" && close == ")") || (open == "[" && close == "]") || (open == "{" && close == "}")
        )

    companion object {
        private val CONTINUATION_KEYWORDS = setOf("else", "catch", "finally")
        private val CONTROL_HEADER_KEYWORDS = setOf("else", "try", "do", "finally")
        private val CONTROL_PARENTHESES = setOf("if", "for", "while", "catch", "when")
        private val DECLARATION_HEADER_KEYWORDS = setOf("fun", "class", "interface", "object", "init")

        /** Kotlin hard keywords: never a name. Soft keywords the lexer marks as keywords may be names. */
        val HARD_KEYWORDS: Set<String> = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )

        /** Declaration modifiers; the lexer marks these as keywords though they are never references. */
        val MODIFIER_WORDS: Set<String> = setOf(
            "actual", "abstract", "annotation", "companion", "const", "crossinline", "data",
            "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override", "private",
            "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
            "value", "context", "dynamic",
        )

        private fun isCodeType(type: TokenType): Boolean =
            type != KotlinTokenTypes.STRING && type != KotlinTokenTypes.LINE_COMMENT && type != KotlinTokenTypes.BLOCK_COMMENT

        private fun rawContinuations(tokens: List<KtToken>): Set<Int> {
            val starts = HashSet<Int>()
            var raw = false
            for (token in tokens) {
                if (!token.isStringBody) continue
                if (!raw) {
                    if (token.text.startsWith("\"\"\"")) {
                        raw = !token.text.substring(3).contains("\"\"\"")
                    }
                } else {
                    starts.add(token.start)
                    if (token.text.contains("\"\"\"")) raw = false
                }
            }
            return starts
        }

        private fun buildLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts.add(0)
            var index = 0
            while (index < text.length) {
                if (text[index] == '\n') starts.add(index + 1)
                index++
            }
            return starts.toIntArray()
        }
    }
}

private class CachedCode(val text: String, val code: KotlinCode)

@Volatile
private var lastCode: CachedCode? = null

/** The model for [text], reused while callers pass the same `String` instance. */
internal fun kotlinCodeFor(text: String): KotlinCode {
    val cached = lastCode
    if (cached != null && cached.text === text) return cached.code
    val code = KotlinCode(text)
    lastCode = CachedCode(text, code)
    return code
}

/** A code token that can be a name, including soft keywords the lexer marks as keywords. */
internal fun isNameToken(token: KtToken): Boolean =
    token.type == KotlinTokenTypes.IDENTIFIER ||
        (token.type == KotlinTokenTypes.KEYWORD && token.text !in KotlinCode.HARD_KEYWORDS)

internal fun computeBlocks(code: KotlinCode): List<KtBlock> {
    val blocks = ArrayList<KtBlock>()
    val stack = ArrayList<KtBlock>()
    for (token in code.code) {
        when (token.text) {
            "{" -> {
                val closeIndex = code.matchingIndex(token.index)
                val close = if (closeIndex >= 0) code.code[closeIndex] else null
                val parent = stack.lastOrNull()
                val info = classifyBrace(code, token.index, parent)
                val block = KtBlock(info.kind, token, close, parent, info.keyword, info.loopBody)
                blocks.add(block)
                stack.add(block)
            }
            "}" -> {
                while (stack.isNotEmpty() && stack.last().close?.index != token.index) stack.removeAt(stack.size - 1)
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
        }
    }
    return blocks.sortedBy { it.open.start }
}

private class BraceInfo(val kind: BlockKind, val keyword: String?, val loopBody: Boolean)

private fun classifyBrace(code: KotlinCode, openIndex: Int, parent: KtBlock?): BraceInfo {
    val previous = code.previousToken(code.code[openIndex]) ?: return BraceInfo(BlockKind.LAMBDA, null, false)
    // A string literal ends an expression, so a brace right after it starts a statement of its own.
    if (previous.isStringBody) return BraceInfo(BlockKind.LAMBDA, null, false)
    if (previous.text == "->") {
        val whenBranch = parent?.kind == BlockKind.WHEN_BODY
        return BraceInfo(if (whenBranch) BlockKind.CONTROL_BODY else BlockKind.LAMBDA, "when", false)
    }
    when (previous.text) {
        "else", "try", "finally", "do" -> return BraceInfo(BlockKind.CONTROL_BODY, previous.text, previous.text == "do")
        "init" -> return BraceInfo(BlockKind.INITIALIZER, "init", false)
        "when" -> return BraceInfo(BlockKind.WHEN_BODY, "when", false)
    }
    if (previous.text == ")") {
        val open = code.matching(previous.index)
        if (open != null && open.text == "(") {
            val head = code.code.getOrNull(open.index - 1)
            if (head != null) {
                when (head.text) {
                    "if" -> return BraceInfo(BlockKind.CONTROL_BODY, "if", false)
                    "for" -> return BraceInfo(BlockKind.CONTROL_BODY, "for", true)
                    "while" -> return BraceInfo(BlockKind.CONTROL_BODY, "while", true)
                    "catch" -> return BraceInfo(BlockKind.CONTROL_BODY, "catch", false)
                    "when" -> return BraceInfo(BlockKind.WHEN_BODY, "when", false)
                    "get", "set", "constructor" -> {
                        if (!isAfterDot(code, open.index - 1)) return BraceInfo(BlockKind.FUNCTION_BODY, head.text, false)
                    }
                    "fun" -> if (!isAfterDot(code, open.index - 1)) return BraceInfo(BlockKind.LAMBDA, "fun", false)
                }
            }
        }
    }
    return scanDeclarationBody(code, openIndex)
}

private fun isAfterDot(code: KotlinCode, index: Int): Boolean {
    val before = code.code.getOrNull(index - 1) ?: return false
    return before.type == KotlinTokenTypes.DOT ||
        (before.text == "?" && code.code.getOrNull(index - 2)?.type == KotlinTokenTypes.DOT)
}

private val HEADER_OPERATORS = setOf(":", "<", ">", "*", "&", "?", "@")

private val HEADER_KEYWORDS = setOf("where", "this", "super", "in", "out", "reified", "by", "dynamic", "typeof", "value")

private fun scanDeclarationBody(code: KotlinCode, openIndex: Int): BraceInfo {
    var index = openIndex - 1
    while (index >= 0) {
        val token = code.code[index]
        when {
            token.text == "}" || token.text == "{" || token.text == ";" || token.text == "=" ->
                return BraceInfo(BlockKind.LAMBDA, null, false)
            token.type == KotlinTokenTypes.DOT -> index--
            token.text == ")" || token.text == "]" -> {
                val partner = code.matchingIndex(index)
                if (partner < 0) return BraceInfo(BlockKind.LAMBDA, null, false)
                index = partner - 1
            }
            token.text == "(" || token.text == "[" -> return BraceInfo(BlockKind.LAMBDA, null, false)
            token.type == KotlinTokenTypes.KEYWORD -> {
                when {
                    token.text == "fun" -> return BraceInfo(BlockKind.FUNCTION_BODY, "fun", false)
                    token.text == "class" || token.text == "interface" || token.text == "object" ->
                        return BraceInfo(BlockKind.CLASS_BODY, token.text, false)
                    token.text == "constructor" -> {
                        if (isAfterDot(code, index)) return BraceInfo(BlockKind.LAMBDA, null, false)
                        return BraceInfo(BlockKind.FUNCTION_BODY, "constructor", false)
                    }
                    token.text == "get" || token.text == "set" -> {
                        if (isAfterDot(code, index) || !accessorHasParens(code, index)) {
                            return BraceInfo(BlockKind.LAMBDA, null, false)
                        }
                        return BraceInfo(BlockKind.FUNCTION_BODY, token.text, false)
                    }
                    token.text in HEADER_KEYWORDS -> index--
                    else -> return BraceInfo(BlockKind.LAMBDA, null, false)
                }
            }
            token.type == KotlinTokenTypes.OPERATION_SIGN && token.text in HEADER_OPERATORS -> index--
            token.type == KotlinTokenTypes.COMMA -> index--
            else -> index--
        }
    }
    return BraceInfo(BlockKind.LAMBDA, null, false)
}

private fun accessorHasParens(code: KotlinCode, keywordIndex: Int): Boolean {
    val next = code.code.getOrNull(keywordIndex + 1) ?: return false
    return next.text == "("
}

/** The offset after a lambda's parameter arrow; the parameter list is not a statement. */
private fun lambdaBodyStart(code: KotlinCode, block: KtBlock): Int {
    val close = block.close?.index ?: return block.start
    var index = block.open.index + 1
    while (index < close) {
        val token = code.code[index]
        if (token.text == "->") return token.end
        if (code.isOpener(token)) {
            val partner = code.matchingIndex(index)
            if (partner in (index + 1) until close) {
                index = partner + 1
                continue
            }
        }
        index++
    }
    return block.start
}

internal fun computeStatements(code: KotlinCode): List<KtStatement> {
    val result = ArrayList<KtStatement>()
    val syntax = code.tokens.filter { !it.isComment }
    for (block in code.blocks) {
        if (!block.isStatementBlock) continue
        val contentEnd = block.end
        val scanStart = if (block.kind == BlockKind.LAMBDA) lambdaBodyStart(code, block) else block.start
        var startOffset = -1
        var endOffset = -1
        var lastToken: KtToken? = null
        var index = syntax.indexOfFirst { it.start >= scanStart }
        if (index < 0) continue
        fun emit() {
            if (startOffset < 0 || endOffset <= startOffset) return
            val firstCode = code.code.indexOfFirst { it.start >= startOffset && it.end <= endOffset }
            val lastCode = code.code.indexOfLast { it.start >= startOffset && it.end <= endOffset }
            result.add(KtStatement(block, startOffset, endOffset, firstCode, lastCode))
        }
        while (index < syntax.size) {
            val token = syntax[index]
            if (token.start >= contentEnd) break
            if (token.text == ";") {
                emit()
                startOffset = -1
                endOffset = -1
                lastToken = null
                index++
                continue
            }
            val previous = lastToken
            if (previous != null && code.splitsAfter(previous, token)) {
                emit()
                startOffset = -1
                endOffset = -1
                lastToken = null
            }
            if (startOffset < 0) startOffset = token.start
            endOffset = maxOf(endOffset, token.end)
            lastToken = token
            if (code.isOpener(token)) {
                val close = code.matching(token.index)
                if (close != null && close.start < contentEnd) {
                    endOffset = maxOf(endOffset, close.end)
                    lastToken = close
                    index++
                    while (index < syntax.size && syntax[index].start < close.end) index++
                    continue
                }
            }
            index++
        }
        emit()
    }
    return result
}
