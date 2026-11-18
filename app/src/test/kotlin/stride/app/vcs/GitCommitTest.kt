package stride.app.vcs

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Project
import stride.core.services.service

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

    @Test
    fun `cancelling a commit stops the hook process`() {
        val repo = repoWithCommit()
        val hook = writeHook(repo, "pre-commit", "#!/bin/sh\nsleep 30 && echo done\n")
        val file = repo.write("src/New.kt", "new\n")

        withService(repo) { service ->
            val job = service.launch { service.commit(listOf(file), "Slow hook") }
            assertTrue(eventually(10_000) { hookRunning(hook) }, "the hook never started")

            job.cancel()

            assertTrue(eventually(5_000) { job.isCompleted }, "the cancelled commit did not finish")
            assertTrue(eventually(5_000) { !hookRunning(hook) }, "the hook is still running")
        }
    }

    @Test
    fun `dispose interrupts a call another scope started and kills its hook`() {
        val repo = repoWithCommit()
        val hook = writeHook(repo, "pre-commit", "#!/bin/sh\nsleep 30 && echo done\n")
        val file = repo.write("src/New.kt", "new\n")
        val project = Project(repo.root)
        val service = project.service<GitService>()
        service.executor = CliGitExecutor(TEST_GIT, repo.environment)
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            scope.launch { runCatching { service.commit(listOf(file), "Slow hook") } }
            assertTrue(eventually(10_000) { hookRunning(hook) }, "the hook never started")

            project.dispose()

            assertTrue(eventually(5_000) { !hookRunning(hook) }, "the hook is still running after dispose")
        } finally {
            scope.cancel()
            project.dispose()
        }
    }

    @Test
    fun `a dispose interrupt does not leak into the commit's next git call`() {
        val repo = repoWithCommit()
        val file = repo.write("src/New.kt", "new\n")
        val project = Project(repo.root)
        val service = project.service<GitService>()
        val executor = InterruptedReadExecutor(CliGitExecutor(TEST_GIT, repo.environment))
        service.executor = executor
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val commit = scope.async { service.commit(listOf(file), "Add new") }
            assertTrue(executor.callEntered.await(10, TimeUnit.SECONDS), "the commit never reached git")

            project.dispose()

            val result = runBlocking { withTimeout(10.seconds) { commit.await() } }
            assertFalse(result.ok, result.message)
            assertEquals(
                false,
                executor.flagOnNextCall,
                "the commit's next git call must run, and not on the interrupted thread with its flag still set",
            )
        } finally {
            scope.cancel()
            project.dispose()
        }
    }

    @Test
    fun `a commit failing after add names the new files it added`() {
        val repo = repoWithCommit()
        writeHook(repo, "pre-commit", "#!/bin/sh\necho 'hook refused the commit' >&2\nexit 1\n")
        val file = repo.write("src/New.kt", "new\n")

        val result = withService(repo) { it.commit(listOf(file), "Blocked") }

        assertFalse(result.ok)
        assertTrue(result.message.endsWith("(1 new file was added to the index)"), result.message)
        assertEquals(1, result.addedToIndex)
    }

    @Test
    fun `a refresh requested during a commit runs once after it`() {
        val repo = repoWithCommit()
        val file = repo.write("src/Kept.kt", "changed\n")

        withService(repo) { service ->
            val executor = BlockingCommitExecutor(CliGitExecutor(TEST_GIT, repo.environment))
            service.executor = executor
            val job = service.launch { service.commit(listOf(file), "Change") }
            assertTrue(executor.commitEntered.await(5, TimeUnit.SECONDS), "the commit never started")

            service.refresh()
            Thread.sleep(500)
            executor.release.countDown()

            assertTrue(eventually(10_000) { job.isCompleted }, "the commit did not finish")
            assertTrue(eventually { executor.statusCalls.get() >= 2 }, "the status after the commit never ran")
            Thread.sleep(700)
            assertEquals(2, executor.statusCalls.get())
            val commitIndex = executor.commands.indexOfFirst { it.firstOrNull() == "commit" }
            assertTrue(commitIndex >= 0, "no commit reached git")
            val statusIndexes = executor.commands.withIndex()
                .filter { it.value.firstOrNull() == "status" }
                .map { it.index }
            assertTrue(statusIndexes.all { it > commitIndex }, "status ran while the commit held the gate")
        }
    }

    @Test
    fun `a failing message file write is a commit failure, not an exception`() {
        val repo = repoWithCommit()
        val head = repo.git("rev-parse", "HEAD").trim()
        val file = repo.write("src/Kept.kt", "changed\n")

        val result = withService(repo) { service ->
            service.messageFileWriter = { _, _ -> throw IOException("No space left on device") }
            service.commit(listOf(file), "Change")
        }

        assertFalse(result.ok)
        assertEquals("Commit failed: No space left on device", result.message)
        assertEquals(head, repo.git("rev-parse", "HEAD").trim())
    }

    @Test
    fun `an unstaged rename of the included paths is committed as one rename`() {
        val repo = repoWithCommit()
        val original = repo.root.resolve("src/Kept.kt")
        Files.delete(original)
        val renamed = repo.write("src/Renamed.kt", "kept\n")

        val result = withService(repo) { it.commit(listOf(renamed, original), "Rename") }

        assertTrue(result.ok, result.message)
        val nameStatus = repo.git("show", "--name-status", "-M", "--format=", "HEAD")
            .lines().firstOrNull { it.isNotBlank() }.orEmpty()
        assertTrue(nameStatus.startsWith("R"), nameStatus)
        assertTrue(nameStatus.endsWith("src/Renamed.kt"), nameStatus)
    }

    @Test
    fun `a file whose name has spaces is committed`() {
        val repo = repoWithCommit()
        val file = repo.write("src/My File.kt", "spaces\n")

        val result = withService(repo) { it.commit(listOf(file), "Spaces") }

        assertTrue(result.ok, result.message)
        assertEquals("spaces\n", repo.git("show", "HEAD:src/My File.kt"))
    }

    @Test
    fun `a project in a repository subfolder commits under its root`() {
        val repo = TestGitRepo.create(temp)
        repo.write("sub/src/App.kt", "app\n")
        repo.write("outside.txt", "outside\n")
        repo.commitAll("Initial commit")
        val file = repo.write("sub/src/App.kt", "app changed\n")

        val result = withService(repo, root = repo.root.resolve("sub")) { it.commit(listOf(file), "Change") }

        assertTrue(result.ok, result.message)
        assertEquals("app changed\n", repo.git("show", "HEAD:sub/src/App.kt"))
        assertTrue(repo.git("status", "--porcelain").isBlank())
    }

    private fun hookRunning(hook: Path): Boolean =
        ProcessHandle.current().descendants().anyMatch { handle ->
            val line = handle.info().commandLine().orElse("")
            line.contains(hook.toString()) || line.contains(".git/hooks/${hook.fileName}")
        }

    private class BlockingCommitExecutor(private val delegate: GitExecutor) : GitExecutor {
        val commitEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val statusCalls = AtomicInteger()
        val commands = CopyOnWriteArrayList<List<String>>()

        override fun run(command: GitCommand): GitResult {
            commands += command.args
            when (command.args.firstOrNull()) {
                "status" -> statusCalls.incrementAndGet()
                "commit" -> {
                    commitEntered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            return delegate.run(command)
        }
    }

    /**
     * Blocks the commit's first git call and answers the interrupt the way [CliGitExecutor] does.
     * The pool clears a leftover flag only when it goes idle, so the flag is read when the commit's
     * next git call runs on the same thread, not from the test thread after the commit returns.
     */
    private class InterruptedReadExecutor(private val delegate: GitExecutor) : GitExecutor {
        val callEntered = CountDownLatch(1)

        @Volatile
        var flagOnNextCall: Boolean? = null

        override fun run(command: GitCommand): GitResult {
            when (command.args.firstOrNull()) {
                "ls-files" -> {
                    val thread = Thread.currentThread()
                    callEntered.countDown()
                    try {
                        Thread.sleep(30_000)
                    } catch (e: InterruptedException) {
                        thread.interrupt()
                        return GitResult(-1, ByteArray(0), "interrupted", timedOut = true)
                    }
                    return GitResult(1, ByteArray(0), "the call was never interrupted", timedOut = false)
                }
                "commit" -> flagOnNextCall = Thread.currentThread().isInterrupted
            }
            return delegate.run(command)
        }
    }

    private fun writeHook(repo: TestGitRepo, name: String, content: String): Path {
        val hook = repo.root.resolve(".git/hooks/$name")
        Files.createDirectories(hook.parent)
        hook.writeText(content)
        hook.toFile().setExecutable(true)
        return hook
    }
}
