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
import stride.lang.kotlin.refactor.KotlinCode
import stride.lang.kotlin.refactor.isNameToken

/** Project sources for tests: a path → whole-file map resolved through package, imports and same package. */
internal class FakeProjectTypes(private val files: Map<String, String>) : ProjectTypes {
    override fun resolve(written: String, path: String, offset: Int): List<TypeSource> {
        val text = files[path] ?: ""
        if (text.isEmpty()) return emptyList()
        val imports = parseImports(text)
        val head = written.substringBefore('.')
        val rest = written.substringAfter('.', "")
        val result = ArrayList<TypeSource>()
        for ((filePath, content) in files) {
            val declared = declaredNames(content)
            if (head !in declared) continue
            if (!resolvesTo(filePath, content, path, imports, head)) continue
            val nameOffset = nameOffsetOf(content, head) ?: continue
            val packageName = parseImports(content).packageName
            val qualified = listOfNotNull(packageName, head).joinToString(".")
            result.add(TypeSource(qualified + if (rest.isEmpty()) "" else ".$rest", filePath, content, nameOffset))
        }
        return result
    }

    override fun hasTopLevelFunction(name: String): Boolean =
        files.values.any { content ->
            val tokens = codeTokens(content)
            tokens.windowed(3).any { window ->
                window[0].text == "fun" && window[1].text.removeSurrounding("`") == name &&
                    (window.getOrNull(2) == null || window[2].text == "(" || window[2].text == "<")
            }
        }

    private fun resolvesTo(
        candidatePath: String,
        candidateText: String,
        path: String,
        imports: stride.lang.kotlin.KotlinImportList,
        name: String,
    ): Boolean {
        if (candidatePath == path) return !imports.imports.any { !it.star && it.boundName == name }
        val explicit = imports.imports.firstOrNull { !it.star && it.boundName == name }
        val candidatePackage = parseImports(candidateText).packageName
        if (explicit != null) {
            return explicit.path == listOfNotNull(candidatePackage, name).joinToString(".")
        }
        if (imports.imports.any { it.star && it.path == listOfNotNull(candidatePackage, "*").joinToString(".") }) return true
        return imports.packageName == candidatePackage
    }

    private fun declaredNames(text: String): Set<String> =
        KotlinCode(text).let { code ->
            code.code.filter { it.type == KotlinTokenTypes.KEYWORD && it.text in setOf("class", "interface", "object") }
                .mapNotNull { token ->
                    val next = code.code.getOrNull(token.index + 1)
                    next?.takeIf { isNameToken(it) }?.text?.removeSurrounding("`")
                }
                .toSet()
        }

    private fun nameOffsetOf(text: String, name: String): Int? {
        val code = KotlinCode(text)
        for (token in code.code) {
            if (token.type != KotlinTokenTypes.KEYWORD) continue
            if (token.text !in setOf("class", "interface", "object")) continue
            val next = code.code.getOrNull(token.index + 1) ?: continue
            if (isNameToken(next) && next.text.removeSurrounding("`") == name) return next.start
        }
        return null
    }
}

internal class GenRun(
    val text: String?,
    val caret: Int?,
    val status: String?,
    val step: RefactoringStep,
)

internal fun prepare(kind: GenerateKind, marked: String, types: ProjectTypes? = null): RefactoringStep {
    val caret = marked.indexOf('|')
    val text = if (caret >= 0) marked.removeRange(caret, caret + 1) else marked
    val actual = if (caret >= 0) caret else (text.indexOf('{') + 1).takeIf { text.contains('{') } ?: text.length
    return KotlinGenerate().prepare(kind, GenerateContext(text, actual, "OrderService.kt", CURRENT_PATH, types))
        ?: stride.lang.RefactoringStep.Refused("provider did not handle $kind")
}

/** Follows chooses and the member chooser (all enabled rows), then applies the edit. */
internal fun run(
    kind: GenerateKind,
    marked: String,
    types: ProjectTypes? = null,
    selected: Set<String>? = null,
): GenRun {
    var step = prepare(kind, marked, types)
    if (step is RefactoringStep.Choose) {
        step = step.options.first().next()
    }
    if (step is RefactoringStep.ChooseMembers) {
        val checked = if (selected != null) {
            step.members.filter { it.text in selected }
        } else {
            step.members.filter { it.disabledReason == null && it.checked }
        }
        when (val check = step.complete(checked)) {
            is NameCheck.Valid -> step = RefactoringStep.Apply(check.edit)
            is NameCheck.Invalid -> return GenRun(null, null, check.message, step)
        }
    }
    return when (step) {
        is RefactoringStep.Apply -> {
            val caretOffset = marked.indexOf('|')
            val text = if (caretOffset >= 0) marked.removeRange(caretOffset, caretOffset + 1) else marked
            val applied = applyEdit(text, step.edit)
            GenRun(applied.first, applied.second, step.edit.message, step)
        }
        is RefactoringStep.Refused -> GenRun(null, null, step.reason, step)
        else -> GenRun(null, null, null, step)
    }
}

internal fun applyEdit(text: String, edit: RefactoringEdit): Pair<String, Int> {
    val builder = StringBuilder(text)
    for (change in edit.edits.sortedByDescending { it.start }) {
        builder.replace(change.start, change.end, change.replacement)
    }
    return builder.toString() to edit.caret
}

internal fun checkedAll(step: RefactoringStep.ChooseMembers, enabledOnly: Boolean = true): List<MemberOption> =
    step.members.filter { !enabledOnly || it.disabledReason == null }

internal fun chooseMembers(step: RefactoringStep.ChooseMembers, texts: Set<String>): NameCheck {
    val checked = step.members.filter { it.text in texts }
    return step.complete(checked)
}

internal const val CURRENT_PATH = "/project/src/main/kotlin/demo/OrderService.kt"

internal fun currentTypes(text: String, others: Map<String, String> = emptyMap()): FakeProjectTypes =
    FakeProjectTypes(others + (CURRENT_PATH to text))
