package stride.app.editor.find

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import stride.app.editor.BackgroundSpan
import stride.app.editor.TextChange
import stride.app.editor.TextFileEditor
import stride.app.ui.theme.DesignTokens

/** State of the in-editor find bar; matches are painted through [TextFileEditor.searchSpans]. */
class FindBarState {
    var visible by mutableStateOf(false)
        private set

    var replaceVisible by mutableStateOf(false)
        private set

    var query by mutableStateOf("")

    var replacement by mutableStateOf("")

    var caseSensitive by mutableStateOf(false)

    var regex by mutableStateOf(false)

    var matches by mutableStateOf(emptyList<FindMatch>())
        private set

    var currentIndex by mutableStateOf<Int?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var lastEditor: TextFileEditor? = null

    fun open(editor: TextFileEditor?, replace: Boolean) {
        visible = true
        if (replace) replaceVisible = true
        val selection = editor?.value?.selection
        if (editor != null && selection != null && !selection.collapsed) {
            val selectedText = editor.value.text.substring(selection.min, selection.max)
            if (selectedText.isNotEmpty() && !selectedText.contains('\n')) query = selectedText
        }
        refresh(editor)
    }

    fun close() {
        visible = false
        replaceVisible = false
        lastEditor?.searchSpans = emptyList()
        lastEditor = null
    }

    fun refresh(editor: TextFileEditor?) {
        if (lastEditor !== editor) {
            lastEditor?.searchSpans = emptyList()
            lastEditor = editor
        }
        val result = FindModel.search(editor?.value?.text ?: "", query, options())
        matches = result.matches
        error = result.error
        val caret = editor?.value?.selection?.start ?: 0
        currentIndex = FindModel.nextIndex(matches, caret)
        updateSpans(editor)
    }

    /** Selects the current match, or the one after it when the current match is already selected. */
    fun next(editor: TextFileEditor?): Boolean = step(editor, forward = true)

    fun previous(editor: TextFileEditor?): Boolean = step(editor, forward = false)

    private fun step(editor: TextFileEditor?, forward: Boolean): Boolean {
        if (editor == null) return false
        if (matches.isEmpty() && query.isNotEmpty()) refresh(editor)
        if (matches.isEmpty()) return false
        val selection = editor.value.selection
        val current = currentIndex?.let { matches.getOrNull(it) }
        val alreadySelected = current != null && !selection.collapsed &&
            selection.min == current.start && selection.max == current.end
        val index = when {
            alreadySelected -> {
                val offset = if (forward) 1 else matches.size - 1
                (checkNotNull(currentIndex) + offset) % matches.size
            }
            forward -> FindModel.nextIndex(matches, selection.start)
            else -> FindModel.previousIndex(matches, selection.start)
        } ?: return false
        currentIndex = index
        select(editor, matches[index])
        return true
    }

    fun replaceCurrent(editor: TextFileEditor?) {
        if (editor == null) return
        val match = currentIndex?.let { matches.getOrNull(it) } ?: return
        val selection = editor.value.selection
        val selected = !selection.collapsed && selection.min == match.start && selection.max == match.end
        if (selected) {
            val text = editor.value.text
            val change = FindModel.replaceCurrent(text, match, expandedReplacement(text, match))
            val newText = StringBuilder(text).replace(change.offset, change.offset + change.oldLength, change.newText).toString()
            editor.updateValue(TextFieldValue(newText, TextRange(change.offset + change.newText.length)))
        }
        refresh(editor)
        next(editor)
    }

    fun replaceAll(editor: TextFileEditor?) {
        if (editor == null) return
        val text = editor.value.text
        val newText = if (regex && query.isNotEmpty() && error == null) {
            val pattern = runCatching { Regex(query, regexOptions()) }.getOrNull() ?: return
            pattern.replace(text, replacement)
        } else {
            applyChanges(text, FindModel.replaceAll(text, matches, replacement))
        }
        if (newText == text) return
        val caret = editor.value.selection.start.coerceAtMost(newText.length)
        editor.updateValue(TextFieldValue(newText, TextRange(caret)))
        refresh(editor)
        next(editor)
    }

    private fun select(editor: TextFileEditor, match: FindMatch) {
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(match.start, match.end)))
        updateSpans(editor)
    }

    private fun updateSpans(editor: TextFileEditor?) {
        if (editor == null) return
        editor.searchSpans = matches.mapIndexed { index, match ->
            val color = if (index == currentIndex) DesignTokens.neonPinkGlow else DesignTokens.neonAmberGlow
            BackgroundSpan(match.start, match.end, color)
        }
    }

    private fun applyChanges(text: String, changes: List<TextChange>): String {
        var result = text
        for (change in changes) {
            result = result.substring(0, change.offset) +
                change.newText +
                result.substring(change.offset + change.oldLength)
        }
        return result
    }

    /** Expands `$1`-style group references of a regular-expression replacement for [match]. */
    private fun expandedReplacement(text: String, match: FindMatch): String {
        if (!regex || replacement.indexOf('$') < 0) return replacement
        val pattern = runCatching { Regex(query, regexOptions()) }.getOrNull() ?: return replacement
        val matched = text.substring(match.start, match.end)
        if (!pattern.matches(matched)) return replacement
        return pattern.replaceFirst(matched, replacement)
    }

    private fun options() = FindOptions(caseSensitive, regex)

    private fun regexOptions(): Set<RegexOption> =
        if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
}
