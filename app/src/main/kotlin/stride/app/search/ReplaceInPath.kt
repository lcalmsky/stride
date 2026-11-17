package stride.app.search

import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindMatch
import stride.app.editor.find.FindModel
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
import stride.lang.TextEdit
import stride.search.IndexedFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/** Where a planned file's text came from: the open editor's unsaved text or the disk. */
enum class ReplaceSource { EDITOR, DISK }

/** One file a Replace in Path plan changes, with the text the edits were computed against. */
data class ReplaceFilePlan(
    val file: VFile,
    val relativePath: String,
    val source: ReplaceSource,
    val originalText: String,
    val edits: List<TextEdit>,
) {
    /** [originalText] with [edits] applied, back to front so an earlier offset stays valid. */
    val newText: String
        get() {
            val text = StringBuilder(originalText)
            for (edit in edits.sortedByDescending { it.start }) text.replace(edit.start, edit.end, edit.replacement)
            return text.toString()
        }
}

/** Every file a Replace All would change; the totals drive the confirmation and the status. */
data class ReplacePlan(val files: List<ReplaceFilePlan>) {
    val occurrences: Int get() = files.sumOf { it.edits.size }

    /** Planned files that would be written to disk, so they cannot be undone in stride. */
    val diskFiles: Int get() = files.count { it.source == ReplaceSource.DISK }
}

/** What [ReplaceInPath.apply] did; the skipped counts are occurrences, not files. */
data class ReplaceOutcome(
    val replaced: Int = 0,
    val files: Int = 0,
    val skippedReadOnly: Int = 0,
    val skippedChanged: Int = 0,
    val unsavedFiles: Int = 0,
) {
    val message: String
        get() = buildString {
            append("Replaced ${counted(replaced, "occurrence")} in ${counted(files, "file")}")
            if (skippedReadOnly > 0) append(", $skippedReadOnly skipped (read-only)")
            if (skippedChanged > 0) append(", $skippedChanged skipped (changed)")
            if (unsavedFiles > 0) append("; ${counted(unsavedFiles, "open file")} not saved")
        }
}

/**
 * Two-phase Replace in Path: [plan] reads every file through the overlay (open editor text,
 * else disk) and computes one edit per match, [apply] writes the plan through the open editor
 * or the disk on the UI thread.
 */
object ReplaceInPath {
    /**
     * One edit per match of [query] in [text]. Literal mode inserts [replacement]; regex mode
     * expands `$1`-style references with java.util.regex semantics over the full text, so a
     * lookbehind or lookahead sees the real context. One pass over the matches. Overlapping
     * matches keep the first one: their edits cannot be applied together.
     */
    fun replacementsFor(
        text: String,
        query: String,
        options: FindOptions,
        replacement: String,
    ): List<TextEdit> {
        if (query.isEmpty()) return emptyList()
        val matches = ArrayList<FindMatch>()
        var lastEnd = 0
        for (match in FindModel.search(text, query, options).matches) {
            if (match.start < lastEnd) continue
            matches.add(match)
            lastEnd = match.end
        }
        if (matches.isEmpty()) return emptyList()
        if (!options.regex) return matches.map { TextEdit(it.start, it.end, replacement) }
        val pattern = runCatching { Pattern.compile(query, patternFlags(options.caseSensitive)) }.getOrNull()
            ?: return matches.map { TextEdit(it.start, it.end, replacement) }
        val matcher = pattern.matcher(text)
        val buffer = StringBuffer()
        val edits = ArrayList<TextEdit>(matches.size)
        var appendPosition = 0
        var found = matcher.find()
        for (match in matches) {
            while (found && matcher.start() < match.start) found = matcher.find()
            if (!found || matcher.start() != match.start || matcher.end() != match.end) {
                edits.add(TextEdit(match.start, match.end, replacement))
                continue
            }
            buffer.setLength(0)
            matcher.appendReplacement(buffer, replacement)
            // appendReplacement writes the text between matches first; only the tail is expanded.
            edits.add(TextEdit(match.start, match.end, buffer.substring(match.start - appendPosition)))
            appendPosition = match.end
        }
        return edits
    }

    /** Plans every file that has at least one match; files without an edit are left out. */
    fun plan(
        files: List<IndexedFile>,
        query: String,
        options: FindOptions,
        replacement: String,
        textOf: (Path) -> String? = { null },
        shouldStop: () -> Boolean = { false },
    ): ReplacePlan {
        if (query.isEmpty()) return ReplacePlan(emptyList())
        val planned = ArrayList<ReplaceFilePlan>()
        for (indexed in files.sortedBy { it.relativePath }) {
            if (shouldStop()) break
            val overlay = textOf(indexed.file.path)
            val text = overlay ?: FindInPathScanner.readText(indexed.file) ?: continue
            val edits = replacementsFor(text, query, options, replacement)
            if (edits.isEmpty()) continue
            planned.add(
                ReplaceFilePlan(
                    file = indexed.file,
                    relativePath = indexed.relativePath,
                    source = if (overlay != null) ReplaceSource.EDITOR else ReplaceSource.DISK,
                    originalText = text,
                    edits = edits,
                ),
            )
        }
        return ReplacePlan(planned)
    }

    /** The single edit of [hit], or `null` when the text no longer has a match at its range. */
    fun planHit(
        hit: FindInPathHit,
        query: String,
        options: FindOptions,
        replacement: String,
        textOf: (Path) -> String? = { null },
    ): ReplaceFilePlan? {
        if (query.isEmpty()) return null
        val overlay = textOf(hit.file.path)
        val text = overlay ?: FindInPathScanner.readText(hit.file) ?: return null
        val edit = replacementsFor(text, query, options, replacement)
            .firstOrNull { it.start == hit.start && it.end == hit.end } ?: return null
        return ReplaceFilePlan(
            file = hit.file,
            relativePath = hit.relativePath,
            source = if (overlay != null) ReplaceSource.EDITOR else ReplaceSource.DISK,
            originalText = text,
            edits = listOf(edit),
        )
    }

    /**
     * Writes [plan] on the caller's (UI) thread. An open editor takes the edits as one undo step
     * and stays unsaved; a disk file is re-read first and left alone when it changed after the
     * plan. A read-only file or editor is skipped in both paths.
     */
    fun apply(plan: ReplacePlan, openEditors: List<TextFileEditor>): ReplaceOutcome {
        var replaced = 0
        var files = 0
        var readOnly = 0
        var changed = 0
        var unsaved = 0
        for (planned in plan.files) {
            val path = planned.file.path.toAbsolutePath().normalize()
            val editor = openEditors.firstOrNull { it.file.path.toAbsolutePath().normalize() == path }
            if (editor != null) {
                when {
                    editor.isReadOnly -> readOnly += planned.edits.size
                    editor.value.text != planned.originalText -> changed += planned.edits.size
                    editor.applyEdits(planned.edits) -> {
                        replaced += planned.edits.size
                        files++
                        unsaved++
                    }
                    else -> changed += planned.edits.size
                }
            } else {
                when {
                    !Files.isWritable(planned.file.path) -> readOnly += planned.edits.size
                    FindInPathScanner.readText(planned.file) != planned.originalText -> changed += planned.edits.size
                    else -> {
                        planned.file.writeText(planned.newText)
                        replaced += planned.edits.size
                        files++
                    }
                }
            }
        }
        return ReplaceOutcome(replaced, files, readOnly, changed, unsaved)
    }

    /** IntelliJ's Replace All wording, including the disk warning when any file is not open. */
    fun confirmation(plan: ReplacePlan, query: String, replacement: String): String {
        val text = "Replace ${counted(plan.occurrences, "occurrence")} of '$query' across " +
            "${counted(plan.files.size, "file")} with '$replacement'?"
        if (plan.diskFiles == 0) return text
        val verb = if (plan.diskFiles == 1) "is" else "are"
        return "$text ${counted(plan.diskFiles, "file")} $verb written to disk and cannot be undone."
    }

    private fun patternFlags(caseSensitive: Boolean): Int =
        if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
}

private fun counted(value: Int, noun: String): String = if (value == 1) "$value $noun" else "$value ${noun}s"

/** Plans and applies Replace in Path edits for the Find window's REPLACE tabs. */
class FindReplaceController(
    private val files: () -> List<IndexedFile>,
    private val textOf: (Path) -> String?,
    private val openEditors: () -> List<TextFileEditor>,
) {
    fun planAll(tab: FindTab): ReplacePlan =
        ReplaceInPath.plan(files(), tab.query, tab.options, tab.replacement.orEmpty(), textOf)

    fun planHit(tab: FindTab, hit: FindInPathHit): ReplaceFilePlan? =
        ReplaceInPath.planHit(hit, tab.query, tab.options, tab.replacement.orEmpty(), textOf)

    fun apply(plan: ReplacePlan): ReplaceOutcome = ReplaceInPath.apply(plan, openEditors())
}
