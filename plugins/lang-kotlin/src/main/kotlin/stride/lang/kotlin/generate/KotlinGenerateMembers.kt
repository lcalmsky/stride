package stride.lang.kotlin.generate

import stride.lang.GenerateContext
import stride.lang.GenerateKind
import stride.lang.MemberOption
import stride.lang.NameCheck
import stride.lang.ProjectTypes
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TypeSource
import stride.lang.kotlin.KotlinTokenTypes
import stride.lang.kotlin.codeTokens
import stride.lang.kotlin.parseImports
import stride.lang.kotlin.usedIdentifiersIn
import stride.lang.kotlin.refactor.KotlinCode

private const val INDEXING_MESSAGE = "Indexing… try again in a moment"
private const val TODO_BODY = "TODO(\"Not yet implemented\")"

internal class WalkNode(
    val declaration: KtClazz,
    val source: TypeSource?,
    /** The direct supertype through which this node was reached, the `Owner` of `super<Owner>`. */
    val directName: String,
    val substitution: Map<String, String?>,
    val depth: Int,
    /** Identifies the node along the walk; two paths through different direct supertypes differ. */
    val key: String,
)

internal class CandidateMember(
    val option: MemberOption,
    val signature: String?,
    val lines: List<String>?,
    val imports: List<String>,
    val caretLine: Int,
    val caretColumn: Int,
)

internal fun overrideMethods(klass: KtClazz, context: GenerateContext): RefactoringStep =
    generateMembers(klass, context, override = true)

internal fun implementMethods(klass: KtClazz, context: GenerateContext): RefactoringStep =
    generateMembers(klass, context, override = false)

private fun generateMembers(klass: KtClazz, context: GenerateContext, override: Boolean): RefactoringStep {
    val kind = if (override) GenerateKind.OVERRIDE_METHODS else GenerateKind.IMPLEMENT_METHODS
    availabilityRefusal(klass, kind)?.let { return RefactoringStep.Refused(it) }
    val types = context.types
    if (types == null) {
        if (klass.supertypes.isNotEmpty()) return RefactoringStep.Refused(INDEXING_MESSAGE)
        if (!override) return RefactoringStep.Refused("No members to implement in project sources")
        val candidates = anyCandidates(klass, emptyList())
        if (candidates.isEmpty()) return RefactoringStep.Refused("No members to implement in project sources")
        return chooseMembers(klass, context, candidates, GenerateKind.OVERRIDE_METHODS.title)
    }

    val walked = walk(klass, context, types, override)
    if (!override && walked.unreadClass != null) {
        return RefactoringStep.Refused("${walked.unreadClass} is not in project sources; it may implement these members")
    }
    // G6: Override lists Any's members with the other candidates, not only when there are none.
    val candidates = collectCandidates(
        klass,
        context,
        walked.nodes,
        walked.providerCount,
        walked.bodies,
        override,
    ) + if (override) anyCandidates(klass, walked.nodes) else emptyList()
    val title = if (walked.unreadInterfaces.isEmpty()) {
        kind.title
    } else {
        kind.title + ": " + walked.unreadInterfaces.joinToString(", ") { "$it is not in project sources" }
    }
    if (candidates.isEmpty()) return RefactoringStep.Refused("No members to implement in project sources")
    return chooseMembers(klass, context, candidates, title)
}

private fun chooseMembers(
    klass: KtClazz,
    context: GenerateContext,
    candidates: List<CandidateMember>,
    title: String,
): RefactoringStep {
    val options = candidates.map { it.option }
    return RefactoringStep.ChooseMembers(
        title = title,
        members = options,
        complete = { checked ->
            val selected = candidates.filter { candidate -> checked.any { it === candidate.option } }
            if (selected.isEmpty()) {
                NameCheck.Valid(RefactoringEdit(emptyList(), context.caretOffset))
            } else {
                val members = selected.map { GeneratedMember(it.lines ?: emptyList(), it.caretLine, it.caretColumn) }
                val imports = selected.flatMap { it.imports }.distinct()
                val edit = applyWithImports(klass, context, members, imports)
                if (edit == null) NameCheck.Invalid("No class at the caret") else NameCheck.Valid(edit)
            }
        },
    )
}

private class Walked(
    val nodes: List<WalkNode>,
    /** For each node key, every node key above it along the walk. */
    val ancestors: Map<String, Set<String>>,
    val providerCount: Map<String, Set<String>>,
    val bodies: MemberBodies,
    val unreadClass: String?,
    val unreadInterfaces: List<String>,
)

private fun walk(klass: KtClazz, context: GenerateContext, types: ProjectTypes, override: Boolean): Walked {
    val nodes = ArrayList<WalkNode>()
    val providers = HashMap<String, MutableSet<String>>()
    val edges = HashMap<String, MutableSet<String>>()
    val seen = HashSet<String>()
    var unreadClass: String? = null
    val unreadInterfaces = ArrayList<String>()
    val queue = ArrayDeque<Pair<WalkNode?, KtSupertype>>()
    for (supertype in klass.supertypes) {
        queue.add(null to supertype)
    }
    while (queue.isNotEmpty()) {
        val (parent, supertype) = queue.removeFirst()
        val declaring = parent?.declaration ?: klass
        val path = parent?.source?.path ?: context.path
        val sources = types.resolve(supertype.written, path, supertype.offset)
        if (sources.isEmpty()) {
            if (declaring.supertypeMayBeClass(supertype)) {
                if (!override && unreadClass == null) unreadClass = supertype.simpleName
            } else if (!override && supertype.simpleName !in unreadInterfaces) {
                unreadInterfaces.add(supertype.simpleName)
            }
            continue
        }
        for (source in sources) {
            val declaration = classInSource(source.text, source.nameOffset) ?: continue
            val substitution = substitutionFor(declaration, supertype, parent?.substitution.orEmpty())
            val directName = parent?.directName ?: supertype.simpleName
            val key = declaration.name + "@" + source.path + "@" + directName
            if (parent != null) edges.getOrPut(parent.key) { HashSet() }.add(key)
            if (!seen.add(key)) continue
            val node = WalkNode(declaration, source, directName, substitution, (parent?.depth ?: -1) + 1, key)
            nodes.add(node)
            for (member in declaration.supertypes) {
                queue.add(node to member)
            }
        }
    }
    // A member is ambiguous for `super` when more than one direct supertype provides it.
    for (node in nodes) {
        for (function in node.declaration.functions) {
            val signature = functionKey(function, node.substitution)
            providers.getOrPut(signature) { HashSet() }.add(node.directName)
        }
        for (property in node.declaration.properties) {
            val name = property.name ?: continue
            providers.getOrPut("prop:$name") { HashSet() }.add(node.directName)
        }
    }
    val ancestors = ancestorKeys(nodes, edges)
    return Walked(nodes, ancestors, providers, memberBodies(nodes, ancestors), unreadClass, unreadInterfaces)
}

private fun ancestorKeys(nodes: List<WalkNode>, edges: Map<String, Set<String>>): Map<String, Set<String>> {
    val result = HashMap<String, Set<String>>()
    for (node in nodes) {
        val reached = HashSet<String>()
        val stack = ArrayDeque<String>()
        edges[node.key]?.let { stack.addAll(it) }
        while (stack.isNotEmpty()) {
            val key = stack.removeLast()
            if (!reached.add(key)) continue
            edges[key]?.let { stack.addAll(it) }
        }
        result[node.key] = reached
    }
    return result
}

private fun substitutionFor(
    declaration: KtClazz,
    supertype: KtSupertype,
    outer: Map<String, String?>,
): Map<String, String?> {
    val result = HashMap<String, String?>()
    val arguments = supertype.typeArguments
    for ((index, parameter) in declaration.typeParameters.withIndex()) {
        val written = arguments?.getOrNull(index)
        if (written == null) {
            result[parameter] = null
            continue
        }
        val substituted = substitute(written, outer, emptySet())
        val value = substituted.text?.trim()
        result[parameter] = when {
            value == null -> null
            value == "*" -> null
            value.startsWith("out ") || value.startsWith("in ") -> null
            else -> value
        }
    }
    return result
}

internal class Substituted(val text: String?, val reason: String? = null)

/**
 * Replaces [substitution]'s type parameter names in [text] token by token, outside qualified names.
 * A parameter whose argument is missing, `*` or a projection makes the whole text unusable.
 */
internal fun substitute(text: String, substitution: Map<String, String?>, memberTypeParameters: Set<String>): Substituted {
    if (substitution.isEmpty()) return Substituted(text)
    val tokens = codeTokens(text)
    val out = StringBuilder()
    var last = 0
    for ((index, token) in tokens.withIndex()) {
        val identifier = token.type == KotlinTokenTypes.IDENTIFIER ||
            (token.type == KotlinTokenTypes.KEYWORD && token.text !in KotlinCode.HARD_KEYWORDS)
        if (!identifier) continue
        val name = token.text.removeSurrounding("`")
        if (name in memberTypeParameters) continue
        if (name !in substitution) continue
        val previous = tokens.getOrNull(index - 1)
        if (previous != null && previous.type == KotlinTokenTypes.DOT) continue
        val value = substitution[name] ?: return Substituted(null, "\$$name has no type argument")
        out.append(text, last, token.start)
        out.append(value)
        last = token.end
    }
    out.append(text, last, text.length)
    return Substituted(out.toString())
}

private fun functionKey(function: SigFunction, substitution: Map<String, String?>): String {
    val parameters = function.parameters.joinToString(",") { parameter ->
        substitute(parameter.type ?: "", substitution, function.typeParameterNames.toSet()).text ?: "?"
    }
    return "fun:" + function.name + "(" + parameters.filterNot { it.isWhitespace() } + ")"
}

private fun propertyKey(property: KtProperty, substitution: Map<String, String?>): String =
    "prop:" + (property.name ?: "") + ":" +
        (substitute(property.typeText ?: "", substitution, emptySet()).text ?: "?").filterNot { it.isWhitespace() }

private fun collectCandidates(
    klass: KtClazz,
    context: GenerateContext,
    nodes: List<WalkNode>,
    providers: Map<String, Set<String>>,
    bodies: MemberBodies,
    override: Boolean,
): List<CandidateMember> {
    val existingFunctions = klass.functions.map { function ->
        "fun:" + function.name + "(" +
            function.parameters.joinToString(",") { (it.type ?: "").filterNot { ch -> ch.isWhitespace() } } + ")"
    }.toSet()
    val existingProperties = klass.properties.mapNotNull { it.name }.toMutableSet()
    klass.primaryConstructor?.parameters.orEmpty().filter { it.property }.forEach { existingProperties.add(it.name) }
    val seen = HashSet<String>()
    val result = ArrayList<CandidateMember>()
    for (node in nodes) {
        val declaration = node.declaration
        for (function in declaration.functions) {
            val key = functionKey(function, node.substitution)
            val abstractMember = isAbstractFunction(function, declaration.isInterface)
            val todoBody = !override || abstractMember
            if (!override && key !in bodies.listed) continue
            val overridable = overridableFunction(function, declaration.isInterface)
            if (!override && !overridable) continue
            if (!seen.add(key)) continue
            if (key in existingFunctions) continue
            if (!overridable) continue
            val finalOwner = if (override) null else bodies.finalIn[key]
            if (finalOwner != null) {
                result.add(disabled(function.name, node, "${function.name} is final in $finalOwner"))
                continue
            }
            result.add(functionCandidate(klass, context, node, function, key, todoBody, providers, !override))
        }
        for (property in declaration.properties) {
            val name = property.name ?: continue
            val key = propertyKey(property, node.substitution)
            val abstractMember = isAbstractProperty(property, declaration.isInterface)
            val todoBody = !override || abstractMember
            if (!override && key !in bodies.listed) continue
            val overridable = overridableProperty(property, declaration.isInterface)
            if (!override && !overridable) continue
            if (!seen.add(key)) continue
            if (name in existingProperties) continue
            if (!overridable) continue
            val finalOwner = if (override) null else bodies.finalIn[key]
            if (finalOwner != null) {
                result.add(disabled(name, node, "$name is final in $finalOwner"))
                continue
            }
            result.add(propertyCandidate(klass, context, node, property, key, todoBody, providers, !override))
        }
    }
    return result
}

private class MemberBodies(
    /** Member keys Implement lists: Kotlin requires the class to override them. */
    val listed: Set<String>,
    /** Listed keys a remaining class declaration makes final, to the class's name: no override compiles. */
    val finalIn: Map<String, String>,
)

private class WalkDeclaration(val hasBody: Boolean, val inClass: Boolean, val finalIn: String?)

/**
 * Members Kotlin requires the class to override (G6): take a member's non-`private` declarations
 * along the walk, one per declaration, and drop each one that another of them overrides. The member
 * is implemented when exactly one of the remaining declarations has a body and it is in a class or
 * is the only one remaining; otherwise it is listed. `private` declarations are not declarations
 * here, so they implement nothing.
 */
private fun memberBodies(nodes: List<WalkNode>, ancestors: Map<String, Set<String>>): MemberBodies {
    fun declarationId(node: WalkNode): String =
        (node.source?.path ?: "") + "@" + (node.source?.nameOffset ?: -1)

    val byKey = HashMap<String, LinkedHashMap<String, WalkDeclaration>>()
    for (node in nodes) {
        val interfaceMember = node.declaration.isInterface
        val id = declarationId(node)
        for (function in node.declaration.functions) {
            if ("private" in function.modifiers) continue
            val key = functionKey(function, node.substitution)
            byKey.getOrPut(key) { LinkedHashMap() }.putIfAbsent(
                id,
                WalkDeclaration(
                    !isAbstractFunction(function, interfaceMember),
                    !interfaceMember,
                    node.declaration.writtenName().takeUnless { overridableFunction(function, interfaceMember) },
                ),
            )
        }
        for (property in node.declaration.properties) {
            if (property.name == null || "private" in property.modifiers) continue
            val key = propertyKey(property, node.substitution)
            byKey.getOrPut(key) { LinkedHashMap() }.putIfAbsent(
                id,
                WalkDeclaration(
                    !isAbstractProperty(property, interfaceMember),
                    !interfaceMember,
                    node.declaration.writtenName().takeUnless { overridableProperty(property, interfaceMember) },
                ),
            )
        }
    }
    val byNodeKey = nodes.associateBy { it.key }
    val inherited = HashMap<String, MutableSet<String>>()
    for ((nodeKey, node) in byNodeKey) {
        val above = inherited.getOrPut(declarationId(node)) { HashSet() }
        for (ancestor in ancestors[nodeKey].orEmpty()) {
            byNodeKey[ancestor]?.let { above.add(declarationId(it)) }
        }
    }
    val listed = HashSet<String>()
    val finalIn = HashMap<String, String>()
    for ((key, declarations) in byKey) {
        val remaining = declarations.filter { (id, _) ->
            declarations.keys.none { other -> other != id && id in inherited[other].orEmpty() }
        }
        val bodies = remaining.values.filter { it.hasBody }
        val implemented = bodies.size == 1 && (bodies[0].inClass || remaining.size == 1)
        if (implemented) continue
        listed.add(key)
        remaining.values.firstNotNullOfOrNull { it.finalIn }?.let { finalIn[key] = it }
    }
    return MemberBodies(listed, finalIn)
}

private fun overridableFunction(function: SigFunction, interfaceMember: Boolean): Boolean {
    if ("private" in function.modifiers) return false
    if ("final" in function.modifiers) return false
    if (interfaceMember) return true
    return "open" in function.modifiers || "abstract" in function.modifiers || "override" in function.modifiers
}

private fun isAbstractFunction(function: SigFunction, interfaceMember: Boolean): Boolean {
    if ("abstract" in function.modifiers) return true
    return interfaceMember && !function.hasBody
}

private fun overridableProperty(property: KtProperty, interfaceMember: Boolean): Boolean {
    if ("private" in property.modifiers) return false
    if ("final" in property.modifiers) return false
    if (interfaceMember) return true
    return "open" in property.modifiers || "abstract" in property.modifiers || "override" in property.modifiers
}

private fun isAbstractProperty(property: KtProperty, interfaceMember: Boolean): Boolean {
    if ("abstract" in property.modifiers) return true
    return interfaceMember && property.initializer == null && !property.hasCustomGetter
}

private fun functionCandidate(
    klass: KtClazz,
    context: GenerateContext,
    node: WalkNode,
    function: SigFunction,
    key: String,
    abstractMember: Boolean,
    providers: Map<String, Set<String>>,
    checked: Boolean,
): CandidateMember {
    val memberTypeParameters = function.typeParameterNames.toSet()
    if (function.receiver != null) {
        return disabled(function.name, node, "member extensions are not generated")
    }
    if (function.returnType == null && function.expressionBody != null) {
        return disabled(function.name, node, "type not written")
    }
    val collision = node.substitution.values.filterNotNull()
        .flatMap(::simpleTypeNames)
        .firstOrNull { it in memberTypeParameters }
    if (collision != null) {
        return disabled(function.name, node, "member type parameter $collision is already used")
    }
    val signature = projectSignature(node, function, memberTypeParameters) ?: return disabled(
        function.name,
        node,
        functionSignatureReason(node, function, memberTypeParameters) ?: "cannot be generated",
    )
    val importResult = checkImports(klass, context, node, function, memberTypeParameters)
    if (importResult.reason != null) return disabled(function.name, node, importResult.reason)
    val owner = providers[key]?.takeIf { it.size > 1 }?.let { node.directName }
    val superPrefix = if (owner != null) "super<$owner>" else "super"
    val lines = if (abstractMember) {
        listOf("override $signature {", "    $TODO_BODY", "}")
    } else {
        val arguments = function.parameters.joinToString(", ") {
            if (it.vararg) "*${it.nameToken.text}" else it.nameToken.text
        }
        val call = "$superPrefix.${function.nameToken.text}($arguments)"
        val returnsUnit = function.returnType == null || function.returnType == "Unit"
        if (returnsUnit) {
            listOf("override $signature {", "    $call", "}")
        } else {
            listOf("override $signature {", "    return $call", "}")
        }
    }
    val caretLine = if (abstractMember) 1 else 1
    val option = MemberOption(
        text = signature,
        group = node.declaration.writtenName(),
        checked = checked,
    )
    return CandidateMember(option, signature, lines, importResult.imports, caretLine, 4)
}

private fun functionSignatureReason(node: WalkNode, function: SigFunction, memberTypeParameters: Set<String>): String? {
    for (parameter in function.parameters) {
        val type = parameter.type ?: continue
        val result = substitute(type, node.substitution, memberTypeParameters)
        if (result.text == null) return result.reason
    }
    return null
}

private fun disabled(name: String, node: WalkNode, reason: String): CandidateMember = CandidateMember(
    MemberOption(text = name, group = node.declaration.writtenName(), disabledReason = reason),
    null,
    null,
    emptyList(),
    0,
    0,
)

private fun projectSignature(node: WalkNode, function: SigFunction, memberTypeParameters: Set<String>): String? {
    val substitutedParameter = function.parameters.map { parameter ->
        val type = parameter.type ?: return null
        substitute(type, node.substitution, memberTypeParameters).text ?: return null
    }
    val typeParameters = function.typeParametersText?.let {
        substitute(it, node.substitution, memberTypeParameters).text ?: return null
    }
    val receiver = function.receiver?.let { substitute(it, node.substitution, memberTypeParameters).text ?: return null }
    val returnType = function.returnType?.let { substitute(it, node.substitution, memberTypeParameters).text ?: return null }
    val where = function.whereText?.let { substitute(it, node.substitution, memberTypeParameters).text ?: return null }
    val kept = KEPT_MODIFIERS.filter { it in function.modifiers }
    val builder = StringBuilder()
    if (kept.isNotEmpty()) builder.append(kept.joinToString(" ")).append(' ')
    builder.append("fun ")
    if (typeParameters != null) builder.append(typeParameters).append(' ')
    if (receiver != null) builder.append(receiver).append('.')
    builder.append(function.nameToken.text).append('(')
    builder.append(
        function.parameters.mapIndexed { index, parameter -> parameterText(parameter, substitutedParameter[index]) }
            .joinToString(", "),
    )
    builder.append(')')
    if (returnType != null) builder.append(": ").append(returnType)
    if (where != null) builder.append(' ').append(where)
    return builder.toString()
}

private val KEPT_MODIFIERS = listOf("suspend", "operator", "infix")

private fun parameterText(parameter: SigParameter, type: String): String =
    (if (parameter.vararg) "vararg " else "") + parameter.nameToken.text + ": " + type

private fun propertyCandidate(
    klass: KtClazz,
    context: GenerateContext,
    node: WalkNode,
    property: KtProperty,
    key: String,
    abstractMember: Boolean,
    providers: Map<String, Set<String>>,
    checked: Boolean,
): CandidateMember {
    val name = property.name ?: return disabled("", node, "unusable")
    if (property.receiver != null) return disabled(name, node, "member extensions are not generated")
    val type = property.typeText ?: return disabled(name, node, "type not written")
    val substituted = substitute(type, node.substitution, emptySet()).text ?: return disabled(name, node, "cannot be generated")
    val importResult = checkPropertyImports(klass, context, node, property)
    if (importResult.reason != null) return disabled(name, node, importResult.reason)
    val writtenName = property.nameToken?.text ?: name
    val signature = "${if (property.isVar) "var" else "val"} $writtenName: $substituted"
    val owner = providers[key]?.takeIf { it.size > 1 }?.let { node.directName }
    val superPrefix = if (owner != null) "super<$owner>" else "super"
    val lines = ArrayList<String>()
    lines.add("override $signature")
    val getter = if (abstractMember) "get() = $TODO_BODY" else "get() = $superPrefix.$writtenName"
    lines.add("    $getter")
    if (property.isVar) {
        if (abstractMember) {
            lines.add("    set(value) {}")
        } else {
            lines.add("    set(value) {")
            lines.add("        $superPrefix.$writtenName = value")
            lines.add("    }")
        }
    }
    val option = MemberOption(text = signature, group = node.declaration.writtenName(), checked = checked)
    return CandidateMember(option, signature, lines, importResult.imports, 1, 4)
}

private class ImportCheck(val imports: List<String>, val reason: String?)

private fun checkImports(
    klass: KtClazz,
    context: GenerateContext,
    node: WalkNode,
    function: SigFunction,
    memberTypeParameters: Set<String>,
): ImportCheck {
    val declaration = node.declaration
    val types = ArrayList<String>()
    function.receiver?.let { types.add(it) }
    function.typeParametersText?.let { types.add(it) }
    function.parameters.forEach { it.type?.let { type -> types.add(type) } }
    function.returnType?.let { types.add(it) }
    function.whereText?.let { types.add(it) }
    val offset = declaration.nameToken?.start ?: 0
    return checkTypeImports(klass, context, node, types, memberTypeParameters, offset)
}

private fun checkPropertyImports(
    klass: KtClazz,
    context: GenerateContext,
    node: WalkNode,
    property: KtProperty,
): ImportCheck {
    val types = listOfNotNull(property.typeText)
    val offset = node.declaration.nameToken?.start ?: 0
    return checkTypeImports(klass, context, node, types, emptySet(), offset)
}

private fun checkTypeImports(
    klass: KtClazz,
    context: GenerateContext,
    node: WalkNode,
    typeTexts: List<String>,
    memberTypeParameters: Set<String>,
    offset: Int,
): ImportCheck {
    val types = context.types ?: return ImportCheck(emptyList(), null)
    val targetParsed = parseImports(context.text)
    val sourceParsed = parseImports(node.source?.text ?: "")
    val targetUsed = usedIdentifiersIn(context.text, targetParsed)
    val imports = LinkedHashSet<String>()
    for (text in typeTexts) {
        for (name in simpleTypeNames(text)) {
            if (name in memberTypeParameters) continue
            if (name in node.substitution) continue
            if (klass.code.text.let { declaresSimpleName(it, name) }) continue
            val targetPackage = targetParsed.packageName
            val sourcePackage = sourceParsed.packageName
            val targetImports = targetParsed.imports.filter { !it.star && it.boundName == name }
            val sourceImports = sourceParsed.imports.filter { !it.star && it.boundName == name }
            // An added import outranks the target's star imports (G6).
            fun starRefusal(): String? {
                val star = targetParsed.imports.firstOrNull { it.star && name in targetUsed } ?: return null
                return "$name may come from import ${star.path}"
            }
            if (sourceImports.any { it.alias != null }) {
                return ImportCheck(emptyList(), "refers to another $name here")
            }
            val targetResolved = types.resolve(name, context.path, context.caretOffset)
            val sourceResolved = types.resolve(name, node.source?.path ?: context.path, offset)
            if (targetImports.isNotEmpty() && sourceImports.isNotEmpty() &&
                targetImports.any { t -> sourceImports.any { it.path == t.path } }
            ) {
                continue
            }
            when {
                sourceResolved.isNotEmpty() -> {
                    val sourceNames = sourceResolved.map { it.qualifiedName }.toSet()
                    if (targetResolved.isNotEmpty()) {
                        if (targetResolved.any { it.qualifiedName in sourceNames }) continue
                        return ImportCheck(emptyList(), "refers to another $name here")
                    }
                    if (targetImports.isNotEmpty()) {
                        if (targetImports.any { it.path in sourceNames }) continue
                        return ImportCheck(emptyList(), "refers to another $name here")
                    }
                    if (targetPackage == sourcePackage && sourceNames.singleOrNull()?.substringBeforeLast('.') == targetPackage) {
                        continue
                    }
                    starRefusal()?.let { return ImportCheck(emptyList(), it) }
                    imports.add(sourceNames.sorted().first())
                }
                sourceImports.isNotEmpty() -> {
                    val path = sourceImports.first().path
                    if (targetImports.isNotEmpty()) {
                        if (targetImports.any { it.path == path }) continue
                        return ImportCheck(emptyList(), "refers to another $name here")
                    }
                    if (targetResolved.isNotEmpty()) return ImportCheck(emptyList(), "refers to another $name here")
                    starRefusal()?.let { return ImportCheck(emptyList(), it) }
                    imports.add(path)
                }
                else -> {
                    val hasStar = sourceParsed.imports.any { it.star }
                    val aliased = sourceParsed.imports.any { it.alias != null }
                    if (targetResolved.isNotEmpty() || targetImports.isNotEmpty() || hasStar || aliased) {
                        return ImportCheck(emptyList(), "refers to another $name here")
                    }
                    // Nothing resolves it and no explicit import names it: a default import needs no line.
                }
            }
        }
    }
    return ImportCheck(imports.toList(), null)
}

private fun declaresSimpleName(text: String, name: String): Boolean {
    val escaped = Regex.escape(name)
    return Regex("""\b(?:class|interface|object|typealias)\s+`?$escaped`?(?![\w`])""").containsMatchIn(text)
}

internal fun simpleTypeNames(text: String): List<String> {
    val tokens = codeTokens(text)
    val names = ArrayList<String>()
    for ((index, token) in tokens.withIndex()) {
        if (token.type != KotlinTokenTypes.IDENTIFIER) continue
        val previous = tokens.getOrNull(index - 1)
        if (previous != null && previous.type == KotlinTokenTypes.DOT) continue
        val name = token.text.removeSurrounding("`")
        if (name.isEmpty()) continue
        if (index > 0 && (previous?.text == "@")) continue
        if (name.firstOrNull()?.isLowerCase() == true) continue
        names.add(name)
    }
    return names
}

private fun anyCandidates(
    klass: KtClazz,
    nodes: List<WalkNode>,
): List<CandidateMember> {
    // An interface may not implement a member of Any, so Kotlin rejects an override there.
    if (klass.isInterface) return emptyList()
    val declared = HashSet<String>()
    var finalEquals = false
    var finalHash = false
    var finalToString = false
    for (node in nodes) {
        val names = node.declaration.functions.filter { it.container == SigContainer.CLASS }
        for (function in names) {
            declared.add(function.name)
            if ("final" in function.modifiers) {
                when (function.name) {
                    "equals" -> finalEquals = true
                    "hashCode" -> finalHash = true
                    "toString" -> finalToString = true
                }
            }
        }
    }
    klass.functions.forEach { declared.add(it.name) }
    val candidates = ArrayList<CandidateMember>()
    if (!finalEquals && "equals" !in declared) {
        candidates.add(
            CandidateMember(
                MemberOption(text = "fun equals(other: Any?): Boolean", group = "Any"),
                "equals(other: Any?): Boolean",
                listOf("override fun equals(other: Any?): Boolean {", "    return super.equals(other)", "}"),
                emptyList(),
                1,
                4,
            ),
        )
    }
    if (!finalHash && "hashCode" !in declared) {
        candidates.add(
            CandidateMember(
                MemberOption(text = "fun hashCode(): Int", group = "Any"),
                "hashCode(): Int",
                listOf("override fun hashCode(): Int {", "    return super.hashCode()", "}"),
                emptyList(),
                1,
                4,
            ),
        )
    }
    if (!finalToString && "toString" !in declared) {
        candidates.add(
            CandidateMember(
                MemberOption(text = "fun toString(): String", group = "Any"),
                "toString(): String",
                listOf("override fun toString(): String {", "    return super.toString()", "}"),
                emptyList(),
                1,
                4,
            ),
        )
    }
    return candidates
}

private fun applyWithImports(
    klass: KtClazz,
    context: GenerateContext,
    members: List<GeneratedMember>,
    imports: List<String>,
): RefactoringEdit? {
    if (members.isEmpty()) return RefactoringEdit(emptyList(), context.caretOffset)
    val placement = placeMembers(klass.code, klass, context.caretOffset, members)
    if (placement !is Placement.At) return null
    val edits = ArrayList<stride.lang.TextEdit>()
    edits.add(stride.lang.TextEdit(placement.offset, placement.end, placement.text))
    edits.addAll(importEdits(klass.code, imports))
    return RefactoringEdit(edits, placement.caret)
}

/** Import line inserts in the shape of the import quick fix, one edit per insertion offset. */
internal fun importEdits(code: KotlinCode, fqns: List<String>): List<stride.lang.TextEdit> {
    val parsed = parseImports(code.text)
    val missing = fqns.distinct().sorted().filter { fqn -> parsed.imports.none { it.path == fqn && !it.star } }
    if (missing.isEmpty()) return emptyList()
    val lineBreak = if (code.text.contains("\r\n")) "\r\n" else "\n"
    if (parsed.imports.isEmpty()) {
        val packageEnd = parsed.packageLineEnd
        return if (parsed.packageName != null && packageEnd != null) {
            listOf(
                stride.lang.TextEdit(
                    packageEnd,
                    packageEnd,
                    lineBreak + lineBreak + missing.joinToString(lineBreak) { "import $it" },
                ),
            )
        } else {
            listOf(
                stride.lang.TextEdit(0, 0, missing.joinToString(lineBreak) { "import $it" } + lineBreak + lineBreak),
            )
        }
    }
    val grouped = LinkedHashMap<Int, MutableList<String>>()
    for (fqn in missing) {
        val after = parsed.imports.firstOrNull { it.path > fqn }
        val offset = after?.start ?: parsed.imports.last().end
        grouped.getOrPut(offset) { ArrayList() }.add(fqn)
    }
    return grouped.map { (offset, names) ->
        stride.lang.TextEdit(offset, offset, names.joinToString("") { "import $it$lineBreak" })
    }
}
