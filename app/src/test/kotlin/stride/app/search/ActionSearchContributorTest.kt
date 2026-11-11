package stride.app.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.MacKeymap
import stride.core.services.Application
import stride.search.SearchTab
import java.nio.file.Files
import java.nio.file.Path

class ActionSearchContributorTest {
    @TempDir
    lateinit var temp: Path

    private fun contributor(state: AppState, chooseFolder: () -> Path? = { null }) =
        ActionSearchContributor(state, chooseFolder, MacKeymap.keymap)

    @Test
    fun `every action has a presentable name`() {
        for (action in ActionId.entries) {
            val name = ActionNames.entries[action]
            assertNotNull(name, "no presentable name for $action")
            assertFalse(name!!.isBlank(), "blank presentable name for $action")
        }
    }

    @Test
    fun `fetch ranks by name and shows the shortcut as secondary text`() {
        val state = AppState()
        try {
            val contributor = contributor(state)
            val ranked = contributor.fetch("save", 10)
            assertEquals("Save", ranked.first().presentationText)
            assertEquals("⌘S", ranked.first().secondaryText)
            assertEquals(SearchTab.ACTIONS, contributor.tab)

            assertEquals("Go to Class", contributor.fetch("go to class", 10).first().presentationText)
            assertEquals("Open Folder", contributor.fetch("open folder", 10).first().presentationText)
            assertTrue(contributor.fetch("save", 0).isEmpty())
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `process closes the popup and runs the action through the dispatcher`() {
        val state = AppState()
        val root = temp.resolve("chosen")
        Files.createDirectories(root)
        try {
            val contributor = contributor(state) { root }
            val openFolder = contributor.fetch("open folder", 1).single()

            assertTrue(contributor.process(openFolder.item))
            assertEquals(root.toAbsolutePath().normalize(), state.session?.project?.root)
        } finally {
            state.dispose()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ActionSearchContributorTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
