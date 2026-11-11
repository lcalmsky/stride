package stride.app.navigation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.EditActions
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import java.nio.file.Files
import java.nio.file.Path

/** Shown when a lookup cannot answer yet because the background index is still scanning. */
const val INDEXING_MESSAGE: String = "Indexing… try again in a moment"

/** The text editor the action acts on, or `null` when something else is selected. */
fun selectedTextEditor(state: AppState): TextFileEditor? =
    state.session?.editors?.selected as? TextFileEditor

/**
 * The identifier touching [offset]. In Kotlin a leading `$` introduces a string template, so it
 * is not part of the name; in Java `$` is an ordinary identifier character and stays.
 */
internal fun identifierAt(text: String, fileName: String, offset: Int): String? {
    val word = EditActions.wordAt(text, offset) ?: return null
    val name = text.substring(word.min, word.max)
    val result = if (fileName.substringAfterLast('.', "").equals("java", ignoreCase = true)) {
        name
    } else {
        name.trimStart('$')
    }
    return result.ifEmpty { null }
}

private fun missingFileMessage(session: ProjectSession, path: Path): String =
    "File not found: ${relativePath(session, VFile(path))}"

fun relativePath(session: ProjectSession, file: VFile): String {
    val path = file.path.toAbsolutePath().normalize()
    return runCatching { session.project.root.relativize(path).toString().replace('\\', '/') }
        .getOrDefault(path.toString())
}

/** Opens [file] at [line] and records the landing position as one history entry. */
fun openFileAtLine(state: AppState, session: ProjectSession, file: VFile, line: Int) {
    val editor = session.history.withoutRecording { session.editors.openFileAtLine(file, line) } ?: return
    session.history.push(navigationLocation(editor))
    state.focus = KeyContext.EDITOR
    state.statusMessage = null
    (editor as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
}

/** Opens [location]; a target whose file is gone is reported and dropped from the history. */
private fun openLocation(state: AppState, session: ProjectSession, location: NavigationLocation): Boolean {
    if (!Files.exists(location.path)) {
        session.history.dropCurrent()
        state.statusMessage = missingFileMessage(session, location.path)
        return false
    }
    val editor = session.history.withoutRecording { session.editors.openFile(VFile(location.path)) } ?: return true
    val textEditor = editor as? TextFileEditor
    if (textEditor != null) {
        val offset = location.offset.coerceIn(0, textEditor.value.text.length)
        textEditor.updateValue(TextFieldValue(textEditor.value.text, TextRange(offset)))
        runCatching { textEditor.focusRequester.requestFocus() }
    }
    state.focus = KeyContext.EDITOR
    return true
}

fun navigateBack(state: AppState): Boolean {
    val session = state.session ?: return false
    val location = session.history.back() ?: return true
    openLocation(state, session, location)
    return true
}

fun navigateForward(state: AppState): Boolean {
    val session = state.session ?: return false
    val location = session.history.forward() ?: return true
    openLocation(state, session, location)
    return true
}

/** Opens [bookmark]; a bookmark whose file is gone is dropped with a status message. */
fun openBookmark(state: AppState, bookmark: Bookmark): Boolean {
    val session = state.session ?: return false
    if (!Files.exists(bookmark.path)) {
        session.bookmarks.remove(bookmark)
        state.statusMessage = "File not found: ${bookmark.relativePath}"
        return true
    }
    openFileAtLine(state, session, VFile(bookmark.path), bookmark.line)
    return true
}

/** Go to Declaration at the caret, or at [offset] for a ⌘-click. */
fun goToDeclaration(state: AppState, offset: Int = selectedTextEditor(state)?.value?.selection?.start ?: 0): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state) ?: return false
    val text = editor.value.text
    val name = identifierAt(text, editor.file.name, offset) ?: return false
    val path = editor.file.path.toAbsolutePath().normalize()
    val relative = relativePath(session, editor.file)

    val local = DeclarationNavigator.localTarget(editor.parsedDeclarations, name, BookmarkManager.lineIndex(text, offset))
    if (local != null) {
        session.history.withoutRecording { editor.gotoLine((local.line + 1).toString()) }
        session.history.push(navigationLocation(editor))
        state.focus = KeyContext.EDITOR
        state.statusMessage = null
        return true
    }

    val index = session.project.service<DeclarationIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val candidates = index.snapshot()
        .filter { it.name == name && it.file.path.toAbsolutePath().normalize() != path }
    val ranked = DeclarationNavigator.ranked(
        candidates,
        DeclarationNavigator.packageOf(text, relative),
        DeclarationNavigator.moduleOfPath(relative),
    )
    state.statusMessage = null
    when {
        ranked.isEmpty() -> state.statusMessage = "Cannot find declaration to go to"
        ranked.size == 1 -> openDeclaration(state, session, ranked.first())
        else -> {
            state.declarationChoices = ranked
            state.declarationChooserVisible = true
            state.focus = KeyContext.CONTEXT_POPUP
        }
    }
    return true
}

/** Opens a declaration picked by [goToDeclaration]'s chooser. */
fun openDeclaration(state: AppState, session: ProjectSession, declaration: Declaration) {
    openFileAtLine(state, session, declaration.file, declaration.line)
}

/** Jumps between a source file and its test counterpart. */
fun goToTest(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected ?: return false
    val index = session.project.service<FileNameIndex>()
    if (!index.isReady) {
        state.statusMessage = INDEXING_MESSAGE
        return true
    }
    val candidates = TestNavigation.candidates(
        editor.file.name,
        relativePath(session, editor.file),
        index.snapshot(),
    ).map { it.file }
    state.statusMessage = null
    when {
        candidates.isEmpty() -> {
            val base = TestNavigation.baseName(editor.file.name) ?: editor.file.name
            state.statusMessage = "No test found for $base"
        }
        candidates.size == 1 -> openFileAtLine(state, session, candidates.first(), 0)
        else -> {
            state.testChoices = candidates
            state.testChooserVisible = true
            state.focus = KeyContext.CONTEXT_POPUP
        }
    }
    return true
}

/** Opens the file structure popup for the selected editor's file. */
fun openFileStructure(state: AppState): Boolean {
    selectedTextEditor(state) ?: return false
    state.fileStructureVisible = true
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Find in Path prefilled with the identifier at the caret and whole-word matching on. */
fun findUsages(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val name = identifierAt(editor.value.text, editor.file.name, editor.value.selection.start) ?: return false
    state.openFindInPath(name, wholeWord = true)
    state.statusMessage = null
    return true
}

fun toggleBookmark(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val text = editor.value.text
    val line = BookmarkManager.lineIndex(text, editor.value.selection.start)
    val path = editor.file.path.toAbsolutePath().normalize()
    session.bookmarks.toggle(
        Bookmark(path, relativePath(session, editor.file), line, BookmarkManager.lineText(text, line)),
    )
    editor.bookmarkLines = session.bookmarks.linesOf(path)
    return true
}

fun showBookmarks(state: AppState): Boolean {
    state.session ?: return false
    state.bookmarksVisible = true
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** Copies the absolute path, or `relative/path:line` when [reference] is set. */
fun copyPath(state: AppState, reference: Boolean): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected ?: return false
    val text = if (reference) {
        val line = (editor as? TextFileEditor)?.let {
            BookmarkManager.lineIndex(it.value.text, it.value.selection.start)
        } ?: 0
        "${relativePath(session, editor.file)}:${line + 1}"
    } else {
        editor.file.path.toAbsolutePath().normalize().toString()
    }
    if (state.clipboard.writeText(text)) state.statusMessage = "Copied $text"
    return true
}

/** Expands the tree to the selected file and scrolls to it. */
fun selectInProjectView(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected ?: return false
    state.projectTreeVisible = true
    if (session.tree.reveal(editor.file)) state.requestTreeReveal()
    return true
}
