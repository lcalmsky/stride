package stride.lang.kotlin.refactor

import stride.lang.RefactoringContext
import stride.lang.RefactoringKind
import stride.lang.RefactoringProvider
import stride.lang.RefactoringStep

/** Introduce Variable, Extract Method and Inline for Kotlin; the rules are in `docs/api-app.md` 「Refactorings」. */
class KotlinRefactorings : RefactoringProvider {
    override fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep? = when (kind) {
        RefactoringKind.INTRODUCE_VARIABLE -> KotlinIntroduceVariable.prepare(context)
        RefactoringKind.EXTRACT_METHOD, RefactoringKind.INLINE -> null
    }
}
