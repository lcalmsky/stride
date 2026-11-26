package stride.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import stride.app.editor.ClipboardHistory
import stride.app.editor.CompletionRequest
import stride.app.editor.PasteHistoryRequest
import stride.app.editor.FileEditorManager
import stride.app.editor.RenameRequest
import stride.app.editor.SurroundRequest
import stride.app.editor.SystemTextClipboard
import stride.app.editor.TextClipboard
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindBarState
import stride.app.editor.find.FindOptions
import stride.app.files.FileOperations
import stride.app.generate.GenerateRequest
import stride.app.generate.MemberChooseRequest
import stride.app.generate.prepareGenerateWithProviders
import stride.app.generate.projectTypes
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.hierarchy.HierarchyToolWindow
import stride.app.hierarchy.HierarchyToolWindowState
import stride.app.hierarchy.HierarchyTree
import stride.app.navigation.BookmarkManager
import stride.app.navigation.BookmarksToolWindow
import stride.app.navigation.EditLocations
import stride.app.navigation.HierarchyGraphState
import stride.app.navigation.NavigationBarState
import stride.app.navigation.NavigationHistory
import stride.app.navigation.NavigationLocation
import stride.app.navigation.NavigationTargets
import stride.app.navigation.ParameterInfoRequest
import stride.app.navigation.PeekRequest
import stride.app.navigation.StructureToolWindow
import stride.app.navigation.navigationLocation
import stride.app.navigation.openBookmark
import stride.app.navigation.openFileAtColumn
import stride.app.navigation.openFileAtOffset
import stride.app.problems.ErrorDescriptionRequest
import stride.app.problems.FileProblem
import stride.app.problems.ProblemsModel
import stride.app.problems.ProblemsToolWindow
import stride.app.problems.ProblemsToolWindowState
import stride.app.refactor.RefactorThisRequest
import stride.app.refactor.RefactoringChooseRequest
import stride.app.refactor.RefactoringNameRequest
import stride.app.refactor.prepareWithProviders
import stride.app.search.FindReplaceController
import stride.app.search.FindTabKind
import stride.app.search.FindToolWindow
import stride.app.search.FindToolWindowState
import stride.app.search.ReplaceOutcome
import stride.app.search.ReplacePlan
import stride.app.search.ShowUsagesRequest
import stride.app.search.openFindHit
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
import stride.app.ui.theme.UiFont
import stride.app.ui.theme.UiFontChoice
import stride.app.ui.toolwindow.ToolWindow
import stride.app.ui.toolwindow.ToolWindowEmptyState
import stride.app.ui.toolwindow.ToolWindowManager
import stride.app.vcs.BranchesRequest
import stride.app.vcs.ChangeMarkPopupRequest
import stride.app.vcs.CommitToolWindowContent
import stride.app.vcs.CommitWindowState
import stride.app.vcs.DiffRequest
import stride.app.vcs.GitService
import stride.app.vcs.GitToolWindowContent
import stride.app.vcs.PushRequest
import stride.app.vcs.RollbackRequest
import stride.app.vcs.UpdateRequest
import stride.app.vcs.VcsPopupRequest
import stride.core.Disposable
import stride.core.extensions.ExtensionPointName
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import stride.core.vfs.VirtualFileManager
import stride.lang.GenerateKind
import stride.lang.RefactoringKind
import stride.lang.RefactoringStep
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import stride.search.SearchTab
import java.nio.file.Path

/** One opened folder: its project, tree model and editor tabs. */
class ProjectSession private constructor(val project: Project) : Disposable {
    val editors: FileEditorManager
    val tree: ProjectTreeModel
    val git: GitService

    /** The project tree's file writes (P5–P10). */
    val files: FileOperations

    /** Back/forward locations of this session. */
    val history = NavigationHistory()

    /** Bookmarks of this session; they outlive the tab the line was set in. */
    val bookmarks = BookmarkManager()

    /** Last edit places of this session, fed by the selected text editor's change reports. */
    val editLocations = EditLocations()

    /** The Commit window's rows, message and last error, kept while the window is hidden. */
    val commit = CommitWindowState()

    init {
        project.service<VirtualFileManager>().start()
        project.service<FileNameIndex>()
        project.service<DeclarationIndex>()
        editors = project.service()
        editors.onSelectionChanged = { editor -> history.record(navigationLocation(editor)) }
        val providers = ExtensionPointName<TreeStructureProvider>(TREE_STRUCTURE_PROVIDER_EP).extensionList()
        tree = ProjectTreeModel(VFile(project.root), providers, project)
        git = project.service()
        files = FileOperations(project.root.toAbsolutePath().normalize(), editors, tree, bookmarks, git)
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

    /** Whether the Find in Path popup opens in replace mode. */
    var findInPathReplace by mutableStateOf(false)

    /** Incremented by [openFindInPath]; the popup is keyed on it so every open starts fresh. */
    var findInPathGeneration by mutableStateOf(0)
        private set

    var recentFilesVisible by mutableStateOf(false)

    /** The open Show Usages popup; `null` when it is closed. */
    var showUsages by mutableStateOf<ShowUsagesRequest?>(null)

    /** Incremented by every Show Usages request; the popup's scan is keyed on it. */
    var showUsagesGeneration by mutableStateOf(0L)
        private set

    var recentLocationsVisible by mutableStateOf(false)

    /** The caret when Recent Locations opened; the popup leaves that line out while it is open. */
    var recentLocationsCaret by mutableStateOf<NavigationLocation?>(null)
        private set

    /** Incremented by the Recent Locations action; the popup is keyed on it so every open starts fresh. */
    var recentLocationsGeneration by mutableStateOf(0)
        private set

    /** The active navigation bar; `null` when the bar is closed. */
    var navigationBar by mutableStateOf<NavigationBarState?>(null)

    /** Candidates of the Go to Declaration chooser; empty when it is closed. */
    var declarationChoices by mutableStateOf(emptyList<Declaration>())

    var declarationChooserVisible by mutableStateOf(false)

    /** The chooser Go to Implementation, Type Declaration and Super Method open; `null` when it is closed. */
    var navigationTargets by mutableStateOf<NavigationTargets?>(null)

    /** The tree the Hierarchy tool window shows; `null` until the first Type or Call Hierarchy. */
    var hierarchyTree by mutableStateOf<HierarchyTree?>(null)
        private set

    /** The last graph the hierarchy actions built; kept until the index or the open texts change. */
    internal var hierarchyGraph: HierarchyGraphState? = null

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

    /** Clipboard for Copy Path, Copy Reference and Paste from History; replaced in tests. */
    var clipboard: TextClipboard = SystemTextClipboard

    /** Recently copied texts for Paste from History, newest first. */
    val clipboardHistory = ClipboardHistory()

    /** The open Paste from History popup; `null` when it is closed. */
    var pasteHistory by mutableStateOf<PasteHistoryRequest?>(null)

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

    /** The open Refactor This popup; `null` when it is closed. */
    internal var refactorThis by mutableStateOf<RefactorThisRequest?>(null)

    /** The open chooser of a refactoring step; `null` when it is closed. */
    internal var refactoringChoose by mutableStateOf<RefactoringChooseRequest?>(null)

    /** The open refactoring name popup; `null` when it is closed. */
    internal var refactoringName by mutableStateOf<RefactoringNameRequest?>(null)

    /** Prepares a refactoring of an editor; tests replace it to skip the language providers. */
    internal var prepareRefactoring: (TextFileEditor, RefactoringKind) -> RefactoringStep = ::prepareWithProviders

    /** The open Generate popup; `null` when it is closed. */
    internal var generate by mutableStateOf<GenerateRequest?>(null)

    /** The open member chooser of a generation step; `null` when it is closed. */
    internal var memberChoose by mutableStateOf<MemberChooseRequest?>(null)

    /** Prepares a generation of an editor; tests replace it to skip the language providers. */
    internal var prepareGenerate: (TextFileEditor, GenerateKind) -> RefactoringStep =
        { editor, kind -> prepareGenerateWithProviders(editor, kind, projectTypes(this)) }

    /** The open Quick Documentation popup; `null` when it is closed. */
    var quickDoc by mutableStateOf<PeekRequest?>(null)

    /** The open Quick Definition popup; `null` when it is closed. */
    var quickDefinition by mutableStateOf<PeekRequest?>(null)

    /** The open Parameter Info panel, which never takes the keyboard focus. */
    var parameterInfo by mutableStateOf<ParameterInfoRequest?>(null)

    /** The open Show Error Description popup; `null` when it is closed. */
    var errorDescription by mutableStateOf<ErrorDescriptionRequest?>(null)

    /** The open diff popup; `null` when it is closed. G, K and V share this field. */
    internal var diffRequest by mutableStateOf<DiffRequest?>(null)

    /** The open VCS Operations popup; `null` when it is closed. */
    internal var vcsPopup by mutableStateOf<VcsPopupRequest?>(null)

    /** The open push confirmation popup; `null` when it is closed. */
    internal var pushRequest by mutableStateOf<PushRequest?>(null)

    /** The open update confirmation popup; `null` when it is closed. */
    internal var updateRequest by mutableStateOf<UpdateRequest?>(null)

    /** The open rollback confirmation popup; `null` when it is closed. */
    internal var rollbackRequest by mutableStateOf<RollbackRequest?>(null)

    /** The open branches popup; `null` when it is closed. */
    internal var branchesPopup by mutableStateOf<BranchesRequest?>(null)

    /** The open gutter change popup; `null` when it is closed. */
    internal var changeMarkPopup by mutableStateOf<ChangeMarkPopupRequest?>(null)

    /** The open Surround With popup; `null` when it is closed. */
    var surround by mutableStateOf<SurroundRequest?>(null)

    var statusMessage: String? by mutableStateOf(null)

    val toolWindows = ToolWindowManager()

    val findToolWindow = FindToolWindowState()

    /** The Hierarchy window's tree and expansion state, kept while the window is hidden. */
    val hierarchyToolWindow = HierarchyToolWindowState()

    /** The Problems window's selected range, kept while the window is hidden. */
    val problemsToolWindow = ProblemsToolWindowState()

    /** Plans and applies the Find window's REPLACE tabs over the open editor texts and the disk. */
    val findReplace = FindReplaceController(
        files = { session?.project?.service<FileNameIndex>()?.snapshot() ?: emptyList() },
        textOf = ::textOfOpenEditor,
        openEditors = { session?.editors?.openFiles?.filterIsInstance<TextFileEditor>() ?: emptyList() },
    )

    init {
        toolWindows.register(
            ToolWindow("bookmarks", "Bookmarks", KeyContext.LIST_WINDOW) { modifier ->
                val current = session
                if (current == null) {
                    ToolWindowEmptyState(
                        "No bookmarks · F3 toggles one",
                        modifier,
                        focusRequests = toolWindows.focusRequests,
                        onEscape = { handFocusToEditor() },
                    )
                } else {
                    BookmarksToolWindow(
                        bookmarks = current.bookmarks.entries,
                        focusRequests = toolWindows.focusRequests,
                        onOpen = { bookmark -> openBookmark(this@AppState, bookmark) },
                        onRemove = { bookmark -> current.bookmarks.remove(bookmark) },
                        onEscape = { handFocusToEditor() },
                        modifier = modifier,
                    )
                }
            },
        )
        toolWindows.register(
            ToolWindow("find", "Find", KeyContext.LIST_WINDOW) { modifier ->
                FindToolWindow(
                    state = findToolWindow,
                    focusRequests = toolWindows.focusRequests,
                    onOpen = { hit ->
                        val current = session
                        if (current != null) openFindHit(this@AppState, current, hit)
                    },
                    onEscape = { handFocusToEditor() },
                    modifier = modifier,
                    replace = findReplace,
                )
            },
        )
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
            ToolWindow("problems", "Problems", KeyContext.LIST_WINDOW) { modifier ->
                val current = session
                if (current == null) {
                    ToolWindowEmptyState(
                        "No file selected",
                        modifier,
                        focusRequests = toolWindows.focusRequests,
                        onEscape = { handFocusToEditor() },
                    )
                } else {
                    val editor = current.editors.selected as? TextFileEditor
                    ProblemsToolWindow(
                        editor = editor,
                        model = current.project.service<ProblemsModel>(),
                        toolState = problemsToolWindow,
                        focusRequests = toolWindows.focusRequests,
                        keymap = remember { Keymap.forPlatform(KeyRouter.isMacOs()) },
                        root = current.project.root,
                        isOpen = { path ->
                            current.editors.openFiles.any { it.file.path.toAbsolutePath().normalize() == path }
                        },
                        onOpenEditorProblem = { problem -> openEditorProblem(editor, problem) },
                        onOpenFileProblem = { path, problem ->
                            openFileAtColumn(this@AppState, current, VFile(path), problem.line, problem.column)
                        },
                        onEscape = { handFocusToEditor() },
                        modifier = modifier,
                    )
                }
            },
        )
        toolWindows.register(
            ToolWindow("structure", "Structure", KeyContext.LIST_WINDOW) { modifier ->
                val editor = session?.editors?.selected as? TextFileEditor
                if (editor == null) {
                    ToolWindowEmptyState(
                        "No structure",
                        modifier,
                        focusRequests = toolWindows.focusRequests,
                        onEscape = { handFocusToEditor() },
                    )
                } else {
                    StructureToolWindow(
                        editor = editor,
                        focusRequests = toolWindows.focusRequests,
                        onOpen = { entry ->
                            editor.gotoLine((entry.line + 1).toString())
                            handFocusToEditor()
                        },
                        onEscape = { handFocusToEditor() },
                        modifier = modifier,
                    )
                }
            },
        )
        toolWindows.register(
            ToolWindow("commit", "Commit", KeyContext.COMMIT_WINDOW) { modifier ->
                CommitToolWindowContent(this@AppState, modifier)
            },
        )
        toolWindows.register(
            ToolWindow("git", "Git", KeyContext.LIST_WINDOW) { modifier ->
                GitToolWindowContent(this@AppState, modifier)
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

    /** Opens Recent Locations on a fresh request; the popup keyed on it starts from the top. */
    fun openRecentLocations() {
        val current = session ?: return
        recentLocationsGeneration++
        // The caret is read here, not in composition: the popup keeps the line it opened on.
        recentLocationsCaret = (current.editors.selected as? TextFileEditor)?.let { navigationLocation(it) }
        recentLocationsVisible = true
        focus = KeyContext.SEARCH_POPUP
    }

    /** Opens Find in Path (or Replace in Path with [replace]) prefilled with [query]. */
    fun openFindInPath(query: String = "", wholeWord: Boolean = false, replace: Boolean = false) {
        findInPathQuery = query
        findInPathWholeWord = wholeWord
        findInPathReplace = replace
        findInPathGeneration++
        findInPathVisible = true
        focus = KeyContext.SEARCH_POPUP
    }

    /** Applies a Replace in Path plan from the popup and reports its outcome in the status bar. */
    fun applyReplacePlan(plan: ReplacePlan): ReplaceOutcome {
        val outcome = findReplace.apply(plan)
        statusMessage = outcome.message
        return outcome
    }

    /**
     * Adds a Find window tab for [query] and shows the window; Find Usages and the ⌘↵
     * "Open in Find Window" key both land here.
     */
    fun openFindResults(
        kind: FindTabKind,
        title: String,
        query: String,
        options: FindOptions,
        replacement: String? = null,
    ) {
        val current = session ?: return
        findToolWindow.addTab(
            kind = kind,
            query = query,
            options = options,
            title = title,
            files = current.project.service<FileNameIndex>().snapshot(),
            replacement = replacement,
            textOf = ::textOfOpenEditor,
        )
        toolWindows.activate("find")
    }

    /**
     * Shows [tree] in the Hierarchy tool window and focuses its list. The window is registered on
     * first use, after the other tool windows, so it has no tab until a hierarchy is opened.
     */
    fun openHierarchy(tree: HierarchyTree) {
        if (toolWindows.windows.none { it.id == "hierarchy" }) {
            toolWindows.register(
                ToolWindow("hierarchy", "Hierarchy", KeyContext.LIST_WINDOW) { modifier ->
                    HierarchyToolWindow(
                        state = hierarchyToolWindow,
                        focusRequests = toolWindows.focusRequests,
                        onOpen = { node ->
                            val location = node.location
                            val current = session
                            if (location != null && current != null) {
                                openFileAtOffset(this@AppState, current, location.path, location.offset)
                            }
                        },
                        onEscape = { handFocusToEditor() },
                        modifier = modifier,
                    )
                },
            )
        }
        hierarchyTree = tree
        hierarchyToolWindow.show(tree)
        toolWindows.activate("hierarchy")
    }

    /**
     * Opens the Show Usages popup for [name] over the same files and editor texts a Find window
     * tab scans, and hands the popup the keyboard.
     */
    fun openShowUsagesPopup(name: String): ShowUsagesRequest? {
        val current = session ?: return null
        showUsagesGeneration++
        val request = ShowUsagesRequest(
            id = showUsagesGeneration,
            name = name,
            files = current.project.service<FileNameIndex>().snapshot(),
            textOf = ::textOfOpenEditor,
        )
        showUsages = request
        focus = KeyContext.SEARCH_POPUP
        return request
    }

    /** The text of the open editor for [path], so searches see unsaved changes; `null` when closed. */
    fun textOfOpenEditor(path: Path): String? {
        val target = path.toAbsolutePath().normalize()
        return session?.editors?.openFiles
            ?.filterIsInstance<TextFileEditor>()
            ?.firstOrNull { it.file.path.toAbsolutePath().normalize() == target }
            ?.value?.text
    }

    /**
     * The ActivateToolWindow rule for tool windows; hiding hands the keyboard back to the editor.
     */
    fun toggleToolWindow(id: String, context: KeyContext) {
        val wasActiveAndFocused = toolWindows.visible && toolWindows.activeId == id && focus == context
        toolWindows.toggle(id, focused = focus == context)
        if (wasActiveAndFocused) handFocusToEditor()
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

    /** Moves the caret of [editor] to [problem]'s start and hands the keyboard back to it. */
    private fun openEditorProblem(editor: TextFileEditor?, problem: FileProblem) {
        val target = editor ?: return
        if (problem.start >= 0) {
            target.jumpTo(problem.start)
        } else {
            target.gotoLine((problem.line + 1).toString())
        }
        handFocusToEditor()
    }

    /**
     * Moves the focus context to the selected text editor and asks it to take the keyboard, so
     * hiding the panel that owned focus does not leave both the context and the real focus gone.
     */
    private fun handFocusToEditor(): Boolean {
        if (session?.editors?.selected !is TextFileEditor) {
            focus = KeyContext.GLOBAL
            return false
        }
        focus = KeyContext.EDITOR
        editorFocusRequests++
        return true
    }

    /** Hands the keyboard to the selected text editor; `false` when something else is selected. */
    fun focusSelectedEditor(): Boolean = handFocusToEditor()

    /** Hides the active tool window and hands the keyboard back to the selected text editor. */
    fun hideActiveWindow() {
        toolWindows.hide()
        handFocusToEditor()
    }

    /** Asks the project tree to scroll to the selected file. */
    fun requestTreeReveal() {
        revealRequests++
    }

    /** Switches the UI font between Pretendard and the system default, remembering it. */
    fun toggleUiFont(): UiFontChoice = UiFont.toggle()

    fun openFolder(path: Path) {
        closeSession()
        // A new folder always starts on the global context: no modal focus from the previous
        // (possibly project-less) screen may outlive the switch.
        focus = KeyContext.GLOBAL
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
        findInPathReplace = false
        recentFilesVisible = false
        showUsages = null
        declarationChoices = emptyList()
        declarationChooserVisible = false
        navigationTargets = null
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
        refactorThis = null
        refactoringChoose = null
        refactoringName = null
        generate = null
        memberChoose = null
        pasteHistory = null
        quickDoc = null
        quickDefinition = null
        parameterInfo = null
        surround = null
        errorDescription = null
        diffRequest = null
        vcsPopup = null
        pushRequest = null
        updateRequest = null
        rollbackRequest = null
        branchesPopup = null
        changeMarkPopup = null
        statusMessage = null
        projectTreeVisible = true
        treeBeforeHide = null
        toolWindowBeforeHide = null
        findToolWindow.clear()
        problemsToolWindow.reset()
        hierarchyToolWindow.clear()
        hierarchyTree = null
        hierarchyGraph = null
        recentLocationsVisible = false
        recentLocationsCaret = null
        navigationBar = null
        toolWindows.hide()
        focus = KeyContext.GLOBAL
    }

    fun dispose() = closeSession()
}
