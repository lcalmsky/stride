package stride.search

/**
 * Case-insensitive matcher with camel-hump, substring and path-segment support.
 *
 * Scores follow the intended ranking: exact name, then name prefix, then camel-hump or
 * word starts, then substring, then path segments only. A shorter file name scores higher
 * within a tier; equal scores are meant to be broken alphabetically by the caller.
 */
object NameMatcher : Matcher {
    private const val EXACT = 10_000
    private const val PREFIX = 8_000
    private const val WORD_START = 6_000
    private const val SUBSTRING = 4_000
    private const val PATH = 2_000
    private const val MAX_BONUS = 999
    private const val SEPARATORS = "_-./ \\"

    override fun match(query: String, name: String, relativePath: String): Int? {
        val trimmed = query.trim()
        if (name.isEmpty()) return null
        val lengthBonus = MAX_BONUS - minOf(name.length, MAX_BONUS)
        if (trimmed.isEmpty()) return lengthBonus
        if ('/' in trimmed) {
            val pathBonus = pathMatch(trimmed, relativePath) ?: return null
            return PATH + pathBonus + lengthBonus
        }
        return nameMatch(trimmed, name)?.let { it + lengthBonus }
    }

    private fun nameMatch(query: String, name: String): Int? {
        val stem = stem(name)
        if (query.equals(name, ignoreCase = true) || query.equals(stem, ignoreCase = true)) return EXACT
        if (name.startsWith(query, ignoreCase = true) || stem.startsWith(query, ignoreCase = true)) return PREFIX
        wordStartMatch(query, name)?.let { return it }
        val index = name.indexOf(query, ignoreCase = true)
        if (index >= 0) return SUBSTRING + positionBonus(index)
        val stemIndex = stem.indexOf(query, ignoreCase = true)
        if (stemIndex >= 0) return SUBSTRING + positionBonus(stemIndex)
        return null
    }

    private fun wordStartMatch(query: String, name: String): Int? {
        var best: Int? = null
        for (start in name.indices) {
            if (!isWordStart(name, start)) continue
            if (!name[start].equals(query[0], ignoreCase = true)) continue
            val positions = subsequence(query, name, start) ?: continue
            val starts = runStarts(positions)
            if (starts.any { !isWordStart(name, it) }) continue
            val score = WORD_START + positionBonus(positions.first()) + runBonus(positions.size, starts.size)
            if (best == null || score > best) best = score
        }
        return best
    }

    private fun subsequence(query: String, name: String, start: Int): List<Int>? {
        val positions = ArrayList<Int>(query.length)
        positions.add(start)
        var cursor = start
        for (i in 1 until query.length) {
            val next = nextMatch(query[i], name, cursor) ?: return null
            positions.add(next)
            cursor = next
        }
        return positions
    }

    private fun nextMatch(c: Char, name: String, after: Int): Int? {
        var first: Int? = null
        for (index in after + 1 until name.length) {
            if (!name[index].equals(c, ignoreCase = true)) continue
            if (first == null) first = index
            if (isWordStart(name, index)) return index
        }
        return first
    }

    private fun runStarts(positions: List<Int>): List<Int> {
        val starts = ArrayList<Int>()
        var previous = Int.MIN_VALUE
        for (position in positions) {
            if (position != previous + 1) starts.add(position)
            previous = position
        }
        return starts
    }

    private fun pathMatch(query: String, relativePath: String): Int? {
        val querySegments = query.split('/').filter { it.isNotEmpty() }
        if (querySegments.isEmpty()) return null
        val pathSegments = relativePath.split('/').filter { it.isNotEmpty() }
        var cursor = 0
        var exact = 0
        for (querySegment in querySegments) {
            val exactIndex = (cursor until pathSegments.size)
                .firstOrNull { pathSegments[it].equals(querySegment, ignoreCase = true) }
            if (exactIndex != null) {
                exact++
                cursor = exactIndex + 1
                continue
            }
            val prefixIndex = (cursor until pathSegments.size)
                .firstOrNull { pathSegments[it].startsWith(querySegment, ignoreCase = true) }
                ?: return null
            cursor = prefixIndex + 1
        }
        return minOf(exact * 100, 900)
    }

    private fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    private fun isWordStart(name: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = name[index - 1]
        val current = name[index]
        if (previous in SEPARATORS) return current !in SEPARATORS
        if (current.isUpperCase() && previous.isLowerCase()) return true
        if (current.isUpperCase() && previous.isUpperCase()) {
            return name.getOrNull(index + 1)?.isLowerCase() == true
        }
        if (current.isDigit() && previous.isLetter()) return true
        if (current.isLetter() && previous.isDigit()) return true
        return false
    }

    private fun positionBonus(index: Int): Int = (500 - index).coerceIn(0, 500)

    private fun runBonus(characters: Int, runs: Int): Int {
        val contiguity = (characters - runs) * 20
        return (contiguity + (400 - (runs - 1) * 50)).coerceIn(0, 400)
    }
}
