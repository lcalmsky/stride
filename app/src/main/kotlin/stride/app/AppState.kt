package stride.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.app.editor.CompletionRequest
import stride.app.editor.FileEditorManager
import stride.app.editor.RenameRequest
import stride.app.editor.SystemTextClipboard
import stride.app.editor.TextClipboard
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindBarState
import stride.app.keys.KeyContext
import stride.app.navigation.BookmarkManager
import stride.app.navigation.NavigationHistory
import stride.app.navigation.navigationLocation
import stride.app.run.ContextAction
import stride.app.run.RunConfiguration
import stride.app.run.RunManager
import stride.app.run.RunToolWindow
import stride.app.terminal.TerminalSession
import stride.app.terminal.TerminalToolWindow
import stride.app.tree.ProjectTreeModel
import stride.app.tree.TREE_STRUCTURE_PROVIDER_EP
import stride.app.tree.TreeStructureProvider
import stride.app.ui.theme.DesignTokens
import stride.app.ui.toolwindow.ToolWindow
import stride.app.ui.toolwindow.ToolWindowManager
import stride.core.Disposable
import stride.core.extensions.ExtensionPointName
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import stride.core.vfs.VirtualFileManager
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import stride.search.SearchTab
import java.nio.file.Path

/** One opened folder: its project, tree model and editor tabs. */
class ProjectSession private constructor(val project: Project) : Disposable {
    val editors: FileEditorManager
    val tree: ProjectTreeModel

    /** Back/forward locations of this session. */
    val history = NavigationHistory()

    /** Bookmarks of this session; they outlive the tab the line was set in. */
    val bookmarks = BookmarkManager()

    init {
        project.service<VirtualFileManager>().start()
        project.service<FileNameIndex>()
        project.service<DeclarationIndex>()
        editors = project.service()
        editors.onSelectionChanged = { editor -> history.record(navigationLocation(editor)) }
        val providers = ExtensionPointName<TreeStructureProvider>(TREE_STRUCTURE_PROVIDER_EP).extensionList()
        tree = ProjectTreeModel(VFile(project.root), providers, project)
    }

    fun displayName(): String = project.root.fileName?.toString() ?: project.root.toString()

    override fun dispose() {
        tree.dispose()
        project.dispose()
    }

    companion object {
        fun open(path: Path): ProjectSession = ProjectSession(Project(path.toAbsolutePath().normalize()))
    }
}

class AppState {
    var session by mutableStateOf<ProjectSession?>(null)
        private set

    var searchVisible by mutableStateOf(false)

    /** Tab the popup opens on; set by the action that opens Search Everywhere. */
    var searchTab by mutableStateOf(SearchTab.ALL)

    /** Incremented by [openSearch]; the popup is keyed on it so every open starts fresh. */
    var searchGeneration by mutableStateOf(0)
        private set

    var focus by mutableStateOf(KeyContext.GLOBAL)

    val findBar = FindBarState()

    var findInPathVisible by mutableStateOf(false)

    /** Query the Find in Path popup opens with, and whether it starts in whole-word mode. */
    var findInPathQuery by mutableStateOf("")

    var findInPathWholeWord by mutableStateOf(false)

    /** Incremented by [openFindInPath]; the popup is keyed on it so every open starts fresh. */
    var findInPathGeneration by mutableStateOf(0)
        private set

    var recentFilesVisible by mutableStateOf(false)

    /** Candidates of the Go to Declaration chooser; empty when it is closed. */
    var declarationChoices by mutableStateOf(emptyList<Declaration>())

    var declarationChooserVisible by mutableStateOf(false)

    var fileStructureVisible by mutableStateOf(false)

    /** Candidates of the Go to Test chooser; empty when it is closed. */
    var testChoices by mutableStateOf(emptyList<VFile>())

    var testChooserVisible by mutableStateOf(false)

    var bookmarksVisible by mutableStateOf(false)

    var projectTreeVisible by mutableStateOf(true)

    /** Incremented to hand the keyboard to the project tree. */
    var treeFocusRequests by mutableStateOf(0)
        private set

    /** Incremented to hand the keyboard back to the selected text editor. */
    var editorFocusRequests by mutableStateOf(0)
        private set

    /** Incremented to scroll the project tree to the selected file. */
    var revealRequests by mutableStateOf(0)
        private set

    /** Clipboard for Copy Path and Copy Reference; replaced in tests. */
    var clipboard: TextClipboard = SystemTextClipboard

    private var treeBeforeHide: Boolean? = null
    private var toolWindowBeforeHide: Boolean? = null

    var contextActions: List<ContextAction> by mutableStateOf(emptyList())
    var contextActionsVisible by mutableStateOf(false)

    var runConfigurationChoices: List<RunConfiguration> by mutableStateOf(emptyList())
    var runConfigurationsVisible by mutableStateOf(false)

    /** The open completion popup; `null` when it is closed. */
    var completion by mutableStateOf<CompletionRequest?>(null)

    /** The open rename popup; `null` when it is closed. */
    var rename by mutableStateOf<RenameRequest?>(null)

    var statusMessage: String? by mutableStateOf(null)

    val toolWindows = ToolWindowManager()

    init {
        toolWindows.register(
            ToolWindow("run", "Run", KeyContext.RUN_WINDOW) { modifier ->
                val current = session
                if (current == null) {
                    Text(
                        "Run — no configuration yet",
                        color = DesignTokens.muted,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = modifier.padding(DesignTokens.space12),
                    )
                } else {
                    RunToolWindow(
                        current.project.service<RunManager>(),
                        modifier,
                        focusRequests = toolWindows.focusRequests,
                    )
                }
            },
        )
        toolWindows.register(
            ToolWindow("terminal", "Terminal", KeyContext.TERMINAL) { modifier ->
                val current = session
                if (current == null) {
                    Text(
                        "Terminal — not started",
                        color = DesignTokens.muted,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = modifier.padding(DesignTokens.space12),
                    )
                } else {
                    TerminalToolWindow(
                        current.project.service<TerminalSession>(),
                        modifier,
                        focusRequests = toolWindows.focusRequests,
                    )
                }
            },
        )
    }

    /** Opens Search Everywhere on [tab], resetting the popup even when it is already open. */
    fun openSearch(tab: SearchTab) {
        searchTab = tab
        searchGeneration++
        searchVisible = true
        focus = KeyContext.SEARCH_POPUP
    }

    /** Opens Find in Path prefilled with [query], resetting the popup. */
    fun openFindInPath(query: String = "", wholeWord: Boolean = false) {
        findInPathQuery = query
        findInPathWholeWord = wholeWord
        findInPathGeneration++
        findInPathVisible = true
        focus = KeyContext.SEARCH_POPUP
    }

    /** Shows or hides the project tree; showing it asks for the tree's focus, hiding hands it back. */
    fun toggleProjectTree() {
        projectTreeVisible = !projectTreeVisible
        if (projectTreeVisible) treeFocusRequests++ else handFocusToEditor()
    }

    /** Hides the tree and the tool window panel; the next call restores the layout it hid. */
    fun hideAllWindows() {
        if (projectTreeVisible || toolWindows.visible) {
            treeBeforeHide = projectTreeVisible
            toolWindowBeforeHide = toolWindows.visible
            projectTreeVisible = false
            toolWindows.hide()
            handFocusToEditor()
            return
        }
        val tree = treeBeforeHide ?: return
        val tools = toolWindowBeforeHide ?: false
        treeBeforeHide = null
        toolWindowBeforeHide = null
        projectTreeVisible = tree
        if (tools) toolWindows.visible = true
        if (projectTreeVisible) treeFocusRequests++
    }

    /**
     * Moves the focus context to the selected text editor and asks it to take the keyboard, so
     * hiding the panel that owned focus does not leave both the context and the real focus gone.
     */
    private fun handFocusToEditor() {
        if (session?.editors?.selected !is TextFileEditor) {
            focus = KeyContext.GLOBAL
            return
        }
        focus = KeyContext.EDITOR
        editorFocusRequests++
    }

    /** Hides the active tool window and hands the keyboard back to the selected text editor. */
    fun hideActiveWindow() {
        toolWindows.hide()
        handFocusToEditor()
    }

    /** Asks the project tree to scroll to the selected file. */
    fun requestTreeReveal() {
        revealRequests++
    }

    fun openFolder(path: Path) {
        closeSession()
        session = ProjectSession.open(path)
    }

    fun closeSession() {
        session?.dispose()
        session = null
        searchVisible = false
        searchTab = SearchTab.ALL
        findBar.close()
        findInPathVisible = false
        findInPathQuery = ""
        findInPathWholeWord = false
        recentFilesVisible = false
        declarationChoices = emptyList()
        declarationChooserVisible = false
        fileStructureVisible = false
        testChoices = emptyList()
        testChooserVisible = false
        bookmarksVisible = false
        contextActions = emptyList()
        contextActionsVisible = false
        runConfigurationChoices = emptyList()
        runConfigurationsVisible = false
        completion = null
        rename = null
        statusMessage = null
        projectTreeVisible = true
        treeBeforeHide = null
        toolWindowBeforeHide = null
        toolWindows.hide()
        focus = KeyContext.GLOBAL
    }

    fun dispose() = closeSession()
}
