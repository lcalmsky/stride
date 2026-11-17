package stride.app.search

import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindMatch
import stride.app.editor.find.FindModel
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
import stride.lang.TextEdit
import stride.search.IndexedFile
import java.io.IOException
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

/**
 * Every file a Replace All would change; the totals drive the confirmation and the status.
 * [error] is set instead of [files] when the replacement string is not valid for the regex, so
 * the UIs report it and never offer the confirmation. [skippedEncoding] counts the files that had
 * a match when read leniently but are not valid UTF-8, so Replace leaves them alone.
 */
data class ReplacePlan(
    val files: List<ReplaceFilePlan>,
    val error: String? = null,
    val skippedEncoding: Int = 0,
) {
    val occurrences: Int get() = files.sumOf { it.edits.size }

    /** Planned files that would be written to disk, so they cannot be undone in stride. */
    val diskFiles: Int get() = files.count { it.source == ReplaceSource.DISK }
}

/** What [ReplaceInPath.apply] did; the skipped and failed counts are occurrences, not files. */
data class ReplaceOutcome(
    val replaced: Int = 0,
    val files: Int = 0,
    val skippedReadOnly: Int = 0,
    val skippedChanged: Int = 0,
    val unsavedFiles: Int = 0,
    val failed: Int = 0,
    val skippedEncoding: Int = 0,
) {
    val message: String
        get() = buildString {
            append("Replaced ${counted(replaced, "occurrence")} in ${counted(files, "file")}")
            if (skippedReadOnly > 0) append(", $skippedReadOnly skipped (read-only)")
            if (skippedChanged > 0) append(", $skippedChanged skipped (changed)")
            if (failed > 0) append(", $failed failed")
            if (skippedEncoding > 0) append(", ${counted(skippedEncoding, "file")} skipped (not UTF-8)")
            if (unsavedFiles > 0) append("; ${counted(unsavedFiles, "open file")} not saved")
        }
}

/** The plan of one Replace Selected, or the reason it cannot be applied in [error]. */
data class HitPlan(val plan: ReplaceFilePlan?, val error: String? = null)

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
    ): List<TextEdit> = computed(text, query, options, replacement).edits

    /** The edits for [text], or the reason a regex [replacement] could not be expanded. */
    private fun computed(
        text: String,
        query: String,
        options: FindOptions,
        replacement: String,
    ): Computed {
        if (query.isEmpty()) return Computed(emptyList())
        val matches = ArrayList<FindMatch>()
        var lastEnd = 0
        for (match in FindModel.search(text, query, options).matches) {
            if (match.start < lastEnd) continue
            matches.add(match)
            lastEnd = match.end
        }
        if (matches.isEmpty()) return Computed(emptyList())
        if (!options.regex) return Computed(matches.map { TextEdit(it.start, it.end, replacement) })
        val pattern = runCatching { Pattern.compile(query, patternFlags(options.caseSensitive)) }.getOrNull()
            ?: return Computed(matches.map { TextEdit(it.start, it.end, replacement) })
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
            try {
                matcher.appendReplacement(buffer, replacement)
            } catch (e: IllegalArgumentException) {
                return Computed(emptyList(), e.message ?: "invalid replacement")
            } catch (e: IndexOutOfBoundsException) {
                return Computed(emptyList(), e.message ?: "invalid replacement")
            }
            // appendReplacement writes the text between matches first; only the tail is expanded.
            edits.add(TextEdit(match.start, match.end, buffer.substring(match.start - appendPosition)))
            appendPosition = match.end
        }
        return Computed(edits)
    }

    private data class Computed(val edits: List<TextEdit>, val error: String? = null)

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
        var skippedEncoding = 0
        for (indexed in files.sortedBy { it.relativePath }) {
            if (shouldStop()) break
            val overlay = textOf(indexed.file.path)
            val text = overlay ?: FindInPathScanner.readTextStrict(indexed.file)
            if (text == null) {
                if (overlay == null && hasMatch(FindInPathScanner.readText(indexed.file), query, options)) {
                    skippedEncoding++
                }
                continue
            }
            val computed = computed(text, query, options, replacement)
            computed.error?.let { return ReplacePlan(emptyList(), error = "Invalid replacement: $it") }
            val edits = computed.edits
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
        return ReplacePlan(planned, skippedEncoding = skippedEncoding)
    }

    /**
     * The single edit of [hit]; [HitPlan.plan] is `null` and [HitPlan.error] set when the hit
     * cannot be applied, and both are `null` when the text no longer has a match at its range.
     */
    fun planHit(
        hit: FindInPathHit,
        query: String,
        options: FindOptions,
        replacement: String,
        textOf: (Path) -> String? = { null },
    ): HitPlan {
        if (query.isEmpty()) return HitPlan(null)
        val overlay = textOf(hit.file.path)
        val text = if (overlay != null) {
            overlay
        } else {
            FindInPathScanner.readTextStrict(hit.file) ?: return HitPlan(
                plan = null,
                error = if (FindInPathScanner.readText(hit.file) != null) {
                    "${hit.relativePath} is not UTF-8"
                } else {
                    null
                },
            )
        }
        val computed = computed(text, query, options, replacement)
        computed.error?.let { return HitPlan(plan = null, error = "Invalid replacement: $it") }
        val edit = computed.edits.firstOrNull { it.start == hit.start && it.end == hit.end }
            ?: return HitPlan(null)
        return HitPlan(
            ReplaceFilePlan(
                file = hit.file,
                relativePath = hit.relativePath,
                source = if (overlay != null) ReplaceSource.EDITOR else ReplaceSource.DISK,
                originalText = text,
                edits = listOf(edit),
            ),
        )
    }

    private fun hasMatch(text: String?, query: String, options: FindOptions): Boolean =
        text != null && FindModel.search(text, query, options).matches.isNotEmpty()

    /**
     * Writes [plan] on the caller's (UI) thread. An open editor takes the edits as one undo step
     * and stays unsaved; a disk file is re-read first and left alone when it changed after the
     * plan. A read-only file or editor is skipped in both paths; a disk write that throws counts
     * as failed and the remaining files are still written.
     */
    fun apply(plan: ReplacePlan, openEditors: List<TextFileEditor>): ReplaceOutcome =
        apply(plan, openEditors) { it.file.writeText(it.newText) }

    internal fun apply(
        plan: ReplacePlan,
        openEditors: List<TextFileEditor>,
        write: (ReplaceFilePlan) -> Unit,
    ): ReplaceOutcome {
        var replaced = 0
        var files = 0
        var readOnly = 0
        var changed = 0
        var unsaved = 0
        var failed = 0
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
                    FindInPathScanner.readTextStrict(planned.file) != planned.originalText ->
                        changed += planned.edits.size
                    else -> try {
                        write(planned)
                        replaced += planned.edits.size
                        files++
                    } catch (e: IOException) {
                        failed += planned.edits.size
                    }
                }
            }
        }
        return ReplaceOutcome(replaced, files, readOnly, changed, unsaved, failed, plan.skippedEncoding)
    }

    /**
     * IntelliJ's Replace All wording, including the disk warning when any file is not open and
     * the notice for files that had a match but are not valid UTF-8.
     */
    fun confirmation(plan: ReplacePlan, query: String, replacement: String): String {
        var text = "Replace ${counted(plan.occurrences, "occurrence")} of '$query' across " +
            "${counted(plan.files.size, "file")} with '$replacement'?"
        if (plan.diskFiles > 0) {
            val verb = if (plan.diskFiles == 1) "is" else "are"
            text += " ${counted(plan.diskFiles, "file")} $verb written to disk and cannot be undone."
        }
        if (plan.skippedEncoding > 0) {
            val verb = if (plan.skippedEncoding == 1) "is" else "are"
            text += " ${counted(plan.skippedEncoding, "file")} $verb not UTF-8 and $verb skipped."
        }
        return text
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

    fun planHit(tab: FindTab, hit: FindInPathHit): HitPlan =
        ReplaceInPath.planHit(hit, tab.query, tab.options, tab.replacement.orEmpty(), textOf)

    fun apply(plan: ReplacePlan): ReplaceOutcome = ReplaceInPath.apply(plan, openEditors())

    /**
     * Scans [hit]'s file again with the tab's query, options and overlay, so a Replace Selected
     * leaves that file's rows on the offsets the current text actually has.
     */
    fun scanFile(tab: FindTab, hit: FindInPathHit): List<FindInPathHit> =
        FindInPathScanner.scan(
            files = listOf(IndexedFile(hit.file, hit.relativePath)),
            query = tab.query,
            options = tab.options,
            limit = FIND_WINDOW_MAX_HITS,
            textOf = textOf,
        )
}
