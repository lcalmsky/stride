package stride.app.vcs

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.vfs.VFile

/** Rollback Lines: which hunks a caret or selection takes, and the one undo step it costs. */
class ChangeMarkRollbackTest {

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ChangeMarkRollbackTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `a caret in a modified hunk rolls that hunk back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\n", "alpha\nBETA\ngamma\n") { editor ->
            val caret = editor.value.text.indexOf("BETA") + 2
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))
        }
    }

    @Test
    fun `a caret just below a deleted hunk rolls it back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\n", "alpha\ngamma\n") { editor ->
            val caret = editor.value.text.indexOf("gamma")
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))
        }
    }

    @Test
    fun `two carets in two hunks roll both back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\ndelta\n", "alpha\nBETA\ngamma\nDELTA\n") { editor ->
            val text = editor.value.text
            editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("BETA"))))
            editor.toggleCaretAt(text.indexOf("DELTA"))
            assertEquals(2, editor.carets.size)
        }
    }

    @Test
    fun `a selection spanning two hunks rolls both back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\ndelta\n", "alpha\nBETA\ngamma\nDELTA\n") { editor ->
            val text = editor.value.text
            editor.updateValue(TextFieldValue(text, TextRange(0, text.indexOf("DELTA") + "DELTA".length)))
        }
    }

    @Test
    fun `a CRLF document rolls back with its own separator`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\n", "alpha\r\nBETA\r\ngamma\r\n", autocrlf = true) { editor ->
            val caret = editor.value.text.indexOf("BETA") + 1
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))
        }
    }

    @Test
    fun `a document without a final newline rolls back exactly`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta", "alpha\nBETA") { editor ->
            val caret = editor.value.text.indexOf("BETA")
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))
        }
    }

    @Test
    fun `a read-only file reports the reason and keeps its text`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\n")
        repo.commitAll("Initial commit")
        val permissions = Files.getPosixFilePermissions(file)
        Files.setPosixFilePermissions(file, emptySet())
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(editor.isReadOnly)
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            editor.updateValue(TextFieldValue("changed\n", TextRange(7)))
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() })

            assertTrue(rollbackLines(state))
            assertEquals("File is read-only", state.statusMessage)
            assertEquals("changed\n", editor.value.text)
        } finally {
            state.dispose()
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    @Test
    fun `a project outside a repository reports the repository`(@TempDir temp: Path) {
        val root = Files.createDirectories(temp.resolve("api-server"))
        val file = root.resolve("Main.kt")
        Files.writeString(file, "alpha\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = temp.resolve("home")),
            )
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })

            assertTrue(rollbackLines(state))
            assertTrue(state.statusMessage!!.startsWith("Not a Git repository: "), state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    /**
     * Opens a tracked file, edits it in the editor and places the carets with [setCarets]. Every
     * hunk is expected to be rolled back; the expected text comes from [LineDiff.rollback] itself.
     */
    private fun rollbackTest(
        temp: Path,
        initial: String,
        edited: String,
        autocrlf: Boolean = false,
        setCarets: (TextFileEditor) -> Unit,
    ) {
        val repo = TestGitRepo.create(temp)
        if (autocrlf) repo.git("config", "core.autocrlf", "true")
        val file = repo.write("src/Main.kt", initial)
        repo.commitAll("Initial commit")
        val base = LineDiff.split(initial)

        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            editor.setText(edited)
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")
            val hunks = editor.vcsHunks

            setCarets(editor)
            val beforeText = editor.value.text
            val beforeSelection = editor.value.selection
            val beforeSecondaries = editor.secondaryCarets
            assertEquals(true, editor.isModified)

            assertTrue(rollbackLines(state))

            val expected = LineDiff.rollback(
                base,
                LineDiff.split(beforeText),
                hunks,
                hunks,
                LineDiff.lineSeparator(beforeText),
            )
            assertEquals(expected, editor.value.text)
            assertEquals(firstChangedLineOffset(beforeText, expected), editor.value.selection.start)

            assertTrue(editor.undo(), "the rollback must be one undo step")
            assertEquals(beforeText, editor.value.text)
            assertEquals(beforeSelection, editor.value.selection)
            assertEquals(beforeSecondaries, editor.secondaryCarets)

            assertEquals(initial, file.readText(), "rollback must not save")
        } finally {
            state.dispose()
        }
    }

    private fun firstChangedLineOffset(before: String, after: String): Int {
        val beforeLines = LineDiff.split(before)
        val afterLines = LineDiff.split(after)
        var line = 0
        while (line < beforeLines.size && line < afterLines.size && beforeLines[line] == afterLines[line]) line++
        var seen = 0
        for (index in after.indices) {
            if (after[index] == '\n') {
                seen++
                if (seen == line) return index + 1
            }
        }
        return after.length
    }
}
