package stride.app.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.search.ClassSearchContributor
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.FileSearchContributor
import stride.search.FileNameIndex
import stride.search.SearchTab
import stride.search.SymbolSearchContributor
import java.nio.file.Files
import java.nio.file.Path

class SearchEverywhereTest {
    @TempDir
    lateinit var temp: Path

    private fun project(): Project {
        val root = temp.resolve("project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Alpha.kt"), "package demo\n\nclass AlphaService {\n    fun alphaFun() {}\n}\n")
        Files.writeString(root.resolve("src/Other.kt"), "package demo\n\nclass Other\n")
        return Project(root)
    }

    @Test
    fun `each tab asks only its own contributors`() {
        val project = project()
        try {
            val state = AppState()
            try {
                awaitIndexes(project)
                val search = SearchEverywhere(project, state)

                assertEquals(listOf(FileSearchContributor.ID), search.contributorsFor(SearchTab.FILES).map { it.id })
                assertEquals(listOf(ClassSearchContributor.ID), search.contributorsFor(SearchTab.CLASSES).map { it.id })
                assertEquals(listOf(SymbolSearchContributor.ID), search.contributorsFor(SearchTab.SYMBOLS).map { it.id })
                assertEquals(listOf(ActionSearchContributor.ID), search.contributorsFor(SearchTab.ACTIONS).map { it.id })
                assertEquals(4, search.contributorsFor(SearchTab.ALL).size)

                val files = search.fetch("Alpha", SearchTab.FILES)
                assertEquals(listOf("Alpha.kt"), files.map { it.presentationText })
                assertEquals("", files.single().groupLabel)

                val classes = search.fetch("Alpha", SearchTab.CLASSES)
                assertEquals(listOf("AlphaService"), classes.map { it.presentationText })
                assertTrue(classes.single().item is Declaration)

                val symbols = search.fetch("alpha", SearchTab.SYMBOLS)
                assertEquals(setOf("AlphaService", "alphaFun"), symbols.map { it.presentationText }.toSet())
            } finally {
                state.dispose()
            }
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `the all tab groups files first then classes, symbols and actions`() {
        val project = project()
        try {
            val state = AppState()
            try {
                awaitIndexes(project)
                val search = SearchEverywhere(project, state)

                val all = search.fetch("a", SearchTab.ALL)
                val groups = all.map { it.groupLabel }.distinct()
                assertEquals(listOf("Files", "Classes", "Symbols", "Actions"), groups)
                assertTrue(all.size <= SearchEverywhere.MAX_RESULTS)

                val actions = search.fetch("go to class", SearchTab.ACTIONS)
                assertEquals("Go to Class", actions.first().presentationText)
                assertTrue(actions.first().secondaryText.isNotEmpty(), "the shortcut is the secondary text")

                val results = search.fetch("Alpha", SearchTab.FILES)
                assertFalse(search.process(results.single()), "a file result is opened by the caller")
            } finally {
                state.dispose()
            }
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `a non positive limit returns nothing`() {
        val project = project()
        try {
            val state = AppState()
            try {
                awaitIndexes(project)
                val search = SearchEverywhere(project, state)
                assertTrue(search.fetch("a", SearchTab.ALL, 0).isEmpty())
                assertTrue(search.fetch("a", SearchTab.FILES, -1).isEmpty())
            } finally {
                state.dispose()
            }
        } finally {
            project.dispose()
        }
    }

    private fun awaitIndexes(project: Project) {
        assertTrue(project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
        assertTrue(project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(SearchEverywhereTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
