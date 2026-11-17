package stride.search

import stride.core.vfs.VFile

enum class DeclarationKind { CLASS, SYMBOL }

/**
 * A declaration found by [DeclarationIndex]; [line] and [column] are 0-based and point at the name.
 * [supertypes], [isInterface] and [isOverride] mean the same as on [ParsedDeclaration].
 */
data class Declaration(
    val name: String,
    val kind: DeclarationKind,
    val file: VFile,
    val relativePath: String,
    val line: Int,
    val container: String? = null,
    val packageName: String? = null,
    val isPrivate: Boolean = false,
    val column: Int = 0,
    val supertypes: List<String> = emptyList(),
    val isInterface: Boolean = false,
    val isOverride: Boolean = false,
)

/**
 * One import of a source file: `import a.b.C` is `SourceImport("a.b.C")`, `import a.b.*` is
 * `SourceImport("a.b", isStar = true)` and `import a.b.C as D` is `SourceImport("a.b.C", alias = "D")`.
 * Java `import static` lines are not listed.
 */
data class SourceImport(val qualifiedName: String, val alias: String? = null, val isStar: Boolean = false) {
    /** The simple name the import brings into scope; `null` for a star import. */
    val visibleName: String? get() = if (isStar) null else alias ?: qualifiedName.substringAfterLast('.')
}
