package stride.lang.kotlin.generate

import stride.lang.GenerateContext
import stride.lang.GenerateKind
import stride.lang.MemberOption
import stride.lang.NameCheck
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit

/** One property the equals/hashCode/toString chooser lists. */
internal class FieldCandidate(
    val name: String,
    val writtenName: String,
    val typeText: String?,
    val isVar: Boolean,
) {
    val array: Boolean get() = typeText?.let(::isArrayType) == true

    val nestedArray: Boolean get() = typeText?.let(::isNestedArrayType) == true

    val nullable: Boolean get() = typeText?.endsWith("?") == true

    val string: Boolean get() = typeText?.removeSuffix("?") == "String"

    val intLike: Boolean get() = typeText?.removeSuffix("?") == "Int"
}

/** Every property with a backing field, in declaration order, primary constructor ones first. */
internal fun fieldCandidates(klass: KtClazz): List<FieldCandidate> {
    val result = ArrayList<FieldCandidate>()
    klass.primaryConstructor?.parameters?.filter { it.property }?.forEach { parameter ->
        result.add(
            FieldCandidate(
                parameter.name,
                parameter.nameToken.text,
                parameter.type,
                parameter.isVar,
            ),
        )
    }
    for (property in klass.properties) {
        if (property.receiver != null) continue
        if (property.delegated || property.hasCustomGetter) continue
        if (property.const || "abstract" in property.modifiers) continue
        if (property.initializer == null && !property.lateinit) continue
        val name = property.name ?: continue
        result.add(FieldCandidate(name, property.nameToken?.text ?: name, property.typeText, property.isVar))
    }
    return result
}

internal fun isArrayType(text: String): Boolean {
    val base = text.removeSuffix("?").trim()
    if (base.endsWith("Array") && base.removeSuffix("Array") in PRIMITIVE_ARRAY_ELEMENTS) return true
    return base.startsWith("Array<") && base.endsWith(">")
}

internal fun isNestedArrayType(text: String): Boolean {
    val base = text.removeSuffix("?").trim()
    if (!base.startsWith("Array<") || !base.endsWith(">")) return false
    return isArrayType(base.substring("Array<".length, base.length - 1))
}

private val PRIMITIVE_ARRAY_ELEMENTS = setOf("Int", "Long", "Short", "Byte", "Char", "Boolean", "Float", "Double")

private fun isEqualsOverride(function: SigFunction): Boolean =
    function.name == "equals" && function.receiver == null &&
        function.parameters.singleOrNull()?.type?.filterNot { it.isWhitespace() } in setOf("Any?", "kotlin.Any?")

private fun isHashCodeOverride(function: SigFunction): Boolean =
    function.name == "hashCode" && function.receiver == null && function.parameters.isEmpty()

private fun KtClazz.hasEquals(): Boolean = functions.any(::isEqualsOverride)

private fun KtClazz.hasHashCode(): Boolean = functions.any(::isHashCodeOverride)

private fun KtClazz.hasToString(): Boolean = functions.any { it.name == "toString" }

private fun className(klass: KtClazz): String = klass.writtenName()

/** What the project-source superclass chain says equals/hashCode must do (G5). */
internal class SuperClassFacts(
    val superEquals: Boolean,
    val superHash: Boolean,
    /** Whether a class along the chain declares a member named `javaClass`, hiding the class check. */
    val javaClassHidden: Boolean,
)

internal sealed interface SuperClassLookup {
    object None : SuperClassLookup

    class Found(val facts: SuperClassFacts) : SuperClassLookup

    class Refused(val reason: String) : SuperClassLookup
}

internal fun superClassFacts(klass: KtClazz, context: GenerateContext): SuperClassLookup {
    val types = context.types ?: return SuperClassLookup.None
    var superEquals = false
    var superHash = false
    var javaClassHidden = false
    val visited = HashSet<String>()
    val queue = ArrayDeque<Pair<KtClazz, String>>()
    queue.add(klass to context.path)
    while (queue.isNotEmpty()) {
        val (declaring, path) = queue.removeFirst()
        if (!visited.add(declaring.name + "@" + path)) continue
        for (supertype in declaring.supertypes) {
            // An unparenthesized supertype in a class without explicit constructors is an interface.
            if (!declaring.supertypeMayBeClass(supertype)) continue
            val sources = types.resolve(supertype.written, path, supertype.offset)
            if (sources.isEmpty()) {
                return SuperClassLookup.Refused("${supertype.simpleName} is not in project sources")
            }
            for (source in sources) {
                val declaration = classInSource(source.text, source.nameOffset) ?: continue
                if (declaration.isInterface) continue
                val equals = declaration.functions.firstOrNull(::isEqualsOverride)
                val hash = declaration.functions.firstOrNull(::isHashCodeOverride)
                if (equals != null && "final" in equals.modifiers) {
                    return SuperClassLookup.Refused("${declaration.writtenName()} declares equals() final")
                }
                if (hash != null && "final" in hash.modifiers) {
                    return SuperClassLookup.Refused("${declaration.writtenName()} declares hashCode() final")
                }
                if (equals != null) superEquals = true
                if (hash != null) superHash = true
                if (declaration.declaresMemberNamed("javaClass")) javaClassHidden = true
                queue.add(declaration to source.path)
            }
        }
    }
    return SuperClassLookup.Found(SuperClassFacts(superEquals, superHash, javaClassHidden))
}

internal fun equalsHashCode(
    klass: KtClazz,
    context: GenerateContext,
): RefactoringStep {
    availabilityRefusal(klass, GenerateKind.EQUALS_HASH_CODE)?.let { return RefactoringStep.Refused(it) }
    if (context.types == null && klass.supertypes.isNotEmpty()) {
        return RefactoringStep.Refused("Indexing… try again in a moment")
    }
    val name = className(klass)
    if (klass.hasEquals()) return RefactoringStep.Refused("equals() already exists in $name")
    if (klass.hasHashCode()) return RefactoringStep.Refused("hashCode() already exists in $name")

    val superLookup = superClassFacts(klass, context)
    if (superLookup is SuperClassLookup.Refused) return RefactoringStep.Refused(superLookup.reason)
    val facts = (superLookup as? SuperClassLookup.Found)?.facts
    val superEquals = facts?.superEquals == true
    val superHash = facts?.superHash == true
    val javaClassHidden = klass.declaresMemberNamed("javaClass") || facts?.javaClassHidden == true

    val candidates = fieldCandidates(klass)
    val options = candidates.map { candidate ->
        val disabled = if (candidate.typeText == null) "type not written" else null
        MemberOption(text = display(candidate), checked = disabled == null, disabledReason = disabled)
    }
    if (options.isEmpty()) {
        val edit = editFor(klass, context, emptyList(), superEquals, superHash, javaClassHidden)
        return edit?.let { RefactoringStep.Apply(it) } ?: RefactoringStep.Refused("No class at the caret")
    }
    return RefactoringStep.ChooseMembers(
        title = "equals() and hashCode()",
        members = options,
        complete = { checked ->
            completeEquals(klass, context, candidates, options, checked, superEquals, superHash, javaClassHidden)
        },
    )
}

private fun display(candidate: FieldCandidate): String =
    candidate.name + (candidate.typeText?.let { ": $it" } ?: "")

private fun completeEquals(
    klass: KtClazz,
    context: GenerateContext,
    candidates: List<FieldCandidate>,
    options: List<MemberOption>,
    checked: List<MemberOption>,
    superEquals: Boolean,
    superHash: Boolean,
    javaClassHidden: Boolean,
): NameCheck {
    val selected = options.withIndex()
        .filter { (_, option) -> checked.any { it === option } }
        .mapNotNull { (index, _) -> candidates.getOrNull(index) }
    val edit = editFor(klass, context, selected, superEquals, superHash, javaClassHidden)
        ?: return NameCheck.Invalid("No class at the caret")
    return NameCheck.Valid(edit)
}

private fun editFor(
    klass: KtClazz,
    context: GenerateContext,
    fields: List<FieldCandidate>,
    superEquals: Boolean,
    superHash: Boolean,
    javaClassHidden: Boolean,
): RefactoringEdit? {
    val members = listOf(
        GeneratedMember(withEqualsBody(klass, fields, superEquals, javaClassHidden), 1, 4),
        GeneratedMember(hashCodeLines(fields, superHash), 0, 4),
    )
    return KotlinGenerate.applyMembers(klass, context, members)
}

private fun withEqualsBody(
    klass: KtClazz,
    fields: List<FieldCandidate>,
    superEquals: Boolean,
    javaClassHidden: Boolean,
): List<String> {
    val parameter = uniqueName("other", fields.map { it.name }.toSet())
    val name = className(klass)
    val lines = ArrayList<String>()
    lines.add("override fun equals($parameter: Any?): Boolean {")
    lines.add("    if (this === $parameter) return true")
    lines.add(
        if (javaClassHidden) {
            "    if ($parameter == null || this::class != $parameter::class) return false"
        } else {
            "    if (javaClass != $parameter?.javaClass) return false"
        },
    )
    if (superEquals) lines.add("    if (!super.equals($parameter)) return false")
    if (fields.isNotEmpty()) {
        lines.add("")
        val wildcards = if (klass.typeParameters.isNotEmpty()) "<*>" else ""
        lines.add("    $parameter as $name$wildcards")
        lines.add("")
        for (field in fields) {
            val access = "$parameter.${field.writtenName}"
            val condition = when {
                field.nestedArray -> "!${field.writtenName}.contentDeepEquals($access)"
                field.array -> "!${field.writtenName}.contentEquals($access)"
                else -> "${field.writtenName} != $access"
            }
            lines.add("    if ($condition) return false")
        }
        lines.add("")
    }
    lines.add("    return true")
    lines.add("}")
    return lines
}

private fun hashCodeLines(fields: List<FieldCandidate>, superHash: Boolean): List<String> {
    val lines = ArrayList<String>()
    lines.add("override fun hashCode(): Int {")
    if (fields.isEmpty() && !superHash) {
        lines.add("    return javaClass.hashCode()")
    } else if (fields.isEmpty()) {
        lines.add("    return super.hashCode()")
    } else if (fields.size == 1 && !superHash) {
        lines.add("    return ${fieldHash(fields[0], parenthesized = false, nullableSuffix = true)}")
    } else {
        val local = uniqueName("result", fields.map { it.name }.toSet())
        if (superHash) {
            lines.add("    var $local = super.hashCode()")
            for (field in fields) {
                lines.add("    $local = 31 * $local + ${fieldHash(field, parenthesized = true, nullableSuffix = true)}")
            }
        } else {
            val first = fields.first()
            lines.add("    var $local = ${fieldHash(first, parenthesized = false, nullableSuffix = true)}")
            for (field in fields.drop(1)) {
                lines.add("    $local = 31 * $local + ${fieldHash(field, parenthesized = true, nullableSuffix = true)}")
            }
        }
        lines.add("    return $local")
    }
    lines.add("}")
    return lines
}

private fun fieldHash(field: FieldCandidate, parenthesized: Boolean, nullableSuffix: Boolean): String {
    val dot = if (field.nullable) "?." else "."
    val call = when {
        field.nestedArray -> "${field.writtenName}${dot}contentDeepHashCode()"
        field.array -> "${field.writtenName}${dot}contentHashCode()"
        field.intLike -> field.writtenName
        else -> "${field.writtenName}${dot}hashCode()"
    }
    if (!field.nullable || !nullableSuffix) return call
    return if (parenthesized) "($call ?: 0)" else "$call ?: 0"
}

/** `toString()` generation (G5). A supertype's own toString is never called. */
internal fun toStringStep(klass: KtClazz, context: GenerateContext): RefactoringStep {
    availabilityRefusal(klass, GenerateKind.TO_STRING)?.let { return RefactoringStep.Refused(it) }
    val name = className(klass)
    if (klass.hasToString()) return RefactoringStep.Refused("toString() already exists in $name")
    val candidates = fieldCandidates(klass)
    val options = candidates.map { candidate ->
        val disabled = if (candidate.typeText == null) "type not written" else null
        MemberOption(text = display(candidate), checked = disabled == null, disabledReason = disabled)
    }
    if (options.isEmpty()) {
        KotlinGenerate.applyMembers(klass, context, listOf(GeneratedMember(toStringLines(klass, emptyList()), 1, 4)))
            ?.let { return RefactoringStep.Apply(it) }
        return RefactoringStep.Refused("No class at the caret")
    }
    return RefactoringStep.ChooseMembers(
        title = "toString()",
        members = options,
        complete = { checked ->
            val selected = options.withIndex()
                .filter { (_, option) -> checked.any { it === option } }
                .mapNotNull { (index, _) -> candidates.getOrNull(index) }
            KotlinGenerate.applyMembers(klass, context, listOf(GeneratedMember(toStringLines(klass, selected), 1, 4)))
                ?.let { NameCheck.Valid(it) } ?: NameCheck.Invalid("No class at the caret")
        },
    )
}

private fun toStringLines(klass: KtClazz, fields: List<FieldCandidate>): List<String> {
    val name = className(klass)
    if (fields.isEmpty()) {
        return listOf("override fun toString(): String {", "    return \"$name()\"", "}")
    }
    val value = fields.joinToString(", ") { field ->
        val printed = when {
            field.nestedArray -> "\${${field.writtenName}.contentDeepToString()}"
            field.array -> "\${${field.writtenName}.contentToString()}"
            field.writtenName.startsWith("`") -> "\${${field.writtenName}}"
            else -> "\$${field.writtenName}"
        }
        val quoted = if (field.string && !field.nullable) "'$printed'" else printed
        "${field.name}=$quoted"
    }
    return listOf("override fun toString(): String {", "    return \"$name($value)\"", "}")
}

internal fun uniqueName(base: String, used: Set<String>): String {
    if (base !in used) return base
    var index = 1
    while ("$base$index" in used) index++
    return "$base$index"
}
