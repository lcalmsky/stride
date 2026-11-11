package stride.app.run

import java.nio.file.Path

enum class RunKind { TEST, TASK, APPLICATION }

/**
 * One run. For a Gradle run, [args] holds the Gradle arguments after the wrapper;
 * `--console=plain` and `--init-script` are not part of [args] because [GradleCommand]
 * adds them. For a direct run, [command] is the full command line and [args] is empty.
 */
data class RunConfiguration(
    val name: String,
    val workingDir: Path,
    val args: List<String> = emptyList(),
    val kind: RunKind,
    val command: List<String>? = null,
) {
    /** Everything but [name]: two runs with the same identity are the same configuration. */
    val identity: RunIdentity
        get() = RunIdentity(kind, workingDir.toAbsolutePath().normalize(), args, command)
}

data class RunIdentity(
    val kind: RunKind,
    val workingDir: Path,
    val args: List<String>,
    val command: List<String>?,
)
