package stride.search

import stride.core.services.Project
import stride.core.services.service

/** Creates a [ClassSearchContributor] backed by the project's [DeclarationIndex]. */
class ClassSearchContributorFactory : SearchEverywhereContributorFactory<Declaration> {
    override fun createContributor(project: Project): SearchEverywhereContributor<Declaration> =
        ClassSearchContributor(project.service<DeclarationIndex>())
}

/** Search Everywhere contributor that matches [DeclarationKind.CLASS] declarations by name. */
class ClassSearchContributor(private val index: DeclarationIndex) : SearchEverywhereContributor<Declaration> {
    override val id: String get() = ID

    override val displayName: String get() = "Classes"

    override val sortWeight: Int get() = 90

    override val tab: SearchTab get() = SearchTab.CLASSES

    override fun fetch(query: String, limit: Int): List<FoundItem<Declaration>> {
        if (limit <= 0) return emptyList()
        return index.snapshot()
            .asSequence()
            .filter { it.kind == DeclarationKind.CLASS }
            .mapNotNull { declaration ->
                val score = NameMatcher.match(query, declaration.name, declaration.relativePath) ?: return@mapNotNull null
                FoundItem(declaration, score, declaration.name, "${declaration.relativePath}:${declaration.line + 1}")
            }
            .sortedWith(declarationOrder())
            .take(minOf(limit, MAX_RESULTS))
            .toList()
    }

    override fun process(item: Declaration): Boolean = false

    companion object {
        const val ID: String = "stride.classes"
        const val MAX_RESULTS: Int = 50
    }
}

internal fun declarationOrder(): Comparator<FoundItem<Declaration>> =
    compareByDescending<FoundItem<Declaration>> { it.score }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.presentationText }
        .thenBy { it.presentationText }
        .thenBy { it.item.relativePath }
        .thenBy { it.item.line }
