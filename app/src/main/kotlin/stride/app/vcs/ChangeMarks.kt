package stride.app.vcs

import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.core.services.Project
import stride.lang.TextEdit
import java.util.concurrent.ConcurrentHashMap

/**
 * Gutter change marks: one [LineStatusTracker] per open text editor, attached when the tab opens
 * and detached when it closes, both from [stride.app.editor.FileEditorManager].
 */
object ChangeMarks {
    private val trackers = ConcurrentHashMap<TextFileEditor, LineStatusTracker>()

    fun attach(project: Project, editor: TextFileEditor) {
        val tracker = LineStatusTracker(project, editor)
        editor.onDocumentTextChanged = tracker::textChanged
        trackers.put(editor, tracker)?.detach()
    }

    fun detach(editor: TextFileEditor) {
        editor.onDocumentTextChanged = null
        trackers.remove(editor)?.detach()
    }

    internal fun trackerOf(editor: TextFileEditor): LineStatusTracker? = trackers[editor]
}

/** Opens the gutter popup for [hunk] with the hunk's base lines. */
internal fun openChangeMarkPopup(state: AppState, editor: TextFileEditor, hunk: LineHunk): Boolean {
    val base = ChangeMarks.trackerOf(editor)?.document()?.baseLines.orEmpty()
    val lines = base.subList(hunk.oldStart.coerceIn(0, base.size), (hunk.oldStart + hunk.oldCount).coerceIn(0, base.size))
        .toList()
    state.changeMarkPopup = ChangeMarkPopupRequest(editor, hunk, lines)
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Rolls back the text of the hunks at the primary and secondary carets, or in the selection. */
fun rollbackLines(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val tracker = ChangeMarks.trackerOf(editor)
    val answer = tracker?.baseState()
    if (answer is BaseText.Unavailable && answer.reason.startsWith(NOT_REPOSITORY_PREFIX)) {
        state.statusMessage = answer.reason
        return true
    }
    val status = tracker?.statusFor(editor.value.text)
    val selected = status?.let { selectedHunks(editor, it.hunks) }.orEmpty()
    if (selected.isEmpty() || status == null) {
        state.statusMessage = "No changes at the caret"
        return true
    }
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    state.statusMessage = null
    applyRollback(editor, status.baseLines, status.hunks, selected)
    return true
}

/** Rolls back the single [hunk] the gutter popup shows; `false` when the text moved past it. */
internal fun rollbackHunk(state: AppState, editor: TextFileEditor, hunk: LineHunk): Boolean {
    if (state.session?.editors?.selected !== editor) return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return false
    }
    val tracker = ChangeMarks.trackerOf(editor) ?: return false
    val status = tracker.statusFor(editor.value.text)
    val target = status?.let { live -> live.hunks.firstOrNull { it == hunk } }
    val applied = if (status != null && target != null) {
        applyRollback(editor, status.baseLines, status.hunks, listOf(target))
    } else {
        false
    }
    state.statusMessage = if (applied) null else "No changes at the caret"
    return applied
}

/** Moves the caret to the first line of the next (or previous) hunk and opens its popup. */
fun gotoChange(state: AppState, forward: Boolean): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val hunks = ChangeMarks.trackerOf(editor)?.document()?.hunks.orEmpty()
    val text = editor.value.text
    val caretLine = lineIndexAt(text, editor.value.selection.end)
    val target = if (forward) {
        hunks.firstOrNull { it.newStart > caretLine }
    } else {
        hunks.lastOrNull { it.newStart < caretLine }
    }
    if (target == null) {
        state.statusMessage = "No more changes"
        return true
    }
    val lastLine = (LineDiff.split(text).size - 1).coerceAtLeast(0)
    editor.jumpTo(lineStartOffset(text, target.newStart.coerceIn(0, lastLine)))
    return openChangeMarkPopup(state, editor, target)
}

/** Hunks that touch a caret line, or any line of a non-empty selection. */
private fun selectedHunks(editor: TextFileEditor, hunks: List<LineHunk>): List<LineHunk> {
    val text = editor.value.text
    val lines = HashSet<Int>()
    for (caret in editor.carets) {
        if (caret.collapsed) {
            lines += lineIndexAt(text, caret.start)
        } else {
            val first = lineIndexAt(text, caret.min)
            var last = lineIndexAt(text, caret.max)
            // A selection dragged to the start of a line does not take that line.
            if (last > first && caret.max == lineStartOffset(text, last)) last--
            for (line in first..last) lines += line
        }
    }
    return hunks.filter { hunk ->
        if (hunk.newCount == 0) {
            hunk.newStart - 1 in lines || hunk.newStart in lines
        } else {
            (hunk.newStart until hunk.newStart + hunk.newCount).any { it in lines }
        }
    }
}

/**
 * Replaces [selected] hunks with their base lines in one undo step, caret at the first change.
 * Only the rolled-back line ranges change; every other line keeps its own text and terminator.
 */
private fun applyRollback(
    editor: TextFileEditor,
    base: List<String>,
    hunks: List<LineHunk>,
    selected: List<LineHunk>,
): Boolean {
    val text = editor.value.text
    val separator = LineDiff.lineSeparator(text)
    val lineCount = LineDiff.split(text).size
    val edits = ArrayList<TextEdit>(selected.size)
    for (hunk in selected.sortedBy { it.newStart }) {
        val start = hunk.newStart.coerceIn(0, lineCount)
        val count = hunk.newCount.coerceIn(0, lineCount - start)
        val oldStart = hunk.oldStart.coerceIn(0, base.size)
        val oldCount = hunk.oldCount.coerceIn(0, base.size - oldStart)
        val oldLines = base.subList(oldStart, oldStart + oldCount)
        when {
            count == 0 -> {
                if (oldLines.isEmpty()) continue
                val offset = lineStartOffset(text, start)
                val atLineStart = offset == 0 || text[offset - 1] == '\n'
                val insertion = if (atLineStart) {
                    oldLines.joinToString(separator) + separator
                } else {
                    separator + oldLines.joinToString(separator)
                }
                edits += TextEdit(offset, offset, insertion)
            }
            oldCount == 0 -> {
                val from = lineStartOffset(text, start)
                if (start + count >= lineCount && from > 0) {
                    // No line follows the hunk, so the break before it goes too.
                    val terminator = if (from > 1 && text[from - 2] == '\r') from - 2 else from - 1
                    edits += TextEdit(terminator, text.length, "")
                } else {
                    val to = lineStartOffset(text, start + count).coerceAtMost(text.length)
                    if (to > from) edits += TextEdit(from, to, "")
                }
            }
            else -> {
                val from = lineStartOffset(text, start)
                val to = lineContentEnd(text, start + count - 1)
                val replacement = oldLines.joinToString(separator)
                if (text.substring(from, to) != replacement) edits += TextEdit(from, to, replacement)
            }
        }
    }
    if (edits.isEmpty()) return false
    return editor.applyEdits(edits, caret = edits.first().start)
}

/** The offset just past [line]'s content, before its terminator; the text end for a last line. */
private fun lineContentEnd(text: String, line: Int): Int {
    val start = lineStartOffset(text, line)
    val next = lineStartOffset(text, line + 1)
    if (next > start && text[next - 1] == '\n') {
        var end = next - 1
        if (end > start && text[end - 1] == '\r') end--
        return end
    }
    return text.length
}

private fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 0) return 0
    var seen = 0
    for (index in text.indices) {
        if (text[index] == '\n') {
            seen++
            if (seen == line) return index + 1
        }
    }
    return text.length
}

private fun lineIndexAt(text: String, offset: Int): Int {
    if (offset <= 0) return 0
    var line = 0
    for (index in 0 until offset.coerceAtMost(text.length)) {
        if (text[index] == '\n') line++
    }
    return line
}

private const val NOT_REPOSITORY_PREFIX = "Not a Git repository"
