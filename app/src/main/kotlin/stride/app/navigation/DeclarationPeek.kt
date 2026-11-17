package stride.app.navigation

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.CodeScan
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationIndex

/** Shown by the peek actions when the caret is not on a name. */
const val NO_DECLARATION_MESSAGE: String = "No declaration found"

/** One declaration a peek popup shows, with the source pieces extracted around its line. */
data class PeekCandidate(
    val declaration: Declaration,
    val signature: String,
    val doc: String?,
    val definition: String,
    val definitionTruncatedLines: Int,
)

/** The candidates of one peek popup, newest lookup wins. */
data class PeekRequest(val candidates: List<PeekCandidate>)

/** The non-modal Parameter Info panel: the call's `(` and the signatures it can resolve to. */
data class ParameterInfoRequest(
    val editor: TextFileEditor,
    val openOffset: Int,
    val signatures: List<String>,
)

/** A lookup either finds candidates or explains why it found none. */
sealed interface PeekLookup {
    data class Found(val name: String, val candidates: List<PeekCandidate>) : PeekLookup

    data class Unavailable(val message: String) : PeekLookup
}

/** Declaration lookup shared by Quick Documentation, Quick Definition and Parameter Info. */
object DeclarationPeek {
    const val MAX_CANDIDATES: Int = 10
    const val MAX_QUICK_DOC: Int = 3
    const val MAX_PARAMETER_SIGNATURES: Int = 5

    /** Candidates for the identifier at [offset]. */
    fun lookup(state: AppState, editor: TextFileEditor, offset: Int, limit: Int): PeekLookup {
        val text = editor.value.text
        val name = identifierAt(text, editor.file.name, offset) ?: return PeekLookup.Unavailable(NO_DECLARATION_MESSAGE)
        return lookup(state, editor, name, BookmarkManager.lineIndex(text, offset), limit)
    }

    /** Candidates for [name] at [caretLine]: the current file first, then the declaration index. */
    fun lookup(state: AppState, editor: TextFileEditor, name: String, caretLine: Int, limit: Int): PeekLookup {
        val session = state.session ?: return PeekLookup.Unavailable(NO_DECLARATION_MESSAGE)
        val text = editor.value.text
        val path = editor.file.path.toAbsolutePath().normalize()
        val relative = relativePath(session, editor.file)

        val localTarget = DeclarationNavigator.localTarget(editor.parsedDeclarations, name, caretLine)
        val localMatches = if (localTarget != null) {
            listOf(localTarget)
        } else {
            editor.parsedDeclarations.filter { it.name == name }
        }
        val local = localMatches.map {
            Declaration(it.name, it.kind, editor.file, relative, it.line, it.container, null, it.isPrivate)
        }

        val index = session.project.service<DeclarationIndex>()
        if (!index.isReady) {
            val targets = local.mapNotNull { candidate(session, it) }
            return if (targets.isEmpty()) {
                PeekLookup.Unavailable(INDEXING_MESSAGE)
            } else {
                PeekLookup.Found(name, targets)
            }
        }

        val indexed = index.snapshot().filter {
            it.name == name && !(it.isPrivate && it.file.path.toAbsolutePath().normalize() != path)
        }
        val ranked = DeclarationNavigator.ranked(
            indexed,
            DeclarationNavigator.packageOf(text, relative),
            DeclarationNavigator.moduleOfPath(relative),
        )
        val merged = (local + ranked)
            .distinctBy { it.file.path.toAbsolutePath().normalize() to it.line }
            .take(limit)
            .mapNotNull { candidate(session, it) }
        return if (merged.isEmpty()) PeekLookup.Unavailable(NO_DECLARATION_MESSAGE) else PeekLookup.Found(name, merged)
    }

    private fun candidate(session: ProjectSession, declaration: Declaration): PeekCandidate? {
        val text = sourceText(session, declaration.file) ?: return null
        val lineCount = text.count { it == '\n' } + 1
        if (declaration.line !in 0 until lineCount) return null
        val source = DeclarationSource.extract(text, declaration.line)
        return PeekCandidate(
            declaration = declaration,
            signature = source.signature,
            doc = source.doc,
            definition = source.definition,
            definitionTruncatedLines = source.definitionTruncatedLines,
        )
    }

    /** The open editor's text when the file is already open, else the file, or `null` on a read error. */
    private fun sourceText(session: ProjectSession, file: VFile): String? {
        val key = file.path.toAbsolutePath().normalize()
        val open = session.editors.openFiles
            .firstOrNull { it.file.path.toAbsolutePath().normalize() == key } as? TextFileEditor
        if (open != null) return open.value.text
        return runCatching { file.readText() }.getOrNull()
    }
}

/** Opens Quick Documentation for the identifier at the caret; a second request closes it. */
fun openQuickDoc(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    if (state.quickDoc != null) {
        state.quickDoc = null
        return true
    }
    val lookup = DeclarationPeek.lookup(state, editor, editor.value.selection.start, DeclarationPeek.MAX_CANDIDATES)
    return when (lookup) {
        is PeekLookup.Unavailable -> {
            state.statusMessage = lookup.message
            true
        }
        is PeekLookup.Found -> {
            state.quickDoc = PeekRequest(lookup.candidates.take(DeclarationPeek.MAX_QUICK_DOC))
            state.focus = KeyContext.CONTEXT_POPUP
            state.statusMessage = null
            true
        }
    }
}

/** Opens Quick Definition for the identifier at the caret. */
fun openQuickDefinition(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val lookup = DeclarationPeek.lookup(state, editor, editor.value.selection.start, DeclarationPeek.MAX_CANDIDATES)
    return when (lookup) {
        is PeekLookup.Unavailable -> {
            state.statusMessage = lookup.message
            true
        }
        is PeekLookup.Found -> {
            state.quickDefinition = PeekRequest(lookup.candidates)
            state.focus = KeyContext.CONTEXT_POPUP
            state.statusMessage = null
            true
        }
    }
}

/** Opens the non-modal Parameter Info panel for the call whose `(` is innermost at the caret. */
fun openParameterInfo(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val text = editor.value.text
    val caret = editor.value.selection.start
    val open = CallText.openParen(text, caret)
    val name = open?.let { CallText.calleeName(text, it) }
    if (open == null || name == null) {
        state.statusMessage = "No call at caret"
        return true
    }
    val lookup = DeclarationPeek.lookup(
        state,
        editor,
        name,
        BookmarkManager.lineIndex(text, caret),
        DeclarationPeek.MAX_CANDIDATES,
    )
    val signatures = when (lookup) {
        is PeekLookup.Unavailable -> {
            state.statusMessage = if (lookup.message == NO_DECLARATION_MESSAGE) {
                "No parameter info for $name"
            } else {
                lookup.message
            }
            return true
        }
        is PeekLookup.Found -> lookup.candidates
            .filter { CallText.parameters(it.signature) != null }
            .map { it.signature }
            .distinct()
            .take(DeclarationPeek.MAX_PARAMETER_SIGNATURES)
    }
    if (signatures.isEmpty()) {
        state.statusMessage = "No parameter info for $name"
        return true
    }
    state.parameterInfo = ParameterInfoRequest(editor, open, signatures)
    state.statusMessage = null
    return true
}

/** The text pieces of one declaration: signature, documentation and definition. */
internal data class DeclarationSourceText(
    val signature: String,
    val doc: String?,
    val definition: String,
    val definitionTruncatedLines: Int,
)

/**
 * Extracts a declaration's signature, documentation and definition from its start line without a
 * parser: strings and comments are judged through [CodeScan], so their brackets do not count.
 */
internal object DeclarationSource {
    const val MAX_SIGNATURE_LINES: Int = 5
    const val MAX_DEFINITION_LINES: Int = 40

    fun extract(text: String, line: Int): DeclarationSourceText {
        val starts = lineStarts(text)
        val scan = CodeScan(text)
        val signature = scanSignature(text, line, starts, scan)
        val doc = scanDoc(text, line, starts)
        val start = doc?.startLine ?: annotationStart(text, line, starts) ?: line
        val bodyEnd = signature.braceOffset
            ?.let { scan.match(it) }
            ?.let { lineIndex(text, it) }
            ?: signature.endLine
        val end = maxOf(bodyEnd, start)
        val total = end - start + 1
        val limited = total.coerceAtMost(MAX_DEFINITION_LINES)
        val last = start + limited - 1
        val lines = (start..last).map { lineText(text, starts, it) }
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { leadingWhitespace(it) } ?: 0
        val stripped = lines.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it }
        return DeclarationSourceText(
            signature = signature.text,
            doc = doc?.content,
            definition = stripped.trimEnd(),
            definitionTruncatedLines = total - limited,
        )
    }

    fun signature(text: String, line: Int): String = scanSignature(text, line, lineStarts(text), CodeScan(text)).text

    fun doc(text: String, line: Int): String? = scanDoc(text, line, lineStarts(text))?.content

    private data class SignatureScan(val text: String, val endLine: Int, val braceOffset: Int?)

    private data class DocScan(val startLine: Int, val content: String)

    private fun scanSignature(text: String, line: Int, starts: IntArray, scan: CodeScan): SignatureScan {
        if (line !in starts.indices) return SignatureScan("", line, null)
        val pieces = ArrayList<String>()
        var depth = 0
        var endLine = line
        var braceOffset: Int? = null
        val lastLine = minOf(line + MAX_SIGNATURE_LINES - 1, starts.size - 1)
        for (current in line..lastLine) {
            val from = starts[current]
            val to = lineEnd(text, starts, current)
            var cut = -1
            var index = from
            while (index < to) {
                val character = text[index]
                if (scan.isCode(index)) {
                    when {
                        character == '(' || character == '[' -> depth++
                        character == ')' || character == ']' -> if (depth > 0) depth--
                        character == '{' && depth == 0 -> {
                            cut = index
                            braceOffset = index
                        }
                        character == '=' && depth == 0 && isAssignment(text, index) -> cut = index
                    }
                }
                if (cut >= 0) break
                index++
            }
            pieces += text.substring(from, if (cut >= 0) cut else to).trim()
            endLine = current
            if (cut >= 0 || depth == 0) break
        }
        return SignatureScan(pieces.filter { it.isNotEmpty() }.joinToString(" ").trim(), endLine, braceOffset)
    }

    private fun isAssignment(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        return (before == null || before !in ASSIGNMENT_BEFORE) && text.getOrNull(index + 1) != '='
    }

    private fun scanDoc(text: String, line: Int, starts: IntArray): DocScan? {
        if (line < 1) return null
        var above = line - 1
        while (above >= 0 && lineText(text, starts, above).trimStart().startsWith("@")) above--
        if (above < 0) return null
        val candidate = lineText(text, starts, above)
        if (candidate.trimEnd().endsWith("*/")) {
            var start = above
            while (start >= 0) {
                val current = lineText(text, starts, start)
                if (current.contains("/**")) break
                if (current.isBlank()) return null
                start--
            }
            if (start < 0) return null
            val block = (start..above).joinToString("\n") { lineText(text, starts, it) }
            val begin = block.indexOf("/**") + 3
            val end = block.lastIndexOf("*/")
            if (end < begin) return null
            val content = block.substring(begin, end)
                .lines()
                .joinToString("\n") { stripDocLine(it) }
                .trim()
            return if (content.isEmpty()) null else DocScan(start, content)
        }
        if (!candidate.trimStart().startsWith("//")) return null
        var start = above
        while (start - 1 >= 0 && lineText(text, starts, start - 1).trimStart().startsWith("//")) start--
        val content = (start..above)
            .joinToString("\n") {
                lineText(text, starts, it).trimStart().removePrefix("//").removePrefix(" ").trimEnd()
            }
            .trim()
        return if (content.isEmpty()) null else DocScan(start, content)
    }

    private fun stripDocLine(line: String): String {
        val trimmed = line.trimStart()
        val withoutStar = if (trimmed.startsWith("*")) trimmed.drop(1).removePrefix(" ") else trimmed
        return withoutStar.trimEnd()
    }

    private fun annotationStart(text: String, line: Int, starts: IntArray): Int? {
        var first: Int? = null
        var index = line - 1
        while (index >= 0 && lineText(text, starts, index).trimStart().startsWith("@")) {
            first = index
            index--
        }
        return first
    }

    private val ASSIGNMENT_BEFORE = setOf('=', '!', '<', '>', '+', '-', '*', '/', '%', '&', '|')
}

/** The call around the caret, used by Parameter Info. */
object CallText {
    /** The offset of the innermost unclosed `(` before [caret], or `null` when there is none. */
    fun openParen(text: String, caret: Int): Int? {
        val scan = CodeScan(text)
        var depth = 0
        var index = caret.coerceIn(0, text.length) - 1
        while (index >= 0) {
            if (scan.isCode(index)) {
                when (text[index]) {
                    ')' -> depth++
                    '(' -> if (depth == 0) return index else depth--
                }
            }
            index--
        }
        return null
    }

    /** The identifier in front of [openParen], skipping generic arguments such as `foo<T>(`. */
    fun calleeName(text: String, openParen: Int): String? {
        var end = openParen - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        if (end >= 0 && text[end] == '>') {
            var depth = 0
            var index = end
            while (index >= 0) {
                when (text[index]) {
                    '>' -> depth++
                    '<' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                index--
            }
            if (index < 0) return null
            end = index - 1
            while (end >= 0 && text[end].isWhitespace()) end--
        }
        if (end < 0) return null
        var start = end
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_' || text[start] == '$')) start--
        return text.substring(start + 1, end + 1).ifEmpty { null }
    }

    /** The parameters of [signature]'s first balanced `(…)`, or `null` when it has none. */
    fun parameters(signature: String): List<String>? {
        val open = signature.indexOf('(')
        if (open < 0) return null
        val scan = CodeScan(signature)
        var close = -1
        var depth = 0
        var index = open
        while (index < signature.length) {
            if (scan.isCode(index)) {
                when (signature[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = index
                            break
                        }
                    }
                }
            }
            index++
        }
        if (close < 0) return null
        val inner = signature.substring(open + 1, close)
        if (inner.isBlank()) return emptyList()
        val innerScan = CodeScan(inner)
        val parts = ArrayList<String>()
        var start = 0
        var nested = 0
        for (position in inner.indices) {
            if (!innerScan.isCode(position)) continue
            when (inner[position]) {
                '(', '[', '<' -> nested++
                ')', ']', '>' -> if (nested > 0) nested--
                ',' -> if (nested == 0) {
                    parts += inner.substring(start, position).trim()
                    start = position + 1
                }
            }
        }
        parts += inner.substring(start).trim()
        while (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        return parts
    }

    /**
     * The zero-based parameter index at [caret], or `null` when the caret is no longer inside the
     * call's parentheses (also covering a `(` that has since moved).
     */
    fun activeIndex(text: String, openOffset: Int, caret: Int): Int? {
        if (openOffset !in text.indices || text[openOffset] != '(') return null
        if (caret < openOffset + 1) return null
        val scan = CodeScan(text)
        val close = scan.match(openOffset)
        if (close != null && caret > close) return null
        var depth = 0
        var commas = 0
        for (index in openOffset + 1 until caret.coerceAtMost(text.length)) {
            if (!scan.isCode(index)) continue
            when (text[index]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) commas++
            }
        }
        return commas
    }
}

private fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    for (index in text.indices) if (text[index] == '\n') starts.add(index + 1)
    return starts.toIntArray()
}

private fun lineEnd(text: String, starts: IntArray, line: Int): Int =
    if (line + 1 < starts.size) starts[line + 1] - 1 else text.length

private fun lineText(text: String, starts: IntArray, line: Int): String =
    if (line in starts.indices) text.substring(starts[line], lineEnd(text, starts, line)).trimEnd('\r') else ""

private fun lineIndex(text: String, offset: Int): Int {
    var line = 0
    for (index in 0 until offset.coerceIn(0, text.length)) if (text[index] == '\n') line++
    return line
}

private fun leadingWhitespace(line: String): Int {
    var count = 0
    while (count < line.length && (line[count] == ' ' || line[count] == '\t')) count++
    return count
}
