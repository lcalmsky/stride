package stride.app.run

import java.nio.file.Path

/** Builds the Gradle arguments and the direct commands for running a file. */
object RunCommands {
    const val STRIDE_TASK = "strideRunMain"

    private val SCRIPT_EXTENSIONS = setOf("py", "sh", "js", "rb", "kts")

    /**
     * `kotlin` when the login shell has it, otherwise the same file through `kotlinc -script`.
     * `$0` is the shell's name, so the script path is `$2`.
     */
    private const val KOTLIN_SCRIPT =
        "command -v kotlin >/dev/null && exec kotlin \"\$2\" || exec kotlinc -script \"\$2\""

    fun gradleMainArgs(gradlePath: String, mainClass: String, sourceSet: String): List<String> = listOf(
        "$gradlePath:$STRIDE_TASK",
        "-Pstride.mainClass=$mainClass",
        "-Pstride.sourceSet=$sourceSet",
    )

    /** The source set whose `src/<set>/(kotlin|java)/` directory holds [file], or null. */
    fun sourceSetIn(projectDir: Path, file: Path): String? {
        val relative = runCatching {
            projectDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
        }.getOrNull() ?: return null
        val parts = relative.map { it.toString() }
        if (parts.size < 4 || parts[0] != "src") return null
        if (parts[2] != "kotlin" && parts[2] != "java") return null
        return parts[1]
    }

    /** `*.gradle.kts` is the Gradle DSL, not a script Kotlin can run on its own. */
    fun isGradleBuildScript(file: Path): Boolean =
        file.fileName?.toString()?.lowercase()?.endsWith(".gradle.kts") == true

    /** Scripts run directly even when the file lives in a Gradle project. */
    fun isScript(file: Path): Boolean = extension(file) in SCRIPT_EXTENSIONS && !isGradleBuildScript(file)

    /** The direct command for [file], or null when the extension has no runner. */
    fun tool(file: Path): List<String>? {
        if (isGradleBuildScript(file)) return null
        val target = file.toAbsolutePath().toString()
        return when (extension(file)) {
            "java" -> listOf("java", target)
            "kts" -> listOf("kotlin", target)
            "py" -> listOf("python3", target)
            "sh" -> listOf("bash", target)
            "js" -> listOf("node", target)
            "rb" -> listOf("ruby", target)
            else -> null
        }
    }

    /**
     * [tool] wrapped in the same login shell pattern [GradleCommand] uses, so the child sees
     * the PATH and environment of the user's shell. Kotlin scripts choose between `kotlin`
     * and `kotlinc -script` inside that shell, so no PATH probe runs on the UI thread.
     */
    fun direct(file: Path, shell: String = GradleCommand.shellOrDefault()): List<String>? {
        val command = tool(file) ?: return null
        val script = if (extension(file) == "kts") KOTLIN_SCRIPT else "exec \"\$@\""
        return listOf(shell, "-l", "-c", script, "stride", *command.toTypedArray())
    }

    private fun extension(file: Path): String = file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
}
