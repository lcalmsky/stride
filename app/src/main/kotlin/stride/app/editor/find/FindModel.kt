package stride.app.editor.find

import stride.app.editor.TextChange

data class FindOptions(val caseSensitive: Boolean = false, val regex: Boolean = false)
data class FindMatch(val start: Int, val end: Int)

/** [matches] are ascending; [error] is the pattern error when a regular expression is invalid. */
data class FindResult(val matches: List<FindMatch>, val error: String? = null)

object FindModel {
    fun search(text: String, query: String, options: FindOptions = FindOptions()): FindResult {
        if (query.isEmpty()) return FindResult(emptyList())
        if (options.regex) {
            val regex = try {
                Regex(query, if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                return FindResult(emptyList(), e.message ?: "Invalid regular expression")
            }
            val matches = regex.findAll(text).map { FindMatch(it.range.first, it.range.last + 1) }.toList()
            return FindResult(matches)
        }
        val matches = buildList {
            var index = text.indexOf(query, 0, ignoreCase = !options.caseSensitive)
            while (index >= 0) {
                add(FindMatch(index, index + query.length))
                index = text.indexOf(query, index + 1, ignoreCase = !options.caseSensitive)
            }
        }
        return FindResult(matches)
    }

    /** The first match at or after [caret], wrapping to the first match; `null` without matches. */
    fun nextIndex(matches: List<FindMatch>, caret: Int): Int? {
        if (matches.isEmpty()) return null
        val index = matches.indexOfFirst { it.start >= caret }
        return if (index >= 0) index else 0
    }

    /** The last match ending at or before [caret], wrapping to the last match; `null` without matches. */
    fun previousIndex(matches: List<FindMatch>, caret: Int): Int? {
        if (matches.isEmpty()) return null
        val index = matches.indexOfLast { it.end <= caret }
        return if (index >= 0) index else matches.lastIndex
    }

    fun replaceCurrent(text: String, match: FindMatch, replacement: String): TextChange =
        TextChange(match.start, match.end - match.start, replacement)

    /** Back to front, so applying the changes in order keeps every untouched offset valid. */
    fun replaceAll(text: String, matches: List<FindMatch>, replacement: String): List<TextChange> =
        matches.sortedByDescending { it.start }
            .map { replaceCurrent(text, it, replacement) }
}
