package stride.app.run

import java.nio.file.Files
import java.nio.file.Path

/** Builds the process command for one Gradle invocation and its console rendering. */
object GradleCommand {
    fun build(
        workingDir: Path,
        args: List<String>,
        initScript: Path,
        shell: String = shellOrDefault(),
        gradlewExists: (Path) -> Boolean = { Files.isExecutable(it) },
    ): List<String> {
        val gradlew = workingDir.resolve("gradlew")
        val launcher = if (gradlewExists(gradlew)) gradlew.toAbsolutePath().toString() else "gradle"
        return listOf(
            shell,
            "-l",
            "-c",
            "exec \"\$@\"",
            "stride",
            launcher,
            "--console=plain",
            "--init-script",
            initScript.toString(),
            *args.toTypedArray(),
        )
    }

    /** The command as one line, quoting only elements that hold whitespace or quotes. */
    fun display(command: List<String>): String = command.joinToString(" ") { element ->
        if (element.any { it.isWhitespace() || it == '\'' || it == '"' }) "'$element'" else element
    }

    fun testArgs(gradlePath: String, filter: String): List<String> =
        listOf("$gradlePath:test", "--tests", filter)

    internal fun shellOrDefault(shell: String? = System.getenv("SHELL")): String =
        shell?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
}
