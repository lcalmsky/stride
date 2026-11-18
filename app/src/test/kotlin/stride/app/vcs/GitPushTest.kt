package stride.app.vcs

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitPushTest {

    @TempDir
    lateinit var temp: Path

    private fun repoWithCommit(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "fun main() {}\n")
        repo.commitAll("Initial commit")
        return repo
    }

    @Test
    fun `a branch with an upstream pushes the outgoing commits`() {
        val repo = repoWithCommit()
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        repo.write("src/Main.kt", "fun main() { println() }\n")
        repo.commitAll("Second commit")

        val plan = withService(repo) { it.pushPlan() }
        val ready = assertInstanceOf(PushPlan.Ready::class.java, plan)
        assertEquals("main", ready.branch)
        assertEquals("origin", ready.remote)
        assertEquals("refs/heads/main", ready.remoteRef)
        assertFalse(ready.newBranch)
        assertEquals(1, ready.outgoing.size)
        assertEquals("Second commit", ready.outgoing.single().subject)
        assertEquals(0, ready.behind)

        val result = withService(repo) { it.push(ready) }
        assertTrue(result.ok, result.message)
        assertEquals("Pushed 1 commits to origin/main", result.message)
    }

    @Test
    fun `a branch without an upstream is pushed to origin as a new branch with -u`() {
        val repo = repoWithCommit()
        repo.bareRemote()

        val plan = withService(repo) { it.pushPlan() }
        val ready = assertInstanceOf(PushPlan.Ready::class.java, plan)
        assertTrue(ready.newBranch)
        assertEquals("origin", ready.remote)
        assertEquals("refs/heads/main", ready.remoteRef)

        val result = withService(repo) { it.push(ready) }
        assertTrue(result.ok, result.message)
        assertEquals("origin/main", repo.git("rev-parse", "--abbrev-ref", "@{upstream}").trim())
    }

    @Test
    fun `nothing ahead of the upstream is refused`() {
        val repo = repoWithCommit()
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")

        val plan = withService(repo) { it.pushPlan() }

        assertEquals(PushPlan.Refused("Nothing to push"), plan)
    }

    @Test
    fun `a non-fast-forward push is rejected with the pull message`() {
        val repo = repoWithCommit()
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        val clone = temp.resolve("web-dashboard").createDirectories().resolve("clone")
        val cloned = TestGitRepo.run(listOf("git", "clone", bare.toString(), clone.toString()), temp, repo.environment)
        assertEquals(0, cloned.exitCode, cloned.stderr)
        val cloneCommit = TestGitRepo.run(
            listOf("git", "-C", clone.toString(), "commit", "--allow-empty", "-m", "Clone commit"),
            temp,
            repo.environment,
        )
        assertEquals(0, cloneCommit.exitCode, cloneCommit.stderr)
        val pushed = TestGitRepo.run(listOf("git", "-C", clone.toString(), "push"), temp, repo.environment)
        assertEquals(0, pushed.exitCode, pushed.stderr)
        repo.git("fetch", "origin")
        repo.write("src/Main.kt", "local\n")
        repo.commitAll("Local commit")

        val (plan, result) = withService(repo) { service ->
            val ready = service.pushPlan() as PushPlan.Ready
            ready to service.push(ready)
        }

        assertEquals(1, plan.behind)
        assertFalse(result.ok)
        assertEquals("Push rejected: pull the remote changes first", result.message)
    }

    @Test
    fun `a detached head cannot be pushed`() {
        val repo = repoWithCommit()
        repo.bareRemote()
        repo.git("checkout", "--detach")

        val plan = withService(repo) { it.pushPlan() }

        assertEquals(PushPlan.Refused("Push needs a branch: HEAD is detached"), plan)
    }

    @Test
    fun `several remotes without origin are refused`() {
        val repo = repoWithCommit()
        repo.bareRemote("one")
        repo.bareRemote("two")

        val plan = withService(repo) { it.pushPlan() }

        assertEquals(PushPlan.Refused("No upstream: set one in the terminal"), plan)
    }

    @Test
    fun `no push argv ever carries a force option`() {
        val repo = repoWithCommit()
        repo.bareRemote()
        val recorder = RecordingExecutor(CliGitExecutor(TEST_GIT, repo.environment))
        val plan = withService(repo) { service ->
            service.executor = recorder
            val ready = service.pushPlan() as PushPlan.Ready
            service.push(ready)
            ready
        }

        assertTrue(plan.newBranch)
        val pushCommands = recorder.commands.filter { it.firstOrNull() == "push" }
        assertEquals(1, pushCommands.size)
        for (command in pushCommands) {
            assertTrue(command.contains("--porcelain"))
            assertFalse(command.any { it == "--force" || it == "-f" || it.startsWith("--force") || it.startsWith("+") })
        }
        assertTrue(recorder.commands.none { command -> command.any { it == "--force" || it.startsWith("--force") } })
    }

    @Test
    fun `a pruned upstream ref plans a push of a new branch`() {
        val repo = repoWithCommit()
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        val clone = temp.resolve("web-dashboard").createDirectories().resolve("api-client")
        val cloned = TestGitRepo.run(listOf("git", "clone", bare.toString(), clone.toString()), temp, repo.environment)
        assertEquals(0, cloned.exitCode, cloned.stderr)
        // The bare remote's HEAD is main, and deleting the current branch is denied by default.
        val allow = TestGitRepo.run(
            listOf("git", "-C", bare.toString(), "config", "receive.denyDeleteCurrent", "ignore"),
            temp,
            repo.environment,
        )
        assertEquals(0, allow.exitCode, allow.stderr)
        val deleted = TestGitRepo.run(
            listOf("git", "-C", clone.toString(), "push", "origin", "--delete", "main"),
            temp,
            repo.environment,
        )
        assertEquals(0, deleted.exitCode, deleted.stderr)
        repo.git("fetch", "--prune")

        val plan = withService(repo) { it.pushPlan() }

        val ready = assertInstanceOf(PushPlan.Ready::class.java, plan)
        assertTrue(ready.newBranch)
        assertEquals("origin", ready.remote)
        assertEquals("refs/heads/main", ready.remoteRef)
        assertEquals(listOf("Initial commit"), ready.outgoing.map { it.subject })
    }

    @Test
    fun `a push carries no tags even when follow tags is set`() {
        val repo = repoWithCommit()
        val bare = repo.bareRemote()
        repo.git("config", "push.followTags", "true")
        repo.git("tag", "-a", "v1", "-m", "release")

        val plan = withService(repo) { it.pushPlan() } as PushPlan.Ready
        val result = withService(repo) { it.push(plan) }

        assertTrue(result.ok, result.message)
        assertEquals("", repo.git("ls-remote", "--tags", bare.toString()).trim())
    }

    @Test
    fun `an ssh push refused over credentials reports the credentials message`() {
        val repo = repoWithCommit()
        val fakeSsh = temp.resolve("fake-ssh.sh")
        fakeSsh.writeText("#!/bin/sh\necho 'Permission denied (publickey).' >&2\nexit 255\n")
        fakeSsh.toFile().setExecutable(true)
        repo.git("remote", "add", "origin", "ssh://git@api-server.invalid/web-dashboard.git")
        repo.git("config", "core.sshCommand", fakeSsh.toString())

        val plan = withService(repo) { it.pushPlan() } as PushPlan.Ready
        val result = withService(repo) { it.push(plan) }

        assertFalse(result.ok)
        assertEquals("Push needs credentials or a passphrase: push from the terminal (⌥F12)", result.message)
    }

    private class RecordingExecutor(private val delegate: GitExecutor) : GitExecutor {
        val commands = CopyOnWriteArrayList<List<String>>()

        override fun run(command: GitCommand): GitResult {
            commands += command.args
            return delegate.run(command)
        }
    }
}
