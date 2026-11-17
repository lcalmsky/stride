package stride.app.hierarchy

import stride.app.navigation.declarationForMember
import stride.app.navigation.lineOf
import stride.app.navigation.offsetOfName
import stride.search.CallSite
import stride.search.CallSites
import stride.search.Declaration
import stride.search.DeclarationKind
import stride.search.DeclarationParser
import stride.search.IndexedFile
import stride.search.NameContext
import stride.search.OutlineMember
import stride.search.TypeGraph
import stride.search.classesAt
import stride.search.memberAt
import java.nio.file.Path

/**
 * The tree Call Hierarchy shows: the member at the root, the calls that reach it under `Callers`
 * and the calls it makes under `Callees`. Calls are matched by name and visibility only; every
 * child that is loaded rescans the project files, and a member already on the path is a
 * `(recursive)` leaf instead of expanding forever.
 */
internal object CallHierarchyNodes {
    const val MAX_CHILDREN: Int = 200

    fun tree(
        graph: TypeGraph,
        declarations: List<Declaration>,
        root: Declaration,
        files: List<IndexedFile>,
        textOf: (Path) -> String?,
    ): HierarchyTree {
        val sharing = declarations.count { it.kind == DeclarationKind.SYMBOL && it.name == root.name }
        val note = buildString {
            append("Calls are matched by name and visibility")
            if (sharing > 1) append("; $sharing declarations share this name")
        }
        val rootPath = setOf(memberKey(root))
        val rootNode = HierarchyNode(
            text = root.name,
            detail = "${root.relativePath}:${root.line + 1}",
            location = locationOf(root, textOf),
            iconName = root.file.name,
            expanded = true,
            children = {
                listOf(
                    HierarchyNode(
                        text = "Callers",
                        expanded = true,
                        children = { callers(graph, declarations, root, rootPath, files, textOf) },
                    ),
                    HierarchyNode(
                        text = "Callees",
                        expanded = false,
                        children = { callees(graph, declarations, root, rootPath, textOf) },
                    ),
                )
            },
        )
        return HierarchyTree("Call Hierarchy of ${root.name}", note, listOf(rootNode))
    }

    /** The members whose calls may reach [target], one node per member, sorted by path then call. */
    fun callers(
        graph: TypeGraph,
        declarations: List<Declaration>,
        target: Declaration,
        path: Set<String>,
        files: List<IndexedFile>,
        textOf: (Path) -> String?,
    ): List<HierarchyNode> {
        val groups = LinkedHashMap<String, CallerGroup>()
        for (file in files) {
            if (!DeclarationParser.isSourceFile(file.name)) continue
            val text = textOf(file.file.path) ?: continue
            if (!text.contains(target.name)) continue
            val sites = CallSites.find(file.name, text, target.name)
            if (sites.isEmpty()) continue
            val packageName = DeclarationParser.packageOf(text)
            val imports = DeclarationParser.imports(file.name, text)
            val outline = DeclarationParser.outline(file.name, text)
            for (site in sites) {
                val context = NameContext(file.file, packageName, imports, outline.classesAt(site.start).map { it.name })
                if (!graph.mayCall(target, site, context)) continue
                val member = outline.memberAt(site.start)
                val key = if (member == null) {
                    "file:${file.file.path.toAbsolutePath().normalize()}"
                } else {
                    memberKey(file.file, member.container, member.name)
                }
                groups.getOrPut(key) { CallerGroup(member, file, text) }.calls += site
            }
        }
        val nodes = groups.values
            .sortedWith(compareBy({ it.file.relativePath }, { it.calls.first().start }))
            .map { group ->
                callerNode(graph, declarations, group, path, files, textOf)
            }
        return limited(nodes)
    }

    /** The project declarations the calls inside [target] reach, one node per declaration. */
    fun callees(
        graph: TypeGraph,
        declarations: List<Declaration>,
        target: Declaration,
        path: Set<String>,
        textOf: (Path) -> String?,
    ): List<HierarchyNode> {
        val text = textOf(target.file.path) ?: return emptyList()
        val outline = DeclarationParser.outline(target.file.name, text)
        val nameOffset = offsetOfName(text, target.line, target.column, target.name)
        val member = outline.memberAt(nameOffset) ?: return emptyList()
        val context = NameContext(
            target.file,
            DeclarationParser.packageOf(text),
            DeclarationParser.imports(target.file.name, text),
            outline.classesAt(nameOffset).map { it.name },
        )
        val grouped = LinkedHashMap<String, Declaration>()
        for (site in CallSites.inRange(target.file.name, text, member.start, member.end)) {
            for (declaration in graph.resolveCallee(site, context)) {
                grouped.getOrPut(memberKey(declaration)) { declaration }
            }
        }
        val nodes = grouped.values
            .map { declaration ->
                val key = memberKey(declaration)
                val recursive = key in path
                val location = locationOf(declaration, textOf)
                val node = HierarchyNode(
                    text = if (recursive) {
                        "${label(declaration.container, declaration.name)} (recursive)"
                    } else {
                        label(declaration.container, declaration.name)
                    },
                    detail = "${declaration.relativePath}:${declaration.line + 1}",
                    location = location,
                    iconName = declaration.file.name,
                    children = if (recursive) {
                        null
                    } else {
                        { callees(graph, declarations, declaration, path + key, textOf) }
                    },
                )
                Triple(node, declaration.relativePath, location.offset)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
        return limited(nodes)
    }

    private fun callerNode(
        graph: TypeGraph,
        declarations: List<Declaration>,
        group: CallerGroup,
        path: Set<String>,
        files: List<IndexedFile>,
        textOf: (Path) -> String?,
    ): HierarchyNode {
        val first = group.calls.first()
        val filePath = group.file.file.path.toAbsolutePath().normalize()
        val member = group.member
        if (member == null) {
            return HierarchyNode(
                text = group.file.name,
                detail = "${group.file.relativePath}:${lineOf(group.text, first.start) + 1}",
                location = HierarchyLocation(filePath, first.start),
            )
        }
        val declaration = declarationForMember(
            snapshot = declarations,
            file = group.file.file,
            relativePath = group.file.relativePath,
            fileName = group.file.name,
            text = group.text,
            member = member,
            packageName = DeclarationParser.packageOf(group.text),
        )
        val key = memberKey(declaration)
        val recursive = key in path
        val calls = group.calls.size
        return HierarchyNode(
            text = if (recursive) "${label(member.container, member.name)} (recursive)" else label(member.container, member.name),
            detail = "${group.file.relativePath}:${member.line + 1}" + if (calls > 1) " ($calls calls)" else "",
            location = HierarchyLocation(filePath, first.start),
            iconName = group.file.name,
            children = if (recursive) {
                null
            } else {
                { callers(graph, declarations, declaration, path + key, files, textOf) }
            },
        )
    }

    private fun limited(nodes: List<HierarchyNode>): List<HierarchyNode> =
        if (nodes.size <= MAX_CHILDREN) {
            nodes
        } else {
            nodes.take(MAX_CHILDREN) + HierarchyNode("… ${nodes.size - MAX_CHILDREN} more")
        }

    private fun label(container: String?, name: String): String =
        if (container == null) name else "$container.$name"

    private fun memberKey(declaration: Declaration): String =
        memberKey(declaration.file, declaration.container, declaration.name)

    private fun memberKey(file: stride.core.vfs.VFile, container: String?, name: String): String =
        "${file.path.toAbsolutePath().normalize()}|$container|$name"

    private class CallerGroup(val member: OutlineMember?, val file: IndexedFile, val text: String) {
        val calls = ArrayList<CallSite>()
    }
}
