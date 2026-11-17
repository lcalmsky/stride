package stride.lang.kotlin

/**
 * One `import` line of a Kotlin file; [start] and [end] span the whole line including its
 * newline when there is one.
 */
internal data class KotlinImport(
    val start: Int,
    val end: Int,
    val path: String,
    val alias: String?,
    val boundName: String,
    val star: Boolean,
)

/** The package statement and the import block of a Kotlin file, both in file order. */
internal data class KotlinImportList(
    val packageName: String?,
    val packageLineStart: Int?,
    val packageLineEnd: Int?,
    val imports: List<KotlinImport>,
)

private val PACKAGE = Regex("""package\s+([^\s;]+)\s*;?""")

private val IMPORT = Regex("""import\s+([^\s;]+)(?:\s+as\s+(`[^`]+`|[A-Za-z_]\w*))?\s*;?""")

private val FILE_ANNOTATION = Regex("""^@file:""")

private class CommentState {
    var depth = 0
}

internal fun parseImports(text: String): KotlinImportList {
    var packageName: String? = null
    var packageLineStart: Int? = null
    var packageLineEnd: Int? = null
    val imports = ArrayList<KotlinImport>()
    val comments = CommentState()
    var offset = 0
    while (offset <= text.length) {
        val newline = text.indexOf('\n', offset)
        val lineEnd = if (newline < 0) text.length else newline
        val fullEnd = if (newline < 0) text.length else newline + 1
        val line = text.substring(offset, lineEnd).removeSuffix("\r")
        val code = stripComments(line, comments).trim()
        if (code.isNotEmpty() && !FILE_ANNOTATION.containsMatchIn(code)) {
            val packageMatch = if (packageName == null && imports.isEmpty()) PACKAGE.matchEntire(code) else null
            if (packageMatch != null) {
                packageName = unquotePath(packageMatch.groupValues[1])
                packageLineStart = offset
                packageLineEnd = offset + line.length
            } else {
                val importMatch = IMPORT.matchEntire(code)
                if (importMatch == null) break
                val path = importMatch.groupValues[1]
                val alias = importMatch.groupValues[2].ifEmpty { null }?.let(::unquoteIdentifier)
                imports.add(
                    KotlinImport(
                        start = offset,
                        end = fullEnd,
                        path = path,
                        alias = alias,
                        boundName = alias ?: unquoteIdentifier(path.substringAfterLast('.')),
                        star = path.endsWith(".*") || path == "*",
                    ),
                )
            }
        }
        if (newline < 0) break
        offset = fullEnd
    }
    return KotlinImportList(packageName, packageLineStart, packageLineEnd, imports)
}

internal fun unquoteIdentifier(name: String): String =
    if (name.length >= 2 && name.startsWith('`') && name.endsWith('`')) name.substring(1, name.length - 1) else name

/** Names used by code, outside the package line, the import lines and comments and strings. */
internal fun usedIdentifiersIn(text: String, parsed: KotlinImportList): Set<String> {
    val used = HashSet<String>()
    for (token in codeTokens(text)) {
        val identifier = when (token.type) {
            KotlinTokenTypes.IDENTIFIER -> true
            KotlinTokenTypes.KEYWORD -> token.text !in HARD_KEYWORDS
            else -> false
        }
        if (!identifier) continue
        if (isOnPackageOrImportLine(token.start, parsed)) continue
        used.add(unquoteIdentifier(token.text))
    }
    return used
}

/** The imports the file never uses, by the one rule the quick fix, the optimizer and the provider share. */
internal fun unusedImports(text: String, parsed: KotlinImportList): List<KotlinImport> {
    val used = usedIdentifiersIn(text, parsed)
    return parsed.imports.filter { !it.star && it.boundName !in used && it.boundName !in ALWAYS_USED }
}

internal fun isOnPackageOrImportLine(offset: Int, parsed: KotlinImportList): Boolean {
    val packageStart = parsed.packageLineStart
    val packageEnd = parsed.packageLineEnd
    if (packageStart != null && packageEnd != null && offset in packageStart..packageEnd) return true
    return parsed.imports.any { offset >= it.start && offset < it.end }
}

/** The offset just past the newline following [offset], so a deleted line does not leave a double blank line. */
internal fun collapseBlankLine(text: String, offset: Int): Int = when {
    text.startsWith("\r\n", offset) -> offset + 2
    text.startsWith("\n", offset) -> offset + 1
    else -> offset
}

/** Names whose imports never count as unused: operator and delegation conventions resolved by the compiler. */
internal val ALWAYS_USED: Set<String> = setOf(
    "getValue", "setValue", "provideDelegate", "get", "set", "invoke", "contains", "iterator",
    "next", "hasNext", "compareTo", "equals", "plus", "minus", "times", "div", "rem", "rangeTo",
    "rangeUntil", "unaryPlus", "unaryMinus", "not", "inc", "dec", "plusAssign", "minusAssign",
    "timesAssign", "divAssign", "remAssign",
    "component1", "component2", "component3", "component4", "component5", "component6",
    "component7", "component8", "component9", "assign",
)

/** Hard keywords: a `KEYWORD` token in this set never counts as use. */
internal val HARD_KEYWORDS: Set<String> = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
)

private fun unquotePath(path: String): String = path.split('.').joinToString(".") { unquoteIdentifier(it) }

private fun stripComments(line: String, state: CommentState): String {
    val out = StringBuilder(line.length)
    var index = 0
    while (index < line.length) {
        val c = line[index]
        if (state.depth > 0) {
            when {
                c == '/' && line.getOrNull(index + 1) == '*' -> {
                    state.depth++
                    out.append("  ")
                    index += 2
                }
                c == '*' && line.getOrNull(index + 1) == '/' -> {
                    state.depth--
                    out.append("  ")
                    index += 2
                }
                else -> {
                    out.append(' ')
                    index++
                }
            }
        } else {
            when {
                c == '/' && line.getOrNull(index + 1) == '/' -> {
                    out.append(" ".repeat(line.length - index))
                    index = line.length
                }
                c == '/' && line.getOrNull(index + 1) == '*' -> {
                    state.depth = 1
                    out.append("  ")
                    index += 2
                }
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
    }
    return out.toString()
}
