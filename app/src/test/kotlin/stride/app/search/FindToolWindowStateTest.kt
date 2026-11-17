package stride.app.search

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindOptions
import stride.core.services.Application
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

    @Test
    fun `a cancelled scan does not write into the tab that replaced it`() {
        val state = state()
        val first = indexed("A.kt", "alpha\n")
        val second = indexed("B.kt", "alpha alpha\n")
        var replaced = false

        val original = state.addTab(
            kind = FindTabKind.OCCURRENCES,
            query = "alpha",
            options = FindOptions(),
            title = "first",
            files = listOf(first, second),
            textOf = { path ->
                // Replace the tab from inside its own scan: the running job is cancelled then.
                if (!replaced && path.fileName.toString() == "A.kt") {
                    replaced = true
                    state.addTab(
                        kind = FindTabKind.OCCURRENCES,
                        query = "alpha",
                        options = FindOptions(),
                        title = "replaced",
                        files = listOf(second),
                    )
                }
                null
            },
        )

        assertTrue(replaced)
        val tab = state.tabs.single()
        assertEquals("replaced", tab.title)
        assertSame(tab, state.selectedTab)
        assertEquals(2, tab.hits.size)
        assertTrue(tab.hits.all { it.relativePath == "B.kt" })
        assertTrue(original.hits.isEmpty())
        assertTrue(original.searching)
    }

    @Test
    fun `a replace plan that finishes on another tab keeps its confirmation`() {
        val state = state()
        val files = listOf(indexed("A.kt", "alpha\n"))
        val first = state.addTab(
            kind = FindTabKind.REPLACE,
            query = "alpha",
            options = FindOptions(),
            title = "replace",
            files = files,
            replacement = "beta",
        )
        val second = state.addTab(
            kind = FindTabKind.OCCURRENCES,
            query = "alpha",
            options = FindOptions(),
            title = "other",
            files = files,
        )
        val gate = CountDownLatch(1)
        val controller = FindReplaceController(
            files = {
                gate.await()
                files
            },
            textOf = { null },
            openEditors = { emptyList() },
        )

        state.select(first)
        state.startReplaceAll(first, controller)
        state.select(second)
        gate.countDown()
        awaitReplacePlan(state.replaceState(first))

        val replace = state.replaceState(first)
        assertFalse(replace.busy)
        assertNotNull(replace.pendingPlan)
        assertEquals(
            "Replace 1 occurrence of 'alpha' across 1 file with 'beta'? " +
                "1 file is written to disk and cannot be undone.",
            replace.confirmation,
        )
        assertSame(second, state.selectedTab)
    }

    @Test
    fun `a replace selected that changed nothing keeps its row`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val editor = TextFileEditor(VFile(path), readOnly = true)
        val files = listOf(IndexedFile(VFile(path), "A.kt"))
        val state = state()
        val tab = state.addTab(
            kind = FindTabKind.REPLACE,
            query = "alpha",
            options = FindOptions(),
            title = "replace",
            files = files,
            replacement = "beta",
        )
        val controller = FindReplaceController(
            files = { files },
            textOf = { null },
            openEditors = { listOf(editor) },
        )
        val hit = tab.hits.single()
        val planned = requireNotNull(controller.planHit(tab, hit))
        val outcome = controller.apply(ReplacePlan(listOf(planned)))

        state.finishReplaceSelected(tab, hit.file, outcome, emptyList())

        assertEquals(1, outcome.skippedReadOnly)
        assertSame(hit, tab.hits.single())
        assertEquals("Replaced 0 occurrences in 0 files, 1 skipped (read-only)", tab.message)
    }

    private fun awaitReplacePlan(replace: ReplaceTabState) {
        val deadline = System.currentTimeMillis() + 5_000
        while (replace.busy && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(replace.busy, "the replace plan did not finish")
    }

    private fun indexed(name: String, content: String): IndexedFile {
        val path = temp.resolve(name)
        Files.writeString(path, content)
        return IndexedFile(VFile(path), name)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(FindToolWindowStateTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
