package stride.app.editor

import stride.app.AppState
import stride.app.keys.KeyContext
import stride.core.services.service
import stride.search.DeclarationIndex

/** An open completion popup: the range to replace and where the names it can show come from. */
class CompletionRequest(
    val editor: TextFileEditor,
    val prefixStart: Int,
    val prefixLength: Int,
    val prefix: String,
    private val nameSource: () -> List<String>,
) {
    /** Candidate names ranked for [prefix]; the popup calls this off the UI thread. */
    fun candidates(): List<String> = CodeCompletion.candidates(prefix, nameSource())
}

/** An open rename popup and the identifier it started from. */
data class RenameRequest(val editor: TextFileEditor, val original: String)

/** Opens the completion popup from the caret's identifier prefix. */
fun openCodeCompletion(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    val text = editor.value.text
    val caret = editor.value.selection.start
    val prefixRange = EditActions.identifierPrefix(text, caret)
    val prefix = if (prefixRange.isEmpty()) "" else text.substring(prefixRange.first, prefixRange.last + 1)
    val request = CompletionRequest(editor, prefixRange.first, prefix.length, prefix) {
        buildList {
            // The identifier under the caret is the one being completed, not a candidate.
            addAll(editor.identifiers(prefixRange))
            for (other in session.editors.openFiles) {
                if (other !== editor) (other as? TextFileEditor)?.let { addAll(it.identifiers()) }
            }
            addAll(session.project.service<DeclarationIndex>().snapshot().map { it.name })
            addAll(CodeCompletion.keywordsFor(editor.file.name))
        }
    }
    state.completion = request
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Replaces the prefix the popup was opened on with [item]. */
fun insertCompletion(request: CompletionRequest, item: String) {
    request.editor.replaceRange(request.prefixStart, request.prefixStart + request.prefixLength, item)
}

/** Opens the rename popup on the identifier at the caret. */
fun openRename(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    val word = EditActions.wordAt(editor.value.text, editor.value.selection.start)
    if (word == null) {
        state.statusMessage = "No identifier at caret"
        return true
    }
    val original = editor.value.text.substring(word.min, word.max)
    state.rename = RenameRequest(editor, original)
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Renames every whole-word occurrence in [request]'s file and records the count in the status bar. */
fun applyRename(state: AppState, request: RenameRequest, newName: String): Int {
    if (newName == request.original) {
        state.statusMessage = "Nothing to rename"
        return 0
    }
    val count = request.editor.renameWord(request.original, newName)
    state.statusMessage = "Renamed $count occurrences"
    return count
}
