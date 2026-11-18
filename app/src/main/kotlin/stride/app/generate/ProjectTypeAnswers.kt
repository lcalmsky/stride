package stride.app.generate

import stride.app.AppState
import stride.app.navigation.HierarchyText
import stride.app.navigation.hierarchyGraphOf
import stride.app.navigation.lineOf
import stride.app.navigation.offsetOfName
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ProjectTypes
import stride.lang.TypeSource
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.DeclarationKind
import stride.search.DeclarationParser
import stride.search.NameContext
import stride.search.TypeGraph
import stride.search.classesAt
import java.nio.file.Path

/** The generators' view of project sources; `null` while the declaration index is not ready. */
internal fun projectTypes(state: AppState): ProjectTypes? {
    val session = state.session ?: return null
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) return null
    val graphState = hierarchyGraphOf(state, session, index)
    return ProjectTypeAnswers(graphState.declarations, graphState.graph) { path -> HierarchyText.read(state, path) }
}

/**
 * [ProjectTypes] over the hierarchy graph, whose declarations and texts include open editors'
 * unsaved text. [declarations] is the same list the graph was built from.
 */
internal class ProjectTypeAnswers(
    private val declarations: List<Declaration>,
    private val graph: TypeGraph,
    private val textOf: (Path) -> String?,
) : ProjectTypes {
    override fun resolve(written: String, path: String, offset: Int): List<TypeSource> {
        val file = runCatching { Path.of(path) }.getOrNull() ?: return emptyList()
        val text = textOf(file) ?: return emptyList()
        val fileName = file.fileName?.toString() ?: return emptyList()
        val position = offset.coerceIn(0, text.length)
        val outline = DeclarationParser.outline(fileName, text)
        val context = NameContext(
            file = VFile(file),
            packageName = DeclarationParser.packageOf(text),
            imports = DeclarationParser.imports(fileName, text),
            enclosingClasses = outline.classesAt(position).map { it.name },
            line = lineOf(text, position),
        )
        val result = ArrayList<TypeSource>()
        for (declaration in graph.resolveType(written, context)) {
            if (declaration.kind != DeclarationKind.CLASS) continue
            val declarationPath = declaration.file.path.toAbsolutePath().normalize()
            val declarationText = textOf(declarationPath) ?: continue
            result += TypeSource(
                qualifiedName(declaration),
                declarationPath.toString(),
                declarationText,
                offsetOfName(declarationText, declaration.line, declaration.column, declaration.name),
            )
        }
        return result
    }

    override fun hasTopLevelFunction(name: String): Boolean =
        declarations.any {
            it.kind == DeclarationKind.SYMBOL && it.container == null && it.name == name && !it.isPrivate
        }

    /** The package, the enclosing classes nearest to [declaration] and its own name, dotted. */
    private fun qualifiedName(declaration: Declaration): String {
        val names = ArrayDeque<String>()
        names.addFirst(declaration.name)
        var current = declaration
        var depth = 0
        while (depth < MAX_NESTING) {
            val container = current.container ?: break
            val owner = declarations
                .filter {
                    it.kind == DeclarationKind.CLASS && it.name == container &&
                        sameFile(it, current) && it.line <= current.line
                }
                .maxByOrNull { it.line } ?: break
            names.addFirst(owner.name)
            current = owner
            depth++
        }
        val suffix = names.joinToString(".")
        val packageName = declaration.packageName
        return if (packageName.isNullOrEmpty()) suffix else "$packageName.$suffix"
    }

    private fun sameFile(left: Declaration, right: Declaration): Boolean =
        left.file.path.toAbsolutePath().normalize() == right.file.path.toAbsolutePath().normalize()

    private companion object {
        const val MAX_NESTING = 32
    }
}
