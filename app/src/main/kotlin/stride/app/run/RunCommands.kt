package stride.app.run

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Builds the Gradle arguments and the direct commands for running a file. */
object RunCommands {
    const val STRIDE_TASK = "strideRunMain"

    private val SCRIPT_EXTENSIONS = setOf("py", "sh", "js", "rb", "kts")

    fun gradleMainArgs(gradlePath: String, mainClass: String, sourceSet: String): List<String> = listOf(
        "$gradlePath:$STRIDE_TASK",
        "-Pstride.mainClass=$mainClass",
        "-Pstride.sourceSet=$sourceSet",
    )

    /** `test` for a file under `src/test/`, `main` otherwise. */
    fun sourceSetFor(file: Path): String =
        if (file.toString().replace('\\', '/').contains("/src/test/")) "test" else "main"

    /** Scripts run directly even when the file lives in a Gradle project. */
    fun isScript(file: Path): Boolean = extension(file) in SCRIPT_EXTENSIONS

    /** The direct command for [file], or null when the extension has no runner. */
    fun tool(file: Path, kotlinAvailable: Boolean): List<String>? {
        val target = file.toAbsolutePath().toString()
        return when (extension(file)) {
            "java" -> listOf("java", target)
            "kts" -> if (kotlinAvailable) listOf("kotlin", target) else listOf("kotlinc", "-script", target)
            "py" -> listOf("python3", target)
            "sh" -> listOf("bash", target)
            "js" -> listOf("node", target)
            "rb" -> listOf("ruby", target)
            else -> null
        }
    }

    /**
     * [tool] wrapped in the same login shell pattern [GradleCommand] uses, so the child sees
     * the PATH and environment of the user's shell.
     */
    fun direct(
        file: Path,
        shell: String = GradleCommand.shellOrDefault(),
        kotlinAvailable: Boolean? = null,
    ): List<String>? {
        val available = kotlinAvailable ?: (extension(file) == "kts" && kotlinOnPath(shell))
        val command = tool(file, available) ?: return null
        return listOf(shell, "-l", "-c", "exec \"\$@\"", "stride", *command.toTypedArray())
    }

    /** Whether `kotlin` resolves on the login shell's PATH. */
    fun kotlinOnPath(shell: String = GradleCommand.shellOrDefault()): Boolean = runCatching {
        val process = ProcessBuilder(shell, "-l", "-c", "command -v kotlin")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun extension(file: Path): String = file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
}
