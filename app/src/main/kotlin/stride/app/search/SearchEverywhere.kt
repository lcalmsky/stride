package stride.app.search

import stride.core.extensions.ExtensionPointName
import stride.core.services.Project
import stride.search.FoundItem
import stride.search.SEARCH_EVERYWHERE_CONTRIBUTOR_EP
import stride.search.SearchEverywhereContributor
import stride.search.SearchEverywhereContributorFactory

data class SearchResultEntry(
    val contributor: SearchEverywhereContributor<*>,
    val item: Any,
    val presentationText: String,
    val secondaryText: String,
)

/** Contributors of one project, highest [SearchEverywhereContributor.sortWeight] first. */
class SearchEverywhere(project: Project) {
    private val contributors: List<SearchEverywhereContributor<*>> =
        ExtensionPointName<SearchEverywhereContributorFactory<*>>(SEARCH_EVERYWHERE_CONTRIBUTOR_EP)
            .extensionList()
            .map { it.createContributor(project) }
            .sortedByDescending { it.sortWeight }

    /** Display name of the primary contributor, shown in the popup header. */
    val displayName: String get() = contributors.firstOrNull()?.displayName ?: "Files"

    fun fetch(query: String, limit: Int = MAX_RESULTS): List<SearchResultEntry> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<SearchResultEntry>()
        for (contributor in contributors) {
            val remaining = limit - result.size
            if (remaining <= 0) break
            for (found in contributor.fetchAny(query, remaining)) {
                result.add(SearchResultEntry(contributor, found.item, found.presentationText, found.secondaryText))
            }
        }
        return result
    }

    /** Returns `true` when the popup should close after [process]. */
    fun process(entry: SearchResultEntry): Boolean = entry.contributor.processItem(entry.item)

    companion object {
        const val MAX_RESULTS: Int = 50
    }
}

@Suppress("UNCHECKED_CAST")
private fun SearchEverywhereContributor<*>.fetchAny(query: String, limit: Int): List<FoundItem<Any>> =
    (this as SearchEverywhereContributor<Any>).fetch(query, limit)

@Suppress("UNCHECKED_CAST")
private fun SearchEverywhereContributor<*>.processItem(item: Any): Boolean =
    (this as SearchEverywhereContributor<Any>).process(item)
