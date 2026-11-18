package stride.lang.kotlin.generate

import stride.lang.GenerateContext
import stride.lang.GenerateKind
import stride.lang.MemberOption
import stride.lang.NameCheck
import stride.lang.RefactoringEdit
import stride.lang.RefactoringOption
import stride.lang.RefactoringStep
import stride.lang.kotlin.refactor.FunctionContainer
import stride.lang.kotlin.refactor.KtParameter
import stride.lang.kotlin.refactor.KotlinCode
import stride.lang.kotlin.refactor.isNameToken

/** A property the secondary constructor can assign, with the reason it cannot when it cannot. */
internal class ConstructorCandidate(
    val property: KtProperty,
    val name: String,
    val writtenName: String,
    val typeText: String?,
)

internal fun constructorCandidates(klass: KtClazz): List<ConstructorCandidate> {
    val assignedInInit = assignedInInitBlocks(klass)
    val result = ArrayList<ConstructorCandidate>()
    for (property in klass.properties) {
        val name = property.name ?: continue
        if (property.receiver != null || property.delegated || property.hasCustomGetter) continue
        if (assignedInInit.contains(name)) continue
        if (!property.isVar && property.initializer != null) continue
        if (property.const) continue
        result.add(ConstructorCandidate(property, name, property.nameToken?.text ?: name, property.typeText))
    }
    return result
}

private fun assignedInInitBlocks(klass: KtClazz): Set<String> {
    val result = HashSet<String>()
    for (member in klass.members) {
        if (member.kind != MemberKind.INIT) continue
        val tokens = klass.code.code.filter { it.start >= member.start && it.end <= member.end }
        for ((index, token) in tokens.withIndex()) {
            if (!isNameToken(token) && token.text != "this") continue
            if (token.text == "this") continue
            val next = tokens.getOrNull(index + 1) ?: continue
            if (next.text == "=") result.add(token.text.removeSurrounding("`"))
        }
    }
    return result
}

/** One constructor of the class's class supertype, offered by the super constructor chooser. */
internal class SuperConstructorChoice(
    val text: String,
    val parameters: List<KtParameter>,
    val varargNames: Set<String>,
)

internal sealed interface SuperConstructorLookup {
    object None : SuperConstructorLookup

    class Chosen(val choices: List<SuperConstructorChoice>) : SuperConstructorLookup

    class Refused(val reason: String) : SuperConstructorLookup
}

private fun superConstructors(klass: KtClazz, context: GenerateContext): SuperConstructorLookup {
    val ofClass = klass.supertypes.filter { !it.hasCall }
    if (ofClass.isEmpty()) return SuperConstructorLookup.None
    val types = context.types ?: return SuperConstructorLookup.Refused(INDEXING)
    for (supertype in ofClass) {
        val sources = types.resolve(supertype.written, context.path, supertype.offset)
        if (sources.isEmpty()) return SuperConstructorLookup.Refused("${supertype.simpleName} is not in project sources")
        var found: KtClazz? = null
        for (source in sources) {
            val declaration = classInSource(source.text, source.nameOffset) ?: continue
            if (declaration.isInterface) continue
            found = declaration
            break
        }
        if (found == null) continue
        val choices = ArrayList<SuperConstructorChoice>()
        val primary = found.primaryConstructor
        if (primary != null) {
            choices.add(
                SuperConstructorChoice(
                    "${supertype.simpleName}(${primary.parameters.joinToString(", ") { parameterText(it.name, it.type, false) }})",
                    primary.parameters.map { KtParameter(it.name, it.nameToken, it.type, false, null) },
                    emptySet(),
                ),
            )
        }
        for (constructor in found.ownSecondaryConstructors) {
            choices.add(
                SuperConstructorChoice(
                    "${supertype.simpleName}(${constructor.parameters.joinToString(", ") { parameterText(it.name, it.type, it.vararg) }})",
                    constructor.parameters,
                    constructor.parameters.filter { it.vararg }.map { it.name }.toSet(),
                ),
            )
        }
        if (choices.isEmpty()) {
            choices.add(SuperConstructorChoice("${supertype.simpleName}()", emptyList(), emptySet()))
        }
        return SuperConstructorLookup.Chosen(choices)
    }
    return SuperConstructorLookup.None
}

private fun parameterText(name: String, type: String?, vararg: Boolean): String =
    (if (vararg) "vararg " else "") + name + (type?.let { ": $it" } ?: "")

private const val INDEXING = "Indexing… try again in a moment"

internal fun secondaryConstructor(klass: KtClazz, context: GenerateContext): RefactoringStep {
    availabilityRefusal(klass, GenerateKind.SECONDARY_CONSTRUCTOR)?.let { return RefactoringStep.Refused(it) }
    val types = context.types ?: return RefactoringStep.Refused(INDEXING)
    val name = klass.writtenName()
    if (klass.supertypes.any { it.hasCall }) {
        val initialized = klass.supertypes.first { it.hasCall }
        return RefactoringStep.Refused("$name initializes ${initialized.simpleName}() in its header")
    }
    for (nested in klass.nestedClasses) {
        if (nested.isCompanion && nested.functions.any { it.name == "invoke" && "operator" in it.modifiers }) {
            return RefactoringStep.Refused("$name's companion invoke() would lose its calls")
        }
    }
    if (hasTopLevelFunction(klass, context, types)) {
        return RefactoringStep.Refused("A top-level function $name(…) exists")
    }

    val superLookup = superConstructors(klass, context)
    if (superLookup is SuperConstructorLookup.Refused) return RefactoringStep.Refused(superLookup.reason)
    val candidates = constructorCandidates(klass)
    val chooser = { superChoice: SuperConstructorChoice? ->
        constructorChooser(klass, context, candidates, superChoice)
    }
    return when (superLookup) {
        is SuperConstructorLookup.Chosen -> RefactoringStep.Choose(
            title = "Choose Super Constructor",
            options = superLookup.choices.map { choice ->
                RefactoringOption(choice.text) { chooser(choice) }
            },
        )
        else -> chooser(null)
    }
}

private fun hasTopLevelFunction(klass: KtClazz, context: GenerateContext, types: stride.lang.ProjectTypes): Boolean {
    val local = klass.code.functions.any { it.container == FunctionContainer.TOP_LEVEL && it.name == klass.name }
    return local || types.hasTopLevelFunction(klass.name)
}

private fun constructorChooser(
    klass: KtClazz,
    context: GenerateContext,
    candidates: List<ConstructorCandidate>,
    superChoice: SuperConstructorChoice?,
): RefactoringStep {
    if (candidates.isEmpty()) {
        val edit = constructorEdit(klass, context, emptyList(), superChoice)
            ?: return RefactoringStep.Refused("No class at the caret")
        return when (edit) {
            is ConstructorEdit.Refused -> RefactoringStep.Refused(edit.reason)
            is ConstructorEdit.Ready -> RefactoringStep.Apply(edit.edit)
        }
    }
    val options = candidates.map { candidate ->
        MemberOption(
            text = candidate.name + (candidate.typeText?.let { ": $it" } ?: ""),
            checked = candidate.typeText != null,
            disabledReason = if (candidate.typeText == null) "type not written" else null,
        )
    }
    return RefactoringStep.ChooseMembers(
        title = "Choose Properties to Initialize",
        members = options,
        complete = { checked ->
            val selected = options.withIndex()
                .filter { (_, option) -> checked.any { it === option } }
                .mapNotNull { (index, _) -> candidates.getOrNull(index) }
            when (val edit = constructorEdit(klass, context, selected, superChoice)) {
                null -> NameCheck.Invalid("No class at the caret")
                is ConstructorEdit.Refused -> NameCheck.Invalid(edit.reason)
                is ConstructorEdit.Ready -> NameCheck.Valid(edit.edit)
            }
        },
    )
}

private sealed interface ConstructorEdit {
    class Ready(val edit: RefactoringEdit) : ConstructorEdit

    class Refused(val reason: String) : ConstructorEdit
}

private fun constructorEdit(
    klass: KtClazz,
    context: GenerateContext,
    checked: List<ConstructorCandidate>,
    superChoice: SuperConstructorChoice?,
): ConstructorEdit? {
    val noExplicitConstructor = klass.primaryConstructor == null && klass.ownSecondaryConstructors.isEmpty()
    val superParameters = superChoice?.parameters.orEmpty()
    val allTypes = superParameters.mapNotNull { it.type } + checked.mapNotNull { it.typeText }
    val argumentCount = allTypes.size

    if (noExplicitConstructor && argumentCount == 0) {
        val line = if (superChoice == null) "constructor()" else "constructor() : super()"
        val edit = KotlinGenerate.applyMembers(
            klass,
            context,
            listOf(GeneratedMember(listOf(line), 0, "constructor(".length)),
        ) ?: return null
        return ConstructorEdit.Ready(edit)
    }

    for (constructor in klass.ownSecondaryConstructors) {
        val existing = constructor.parameters.map { it.type ?: "" }
        if (existing.map(::normalizeType) == allTypes.map(::normalizeType)) {
            return ConstructorEdit.Refused("constructor(${allTypes.joinToString(", ")}) already exists")
        }
    }
    val existing = ArrayList<List<KtParameter>>()
    klass.ownSecondaryConstructors.forEach { existing.add(it.parameters) }
    if (noExplicitConstructor) existing.add(emptyList())
    for (parameters in existing) {
        if (callableWith(parameters, argumentCount)) {
            val plural = if (argumentCount == 1) "argument" else "arguments"
            return ConstructorEdit.Refused(
                "${klass.writtenName()}(…) with $argumentCount $plural would call the new constructor",
            )
        }
    }

    val members = ArrayList<GeneratedMember>()
    if (noExplicitConstructor) {
        val preserved = if (superChoice == null) "constructor()" else "constructor() : super()"
        members.add(GeneratedMember(listOf(preserved), 0, "constructor(".length))
    }

    val used = LinkedHashSet<String>()
    superParameters.forEach { used.add(it.name) }
    val parameters = ArrayList<String>()
    val superArguments = ArrayList<String>()
    for (parameter in superParameters) {
        parameters.add(parameterText(parameter.name, parameter.type, parameter.vararg))
        superArguments.add(if (parameter.name in superChoice!!.varargNames) "*${parameter.name}" else parameter.name)
    }
    val assignments = ArrayList<String>()
    for (candidate in checked) {
        val parameterName = uniqueName(candidate.name, used)
        used.add(parameterName)
        val writtenParameter = if (parameterName == candidate.name) candidate.writtenName else parameterName
        parameters.add(writtenParameter + ": " + candidate.typeText)
        if (parameterName == candidate.name) {
            assignments.add("this.${candidate.writtenName} = ${candidate.writtenName}")
        } else {
            assignments.add("this.${candidate.writtenName} = $parameterName")
        }
    }
    val delegation = if (superChoice == null) "" else " : super(${superArguments.joinToString(", ")})"
    val parameterText = parameters.joinToString(", ")
    val lines = ArrayList<String>()
    lines.add("constructor($parameterText)$delegation" + if (assignments.isEmpty()) "" else " {")
    if (assignments.isNotEmpty()) {
        assignments.forEach { lines.add("    $it") }
        lines.add("}")
    }
    val caretLine = if (assignments.isEmpty()) 0 else 1
    val caretColumn = if (assignments.isEmpty()) "constructor(".length + parameterText.length else 4
    members.add(GeneratedMember(lines, caretLine, caretColumn))
    KotlinGenerate.applyMembers(klass, context, members)?.let { return ConstructorEdit.Ready(it) }
    return null
}

private fun callableWith(parameters: List<KtParameter>, arguments: Int): Boolean {
    val vararg = parameters.any { it.vararg }
    val required = parameters.count { it.defaultRange == null && !it.vararg }
    return arguments >= required && (arguments <= parameters.size || vararg)
}

private fun normalizeType(text: String): String = text.filterNot { it.isWhitespace() }
