package stride.lang.kotlin

import stride.lang.BracketProblems
import stride.lang.Problem
import stride.lang.ProblemContext
import stride.lang.ProblemProvider
import stride.lang.ProblemSeverity

/** Bracket pairing and unused imports of a Kotlin file, judged from its tokens alone. */
class KotlinProblemProvider : ProblemProvider {
    override fun collect(context: ProblemContext): List<Problem> {
        val text = context.text
        val parsed = parseImports(text)
        return bracketProblems(text) + unusedImportProblems(text, parsed)
    }

    private fun bracketProblems(text: String): List<Problem> {
        val brackets = ArrayList<Pair<Int, Char>>()
        for (token in codeTokens(text)) {
            if (token.type == KotlinTokenTypes.BRACES ||
                token.type == KotlinTokenTypes.BRACKETS ||
                token.type == KotlinTokenTypes.PARENTHESES
            ) {
                brackets.add(token.start to token.text[0])
            }
        }
        return BracketProblems.find(brackets)
    }

    private fun unusedImportProblems(text: String, parsed: KotlinImportList): List<Problem> =
        unusedImports(text, parsed).map { import ->
            Problem(import.start, directiveEnd(text, import), ProblemSeverity.WARNING, UNUSED_IMPORT)
        }

    private fun directiveEnd(text: String, import: KotlinImport): Int {
        var end = import.end
        if (end > import.start && text[end - 1] == '\n') end--
        if (end > import.start && text[end - 1] == '\r') end--
        return end
    }

    private companion object {
        const val UNUSED_IMPORT = "Unused import directive"
    }
}
