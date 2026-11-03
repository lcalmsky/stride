package stride.app.keys

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class ActionDispatcherTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @Test
    fun `undo and redo are consumed in the editor and duplicate needs an editor`() {
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.UNDO, state) { null })
        assertTrue(dispatchAction(ActionId.REDO, state) { null })
        assertFalse(dispatchAction(ActionId.EDITOR_DUPLICATE, state) { null })

        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo", TextRange(1)))

        assertTrue(dispatchAction(ActionId.EDITOR_DUPLICATE, state) { null })
        assertEquals("one\none\ntwo", editor.value.text)

        assertTrue(dispatchAction(ActionId.UNDO, state) { null })
        assertEquals("one\ntwo", editor.value.text)
    }

    @Test
    fun `comment line uses the language commenter`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("project")
        val session = openProjectWith("a.kt", "b.txt")

        val kotlin = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        kotlin.updateValue(TextFieldValue("val x = 1", TextRange(0)))
        assertTrue(dispatchAction(ActionId.COMMENT_LINE, state) { null })
        assertEquals("// val x = 1", kotlin.value.text)

        val text = session.editors.openFile(VFile(root.resolve("b.txt"))) as TextFileEditor
        assertTrue(dispatchAction(ActionId.COMMENT_LINE, state) { null })
        assertEquals("", text.value.text)
    }

    @Test
    fun `goto line opens the popup and escape closes it`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor

        assertTrue(dispatchAction(ActionId.GOTO_LINE, state) { null })
        assertTrue(editor.gotoLineVisible)

        assertTrue(dispatchAction(ActionId.EDITOR_ESCAPE, state) { null })
        assertFalse(editor.gotoLineVisible)
    }

    @Test
    fun `next tab wraps from the last tab to the first`() {
        val session = openProjectWith("a.kt", "b.kt", "c.kt")
        assertEquals("c.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.NEXT_TAB, state) { null })
        assertEquals("a.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.PREVIOUS_TAB, state) { null })
        assertEquals("c.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `activate run tool window opens then hides when already focused`() {
        state.focus = KeyContext.GLOBAL

        assertTrue(dispatchAction(ActionId.ACTIVATE_RUN_TOOL_WINDOW, state) { null })
        assertTrue(state.toolWindows.visible)
        assertEquals("run", state.toolWindows.activeId)

        state.focus = KeyContext.RUN_WINDOW
        assertTrue(dispatchAction(ActionId.ACTIVATE_RUN_TOOL_WINDOW, state) { null })
        assertFalse(state.toolWindows.visible)
    }

    @Test
    fun `hide active window hides the panel and resets the focus context`() {
        state.toolWindows.activate("run")
        state.focus = KeyContext.RUN_WINDOW

        assertTrue(dispatchAction(ActionId.HIDE_ACTIVE_WINDOW, state) { null })

        assertFalse(state.toolWindows.visible)
        assertEquals(KeyContext.GLOBAL, state.focus)
    }

    @Test
    fun `find without an editor is not consumed`() {
        openProjectWith()
        assertFalse(dispatchAction(ActionId.FIND, state) { null })
        assertFalse(dispatchAction(ActionId.REPLACE, state) { null })
        assertFalse(state.findBar.visible)

        val withoutSession = AppState()
        try {
            assertFalse(dispatchAction(ActionId.FIND, withoutSession) { null })
        } finally {
            withoutSession.dispose()
        }
    }

    @Test
    fun `recent files without a session is consumed but opens nothing`() {
        assertTrue(dispatchAction(ActionId.RECENT_FILES, state) { null })
        assertFalse(state.recentFilesVisible)
        assertTrue(dispatchAction(ActionId.FIND_IN_PATH, state) { null })
        assertFalse(state.findInPathVisible)
    }

    private fun openProjectWith(vararg names: String): ProjectSession {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        state.openFolder(root)
        val session = requireNotNull(state.session)
        for (name in names) {
            Files.writeString(root.resolve(name), "")
            session.editors.openFile(VFile(root.resolve(name)))
        }
        return session
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ActionDispatcherTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
