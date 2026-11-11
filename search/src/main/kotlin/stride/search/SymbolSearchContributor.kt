package stride.search

import stride.core.services.Project
import stride.core.services.service

/** Creates a [SymbolSearchContributor] backed by the project's [DeclarationIndex]. */
class SymbolSearchContributorFactory : SearchEverywhereContributorFactory<Declaration> {
    override fun createContributor(project: Project): SearchEverywhereContributor<Declaration> =
        SymbolSearchContributor(project.service<DeclarationIndex>())
}

/**
 * Search Everywhere contributor that matches class and symbol declarations by name. The
 * secondary text is `Container — relative/path.kt:line`, or the path alone at top level.
 */
class SymbolSearchContributor(private val index: DeclarationIndex) : SearchEverywhereContributor<Declaration> {
    override val id: String get() = ID

    override val displayName: String get() = "Symbols"

    override val sortWeight: Int get() = 80

    override val tab: SearchTab get() = SearchTab.SYMBOLS

    override fun fetch(query: String, limit: Int): List<FoundItem<Declaration>> {
        if (limit <= 0) return emptyList()
        return index.snapshot()
            .asSequence()
            .mapNotNull { declaration ->
                val score = NameMatcher.match(query, declaration.name, declaration.relativePath) ?: return@mapNotNull null
                FoundItem(declaration, score, declaration.name, secondaryText(declaration))
            }
            .sortedWith(declarationOrder())
            .take(minOf(limit, MAX_RESULTS))
            .toList()
    }

    override fun process(item: Declaration): Boolean = false

    private fun secondaryText(declaration: Declaration): String {
        val location = "${declaration.relativePath}:${declaration.line + 1}"
        val container = declaration.container ?: return location
        return "$container — $location"
    }

    companion object {
        const val ID: String = "stride.symbols"
        const val MAX_RESULTS: Int = 50
    }
}
