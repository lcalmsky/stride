package stride.app.vcs

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `a rollback within the debounce uses the hunks of the current text`(@TempDir temp: Path) {
        val initial = "alpha\nbeta\ngamma\ndelta\nepsilon\n"
        val base = LineDiff.split(initial)
        withCleanEditor(temp, initial) { editor, state ->
            val edited = "sigma\nalpha\nbeta\ngamma\nDELTA\nepsilon\n"
            val caret = edited.indexOf("DELTA") + 1
            editor.updateValue(TextFieldValue(edited, TextRange(caret)))
            assertTrue(editor.vcsHunks.isEmpty(), "the tracker is debounced; hunks: ${editor.vcsHunks}")

            assertTrue(rollbackLines(state))

            assertEquals(expectedRollback(base, edited, line = 4), editor.value.text)
            assertEquals("sigma\nalpha\nbeta\ngamma\ndelta\nepsilon\n", editor.value.text)
            assertNull(state.statusMessage)
        }
    }

    @Test
    fun `a rollback after deleting lines within the debounce does not throw`(@TempDir temp: Path) {
        val lines = List(15) { "line$it" }
        val initial = lines.joinToString("\n") + "\n"
        val base = LineDiff.split(initial)
        withCleanEditor(temp, initial) { editor, state ->
            val modified = lines.toMutableList().also {
                it[0] = "LINE0"
                it[9] = "LINE9"
            }.joinToString("\n") + "\n"
            editor.setText(modified)
            assertTrue(eventually { editor.vcsHunks.size == 2 }, "hunks: ${editor.vcsHunks}")

            val shorter = (listOf("LINE0", "line1") + lines.subList(9, 15)).joinToString("\n") + "\n"
            editor.updateValue(TextFieldValue(shorter, TextRange(1)))
            assertTrue(rollbackLines(state))

            assertEquals(expectedRollback(base, shorter, line = 0), editor.value.text)
            assertTrue(editor.value.text.endsWith("line14\n"), editor.value.text)
        }
    }

    @Test
    fun `a file that was clean rolls back an edit made within the debounce`(@TempDir temp: Path) {
        val initial = "alpha\nbeta\ngamma\n"
        withCleanEditor(temp, initial) { editor, state ->
            val edited = "alpha\nBETA\ngamma\n"
            editor.updateValue(TextFieldValue(edited, TextRange(edited.indexOf("BETA") + 1)))
            assertTrue(editor.vcsHunks.isEmpty(), "the tracker is debounced; hunks: ${editor.vcsHunks}")

            assertTrue(rollbackLines(state))

            assertEquals(initial, editor.value.text)
            assertNull(state.statusMessage)
        }
    }

    @Test
    fun `a rollback keeps the other lines' own terminators`(@TempDir temp: Path) {
        val initial = "alpha\nbeta\r\ngamma\ndelta\n"
        withCleanEditor(temp, initial) { editor, state ->
            val edited = "ALPHA\nbeta\r\ngamma\ndelta\n"
            editor.setText(edited)
            assertTrue(eventually { editor.vcsHunks.size == 1 }, "hunks: ${editor.vcsHunks}")

            editor.updateValue(TextFieldValue(edited, TextRange(1)))
            assertTrue(rollbackLines(state))

            assertEquals(initial, editor.value.text)
        }
    }

    @Test
    fun `a selection ending at the start of a line does not take that line`(@TempDir temp: Path) {
        val initial = "alpha\nbeta\ngamma\ndelta\nepsilon\n"
        val edited = "alpha\nBETA\ngamma\nDELTA\nepsilon\n"
        withCleanEditor(temp, initial) { editor, state ->
            editor.setText(edited)
            assertTrue(eventually { editor.vcsHunks.size == 2 }, "hunks: ${editor.vcsHunks}")

            editor.updateValue(TextFieldValue(edited, TextRange(edited.indexOf("BETA"), edited.indexOf("DELTA"))))
            assertTrue(rollbackLines(state))

            assertEquals("alpha\nbeta\ngamma\nDELTA\nepsilon\n", editor.value.text)
        }
    }

    @Test
    fun `the popup rollback does nothing once another editor is selected`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\n")
        val other = repo.write("src/Other.kt", "one\ntwo\n")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            editor.setText("new\nalpha\nbeta\n")
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")
            val hunk = editor.vcsHunks.single()

            state.session!!.editors.openFile(VFile(other))

            assertFalse(rollbackHunk(state, editor, hunk))
            assertEquals("new\nalpha\nbeta\n", editor.value.text)
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a deleted first line rolls back at the first line`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\n", "beta\ngamma\n") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
        }
    }

    @Test
    fun `a deleted last line rolls back at the end of the file`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\ngamma\n", "alpha\nbeta\n") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.length)))
        }
    }

    @Test
    fun `a line added after a file without a trailing newline rolls back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta", "alpha\nbeta\n") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.length)))
        }
    }

    @Test
    fun `a CRLF line added after a file without a trailing newline rolls back`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta", "alpha\r\nbeta\r\n") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.length)))
        }
    }

    @Test
    fun `lines appended to a file without a trailing newline roll back`(@TempDir temp: Path) {
        rollbackTest(temp, "a\nb", "a\nb\nc\nd") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("c"))))
        }
    }

    @Test
    fun `lines appended after a final newline keep that newline`(@TempDir temp: Path) {
        rollbackTest(temp, "a\nb\n", "a\nb\nc\nd\n") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("c"))))
        }
    }

    @Test
    fun `deleted lines at the end of a file roll back with their break`(@TempDir temp: Path) {
        withCleanEditor(temp, "alpha\nbeta\n") { editor, state ->
            editor.setText("alpha")
            assertTrue(eventually { editor.vcsHunks.size == 1 }, "hunks: ${editor.vcsHunks}")
            editor.updateValue(TextFieldValue("alpha", TextRange(0)))

            assertTrue(rollbackLines(state))

            assertEquals("alpha\nbeta\n", editor.value.text)
            assertNull(state.statusMessage)
        }
    }

    @Test
    fun `a modified last line without a newline rolls back to the base`(@TempDir temp: Path) {
        rollbackTest(temp, "alpha\nbeta\n", "alpha\nBETA") { editor ->
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("BETA"))))
        }
    }

    @Test
    fun `the popup rollback removes a line added at the end`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            editor.setText("alpha\nbeta\n")
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")
            val hunk = editor.vcsHunks.single()

            assertTrue(rollbackHunk(state, editor, hunk))
            assertEquals("alpha\nbeta", editor.value.text)
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    /**
     * Opens [initial] committed in a tracked file through a tracker that has published the clean
     * document, and hands the editor and state to [block] without waiting for a recompute.
     */
    private fun withCleanEditor(temp: Path, initial: String, block: (TextFileEditor, AppState) -> Unit) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", initial)
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
            assertTrue(eventually { editor.vcsHunks.isEmpty() })
            block(editor, state)
        } finally {
            state.dispose()
        }
    }

    /** The text [LineDiff.rollback] gives when only the hunks touching [line] are rolled back. */
    private fun expectedRollback(base: List<String>, text: String, line: Int): String {
        val current = LineDiff.split(text)
        val fresh = LineDiff.diff(base, current).orEmpty()
        val selected = fresh.filter { hunk ->
            if (hunk.newCount == 0) {
                hunk.newStart - 1 == line || hunk.newStart == line
            } else {
                line >= hunk.newStart && line < hunk.newStart + hunk.newCount
            }
        }
        return LineDiff.rollback(base, current, fresh, selected, LineDiff.lineSeparator(text))
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
        if (line == 0) return 0
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
