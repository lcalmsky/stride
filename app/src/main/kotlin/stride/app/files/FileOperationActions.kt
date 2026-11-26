package stride.app.files

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.keys.KeyContext
import stride.app.terminal.TerminalSession
import stride.core.services.service
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The popup page of one project tree file operation (P7–P10). */
enum class FileOperationPage { NEW_LIST, NEW_NAME, KOTLIN, RENAME, MOVE, COPY, DELETE }

/** The three rows of the New popup's list, in IntelliJ's `NewGroup` order. */
enum class NewKind(val label: String) {
    KOTLIN("Kotlin Class/File"),
    FILE("File"),
    DIRECTORY("Directory"),
}

/**
 * The state of one open file-operation popup; [AppState.fileOperation] holds it, so the pages and
 * the typed text outlive recomposition. [target] is the directory a New page creates in and the
 * file or directory every other page works on.
 */
data class FileOperationRequest(
    val page: FileOperationPage,
    val target: Path,
    val text: TextFieldValue = TextFieldValue(),
    val newKind: NewKind = NewKind.KOTLIN,
    val kotlinKind: KotlinFileKind = KotlinFileKind.CLASS,
    val newDirectory: Boolean = false,
    val error: String? = null,
    val returnContext: KeyContext = KeyContext.PROJECT_TREE,
)

/**
 * P4: the context a file action counts as coming from. The Actions tab dispatches with the search
 * popup still in `focus`, so it answers with the context Search Everywhere replaced.
 */
internal fun fileOperationContext(state: AppState): KeyContext =
    if (state.focus == KeyContext.SEARCH_POPUP) state.searchOpenedFrom else state.focus

/**
 * P4: the path an action works on. In the tree it is the tree selection, else the highlighted row
 * (the selected editor's file, else the root); elsewhere it is the selected editor's file.
 */
internal fun fileOperationTarget(state: AppState): Path? {
    val session = state.session ?: return null
    val root = session.project.root.toAbsolutePath().normalize()
    if (fileOperationContext(state) != KeyContext.PROJECT_TREE) {
        return session.editors.selected?.file?.path?.toAbsolutePath()?.normalize()
    }
    return session.tree.selected
        ?: session.editors.selected?.file?.path?.toAbsolutePath()?.normalize()
        ?: root
}

/** P7: `NEW_ELEMENT` — the New popup in the selected directory, else the selected file's parent. */
fun startNewElement(state: AppState): Boolean {
    val session = state.session ?: return false
    val target = fileOperationTarget(state)
    val root = session.project.root.toAbsolutePath().normalize()
    val directory = when {
        target == null -> root
        Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) -> target
        else -> target.parent ?: root
    }
    openFileOperation(state, FileOperationRequest(FileOperationPage.NEW_LIST, directory))
    return true
}

/** P8: `RENAME_FILE` — a field prefilled with the name, the stem selected for a file. */
fun startRenameFile(state: AppState): Boolean {
    val target = fileOperationTarget(state) ?: return noTarget(state)
    val name = target.fileName?.toString() ?: target.toString()
    val stem = if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        name.length
    } else {
        name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    }
    openFileOperation(
        state,
        FileOperationRequest(
            FileOperationPage.RENAME,
            target,
            text = TextFieldValue(name, TextRange(0, stem)),
        ),
    )
    return true
}

/** P8: `MOVE_ELEMENT` — a field prefilled with the source's parent, relative to the root. */
fun startMoveElement(state: AppState): Boolean {
    val session = state.session ?: return false
    val target = fileOperationTarget(state) ?: return noTarget(state)
    val root = session.project.root.toAbsolutePath().normalize()
    val relative = root.relativize(target.parent ?: root).toString()
    openFileOperation(
        state,
        FileOperationRequest(
            FileOperationPage.MOVE,
            target,
            text = TextFieldValue(relative, TextRange(relative.length)),
        ),
    )
    return true
}

/** P9: `DELETE_ELEMENT` — the confirmation popup. */
fun startDeleteElement(state: AppState): Boolean {
    val target = fileOperationTarget(state) ?: return noTarget(state)
    openFileOperation(state, FileOperationRequest(FileOperationPage.DELETE, target))
    return true
}

/** P10: `COPY_ELEMENT` — a field prefilled with the source's relative path, its name selected. */
fun startCopyElement(state: AppState): Boolean {
    val session = state.session ?: return false
    val target = fileOperationTarget(state) ?: return noTarget(state)
    val relative = session.project.root.toAbsolutePath().normalize().relativize(target).toString()
    val nameStart = relative.lastIndexOf('/') + 1
    openFileOperation(
        state,
        FileOperationRequest(
            FileOperationPage.COPY,
            target,
            text = TextFieldValue(relative, TextRange(nameStart, relative.length)),
        ),
    )
    return true
}

/** P10: `CUT_FILE` — remembers the tree target in the file clipboard. */
fun cutFile(state: AppState): Boolean = clipboardAction(state) { files, target ->
    files.cut(target)
}

/** P10: `COPY_FILE` — remembers the tree target in the file clipboard. */
fun copyFile(state: AppState): Boolean = clipboardAction(state) { files, target ->
    files.copy(target)
}

/** P10: `PASTE_FILE` — pastes into the selected directory, or the selected file's parent. */
fun pasteFile(state: AppState): Boolean {
    val session = treeOnlyClipboardAction(state) ?: return true
    val target = fileOperationTarget(state) ?: return true
    val root = session.project.root.toAbsolutePath().normalize()
    val directory = if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        target
    } else {
        target.parent ?: root
    }
    when (val result = session.files.paste(directory)) {
        null -> state.statusMessage = null
        is FileOperationResult.Success -> state.statusMessage = result.message
        is FileOperationResult.Failure -> state.statusMessage = result.message
        is FileOperationResult.OpenCopyPopup -> {
            val source = session.files.clipboard?.path
            if (source == null) {
                state.statusMessage = result.message
            } else {
                val relative = root.relativize(result.path).toString()
                val nameStart = relative.lastIndexOf('/') + 1
                openFileOperation(
                    state,
                    FileOperationRequest(
                        FileOperationPage.COPY,
                        source,
                        text = TextFieldValue(relative, TextRange(nameStart, relative.length)),
                        error = result.message,
                    ),
                )
            }
        }
    }
    return true
}

/** P11: Reveal in Finder for the tree target. */
fun revealFileInFinder(state: AppState): Boolean {
    val session = state.session ?: return false
    val target = fileOperationTarget(state) ?: return noTarget(state)
    val result = session.files.revealInFinder(target)
    state.statusMessage = result.message
    return true
}

/** P11: shows the terminal at the target's directory and reports a refused `cd`. */
fun openFileInTerminal(state: AppState): Boolean {
    val session = state.session ?: return false
    val target = fileOperationTarget(state) ?: return noTarget(state)
    val root = session.project.root.toAbsolutePath().normalize()
    val directory = if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        target
    } else {
        target.parent ?: root
    }
    state.toolWindows.activate("terminal")
    state.focus = KeyContext.TERMINAL
    state.statusMessage = session.project.service<TerminalSession>().openAt(directory)
    return true
}

/**
 * Hands the keyboard back after a file-operation popup closed: a created file's own editor takes
 * it, otherwise the tree does when it opened the popup and is still shown, else the editor (P4).
 */
fun closeFileOperation(state: AppState, returnContext: KeyContext, created: Path?) {
    val session = state.session
    if (created != null && session != null) {
        session.editors.openFile(VFile(created))
        state.focusSelectedEditor()
        return
    }
    if (returnContext == KeyContext.PROJECT_TREE && state.projectTreeVisible) {
        state.focusProjectTree()
    } else {
        state.focusSelectedEditor()
    }
}

private fun openFileOperation(state: AppState, request: FileOperationRequest) {
    val context = fileOperationContext(state)
    state.statusMessage = null
    state.fileOperation = request.copy(returnContext = context)
    state.focus = KeyContext.CONTEXT_POPUP
}

private fun noTarget(state: AppState): Boolean {
    state.statusMessage = "No file selected"
    return true
}

/** P4: the clipboard actions only work on the tree target. */
private fun treeOnlyClipboardAction(state: AppState): ProjectSession? {
    if (fileOperationContext(state) != KeyContext.PROJECT_TREE) {
        state.statusMessage = "Cut, copy and paste files in the Project tree"
        return null
    }
    return state.session
}

private fun clipboardAction(
    state: AppState,
    action: (FileOperations, Path) -> FileOperationResult,
): Boolean {
    val session = treeOnlyClipboardAction(state) ?: return true
    val target = fileOperationTarget(state) ?: return true
    state.statusMessage = action(session.files, target).message
    return true
}
