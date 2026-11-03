package stride.search

/**
 * Matches a search query against a file name and its project-relative path.
 */
interface Matcher {
    /**
     * @param query user input; a blank query matches every candidate
     * @param name file name including its extension
     * @param relativePath path from the project root using `/` separators
     * @return a score where a larger value is a better match, or `null` when there is no match
     */
    fun match(query: String, name: String, relativePath: String = name): Int?
}
