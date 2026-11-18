package stride.lang.kotlin.generate

import stride.lang.GenerateContext
import stride.lang.GenerateKind
import stride.lang.GenerateProvider
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit
import stride.lang.kotlin.refactor.kotlinCodeFor

/**
 * Generate for Kotlin: the class at the caret gets a secondary constructor, equals/hashCode,
 * toString, or checked overrides and implementations (G2–G6 of `docs/api-app.md`).
 */
class KotlinGenerate : GenerateProvider {
    override fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep? {
        val code = kotlinCodeFor(context.text)
        if (!code.bracketsMatch) return RefactoringStep.Refused("Brackets do not match in this file")
        val klass = classAt(code, context.caretOffset) ?: return RefactoringStep.Refused("No class at the caret")
        placementRefusal(code, klass, context.caretOffset)?.let { return RefactoringStep.Refused(it) }
        return when (kind) {
            GenerateKind.SECONDARY_CONSTRUCTOR -> secondaryConstructor(klass, context)
            GenerateKind.EQUALS_HASH_CODE -> equalsHashCode(klass, context)
            GenerateKind.TO_STRING -> toStringStep(klass, context)
            GenerateKind.OVERRIDE_METHODS -> overrideMethods(klass, context)
            GenerateKind.IMPLEMENT_METHODS -> implementMethods(klass, context)
            else -> null
        }
    }

    internal companion object {
        /** The single insert of [members] at the G3 placement, plus the caret after the edits. */
        fun applyMembers(klass: KtClazz, context: GenerateContext, members: List<GeneratedMember>): RefactoringEdit? {
            val placement = placeMembers(klass.code, klass, context.caretOffset, members)
            if (placement !is Placement.At) return null
            return RefactoringEdit(
                edits = listOf(TextEdit(placement.offset, placement.end, placement.text)),
                caret = placement.caret,
            )
        }
    }
}

/** The one line a refused kind shows, or `null` when the class allows [kind] (G2). */
internal fun availabilityRefusal(klass: KtClazz, kind: GenerateKind): String? {
    fun refusal(target: String): String? = "${kind.title} is not available in $target"
    return when (kind) {
        GenerateKind.SECONDARY_CONSTRUCTOR -> when {
            klass.isInterface -> refusal("an interface")
            klass.isAnnotation -> refusal("an annotation class")
            klass.isObject -> refusal("an object")
            klass.primaryConstructor != null -> refusal("a class with a primary constructor")
            else -> null
        }
        GenerateKind.EQUALS_HASH_CODE -> when {
            klass.isAnnotation -> refusal("an annotation class")
            klass.isInterface -> refusal("an interface")
            klass.isObject -> refusal("an object")
            klass.isEnum -> refusal("an enum class")
            klass.isValue -> refusal("a value class")
            else -> null
        }
        GenerateKind.TO_STRING -> when {
            klass.isAnnotation -> refusal("an annotation class")
            klass.isInterface -> refusal("an interface")
            else -> null
        }
        GenerateKind.OVERRIDE_METHODS -> if (klass.isAnnotation) refusal("an annotation class") else null
        GenerateKind.IMPLEMENT_METHODS -> when {
            klass.isAnnotation -> refusal("an annotation class")
            klass.isInterface -> refusal("an interface")
            else -> null
        }
        else -> null
    }
}
