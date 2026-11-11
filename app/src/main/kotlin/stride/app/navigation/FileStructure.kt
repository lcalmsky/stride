package stride.app.navigation

import stride.search.DeclarationKind
import stride.search.DeclarationParser
import stride.search.NameMatcher

/** One row of the file structure popup. [depth] is the nesting level of the declaration. */
data class FileStructureEntry(val name: String, val kind: DeclarationKind, val line: Int, val depth: Int)

/** Declarations of one file, indented by the nesting the parser knows. */
object FileStructure {
    fun entries(fileName: String, text: String): List<FileStructureEntry> {
        val classDepth = HashMap<String, Int>()
        return DeclarationParser.parse(fileName, text).map { declaration ->
            val depth = if (declaration.container == null) 0 else (classDepth[declaration.container] ?: 0) + 1
            if (declaration.kind == DeclarationKind.CLASS) classDepth[declaration.name] = depth
            FileStructureEntry(declaration.name, declaration.kind, declaration.line, depth)
        }
    }

    /** Entries matching [query] by name, best match first; an empty query keeps the file order. */
    fun matching(entries: List<FileStructureEntry>, query: String): List<FileStructureEntry> {
        if (query.isEmpty()) return entries
        return entries
            .mapNotNull { entry -> NameMatcher.match(query, entry.name)?.let { entry to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
