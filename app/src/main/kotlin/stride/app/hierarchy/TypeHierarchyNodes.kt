package stride.app.hierarchy

import stride.app.navigation.offsetOfName
import stride.search.Declaration
import stride.search.TypeGraph
import java.nio.file.Path

/**
 * The tree Type Hierarchy shows: each root class, its supertypes upward and its subtypes
 * downward. One root opens itself; several roots (one per class the name at the caret resolves
 * to) start collapsed so their paths say where each came from. Children read the project sources
 * when the window expands them; a cycle in malformed code ends in a `(recursive)` leaf instead of
 * looping.
 */
internal object TypeHierarchyNodes {
    const val MAX_CHILDREN: Int = 200
    const val MAX_ROOTS: Int = 20

    fun tree(graph: TypeGraph, roots: List<Declaration>, textOf: (Path) -> String?): HierarchyTree {
        require(roots.isNotEmpty()) { "a type hierarchy needs at least one root" }
        val single = roots.size == 1
        val name = roots.first().name
        val title = if (single) "Type Hierarchy of $name" else "Type Hierarchy of $name (${roots.size} classes)"
        val nodes = roots.take(MAX_ROOTS).map {
            classNode(graph, it, emptySet(), textOf, root = true, expanded = single)
        }
        val shown = if (roots.size > MAX_ROOTS) nodes + HierarchyNode("… ${roots.size - MAX_ROOTS} more") else nodes
        return HierarchyTree(title, "Types outside project sources are not expanded", shown)
    }

    private fun classNode(
        graph: TypeGraph,
        type: Declaration,
        path: Set<String>,
        textOf: (Path) -> String?,
        root: Boolean = false,
        expanded: Boolean = false,
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
            expanded = expanded,
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
                                nodes += classNode(graph, it, path, textOf)
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

