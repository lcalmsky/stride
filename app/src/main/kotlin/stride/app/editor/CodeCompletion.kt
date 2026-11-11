package stride.app.editor

import stride.search.NameMatcher

/** Candidate names for the completion popup, ranked with [NameMatcher]. */
object CodeCompletion {
    const val MAX_RESULTS: Int = 200

    /** Identifiers of [text] in document order; callers deduplicate across sources. */
    fun identifiers(text: String): List<String> =
        EditActions.identifierRanges(text).map { text.substring(it.first, it.last + 1) }

    /** [names] matching [prefix], best first; a blank prefix keeps every name. */
    fun candidates(prefix: String, names: List<String>, limit: Int = MAX_RESULTS): List<String> {
        if (limit <= 0) return emptyList()
        return names.asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { name -> NameMatcher.match(prefix, name)?.let { name to it } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Keywords of the file's language; empty for other files. */
    fun keywordsFor(fileName: String): List<String> = when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> KOTLIN_KEYWORDS
        "java" -> JAVA_KEYWORDS
        else -> emptyList()
    }

    private val KOTLIN_KEYWORDS = listOf(
        "as", "break", "by", "catch", "class", "companion", "constructor", "continue", "do", "else",
        "false", "finally", "for", "fun", "get", "if", "import", "in", "init", "interface", "is",
        "null", "object", "package", "private", "protected", "public", "return", "set", "super",
        "this", "throw", "true", "try", "typealias", "val", "var", "when", "where", "while",
    )

    private val JAVA_KEYWORDS = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "false", "final",
        "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface",
        "long", "native", "new", "null", "package", "private", "protected", "public", "return",
        "short", "static", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "true", "try", "void", "volatile", "while",
    )
}
