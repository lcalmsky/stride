package stride.app.vcs

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.core.services.Application
import stride.core.vfs.VFile

/** Next/Previous Change: hunk order, the no-wrap message and the popup each jump opens. */
class ChangeMarkNavigationTest {

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ChangeMarkNavigationTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `next walks the hunks in order and previous walks back`(@TempDir temp: Path) {
        withHunks(temp) { editor, state ->
            editor.jumpTo(0)
            assertEquals(0, caretLine(editor))

            assertTrue(gotoChange(state, forward = true))
            assertEquals(1, caretLine(editor))
            assertEquals(1, state.changeMarkPopup?.hunk?.newStart)

            assertTrue(gotoChange(state, forward = true))
            assertEquals(5, caretLine(editor))
            assertEquals(5, state.changeMarkPopup?.hunk?.newStart)

            assertTrue(gotoChange(state, forward = false))
            assertEquals(1, caretLine(editor))
            assertEquals(1, state.changeMarkPopup?.hunk?.newStart)
        }
    }

    @Test
    fun `there is no wrap past either end`(@TempDir temp: Path) {
        withHunks(temp) { editor, state ->
            editor.jumpTo(lineStart(editor, 5))
            assertTrue(gotoChange(state, forward = true))
            assertEquals("No more changes", state.statusMessage)
            assertEquals(5, caretLine(editor))

            editor.jumpTo(lineStart(editor, 0))
            assertTrue(gotoChange(state, forward = false))
            assertEquals("No more changes", state.statusMessage)
            assertEquals(0, caretLine(editor))
        }
    }

    @Test
    fun `the popup request carries the editor, the hunk and its base lines`(@TempDir temp: Path) {
        withHunks(temp) { editor, state ->
            editor.jumpTo(0)
            assertTrue(gotoChange(state, forward = true))
            val request = requireNotNull(state.changeMarkPopup)
            assertTrue(request.editor === editor)
            assertEquals(LineHunk(1, 1, 1, 1), request.hunk)
            assertEquals(listOf("beta"), request.baseLines)
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        }
    }

    @Test
    fun `the actions report no editor when none is open`() {
        val state = AppState()
        try {
            assertEquals(false, rollbackLines(state))
            assertEquals(false, gotoChange(state, forward = true))
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    /** A six-line file with line 1 modified and line 4 added. */
    private fun withHunks(temp: Path, block: (TextFileEditor, AppState) -> Unit) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\ngamma\ndelta\nepsilon\n")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            editor.setText("alpha\nBETA\ngamma\ndelta\nepsilon\nzeta\n")
            assertTrue(eventually { editor.vcsHunks.size == 2 }, "hunks: ${editor.vcsHunks}")
            assertEquals(listOf(LineHunk(1, 1, 1, 1), LineHunk(5, 0, 5, 1)), editor.vcsHunks)
            block(editor, state)
        } finally {
            state.dispose()
        }
    }

    private fun caretLine(editor: TextFileEditor): Int {
        val text = editor.value.text
        var line = 0
        for (index in 0 until editor.value.selection.end) if (text[index] == '\n') line++
        return line
    }

    private fun lineStart(editor: TextFileEditor, line: Int): Int {
        val text = editor.value.text
        if (line <= 0) return 0
        var seen = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                seen++
                if (seen == line) return index + 1
            }
        }
        return text.length
    }
}
