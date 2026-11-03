package stride.app.run

import java.nio.file.Path

enum class RunKind { TEST, TASK }

/**
 * One run: the Gradle arguments after the wrapper, in [args]. `--console=plain` and
 * `--init-script` are not part of [args]; [GradleCommand] adds them.
 */
data class RunConfiguration(
    val name: String,
    val workingDir: Path,
    val args: List<String>,
    val kind: RunKind,
)
