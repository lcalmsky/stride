package stride.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.search.FoundItem
import stride.search.SearchEverywhereContributor
import stride.search.SearchTab

class PopupRowsTest {
    @Test
    fun `tabs cycle in the tab row order and wrap around`() {
        assertEquals(SearchTab.CLASSES, SearchTab.ALL.next())
        assertEquals(SearchTab.ALL, SearchTab.ACTIONS.next())
        assertEquals(SearchTab.SYMBOLS, SearchTab.ACTIONS.previous())
        assertEquals(SearchTab.ALL, SearchTab.CLASSES.previous())
    }

    @Test
    fun `grouped rows insert one header per contributor run`() {
        val files = listOf(
            entry("Alpha.kt", "Files"),
            entry("Beta.kt", "Files"),
            entry("AlphaService", "Classes"),
            entry("alphaFun", "Symbols"),
        )

        val rows = popupRows(files, grouped = true)

        assertEquals(
            listOf("group:Files", "item:0", "item:1", "group:Classes", "item:2", "group:Symbols", "item:3"),
            rows.map { row ->
                when (row) {
                    is PopupRow.Group -> "group:${row.label}"
                    is PopupRow.Entry -> "item:${row.index}"
                }
            }
        )
        val items = rows.filterIsInstance<PopupRow.Entry>()
        assertEquals(files, items.map { it.entry })
        assertEquals(listOf(0, 1, 2, 3), items.map { it.index })
    }

    @Test
    fun `ungrouped rows keep only the entries`() {
        val files = listOf(entry("Alpha.kt", "Files"), entry("AlphaService", "Classes"))

        val rows = popupRows(files, grouped = false)

        assertEquals(listOf(0, 1), rows.filterIsInstance<PopupRow.Entry>().map { it.index })
        assertEquals(2, rows.size)
    }

    private fun entry(text: String, group: String): SearchResultEntry =
        SearchResultEntry(FakeContributor, text, text, "", group)

    private object FakeContributor : SearchEverywhereContributor<Any> {
        override val id: String get() = "test.fake"

        override val displayName: String get() = "Fake"

        override val sortWeight: Int get() = 0

        override fun fetch(query: String, limit: Int): List<FoundItem<Any>> = emptyList()

        override fun process(item: Any): Boolean = false
    }
}
