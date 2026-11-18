package stride.app.vcs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import stride.app.AppState
import stride.app.editor.TextFileEditor
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** The one-column diff popup; lane V replaces this. */
@Composable
fun DiffPopup(request: DiffRequest, state: AppState, onClose: () -> Unit) {
}

/**
 * Opens the diff popup for [file]: the file at HEAD against the open editor's text, or against the
 * text on disk when no editor has the file open. [line] is the 0-based line the popup starts at.
 * Outside a repository, or without git, the status bar gets the reason and no popup opens.
 */
fun openWorkingTreeDiff(state: AppState, file: Path, line: Int? = null): Boolean {
    val session = state.session ?: return false
    val git = session.git
    val path = file.toAbsolutePath().normalize()
    val editorText = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .firstOrNull { it.file.path.toAbsolutePath().normalize() == path }
        ?.value?.text
    git.launch {
        val old = git.baseText(path)
        val root = git.snapshot.repository?.root
        if (root == null && old is BaseText.Unavailable) {
            Snapshot.withMutableSnapshot { state.statusMessage = old.reason }
            return@launch
        }
        val new = editorText?.let { BaseText.Text(it) } ?: workingText(path)
        val display = displayPath(root, path)
        val request = DiffRequest(
            title = "$display  HEAD ↔ Local",
            files = listOf(DiffFile(path, display, old, new, openable = true)),
            initialLine = line,
        )
        Snapshot.withMutableSnapshot { state.diffRequest = request }
    }
    return true
}

private fun workingText(path: Path): BaseText = when {
    !Files.exists(path) -> BaseText.Text("")
    Files.size(path) > GitService.MAX_BASE_BYTES -> BaseText.Unavailable("Larger than 8 MB")
    else -> try {
        BaseText.Text(Files.readString(path))
    } catch (_: CharacterCodingException) {
        BaseText.Unavailable("Not valid UTF-8")
    }
}

private fun displayPath(root: Path?, path: Path): String {
    if (root == null) return path.fileName.toString()
    val real = runCatching { path.toRealPath() }.getOrDefault(path)
    return if (real.startsWith(root)) root.relativize(real).toString() else path.fileName.toString()
}
