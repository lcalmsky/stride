package stride.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.core.Disposable
import stride.core.Disposer
import stride.core.extensions.ExtensionPointName
import stride.core.services.Project
import stride.core.services.Service
import stride.core.vfs.VFile
import java.nio.file.Path

/** Open editor tabs of one project, ordered as they were opened. */
@Service(Service.Level.PROJECT)
class FileEditorManager(private val project: Project) : Disposable {
    private val providers: List<FileEditorProvider> by lazy {
        ExtensionPointName<FileEditorProvider>(FileEditorProvider.EP_NAME).extensionList()
    }

    private val openFilesState = mutableStateListOf<FileEditor>()
    private val recentFilesState = mutableStateListOf<VFile>()

    val openFiles: List<FileEditor> get() = openFilesState

    /** Most recently opened or selected files, newest first; closed files stay. */
    val recentFiles: List<VFile> get() = recentFilesState

    var selected by mutableStateOf<FileEditor?>(null)
        private set

    /** Called whenever a different editor becomes selected; used by the navigation history. */
    var onSelectionChanged: (FileEditor) -> Unit = {}

    private val closedFiles = ArrayList<VFile>()

    private var disposed = false

    /**
     * Opens [file] in a new tab, or selects its existing tab. Returns `null` when no provider
     * accepts the file.
     */
    fun openFile(file: VFile): FileEditor? {
        if (disposed) return null
        val key = keyOf(file)
        openFilesState.firstOrNull { keyOf(it.file) == key }?.let {
            select(it)
            touchRecent(it.file)
            return it
        }
        val provider = providers.firstOrNull { it.accept(project, file) } ?: return null
        val editor = provider.createEditor(project, file)
        openFilesState.add(editor)
        select(editor)
        touchRecent(file)
        return editor
    }

    /**
     * Opens [file] and moves the caret to the start of [line] (0-based); the editor scrolls
     * there once it is composed. Returns `null` when no provider accepts the file.
     */
    fun openFileAtLine(file: VFile, line: Int): FileEditor? {
        val editor = openFile(file) ?: return null
        (editor as? TextFileEditor)?.gotoLine((line + 1).toString())
        return editor
    }

    /** Closes [file]'s tab, disposing its editor; the file can be reopened later. */
    fun closeFile(file: VFile) {
        val index = openFilesState.indexOfFirst { keyOf(it.file) == keyOf(file) }
        if (index < 0) return
        val editor = openFilesState.removeAt(index)
        if (selected === editor) {
            select(openFilesState.getOrNull(index) ?: openFilesState.getOrNull(index - 1))
        }
        rememberClosed(file)
        Disposer.dispose(editor)
    }

    /** Closes every tab except the selected one. */
    fun closeOthers() {
        val keep = selected ?: return
        val others = openFilesState.filter { it !== keep }
        for (editor in others) {
            openFilesState.remove(editor)
            rememberClosed(editor.file)
            Disposer.dispose(editor)
        }
        select(keep)
    }

    /** Reopens the most recently closed tab; `null` when no tab was closed. */
    fun reopenClosed(): FileEditor? {
        val file = closedFiles.removeLastOrNull() ?: return null
        return openFile(file)
    }

    /** Selects the next tab, wrapping around; does nothing with fewer than two tabs. */
    fun selectNext() = moveSelection(1)

    /** Selects the previous tab, wrapping around; does nothing with fewer than two tabs. */
    fun selectPrevious() = moveSelection(-1)

    private fun moveSelection(step: Int) {
        val count = openFilesState.size
        if (count <= 1) return
        val current = openFilesState.indexOfFirst { it === selected }
        if (current < 0) {
            val next = openFilesState[0]
            select(next)
            touchRecent(next.file)
            return
        }
        val next = openFilesState[(current + step + count) % count]
        select(next)
        touchRecent(next.file)
    }

    private fun select(editor: FileEditor?) {
        if (selected === editor) return
        selected = editor
        if (editor != null) onSelectionChanged(editor)
    }

    private fun rememberClosed(file: VFile) {
        val key = keyOf(file)
        closedFiles.removeAll { keyOf(it) == key }
        closedFiles.add(file)
        while (closedFiles.size > MAX_CLOSED_FILES) closedFiles.removeAt(0)
    }

    private fun touchRecent(file: VFile) {
        if (disposed) return
        val key = keyOf(file)
        val existing = recentFilesState.indexOfFirst { keyOf(it) == key }
        if (existing >= 0) recentFilesState.removeAt(existing)
        recentFilesState.add(0, file)
        while (recentFilesState.size > MAX_RECENT_FILES) recentFilesState.removeAt(recentFilesState.lastIndex)
    }

    fun saveSelected() {
        selected?.save()
    }

    fun closeSelected() {
        selected?.let { closeFile(it.file) }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        val editors = openFilesState.toList()
        openFilesState.clear()
        recentFilesState.clear()
        closedFiles.clear()
        selected = null
        for (editor in editors) Disposer.dispose(editor)
    }

    private fun keyOf(file: VFile): Path = file.path.toAbsolutePath().normalize()

    companion object {
        const val MAX_RECENT_FILES: Int = 50
        const val MAX_CLOSED_FILES: Int = 50
    }
}
