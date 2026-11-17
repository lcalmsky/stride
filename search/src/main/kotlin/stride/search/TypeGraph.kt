package stride.search

import stride.core.vfs.VFile

/**
 * Where a name is written, so it can be resolved the way the compiler would without one: the file,
 * its package and imports, and the simple names of the classes around the position, innermost first.
 */
data class NameContext(
    val file: VFile?,
    val packageName: String?,
    val imports: List<SourceImport>,
    val enclosingClasses: List<String> = emptyList(),
)

/** A supertype as written and the project classes it resolves to; an empty [resolved] means outside project sources. */
data class SupertypeRef(val written: String, val resolved: List<Declaration>)

/**
 * Supertype, subtype and override relations of the project's Kotlin and Java classes, built from
 * one [DeclarationIndex.snapshot] and [DeclarationIndex.imports]. Construction is O(declarations);
 * every query is in memory. A supertype that does not resolve to a project class (a library, JDK or
 * standard library type) is kept as written and has no edges, so relations through such a type are
 * not known. Cycles in malformed code end the walk instead of looping. Results are sorted by
 * [Declaration.relativePath], then [Declaration.line].
 */
class TypeGraph(
    private val declarations: List<Declaration>,
    private val importsOf: (VFile) -> List<SourceImport>,
) {
    private val classes: List<Declaration> = declarations.filter { it.kind == DeclarationKind.CLASS }
    private val declarationsByFile: Map<String, List<Declaration>> = declarations.groupBy { it.file.path.toString() }
    private val classesByFile: Map<String, List<Declaration>> = classes.groupBy { it.file.path.toString() }
    private val qualified: Map<String, List<Declaration>> = buildQualifiedNames()
    private val supertypeRefs: Map<String, List<SupertypeRef>> = classes.associate { key(it) to resolveSupertypes(it) }
    private val supertypeIndex: Map<String, List<Declaration>> = classes.associate { type ->
        key(type) to supertypeRefs[key(type)].orEmpty().flatMap { it.resolved }
    }
    private val subtypeIndex: Map<String, List<Declaration>> = buildSubtypeIndex()
    private val membersByClass: Map<String, List<Declaration>> = buildMemberIndex()

    /**
     * The project classes [written] names at [context], resolved in this order, the first step that
     * finds a class wins:
     * 1. a dotted name `A.B`: when `A` resolves, the class `B` nested in it; otherwise the class whose
     *    package plus name (or package, container and name) equals `A.B`
     * 2. a class nested in one of [NameContext.enclosingClasses], or a sibling of the innermost one,
     *    in [NameContext.file]
     * 3. an explicit import whose [SourceImport.visibleName] is [written]; when that import names
     *    no project class the name is outside the project and the later steps are skipped
     * 4. a top-level class of [NameContext.packageName]
     * 5. a top-level class of a star-imported package
     *
     * Empty means the name is not a project class (a default import such as `kotlin.String` or
     * `java.lang.Object`, a library type, or a misspelling). Several results mean the same
     * qualified name is declared more than once (for example in two modules).
     */
    fun resolveType(written: String, context: NameContext): List<Declaration> {
        val name = written.trim()
        if (name.isEmpty()) return emptyList()
        if (name.contains('.')) {
            val prefix = name.substringBeforeLast('.')
            val last = name.substringAfterLast('.')
            if (prefix.isNotEmpty() && last.isNotEmpty()) {
                val owners = resolveType(prefix, context)
                if (owners.isNotEmpty()) {
                    val nested = owners.flatMap { owner ->
                        classesInFile(owner.file.path.toString()).filter {
                            it.name == last && it.container == owner.name
                        }
                    }
                    if (nested.isNotEmpty()) return sort(nested)
                }
            }
            return sort(qualified[name].orEmpty())
        }

        val filePath = context.file?.path?.toString()
        if (filePath != null) {
            for (enclosing in context.enclosingClasses) {
                val nested = classesInFile(filePath).filter { it.container == enclosing && it.name == name }
                if (nested.isNotEmpty()) return sort(nested)
            }
            val innermost = context.enclosingClasses.firstOrNull()
            if (innermost != null) {
                val container = classesInFile(filePath)
                    .filter { it.name == innermost }
                    .minByOrNull { it.line }
                    ?.container
                val siblings = classesInFile(filePath).filter {
                    it.container == container && it.name == name && it.name != innermost
                }
                if (siblings.isNotEmpty()) return sort(siblings)
            }
        }

        for (import in context.imports) {
            if (import.isStar || import.visibleName != name) continue
            val imported = qualified[import.qualifiedName].orEmpty()
            return if (imported.isEmpty()) emptyList() else sort(imported)
        }

        val samePackage = classes.filter {
            it.container == null && it.name == name && samePackage(it.packageName, context.packageName)
        }
        if (samePackage.isNotEmpty()) return sort(samePackage)

        val starred = context.imports.filter { it.isStar }.flatMap { import ->
            classes.filter { it.container == null && it.name == name && it.packageName == import.qualifiedName }
        }
        return sort(starred.distinct())
    }

    /** The context a declaration's own text is written in: its file, package, imports and container. */
    fun contextOf(declaration: Declaration): NameContext =
        NameContext(declaration.file, declaration.packageName, importsOf(declaration.file), listOfNotNull(declaration.container))

    /** The class a member is declared in: the CLASS declaration of the same file named [Declaration.container]. */
    fun classOf(member: Declaration): Declaration? {
        val container = member.container ?: return null
        return classesInFile(member.file.path.toString())
            .filter { it.name == container }
            .minByOrNull { it.line }
    }

    /** [type]'s direct supertypes in written order, each resolved from [type]'s own file. */
    fun supertypes(type: Declaration): List<SupertypeRef> = supertypeRefs[key(type)].orEmpty()

    /** The project classes that list [type] as a direct supertype. */
    fun directSubtypes(type: Declaration): List<Declaration> = sort(subtypeIndex[key(type)].orEmpty())

    /** Every project class below [type], transitively, each once. */
    fun allSubtypes(type: Declaration): List<Declaration> = sort(
        walk(key(type), subtypeIndex)
    )

    /**
     * What Go to Implementation lists for [target]. A CLASS: [allSubtypes] without interfaces. A
     * member (SYMBOL with a container): the members named like it declared in [allSubtypes] of its
     * class; in a Kotlin file only members marked [Declaration.isOverride] count, in a Java file any
     * member of that name counts because `@Override` is optional. A top-level SYMBOL has none.
     */
    fun implementations(target: Declaration): List<Declaration> {
        if (target.kind == DeclarationKind.CLASS) {
            return allSubtypes(target).filter { !it.isInterface }
        }
        if (target.container == null) return emptyList()
        val type = classOf(target) ?: return emptyList()
        val result = LinkedHashSet<Declaration>()
        for (subtype in allSubtypes(type)) {
            for (member in membersOf(subtype)) {
                if (member.name != target.name) continue
                if (isKotlin(member) && !member.isOverride) continue
                result.add(member)
            }
        }
        return sort(result)
    }

    /**
     * What Go to Super Method lists for [member]: walking [supertypes] breadth first, the first
     * member named like it on each path (a branch stops at its first match). Empty for a Kotlin
     * member without `override`, since it overrides nothing.
     */
    fun superMembers(member: Declaration): List<Declaration> {
        if (member.kind != DeclarationKind.SYMBOL || member.container == null) return emptyList()
        if (isKotlin(member) && !member.isOverride) return emptyList()
        val type = classOf(member) ?: return emptyList()
        val result = LinkedHashSet<Declaration>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Declaration>()
        queue.add(type)
        visited.add(key(type))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (ref in supertypeRefs[key(current)].orEmpty()) {
                for (supertype in ref.resolved) {
                    val found = membersOf(supertype).filter { it.name == member.name }
                    if (found.isNotEmpty()) {
                        result.addAll(found)
                    } else if (visited.add(key(supertype))) {
                        queue.add(supertype)
                    }
                }
            }
        }
        return sort(result)
    }

    /** Members named [name] declared in [type], and with [includeSupertypes] in its project supertypes too. */
    fun membersNamed(name: String, type: Declaration, includeSupertypes: Boolean): List<Declaration> {
        val result = LinkedHashSet<Declaration>()
        result.addAll(membersOf(type).filter { it.name == name })
        if (includeSupertypes) {
            for (supertype in walk(key(type), supertypeIndex)) {
                result.addAll(membersOf(supertype).filter { it.name == name })
            }
        }
        return sort(result)
    }

    /**
     * The project declarations a call of [name] written at [context] can reach. Unqualified: members
     * named [name] of [NameContext.enclosingClasses] and their supertypes, else visible top-level
     * functions (same package, explicit import, star import), else a visible class (a constructor
     * call). Qualified ([CallSite.receiver] not `null`): a receiver that [resolveType] resolves to a
     * class limits the result to members of that class and its supertypes; any other receiver gives
     * every non-private SYMBOL named [name] with a container, plus visible top-level ones (extension
     * functions). Private declarations count only inside their own file.
     */
    fun resolveCallee(site: CallSite, context: NameContext): List<Declaration> {
        val receiver = site.receiver
        if (receiver != null) {
            val owners = resolveType(receiver, context)
            if (owners.isNotEmpty()) {
                val result = LinkedHashSet<Declaration>()
                for (owner in owners) result.addAll(membersNamed(site.name, owner, includeSupertypes = true))
                return sort(result.filter { !it.isPrivate || sameFile(it, context) })
            }
            val result = LinkedHashSet<Declaration>()
            for (declaration in declarations) {
                if (declaration.kind != DeclarationKind.SYMBOL || declaration.name != site.name) continue
                if (declaration.container == null) continue
                if (declaration.isPrivate && !sameFile(declaration, context)) continue
                result.add(declaration)
            }
            result.addAll(topLevelFunctions(site.name, context))
            return sort(result)
        }

        val members = LinkedHashSet<Declaration>()
        for (enclosing in context.enclosingClasses) {
            val type = classInContext(enclosing, context) ?: continue
            members.addAll(membersNamed(site.name, type, includeSupertypes = true))
        }
        if (members.isNotEmpty()) return sort(members)

        val functions = topLevelFunctions(site.name, context)
        if (functions.isNotEmpty()) return sort(functions)

        return sort(
            resolveType(site.name, context)
                .filter { it.kind == DeclarationKind.CLASS && (!it.isPrivate || sameFile(it, context)) }
        )
    }

    /**
     * Whether [site], written at [context], may call [target] (a SYMBOL), judged by name and
     * visibility only:
     * - a private [target] only from its own file
     * - a top-level [target] only from its package, an explicit import of it or a star import of
     *   its package
     * - a member [target], unqualified or through `this` / `super`: only from inside its class, a
     *   class nested in it, or one of its [allSubtypes]
     * - a member [target] through a receiver that [resolveType] resolves to a class: only when that
     *   class is [target]'s class or one of its subtypes; any other receiver may
     */
    fun mayCall(target: Declaration, site: CallSite, context: NameContext): Boolean {
        if (target.isPrivate && !sameFile(target, context)) return false
        if (target.container == null) return topLevelVisible(target, context)
        val type = classOf(target) ?: return true
        val receiver = site.receiver
        if (receiver == null || receiver == "this" || receiver == "super") {
            val subtypes = allSubtypes(type).map { key(it) }.toSet()
            for (enclosing in context.enclosingClasses) {
                val candidate = classInContext(enclosing, context) ?: continue
                if (key(candidate) == key(type) || key(candidate) in subtypes) return true
            }
            return false
        }
        val owners = resolveType(receiver, context)
        if (owners.isEmpty()) return true
        val subtypes = allSubtypes(type).map { key(it) }.toSet()
        return owners.any { key(it) == key(type) || key(it) in subtypes }
    }

    private fun resolveSupertypes(type: Declaration): List<SupertypeRef> {
        val context = contextOf(type)
        return type.supertypes.map { written -> SupertypeRef(written, resolveType(written, context)) }
    }

    private fun buildSubtypeIndex(): Map<String, List<Declaration>> {
        val index = HashMap<String, MutableList<Declaration>>()
        for (type in classes) {
            for (ref in supertypeRefs[key(type)].orEmpty()) {
                for (supertype in ref.resolved) {
                    val bucket = index.getOrPut(key(supertype)) { ArrayList() }
                    if (bucket.none { key(it) == key(type) }) bucket.add(type)
                }
            }
        }
        return index
    }

    private fun buildMemberIndex(): Map<String, List<Declaration>> {
        val index = HashMap<String, MutableList<Declaration>>()
        for (declaration in declarations) {
            if (declaration.kind != DeclarationKind.SYMBOL || declaration.container == null) continue
            val type = classOf(declaration) ?: continue
            index.getOrPut(key(type)) { ArrayList() }.add(declaration)
        }
        return index
    }

    private fun buildQualifiedNames(): Map<String, List<Declaration>> {
        val index = HashMap<String, MutableList<Declaration>>()
        for (type in classes) {
            for (name in qualifiedNamesOf(type)) {
                index.getOrPut(name) { ArrayList() }.add(type)
            }
        }
        return index
    }

    private fun qualifiedNamesOf(type: Declaration): List<String> {
        val chain = ArrayList<String>()
        var current: Declaration? = type
        var guard = 0
        while (current != null && guard++ < 16) {
            chain.add(0, current.name)
            current = if (current.container == null) {
                null
            } else {
                classesInFile(current.file.path.toString())
                    .filter { it.name == current.container && it.line < current.line }
                    .maxByOrNull { it.line }
            }
        }
        val simple = chain.joinToString(".")
        val packageName = type.packageName
        return if (packageName == null) listOf(simple) else listOf("$packageName.$simple", simple)
    }

    private fun classesInFile(path: String): List<Declaration> = classesByFile[path].orEmpty()

    private fun membersOf(type: Declaration): List<Declaration> = membersByClass[key(type)].orEmpty()

    private fun classInContext(name: String, context: NameContext): Declaration? {
        val path = context.file?.path?.toString() ?: return null
        return classesInFile(path).filter { it.name == name }.minByOrNull { it.line }
    }

    private fun topLevelFunctions(name: String, context: NameContext): List<Declaration> {
        val result = ArrayList<Declaration>()
        for (declaration in declarations) {
            if (declaration.kind != DeclarationKind.SYMBOL || declaration.container != null) continue
            if (declaration.name != name) continue
            if (declaration.isPrivate && !sameFile(declaration, context)) continue
            if (topLevelVisible(declaration, context)) result.add(declaration)
        }
        return result
    }

    private fun topLevelVisible(target: Declaration, context: NameContext): Boolean {
        if (samePackage(target.packageName, context.packageName)) return true
        val qualifiedName = if (target.packageName == null) target.name else "${target.packageName}.${target.name}"
        for (import in context.imports) {
            if (import.isStar && import.qualifiedName == target.packageName) return true
            if (!import.isStar && import.qualifiedName == qualifiedName) return true
        }
        return false
    }

    private fun walk(start: String, edges: Map<String, List<Declaration>>): List<Declaration> {
        val visited = HashSet<String>()
        visited.add(start)
        val result = ArrayList<Declaration>()
        val queue = ArrayDeque<Declaration>()
        edges[start].orEmpty().forEach { if (visited.add(key(it))) queue.add(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            for (next in edges[key(current)].orEmpty()) {
                if (visited.add(key(next))) queue.add(next)
            }
        }
        return result
    }

    private fun sort(items: Collection<Declaration>): List<Declaration> =
        items.distinct().sortedWith(compareBy({ it.relativePath }, { it.line }))

    private fun sameFile(declaration: Declaration, context: NameContext): Boolean =
        context.file != null && declaration.file.path.toString() == context.file.path.toString()

    private fun samePackage(left: String?, right: String?): Boolean = left == right

    private fun isKotlin(declaration: Declaration): Boolean {
        val extension = declaration.file.extension?.lowercase()
        return extension == DeclarationParser.KOTLIN_EXTENSIONS ||
            extension == DeclarationParser.KOTLIN_SCRIPT_EXTENSION
    }

    private fun key(declaration: Declaration): String =
        "${declaration.file.path}|${declaration.line}|${declaration.kind}|${declaration.container}|${declaration.name}"
}
