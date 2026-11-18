package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.vfs.VFile

class CommitFlowTest {

    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(CommitFlowTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `a checked file's unsaved editor is saved first and its text is committed`() {
        val repo = repoWithSources()
        val file = repo.root.resolve("src/Main.kt")
        withState(repo) { state, session ->
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("committed text\n")
            val window = session.commit
            window.syncRows(commitRows(session))
            window.message = "Change the text"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files to main: ") == true })
            assertEquals("committed text\n", repo.git("show", "HEAD:src/Main.kt"))
            assertFalse(editor.isModified)
            assertEquals("", window.message)
            assertTrue(window.rows.isEmpty(), window.rows.toString())
        }
    }

    @Test
    fun `an unchecked unsaved editor stays unsaved and its file is not committed`() {
        val repo = repoWithSources()
        val tracked = repo.root.resolve("src/Main.kt")
        val untracked = repo.write("src/New.kt", "on disk\n")
        withState(repo) { state, session ->
            val trackedEditor = session.editors.openFile(VFile(tracked)) as TextFileEditor
            trackedEditor.setText("changed\n")
            val untrackedEditor = session.editors.openFile(VFile(untracked)) as TextFileEditor
            untrackedEditor.setText("typed\n")
            val window = session.commit
            window.syncRows(commitRows(session))
            window.message = "Only the tracked file"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files") == true })
            assertEquals("changed\n", repo.git("show", "HEAD:src/Main.kt"))
            assertEquals("", repo.git("show", "HEAD:src/New.kt", check = false))
            assertTrue(untrackedEditor.isModified)
            assertEquals("on disk\n", Files.readString(untracked))
        }
    }

    @Test
    fun `a failing hook keeps the message and the selection and shows the status message`() {
        val repo = repoWithSources()
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        Files.createDirectories(hook.parent)
        hook.writeText("#!/bin/sh\necho 'hook refused the commit' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        val file = repo.root.resolve("src/Main.kt")
        withState(repo) { state, session ->
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("blocked\n")
            val window = session.commit
            window.syncRows(commitRows(session))
            window.message = "Blocked"
            val checked = window.checkedPaths()

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Commit failed:") == true })
            assertEquals("Blocked", window.message)
            assertEquals(checked, window.checkedPaths())
            assertEquals(state.statusMessage, window.error)
            assertTrue(window.error!!.contains("hook refused the commit"), window.error)
        }
    }

    @Test
    fun `a blank message and an empty selection are refused before git runs`() {
        val repo = repoWithSources()
        repo.write("src/Main.kt", "second\n")
        withState(repo) { state, session ->
            val window = session.commit
            window.syncRows(commitRows(session))
            window.message = "  "

            commitFromWindow(state)
            assertEquals("Enter a commit message", state.statusMessage)
            assertEquals("Enter a commit message", window.error)

            window.inclusion.toggle(window.rows.single())
            window.message = "Message"
            commitFromWindow(state)
            assertEquals("No changes to commit", state.statusMessage)
            assertEquals("No changes to commit", window.error)
        }
    }

    @Test
    fun `outside a repository the window reports the root`() {
        val root = Files.createDirectories(temp.resolve("web-dashboard"))
        root.resolve("Main.kt").writeText("val x = 1\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = temp.resolve("home")),
            )
            runBlocking { session.git.refreshNow() }

            openCommit(state)

            assertEquals("Not a Git repository: $root", state.statusMessage)
            assertFalse(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    private fun repoWithSources(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        return repo
    }

    private fun withState(repo: TestGitRepo, block: (AppState, ProjectSession) -> Unit) {
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val session = requireNotNull(state.session)
            session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { session.git.refreshNow() }
            block(state, session)
        } finally {
            state.dispose()
        }
    }
}
