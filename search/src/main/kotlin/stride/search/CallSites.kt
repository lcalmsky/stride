package stride.search

/**
 * One call of a name in a file's text. [start] until [end] is the name. [receiver] is `null` for an
 * unqualified call, else the expression before the `.`: `this`, `super`, a simple name, or `"?"` for
 * anything longer (a call result, an index, a chain). [isReference] marks a `::name` callable
 * reference, whose [receiver] is the name before `::` or `null`.
 */
data class CallSite(
    val name: String,
    val start: Int,
    val end: Int,
    val receiver: String? = null,
    val isReference: Boolean = false,
)

/**
 * Finds calls by text in Kotlin and Java sources, outside comments, strings and characters. A call
 * is an identifier followed, after optional spaces on the same line, by `(`, by balanced type
 * arguments and `(`, or in Kotlin by a trailing lambda `{`; or an identifier after `::`. Keywords
 * (`if`, `when`, `for`, `while`, `catch`, `return`, `super`, `this`, `object`, ...), annotations
 * (`@Name(`), Java `new` and the names of declarations themselves (`fun name(`, `class Name(`, a
 * Java method or constructor header, `constructor(`) are not calls. No types are resolved here.
 */
object CallSites {
    /** The calls of [name] in [text], in text order. */
    fun find(fileName: String, text: String, name: String): List<CallSite> = emptyList()

    /** Every call inside `[start, end)` of [text], in text order. */
    fun inRange(fileName: String, text: String, start: Int, end: Int): List<CallSite> = emptyList()
}
