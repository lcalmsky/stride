package stride.app.hierarchy

import stride.app.navigation.offsetOfName
import stride.search.Declaration
import stride.search.TypeGraph
import java.nio.file.Path

/**
 * The tree Type Hierarchy shows: the class at the root, its supertypes upward and its subtypes
 * downward. Children read the project sources when the window expands them; a cycle in malformed
 * code ends in a `(recursive)` leaf instead of looping.
 */
internal object TypeHierarchyNodes {
    const val MAX_CHILDREN: Int = 200

    fun tree(graph: TypeGraph, root: Declaration, textOf: (Path) -> String?): HierarchyTree =
        HierarchyTree(
            title = "Type Hierarchy of ${root.name}",
            note = "Types outside project sources are not expanded",
            roots = listOf(classNode(graph, root, emptySet(), textOf, root = true)),
        )

    private fun classNode(
        graph: TypeGraph,
        type: Declaration,
        path: Set<String>,
        textOf: (Path) -> String?,
        root: Boolean = false,
        open: Boolean = false,
    ): HierarchyNode {
        val key = keyOf(type)
        if (key in path) {
            return HierarchyNode(
                text = "${type.name} (recursive)",
                detail = "${type.relativePath}:${type.line + 1}",
                location = locationOf(type, textOf),
                iconName = type.file.name,
            )
        }
        val next = path + key
        return HierarchyNode(
            text = type.name,
            detail = "${type.relativePath}:${type.line + 1}",
            location = locationOf(type, textOf),
            iconName = type.file.name,
            accent = root,
            expanded = root || open,
            children = { children(graph, type, next, textOf) },
        )
    }

    /** The `Supertypes` and `Subtypes` bundles under [type]; both open when their parent is shown. */
    fun children(graph: TypeGraph, type: Declaration, path: Set<String>, textOf: (Path) -> String?): List<HierarchyNode> =
        listOf(
            HierarchyNode(
                text = "Supertypes",
                expanded = true,
                children = {
                    val nodes = ArrayList<HierarchyNode>()
                    for (ref in graph.supertypes(type)) {
                        if (ref.resolved.isEmpty()) {
                            nodes += HierarchyNode(ref.written, detail = "outside project sources")
                        } else {
                            ref.resolved.distinct().forEach {
                                nodes += classNode(graph, it, path, textOf, open = true)
                            }
                        }
                    }
                    limited(nodes)
                },
            ),
            HierarchyNode(
                text = "Subtypes",
                expanded = true,
                children = {
                    limited(graph.directSubtypes(type).distinct().map { classNode(graph, it, path, textOf) })
                },
            ),
        )

    private fun limited(nodes: List<HierarchyNode>): List<HierarchyNode> =
        if (nodes.size <= MAX_CHILDREN) {
            nodes
        } else {
            nodes.take(MAX_CHILDREN) + HierarchyNode("… ${nodes.size - MAX_CHILDREN} more")
        }

    private fun keyOf(type: Declaration): String =
        "${type.file.path.toAbsolutePath().normalize()}|${type.line}|${type.container}|${type.name}"
}

/** Where a declaration's name starts in its file, or [offset 0][HierarchyLocation] when unreadable. */
internal fun locationOf(declaration: Declaration, textOf: (Path) -> String?): HierarchyLocation {
    val path = declaration.file.path.toAbsolutePath().normalize()
    val text = textOf(path) ?: return HierarchyLocation(path, 0)
    return HierarchyLocation(path, offsetOfName(text, declaration.line, declaration.column, declaration.name))
}

