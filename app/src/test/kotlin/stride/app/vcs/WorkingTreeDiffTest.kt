package stride.app.vcs

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkingTreeDiffTest {

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(WorkingTreeDiffTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `the test JVM turns the default executor off`(@TempDir temp: Path) {
        val project = Project(temp)
        try {
            assertSame(MissingGitExecutor, project.service<GitService>().executor)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `the diff compares HEAD with the text on disk`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "fun main() {\n}\n")
        repo.commitAll("Initial commit")
        file.writeText("fun main() {\n    println()\n}\n")

        withState(repo) { state ->
            assertTrue(openWorkingTreeDiff(state, file, line = 1))

            assertTrue(eventually { state.diffRequest != null })
            val request = requireNotNull(state.diffRequest)
            assertEquals("src/Main.kt  HEAD ↔ Local", request.title)
            assertEquals(1, request.initialLine)
            val diff = request.files.single()
            assertEquals(BaseText.Text("fun main() {\n}\n"), diff.old)
            assertEquals(BaseText.Text("fun main() {\n    println()\n}\n"), diff.new)
            assertTrue(diff.openable)
        }
    }

    @Test
    fun `an open editor's unsaved text is the new side`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "fun main() {\n}\n")
        repo.commitAll("Initial commit")

        withState(repo) { state ->
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("fun main() = Unit\n")

            openWorkingTreeDiff(state, file)

            assertTrue(eventually { state.diffRequest != null })
            assertEquals(BaseText.Text("fun main() = Unit\n"), state.diffRequest!!.files.single().new)
        }
    }

    @Test
    fun `outside a repository the status bar gets the reason and no popup opens`(@TempDir temp: Path) {
        val root = temp.resolve("web-dashboard").createDirectories()
        val file = root.resolve("index.kt").also { it.writeText("val x = 1\n") }
        val state = AppState()
        try {
            state.openFolder(root)
            state.session!!.git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = temp.resolve("home")),
            )
            runBlocking { state.session!!.git.refreshNow() }

            openWorkingTreeDiff(state, file)

            assertTrue(eventually { state.statusMessage != null })
            assertTrue(state.statusMessage!!.startsWith("Not a Git repository"), state.statusMessage)
            assertNull(state.diffRequest)
        } finally {
            state.dispose()
        }
    }

    private fun withState(repo: TestGitRepo, block: (AppState) -> Unit) {
        val state = AppState()
        try {
            state.openFolder(repo.root)
            val git = state.session!!.git
            git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { git.refreshNow() }
            block(state)
        } finally {
            state.dispose()
        }
    }
}
