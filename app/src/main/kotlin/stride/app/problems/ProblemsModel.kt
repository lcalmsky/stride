package stride.app.problems

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Files
import java.nio.file.Path
import stride.core.extensions.ExtensionArea
import stride.core.services.Project
import stride.core.services.Service
import stride.lang.FileTypeRegistry
import stride.lang.LanguageFileType
import stride.lang.ProblemContext
import stride.lang.ProblemProvider
import stride.lang.ProblemSeverity

/** Where a [FileProblem] came from: the language provider reading the text, or a build output. */
enum class ProblemSource { EDITOR, BUILD }

/**
 * One problem of a file. [line] and [column] are 0-based; [start] and [end] are offsets into the
 * text the problem was computed on, and are -1 for a build message whose text is unknown.
 */
data class FileProblem(
    val severity: ProblemSeverity,
    val message: String,
    val source: ProblemSource,
    val line: Int,
    val column: Int,
    val start: Int,
    val end: Int,
)

/** The errors of one file in the Project Errors view. */
data class FileErrors(val path: Path, val errors: List<FileProblem>)

/**
 * One project's problems. The editor source recomputes from the text; the build source is the
 * compiler message set of the last Gradle run, remapped from the text that build compiled onto
 * the text shown now.
 */
@Service(Service.Level.PROJECT)
class ProblemsModel(private val project: Project) {
    private class BuildFile(val baseLines: List<String>?, val messages: List<CompilerMessage>)

    private class Record(val version: Int, val problems: List<FileProblem>)

    private var buildFiles by mutableStateOf<Map<Path, BuildFile>>(emptyMap())

    /** Incremented when the build source is replaced; the editor effect recomputes on it. */
    var buildVersion: Int by mutableStateOf(0)
        private set

    private val records = mutableStateMapOf<Path, Record>()

    /**
     * Replaces the build source with [messages] as one state change. Records are kept: they carry
     * the version they were computed on, so an open file shows only its editor source until the
     * next recompute. Runs on the build's reader thread, so the disk reads happen first and the
     * snapshot writes happen together.
     */
    fun publishBuild(messages: List<CompilerMessage>) {
        val files = messages.groupBy { it.path.toAbsolutePath().normalize() }.mapValues { (path, ofFile) ->
            val base = runCatching { Files.readString(path) }.getOrNull()
            BuildFile(base?.split('\n'), ofFile)
        }
        Snapshot.withMutableSnapshot {
            buildFiles = files
            buildVersion++
        }
    }

    /**
     * The problems of [text]: the language provider's, plus the build messages of [path]
     * remapped onto [text]. Sorted by start, errors before warnings at the same start, one entry
     * per (start, severity, message).
     */
    fun compute(
        path: Path,
        fileName: String,
        text: String,
        area: ExtensionArea = stride.core.services.Application.extensionArea,
    ): List<FileProblem> {
        val merged = ArrayList<FileProblem>()
        merged.addAll(editorProblems(fileName, text, area))
        merged.addAll(buildProblems(path.toAbsolutePath().normalize(), text))
        return merged
            .distinctBy { Triple(it.start, it.severity, it.message) }
            .sortedWith(compareBy({ it.start }, { it.severity.ordinal }))
    }

    /**
     * Keeps the last merged result of a file for the Problems window and the Project Errors view,
     * stamped with the build [version] it was computed on. Pass the version read before
     * computing, so a build that lands during the computation leaves the record stale.
     */
    fun record(path: Path, problems: List<FileProblem>, version: Int = buildVersion) {
        records[path.toAbsolutePath().normalize()] = Record(version, problems)
    }

    /**
     * The project's errors by path: every file the build reported and every open file that has a
     * record. An open file with a record on the current build version reports its recorded
     * errors; on an older version it reports only the editor-sourced errors, so a build result
     * never points at a line the editing has moved. An open file without a record, and a closed
     * file, report the build messages as they are. Files without an error are left out, and
     * paths are sorted.
     */
    fun projectErrors(isOpen: (Path) -> Boolean): List<FileErrors> {
        val openRecorded = records.keys.filter(isOpen)
        return (buildFiles.keys + openRecorded)
            .distinct()
            .sortedBy { it.toString() }
            .mapNotNull { path ->
                val open = isOpen(path)
                val record = if (open) records[path] else null
                val errors = when {
                    record == null -> buildErrors(path)
                    record.version == buildVersion ->
                        record.problems.filter { it.severity == ProblemSeverity.ERROR }
                    else -> record.problems.filter {
                        it.severity == ProblemSeverity.ERROR && it.source == ProblemSource.EDITOR
                    }
                }
                if (errors.isEmpty()) null else FileErrors(path, errors)
            }
    }

    private fun buildErrors(path: Path): List<FileProblem> =
        buildFiles[path]?.messages
            .orEmpty()
            .filter { it.severity == ProblemSeverity.ERROR }
            .sortedWith(compareBy({ it.line }, { it.column ?: 0 }))
            .map { message ->
                FileProblem(
                    severity = message.severity,
                    message = message.message,
                    source = ProblemSource.BUILD,
                    line = message.line,
                    column = message.column ?: 0,
                    start = -1,
                    end = -1,
                )
            }

    private fun editorProblems(fileName: String, text: String, area: ExtensionArea): List<FileProblem> {
        val language = (FileTypeRegistry.getFileType(fileName, area) as? LanguageFileType)?.language
            ?: return emptyList()
        val starts = lineStarts(text)
        return ProblemProvider.collect(language, ProblemContext(text, fileName), area).map { problem ->
            val position = lineColumn(starts, problem.start)
            FileProblem(
                severity = problem.severity,
                message = problem.message,
                source = ProblemSource.EDITOR,
                line = position.first,
                column = position.second,
                start = problem.start,
                end = problem.end,
            )
        }
    }

    private fun buildProblems(path: Path, text: String): List<FileProblem> {
        val file = buildFiles[path] ?: return emptyList()
        val base = file.baseLines ?: return emptyList()
        val remap = LineRemap.of(base, text.split('\n'))
        val starts = lineStarts(text)
        val result = ArrayList<FileProblem>()
        for (message in file.messages) {
            val line = remap.map(message.line) ?: continue
            if (line >= starts.size) continue
            val lineStart = starts[line]
            val lineEnd = lineEnd(text, starts, line)
            val (start, end) = if (message.column != null) {
                val columnStart = (lineStart + message.column).coerceIn(lineStart, lineEnd)
                columnStart to identifierEnd(text, columnStart, lineEnd)
            } else {
                val first = (lineStart until lineEnd).firstOrNull { !text[it].isWhitespace() } ?: lineEnd
                var last = lineEnd
                while (last > first && text[last - 1].isWhitespace()) last--
                first to last
            }
            val position = lineColumn(starts, start)
            result += FileProblem(
                severity = message.severity,
                message = message.message,
                source = ProblemSource.BUILD,
                line = position.first,
                column = position.second,
                start = start,
                end = end,
            )
        }
        return result
    }

    private fun identifierEnd(text: String, start: Int, lineEnd: Int): Int {
        if (start >= lineEnd || !isIdentifierPart(text[start])) return (start + 1).coerceAtMost(lineEnd)
        var end = start + 1
        while (end < lineEnd && isIdentifierPart(text[end])) end++
        return end
    }

    private fun isIdentifierPart(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '$'

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in text.indices) if (text[index] == '\n') starts.add(index + 1)
        return starts.toIntArray()
    }

    private fun lineEnd(text: String, starts: IntArray, line: Int): Int {
        val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
        return if (end > starts[line] && text[end - 1] == '\r') end - 1 else end
    }

    private fun lineColumn(starts: IntArray, offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceAtLeast(0)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= clamped) low = middle else high = middle - 1
        }
        return low to (clamped - starts[low])
    }
}
