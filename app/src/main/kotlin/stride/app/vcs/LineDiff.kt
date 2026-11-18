package stride.app.vcs

enum class HunkKind { ADDED, DELETED, MODIFIED }

/**
 * A changed run, with 0-based line indices into the old and new line lists: [oldStart] is the
 * first replaced old line and [newStart] the first replacement new line. An insertion has
 * `oldStart` at the line after which it appears and `oldCount` 0; a deletion has `newCount` 0.
 */
data class LineHunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int) {
    val kind: HunkKind
        get() = when {
            oldCount == 0 -> HunkKind.ADDED
            newCount == 0 -> HunkKind.DELETED
            else -> HunkKind.MODIFIED
        }
}

/** A line diff in process: Myers' algorithm, compared line by line, no git call. */
object LineDiff {

    const val DEFAULT_MAX_EDITS: Int = 5_000

    /** Splits on `\n`, dropping one trailing `\r` from each line, so CRLF checkouts compare equal. */
    fun split(text: String): List<String> = text.split('\n').map { if (it.endsWith('\r')) it.dropLast(1) else it }

    /** The separator [split] removed from [text]'s first line break; `\n` when it has none. */
    fun lineSeparator(text: String): String {
        val index = text.indexOf('\n')
        return if (index > 0 && text[index - 1] == '\r') "\r\n" else "\n"
    }

    fun join(lines: List<String>, separator: String): String = lines.joinToString(separator)

    /** The hunks from [old] to [new], or `null` when more than [maxEdits] lines change. */
    fun diff(old: List<String>, new: List<String>, maxEdits: Int = DEFAULT_MAX_EDITS): List<LineHunk>? {
        val hunks = ArrayList<LineHunk>()
        val budget = EditBudget(maxEdits)
        return try {
            diffRange(old, 0, old.size, new, 0, new.size, hunks, budget)
            merge(hunks)
        } catch (_: TooManyEdits) {
            null
        }
    }

    /**
     * [current] with [selected] hunks replaced by their base lines. Unselected hunks keep the
     * current lines and the unchanged runs are copied from [current]; inserted lines are joined
     * with [lineSeparator], the document's separator.
     */
    fun rollback(
        base: List<String>,
        current: List<String>,
        hunks: List<LineHunk>,
        selected: List<LineHunk>,
        lineSeparator: String,
    ): String {
        if (selected.isEmpty()) return join(current, lineSeparator)
        val chosen = selected.toHashSet()
        val output = ArrayList<String>(current.size)
        var newCursor = 0
        for (hunk in hunks.sortedBy { it.oldStart }) {
            for (index in newCursor until hunk.newStart.coerceAtLeast(newCursor)) output += current[index]
            if (hunk in chosen) {
                for (index in hunk.oldStart until hunk.oldStart + hunk.oldCount) output += base[index]
            } else {
                for (index in hunk.newStart until hunk.newStart + hunk.newCount) output += current[index]
            }
            newCursor = hunk.newStart + hunk.newCount
        }
        for (index in newCursor until current.size) output += current[index]
        return join(output, lineSeparator)
    }

    private fun diffRange(
        old: List<String>,
        oldStart: Int,
        oldEnd: Int,
        new: List<String>,
        newStart: Int,
        newEnd: Int,
        output: MutableList<LineHunk>,
        budget: EditBudget,
    ) {
        var a = oldStart
        var b = newStart
        var aEnd = oldEnd
        var bEnd = newEnd
        while (a < aEnd && b < bEnd && old[a] == new[b]) {
            a++
            b++
        }
        while (aEnd > a && bEnd > b && old[aEnd - 1] == new[bEnd - 1]) {
            aEnd--
            bEnd--
        }
        val removed = aEnd - a
        val added = bEnd - b
        if (removed == 0 && added == 0) return
        if (removed == 0 || added == 0 || removed == 1 || added == 1) {
            budget.spend(removed + added)
            output += LineHunk(a, removed, b, added)
            return
        }
        val split = bisect(old, a, aEnd, new, b, bEnd, budget.remaining)
        if (split == null || (split.x == a && split.y == b) || (split.x == aEnd && split.y == bEnd)) {
            budget.spend(removed + added)
            output += LineHunk(a, removed, b, added)
            return
        }
        diffRange(old, a, split.x, new, b, split.y, output, budget)
        diffRange(old, split.x, aEnd, new, split.y, bEnd, output, budget)
    }

    /**
     * The middle of the shortest edit path, found by walking the forward and reverse D-paths until
     * they overlap (Myers 1986, the bisect split of diff-match-patch). Linear memory.
     */
    private fun bisect(
        old: List<String>,
        oldStart: Int,
        oldEnd: Int,
        new: List<String>,
        newStart: Int,
        newEnd: Int,
        maxDiagonals: Int,
    ): Split? {
        val n = oldEnd - oldStart
        val m = newEnd - newStart
        val maxD = (n + m + 1) / 2
        val offset = maxD
        val length = 2 * maxD
        if (length <= offset) return null
        val forward = IntArray(length) { -1 }
        val reverse = IntArray(length) { -1 }
        forward[offset + 1] = 0
        reverse[offset + 1] = 0
        val delta = n - m
        val odd = delta % 2 != 0
        var forwardDown = 0
        var forwardUp = 0
        var reverseDown = 0
        var reverseUp = 0
        val diagonals = minOf(maxD, maxDiagonals + 1)
        for (distance in 0 until diagonals) {
            var k = -distance + forwardDown
            while (k <= distance - forwardUp) {
                val cell = offset + k
                var x = if (k == -distance || (k != distance && forward[cell - 1] < forward[cell + 1])) {
                    forward[cell + 1]
                } else {
                    forward[cell - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && old[oldStart + x] == new[newStart + y]) {
                    x++
                    y++
                }
                forward[cell] = x
                when {
                    x > n -> forwardUp += 2
                    y > m -> forwardDown += 2
                    odd -> {
                        val mirror = offset + delta - k
                        if (mirror in 0 until length && reverse[mirror] != -1) {
                            val reverseX = n - reverse[mirror]
                            if (x >= reverseX) return Split(oldStart + x, newStart + y)
                        }
                    }
                }
                k += 2
            }
            k = -distance + reverseDown
            while (k <= distance - reverseUp) {
                val cell = offset + k
                var x = if (k == -distance || (k != distance && reverse[cell - 1] < reverse[cell + 1])) {
                    reverse[cell + 1]
                } else {
                    reverse[cell - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && old[oldEnd - x - 1] == new[newEnd - y - 1]) {
                    x++
                    y++
                }
                reverse[cell] = x
                when {
                    x > n -> reverseUp += 2
                    y > m -> reverseDown += 2
                    !odd -> {
                        val mirror = offset + delta - k
                        if (mirror in 0 until length && forward[mirror] != -1) {
                            val forwardX = forward[mirror]
                            val forwardY = offset + forwardX - mirror
                            if (forwardX >= n - x) return Split(oldStart + forwardX, newStart + forwardY)
                        }
                    }
                }
                k += 2
            }
        }
        return null
    }

    /** Hunks that end where the next begins are one changed run, not two. */
    private fun merge(hunks: List<LineHunk>): List<LineHunk> {
        val merged = ArrayList<LineHunk>(hunks.size)
        for (hunk in hunks.sortedWith(compareBy({ it.oldStart }, { it.newStart }))) {
            val last = merged.lastOrNull()
            if (last != null && last.oldStart + last.oldCount == hunk.oldStart &&
                last.newStart + last.newCount == hunk.newStart
            ) {
                merged[merged.lastIndex] = LineHunk(
                    last.oldStart,
                    last.oldCount + hunk.oldCount,
                    last.newStart,
                    last.newCount + hunk.newCount,
                )
            } else {
                merged += hunk
            }
        }
        return merged
    }

    private data class Split(val x: Int, val y: Int)

    private class EditBudget(private val maximum: Int) {
        private var used = 0

        val remaining: Int get() = maximum - used

        fun spend(edits: Int) {
            used += edits
            if (used > maximum) throw TooManyEdits()
        }
    }

    private class TooManyEdits : RuntimeException()
}
