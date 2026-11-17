package stride.app.editor

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.keys.KeyContext
import stride.core.services.service
import stride.search.DeclarationIndex
import stride.search.DeclarationKind

/** An open completion popup: the range to replace and where the names it can show come from. */
class CompletionRequest(
    val editor: TextFileEditor,
    val prefixStart: Int,
    val prefixLength: Int,
    val prefix: String,
    val title: String = "Completion",
    private val nameSource: (checkCancelled: () -> Unit) -> List<String>,
) {
    /**
     * Candidate names ranked for [prefix]; the popup calls this off the UI thread.
     * [checkCancelled] throws once the popup has closed so the scan stops early.
     */
    fun candidates(checkCancelled: () -> Unit = {}): List<String> =
        CodeCompletion.candidates(prefix, nameSource(checkCancelled))
}

/** An open rename popup and the identifier it started from. */
data class RenameRequest(val editor: TextFileEditor, val original: String)

/** Opens the completion popup from the caret's identifier prefix; [smart] keeps names the position can use. */
fun openCodeCompletion(state: AppState, smart: Boolean = false): Boolean {
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
    val position = if (smart) SmartCompletion.positionAt(text, caret) else null
    val request = CompletionRequest(
        editor = editor,
        prefixStart = prefixRange.first,
        prefixLength = prefix.length,
        prefix = prefix,
        title = if (smart) "Smart completion" else "Completion",
    ) { checkCancelled ->
        completionNames(session, editor, prefixRange, position, checkCancelled)
    }
    state.completion = request
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/**
 * Every name the popup may offer, from the current file, the other open files and the
 * declaration index. A [position] narrows that to the names a type or an expression can be,
 * and leaves keywords out.
 */
private fun completionNames(
    session: ProjectSession,
    editor: TextFileEditor,
    prefixRange: IntRange,
    position: CompletionPosition?,
    checkCancelled: () -> Unit,
): List<String> = buildList {
    val declarations = session.project.service<DeclarationIndex>().snapshot()
    // The identifier under the caret is the one being completed, not a candidate.
    when (position) {
        null -> {
            addAll(editor.identifiers(prefixRange, checkCancelled))
            for (other in session.editors.openFiles) {
                if (other === editor) continue
                checkCancelled()
                (other as? TextFileEditor)?.let { addAll(it.identifiers(checkCancelled = checkCancelled)) }
            }
            checkCancelled()
            addAll(declarations.map { it.name })
            addAll(CodeCompletion.keywordsFor(editor.file.name))
        }
        CompletionPosition.TYPE -> {
            addAll(editor.identifiers(prefixRange, checkCancelled).filter(::startsUpperCase))
            for (other in session.editors.openFiles) {
                if (other === editor) continue
                checkCancelled()
                (other as? TextFileEditor)?.let { file ->
                    addAll(file.identifiers(checkCancelled = checkCancelled).filter(::startsUpperCase))
                }
            }
            checkCancelled()
            addAll(declarations.filter { it.kind == DeclarationKind.CLASS }.map { it.name })
        }
        CompletionPosition.EXPRESSION -> {
            addAll(editor.identifiers(prefixRange, checkCancelled))
            checkCancelled()
            addAll(declarations.filter { it.kind == DeclarationKind.SYMBOL }.map { it.name })
        }
    }
}

private fun startsUpperCase(name: String): Boolean = name.isNotEmpty() && name.first().isUpperCase()

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
