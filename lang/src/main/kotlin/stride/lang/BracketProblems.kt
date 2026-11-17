package stride.lang

/**
 * Matches the brackets a lexer has already classified as code, in document order, and reports the
 * ones that do not pair up. Brackets inside strings, characters and comments never reach this
 * object; a higher-level provider drops them before calling [find].
 */
object BracketProblems {
    private val OPENERS: Set<Char> = setOf('(', '[', '{')

    private val CLOSING: Map<Char, Char> = mapOf(')' to '(', ']' to '[', '}' to '{')

    /**
     * Problems for [brackets], each a (offset, bracket character) pair, sorted by start. A closing
     * bracket with no matching opener is `Unexpected ')'`; an opener still on the stack when a
     * matching closer arrives, or when the input ends, is `Unclosed '('`. Every problem is an
     * [ProblemSeverity.ERROR] over `[offset, offset + 1)`.
     */
    fun find(brackets: List<Pair<Int, Char>>): List<Problem> {
        val stack = ArrayList<Pair<Int, Char>>()
        val problems = ArrayList<Problem>()
        for ((offset, bracket) in brackets) {
            if (bracket in OPENERS) {
                stack.add(offset to bracket)
                continue
            }
            val opener = CLOSING[bracket] ?: continue
            val match = stack.indexOfLast { it.second == opener }
            if (match < 0) {
                problems.add(unexpected(offset, bracket))
                continue
            }
            for (index in stack.lastIndex downTo match + 1) {
                problems.add(unclosed(stack[index]))
            }
            stack.subList(match, stack.size).clear()
        }
        for (entry in stack) problems.add(unclosed(entry))
        return problems.sortedBy { it.start }
    }

    private fun unexpected(offset: Int, bracket: Char): Problem =
        Problem(offset, offset + 1, ProblemSeverity.ERROR, "Unexpected '$bracket'")

    private fun unclosed(entry: Pair<Int, Char>): Problem =
        Problem(entry.first, entry.first + 1, ProblemSeverity.ERROR, "Unclosed '${entry.second}'")
}
