package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitCommitTest {

    @TempDir
    lateinit var temp: Path

    private fun repoWithCommit(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Kept.kt", "kept\n")
        repo.commitAll("Initial commit")
        return repo
    }

    @Test
    fun `an untracked included file is added and committed`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")

        val result = withService(repo) { it.commit(listOf(file), "Add new") }

        assertTrue(result.ok, result.message)
        assertEquals("new\n", repo.git("show", "HEAD:src/New.kt"))
        assertTrue(result.message.startsWith("Committed 1 files to main: "), result.message)
        assertEquals("Add new", result.subject)
        assertEquals(repo.git("rev-parse", "HEAD").trim(), result.commitId)
    }

    @Test
    fun `a deleted included file is committed as a deletion`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        Files.delete(file)

        val result = withService(repo) { it.commit(listOf(file), "Delete kept") }

        assertTrue(result.ok, result.message)
        assertEquals("", repo.git("show", "HEAD:src/Kept.kt", check = false))
        assertTrue(repo.git("status", "--porcelain").isBlank())
    }

    @Test
    fun `a staged rename is committed with both paths`() {
        val repo = repoWithCommit()
        repo.git("mv", "src/Kept.kt", "src/Renamed.kt")
        val renamed = repo.root.resolve("src/Renamed.kt")
        val original = repo.root.resolve("src/Kept.kt")

        val result = withService(repo) { it.commit(listOf(renamed, original), "Rename") }

        assertTrue(result.ok, result.message)
        assertEquals("kept\n", repo.git("show", "HEAD:src/Renamed.kt"))
        assertTrue(repo.git("ls-files").lines().none { it == "src/Kept.kt" })
    }

    @Test
    fun `a partial staging of an included file is replaced by the whole content`() {
        val repo = repoWithCommit()
        repo.write("src/Kept.kt", "staged\n")
        repo.git("add", "--", "src/Kept.kt")
        repo.write("src/Kept.kt", "working\n")

        val result = withService(repo) { it.commit(listOf(repo.root.resolve("src/Kept.kt")), "Whole file") }

        assertTrue(result.ok, result.message)
        assertEquals("working\n", repo.git("show", "HEAD:src/Kept.kt"))
    }

    @Test
    fun `a staged unrelated file stays staged and uncommitted`() {
        val repo = repoWithCommit()
        repo.write("src/Kept.kt", "kept changed\n")
        repo.write("src/Other.kt", "other\n")
        repo.git("add", "--", "src/Other.kt")

        val result = withService(repo) { it.commit(listOf(repo.root.resolve("src/Kept.kt")), "Only kept") }

        assertTrue(result.ok, result.message)
        assertEquals("", repo.git("show", "HEAD:src/Other.kt", check = false))
        assertTrue(repo.git("status", "--porcelain").contains("A  src/Other.kt"))
    }

    @Test
    fun `a file whose name has bracket characters is committed`() {
        val repo = repoWithCommit()
        val file = repo.write("src/a[1].kt", "bracket\n")

        val result = withService(repo) { it.commit(listOf(file), "Bracket file") }

        assertTrue(result.ok, result.message)
        assertEquals("bracket\n", repo.git("show", "HEAD:src/a[1].kt"))
    }

    @Test
    fun `a message whose first line starts with hash survives`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")

        val result = withService(repo) { it.commit(listOf(file), "#13 title\n\nbody\n") }

        assertTrue(result.ok, result.message)
        assertEquals("#13 title", repo.git("log", "-1", "--format=%s").trim())
        assertEquals("body", repo.git("log", "-1", "--format=%b").trim())
    }

    @Test
    fun `an empty message and an empty include are refused before git runs`() {
        val repo = repoWithCommit()
        val head = repo.git("rev-parse", "HEAD").trim()

        withService(repo) { service ->
            assertEquals("Enter a commit message", service.commit(listOf(repo.root.resolve("src/Kept.kt")), "  \n ").message)
            assertEquals("No changes to commit", service.commit(emptyList(), "message").message)
        }
        assertEquals(head, repo.git("rev-parse", "HEAD").trim())
    }

    @Test
    fun `a failing pre-commit hook keeps head and reports its output in the console`() {
        val repo = repoWithCommit()
        val head = repo.git("rev-parse", "HEAD").trim()
        writeHook(repo, "pre-commit", "#!/bin/sh\necho 'hook refused the commit' >&2\nexit 1\n")
        val file = repo.write("src/New.kt", "new\n")

        val result = withService(repo) { service ->
            val outcome = service.commit(listOf(file), "Blocked")
            outcome to service.console.last()
        }

        assertFalse(result.first.ok)
        assertTrue(result.first.message.startsWith("Commit failed:"), result.first.message)
        assertTrue(result.first.message.contains("hook refused the commit"), result.first.message)
        assertTrue(result.second.output.contains("hook refused the commit"), result.second.output)
        assertEquals(1, result.first.addedToIndex)
        assertEquals(head, repo.git("rev-parse", "HEAD").trim())
    }

    @Test
    fun `a commit over its timeout returns quickly and kills the hook`() {
        val repo = repoWithCommit()
        val hook = writeHook(repo, "pre-commit", "#!/bin/sh\nsleep 30 && echo done\n")
        val file = repo.write("src/New.kt", "new\n")

        val (result, elapsedMillis) = withService(repo) { service ->
            service.timeouts = service.timeouts.copy(commit = 1.seconds)
            val started = System.nanoTime()
            val outcome = service.commit(listOf(file), "Slow hook")
            outcome to (System.nanoTime() - started) / 1_000_000
        }

        assertFalse(result.ok)
        assertTrue(result.message.contains("timed out"), result.message)
        assertTrue(elapsedMillis < 20_000, "the commit took $elapsedMillis ms")
        assertTrue(
            eventually(5_000) {
                ProcessHandle.allProcesses().toList().none { handle ->
                    handle.info().commandLine().orElse("").contains(hook.toString())
                }
            },
            "the hook process is still running",
        )
    }

    @Test
    fun `a missing git cannot commit`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")

        val message = withService(repo) { service ->
            service.executor = MissingGitExecutor
            service.commit(listOf(file), "message").message
        }

        assertEquals("Git is not installed", message)
    }

    private fun writeHook(repo: TestGitRepo, name: String, content: String): Path {
        val hook = repo.root.resolve(".git/hooks/$name")
        Files.createDirectories(hook.parent)
        hook.writeText(content)
        hook.toFile().setExecutable(true)
        return hook
    }
}
