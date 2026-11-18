package stride.app.vcs

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CliGitExecutorTest {

    @TempDir
    lateinit var temp: Path

    private val git: Path = requireNotNull(GitBinary.resolve()) { "these tests need a git binary" }

    @Test
    fun `a read disables optional locks and a write does not`() {
        val executor = CliGitExecutor(git, emptyMap(), baseEnvironment = mapOf("PATH" to "/usr/bin:/bin"))

        val read = executor.environmentFor(GitCommand(listOf("status"), temp, 15.seconds))
        assertEquals("0", read["GIT_OPTIONAL_LOCKS"])
        assertEquals("0", read["GIT_TERMINAL_PROMPT"])
        assertEquals("cat", read["GIT_PAGER"])
        assertEquals(":", read["GIT_EDITOR"])
        assertEquals("1", read["GIT_LITERAL_PATHSPECS"])

        val write = executor.environmentFor(GitCommand(listOf("commit"), temp, 15.seconds, write = true))
        assertNull(write["GIT_OPTIONAL_LOCKS"])
    }

    @Test
    fun `the leaking variables are dropped from whatever base environment it got`() {
        val base = mapOf(
            "PATH" to "/usr/bin:/bin",
            "GIT_DIR" to "/elsewhere/.git",
            "GIT_WORK_TREE" to "/elsewhere",
            "GIT_INDEX_FILE" to "/elsewhere/index",
            "GIT_OBJECT_DIRECTORY" to "/elsewhere/objects",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES" to "/elsewhere/alternates",
            "GIT_COMMON_DIR" to "/elsewhere/common",
            "GIT_NAMESPACE" to "namespace",
            "GIT_PREFIX" to "sub/",
        )
        val executor = CliGitExecutor(git, emptyMap(), baseEnvironment = base)

        val environment = executor.environmentFor(GitCommand(listOf("status"), temp, 15.seconds))
        assertEquals("/usr/bin:/bin", environment["PATH"])
        for (name in CliGitExecutor.LEAKING_VARIABLES) assertNull(environment[name], name)
    }

    @Test
    fun `a command variable overrides a constant and a null removes a base variable`() {
        val executor = CliGitExecutor(
            git,
            mapOf("GIT_PAGER" to "more", "PATH" to null),
            baseEnvironment = mapOf("PATH" to "/usr/bin:/bin"),
        )

        val environment = executor.environmentFor(
            GitCommand(listOf("status"), temp, 15.seconds, environment = mapOf("GIT_PAGER" to "less")),
        )
        assertEquals("less", environment["GIT_PAGER"])
        assertNull(environment["PATH"])
    }

    @Test
    fun `GIT_DIR in the base environment cannot point the command at another repository`() {
        val repo = TestGitRepo.create(temp)
        val other = TestGitRepo.create(temp, "web-dashboard")
        val base = System.getenv().toMutableMap()
        base["GIT_DIR"] = other.root.resolve(".git").toString()
        val executor = CliGitExecutor(git, repo.environment, baseEnvironment = base)

        val result = executor.run(GitCommand(listOf("rev-parse", "--show-toplevel"), repo.root, 15.seconds))

        assertTrue(result.ok, result.stderr)
        assertEquals(repo.root.toString(), result.stdoutText().trim())
    }

    @Test
    fun `stdout and stderr are separate and the exit code is preserved`() {
        val repo = TestGitRepo.create(temp)
        val executor = CliGitExecutor(git, repo.environment)

        val result = executor.run(GitCommand(listOf("rev-parse", "--verify", "nope"), repo.root, 15.seconds))

        assertFalse(result.ok)
        assertEquals("", result.stdoutText())
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `literal pathspecs add the bracket file and not a glob`() {
        val repo = TestGitRepo.create(temp)
        repo.write("a[1].kt", "bracket\n")
        repo.root.resolve("*.kt").writeText("glob\n")
        repo.root.resolve("plain.kt").writeText("plain\n")
        val executor = CliGitExecutor(git, repo.environment)

        val add = executor.run(GitCommand(listOf("add", "--", "a[1].kt"), repo.root, 15.seconds))

        assertTrue(add.ok, add.stderr)
        assertEquals("a[1].kt\n", repo.git("diff", "--cached", "--name-only"))
    }

    @Test
    fun `a command over its timeout is destroyed and reports timedOut`() {
        val repo = TestGitRepo.create(temp)
        val token = "stride-timeout-${System.nanoTime()}"
        repo.git("config", "alias.slow", "!sleep 30 && echo $token")
        val executor = CliGitExecutor(git, repo.environment)

        val started = System.nanoTime()
        val result = executor.run(GitCommand(listOf("slow"), repo.root, 400.milliseconds))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(result.timedOut)
        assertFalse(result.ok)
        assertTrue(elapsedMillis < 10_000, "the command took $elapsedMillis ms")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val alive = runningProcesses(token)
            if (!alive) return
            Thread.sleep(50)
        }
        assertFalse(runningProcesses(token), "the timed-out command is still running")
    }

    @Test
    fun `ssh runs in batch mode only when the user configured no ssh`() {
        val repo = TestGitRepo.create(temp)
        val executor = CliGitExecutor(git, repo.environment)

        assertEquals(
            mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes"),
            sshBatchModeEnvironment(executor, repo.root, 15.seconds, baseEnvironment = emptyMap()),
        )
        assertEquals(
            emptyMap<String, String?>(),
            sshBatchModeEnvironment(
                executor,
                repo.root,
                15.seconds,
                baseEnvironment = mapOf("GIT_SSH_COMMAND" to "ssh -i ~/.ssh/other"),
            ),
        )
        assertEquals(
            emptyMap<String, String?>(),
            sshBatchModeEnvironment(
                executor,
                repo.root,
                15.seconds,
                baseEnvironment = mapOf("GIT_SSH" to "/usr/bin/ssh-other"),
            ),
        )

        repo.git("config", "core.sshCommand", "ssh -o Port=2222")
        assertEquals(
            emptyMap<String, String?>(),
            sshBatchModeEnvironment(executor, repo.root, 15.seconds, baseEnvironment = emptyMap()),
        )
    }

    private fun runningProcesses(token: String): Boolean =
        ProcessHandle.allProcesses().toList().any { it.info().commandLine().orElse("").contains(token) }
}
