package stride.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import stride.app.editor.TextFileEditor
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowRow
import stride.app.ui.toolwindow.ToolWindowEmptyState
import stride.search.DeclarationKind

private const val STRUCTURE_DEBOUNCE_MILLIS: Long = 150

/** The Structure tool window: the declarations of the selected file, parsed while it is shown. */
@Composable
fun StructureToolWindow(
    editor: TextFileEditor,
    focusRequests: Int,
    onOpen: (FileStructureEntry) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = editor.value.text
    var entries by remember(editor) { mutableStateOf(emptyList<FileStructureEntry>()) }
    var parsed by remember(editor) { mutableStateOf(false) }
    LaunchedEffect(editor, text) {
        delay(STRUCTURE_DEBOUNCE_MILLIS)
        entries = withContext(Dispatchers.Default) { FileStructure.entries(editor.file.name, text) }
        parsed = true
    }
    if (parsed && entries.isEmpty()) {
        ToolWindowEmptyState(
            "No structure",
            modifier,
            focusRequests = focusRequests,
            onEscape = onEscape,
        )
        return
    }
    val latestEntries by rememberUpdatedState(entries)
    val selectAtCaret: () -> Int = {
        val caret = BookmarkManager.lineIndex(editor.value.text, editor.value.selection.start)
        latestEntries.indexOfLast { it.line <= caret }.coerceAtLeast(0)
    }
    val rows = remember(entries) {
        entries.map { entry ->
            ListWindowRow(
                primary = AnnotatedString(entry.name),
                secondary = AnnotatedString((entry.line + 1).toString()),
                indent = entry.depth,
                accent = entry.kind == DeclarationKind.CLASS,
            )
        }
    }
    ListToolWindow(
        rows = rows,
        focusRequests = focusRequests,
        onOpen = { index -> entries.getOrNull(index)?.let(onOpen) },
        onEscape = onEscape,
        selectOnFocus = selectAtCaret,
        modifier = modifier,
    )
}
