package stride.lang.kotlin

/** One `import` line of a Kotlin file; [start] and [end] span the whole line including its `\n`. */
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
        if (code.isNotEmpty()) {
            val packageMatch = if (packageName == null && imports.isEmpty()) PACKAGE.matchEntire(code) else null
            if (packageMatch != null) {
                packageName = unquotePath(packageMatch.groupValues[1])
                packageLineStart = offset
                packageLineEnd = offset + line.length
            } else {
                IMPORT.matchEntire(code)?.let { match ->
                    val path = match.groupValues[1]
                    val alias = match.groupValues[2].ifEmpty { null }?.let(::unquoteIdentifier)
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
        }
        if (newline < 0) break
        offset = fullEnd
    }
    return KotlinImportList(packageName, packageLineStart, packageLineEnd, imports)
}

internal fun unquoteIdentifier(name: String): String =
    if (name.length >= 2 && name.startsWith('`') && name.endsWith('`')) name.substring(1, name.length - 1) else name

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
