package stride.app.vcs

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One invocation of the git command line. [args] is an argv list, never a shell string. */
data class GitCommand(
    val args: List<String>,
    val workDir: Path,
    val timeout: Duration,
    val write: Boolean = false,
    /** Variables for this command; a null value removes the variable from the child environment. */
    val environment: Map<String, String?> = emptyMap(),
)

class GitResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    fun stdoutText(): String = String(stdout, Charsets.UTF_8)
}

fun interface GitExecutor {
    fun run(command: GitCommand): GitResult
}

/** Stands in for git when [GitBinary.resolve] found nothing. */
internal object MissingGitExecutor : GitExecutor {
    override fun run(command: GitCommand): GitResult =
        GitResult(127, ByteArray(0), "Git is not installed", timedOut = false)
}

/**
 * Runs git as a child process with the environment rules of V3: the inherited variables that
 * would point git at another repository are dropped, prompts and the pager are disabled, and
 * pathspecs are literal. stdin is `/dev/null`; stdout and stderr are read concurrently so a full
 * pipe cannot stall the child. A timeout or an interrupt destroys the process and its descendants.
 */
class CliGitExecutor(
    private val git: Path,
    private val environment: Map<String, String?>,
    baseEnvironment: Map<String, String> = System.getenv(),
) : GitExecutor {

    private val baseEnvironment: Map<String, String> = buildEnvironment(baseEnvironment)

    /** [command]'s complete child environment, with [environment] and the command's own on top. */
    internal fun environmentFor(command: GitCommand): Map<String, String> {
        val result = baseEnvironment.toMutableMap()
        if (!command.write) result["GIT_OPTIONAL_LOCKS"] = "0"
        applyEnvironment(result, environment)
        applyEnvironment(result, command.environment)
        return result
    }

    override fun run(command: GitCommand): GitResult {
        val process = try {
            ProcessBuilder(listOf(git.toString()) + command.args)
                .directory(command.workDir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .also { builder ->
                    val child = builder.environment()
                    child.clear()
                    child.putAll(environmentFor(command))
                }
                .start()
        } catch (e: IOException) {
            return GitResult(127, ByteArray(0), e.message ?: "Cannot start git", timedOut = false)
        }
        val stdout = ByteArrayOutputStream()
        val stderr = StringBuilder()
        val stdoutThread = Thread { runCatching { process.inputStream.copyTo(stdout) } }
        val stderrThread = Thread {
            runCatching { process.errorStream.bufferedReader(Charsets.UTF_8).use { stderr.append(it.readText()) } }
        }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()
        val finished = try {
            process.waitFor(command.timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            destroy(process)
            Thread.currentThread().interrupt()
            stdoutThread.join(READ_JOIN_MILLIS)
            stderrThread.join(READ_JOIN_MILLIS)
            return GitResult(-1, stdout.toByteArray(), "interrupted", timedOut = true)
        }
        if (!finished) {
            destroy(process)
            stdoutThread.join(READ_JOIN_MILLIS)
            stderrThread.join(READ_JOIN_MILLIS)
            return GitResult(-1, stdout.toByteArray(), stderr.toString(), timedOut = true)
        }
        stdoutThread.join(READ_JOIN_MILLIS)
        stderrThread.join(READ_JOIN_MILLIS)
        return GitResult(process.exitValue(), stdout.toByteArray(), stderr.toString(), timedOut = false)
    }

    companion object {
        private const val READ_JOIN_MILLIS = 2_000L

        internal val LEAKING_VARIABLES = listOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_COMMON_DIR",
            "GIT_NAMESPACE",
            "GIT_PREFIX",
            // A parent git process sets these for hooks; they would point the child at another
            // repository's configuration.
            "GIT_CONFIG",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
        )

        private fun buildEnvironment(base: Map<String, String>): Map<String, String> {
            val result = base.toMutableMap()
            LEAKING_VARIABLES.forEach(result::remove)
            result["GIT_TERMINAL_PROMPT"] = "0"
            result["GIT_PAGER"] = "cat"
            result["GIT_EDITOR"] = ":"
            result["GIT_LITERAL_PATHSPECS"] = "1"
            return result
        }

        private fun applyEnvironment(target: MutableMap<String, String>, values: Map<String, String?>) {
            for ((key, value) in values) {
                if (value == null) target.remove(key) else target[key] = value
            }
        }

        private fun destroy(process: Process) {
            runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }
}

/**
 * The environment that makes an unreachable ssh fail at once instead of asking for a passphrase:
 * `GIT_SSH_COMMAND=ssh -o BatchMode=yes`. Empty when the user configured their own ssh
 * (`GIT_SSH_COMMAND`, `GIT_SSH` or the repository's `core.sshCommand`).
 */
internal fun sshBatchModeEnvironment(
    executor: GitExecutor,
    repository: Path,
    timeout: Duration,
    baseEnvironment: Map<String, String?> = System.getenv(),
): Map<String, String?> {
    if (!baseEnvironment["GIT_SSH_COMMAND"].isNullOrBlank() || !baseEnvironment["GIT_SSH"].isNullOrBlank()) {
        return emptyMap()
    }
    val configured = executor.run(GitCommand(listOf("config", "--get", "core.sshCommand"), repository, timeout))
    if (configured.ok && configured.stdoutText().isNotBlank()) return emptyMap()
    return mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
}

/**
 * Finds the git binary once per process (V3): the login shell's `command -v git` first, because an
 * app started from the Finder inherits a minimal PATH, then the Homebrew and system locations.
 * `/usr/bin/git` is only a real git when the command line tools are installed; without them the
 * stub opens an installer dialog.
 */
object GitBinary {
    private val lock = Any()
    private var resolved = false
    private var path: Path? = null

    val appleGit: Path = Path.of("/usr/bin/git")

    fun resolve(): Path? = synchronized(lock) {
        if (!resolved) {
            path = resolve(::shellLookup, ::isExecutable, ::xcodeSelectAvailable)
            resolved = true
        }
        path
    }

    internal fun resolve(
        shellLookup: (String) -> Path?,
        isExecutable: (Path) -> Boolean,
        xcodeSelectOk: () -> Boolean,
    ): Path? {
        val candidates = ArrayList<Path>(4)
        shellLookup("git")?.let(candidates::add)
        candidates.add(Path.of("/opt/homebrew/bin/git"))
        candidates.add(Path.of("/usr/local/bin/git"))
        candidates.add(appleGit)
        for (candidate in candidates) {
            val normalized = candidate.toAbsolutePath().normalize()
            if (normalized == appleGit && !xcodeSelectOk()) continue
            if (isExecutable(normalized)) return normalized
        }
        return null
    }

    private fun shellLookup(name: String): Path? {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return lookupInShell(listOf(shell, "-l", "-c", "command -v $name"))
    }

    /**
     * The answer of [command]. The output is drained on a second thread, because reading it to
     * EOF would block past the timeout; a shell still running when [timeout] passes is destroyed
     * with its descendants and the answer is `null`.
     */
    internal fun lookupInShell(
        command: List<String>,
        timeout: Duration = SHELL_LOOKUP_TIMEOUT,
    ): Path? = runCatching {
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = StringBuilder()
        val reader = Thread { runCatching { output.append(process.inputStream.bufferedReader().readText()) } }
        reader.isDaemon = true
        reader.start()
        val finished = try {
            process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) destroyTree(process)
        reader.join(READ_JOIN_MILLIS)
        if (!finished) return@runCatching null
        // A login shell may print its own startup messages before the answer; the answer is the
        // last absolute path on stdout.
        output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("/") }
            ?.let(Path::of)
    }.getOrNull()

    private fun destroyTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private fun isExecutable(path: Path): Boolean = Files.isExecutable(path)

    private fun xcodeSelectAvailable(): Boolean = runCatching {
        // The output must be drained, not closed: a child writing to a closed pipe can fail.
        val process = ProcessBuilder("xcode-select", "-p")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private const val READ_JOIN_MILLIS = 2_000L
    private val SHELL_LOOKUP_TIMEOUT = 5.seconds
}
