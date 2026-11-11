package stride.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.app.editor.FileEditorManager
import stride.app.editor.find.FindBarState
import stride.app.keys.KeyContext
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
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import stride.search.SearchTab
import java.nio.file.Path

/** One opened folder: its project, tree model and editor tabs. */
class ProjectSession private constructor(val project: Project) : Disposable {
    val editors: FileEditorManager
    val tree: ProjectTreeModel

    init {
        project.service<VirtualFileManager>().start()
        project.service<FileNameIndex>()
        project.service<DeclarationIndex>()
        editors = project.service()
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

    var recentFilesVisible by mutableStateOf(false)

    var contextActions: List<ContextAction> by mutableStateOf(emptyList())
    var contextActionsVisible by mutableStateOf(false)

    var runConfigurationChoices: List<RunConfiguration> by mutableStateOf(emptyList())
    var runConfigurationsVisible by mutableStateOf(false)

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
        recentFilesVisible = false
        contextActions = emptyList()
        contextActionsVisible = false
        runConfigurationChoices = emptyList()
        runConfigurationsVisible = false
        statusMessage = null
        toolWindows.hide()
        focus = KeyContext.GLOBAL
    }

    fun dispose() = closeSession()
}
