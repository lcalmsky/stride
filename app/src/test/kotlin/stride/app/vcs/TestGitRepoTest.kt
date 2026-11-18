package stride.app.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class TestGitRepoTest {

    @Test
    fun `a created repository is its own top level`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)

        assertEquals(repo.root.toString(), repo.git("rev-parse", "--show-toplevel").trim())
    }

    @Test
    fun `git never finds a repository above the ceiling`(@TempDir temp: Path) {
        val outer = TestGitRepo.create(temp, "outer")
        val inner = outer.root.resolve("inner").createDirectories()
        val environment = TestGitRepo.isolatedEnvironment(ceiling = outer.root, home = outer.home)

        val result = TestGitRepo.run(listOf("git", "rev-parse", "--show-toplevel"), inner, environment)

        assertNotEquals(0, result.exitCode, "git found ${result.stdout.trim()} above the ceiling")
    }

    @Test
    fun `commits carry the fixed test author and no user configuration`(@TempDir temp: Path) {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "fun main() {}\n")
        repo.commitAll("Initial commit")

        assertEquals("Test Author <author@example.com>", repo.git("log", "-1", "--format=%an <%ae>").trim())
        assertTrue(repo.git("config", "--global", "--list", check = false).isBlank())
    }
}
