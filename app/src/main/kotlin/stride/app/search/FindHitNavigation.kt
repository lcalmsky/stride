package stride.app.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.navigation.navigationLocation

/**
 * Opens [hit] in the editor and selects its range when the text there is still the matched text;
 * otherwise it moves to the hit's line. The landing position is one history entry.
 */
fun openFindHit(state: AppState, session: ProjectSession, hit: FindInPathHit) {
    val editor = session.history.withoutRecording {
        session.editors.openFile(hit.file)
    } as? TextFileEditor
    if (editor == null) {
        // No text editor opened: return the keyboard to the selected editor instead of leaving
        // the search popup's modal context behind.
        state.focus = KeyContext.EDITOR
        (session.editors.selected as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
        return
    }
    val text = editor.value.text
    val start = hit.start.coerceIn(0, text.length)
    val end = hit.end.coerceIn(start, text.length)
    if (hit.matchText.isNotEmpty() && text.substring(start, end) == hit.matchText) {
        editor.updateValue(TextFieldValue(text, TextRange(start, end)))
    } else {
        editor.gotoLine(hit.line.toString())
    }
    session.history.push(navigationLocation(editor))
    state.focus = KeyContext.EDITOR
    state.statusMessage = null
    runCatching { editor.focusRequester.requestFocus() }
}
