package stride.app.editor

import androidx.compose.ui.text.TextRange
import stride.app.AppState
import stride.app.keys.KeyContext

/** Recently copied clipboard texts, newest first; blank entries are ignored and the list is bounded. */
class ClipboardHistory(private val limit: Int = MAX_ITEMS) {
    private val entries = ArrayList<String>()

    /** The recorded texts, newest first. */
    val items: List<String> get() = entries

    /** Puts [text] at the front, moving an earlier copy of the same text there. */
    fun record(text: String) {
        if (text.isBlank()) return
        entries.remove(text)
        entries.add(0, text)
        while (entries.size > limit) entries.removeAt(entries.lastIndex)
    }

    fun clear() = entries.clear()

    companion object {
        const val MAX_ITEMS: Int = 20
    }
}

/** An open Paste from History popup; the selection is the one it will replace. */
data class PasteHistoryRequest(val editor: TextFileEditor, val selection: TextRange)

/** Opens the Paste from History popup with the system clipboard's current text recorded first. */
fun openPasteHistory(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    state.clipboard.readText()?.let(state.clipboardHistory::record)
    if (state.clipboardHistory.items.isEmpty()) {
        state.statusMessage = "Clipboard history is empty"
        return true
    }
    state.pasteHistory = PasteHistoryRequest(editor, editor.value.selection)
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Replaces the selection with [text] as one undo step, and makes it the newest history entry. */
fun applyPasteHistory(state: AppState, request: PasteHistoryRequest, text: String) {
    if (request.editor.isReadOnly) return
    request.editor.replaceRange(request.selection.min, request.selection.max, text)
    state.clipboard.writeText(text)
    state.clipboardHistory.record(text)
}
