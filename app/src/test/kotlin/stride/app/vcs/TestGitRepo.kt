package stride.app.vcs

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A throwaway Git repository for tests, cut off from the user's configuration, credentials and
 * any repository above it. Every test that runs git must create its repository through [create]
 * and hand [environment] to the executor under test, so a failed `git init` can never fall
 * through to the repository the tests run in.
 */
class TestGitRepo private constructor(val root: Path, val home: Path) {

    /** Environment for git under test: inherited variables are dropped where they would leak. */
    val environment: Map<String, String?> = isolatedEnvironment(ceiling = root.parent, home = home)

    /** Runs `git -C root <args>` and returns stdout; throws when [check] and git fails. */
    fun git(vararg args: String, check: Boolean = true): String {
        val result = run(listOf("git", "-C", root.toString()) + args, root, environment)
        if (check && result.exitCode != 0) {
            error("git ${args.joinToString(" ")} failed (${result.exitCode}): ${result.stderr}")
        }
        return result.stdout
    }

    fun write(relative: String, text: String): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    /** Stages everything and commits; returns the new HEAD commit id. */
    fun commitAll(message: String): String {
        git("add", "-A")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD").trim()
    }

    /** Creates a bare repository next to [root] and adds it as remote [name]; returns its path. */
    fun bareRemote(name: String = "origin"): Path {
        val bare = root.resolveSibling("${root.fileName}-$name.git")
        val init = run(listOf("git", "init", "-q", "--bare", "-b", "main", bare.toString()), root.parent, environment)
        check(init.exitCode == 0) { "git init --bare failed: ${init.stderr}" }
        git("remote", "add", name, bare.toString())
        return bare
    }

    class Result(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        private val LEAKING_VARIABLES = listOf(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR", "GIT_NAMESPACE", "GIT_PREFIX",
            "GIT_SSH_COMMAND", "GIT_SSH", "GIT_ASKPASS", "SSH_ASKPASS", "GIT_CONFIG",
            "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR",
        )

        /**
         * Creates `<parent>/<name>` as a new repository on branch `main`. [parent] is the ceiling:
         * git never looks above it for a repository.
         */
        fun create(parent: Path, name: String = "api-server"): TestGitRepo {
            val root = parent.resolve(name).createDirectories().toRealPath()
            val home = Files.createDirectories(parent.resolve("$name-home")).toRealPath()
            val repo = TestGitRepo(root, home)
            val init = run(listOf("git", "init", "-q", "-b", "main", root.toString()), root.parent, repo.environment)
            check(init.exitCode == 0) { "git init failed: ${init.stderr}" }
            val top = repo.git("rev-parse", "--show-toplevel").trim()
            check(Path.of(top).toRealPath() == root) {
                "git resolved $top instead of the temporary repository $root"
            }
            return repo
        }

        fun isolatedEnvironment(ceiling: Path, home: Path): Map<String, String?> = buildMap {
            LEAKING_VARIABLES.forEach { put(it, null) }
            put("GIT_CEILING_DIRECTORIES", ceiling.toString())
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("HOME", home.toString())
            put("XDG_CONFIG_HOME", home.resolve(".config").toString())
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_AUTHOR_NAME", "Test Author")
            put("GIT_AUTHOR_EMAIL", "author@example.com")
            put("GIT_COMMITTER_NAME", "Test Author")
            put("GIT_COMMITTER_EMAIL", "author@example.com")
            put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z")
            put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z")
        }

        fun run(command: List<String>, workDir: Path, environment: Map<String, String?>): Result {
            val builder = ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectInput(File("/dev/null"))
            val env = builder.environment()
            environment.forEach { (key, value) -> if (value == null) env.remove(key) else env[key] = value }
            val process = builder.start()
            val stderr = StringBuilder()
            val errReader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
            errReader.start()
            val stdout = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("${command.joinToString(" ")} timed out")
            }
            errReader.join()
            return Result(process.exitValue(), stdout, stderr.toString())
        }
    }
}
