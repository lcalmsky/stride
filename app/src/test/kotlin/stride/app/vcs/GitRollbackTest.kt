package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitRollbackTest {

    @TempDir
    lateinit var temp: Path

    private fun repoWithCommit(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Kept.kt", "one\n")
        repo.write("src/Other.kt", "other\n")
        repo.commitAll("Initial commit")
        return repo
    }

    private fun status(repo: TestGitRepo): String =
        repo.git("status", "--porcelain", "--untracked-files=all").trimEnd()

    @Test
    fun `MM restores the index entry and the working file from HEAD`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        repo.write("src/Kept.kt", "staged\n")
        repo.git("add", "--", "src/Kept.kt")
        repo.write("src/Kept.kt", "working\n")
        assertEquals("MM src/Kept.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("Rolled back 1 file", result.message)
        assertEquals("one\n", Files.readString(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.restored)
        assertEquals(emptyList<Path>(), result.unstaged)
    }

    @Test
    fun `MD brings a file staged as modified and deleted in the work tree back`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        repo.write("src/Kept.kt", "staged\n")
        repo.git("add", "--", "src/Kept.kt")
        Files.delete(file)
        assertEquals("MD src/Kept.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("one\n", Files.readString(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.restored)
    }

    @Test
    fun `a staged deletion restores the file from HEAD`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        Files.delete(file)
        repo.git("add", "--", "src/Kept.kt")
        assertEquals("D  src/Kept.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("one\n", Files.readString(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.restored)
    }

    @Test
    fun `a work-tree-only deletion restores the file from HEAD`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        Files.delete(file)
        assertEquals(" D src/Kept.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("one\n", Files.readString(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.restored)
    }

    @Test
    fun `a rename restores the old path and leaves the new path unversioned with its edits`() {
        val repo = repoWithCommit()
        val original = repo.root.resolve("src/Kept.kt")
        val renamed = repo.root.resolve("src/Renamed.kt")
        repo.git("mv", "src/Kept.kt", "src/Renamed.kt")
        repo.write("src/Renamed.kt", "edited\n")
        assertEquals("RM src/Kept.kt -> src/Renamed.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(renamed, original)) }

        assertTrue(result.ok, result.message)
        assertEquals("Rolled back 2 files; 1 added file kept as unversioned", result.message)
        assertEquals("one\n", Files.readString(original))
        assertEquals("edited\n", Files.readString(renamed))
        assertEquals("?? src/Renamed.kt", status(repo))
        assertEquals(listOf(original), result.restored)
        assertEquals(listOf(renamed), result.unstaged)
    }

    @Test
    fun `an AM file stays on disk unversioned with its edits`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")
        repo.write("src/New.kt", "edited\n")
        assertEquals("AM src/New.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("Rolled back 1 file; 1 added file kept as unversioned", result.message)
        assertEquals("edited\n", Files.readString(file))
        assertEquals("?? src/New.kt", status(repo))
        assertEquals(emptyList<Path>(), result.restored)
        assertEquals(listOf(file), result.unstaged)
    }

    @Test
    fun `an AD file only leaves the index`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")
        Files.delete(file)
        assertEquals("AD src/New.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("Rolled back 1 file; 1 added file kept as unversioned", result.message)
        assertFalse(Files.exists(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.unstaged)
    }

    @Test
    fun `an A file is left untracked with its content intact`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")
        assertEquals("A  src/New.kt", status(repo))

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("Rolled back 1 file; 1 added file kept as unversioned", result.message)
        assertEquals("new\n", Files.readString(file))
        assertEquals("?? src/New.kt", status(repo))
        assertEquals(listOf(file), result.unstaged)
    }

    @Test
    fun `a bracket in the name matches that file and not a name the pattern would also match`() {
        val repo = TestGitRepo.create(temp)
        val bracket = repo.write("src/a[1].kt", "one\n")
        repo.write("src/a1.kt", "one\n")
        repo.commitAll("Initial commit")
        repo.write("src/a[1].kt", "changed\n")
        repo.write("src/a1.kt", "changed\n")

        val result = withService(repo) { it.rollback(listOf(bracket)) }

        assertTrue(result.ok, result.message)
        assertEquals("one\n", Files.readString(bracket))
        assertEquals("changed\n", Files.readString(repo.root.resolve("src/a1.kt")))
        assertEquals(" M src/a1.kt", status(repo))
        assertEquals(listOf(bracket), result.restored)
    }

    @Test
    fun `a project in a repository subfolder rolls back through the repository root`() {
        val repo = TestGitRepo.create(temp)
        repo.write("sub/src/App.kt", "app\n")
        repo.write("root.txt", "root\n")
        repo.commitAll("Initial commit")
        val file = repo.write("sub/src/App.kt", "app changed\n")

        val result = withService(repo, root = repo.root.resolve("sub")) { it.rollback(listOf(file)) }

        assertTrue(result.ok, result.message)
        assertEquals("app\n", Files.readString(file))
        assertEquals("", status(repo))
        assertEquals(listOf(file), result.restored)
    }

    @Test
    fun `a repository without a commit only unstages and never restores`() {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")

        val result = withService(repo) { service ->
            service.rollback(listOf(file)) to service.console.filter { it.write }.map { it.command }
        }

        assertTrue(result.first.ok, result.first.message)
        assertEquals("Rolled back 1 file; 1 added file kept as unversioned", result.first.message)
        assertEquals("?? src/New.kt", status(repo))
        assertFalse(result.second.any { it.startsWith("git restore") }, result.second.toString())
        assertTrue(result.second.any { it.startsWith("git rm --cached") }, result.second.toString())
    }

    @Test
    fun `a merge in progress refuses the rollback and nothing changes`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Base")
        repo.git("checkout", "-q", "-b", "side")
        repo.write("src/Main.kt", "side\n")
        repo.commitAll("Side")
        repo.git("checkout", "-q", "main")
        repo.write("src/Main.kt", "main\n")
        repo.commitAll("Main")
        repo.git("merge", "side", check = false)
        val file = repo.root.resolve("src/Main.kt")
        val conflicted = Files.readString(file)
        val before = status(repo)

        val result = withService(repo) { it.rollback(listOf(file)) }

        assertFalse(result.ok)
        assertEquals("A merge is in progress: finish it in the terminal (⌥F12)", result.message)
        assertEquals(conflicted, Files.readString(file))
        assertEquals(before, status(repo))
        assertEquals(emptyList<Path>(), result.restored)
        assertEquals(emptyList<Path>(), result.unstaged)
    }

    @Test
    fun `a rollback in progress makes a second one report busy`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        repo.write("src/Kept.kt", "working\n")

        withService(repo) { service ->
            val gate = CountDownLatch(1)
            service.executor = GatedHeadExecutor(CliGitExecutor(TEST_GIT, repo.environment), gate)
            coroutineScope {
                val running = launch(Dispatchers.IO) { service.rollback(listOf(file)) }
                try {
                    assertTrue(eventually { service.busy == "Rolling back…" }, "the rollback did not start")
                    assertEquals("Git is busy: Rolling back…", service.rollback(listOf(file)).message)
                } finally {
                    gate.countDown()
                    running.join()
                }
            }
        }
    }

    @Test
    fun `a failing ls-tree refuses with nothing changed`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")
        repo.write("src/Kept.kt", "working\n")

        val result = withService(repo) { service ->
            service.executor = FailingCommandExecutor(
                CliGitExecutor(TEST_GIT, repo.environment),
                "ls-tree",
                GitResult(128, ByteArray(0), "fatal: not a valid object name HEAD\nmore\n", timedOut = false),
            )
            service.rollback(listOf(file))
        }

        assertFalse(result.ok)
        assertEquals("Rollback failed: fatal: not a valid object name HEAD", result.message)
        assertEquals("working\n", Files.readString(file))
        assertEquals(" M src/Kept.kt", status(repo))
        assertEquals(emptyList<Path>(), result.restored)
        assertEquals(emptyList<Path>(), result.unstaged)
    }

    @Test
    fun `a failing rm after the restore names the files already rolled back`() {
        val repo = repoWithCommit()
        val kept = repo.root.resolve("src/Kept.kt")
        val added = repo.write("src/New.kt", "new\n")
        repo.write("src/Kept.kt", "working\n")
        repo.git("add", "--", "src/New.kt")

        val result = withService(repo) { service ->
            service.executor = FailingCommandExecutor(
                CliGitExecutor(TEST_GIT, repo.environment),
                "rm",
                GitResult(128, ByteArray(0), "fatal: could not remove 'src/New.kt'\n", timedOut = false),
            )
            service.rollback(listOf(kept, added))
        }

        assertFalse(result.ok)
        assertEquals("Rollback failed: fatal: could not remove 'src/New.kt' (1 file was already rolled back)", result.message)
        assertEquals("one\n", Files.readString(kept))
        assertEquals(listOf(kept), result.restored)
        assertEquals(emptyList<Path>(), result.unstaged)
    }

    @Test
    fun `two restored files are counted in the plural`() {
        val repo = repoWithCommit()
        val kept = repo.root.resolve("src/Kept.kt")
        val other = repo.root.resolve("src/Other.kt")
        repo.write("src/Kept.kt", "kept changed\n")
        repo.write("src/Other.kt", "other changed\n")

        val result = withService(repo) { service ->
            service.rollback(listOf(kept, other)) to service.console.filter { it.write }.map { it.command }
        }

        assertTrue(result.first.ok, result.first.message)
        assertEquals("Rolled back 2 files", result.first.message)
        assertEquals(setOf(kept, other), result.first.restored.toSet())
        assertTrue(result.second.any { it.startsWith("git restore --source=HEAD --staged --worktree") }, result.second.toString())
        assertEquals("", status(repo))
    }

    @Test
    fun `the restore and the rm are write entries in the console`() {
        val repo = repoWithCommit()
        val kept = repo.root.resolve("src/Kept.kt")
        val added = repo.write("src/New.kt", "new\n")
        repo.write("src/Kept.kt", "working\n")
        repo.git("add", "--", "src/New.kt")

        val writes = withService(repo) { service ->
            service.rollback(listOf(kept, added))
            service.console.filter { it.write }.map { it.command }
        }

        assertTrue(
            writes.any { it == "git restore --source=HEAD --staged --worktree -- src/Kept.kt" },
            writes.toString(),
        )
        assertTrue(writes.any { it == "git rm --cached --force --quiet -- src/New.kt" }, writes.toString())
    }

    @Test
    fun `no paths and paths outside the repository are nothing to roll back`() {
        val repo = repoWithCommit()
        val outside = temp.resolve("outside.kt")
        outside.writeText("outside\n")

        val messages = withService(repo) { service ->
            listOf(service.rollback(emptyList()).message, service.rollback(listOf(outside)).message)
        }

        assertEquals(listOf("Nothing to roll back", "Nothing to roll back"), messages)
    }

    @Test
    fun `a missing git cannot roll back`() {
        val repo = repoWithCommit()
        val file = repo.root.resolve("src/Kept.kt")

        val message = withService(repo) { service ->
            service.executor = MissingGitExecutor
            service.rollback(listOf(file)).message
        }

        assertEquals("Git is not installed", message)
    }

    /** Holds the rollback's `rev-parse --verify` until [gate] opens. */
    private class GatedHeadExecutor(private val delegate: GitExecutor, private val gate: CountDownLatch) : GitExecutor {
        override fun run(command: GitCommand): GitResult {
            if (command.args.firstOrNull() == "rev-parse" && "verify" in command.args) {
                gate.await(10, TimeUnit.SECONDS)
            }
            return delegate.run(command)
        }
    }

    /** Answers every command whose first argument is [name] with [result]. */
    private class FailingCommandExecutor(
        private val delegate: GitExecutor,
        private val name: String,
        private val result: GitResult,
    ) : GitExecutor {
        override fun run(command: GitCommand): GitResult =
            if (command.args.firstOrNull() == name) result else delegate.run(command)
    }
}
