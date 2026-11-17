package stride.lang.kotlin.refactor

import stride.lang.TokenType
import stride.lang.kotlin.KotlinTokenTypes
import stride.lang.kotlin.codeTokens

/** An offset range `[start, end)` in the prepared text. */
internal data class KtRange(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start

    val length: Int get() = end - start

    fun contains(offset: Int): Boolean = offset in start until end

    fun containsRange(other: KtRange): Boolean = other.start >= start && other.end <= end

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

    /** Code token indices that a reference scan must not treat as references. */
    internal val keywordTokens: MutableSet<Int> = HashSet()

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

    init {
        val raw = codeTokens(text).sortedBy { it.start }
        var index = 0
        tokens = raw.map { token ->
            val codeIndex = if (isCodeType(token.type)) index++ else -1
            KtToken(codeIndex, token.type, token.start, token.end, token.text)
        }
        code = tokens.filter { it.isCode }

        lineStarts = buildLineStarts(text)
        rawContinuationStarts = rawContinuations(tokens)
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

    fun codeToken(index: Int): KtToken? = code.getOrNull(index)

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

    /** True when no code token other than the statement itself sits on its lines, before or after it. */
    fun isAloneOnLine(statement: KtStatement): Boolean {
        if (!isBlank(lineStart(statement.start), statement.start)) return false
        val after = statement.end
        val lineEnd = lineEnd(after)
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
                    if (end < 0) return true
                    index = end + 2
                }
                else -> return false
            }
        }
        return true
    }

    fun statementText(statement: KtStatement): String = text.substring(statement.start, statement.end)

    /** True when the newline between the two code tokens ends a statement. */
    internal fun splitsAfter(previous: KtToken, next: KtToken): Boolean {
        if (previous.text == ";") return true
        if (!hasNewline(previous.end, next.start)) return false
        if (next.isStringBody && next.start in rawContinuationStarts) return false
        if (templateExpressionRanges.any { previous.end >= it.start && previous.end <= it.end && next.start <= it.end }) {
            return false
        }
        if (next.text == "{" && blockOpeningAt(next.index)?.kind != BlockKind.LAMBDA) return false
        if (cannotEndExpression(previous)) return false
        if (continuesExpression(next)) return false
        if (lineHoldsOnlyAnnotations(previous)) return false
        if (isControlHeaderEnd(previous)) return false
        return true
    }

    private fun blockOpeningAt(tokenIndex: Int): KtBlock? = blocks.firstOrNull { it.open.index == tokenIndex }

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
        KotlinTokenTypes.OPERATION_SIGN -> token.text != "++" && token.text != "--" && token.text != "!!"
        KotlinTokenTypes.KEYWORD -> token.text == "as" || token.text == "is" || token.text == "in"
        else -> false
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
    val previous = code.code.getOrNull(openIndex - 1) ?: return BraceInfo(BlockKind.LAMBDA, null, false)
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
