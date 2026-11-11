package stride.app.editor

/**
 * One text revision's identifier scan. The fields belong together, so a reader that takes the
 * snapshot once cannot mix revisions when another thread publishes a newer scan.
 */
internal class IdentifierSnapshot private constructor(
    val text: String,
    val ranges: List<IntRange>,
    val names: List<String>,
) {
    /** The names except the identifier exactly at [excluded]. */
    fun namesExcluding(excluded: IntRange?): List<String> {
        if (excluded == null) return names
        val result = ArrayList<String>(names.size)
        for (index in ranges.indices) {
            if (ranges[index] != excluded) result.add(names[index])
        }
        return result
    }

    companion object {
        private const val CHECK_INTERVAL = 1_024

        /** Scans [text]; [checkCancelled] throws to abandon a scan whose popup has closed. */
        fun scan(text: String, checkCancelled: () -> Unit = {}): IdentifierSnapshot {
            checkCancelled()
            val ranges = EditActions.identifierRanges(text, checkCancelled)
            val names = ArrayList<String>(ranges.size)
            for (index in ranges.indices) {
                if (index % CHECK_INTERVAL == 0) checkCancelled()
                val range = ranges[index]
                names.add(text.substring(range.first, range.last + 1))
            }
            return IdentifierSnapshot(text, ranges, names)
        }
    }
}
