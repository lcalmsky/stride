package stride.app.problems

import stride.lang.ProblemSeverity

/** F2/⇧F2 and ⌘F1 over one file's problems; a record without an offset is not reachable. */
object ProblemNavigation {
    /**
     * The next problem in [forward] direction: errors first when the file has one, otherwise
     * warnings. Forward takes the first start after [caret] and wraps to the top; backward takes
     * the last start before [caret] and wraps to the bottom.
     */
    fun target(problems: List<FileProblem>, caret: Int, forward: Boolean): FileProblem? {
        val reachable = problems.filter { it.start >= 0 }.sortedWith(PROBLEM_ORDER)
        if (reachable.isEmpty()) return null
        val errors = reachable.filter { it.severity == ProblemSeverity.ERROR }
        val walking = if (errors.isNotEmpty()) errors else reachable
        return if (forward) {
            walking.firstOrNull { it.start > caret } ?: walking.first()
        } else {
            walking.lastOrNull { it.start < caret } ?: walking.last()
        }
    }

    /** Every problem the caret sits in, `start ≤ caret ≤ end`. */
    fun at(problems: List<FileProblem>, caret: Int): List<FileProblem> =
        problems.filter { it.start >= 0 && caret in it.start..it.end }
            .sortedWith(PROBLEM_ORDER)

    private val PROBLEM_ORDER: Comparator<FileProblem> =
        compareBy({ it.start }, { it.severity.ordinal })
}
