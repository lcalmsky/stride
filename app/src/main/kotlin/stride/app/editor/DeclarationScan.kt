package stride.app.editor

import stride.search.DeclarationParser

/** Declaration start lines of [text], from the search module's regex parser. */
object DeclarationScan {
    fun lines(fileName: String, text: String): List<Int> =
        DeclarationParser.parse(fileName, text).map { it.line }.distinct().sorted()
}
