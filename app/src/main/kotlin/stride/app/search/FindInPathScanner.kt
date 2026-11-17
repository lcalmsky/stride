package stride.app.search

import stride.app.editor.find.FindModel
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
import stride.search.IndexedFile
import java.nio.file.Files
import java.nio.file.Path

data class FindInPathHit(
    val file: VFile,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val lineText: String,
    val start: Int,
    val end: Int,
    /** The matched text at scan time, so opening the hit can tell whether the offsets still fit. */
    val matchText: String = "",
)

/** Synchronous project-wide text scan; the popup runs it off the UI thread. */
object FindInPathScanner {
    const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024
    const val MAX_HITS: Int = 200

    private const val NUL_CHECK_BYTES = 8 * 1024

    /**
     * Scans [files]; [textOf] supplies the text of a file that is open in an editor, so a search
     * matches what the user sees. It returns `null` for files to read from disk.
     */
    fun scan(
        files: List<IndexedFile>,
        query: String,
        options: FindOptions,
        limit: Int = MAX_HITS,
        textOf: (Path) -> String? = { null },
        shouldStop: () -> Boolean = { false },
    ): List<FindInPathHit> {
        if (query.isEmpty() || limit <= 0) return emptyList()
        val hits = ArrayList<FindInPathHit>()
        for (indexed in files.sortedBy { it.relativePath }) {
            if (hits.size >= limit || shouldStop()) break
            val file = indexed.file
            val text = textOf(file.path) ?: readText(file) ?: continue
            val result = FindModel.search(text, query, options)
            if (result.error != null) return emptyList()
            for (match in result.matches) {
                if (hits.size >= limit || shouldStop()) return hits
                hits.add(hitAt(file, indexed.relativePath, text, match.start, match.end))
            }
        }
        return hits
    }

    private fun readText(file: VFile): String? {
        if (file.length() > MAX_FILE_SIZE) return null
        val bytes = try {
            Files.readAllBytes(file.path)
        } catch (t: Throwable) {
            return null
        }
        if (bytes.size > MAX_FILE_SIZE) return null
        if (containsNul(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun containsNul(bytes: ByteArray): Boolean {
        val end = minOf(bytes.size, NUL_CHECK_BYTES)
        for (index in 0 until end) if (bytes[index] == 0.toByte()) return true
        return false
    }

    private fun hitAt(file: VFile, relativePath: String, text: String, start: Int, end: Int): FindInPathHit {
        var line = 1
        var lineStart = 0
        for (index in 0 until start) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        var lineEnd = text.indexOf('\n', start)
        if (lineEnd < 0) lineEnd = text.length
        val lineText = text.substring(lineStart, lineEnd).trimEnd('\r')
        return FindInPathHit(
            file = file,
            relativePath = relativePath,
            line = line,
            column = start - lineStart + 1,
            lineText = lineText,
            start = start,
            end = end,
            matchText = text.substring(start, end),
        )
    }
}
