package stride.app.search

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
import stride.search.IndexedFile

class FindToolWindowStateTest {
    @TempDir
    lateinit var temp: Path

    private fun state(): FindToolWindowState = FindToolWindowState(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a tab scans the files and reports its result count`() {
        val state = state()
        val files = listOf(indexed("A.kt", "alpha alpha\nbeta alpha\n"))

        val tab = state.addTab(
            kind = FindTabKind.USAGES,
            query = "alpha",
            options = FindOptions(),
            title = "Usages of alpha",
            files = files,
        )

        assertSame(tab, state.selectedTab)
        assertFalse(tab.searching)
        assertEquals(3, tab.hits.size)
        assertEquals("3 results", tab.header)
        assertFalse(tab.truncated)
    }

    @Test
    fun `an equal search replaces the tab in place`() {
        val state = state()
        val first = state.addTab(
            kind = FindTabKind.OCCURRENCES,
            query = "alpha",
            options = FindOptions(wholeWord = true),
            title = "Occurrences of 'alpha'",
            files = listOf(indexed("A.kt", "alpha alpha\n")),
        )
        state.addTab(
            kind = FindTabKind.USAGES,
            query = "beta",
            options = FindOptions(),
            title = "Usages of beta",
            files = listOf(indexed("B.kt", "beta\n")),
        )
        assertEquals(listOf(first.id, state.tabs[1].id), state.tabs.map { it.id })

        val replaced = state.addTab(
            kind = FindTabKind.OCCURRENCES,
            query = "alpha",
            options = FindOptions(wholeWord = true),
            title = "Occurrences of 'alpha'",
            files = listOf(indexed("C.kt", "alpha\n")),
        )

        assertEquals(2, state.tabs.size)
        assertEquals(listOf(first.id, state.tabs[1].id), state.tabs.map { it.id })
        assertSame(replaced, state.tabs[0])
        assertSame(replaced, state.selectedTab)
        assertEquals(1, replaced.hits.size)
        assertEquals("C.kt", replaced.hits.single().relativePath)
    }

    @Test
    fun `an equal search with another kind, query, options or replacement adds a tab`() {
        val state = state()
        val files = listOf(indexed("A.kt", "alpha\n"))
        state.addTab(FindTabKind.USAGES, "alpha", FindOptions(), "usages", files)
        state.addTab(FindTabKind.OCCURRENCES, "alpha", FindOptions(), "occurrences", files)
        state.addTab(FindTabKind.OCCURRENCES, "alpha", FindOptions(wholeWord = true), "whole word", files)
        state.addTab(FindTabKind.OCCURRENCES, "alpha", FindOptions(), "replaced", files, replacement = "beta")

        assertEquals(4, state.tabs.size)
    }

    @Test
    fun `the oldest tab is dropped after ten tabs`() {
        val state = state()
        val files = listOf(indexed("A.kt", "query\n"))
        for (number in 1..11) {
            state.addTab(FindTabKind.OCCURRENCES, "query $number", FindOptions(), "tab $number", files)
        }

        assertEquals(FindToolWindowState.MAX_TABS, state.tabs.size)
        assertEquals("tab 2", state.tabs.first().title)
        assertEquals("tab 11", state.tabs.last().title)
        assertEquals(state.tabs.last().id, state.selectedTabId)
    }

    @Test
    fun `closing a tab selects its neighbour and clear drops everything`() {
        val state = state()
        val files = listOf(indexed("A.kt", "alpha\n"))
        val first = state.addTab(FindTabKind.OCCURRENCES, "alpha", FindOptions(), "first", files)
        val second = state.addTab(FindTabKind.USAGES, "beta", FindOptions(), "second", files)
        val third = state.addTab(FindTabKind.USAGES, "gamma", FindOptions(), "third", files)

        assertSame(third, state.closeSelected())
        assertEquals(listOf(first.id, second.id), state.tabs.map { it.id })
        assertSame(second, state.selectedTab)

        state.select(first)
        assertSame(first, state.closeSelected())
        assertSame(second, state.selectedTab)

        state.clear()
        assertTrue(state.tabs.isEmpty())
        assertNull(state.selectedTab)
    }

    @Test
    fun `a truncated scan reports the cap`() {
        val state = state()
        val text = "x ".repeat(FIND_WINDOW_MAX_HITS + 10)
        val tab = state.addTab(
            kind = FindTabKind.OCCURRENCES,
            query = "x",
            options = FindOptions(),
            title = "many",
            files = listOf(indexed("A.kt", text)),
        )

        assertEquals(FIND_WINDOW_MAX_HITS, tab.hits.size)
        assertTrue(tab.truncated)
        assertEquals("$FIND_WINDOW_MAX_HITS+ results (truncated)", tab.header)
    }

    private fun indexed(name: String, content: String): IndexedFile {
        val path = temp.resolve(name)
        Files.writeString(path, content)
        return IndexedFile(VFile(path), name)
    }
}
