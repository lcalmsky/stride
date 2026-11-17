package stride.app.navigation

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.keys.KeyContext
import stride.core.services.Application
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class NavigationBarOpenFileTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a file the editor cannot open hands the keyboard back and reports it`() {
        val root = temp.resolve("project")
        val folder = Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("Main.kt"), "fun main() {}\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Main.kt")))
            state.focus = KeyContext.NAV_BAR
            val before = state.editorFocusRequests

            // No editor provider accepts a directory, so opening it fails after the existence check.
            val opened = openNavigationBarFile(state, session, VFile(folder))

            assertFalse(opened)
            assertEquals(KeyContext.EDITOR, state.focus)
            assertEquals(before + 1, state.editorFocusRequests)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `an open file is only selected and the keyboard goes to it`() {
        val root = temp.resolve("project-open")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "fun main() {}\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt")))
            state.focus = KeyContext.NAV_BAR
            val before = state.editorFocusRequests

            val opened = openNavigationBarFile(state, session, VFile(root.resolve("Main.kt")))

            assertTrue(opened)
            assertEquals(editor, session.editors.selected)
            assertEquals(KeyContext.EDITOR, state.focus)
            assertEquals(before + 1, state.editorFocusRequests)
        } finally {
            state.dispose()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(NavigationBarOpenFileTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
