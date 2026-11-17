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
    fun resolveType(written: String, context: NameContext): List<Declaration> = emptyList()

    /** The context a declaration's own text is written in: its file, package, imports and container. */
    fun contextOf(declaration: Declaration): NameContext =
        NameContext(declaration.file, declaration.packageName, importsOf(declaration.file), listOfNotNull(declaration.container))

    /** The class a member is declared in: the CLASS declaration of the same file named [Declaration.container]. */
    fun classOf(member: Declaration): Declaration? = null

    /** [type]'s direct supertypes in written order, each resolved from [type]'s own file. */
    fun supertypes(type: Declaration): List<SupertypeRef> = emptyList()

    /** The project classes that list [type] as a direct supertype. */
    fun directSubtypes(type: Declaration): List<Declaration> = emptyList()

    /** Every project class below [type], transitively, each once. */
    fun allSubtypes(type: Declaration): List<Declaration> = emptyList()

    /**
     * What Go to Implementation lists for [target]. A CLASS: [allSubtypes] without interfaces. A
     * member (SYMBOL with a container): the members named like it declared in [allSubtypes] of its
     * class; in a Kotlin file only members marked [Declaration.isOverride] count, in a Java file any
     * member of that name counts because `@Override` is optional. A top-level SYMBOL has none.
     */
    fun implementations(target: Declaration): List<Declaration> = emptyList()

    /**
     * What Go to Super Method lists for [member]: walking [supertypes] breadth first, the first
     * member named like it on each path (a branch stops at its first match). Empty for a Kotlin
     * member without `override`, since it overrides nothing.
     */
    fun superMembers(member: Declaration): List<Declaration> = emptyList()

    /** Members named [name] declared in [type], and with [includeSupertypes] in its project supertypes too. */
    fun membersNamed(name: String, type: Declaration, includeSupertypes: Boolean): List<Declaration> = emptyList()

    /**
     * The project declarations a call of [name] written at [context] can reach. Unqualified: members
     * named [name] of [NameContext.enclosingClasses] and their supertypes, else visible top-level
     * functions (same package, explicit import, star import), else a visible class (a constructor
     * call). Qualified ([CallSite.receiver] not `null`): a receiver that [resolveType] resolves to a
     * class limits the result to members of that class and its supertypes; any other receiver gives
     * every non-private SYMBOL named [name] with a container, plus visible top-level ones (extension
     * functions). Private declarations count only inside their own file.
     */
    fun resolveCallee(site: CallSite, context: NameContext): List<Declaration> = emptyList()

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
    fun mayCall(target: Declaration, site: CallSite, context: NameContext): Boolean = false
}
