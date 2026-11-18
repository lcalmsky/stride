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
        trackers.put(editor, LineStatusTracker(project, editor))?.detach()
    }

    fun detach(editor: TextFileEditor) {
        trackers.remove(editor)?.detach()
    }

    internal fun trackerOf(editor: TextFileEditor): LineStatusTracker? = trackers[editor]
}

/** Opens the gutter popup for [hunk] with the hunk's base lines. */
internal fun openChangeMarkPopup(state: AppState, editor: TextFileEditor, hunk: LineHunk): Boolean {
    val base = ChangeMarks.trackerOf(editor)?.baseLines.orEmpty()
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
    val hunks = tracker?.hunks.orEmpty()
    val selected = selectedHunks(editor, hunks)
    if (selected.isEmpty()) {
        state.statusMessage = "No changes at the caret"
        return true
    }
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    applyRollback(editor, tracker?.baseLines.orEmpty(), hunks, selected)
    state.statusMessage = null
    return true
}

/** Rolls back the single [hunk] the gutter popup shows; `false` when the text moved past it. */
internal fun rollbackHunk(state: AppState, editor: TextFileEditor, hunk: LineHunk): Boolean {
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return false
    }
    val tracker = ChangeMarks.trackerOf(editor) ?: return false
    val hunks = tracker.hunks
    val target = hunks.firstOrNull { it == hunk } ?: return false
    val applied = applyRollback(editor, tracker.baseLines, hunks, listOf(target))
    state.statusMessage = null
    return applied
}

/** Moves the caret to the first line of the next (or previous) hunk and opens its popup. */
fun gotoChange(state: AppState, forward: Boolean): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val hunks = ChangeMarks.trackerOf(editor)?.hunks.orEmpty()
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
            for (line in lineIndexAt(text, caret.min)..lineIndexAt(text, caret.max)) lines += line
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

/** Replaces [selected] hunks with their base lines in one undo step, caret at the first change. */
private fun applyRollback(
    editor: TextFileEditor,
    base: List<String>,
    hunks: List<LineHunk>,
    selected: List<LineHunk>,
): Boolean {
    val text = editor.value.text
    val current = LineDiff.split(text)
    val result = LineDiff.rollback(base, current, hunks, selected, LineDiff.lineSeparator(text))
    if (result == text) return false
    val caret = lineStartOffset(result, firstChangedLine(current, result))
    return editor.applyEdits(listOf(TextEdit(0, text.length, result)), caret = caret)
}

private fun firstChangedLine(before: List<String>, after: String): Int {
    val afterLines = LineDiff.split(after)
    var line = 0
    while (line < before.size && line < afterLines.size && before[line] == afterLines[line]) line++
    return line
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
