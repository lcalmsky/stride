package stride.lang.kotlin

import stride.lang.ImportOptimizer
import stride.lang.TextEdit

/** Drops unused and duplicate imports, then sorts the block into the default Kotlin layout. */
class KotlinImportOptimizer : ImportOptimizer {
    override fun optimize(text: String): List<TextEdit> {
        val parsed = parseImports(text)
        val imports = parsed.imports
        if (imports.isEmpty()) return emptyList()
        val unused = unusedImports(text, parsed).toHashSet()
        val kept = ArrayList<KotlinImport>()
        val seen = HashSet<String>()
        for (import in imports) {
            if (import in unused) continue
            if (!seen.add(import.path + " as " + import.alias)) continue
            kept.add(import)
        }
        val first = imports.first()
        val last = imports.last()
        // A comment inside the block keeps its position relative to its import, so only whole
        // lines are deleted and the remaining order is left alone. The comment also keeps the
        // blank line after the block, so nothing is collapsed here.
        if (hasNonImportLines(text, imports)) {
            return imports.filter { it !in kept }.map { import -> TextEdit(import.start, import.end, "") }
        }
        val sorted = kept.sortedWith(IMPORT_ORDER)
        if (sorted.isEmpty()) {
            val end = collapseBlankLine(text, last.end)
            if (text.substring(first.start, end).isEmpty()) return emptyList()
            return listOf(TextEdit(first.start, end, ""))
        }
        val lineBreak = when {
            text.startsWith("\r\n", first.end - 2) -> "\r\n"
            else -> "\n"
        }
        val tail = if (text.getOrNull(last.end - 1) == '\n') lineBreak else ""
        val replacement = sorted.joinToString(separator = lineBreak, postfix = tail) { statement(text, it) }
        if (text.substring(first.start, last.end) == replacement) return emptyList()
        return listOf(TextEdit(first.start, last.end, replacement))
    }

    private fun hasNonImportLines(text: String, imports: List<KotlinImport>): Boolean {
        val importStarts = imports.map { it.start }.toHashSet()
        val end = imports.last().end
        var offset = imports.first().start
        while (offset < end) {
            val newline = text.indexOf('\n', offset)
            val lineEnd = if (newline < 0) end else minOf(newline, end)
            val line = text.substring(offset, lineEnd)
            if (line.isNotBlank() && offset !in importStarts) return true
            offset = if (newline < 0) end else newline + 1
        }
        return false
    }

    private fun statement(text: String, import: KotlinImport): String =
        text.substring(import.start, import.end).trimEnd('\n', '\r')

    private companion object {
        /** The layout of the Kotlin style template: everything else, `java`, `javax`, `kotlin`, aliases. */
        val IMPORT_ORDER: Comparator<KotlinImport> = compareBy({ groupOf(it) }, { it.path })

        fun groupOf(import: KotlinImport): Int = when {
            import.alias != null -> 4
            import.path.startsWith("java.") -> 1
            import.path.startsWith("javax.") -> 2
            import.path.startsWith("kotlin.") -> 3
            else -> 0
        }
    }
}
