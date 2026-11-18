package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
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
    fun `a tracked clean file edited and unsaved is listed, saved and committed`() {
        val repo = repoWithSources()
        val file = repo.root.resolve("src/Main.kt")
        withState(repo) { state, session ->
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("committed text\n")
            runBlocking { resolveTracked(session, session.commit) }
            val window = session.commit
            window.syncRows(commitRows(session, window))
            assertEquals(listOf(file), window.rows.map { it.path })
            assertTrue(window.inclusion.isChecked(window.rows.single()))
            assertTrue(window.rows.single().unsaved)
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
    fun `an ignored file edited without saving is not listed and the commit still succeeds`() {
        val repo = repoWithSources()
        repo.write(".gitignore", "build/\n")
        repo.commitAll("Ignore build output")
        val ignored = repo.write("build/out.txt", "on disk\n")
        repo.write("src/Main.kt", "second\n")
        withState(repo) { state, session ->
            val ignoredEditor = session.editors.openFile(VFile(ignored)) as TextFileEditor
            ignoredEditor.setText("typed\n")
            runBlocking { resolveTracked(session, session.commit) }
            val window = session.commit
            window.syncRows(commitRows(session, window))
            assertEquals(listOf(repo.root.resolve("src/Main.kt")), window.rows.map { it.path })
            window.message = "Commit the tracked file"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files to main: ") == true }, state.statusMessage)
            assertEquals("second\n", repo.git("show", "HEAD:src/Main.kt"))
            assertTrue(ignoredEditor.isModified)
            assertEquals("on disk\n", Files.readString(ignored))
            assertFalse(repo.git("ls-tree", "-r", "--name-only", "HEAD").contains("build/out.txt"))
        }
    }

    @Test
    fun `a project opened through a symlink lists each file once`() {
        val repo = repoWithSources()
        val link = temp.resolve("web-dashboard-link")
        Files.createSymbolicLink(link, repo.root)
        repo.write("src/Main.kt", "second\n")
        val state = AppState()
        try {
            state.openFolder(link)
            val session = requireNotNull(state.session)
            session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { session.git.refreshNow() }
            val editor = session.editors.openFile(VFile(link.resolve("src/Main.kt"))) as TextFileEditor
            editor.setText("typed\n")
            runBlocking { resolveTracked(session, session.commit) }

            val rows = commitRows(session, session.commit)

            assertEquals(listOf(repo.root.resolve("src/Main.kt")), rows.map { it.path })
            assertTrue(rows.single().unsaved)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `command K on a cold snapshot opens the window and waits for the refresh`() {
        val repo = repoWithSources()
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val session = requireNotNull(state.session)
            session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)

            assertTrue(openCommit(state))

            assertTrue(state.toolWindows.visible)
            assertEquals("commit", state.toolWindows.activeId)
            assertTrue(eventually { session.git.snapshot.repository != null })
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
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
            runBlocking { resolveTracked(session, session.commit) }
            val window = session.commit
            window.syncRows(commitRows(session, window))
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
            runBlocking { resolveTracked(session, session.commit) }
            val window = session.commit
            window.syncRows(commitRows(session, window))
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
    fun `outside a repository the window opens and reports the root`() {
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

            assertTrue(state.toolWindows.visible)
            assertEquals("commit", state.toolWindows.activeId)
            assertTrue(eventually { state.statusMessage == "Not a Git repository: $root" }, state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a checked untracked file is added and committed`() {
        val repo = repoWithSources()
        val newFile = repo.write("src/New.kt", "on disk\n")
        withState(repo) { state, session ->
            val window = session.commit
            window.syncRows(commitRows(session, window))
            val row = window.rows.single { it.path == newFile }
            assertFalse(window.inclusion.isChecked(row))
            window.inclusion.toggle(row)
            window.message = "Add the new file"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files to main: ") == true })
            assertEquals("on disk\n", repo.git("show", "HEAD:src/New.kt"))
            assertTrue(repo.git("ls-files", "--", "src/New.kt").contains("src/New.kt"))
        }
    }

    @Test
    fun `a checked rename row commits the old path as well`() {
        val repo = repoWithSources()
        repo.git("mv", "src/Main.kt", "src/Renamed.kt")
        withState(repo) { state, session ->
            val window = session.commit
            window.syncRows(commitRows(session, window))
            val row = window.rows.single()
            assertEquals(ChangeKind.RENAMED, row.kind)
            assertTrue(window.inclusion.isChecked(row))
            window.message = "Rename the source"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files to main: ") == true }, state.statusMessage)
            val tree = repo.git("ls-tree", "-r", "--name-only", "HEAD").lines().filter { it.isNotBlank() }
            assertEquals(listOf("src/Renamed.kt"), tree)
            assertEquals("", repo.git("status", "--porcelain"))
        }
    }

    @Test
    fun `the first commit of a repository without commits`() {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "first\n")
        withState(repo) { state, session ->
            val window = session.commit
            window.syncRows(commitRows(session, window))
            val row = window.rows.single { it.path == file }
            assertEquals(ChangeKind.UNTRACKED, row.kind)
            window.inclusion.toggle(row)
            window.message = "First commit"

            commitFromWindow(state)

            assertTrue(eventually { state.statusMessage?.startsWith("Committed 1 files to main: ") == true }, state.statusMessage)
            assertEquals("first\n", repo.git("show", "HEAD:src/Main.kt"))
            assertEquals("", repo.git("status", "--porcelain"))
        }
    }

    @Test
    fun `a save that fails stops the commit before git runs`() {
        val repo = repoWithSources()
        val file = repo.root.resolve("src/Main.kt")
        file.toFile().setWritable(false)
        val headBefore = repo.git("rev-parse", "HEAD").trim()
        try {
            withState(repo) { state, session ->
                val editor = session.editors.openFile(VFile(file)) as TextFileEditor
                editor.setText("cannot be saved\n")
                runBlocking { resolveTracked(session, session.commit) }
                val window = session.commit
                window.syncRows(commitRows(session, window))
                window.message = "Will not run"

                commitFromWindow(state)

                assertTrue(window.error?.startsWith("Commit failed:") == true, window.error)
                assertEquals(window.error, state.statusMessage)
                assertEquals(headBefore, repo.git("rev-parse", "HEAD").trim())
                assertTrue(editor.isModified)
            }
        } finally {
            file.toFile().setWritable(true)
        }
    }

    @Test
    fun `a new project starts from the default checkboxes`() {
        val repo = repoWithSources()
        repo.write("src/Main.kt", "second\n")
        val state = AppState()
        try {
            withState(repo) { _, session ->
                val window = session.commit
                window.syncRows(commitRows(session, window))
                window.inclusion.toggle(window.rows.single())
                assertFalse(window.inclusion.isChecked(window.rows.single()))
            }
            val next = TestGitRepo.create(temp, name = "web-dashboard")
            next.write("src/Main.kt", "first\n")
            next.commitAll("Initial commit")
            state.openFolder(next.root)
            val session = requireNotNull(state.session)
            session.git.executor = CliGitExecutor(TEST_GIT, next.environment)
            runBlocking { session.git.refreshNow() }
            next.write("src/Main.kt", "second\n")
            runBlocking { session.git.refreshNow() }
            val window = session.commit
            window.syncRows(commitRows(session, window))
            assertTrue(window.inclusion.isChecked(window.rows.single()))
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
