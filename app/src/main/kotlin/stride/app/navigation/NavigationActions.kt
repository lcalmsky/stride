package stride.app.navigation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.EditActions
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindModel
import stride.app.editor.find.FindOptions
import stride.app.keys.KeyContext
import stride.app.search.FindTabKind
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

/** Opens [file] at [line] and [column] (0-based) and records the landing position as one history entry. */
fun openFileAtColumn(state: AppState, session: ProjectSession, file: VFile, line: Int, column: Int) {
    if (!Files.exists(file.path)) {
        state.statusMessage = missingFileMessage(session, file.path)
        return
    }
    val editor = session.history.withoutRecording {
        session.editors.openFile(file)?.also { opened ->
            val textEditor = opened as? TextFileEditor ?: return@also
            textEditor.jumpTo(EditActions.gotoLine(textEditor.value.text, line + 1, column + 1).start)
        }
    } ?: return
    session.history.push(navigationLocation(editor))
    state.focus = KeyContext.EDITOR
    state.statusMessage = null
    (editor as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
}

/**
 * Opens [path] with the caret at [offset] (clamped) and records the landing position as one
 * history entry. A file that no longer exists is reported and nothing opens; returns whether it opened.
 */
fun openFileAtOffset(state: AppState, session: ProjectSession, path: Path, offset: Int): Boolean {
    if (!Files.exists(path)) {
        state.statusMessage = missingFileMessage(session, path)
        return false
    }
    val editor = session.history.withoutRecording {
        session.editors.openFile(VFile(path))?.also { opened ->
            val textEditor = opened as? TextFileEditor ?: return@also
            textEditor.jumpTo(offset.coerceIn(0, textEditor.value.text.length))
        }
    } ?: return false
    session.history.push(navigationLocation(editor))
    state.focus = KeyContext.EDITOR
    state.statusMessage = null
    (editor as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
    return true
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

/** The scan every usage search runs: whole word and case-sensitive, like IntelliJ's usages. */
private val USAGE_OPTIONS = FindOptions(caseSensitive = true, wholeWord = true)

/** Opens the Find window tab a usage search of [name] shows; ⌥F7 and ⌘↵ both land here. */
fun openUsagesFindWindow(state: AppState, name: String) {
    state.openFindResults(
        kind = FindTabKind.USAGES,
        title = "Usages of $name",
        query = name,
        options = USAGE_OPTIONS,
    )
}

/** Find Usages: a Find window tab for the identifier at the caret, whole-word and case-sensitive. */
fun findUsages(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val name = identifierAt(editor.value.text, editor.file.name, editor.value.selection.start) ?: return false
    openUsagesFindWindow(state, name)
    state.statusMessage = null
    return true
}

/** Show Usages: the same scan as Find Usages, shown in a popup over the editor. */
fun showUsages(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val name = identifierAt(editor.value.text, editor.file.name, editor.value.selection.start)
    if (name == null) {
        state.statusMessage = "No identifier at the caret"
        return true
    }
    state.openShowUsagesPopup(name)
    state.statusMessage = null
    return true
}

/** Whole-word, case-sensitive matches of [name], the same matcher the Find window scans with. */
object UsageMatches {
    fun ranges(text: String, name: String): List<IntRange> =
        FindModel.search(text, name, USAGE_OPTIONS).matches.map { it.start until it.end }

    /** The first start after [caret], wrapping to the first match; `null` without matches. */
    fun next(highlights: List<IntRange>, caret: Int): IntRange? =
        highlights.firstOrNull { it.first > caret } ?: highlights.firstOrNull()

    /** The last start before [caret], wrapping to the last match; `null` without matches. */
    fun previous(highlights: List<IntRange>, caret: Int): IntRange? =
        highlights.lastOrNull { it.first < caret } ?: highlights.lastOrNull()
}

/** The status line Highlight Usages in File leaves up while its highlights are on. */
internal fun usageStatusMessage(count: Int, name: String): String =
    "$count usages of $name$USAGE_STATUS_TAIL"

private const val USAGE_STATUS_TAIL: String = " · ⌘G next · ⇧⌘G previous · Esc clears"

/** Clears the status line when it still shows [usageStatusMessage]; a later message stays. */
internal fun clearUsageStatus(state: AppState) {
    val message = state.statusMessage ?: return
    if (message.endsWith(USAGE_STATUS_TAIL)) state.statusMessage = null
}

/**
 * Highlight Usages in File: the identifier at the caret, or clears the highlights when the
 * caret already sits on one of them.
 */
fun highlightUsagesInFile(state: AppState): Boolean {
    val editor = selectedTextEditor(state) ?: return false
    val caret = editor.value.selection.start
    if (editor.usageHighlights.any { caret in it }) {
        editor.usageHighlights = emptyList()
        state.statusMessage = null
        return true
    }
    val name = identifierAt(editor.value.text, editor.file.name, caret)
    if (name == null) {
        state.statusMessage = "No identifier at the caret"
        return true
    }
    val highlights = UsageMatches.ranges(editor.value.text, name)
    editor.usageHighlights = highlights
    state.statusMessage = usageStatusMessage(highlights.size, name)
    return true
}

/** Jump to Last Change: the newest edit place, walking back on every repeated press. */
fun jumpToLastChange(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = selectedTextEditor(state)
    val path = editor?.file?.path?.toAbsolutePath()?.normalize()
    val line = editor?.let { BookmarkManager.lineIndex(it.value.text, it.value.selection.start) }
    val lineOf: (Int) -> Int = { offset -> editor?.let { BookmarkManager.lineIndex(it.value.text, offset) } ?: 0 }
    val place = session.editLocations.next(path, line, lineOf)
    if (place == null) {
        state.statusMessage = "No edit location"
        return true
    }
    if (!Files.exists(place.path)) {
        session.editLocations.drop(place.path)
        state.statusMessage = missingFileMessage(session, place.path)
        return true
    }
    openFileAtOffset(state, session, place.path, place.offset)
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
