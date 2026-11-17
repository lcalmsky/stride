package stride.app.problems

/**
 * Maps a line of the text a build compiled onto the text shown now. Unchanged line numbers at
 * the top and the bottom keep their distance from the edges; a line inside the changed span is
 * mapped only when its text occurs exactly once on each side of that span, and otherwise maps to
 * `null`, which hides the message until the next build.
 */
class LineRemap private constructor(
    private val baseCount: Int,
    private val currentCount: Int,
    private val prefix: Int,
    private val suffix: Int,
    private val baseMiddle: List<String>,
    private val baseAnchors: Map<String, Int>,
    private val currentAnchors: Map<String, Int>,
) {
    fun map(line: Int): Int? {
        if (line < 0 || line >= baseCount) return null
        if (line < prefix) return line
        if (line >= baseCount - suffix) return line - baseCount + currentCount
        val text = baseMiddle[line - prefix]
        baseAnchors[text] ?: return null
        val target = currentAnchors[text] ?: return null
        return prefix + target
    }

    companion object {
        fun of(base: List<String>, current: List<String>): LineRemap {
            val limit = minOf(base.size, current.size)
            var prefix = 0
            while (prefix < limit && base[prefix] == current[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && base[base.size - 1 - suffix] == current[current.size - 1 - suffix]) suffix++
            val baseMiddle = base.subList(prefix, base.size - suffix)
            val currentMiddle = current.subList(prefix, current.size - suffix)
            return LineRemap(
                baseCount = base.size,
                currentCount = current.size,
                prefix = prefix,
                suffix = suffix,
                baseMiddle = baseMiddle,
                baseAnchors = uniqueLines(baseMiddle),
                currentAnchors = uniqueLines(currentMiddle),
            )
        }

        fun of(baseText: String, currentText: String): LineRemap =
            of(baseText.split('\n'), currentText.split('\n'))

        /** Line text → its index, only for a text that occurs once in [lines]. */
        private fun uniqueLines(lines: List<String>): Map<String, Int> {
            val counts = HashMap<String, Int>()
            for (line in lines) counts[line] = (counts[line] ?: 0) + 1
            val unique = HashMap<String, Int>()
            lines.forEachIndexed { index, line -> if (counts[line] == 1) unique[line] = index }
            return unique
        }
    }
}
