package stride.app.navigation

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.hierarchy.CallHierarchyNodes
import stride.app.hierarchy.TypeHierarchyNodes
import stride.app.keys.KeyContext
import stride.core.services.service
import stride.search.CallSite
import stride.search.CallSites
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.DeclarationKind
import stride.search.DeclaredType
import stride.search.FileNameIndex
import stride.search.NameContext
import stride.search.OutlineKind
import stride.search.TypeGraph
import stride.search.classesAt
import stride.search.memberAt
import stride.search.TypeReferences
import kotlin.math.abs

/** The chooser Go to Implementation, Type Declaration and Super Method open over several targets. */
class NavigationTargets(val title: String, val targets: List<Declaration>)

/**
 * Go to Implementation (⌥⌘B): the implementations of the class or member at the caret
 * ([stride.search.TypeGraph.implementations]). One opens at its name, several open the chooser,
 * none reports `No implementations of <name> found in project sources`.
 */
fun goToImplementation(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val graph = typeGraphOf(index)
    val snapshot = index.snapshot()
    val caret = HierarchyCaret(editor)

    val targets: List<Declaration>
    val name: String
    val named = caret.nameAtCaret()
    if (named != null) {
        targets = listOf(caret.declaration(named, session, snapshot))
        name = named.name
    } else {
        val identifier = caret.identifier ?: run {
            state.statusMessage = "No identifier at the caret"
            return true
        }
        name = identifier
        val asType = graph.resolveType(identifier, caret.contextAt())
        targets = if (asType.isNotEmpty()) {
            asType
        } else {
            val site = caret.callAtCaret(identifier)
            graph.resolveCallee(site, caret.contextAt())
        }
    }

    val results = targets.flatMap { graph.implementations(it) }.distinct().sortedWith(byLocation)
    return when {
        results.isEmpty() -> {
            state.statusMessage = "No implementations of $name found in project sources"
            true
        }
        results.size == 1 -> openTarget(state, session, results.first())
        else -> openChooser(state, "Choose Implementation of $name (${results.size} found)", results)
    }
}

/**
 * Go to Type Declaration (⇧⌘B, ⌃⇧B): the class the declaration at the caret declares as its type,
 * only when the text says so ([stride.search.TypeReferences]); otherwise the status bar says why.
 */
fun goToTypeDeclaration(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val graph = typeGraphOf(index)
    val snapshot = index.snapshot()
    val caret = HierarchyCaret(editor)
    val name = caret.identifier ?: run {
        state.statusMessage = "No identifier at the caret"
        return true
    }

    return when (val lookup = findDeclaration(state, session, caret, graph, snapshot, name)) {
        null -> {
            state.statusMessage = "Cannot find the declaration of $name"
            true
        }
        is DeclarationLookup.Type -> openTypeResults(state, session, lookup.declarations, name)
        is DeclarationLookup.Site -> {
            val site = lookup.site
            when (val declared = TypeReferences.declaredType(site.fileName, site.text, site.nameStart)) {
                is DeclaredType.Explicit ->
                    resolveDeclaredType(state, session, graph, declared.written, site.context, name)
                is DeclaredType.ConstructorCall -> {
                    val simple = declared.written.substringAfterLast('.')
                    if (visibleTopLevel(snapshot, simple, site.context).any { it.kind == DeclarationKind.SYMBOL }) {
                        state.statusMessage = unknownTypeReason(name)
                        true
                    } else {
                        resolveDeclaredType(state, session, graph, declared.written, site.context, name)
                    }
                }
                is DeclaredType.Unknown -> {
                    state.statusMessage = declared.reason
                    true
                }
            }
        }
    }
}

/**
 * Go to Super Method (⌘U): the members the function around the caret overrides
 * ([stride.search.TypeGraph.superMembers]); in a class outside any function, its project supertypes.
 */
fun goToSuperMethod(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val graph = typeGraphOf(index)
    val snapshot = index.snapshot()
    val caret = HierarchyCaret(editor)
    val member = caret.outline.memberAt(caret.offset)

    if (member != null && (member.kind == OutlineKind.FUNCTION || member.kind == OutlineKind.PROPERTY)) {
        val name = member.name
        if (isKotlin(editor.file.extension) && !member.isOverride) {
            state.statusMessage = "$name does not override a method"
            return true
        }
        val target = caret.declaration(member, session, snapshot)
        val supers = graph.superMembers(target).distinct().sortedWith(byLocation)
        return when {
            supers.isEmpty() -> {
                state.statusMessage = "No super method of $name in project sources"
                true
            }
            supers.size == 1 -> openTarget(state, session, supers.first())
            else -> openChooser(state, "Choose Super Method of $name", supers)
        }
    }

    val classMember = when {
        member == null -> caret.outline.classesAt(caret.offset).firstOrNull()
        member.kind == OutlineKind.CLASS -> member
        else -> caret.outline.classesAt(caret.offset).firstOrNull()
    } ?: run {
        state.statusMessage = "Place the caret in a class or function"
        return true
    }
    val classDeclaration = caret.declaration(classMember, session, snapshot)
    val supertypes = graph.supertypes(classDeclaration)
        .flatMap { it.resolved }
        .distinct()
        .sortedWith(byLocation)
    return when {
        supertypes.isEmpty() -> {
            state.statusMessage = "No supertype of ${classDeclaration.name} in project sources"
            true
        }
        supertypes.size == 1 -> openTarget(state, session, supertypes.first())
        else -> openChooser(state, "Choose Super Class of ${classDeclaration.name}", supertypes)
    }
}

/** Type Hierarchy (⌃H): supertypes and subtypes of the class at or around the caret, in the Hierarchy tool window. */
fun showTypeHierarchy(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val graph = typeGraphOf(index)
    val snapshot = index.snapshot()
    val caret = HierarchyCaret(editor)
    val target = caret.identifier?.let { graph.resolveType(it, caret.contextAt()).firstOrNull() }
        ?: caret.outline.classesAt(caret.offset).firstOrNull()?.let { caret.declaration(it, session, snapshot) }
    if (target == null) {
        state.statusMessage = "Place the caret in a class"
        return true
    }
    state.openHierarchy(TypeHierarchyNodes.tree(graph, target) { path -> HierarchyText.read(state, path) })
    return true
}

/** Call Hierarchy (⌃⌥H): callers and callees of the function at or around the caret, in the Hierarchy tool window. */
fun showCallHierarchy(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val graph = typeGraphOf(index)
    val snapshot = index.snapshot()
    val caret = HierarchyCaret(editor)
    val target = callTarget(caret, session, graph, snapshot)
    if (target == null) {
        state.statusMessage = "Place the caret in a function"
        return true
    }
    val files = session.project.service<FileNameIndex>().snapshot()
    state.openHierarchy(
        CallHierarchyNodes.tree(graph, snapshot, target, files) { path -> HierarchyText.read(state, path) },
    )
    return true
}

private val byLocation: Comparator<Declaration> =
    compareBy<Declaration>({ it.relativePath }, { it.line }, { it.column })

private val callableKinds = setOf(OutlineKind.FUNCTION, OutlineKind.CONSTRUCTOR, OutlineKind.PROPERTY)

private fun HierarchyCaret.callAtCaret(name: String): CallSite =
    CallSites.inRange(fileName, text, 0, text.length).firstOrNull { offset in it.start..it.end }
        ?: CallSite(name, offset, offset + name.length)

private fun callTarget(
    caret: HierarchyCaret,
    session: ProjectSession,
    graph: TypeGraph,
    snapshot: List<Declaration>,
): Declaration? {
    val declaredName = caret.outline.firstOrNull {
        it.kind == OutlineKind.FUNCTION && caret.offset >= it.nameStart && caret.offset <= it.nameEnd
    }
    if (declaredName != null) return caret.declaration(declaredName, session, snapshot)

    val identifier = caret.identifier
    if (identifier != null) {
        val site = CallSites.inRange(caret.fileName, caret.text, 0, caret.text.length)
            .firstOrNull { caret.offset in it.start..it.end }
        if (site != null) {
            val resolved = graph.resolveCallee(site, caret.contextAt())
            if (resolved.size == 1) return resolved.first()
        }
    }

    val member = caret.outline.memberAt(caret.offset)
    return if (member != null && member.kind in callableKinds) {
        caret.declaration(member, session, snapshot)
    } else {
        null
    }
}

private fun isKotlin(extension: String?): Boolean =
    extension?.lowercase()?.let { it == "kt" || it == "kts" } == true

private fun openTarget(state: AppState, session: ProjectSession, declaration: Declaration): Boolean {
    openFileAtColumn(state, session, declaration.file, declaration.line, declaration.column)
    return true
}

private fun openChooser(state: AppState, title: String, targets: List<Declaration>): Boolean {
    state.navigationTargets = NavigationTargets(title, targets)
    state.focus = KeyContext.CONTEXT_POPUP
    return true
}

private fun openTypeResults(state: AppState, session: ProjectSession, types: List<Declaration>, name: String): Boolean {
    val results = types.distinct().sortedWith(byLocation)
    return when {
        results.isEmpty() -> {
            state.statusMessage = "Cannot find the declaration of $name"
            true
        }
        results.size == 1 -> openTarget(state, session, results.first())
        else -> openChooser(state, "Choose Type Declaration of $name", results)
    }
}

private fun resolveDeclaredType(
    state: AppState,
    session: ProjectSession,
    graph: TypeGraph,
    written: String,
    context: NameContext,
    name: String,
): Boolean {
    val results = graph.resolveType(written, context).distinct().sortedWith(byLocation)
    return when {
        results.isEmpty() -> {
            state.statusMessage = "$written is not in project sources"
            true
        }
        results.size == 1 -> openTarget(state, session, results.first())
        else -> openChooser(state, "Choose Type Declaration of $name", results)
    }
}

private fun unknownTypeReason(name: String): String =
    "Cannot infer the type of $name without a compiler; declare it explicitly"

private class DeclarationSite(
    val fileName: String,
    val text: String,
    val nameStart: Int,
    val context: NameContext,
)

private sealed interface DeclarationLookup {
    class Site(val site: DeclarationSite) : DeclarationLookup
    class Type(val declarations: List<Declaration>) : DeclarationLookup
}

private fun findDeclaration(
    state: AppState,
    session: ProjectSession,
    caret: HierarchyCaret,
    graph: TypeGraph,
    snapshot: List<Declaration>,
    name: String,
): DeclarationLookup? {
    val local = TypeReferences.localDeclaration(caret.fileName, caret.text, caret.offset, name)
    if (local != null) {
        return DeclarationLookup.Site(
            DeclarationSite(caret.fileName, caret.text, local, caret.contextAt(local)),
        )
    }

    for (enclosing in caret.outline.classesAt(caret.offset)) {
        val classDeclaration = caret.declaration(enclosing, session, snapshot)
        for (member in graph.membersNamed(name, classDeclaration, includeSupertypes = true)) {
            val site = siteOf(member, caret, state, graph) ?: continue
            return DeclarationLookup.Site(site)
        }
    }

    for (topLevel in visibleTopLevel(snapshot, name, caret.contextAt()).sortedWith(byLocation)) {
        val site = siteOf(topLevel, caret, state, graph) ?: continue
        return DeclarationLookup.Site(site)
    }

    val types = graph.resolveType(name, caret.contextAt())
    return if (types.isEmpty()) null else DeclarationLookup.Type(types)
}

private fun siteOf(
    declaration: Declaration,
    caret: HierarchyCaret,
    state: AppState,
    graph: TypeGraph,
): DeclarationSite? {
    val path = declaration.file.path.toAbsolutePath().normalize()
    val current = path == caret.editor.file.path.toAbsolutePath().normalize()
    val text = if (current) caret.text else HierarchyText.read(state, path) ?: return null
    val nameStart = if (current) {
        caret.outline
            .filter { it.name == declaration.name && it.container == declaration.container }
            .minByOrNull { abs(it.line - declaration.line) }
            ?.nameStart
            ?: offsetOfName(text, declaration.line, declaration.column, declaration.name)
    } else {
        offsetOfName(text, declaration.line, declaration.column, declaration.name)
    }
    val context = if (current) caret.contextAt(nameStart) else graph.contextOf(declaration)
    return DeclarationSite(declaration.file.name, text, nameStart, context)
}

private fun visibleTopLevel(
    snapshot: List<Declaration>,
    name: String,
    context: NameContext,
): List<Declaration> {
    val currentPath = context.file?.path?.toAbsolutePath()?.normalize()
    return snapshot.filter {
        it.kind == DeclarationKind.SYMBOL && it.container == null && it.name == name &&
            visible(it, context, currentPath)
    }
}

private fun visible(declaration: Declaration, context: NameContext, currentPath: java.nio.file.Path?): Boolean {
    if (declaration.isPrivate && declaration.file.path.toAbsolutePath().normalize() != currentPath) return false
    if (declaration.packageName == context.packageName) return true
    val qualified = if (declaration.packageName == null) {
        declaration.name
    } else {
        "${declaration.packageName}.${declaration.name}"
    }
    return context.imports.any { import ->
        if (import.isStar) import.qualifiedName == declaration.packageName else import.qualifiedName == qualified
    }
}
