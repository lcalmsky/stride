package stride.app.problems

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import stride.lang.ProblemSeverity

/** One compiler message from a build's output; [line] and [column] are 0-based. */
data class CompilerMessage(
    val path: Path,
    val line: Int,
    val column: Int?,
    val severity: ProblemSeverity,
    val message: String,
)

/**
 * Reads the compiler lines a Gradle build prints: the Kotlin 1.8+ URI form, the Kotlin 1.7-
 * form with a parenthesised position, and javac's `path:line: severity:` form. Anything else,
 * including a relative path, an unreadable position or an empty message, reads as `null`.
 */
object CompilerMessageParser {
    private val KOTLIN_URI = Regex("""^([ew]): (file:.+?):(\d+):(\d+) (.+)$""")
    private val KOTLIN_LEGACY = Regex("""^([ew]): (.+?): \((\d+), (\d+)\): (.+)$""")
    private val JAVAC = Regex("""^(.+?):(\d+): (error|warning): (.+)$""")

    fun parse(line: String): CompilerMessage? {
        KOTLIN_URI.matchEntire(line)?.let { match ->
            val path = uriPath(match.groupValues[2]) ?: return null
            return message(
                path = path,
                line = match.groupValues[3],
                column = match.groupValues[4],
                severity = severityOf(match.groupValues[1]),
                text = match.groupValues[5],
            )
        }
        KOTLIN_LEGACY.matchEntire(line)?.let { match ->
            val path = absolutePath(match.groupValues[2]) ?: return null
            return message(
                path = path,
                line = match.groupValues[3],
                column = match.groupValues[4],
                severity = severityOf(match.groupValues[1]),
                text = match.groupValues[5],
            )
        }
        JAVAC.matchEntire(line)?.let { match ->
            val path = absolutePath(match.groupValues[1]) ?: return null
            val severity = if (match.groupValues[3] == "error") ProblemSeverity.ERROR else ProblemSeverity.WARNING
            return message(
                path = path,
                line = match.groupValues[2],
                column = null,
                severity = severity,
                text = match.groupValues[4],
            )
        }
        return null
    }

    private fun message(
        path: Path,
        line: String,
        column: String?,
        severity: ProblemSeverity,
        text: String,
    ): CompilerMessage? {
        val lineNumber = line.toIntOrNull() ?: return null
        if (lineNumber < 1) return null
        val columnNumber = if (column == null) null else (column.toIntOrNull()?.takeIf { it >= 1 } ?: return null)
        val message = text.trim()
        if (message.isEmpty()) return null
        return CompilerMessage(
            path = path.toAbsolutePath().normalize(),
            line = lineNumber - 1,
            column = columnNumber?.minus(1),
            severity = severity,
            message = message,
        )
    }

    /**
     * The absolute path of a `file:` URI. A URI that does not parse — an unencoded space, for
     * one — keeps its text as written after the `file://` prefix, with `%XX` left undecoded.
     */
    private fun uriPath(raw: String): Path? {
        runCatching { Paths.get(URI(raw)) }.getOrNull()?.let { return it }
        val path = runCatching { Paths.get(raw.removePrefix("file://")) }.getOrNull() ?: return null
        return if (path.isAbsolute) path else null
    }

    private fun absolutePath(raw: String): Path? {
        val path = runCatching { Paths.get(raw) }.getOrNull() ?: return null
        return if (path.isAbsolute) path else null
    }

    private fun severityOf(letter: String): ProblemSeverity =
        if (letter == "e") ProblemSeverity.ERROR else ProblemSeverity.WARNING
}
