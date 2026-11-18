package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
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

/** The gutter hunks a real repository produces for an open editor. */
class LineStatusTrackerTest {

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(LineStatusTrackerTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `an edited tracked file gets one hunk per change and reverting removes them`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\ngamma\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file) { editor ->
            waitForHunks(editor, emptyList())
            editor.setText("alpha\nbeta two\ngamma\ndelta\n")

            assertTrue(eventually { editor.vcsHunks.size == 2 }, "hunks: ${editor.vcsHunks}")
            assertEquals(
                listOf(LineHunk(1, 1, 1, 1), LineHunk(3, 0, 3, 1)),
                editor.vcsHunks,
            )

            editor.setText("alpha\nbeta\ngamma\n")
            assertTrue(eventually { editor.vcsHunks.isEmpty() }, "hunks: ${editor.vcsHunks}")
        }
    }

    @Test
    fun `deleting a line is a DELETED hunk`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\ngamma\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file) { editor ->
            editor.setText("alpha\ngamma\n")

            assertTrue(eventually { editor.vcsHunks.size == 1 }, "hunks: ${editor.vcsHunks}")
            val hunk = editor.vcsHunks.single()
            assertEquals(HunkKind.DELETED, hunk.kind)
            assertEquals(LineHunk(1, 1, 1, 0), hunk)
        }
    }

    @Test
    fun `adding an open untracked file to the index marks every line added`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "alpha\n")
        repo.commitAll("Initial commit")
        val file = repo.write("src/Fresh.kt", "alpha\nbeta\n")

        withEditor(repo, file, baseAlreadySet = true) { editor, state ->
            assertEquals(BaseText.Untracked, ChangeMarks.trackerOf(editor)?.baseState())

            repo.git("add", "src/Fresh.kt")
            runBlocking { state.session!!.git.refreshNow() }

            assertTrue(
                eventually { editor.vcsHunks == listOf(LineHunk(0, 0, 0, 2)) },
                "hunks: ${editor.vcsHunks}",
            )
        }
    }

    @Test
    fun `removing an open tracked file from the index drops its marks`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file, baseAlreadySet = true) { editor, state ->
            editor.setText("alpha\nBETA\n")
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")

            repo.git("rm", "--cached", "src/Main.kt")
            runBlocking { state.session!!.git.refreshNow() }

            assertTrue(eventually { editor.vcsHunks.isEmpty() }, "hunks: ${editor.vcsHunks}")
            assertEquals(BaseText.Untracked, ChangeMarks.trackerOf(editor)?.baseState())
        }
    }

    @Test
    fun `a new commit refetches the base`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file, baseAlreadySet = true) { editor, state ->
            editor.setText("alpha\nbeta\n")
            assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")

            file.writeText("alpha\nbeta\n")
            repo.commitAll("Second commit")
            runBlocking { state.session!!.git.refreshNow() }

            assertTrue(eventually { editor.vcsHunks.isEmpty() }, "hunks: ${editor.vcsHunks}")
        }
    }

    @Test
    fun `an untracked file draws no marks`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "alpha\n")
        repo.commitAll("Initial commit")
        val untracked = repo.write("src/Fresh.kt", "alpha\n")

        withEditor(repo, untracked) { editor ->
            editor.setText("alpha\nbeta\n")
            Thread.sleep(500)
            assertTrue(editor.vcsHunks.isEmpty(), "hunks: ${editor.vcsHunks}")
            assertEquals(BaseText.Untracked, ChangeMarks.trackerOf(editor)?.baseState())
        }
    }

    @Test
    fun `a file with a filter attribute draws no marks`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        repo.write(".gitattributes", "*.dat filter=lfs\n")
        val file = repo.write("src/data.dat", "alpha\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file) { editor ->
            editor.setText("alpha\nbeta\n")
            Thread.sleep(500)
            assertTrue(editor.vcsHunks.isEmpty(), "hunks: ${editor.vcsHunks}")
            assertEquals(
                BaseText.Unavailable("Filtered by a Git attribute"),
                ChangeMarks.trackerOf(editor)?.baseState(),
            )
        }
    }

    @Test
    fun `a binary file draws no marks`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.root.resolve("src/asset.dat")
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(0x41, 0x00, 0x42, 0x0A))
        repo.commitAll("Initial commit")

        withEditor(repo, file) { editor ->
            editor.setText("changed\n")
            Thread.sleep(500)
            assertTrue(editor.vcsHunks.isEmpty(), "hunks: ${editor.vcsHunks}")
            assertEquals(BaseText.Unavailable("Binary file"), ChangeMarks.trackerOf(editor)?.baseState())
        }
    }

    @Test
    fun `a project outside a repository draws no marks`(@TempDir temp: Path) {
        val root = temp.resolve("web-dashboard").createDirectories()
        val file = root.resolve("index.kt")
        file.writeText("alpha\n")

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
            editor.setText("alpha\nbeta\n")
            Thread.sleep(500)
            assertTrue(editor.vcsHunks.isEmpty(), "hunks: ${editor.vcsHunks}")
            val answer = ChangeMarks.trackerOf(editor)?.baseState()
            assertTrue(answer is BaseText.Unavailable, "base: $answer")
            assertTrue((answer as BaseText.Unavailable).reason.startsWith("Not a Git repository"))
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a CRLF checkout of an unchanged file has no hunks`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        repo.git("config", "core.autocrlf", "true")
        val file = repo.write("src/Main.kt", "alpha\r\nbeta\r\n")
        repo.commitAll("Initial commit")

        withEditor(repo, file) { editor ->
            waitForHunks(editor, emptyList())
            assertEquals("alpha\r\nbeta\r\n", editor.value.text)
            assertTrue(editor.vcsHunks.isEmpty(), "hunks: ${editor.vcsHunks}")
        }
    }

    private fun withEditor(repo: TestGitRepo, file: Path, block: (TextFileEditor) -> Unit) {
        withEditor(repo, file, baseAlreadySet = false) { editor, _ -> block(editor) }
    }

    private fun withEditor(
        repo: TestGitRepo,
        file: Path,
        baseAlreadySet: Boolean,
        block: (TextFileEditor, AppState) -> Unit,
    ) {
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null }, "base never arrived")
            if (baseAlreadySet) {
                assertTrue(eventually { editor.vcsHunks.isEmpty() })
            }
            block(editor, state)
        } finally {
            state.dispose()
        }
    }

    private fun waitForHunks(editor: TextFileEditor, expected: List<LineHunk>) {
        assertTrue(eventually { editor.vcsHunks == expected }, "hunks: ${editor.vcsHunks}")
    }
}
