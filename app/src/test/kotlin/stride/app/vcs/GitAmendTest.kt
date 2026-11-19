package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitAmendTest {

    @TempDir
    lateinit var temp: Path

    /** Two commits, so an amend of HEAD has a parent that must not move. */
    private fun repoWithTwoCommits(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/First.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Second.kt", "second\n")
        repo.commitAll("Second commit")
        return repo
    }

    private suspend fun ready(service: GitService): AmendBase =
        assertInstanceOf(AmendStart.Ready::class.java, service.amendStart()).base

    @Test
    fun `amendStart reads HEAD's id and full message`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "one\n")
        repo.git("add", "-A")
        repo.git("commit", "-q", "-m", "Title\n\nBody line")
        val head = repo.git("rev-parse", "HEAD").trim()

        val start = withService(repo) { it.amendStart() }

        assertEquals(AmendStart.Ready(AmendBase(head, "Title\n\nBody line")), start)
    }

    @Test
    fun `a branch without commits refuses Amend`() {
        val repo = TestGitRepo.create(temp)

        assertEquals(
            AmendStart.Refused("Nothing to amend: the branch has no commits yet"),
            withService(repo) { it.amendStart() },
        )
    }

    @Test
    fun `a merge in progress refuses Amend`() {
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

        assertEquals(
            AmendStart.Refused("A merge is in progress: finish it in the terminal (⌥F12)"),
            withService(repo) { it.amendStart() },
        )
    }

    @Test
    fun `a pushed HEAD refuses Amend and names the branch not the remote HEAD`() {
        val repo = repoWithTwoCommits()
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        // The remote's symbolic HEAD contains HEAD too, and must not be the ref named.
        repo.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        assertEquals(
            AmendStart.Refused("HEAD is already pushed to origin/main: amending it needs a force push"),
            withService(repo) { it.amendStart() },
        )
    }

    @Test
    fun `a failing pushed check refuses Amend`() {
        val repo = repoWithTwoCommits()

        val start = withService(repo) { service ->
            service.executor = FailingCommandExecutor(
                CliGitExecutor(TEST_GIT, repo.environment),
                "for-each-ref",
                GitResult(128, ByteArray(0), "fatal: bad revision\nsecond\n", timedOut = false),
            )
            service.amendStart()
        }

        assertEquals(AmendStart.Refused("Amend failed: fatal: bad revision"), start)
    }

    @Test
    fun `an amend replaces HEAD with the checked file and keeps its parent`() {
        val repo = repoWithTwoCommits()
        val parent = repo.git("rev-parse", "HEAD~1").trim()
        val oldHead = repo.git("rev-parse", "HEAD").trim()
        val file = repo.write("src/Second.kt", "second changed\n")

        val result = withService(repo) { service ->
            service.commit(listOf(file), "Amended subject", ready(service))
        }

        assertTrue(result.ok, result.message)
        val newHead = repo.git("rev-parse", "HEAD").trim()
        assertNotEquals(oldHead, newHead)
        assertEquals("$newHead $parent", repo.git("rev-list", "--parents", "-n", "1", "HEAD").trim())
        assertEquals("2", repo.git("rev-list", "--count", "HEAD").trim())
        assertEquals("second changed\n", repo.git("show", "HEAD:src/Second.kt"))
        assertEquals("", repo.git("status", "--porcelain").trim())
        assertEquals(oldHead, result.previousCommitId)
        assertEquals("Amended subject", result.subject)
        assertEquals(newHead, result.commitId)
    }

    @Test
    fun `an amend with no paths replaces only the message and keeps a staged change staged`() {
        val repo = repoWithTwoCommits()
        val oldHead = repo.git("rev-parse", "HEAD").trim()
        val other = repo.write("src/First.kt", "first changed\n")
        repo.git("add", "--", "src/First.kt")

        val result = withService(repo) { service ->
            service.commit(emptyList(), "New message", ready(service))
        }

        assertTrue(result.ok, result.message)
        assertEquals("New message", repo.git("log", "-1", "--format=%B").trim())
        assertNotEquals(oldHead, repo.git("rev-parse", "HEAD").trim())
        assertEquals("2", repo.git("rev-list", "--count", "HEAD").trim())
        assertTrue(repo.git("status", "--porcelain=v2").contains("M."), repo.git("status", "--porcelain=v2"))
        assertEquals("first\n", repo.git("show", "HEAD:src/First.kt"))
        assertTrue(Files.exists(other))
    }

    @Test
    fun `an untracked included file is added by the amend`() {
        val repo = repoWithTwoCommits()
        val file = repo.write("src/New.kt", "new\n")

        val result = withService(repo) { service ->
            service.commit(listOf(file), "Add new", ready(service))
        }

        assertTrue(result.ok, result.message)
        assertEquals("new\n", repo.git("show", "HEAD:src/New.kt"))
        assertEquals("", repo.git("status", "--porcelain").trim())
        assertEquals(0, result.addedToIndex)
    }

    @Test
    fun `HEAD moved after Amend was turned on refuses and changes nothing`() {
        val repo = repoWithTwoCommits()
        val file = repo.root.resolve("src/Second.kt")

        val result = withService(repo) { service ->
            val base = ready(service)
            repo.write("src/Second.kt", "changed\n")
            val moved = repo.commitAll("Move HEAD")
            service.commit(listOf(file), "Reword", base) to moved
        }

        assertFalse(result.first.ok)
        assertEquals("HEAD changed since Amend was turned on: turn it off and on again", result.first.message)
        assertEquals(result.second, repo.git("rev-parse", "HEAD").trim())
        assertEquals("", repo.git("status", "--porcelain").trim())
        assertEquals(emptyList<Path>(), result.first.restored)
    }

    @Test
    fun `HEAD pushed after Amend was turned on refuses and changes nothing`() {
        val repo = repoWithTwoCommits()
        val file = repo.write("src/Second.kt", "second changed\n")
        val oldHead = repo.git("rev-parse", "HEAD").trim()

        val result = withService(repo) { service ->
            val base = ready(service)
            repo.bareRemote()
            repo.git("push", "-u", "origin", "main")
            service.commit(listOf(file), "Reword", base)
        }

        assertFalse(result.ok)
        assertEquals("HEAD is already pushed to origin/main: amending it needs a force push", result.message)
        assertEquals(oldHead, repo.git("rev-parse", "HEAD").trim())
        assertEquals(" M src/Second.kt", repo.git("status", "--porcelain").trimEnd())
    }

    @Test
    fun `the amend message names the new and the old short hash`() {
        val repo = repoWithTwoCommits()
        val oldHead = repo.git("rev-parse", "HEAD").trim()
        val oldShort = repo.git("rev-parse", "--short", oldHead).trim()
        val file = repo.write("src/Second.kt", "second changed\n")

        val (result, newShort) = withService(repo) { service ->
            val outcome = service.commit(listOf(file), "Reworded subject", ready(service))
            outcome to repo.git("rev-parse", "--short", "HEAD").trim()
        }

        assertTrue(result.ok, result.message)
        assertEquals("Amended $newShort Reworded subject on main (was $oldShort)", result.message)
    }

    @Test
    fun `the pushed check runs before HEAD's message is read`() {
        val repo = repoWithTwoCommits()
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")

        val start = withService(repo) { service ->
            service.executor = FailingCommandExecutor(
                CliGitExecutor(TEST_GIT, repo.environment),
                "log",
                GitResult(128, ByteArray(0), "fatal: bad revision\n", timedOut = false),
            )
            service.amendStart()
        }

        assertEquals(
            AmendStart.Refused("HEAD is already pushed to origin/main: amending it needs a force push"),
            start,
        )
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
