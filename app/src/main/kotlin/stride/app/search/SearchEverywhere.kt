package stride.app.search

import stride.app.AppState
import stride.core.extensions.ExtensionPointName
import stride.core.services.Project
import stride.search.FoundItem
import stride.search.SEARCH_EVERYWHERE_CONTRIBUTOR_EP
import stride.search.SearchEverywhereContributor
import stride.search.SearchEverywhereContributorFactory
import stride.search.SearchTab
import java.nio.file.Path

data class SearchResultEntry(
    val contributor: SearchEverywhereContributor<*>,
    val item: Any,
    val presentationText: String,
    val secondaryText: String,
    val groupLabel: String = "",
)

/**
 * Contributors of one project, highest [SearchEverywhereContributor.sortWeight] first.
 *
 * The action contributor needs [AppState], so it is created here instead of through the
 * extension point and is only added when [state] is given.
 */
class SearchEverywhere(
    project: Project,
    state: AppState? = null,
    chooseFolder: () -> Path? = { null },
) {
    private val contributors: List<SearchEverywhereContributor<*>> = buildList {
        addAll(
            ExtensionPointName<SearchEverywhereContributorFactory<*>>(SEARCH_EVERYWHERE_CONTRIBUTOR_EP)
                .extensionList()
                .map { it.createContributor(project) }
        )
        if (state != null) add(ActionSearchContributor(state, chooseFolder))
    }.sortedByDescending { it.sortWeight }

    /** Contributors that answer on [tab]; [SearchTab.ALL] uses everyone. */
    fun contributorsFor(tab: SearchTab): List<SearchEverywhereContributor<*>> =
        if (tab == SearchTab.ALL) contributors else contributors.filter { it.tab == tab }

    /**
     * Results of [tab], best match first. [SearchTab.ALL] groups by contributor (Files, Classes,
     * Symbols, Actions), gives each at most [ALL_PER_CONTRIBUTOR] results and stops at [limit].
     * A caller in a coroutine passes [isActive] so a superseded fetch stops between contributors.
     */
    fun fetch(
        query: String,
        tab: SearchTab = SearchTab.ALL,
        limit: Int = MAX_RESULTS,
        isActive: () -> Boolean = { true },
    ): List<SearchResultEntry> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<SearchResultEntry>()
        val perContributor = if (tab == SearchTab.ALL) minOf(limit, ALL_PER_CONTRIBUTOR) else limit
        for (contributor in contributorsFor(tab)) {
            if (!isActive()) break
            val remaining = minOf(limit - result.size, perContributor)
            if (remaining <= 0) break
            for (found in contributor.fetchAny(query, remaining)) {
                result.add(
                    SearchResultEntry(
                        contributor,
                        found.item,
                        found.presentationText,
                        found.secondaryText,
                        groupLabel = if (tab == SearchTab.ALL) contributor.displayName else "",
                    )
                )
            }
        }
        return result
    }

    /** Returns `true` when the popup should close after [process]. */
    fun process(entry: SearchResultEntry): Boolean = entry.contributor.processItem(entry.item)

    companion object {
        const val MAX_RESULTS: Int = 50
        const val ALL_PER_CONTRIBUTOR: Int = 4
    }
}

@Suppress("UNCHECKED_CAST")
private fun SearchEverywhereContributor<*>.fetchAny(query: String, limit: Int): List<FoundItem<Any>> =
    (this as SearchEverywhereContributor<Any>).fetch(query, limit)

@Suppress("UNCHECKED_CAST")
private fun SearchEverywhereContributor<*>.processItem(item: Any): Boolean =
    (this as SearchEverywhereContributor<Any>).process(item)
