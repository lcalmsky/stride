package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchTabTest {
    @Test
    fun `next and previous cycle through the tab row order`() {
        assertEquals(
            listOf("All", "Classes", "Files", "Symbols", "Actions"),
            SearchTab.entries.map { it.title }
        )

        assertEquals(SearchTab.CLASSES, SearchTab.ALL.next())
        assertEquals(SearchTab.ACTIONS, SearchTab.SYMBOLS.next())
        assertEquals(SearchTab.ALL, SearchTab.ACTIONS.next())

        assertEquals(SearchTab.ACTIONS, SearchTab.ALL.previous())
        assertEquals(SearchTab.SYMBOLS, SearchTab.ACTIONS.previous())
        assertEquals(SearchTab.ALL, SearchTab.CLASSES.previous())
    }
}
