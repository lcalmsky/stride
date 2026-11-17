package stride.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowRow
import stride.app.ui.toolwindow.ToolWindowEmptyState

/** The Bookmarks tool window: rows open the bookmark; Delete or Backspace removes it. */
@Composable
fun BookmarksToolWindow(
    bookmarks: List<Bookmark>,
    focusRequests: Int,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = bookmarks.toList()
    if (entries.isEmpty()) {
        ToolWindowEmptyState(
            "No bookmarks · F3 toggles one",
            modifier,
            focusRequests = focusRequests,
            onEscape = onEscape,
        )
        return
    }
    val rows = remember(entries) {
        entries.map { bookmark ->
            ListWindowRow(
                primary = AnnotatedString("${bookmark.relativePath}:${bookmark.line + 1}"),
                secondary = AnnotatedString(bookmark.lineText),
                iconName = bookmark.path.fileName?.toString(),
            )
        }
    }
    ListToolWindow(
        rows = rows,
        focusRequests = focusRequests,
        onOpen = { index -> entries.getOrNull(index)?.let(onOpen) },
        onDelete = { index -> entries.getOrNull(index)?.let(onRemove) },
        onEscape = onEscape,
        modifier = modifier,
    )
}
