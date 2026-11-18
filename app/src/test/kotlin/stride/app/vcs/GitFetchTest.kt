package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitFetchTest {

    @TempDir
    lateinit var temp: Path

    private class Upstream(val repo: TestGitRepo, val bare: Path)

    private fun upstream(): Upstream {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Initial commit")
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        return Upstream(repo, bare)
    }

    private fun commitIn(up: Upstream, clone: Path, file: String, text: String, message: String): String {
        val target = clone.resolve(file)
        target.parent.createDirectories()
        target.writeText(text)
        for (args in listOf(listOf("add", "-A"), listOf("commit", "-q", "-m", message), listOf("push"))) {
            val result = TestGitRepo.run(listOf("git", "-C", clone.toString()) + args, clone, up.repo.environment)
            assertEquals(0, result.exitCode, result.stderr)
        }
        return TestGitRepo.run(
            listOf("git", "-C", clone.toString(), "rev-parse", "HEAD"),
            clone,
            up.repo.environment,
        ).stdout.trim()
    }

    private fun cloneOf(up: Upstream): Path {
        val clone = Files.createTempDirectory(temp, "web-dashboard")
        val result = TestGitRepo.run(
            listOf("git", "clone", up.bare.toString(), clone.toString()),
            temp,
            up.repo.environment,
        )
        assertEquals(0, result.exitCode, result.stderr)
        return clone
    }

    @Test
    fun `fetch without a remote is refused before git runs`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Initial commit")

        val (result, commands) = withService(repo) { service ->
            val recorder = RecordingExecutor(CliGitExecutor(TEST_GIT, repo.environment))
            service.executor = recorder
            val outcome = service.fetch()
            outcome to service.console.map { it.command }
        }

        assertEquals("No remotes: add one in the terminal", result.message)
        assertTrue(commands.none { it.startsWith("git fetch") }, commands.toString())
    }

    @Test
    fun `fetch reports the new commits and leaves the branch and files alone`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/One.kt", "one\n", "Remote one")
        commitIn(up, clone, "src/Two.kt", "two\n", "Remote two")
        val head = up.repo.git("rev-parse", "HEAD").trim()
        val content = Files.readString(up.repo.root.resolve("src/Main.kt"))

        val (result, snapshot) = withService(up.repo) { service -> service.fetch() to service.snapshot }

        assertTrue(result.ok, result.message)
        assertEquals("Fetched from origin: 2 new commits on origin/main", result.message)
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals(content, Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertEquals(2, snapshot.branch?.behind)
    }

    @Test
    fun `fetch with nothing new names the remote alone`() {
        val up = upstream()

        val result = withService(up.repo) { it.fetch() }

        assertTrue(result.ok, result.message)
        assertEquals("Fetched from origin", result.message)
    }

    @Test
    fun `fetch carries the ssh batch mode unless the repository configures ssh`() {
        val up = upstream()

        val (defaulted, configured) = withService(up.repo) { service ->
            val first = RecordingExecutor(CliGitExecutor(TEST_GIT, up.repo.environment))
            service.executor = first
            service.fetch()
            up.repo.git("config", "core.sshCommand", "ssh -F /dev/null")
            val second = RecordingExecutor(CliGitExecutor(TEST_GIT, up.repo.environment))
            service.executor = second
            service.fetch()
            first.fetchCommand() to second.fetchCommand()
        }

        // A user-level GIT_SSH_COMMAND wins over the repository check, so it also suppresses ours.
        val ambient = System.getenv("GIT_SSH_COMMAND").isNullOrBlank() && System.getenv("GIT_SSH").isNullOrBlank()
        if (ambient) assertEquals("ssh -o BatchMode=yes", defaulted.environment["GIT_SSH_COMMAND"])
        assertNull(configured.environment["GIT_SSH_COMMAND"])
    }

    @Test
    fun `the fetch and update limits default to 90 and 120 seconds`() {
        assertEquals(90.seconds, GitTimeouts().fetch)
        assertEquals(120.seconds, GitTimeouts().update)
    }

    @Test
    fun `a fetch refused over credentials reports the credentials message`() {
        val up = upstream()

        val result = withStubbedFetch(up.repo, failure("Permission denied (publickey).\n")) { it.fetch() }

        assertEquals("Fetch needs credentials or a passphrase: fetch from the terminal (⌥F12)", result.message)
    }

    @Test
    fun `any other fetch failure reports its first line`() {
        val up = upstream()

        val result = withStubbedFetch(up.repo, failure("fatal: the remote end hung up\nsecond line\n")) { it.fetch() }

        assertEquals("Fetch failed: fatal: the remote end hung up", result.message)
    }

    @Test
    fun `a fetch timeout reports the limit`() {
        val up = upstream()
        val timeout = GitResult(-1, ByteArray(0), "", timedOut = true)

        val result = withStubbedFetch(up.repo, timeout) { it.fetch() }

        assertEquals("Fetch failed: timed out after 90 s", result.message)
    }

    @Test
    fun `a fetch in progress makes fetch, updatePlan and update report busy`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Remote.kt", "remote\n", "Remote one")
        val gate = CountDownLatch(1)

        withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.executor = GatedFetchExecutor(CliGitExecutor(TEST_GIT, up.repo.environment), gate)
            coroutineScope {
                val running = launch(Dispatchers.IO) { service.fetch() }
                try {
                    assertTrue(eventually { service.busy == "Fetching…" }, "the fetch did not start")
                    assertEquals("Git is busy: Fetching…", service.fetch().message)
                    assertEquals(UpdatePlan.Refused("Git is busy: Fetching…"), service.updatePlan())
                    assertEquals(
                        "Git is busy: Fetching…",
                        service.update(ready, UpdateStrategy.FAST_FORWARD).message,
                    )
                } finally {
                    gate.countDown()
                    running.join()
                }
            }
        }
    }

    private fun failure(stderr: String): GitResult = GitResult(128, ByteArray(0), stderr, timedOut = false)

    private fun <T> withStubbedFetch(
        repo: TestGitRepo,
        result: GitResult,
        block: suspend (GitService) -> T,
    ): T = withService(repo) { service ->
        service.executor = FetchStubExecutor(CliGitExecutor(TEST_GIT, repo.environment), result)
        block(service)
    }

    /** Answers every `fetch` with [result] and every other command through [delegate]. */
    private class FetchStubExecutor(private val delegate: GitExecutor, private val result: GitResult) : GitExecutor {
        override fun run(command: GitCommand): GitResult =
            if (command.args.firstOrNull() == "fetch") result else delegate.run(command)
    }

    /** Holds every `fetch` until [gate] opens. */
    private class GatedFetchExecutor(private val delegate: GitExecutor, private val gate: CountDownLatch) : GitExecutor {
        override fun run(command: GitCommand): GitResult {
            if (command.args.firstOrNull() == "fetch") gate.await(10, TimeUnit.SECONDS)
            return delegate.run(command)
        }
    }

    private class RecordingExecutor(private val delegate: GitExecutor) : GitExecutor {
        val commands = CopyOnWriteArrayList<GitCommand>()

        override fun run(command: GitCommand): GitResult {
            commands += command
            return delegate.run(command)
        }

        fun fetchCommand(): GitCommand =
            commands.first { it.args.firstOrNull() == "fetch" }
    }
}
