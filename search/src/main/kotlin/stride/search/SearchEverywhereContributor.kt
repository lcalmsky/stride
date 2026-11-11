package stride.search

import stride.core.services.Project

/** Extension point name for [SearchEverywhereContributorFactory] implementations. */
const val SEARCH_EVERYWHERE_CONTRIBUTOR_EP: String = "stride.searchEverywhereContributor"

/**
 * One result returned by a [SearchEverywhereContributor].
 *
 * @param item value handed back to [SearchEverywhereContributor.process]
 * @param score ranking score; higher is better
 * @param presentationText main line shown in the popup
 * @param secondaryText optional second line, usually the path
 */
data class FoundItem<T : Any>(
    val item: T,
    val score: Int,
    val presentationText: String,
    val secondaryText: String = ""
)

/**
 * Creates a [SearchEverywhereContributor] for one project. Instances are registered through
 * the `stride.searchEverywhereContributor` interface extension point.
 */
interface SearchEverywhereContributorFactory<T : Any> {
    fun createContributor(project: Project): SearchEverywhereContributor<T>
}

/**
 * A source of results for the Search Everywhere popup.
 *
 * [sortWeight] orders contributors relative to each other; a higher weight comes first.
 */
interface SearchEverywhereContributor<T : Any> {
    val id: String

    val displayName: String

    val sortWeight: Int

    /** Tab that lists this contributor's results; it always appears in [SearchTab.ALL]. */
    val tab: SearchTab get() = SearchTab.ALL

    /** Returns at most [limit] results, best match first; [limit] may be zero or negative. */
    fun fetch(query: String, limit: Int): List<FoundItem<T>>

    /** Handles a selected item. Returns `true` when the popup should close. */
    fun process(item: T): Boolean
}
