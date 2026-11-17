package stride.app.hierarchy

import stride.app.navigation.declarationForMember
import stride.app.navigation.lineOf
import stride.app.navigation.offsetOfName
import stride.core.vfs.VFile
import stride.search.CallSite
import stride.search.CallSites
import stride.search.Declaration
import stride.search.DeclarationParser
import stride.search.IndexedFile
import stride.search.NameContext
import stride.search.OutlineMember
import stride.search.SourceImport
import stride.search.TypeGraph
import stride.search.classesAt
import stride.search.memberAt
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The tree Call Hierarchy shows: a root per member the caret resolves to, the calls that reach
 * each under `Callers` and the calls it makes under `Callees`. Calls are matched by name and
 * visibility only; one root opens itself, several roots start collapsed. A member already on the
 * path is a `(recursive)` leaf instead of expanding forever.
 */
internal object CallHierarchyNodes {
    const val MAX_CHILDREN: Int = 200
    const val MAX_ROOTS: Int = 20

    fun tree(
        graph: TypeGraph,
        declarations: List<Declaration>,
        roots: List<Declaration>,
        files: List<IndexedFile>,
        textOf: (Path) -> String?,
    ): HierarchyTree {
        require(roots.isNotEmpty()) { "a call hierarchy needs at least one root" }
        val sources = CallHierarchySources(declarations, textOf)
        val name = roots.first().name
        val sharing = declarations.count { it.name == name }
        val note = buildString {
            append("Calls are matched by name and visibility")
            if (sharing > 1) append("; $sharing declarations share this name")
        }
        val single = roots.size == 1
        val title = if (single) {
            "Call Hierarchy of $name"
        } else {
            "Call Hierarchy of $name (${roots.size} declarations)"
        }
        val nodes = roots.take(MAX_ROOTS).map {
            rootNode(graph, declarations, it, files, sources, expanded = single)
        }
        val shown = if (roots.size > MAX_ROOTS) nodes + HierarchyNode("… ${roots.size - MAX_ROOTS} more") else nodes
        return HierarchyTree(title, note, shown)
    }

    private fun rootNode(
        graph: TypeGraph,
        declarations: List<Declaration>,
        root: Declaration,
        files: List<IndexedFile>,
        sources: CallHierarchySources,
        expanded: Boolean,
    ): HierarchyNode {
        val rootPath = setOf(memberKey(root))
        return HierarchyNode(
            text = root.name,
            detail = "${root.relativePath}:${root.line + 1}",
            location = locationOf(root, sources::text),
            iconName = root.file.name,
            expanded = expanded,
            children = {
                listOf(
                    HierarchyNode(
                        text = "Callers",
                        expanded = true,
                        children = { callers(graph, declarations, root, rootPath, files, sources) },
                    ),
                    HierarchyNode(
                        text = "Callees",
                        expanded = false,
                        children = { callees(graph, declarations, root, rootPath, sources) },
                    ),
                )
            },
        )
    }

    /** The members whose calls may reach [target], one node per member, sorted by path then call. */
    fun callers(
        graph: TypeGraph,
        declarations: List<Declaration>,
        target: Declaration,
        path: Set<String>,
        files: List<IndexedFile>,
        sources: CallHierarchySources,
    ): List<HierarchyNode> {
        val groups = LinkedHashMap<String, CallerGroup>()
        for (file in files) {
            if (!DeclarationParser.isSourceFile(file.name)) continue
            val text = sources.text(file.file.path) ?: continue
            if (!text.contains(target.name)) continue
            val sites = CallSites.find(file.name, text, target.name)
            if (sites.isEmpty()) continue
            val parsed = sources.parsed(file.file) ?: continue
            for (site in sites) {
                val context = NameContext(
                    file.file,
                    parsed.packageName,
                    parsed.imports,
                    parsed.outline.classesAt(site.start).map { it.name },
                )
                if (!graph.mayCall(target, site, context)) continue
                val member = parsed.outline.memberAt(site.start)
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
                callerNode(graph, declarations, group, path, files, sources)
            }
        return limited(nodes)
    }

    /** The project declarations the calls inside [target] reach, one node per declaration. */
    fun callees(
        graph: TypeGraph,
        declarations: List<Declaration>,
        target: Declaration,
        path: Set<String>,
        sources: CallHierarchySources,
    ): List<HierarchyNode> {
        val text = sources.text(target.file.path) ?: return emptyList()
        val parsed = sources.parsed(target.file) ?: return emptyList()
        val outline = parsed.outline
        val nameOffset = offsetOfName(text, target.line, target.column, target.name)
        val member = outline.memberAt(nameOffset) ?: return emptyList()
        val context = NameContext(
            target.file,
            parsed.packageName,
            parsed.imports,
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
                val location = locationOf(declaration, sources::text)
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
                        { callees(graph, declarations, declaration, path + key, sources) }
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
        sources: CallHierarchySources,
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
        val declaration = sources.declarationOf(group.file, member, group.text)
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
                { callers(graph, declarations, declaration, path + key, files, sources) }
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

    private fun memberKey(file: VFile, container: String?, name: String): String =
        "${file.path.toAbsolutePath().normalize()}|$container|$name"

    private class CallerGroup(val member: OutlineMember?, val file: IndexedFile, val text: String) {
        val calls = ArrayList<CallSite>()
    }
}

/**
 * What one tree's loaders share: each file's text is read once, a file is parsed once when it
 * contains the searched name, and each caller's declaration is built once from the overlaid
 * declaration list. One instance per [CallHierarchyNodes.tree] call; the maps take concurrent
 * loaders.
 */
internal class CallHierarchySources(
    private val declarations: List<Declaration>,
    private val textOf: (Path) -> String?,
) {
    private val texts = ConcurrentHashMap<Path, SourceText>()
    private val parses = ConcurrentHashMap<Path, ParsedSource>()
    private val members = ConcurrentHashMap<String, Declaration>()

    fun text(path: Path): String? {
        val key = path.toAbsolutePath().normalize()
        return texts.computeIfAbsent(key) { SourceText(textOf(it)) }.text
    }

    fun parsed(file: VFile): ParsedSource? {
        val key = file.path.toAbsolutePath().normalize()
        parses[key]?.let { return it }
        val text = text(key) ?: return null
        val created = ParsedSource(
            packageName = DeclarationParser.packageOf(text),
            imports = DeclarationParser.imports(file.name, text),
            outline = DeclarationParser.outline(file.name, text),
        )
        parses[key] = created
        return created
    }

    fun declarationOf(file: IndexedFile, member: OutlineMember, text: String): Declaration {
        val key = "${file.file.path.toAbsolutePath().normalize()}|${member.container}|${member.name}"
        return members.computeIfAbsent(key) {
            declarationForMember(
                declarations = declarations,
                file = file.file,
                relativePath = file.relativePath,
                text = text,
                member = member,
                packageName = parsed(file.file)?.packageName,
            )
        }
    }

    class SourceText(val text: String?)

    class ParsedSource(
        val packageName: String?,
        val imports: List<SourceImport>,
        val outline: List<OutlineMember>,
    )
}
