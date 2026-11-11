package stride.app.navigation

import stride.search.Declaration
import stride.search.DeclarationParser
import stride.search.ParsedDeclaration

/** Picks where Go to Declaration lands: the current file first, then the declaration index. */
object DeclarationNavigator {
    /** The declaration of [name] nearest before [caretLine], else the first one in the file. */
    fun localTarget(fileName: String, text: String, name: String, caretLine: Int): ParsedDeclaration? {
        val declarations = DeclarationParser.parse(fileName, text).filter { it.name == name }
        if (declarations.isEmpty()) return null
        return declarations.filter { it.line < caretLine }.maxByOrNull { it.line } ?: declarations.first()
    }

    /**
     * Index candidates ordered by closeness: the current package first, then the current module,
     * then the path and the line.
     */
    fun ranked(
        candidates: List<Declaration>,
        currentPackage: String?,
        currentModule: String?,
    ): List<Declaration> = candidates.sortedWith(
        compareByDescending<Declaration> { packageOfPath(it.relativePath) == currentPackage }
            .thenByDescending { moduleOfPath(it.relativePath) == currentModule }
            .thenBy { it.relativePath }
            .thenBy { it.line },
    )

    /** The package declared in [text], else the one its source-root path implies. */
    fun packageOf(text: String, relativePath: String): String? =
        PACKAGE.find(text)?.groupValues?.get(1) ?: packageOfPath(relativePath)

    fun packageOfPath(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        val marker = SOURCE_MARKERS.firstOrNull { normalized.contains(it) } ?: return null
        val directory = normalized.substringAfter(marker).substringBeforeLast('/', "")
        return if (directory.isEmpty()) null else directory.replace('/', '.')
    }

    fun moduleOfPath(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("src/")) return ""
        return if (normalized.contains("/src/")) {
            normalized.substringBefore("/src/")
        } else {
            normalized.substringBefore('/')
        }
    }

    private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)""")
    private val SOURCE_MARKERS = listOf("/kotlin/", "/java/")
}
