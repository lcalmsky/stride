# stride app public API

This document is the contract for `:app`: the Compose Desktop shell that wires `:core`,
`:lang`, `:search` and the two language plugins into an IDE window. Everything here lives in
`stride.app.*` and builds only on the APIs in `docs/api-core.md`, `docs/api-lang.md` and
`docs/api-search.md`.

## Descriptor

`app/src/main/resources/META-INF/stride-plugin.xml` is the `stride.core` descriptor. It has
no `<depends>` and declares the extension points that the other modules extend:

```xml
<stride-plugin>
  <id>stride.core</id>
  <name>stride core</name>
  <extensionPoints>
    <extensionPoint name="fileType" beanClass="stride.lang.FileTypeBean"/>
    <extensionPoint name="lang.syntaxHighlighterFactory" beanClass="stride.lang.SyntaxHighlighterFactoryBean"/>
    <extensionPoint name="lang.intentionProvider" beanClass="stride.lang.IntentionProviderBean"/>
    <extensionPoint qualifiedName="stride.searchEverywhereContributor"
                    interface="stride.search.SearchEverywhereContributorFactory"/>
    <extensionPoint name="fileEditorProvider" interface="stride.app.editor.FileEditorProvider"/>
    <extensionPoint name="treeStructureProvider" interface="stride.app.tree.TreeStructureProvider"/>
  </extensionPoints>
  <extensions defaultExtensionNs="stride">
    <searchEverywhereContributor implementation="stride.search.FileSearchContributorFactory"/>
    <fileEditorProvider implementation="stride.app.editor.TextEditorProvider"/>
    <treeStructureProvider implementation="stride.app.tree.HiddenDirectoriesTreeStructureProvider"/>
  </extensions>
</stride-plugin>
```

Because the id is `stride.core`, `name="x"` resolves to `stride.x`. The descriptor must not
declare `stride.applicationService` / `stride.projectService`; `Application.init()` registers
them in code and a second declaration makes `registerExtensionPoint` throw.

## Startup order

`main(args)`:

1. `Application.init()` — replaces the extension area and application manager, then loads every
   `META-INF/stride-plugin.xml` on the class path. The `LoadResult` is logged as
   `LoadResult enabled=[...]` and `LoadResult disabled={...}`.
2. `args.firstOrNull()` is the folder to open (`-PopenPath=<dir>` passes it; without the
   property there is no argument).
3. Opening a folder disposes the previous `Project` (if any), then
   `ProjectSession.open(path)`, which runs in this order:
   `Project(root)` → `project.service<VirtualFileManager>().start()` →
   `project.service<FileNameIndex>()` (starts the index scan) →
   `project.service<FileEditorManager>()` → `ProjectTreeModel(root, providers, project)`.

A `Project` is only ever created after `Application.init()`; one created earlier would keep a
reference to the replaced application manager.

The window title is `strIDE — <root folder name>`; with no project the content is an empty
state with an **Open Folder…** button. The folder chooser is `java.awt.FileDialog` with
`apple.awt.fileDialogForDirectories=true` on macOS and `JFileChooser(DIRECTORIES_ONLY)`
elsewhere.

## Editor extension points

```kotlin
package stride.app.editor

const val FILE_EDITOR_PROVIDER_EP: String = "stride.fileEditorProvider"

interface FileEditorProvider {
    fun accept(project: Project, file: VFile): Boolean
    fun createEditor(project: Project, file: VFile): FileEditor
    companion object { const val EP_NAME: String = FILE_EDITOR_PROVIDER_EP }
}

interface FileEditor : Disposable {
    val file: VFile
    val isModified: Boolean
    fun save()
    @Composable fun Content(modifier: Modifier)
}
```

`FileEditorProvider` is the interface extension point `stride.fileEditorProvider`. Providers
are consulted in extension-point order and the first one whose `accept` returns `true` creates
the editor. State that must survive a tab switch (text, selection, highlighter) lives in the
editor object, not in the composable.

`TextEditorProvider` accepts every non-directory file and creates a `TextFileEditor`. Files
larger than `TextFileEditor.MAX_HIGHLIGHTED_SIZE` (2 MB, 2 097 152 bytes) open read-only and
without a highlighter.

```kotlin
class TextFileEditor(file: VFile, readOnly: Boolean = false) : FileEditor {
    val isReadOnly: Boolean
    val value: TextFieldValue                 // text + selection, Compose snapshot state
    val spans: List<TokenSpan>                // current highlighter spans
    val isDisposed: Boolean
    var onFocusContext: (KeyContext) -> Unit   // reports an editor sub-field's focus context
    fun updateValue(newValue: TextFieldValue) // feeds only the changed range to the highlighter
    fun setText(newText: String)
}
```

`TextFileEditor` resolves the file type through `FileTypeRegistry`, the highlighter through
`SyntaxHighlighterFactory.forLanguage`, and wraps `LineStateHighlighter`. A text change is
reduced to one `TextChange(offset, oldLength, newText)` by common prefix/suffix and passed to
`LineStateHighlighter.edit`; plain-text files get no spans.

```kotlin
@Service(Service.Level.PROJECT)
class FileEditorManager(project: Project) : Disposable {
    val openFiles: List<FileEditor>           // Compose snapshot list
    var selected: FileEditor?                 // Compose snapshot state
    var onSelectionChanged: (FileEditor) -> Unit  // records the navigation history
    fun openFile(file: VFile): FileEditor?    // reuses an open tab and selects it
    fun closeFile(file: VFile)                // selects the right neighbour, else the left, else none
    fun closeOthers()                         // closes every tab except the selected one
    fun reopenClosed(): FileEditor?           // reopens the most recently closed tab
    fun selectNext()                          // cycles, does nothing with fewer than two tabs
    fun selectPrevious()
    fun saveSelected()
    fun closeSelected()
}
```

`FileEditorManager` is a project light service; `Project.dispose()` disposes every open
editor.

### Language commenters

`TextFileEditor` resolves a `Commenter` for the file's language to drive ⌘/ and ⌘⌥/:

```kotlin
package stride.lang

interface Commenter {
    val lineCommentPrefix: String?
    val blockCommentPrefix: String?
    val blockCommentSuffix: String?
    companion object {
        const val EP_NAME = "stride.lang.commenter"
        val EP: BeanExtensionPointName<CommenterBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): Commenter?
    }
}

class CommenterBean : ExtensionBean() {
    override val key: String?                 // attributes["language"]
    fun commenter(): Commenter
}
```

The app descriptor declares `<extensionPoint name="lang.commenter"
beanClass="stride.lang.CommenterBean"/>`; because its id is `stride.core` the point resolves to
`stride.lang.commenter`. The language plugins register one entry each:

```xml
<lang.commenter language="kotlin" implementationClass="stride.lang.kotlin.KotlinCommenter"/>
<lang.commenter language="json" implementationClass="stride.lang.json.JsonCommenter"/>
```

Both register `//`, `/*` and `*/`. A language without a commenter (plain text) resolves to
`null`; the editor then consumes the comment actions without changing the text.

### Import optimizers

`OPTIMIZE_IMPORTS` (⌃⌥O) asks the selected file's language for an import optimizer:

```kotlin
package stride.lang

interface ImportOptimizer {
    fun optimize(text: String): List<TextEdit>   // an empty list means nothing to change
    companion object {
        const val EP_NAME = "stride.lang.importOptimizer"
        val EP: BeanExtensionPointName<ImportOptimizerBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): ImportOptimizer?
    }
}

class ImportOptimizerBean : ExtensionBean() {
    override val key: String?                 // attributes["language"]
    fun optimizer(): ImportOptimizer
}
```

The app descriptor declares `<extensionPoint name="lang.importOptimizer"
beanClass="stride.lang.ImportOptimizerBean"/>`. Edits use the offsets of the original text and
must not overlap; the editor applies them as one undo step. The Kotlin plugin registers
`KotlinImportOptimizer`, which drops unused imports with the same rules as the ⌥↩ quick fix
(star imports and operator convention names kept), removes duplicates, and sorts the rest into
the default layout — everything else, `java.*`, `javax.*`, `kotlin.*`, then alias imports. A
block with a comment between its imports keeps its order and only loses whole lines; with no
import left the block goes, and the blank line after it goes too unless a comment stays in the
block. The status bar reports `Removed N imports` (`import` for one, duplicates included),
`Imports optimized`, `Imports are already optimized`, `Optimize Imports could not apply its
edits`, `Optimize Imports is not available for this file` or `File is read-only`.

`parseImports` reads only the package line, single-line `@file:` annotations and import lines,
so imports after a shebang line in a `.kts` script or after a multi-line `@file:` annotation are
never parsed; the action then reports `Imports are already optimized` even when unused imports
remain.

### Intentions and the ⌥↩ menu

⌥↩ (`SHOW_INTENTION_ACTIONS`) opens one popup with three groups in this order: quick fixes,
intentions and run entries. Languages contribute the first two through the `:lang`
extension point `stride.lang.intentionProvider` (contract in `docs/api-lang.md`); the run
group is the existing main, test, rerun and stop entries.

```kotlin
package stride.app.run

enum class ContextActionKind { QUICK_FIX, INTENTION, RUN_MAIN, RUN_TEST, RUN_TEST_CLASS, RERUN, STOP }
enum class ContextActionGroup(val title: String) { QUICK_FIX("Quick fixes"), INTENTION("Intentions"), RUN("Run") }
data class ContextAction(val kind: ContextActionKind, val label: String, val edits: List<TextEdit> = emptyList()) {
    val group: ContextActionGroup
}

object ContextActions {
    fun collect(text: String, fileName: String, caretOffset: Int, rerunName: String? = null,
                runningName: String? = null, intentions: List<IntentionAction> = emptyList()): List<ContextAction>
    fun intentionsAt(text: String, fileName: String, caretOffset: Int, symbols: SymbolLookup): List<IntentionAction>
}

fun openContextActions(state: AppState): Boolean
fun applyContextAction(state: AppState, action: ContextAction): Boolean   // edits, else run
```

`openContextActions` passes a `stride.app.intention.ProjectSymbolLookup` over the project's
`DeclarationIndex`: top-level declarations (`container == null`) with a package map their
simple name to `<package>.<name>`. It never waits for the index scan and rebuilds its map
only when `snapshot()` returns a new list. `applyContextAction` hands an action's edits to
`TextFileEditor.applyEdits(edits): Boolean`, which applies them as one undo step and maps
the caret through them; a read-only editor, an empty list, an edit out of range or two
overlapping edits change nothing and return `false`. Actions without edits run as before.

`ContextActionsPopup` draws a header row (`textOnBusyGlass`, `labelTextSize`) before each
group only when the actions span two or more groups, so a run-only menu looks as it did.
The hint reads `↵ Apply` when any entry is a quick fix or an intention, `↵ Run` otherwise.
`RunConfigurationPopup` (⌃⌥R) shares `ChoicePopup` and draws no headers. The first row is
selected, so with a quick fix or an intention present `Enter` applies it instead of running.
`ProjectSymbolLookup` skips declarations with `isPrivate`.

## Project tree

```kotlin
package stride.app.tree

const val TREE_STRUCTURE_PROVIDER_EP: String = "stride.treeStructureProvider"

interface TreeStructureProvider {
    fun modify(parent: VFile, children: List<VFile>): List<VFile>
    companion object { const val EP_NAME: String = TREE_STRUCTURE_PROVIDER_EP }
}

data class TreeRow(val file: VFile, val depth: Int, val isExpanded: Boolean)

class ProjectTreeModel(
    root: VFile,
    providers: List<TreeStructureProvider>,
    project: Project? = null,
    childrenLoader: (VFile) -> List<VFile> = { it.children() },
) : Disposable {
    fun rows(): List<TreeRow>          // Compose snapshot state, root first
    fun expand(file: VFile)
    fun collapse(file: VFile)
    fun toggle(file: VFile)
    fun reveal(file: VFile): Boolean   // expands the file's ancestors; false outside the root
    fun handleEvents(events: List<VFileEvent>)
    fun children(directory: VFile): List<VFile>
}
```

- `TreeStructureProvider` is the interface extension point `stride.treeStructureProvider`;
  providers run in extension-point order (`CompoundTreeStructureProvider` shape), each
  receiving the previous result.
- `HiddenDirectoriesTreeStructureProvider` removes a `.git` entry whether it is a directory
  or a file (worktrees and submodules use a gitdir pointer file), and removes directories
  named `build`, `.gradle`, `node_modules`, `.kotlin`, `.idea`.
- The model is lazy: a directory's children are read on first expansion and cached; `rows()`
  is the flattened visible list for a `LazyColumn`. The root is expanded on construction.
- With a `project`, the model subscribes to `VFS_CHANGES` on the project's message bus
  (registered against the project disposable). Each event drops the cached children of the
  event file's parent directory and rebuilds the rows; `handleEvents` is the same entry point
  without a bus.
- `childrenLoader` is injectable so tests can observe when a directory is read; production
  code uses `VFile.children()`.
- In the UI a directory row toggles on a single click and a file row opens on a single click.

## Search Everywhere

```kotlin
package stride.app.search

data class SearchResultEntry(
    val contributor: SearchEverywhereContributor<*>,
    val item: Any,
    val presentationText: String,
    val secondaryText: String,
)

class SearchEverywhere(project: Project) {
    val displayName: String                   // primary contributor's display name, "Files" when none
    fun fetch(query: String, limit: Int = MAX_RESULTS): List<SearchResultEntry>
    fun process(entry: SearchResultEntry): Boolean
    companion object { const val MAX_RESULTS: Int = 50 }
}
```

The popup itself is:

```kotlin
@Composable fun SearchEverywherePopup(
    search: SearchEverywhere,
    onChoose: (SearchResultEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
)
```

`initialQuery` seeds the query field and is `""` in the window; the snapshot renderer uses it.
An empty query shows no result rows, only the header and footer.

Contributors are created from every `stride.searchEverywhereContributor` factory with
`createContributor(project)` and ordered by `sortWeight` descending. The popup fetches off the
UI thread whenever the query changes (the previous run is cancelled by coroutine
cancellation), shows presentation and secondary text, moves with Up/Down, chooses with Enter
and closes on Esc or a click outside. Choosing calls `process(item)`; when it returns `false`
and the item is a `VFile`, the file is opened through `FileEditorManager` and the popup closes.
`⌘⇧O` (`GOTO_FILE`) opens this same popup through `state.searchVisible`.

## Keys

```kotlin
package stride.app.keys

enum class KeyEventType { DOWN, UP }

data class KeyStroke(
    val key: Key,
    val meta: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    companion object {
        fun parse(text: String): KeyStroke   // space-separated key stroke syntax
        fun of(event: KeyEvent): KeyStroke
    }
}

enum class ActionId {
    OPEN_FOLDER, SAVE, CLOSE_TAB, SEARCH_EVERYWHERE, NEXT_TAB, PREVIOUS_TAB, HIDE_ACTIVE_WINDOW,
    RUN, RUN_CONTEXT, STOP, RERUN, ACTIVATE_RUN_TOOL_WINDOW,
    ACTIVATE_BOOKMARKS_TOOL_WINDOW, ACTIVATE_FIND_TOOL_WINDOW, ACTIVATE_STRUCTURE_TOOL_WINDOW,
    SHOW_INTENTION_ACTIONS, CHOOSE_RUN_CONFIGURATION, BUILD_PROJECT,
    EDITOR_DUPLICATE, EDITOR_DELETE_LINE, COMMENT_LINE, COMMENT_BLOCK, MOVE_LINE_UP, MOVE_LINE_DOWN,
    GOTO_LINE, UNDO, REDO, INDENT, UNINDENT, EDITOR_ESCAPE,
    FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, FIND_IN_PATH, REPLACE_IN_PATH, GOTO_FILE, GOTO_CLASS, GOTO_SYMBOL,
    GOTO_ACTION, RECENT_FILES, ACTIVATE_TERMINAL_TOOL_WINDOW,
    EDITOR_SELECT_WORD, EDITOR_UNSELECT_WORD, EDITOR_TOGGLE_CASE, EDITOR_JOIN_LINES,
    EDITOR_START_NEW_LINE, EDITOR_START_NEW_LINE_BEFORE, EDITOR_MATCH_BRACE,
    EDITOR_CODE_BLOCK_START, EDITOR_CODE_BLOCK_END, METHOD_UP, METHOD_DOWN,
    HIPPIE_COMPLETION, CODE_COMPLETION, RENAME_ELEMENT, COPY_LINE, CUT_LINE,
    GOTO_DECLARATION, BACK, FORWARD, FILE_STRUCTURE_POPUP, GOTO_TEST, FIND_USAGES,
    TOGGLE_BOOKMARK, SHOW_BOOKMARKS, ACTIVATE_PROJECT_TOOL_WINDOW, HIDE_ALL_WINDOWS,
    COPY_PATHS, COPY_REFERENCE, SWITCHER, SELECT_IN_PROJECT_VIEW, REOPEN_CLOSED_TAB, CLOSE_OTHER_TABS,
    TOGGLE_UI_FONT, MOVE_STATEMENT_UP, MOVE_STATEMENT_DOWN, EDITOR_COMPLETE_STATEMENT,
    UNWRAP, REFORMAT_CODE, EDITOR_SCROLL_TO_CENTER,
    OPTIMIZE_IMPORTS, SMART_TYPE_COMPLETION, PASTE_MULTIPLE,
    PARAMETER_INFO, QUICK_DOC, QUICK_DEFINITION, SURROUND_WITH,
    SELECT_NEXT_OCCURRENCE, UNSELECT_PREVIOUS_OCCURRENCE, SELECT_ALL_OCCURRENCES,
    EDITOR_TOGGLE_COLUMN_MODE, EDITOR_CLONE_CARET_ABOVE, EDITOR_CLONE_CARET_BELOW,
    EXPAND_REGION, COLLAPSE_REGION, EXPAND_ALL_REGIONS, COLLAPSE_ALL_REGIONS,
    ACTIVATE_PROBLEMS_TOOL_WINDOW, GOTO_NEXT_ERROR, GOTO_PREVIOUS_ERROR, SHOW_ERROR_DESCRIPTION,
    JUMP_TO_LAST_CHANGE, SHOW_USAGES, HIGHLIGHT_USAGES_IN_FILE,
    RECENT_LOCATIONS, SHOW_NAV_BAR, EDIT_SOURCE,
    GOTO_IMPLEMENTATION, GOTO_TYPE_DECLARATION, GOTO_SUPER_METHOD, TYPE_HIERARCHY, CALL_HIERARCHY,
}

enum class KeyContext {
    GLOBAL, EDITOR, EDITOR_FIELD, FIND_BAR, SEARCH_POPUP, RUN_WINDOW, TERMINAL, CONTEXT_POPUP,
    LIST_WINDOW, NAV_BAR,
}

data class Binding(val action: ActionId, val context: KeyContext)

class Keymap(val bindings: Map<KeyStroke, List<Binding>>, val isMacOs: Boolean = true) {
    fun actionFor(stroke: KeyStroke, focus: KeyContext): ActionId?
    fun strokesFor(action: ActionId): List<KeyStroke>
    companion object { fun forPlatform(isMac: Boolean): Keymap }
}

object MacKeymap { val keymap: Keymap }

class KeyRouter(keymap: Keymap, clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    constructor(isMac: Boolean, clock: () -> Long = { System.nanoTime() / 1_000_000 })
    fun route(
        type: KeyEventType,
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        focus: KeyContext = KeyContext.GLOBAL,
    ): ActionId?
}

fun dispatchAction(action: ActionId, state: AppState, chooseFolder: () -> Path?): Boolean
fun handleWindowKeyEvent(event: KeyEvent, router: KeyRouter, state: AppState, chooseFolder: () -> Path?): Boolean
```

`KeyStroke.parse` reads a space-separated key stroke grammar: modifier tokens
(`meta`, `control`/`ctrl`, `alt`, `shift`) followed by one key token (`A`–`Z`, `0`–`9`, `TAB`,
`BACK_SPACE`, `DELETE`, `ESCAPE`, `ENTER`, `UP`/`DOWN`/`LEFT`/`RIGHT`, `HOME`, `END`, `SLASH`,
`SPACE`, `DIVIDE`, `MULTIPLY`, `OPEN_BRACKET`, `CLOSE_BRACKET`, `INSERT`, `F1`–`F12`, `NUMPAD0`–`NUMPAD9`).
Tokens are case-insensitive; an unknown token throws `IllegalArgumentException`.

`Keymap.actionFor` prefers a binding whose context equals `focus`, then a `GLOBAL` binding, then
`null`. `Keymap.forPlatform(false)` is the macOS table with `meta` and `ctrl` swapped on every
stroke. `SEARCH_EVERYWHERE` carries no stroke: `DoubleModifierDetector` produces it.
`REOPEN_CLOSED_TAB`, `CLOSE_OTHER_TABS` and `TOGGLE_UI_FONT` also carry no stroke; they are reached from the
Actions tab.

### macOS bindings

The `Keymap.kt` column gives the `bind(` line in `MacKeymap`.

| ActionId | Context | macOS strokes | Action | Keymap.kt |
|---|---|---|---|---|
| `GOTO_CLASS` | GLOBAL | ⌘O (opens a folder when no project is open) | `GotoClass` | :89 |
| `SAVE` | GLOBAL | ⌘S | `SaveAll` | :91 |
| `CLOSE_TAB` | GLOBAL | ⌘W | `CloseContent` | :93 |
| `NEXT_TAB` | GLOBAL | ⌘⇧] , ⌃→ | `NextTab` | :95, :96 |
| `PREVIOUS_TAB` | GLOBAL | ⌘⇧[ , ⌃← | `PreviousTab` | :98, :99 |
| `HIDE_ACTIVE_WINDOW` | RUN_WINDOW, TERMINAL, LIST_WINDOW | ⇧Esc | `HideActiveWindow` | :100 |
| `RUN` | GLOBAL | ⌃R | `Run` | :109 |
| `RUN_CONTEXT` | EDITOR | ⌃⇧R | `RunClass` | :111 |
| `STOP` | GLOBAL | ⌘F2 | `Stop` | :113 |
| `RERUN` | RUN_WINDOW | ⌘R | `Rerun` | :115 |
| `ACTIVATE_BOOKMARKS_TOOL_WINDOW` | GLOBAL | ⌘2 | `ActivateBookmarksToolWindow` | :117 |
| `ACTIVATE_FIND_TOOL_WINDOW` | GLOBAL | ⌘3 | `ActivateFindToolWindow` | :119 |
| `ACTIVATE_RUN_TOOL_WINDOW` | GLOBAL | ⌘4 | `ActivateRunToolWindow` | :121 |
| `ACTIVATE_PROBLEMS_TOOL_WINDOW` | GLOBAL | ⌘6 | `ActivateProblemsViewToolWindow` | :123 |
| `GOTO_NEXT_ERROR` | EDITOR | F2 | `GotoNextError` | :291 |
| `GOTO_PREVIOUS_ERROR` | EDITOR | ⇧F2 | `GotoPreviousError` | :293 |
| `SHOW_ERROR_DESCRIPTION` | EDITOR | ⌘F1 | `ShowErrorDescription` | :295 |
| `RECENT_LOCATIONS` | GLOBAL | ⌘⇧E | `RecentLocations` | :297 |
| `SHOW_NAV_BAR` | GLOBAL, LIST_WINDOW | ⌥Home (GLOBAL), ⌘↑ (LIST_WINDOW only; the editor keeps ⌘↑ for the document start) | `ShowNavBar` | :298, :299 |
| `EDIT_SOURCE` | GLOBAL, LIST_WINDOW | F4 (GLOBAL), ⌘↓ (LIST_WINDOW only; the editor keeps ⌘↓ for the document end) | `EditSource` | :303, :304 |
| `JUMP_TO_LAST_CHANGE` | GLOBAL | ⌘⇧⌫ | `JumpToLastChange` | :309 |
| `SHOW_USAGES` | EDITOR | ⌘⌥F7 | `ShowUsages` | :311 |
| `HIGHLIGHT_USAGES_IN_FILE` | EDITOR | ⌘⇧F7 | `HighlightUsagesInFile` | :313 |
| `GOTO_IMPLEMENTATION` | EDITOR | ⌘⌥B | `GotoImplementation` | :315 |
| `GOTO_TYPE_DECLARATION` | EDITOR | ⌘⇧B , ⌃⇧B | `GotoTypeDeclaration` | :317, :318 |
| `GOTO_SUPER_METHOD` | EDITOR | ⌘U | `GotoSuperMethod` | :320 |
| `TYPE_HIERARCHY` | EDITOR | ⌃H | `TypeHierarchy` | :322 |
| `CALL_HIERARCHY` | EDITOR | ⌃⌥H | `CallHierarchy` | :324 |
| `REFACTOR_THIS` | EDITOR | ⌃T | `Refactorings.QuickListPopupAction` | :326 |
| `INTRODUCE_VARIABLE` | EDITOR | ⌥⌘V | `IntroduceVariable` | :328 |
| `EXTRACT_METHOD` | EDITOR | ⌥⌘M | `ExtractMethod` | :330 |
| `INLINE` | EDITOR | ⌥⌘N | `Inline` | :332 |
| `ACTIVATE_STRUCTURE_TOOL_WINDOW` | GLOBAL | ⌘7 | `ActivateStructureToolWindow` | :125 |
| `EDITOR_DUPLICATE` | EDITOR | ⌘D | `EditorDuplicate` | :127 |
| `EDITOR_DELETE_LINE` | EDITOR | ⌘⌫ | `EditorDeleteLine` | :129 |
| `COMMENT_LINE` | EDITOR | ⌘/ , ⌘(numpad /) | `CommentByLineComment` | :131, :132 |
| `COMMENT_BLOCK` | EDITOR | ⌘⌥/ | `CommentByBlockComment` | :134 |
| `MOVE_LINE_UP` | EDITOR | ⌥⇧↑ | `MoveLineUp` | :136 |
| `MOVE_LINE_DOWN` | EDITOR | ⌥⇧↓ | `MoveLineDown` | :138 |
| `MOVE_STATEMENT_UP` | EDITOR | ⇧⌘↑ | `MoveStatementUp` | :140 |
| `MOVE_STATEMENT_DOWN` | EDITOR | ⇧⌘↓ | `MoveStatementDown` | :142 |
| `EDITOR_COMPLETE_STATEMENT` | EDITOR | ⇧⌘↩ | `EditorCompleteStatement` | :144 |
| `UNWRAP` | EDITOR | ⇧⌘⌦ | `Unwrap` | :146 |
| `REFORMAT_CODE` | EDITOR | ⌥⌘L | `ReformatCode` | :148 |
| `EDITOR_SCROLL_TO_CENTER` | EDITOR | ⌃L | `EditorScrollToCenter` | :150 |
| `GOTO_LINE` | EDITOR | ⌘L | `GotoLine` | :152 |
| `UNDO` | EDITOR | ⌘Z | `$Undo` | :154 |
| `REDO` | EDITOR | ⌘⇧Z | `$Redo` | :156 |
| `INDENT` | EDITOR | Tab | `EditorIndentSelection` | :158 |
| `UNINDENT` | EDITOR | ⇧Tab | `EditorUnindentSelection` | :160 |
| `EDITOR_ESCAPE` | EDITOR | Esc | `EditorEscape` | :162 |
| `FIND` | EDITOR | ⌘F | `Find` | :164 |
| `REPLACE` | EDITOR | ⌘R | `Replace` | :166 |
| `FIND_NEXT` | EDITOR, FIND_BAR | ⌘G | `FindNext` | :167 |
| `FIND_PREVIOUS` | EDITOR, FIND_BAR | ⌘⇧G | `FindPrevious` | :169 |
| `FIND_IN_PATH` | GLOBAL | ⌘⇧F | `FindInPath` | :172 |
| `REPLACE_IN_PATH` | GLOBAL | ⇧⌘R | `ReplaceInPath` | :174 |
| `GOTO_FILE` | GLOBAL | ⌘⇧O | `GotoFile` | :176 |
| `GOTO_SYMBOL` | GLOBAL | ⌘⌥O | `GotoSymbol` | :178 |
| `GOTO_ACTION` | GLOBAL | ⌘⇧A | `GotoAction` | :180 |
| `RECENT_FILES` | GLOBAL | ⌘E | `RecentFiles` | :182 |
| `ACTIVATE_TERMINAL_TOOL_WINDOW` | GLOBAL | ⌥F12 | `ActivateTerminalToolWindow` | :184 |
| `SHOW_INTENTION_ACTIONS` | EDITOR | ⌥↩ | `ShowIntentionActions` | :186 |
| `CHOOSE_RUN_CONFIGURATION` | GLOBAL | ⌃⌥R | `ChooseRunConfiguration` | :188 |
| `BUILD_PROJECT` | GLOBAL | ⌘F9 | `CompileDirty` | :190 |
| `EDITOR_SELECT_WORD` | EDITOR | ⌥↑ | `EditorSelectWord` | :192 |
| `EDITOR_UNSELECT_WORD` | EDITOR | ⌥↓ | `EditorUnSelectWord` | :194 |
| `EDITOR_TOGGLE_CASE` | EDITOR | ⌘⇧U | `EditorToggleCase` | :196 |
| `EDITOR_JOIN_LINES` | EDITOR | ⌃⇧J | `EditorJoinLines` | :198 |
| `EDITOR_START_NEW_LINE` | EDITOR | ⇧↩ | `EditorStartNewLine` | :200 |
| `EDITOR_START_NEW_LINE_BEFORE` | EDITOR | ⌘⌥↩ | `EditorStartNewLineBefore` | :202 |
| `EDITOR_MATCH_BRACE` | EDITOR | ⌃M | `EditorMatchBrace` | :204 |
| `EDITOR_CODE_BLOCK_START` | EDITOR | ⌘⌥[ | `EditorCodeBlockStart` | :206 |
| `EDITOR_CODE_BLOCK_END` | EDITOR | ⌘⌥] | `EditorCodeBlockEnd` | :208 |
| `METHOD_UP` | EDITOR | ⌃⇧↑ | `MethodUp` | :210 |
| `METHOD_DOWN` | EDITOR | ⌃⇧↓ | `MethodDown` | :212 |
| `HIPPIE_COMPLETION` | EDITOR | ⌥/ | `HippieCompletion` | :214 |
| `CODE_COMPLETION` | EDITOR | ⌃Space | `CodeCompletion` | :217, :218 |
| `SMART_TYPE_COMPLETION` | EDITOR | ⌃⇧Space | `SmartTypeCompletion` | :220 |
| `OPTIMIZE_IMPORTS` | EDITOR | ⌃⌥O | `OptimizeImports` | :222 |
| `PASTE_MULTIPLE` | EDITOR | ⇧⌘V | `PasteMultiple` | :224 |
| `PARAMETER_INFO` | EDITOR | ⌘P | `ParameterInfo` | :226 |
| `QUICK_DOC` | EDITOR | F1 , ⌃J | `QuickJavaDoc` | :228, :229 |
| `QUICK_DEFINITION` | EDITOR | ⌥Space | `QuickImplementations` | :231 |
| `SURROUND_WITH` | EDITOR | ⌥⌘T | `SurroundWith` | :233 |
| `RENAME_ELEMENT` | EDITOR | ⇧F6 | `RenameElement` | :235 |
| `COPY_LINE` | EDITOR | ⌘C | `$Copy` | :237 |
| `CUT_LINE` | EDITOR | ⌘X | `$Cut` | :238 |
| `GOTO_DECLARATION` | EDITOR | ⌘B | `GotoDeclaration` | :240 |
| `BACK` | GLOBAL | ⌘[ , ⌘⌥← | `Back` | :242, :243 |
| `FORWARD` | GLOBAL | ⌘] , ⌘⌥→ | `Forward` | :245, :246 |
| `FILE_STRUCTURE_POPUP` | EDITOR | ⌘F12 | `FileStructurePopup` | :248 |
| `GOTO_TEST` | GLOBAL | ⌘⇧T | `GotoTest` | :250 |
| `FIND_USAGES` | EDITOR | ⌥F7 | `FindUsages` | :252 |
| `TOGGLE_BOOKMARK` | EDITOR | F3 | `ToggleBookmark` | :254 |
| `SHOW_BOOKMARKS` | GLOBAL | ⌘F3 | `ShowBookmarks` | :256 |
| `ACTIVATE_PROJECT_TOOL_WINDOW` | GLOBAL | ⌘1 | `ActivateProjectToolWindow` | :258 |
| `HIDE_ALL_WINDOWS` | GLOBAL | ⌘⇧F12 | `HideAllWindows` | :260 |
| `COPY_PATHS` | GLOBAL | ⌘⇧C | `CopyPaths` | :262 |
| `COPY_REFERENCE` | GLOBAL | ⌘⌥⇧C | `CopyReference` | :264 |
| `SWITCHER` | GLOBAL | ⌃Tab , ⌃⇧Tab | `Switcher` | :266, :267 |
| `SELECT_IN_PROJECT_VIEW` | GLOBAL | ⌥F1 | `SelectInProjectView` | :269 |
| `SELECT_NEXT_OCCURRENCE` | EDITOR | ⌃G | `SelectNextOccurrence` | :271 |
| `UNSELECT_PREVIOUS_OCCURRENCE` | EDITOR | ⌃⇧G | `UnselectPreviousOccurrence` | :273 |
| `SELECT_ALL_OCCURRENCES` | EDITOR | ⌃⌘G | `SelectAllOccurrences` | :275 |
| `EDITOR_TOGGLE_COLUMN_MODE` | EDITOR | ⇧⌘8 | `EditorToggleColumnMode` | :277 |
| `EDITOR_CLONE_CARET_ABOVE` | EDITOR | ⌥⌥↑ (second ⌥ held) | `EditorCloneCaretAbove` | — |
| `EDITOR_CLONE_CARET_BELOW` | EDITOR | ⌥⌥↓ (second ⌥ held) | `EditorCloneCaretBelow` | — |
| `EXPAND_REGION` | EDITOR | ⌘(numpad +) , ⌘= | `ExpandRegion` | :279, :280 |
| `COLLAPSE_REGION` | EDITOR | ⌘(numpad -) , ⌘- | `CollapseRegion` | :282, :283 |
| `EXPAND_ALL_REGIONS` | EDITOR | ⇧⌘(numpad +) , ⇧⌘= | `ExpandAllRegions` | :285, :286 |
| `COLLAPSE_ALL_REGIONS` | EDITOR | ⇧⌘(numpad -) , ⇧⌘- | `CollapseAllRegions` | :288, :289 |
| `OPEN_FOLDER` | — | (none; Actions tab) | (none) | — |
| `SEARCH_EVERYWHERE` | GLOBAL | ⇧⇧ | `SearchEverywhere` | — |
| `REOPEN_CLOSED_TAB` | — | (none) | — | — |
| `TOGGLE_UI_FONT` | — | (none) | — | — |
| `CLOSE_OTHER_TABS` | — | (none) | — | — |

`KeyRouter` has no window dependency and is unit-tested. Modifier keys map to `ModifierKey`; a
repeated down of an already-pressed modifier is ignored. `DoubleModifierDetector` turns a double
Shift into `SEARCH_EVERYWHERE` (on the second release), and any other key in between cancels it.
Non-modifier downs call `otherKeyPressed()` and then `keymap.actionFor(stroke, focus)`.
`KeyRouter.isMacOs()` reads `os.name`; `KeyRouter(isMac, clock)` builds the platform keymap.

`KeyRouter` also recognises the clone-caret gesture the keymap cannot carry: while the second
Option press is held (`DoubleModifierDetector.isSecondPressHeld(ModifierKey.ALT)`), an ↑/↓ down
with Option alone in the editor returns `EDITOR_CLONE_CARET_ABOVE` / `EDITOR_CLONE_CARET_BELOW`
without clearing the wait, so holding the second Option and pressing the arrow again clones
again. Releasing Option, pressing any other key, or pressing another modifier ends the wait, and
a single Option press keeps its keymap action (`EDITOR_SELECT_WORD` / `EDITOR_UNSELECT_WORD`).
The Actions tab shows the gestures from `KeyRouter.CLONE_CARET_ABOVE_GESTURE` and
`CLONE_CARET_BELOW_GESTURE`.

`Main` wires `Window(onPreviewKeyEvent = ...)` to `handleWindowKeyEvent`, which reads
`state.focus`, routes the event and calls `dispatchAction`. `dispatchAction` returns `true` only
when the event is consumed; an action that needs an editor or a project returns `false` without
one, so the key reaches the focused component. The folder chooser is a parameter, so the handler
runs in headless tests.

`AppState.focus` is the reported focus context. `App` sets it to `EDITOR` from the box around the
editor area, to the active window's context (or `GLOBAL` when it loses focus) from the tool window
panel, and to `SEARCH_POPUP` from the box around the popup; a region resets it to `GLOBAL` only
while the current value is still its own context. Hiding the tree or a tool window sets `EDITOR`
and bumps `editorFocusRequests`, which `EditorArea` consumes by requesting focus on the selected
text editor, so removing the panel that owned focus does not leave the context and the real
keyboard focus apart.

## Editing actions

`stride.app.editor.EditActions` holds line-oriented pure functions over `(text, selection)`:

```kotlin
package stride.app.editor

data class EditResult(val text: String, val selection: TextRange)

object EditActions {
    fun duplicateLineOrSelection(text: String, selection: TextRange): EditResult
    fun deleteLines(text: String, selection: TextRange): EditResult
    fun moveLines(text: String, selection: TextRange, up: Boolean): EditResult
    fun toggleLineComment(text: String, selection: TextRange, prefix: String): EditResult
    fun toggleBlockComment(text: String, selection: TextRange, prefix: String, suffix: String): EditResult
    fun indent(text: String, selection: TextRange, unit: String = "    "): EditResult
    fun unindent(text: String, selection: TextRange, unit: String = "    "): EditResult
    fun gotoLine(text: String, line: Int, column: Int = 1): TextRange
    fun wordAt(text: String, offset: Int): TextRange?
    fun identifierPrefix(text: String, offset: Int): IntRange
    fun identifierRanges(text: String): List<IntRange>
    fun selectWord(text: String, selection: TextRange): TextRange?
    fun toggleCase(text: String, selection: TextRange): EditResult
    fun joinLines(text: String, selection: TextRange): EditResult
    fun startNewLine(text: String, selection: TextRange, before: Boolean): EditResult
    fun moveStatement(text: String, selection: TextRange, up: Boolean): EditResult
    fun completeStatement(
        text: String,
        selection: TextRange,
        semicolons: Boolean,
        unit: String = "    ",
    ): EditResult
    fun unwrap(text: String, selection: TextRange): EditResult?
    fun reformat(
        text: String,
        selection: TextRange,
        braceLanguage: Boolean,
        unit: String = "    ",
    ): List<TextEdit>
    fun matchBrace(text: String, caret: Int): TextRange?
    fun codeBlockStart(text: String, caret: Int): TextRange?
    fun codeBlockEnd(text: String, caret: Int): TextRange?
    fun wholeLine(text: String, caret: Int): TextRange
    fun declarationTarget(text: String, caret: Int, declarationLines: List<Int>, up: Boolean): TextRange?
    fun wholeWordRanges(text: String, word: String): List<IntRange>
    fun hippieCandidates(
        text: String,
        prefix: String,
        prefixStart: Int,
        caret: Int,
        otherTexts: List<String> = emptyList(),
    ): List<String>
}
```

Line actions apply to every line the selection touches. Duplicate puts the
line (or the selection) below and moves the caret into the copy; delete removes the touched lines
with their newlines (the preceding newline for a last line without one); move swaps the touched
block with the neighbouring line and returns the input unchanged at the first line (up) or the
last line (down); line comments insert `prefix + " "` at the smallest indentation column and
remove it again when every non-blank touched line is already commented (blank lines are left
alone); block comments wrap the selection or strip an outer `prefix…suffix`; indent inserts the
unit and widens the selection to the touched lines; unindent removes up to `unit.length` leading
whitespace; `gotoLine` is 1-based and clamps to the nearest line end. Actions that insert or join
lines use the document's own separator, so a CRLF file keeps its CRLF endings.

The selection and caret actions are pure as well. `selectWord` returns the next larger selection
around the input — word, string-literal contents, bracket contents, the brackets themselves, the
touched lines, then the whole text — or `null` at the top; `wordAt` is the identifier touching an
offset (including one ending exactly there), and `identifierPrefix` is the empty-or-filled range
of identifier characters just before it. `toggleCase` upper-cases the selection or caret word when
it holds a lower-case letter and lower-cases it otherwise. `joinLines` merges the next line (also
when the selection touches only one line), or every line the selection touches, into the first:
the joined line loses its indentation, one space separates the parts unless it starts with `)`,
`]`, `.` or `,`, and the caret lands on the first join point. `startNewLine` inserts a line with
the current line's indentation after (or before) it and puts the caret there. `matchBrace`,
`codeBlockStart` and `codeBlockEnd` skip
characters inside comments and string literals; the two block functions return `null` outside any
block. `wholeLine` includes the trailing newline when there is one. `declarationTarget` picks the
previous or next line in a declaration-line list, which `DeclarationScan.lines(fileName, text)`
produces from the search module's `DeclarationParser`. `wholeWordRanges` finds occurrences that
are not part of a larger identifier. `hippieCandidates` orders words that start with the prefix by
distance from the caret — preceding occurrences first, then following ones, then the words of
`otherTexts` — and drops duplicates.

`moveStatement` swaps the statement unit at the caret — or the selected lines — with its sibling
above or below. The unit grows through the `}` of a block its first or last line opens, and
comment (`//`, `/*`, `*`) and annotation (`@`) lines glued above it travel with it. The neighbour
is the line above or below: a blank line is a one-line neighbour, the header of an enclosing
block and the closing line of one are not crossed, and a `}` line brings the block above it. A
unit whose first line starts with the `}` of a block opened above it is not moved. Lines are
never re-indented, and the selection moves with the unit; a selection that ends at the start of
the line below the unit keeps covering just the unit.

`completeStatement` finishes the caret's line without parsing the file. It closes the `(` and `[`
left open on the line at the end of its code (before trailing spaces and a comment), opens a
block when the line starts with a block keyword and does not end with `{`, closes the braces when
the block already has them, appends `;` when `semicolons` is set (`.java` files), and puts the
caret on a new line with the current indentation. A `fun` line with a top-level `=` keeps its
expression body, also when its return type closes with `>` (`fun f(): Map<String, Int> = mapOf()`).
A line that ends with `{` leaves its open brackets alone and puts the new content after a
trailing comment, so `foo({` gains no `)`. Brackets and comments are judged through `CodeScan`,
so brackets inside strings and comments are ignored.

`unwrap` removes the innermost `{…}` block around the caret whose header before the `{` starts
with `if`, `else`, `for`, `while`, `do`, `try`, `catch` or `finally`. Chained `else`/`else if`,
`catch`/`finally` blocks and a `do`'s trailing `while` are removed with it, and only the target
block's body remains, dedented one unit. A one-line block becomes one line; an empty body removes
the chain without leaving a blank line, with the caret where the chain was; `null` means there
was no candidate. Text after the chain's closing `}`, `while (…)` or `;` on the same line — a
comment or another statement — stays where it is, as does code in front of a header that does
not start its line. A header such as `val x = if (…) {` is not a candidate because the line does
not start with the keyword.

`reformat` returns the `TextEdit`s that re-indent the selected lines (or the whole file when the
selection is empty) and trim trailing whitespace; the editor applies them as one undo step.
Brace languages (`.kt`, `.kts`, `.java`, `.json`) get their indentation recomputed from a bracket
stack that records the level of the line that opened each bracket, with a one-unit extra indent
for a line that continues with `.`, `?.`, `?:`, `&&` or `||` and star alignment inside block
comments; other files only lose trailing whitespace. A line that starts or ends inside a string
literal is left exactly as it is, blank lines lose their indentation, and a whole-file pass
reduces a run of three or more blank code lines to two. Line breaks, intra-line spacing and
bracket alignment are not changed, so a style that aligns parameters under the opening paren
becomes one indent unit.

`EDITOR_SCROLL_TO_CENTER` asks the editor content to put the caret's line in the middle of the
visible viewport without moving the caret or the selection. The content reads the same
`TextLayoutResult` and viewport height as the caret-following effect: it takes the line's centre
including the top padding (`topPad + (lineTop + lineBottom) / 2`), subtracts `topPad` and half
the viewport height, and calls `EditorScrollState.scrollTo` with the result clamped to the scroll
range.

`CodeCompletion.candidates(prefix, names, limit)` ranks names with `NameMatcher`;
`CodeCompletion.identifiers(text, excluded)` lists a file's identifiers (leaving out the range
that is exactly the one being completed) and `keywordsFor(fileName)` the Kotlin or Java keywords.

`SMART_TYPE_COMPLETION` (⌃⇧Space) opens the same completion popup under the `Smart completion`
header. `SmartCompletion.positionAt` judges the position from the code before the identifier
prefix, skipping whitespace and strings and comments through `CodeScan`: a type after `:`
(not `::` or `?:`), `as`, `as?`, `is`, a `<` that directly follows an identifier character or
`>`, or a `,` inside still-open angle brackets, an expression anywhere else. A `<` after
whitespace is read as a comparison, and a `->` is not recognized at all, so the position after a
function type is treated as an expression. A type position offers the current and other open
files' upper-case identifiers and the declaration index's `CLASS` names; an expression position
offers the current file's identifiers and the `SYMBOL` names. Neither adds language keywords,
and the identifier being completed stays excluded as in the plain popup.

`PASTE_MULTIPLE` (⇧⌘V) opens the Paste from History popup over `AppState.clipboardHistory`:
newest first, at most 20 entries, a repeated text moves to the front, and blank texts are
ignored. Opening records the system clipboard's current text through `TextClipboard.readText`.
Enter replaces the selection captured when the popup opened with the chosen entry in one undo
step, writes it back to the system clipboard and moves it to the front. `COPY_LINE` and
`CUT_LINE` record the text they copy — the selection when there is one, the whole line
otherwise. An empty history reports `Clipboard history is empty` without opening the popup.

`PARAMETER_INFO` (⌘P) draws a non-modal panel over the editor. `CallText.openParen` walks back
over code, judged through `CodeScan`, to the innermost unclosed `(`, whose partner may be after
the caret or absent; `calleeName` takes the identifier before it, skipping whitespace and a
`<…>` generic argument list. The declaration lookup below resolves that name to signatures, and
each panel line joins one overload's parameters — split at the top-level commas of the first
balanced `(…)` in the declaration's signature — with `, ` in the code font. The parameter the
caret is in (the count of top-level commas between `(` and the caret) is bold in
`DesignTokens.accentOnPopupPlate`; an overload with no such parameter is dimmed, and a signature
with no parameters shows `<no parameters>`. Up to five overloads are shown. The panel never
takes the keyboard focus: it recomputes from the editor's live text and caret on every
recomposition and closes when the caret moves before `(` or past the matching `)`, the selected
editor changes, another popup opens, `AppState.focus` leaves `EDITOR` (the go-to-line field, a
tool window or the tree), or Escape is pressed; it does not reopen by itself when the editor
regains the focus. `dispatchAction` handles `EDITOR_ESCAPE` ahead of the editor's own Escape
handling so the panel closes first. `No call at
caret` means no unclosed call precedes the caret; `No parameter info for <name>` means the
declarations were found but none carries a parameter list.

`QUICK_DOC` (F1, ⌃J) and `QUICK_DEFINITION` (⌥Space) share `DeclarationPeek.lookup`: the current
file first — the declaration of the name nearest before the caret, or every declaration with
that name when there is none before it — then the declaration index, ranked by package and
module, with other files' private declarations removed and one entry per file and line. The
declaration's source is read from its open `TextFileEditor` or else from the file; a file that
fails to read is dropped from the list. Before the index is ready only the current file's
declarations are used, and an empty list reports the usual `INDEXING_MESSAGE`; after that an
empty list reports `No declaration found`. `DeclarationSource` extracts three pieces without a
parser: the signature runs from the declaration's line while its brackets are unbalanced, stops
before the first code `{` or a top-level `=`, leaves out the parts `CodeScan` marks as comments
(string-literal contents stay), and joins at most five lines with single spaces;
the documentation is a KDoc block or a run of `//` lines directly above the declaration — with
annotation lines skipped and a blank line breaking the connection — with the markers and the
leading `*` removed; the definition runs from the documentation's first line (else the first
annotation, else the declaration) through the matching `}` of the block the signature opens
(else to the signature's end), keeps the first 40 lines, and loses the common indentation.

Quick Documentation shows at most three candidates, each with its signature, `relativePath:line`
and documentation (or `No documentation`), scrolling vertically inside a capped height. Enter
opens the first candidate and the popup closes; Escape, F1 or ⌃J close it. Quick Definition shows
one candidate's definition in the code font with `relativePath:line` and `1/<n>` in the header,
`… <n> more lines` when the definition was cut, and ←/→ switching candidates; Enter or ⌘↓ opens
the current one. Both open their declaration through `openDeclaration`, so back and forward move
between the peek target and the place it was invoked from. Because these two popups move to
another location, they darken the editor with `scrimSurface` like the navigation popups;
Surround With, Paste from History, completion and rename edit the caret's own place, so they
draw no scrim.

`SURROUND_WITH` (⌥⌘T) wraps the lines the selection covers — the caret line when it is
collapsed — in a template of the file type. Kotlin (`.kt`, `.kts`) offers `if`, `if / else`,
`while`, `do / while`, `for`, `try / catch`, `try / finally`, `try / catch / finally` and
`run { }`; Java (`.java`) offers the same block set with the Java `catch (Exception e)` clause
plus `synchronized` and a plain `{ }`. The header takes the first target line's indentation and
the body gains one four-space unit (blank lines are not indented); `run { }` and `{ }` leave the
body as it is and map the caret by the inserted header. `if`, `while`, `for` and `synchronized`
put the caret in the condition parentheses, `do / while` in the trailing `while (…)`, the try
templates select `Exception`, and `try / finally` leaves the caret on the empty finally line.
The edit is one undo step through `TextFileEditor.applyResult`. The popup applies the highlighted
template with Enter or with its shortcut key (`1`…`9`, `0`, then letters), ↑↓ move and Escape
closes. An expression-level wrap (parentheses, string literals) is not offered: the unit is
always whole lines. A file type with no table reports `No surround templates for this file`, and
a read-only editor `File is read-only`.

`stride.app.editor.UndoManager` keeps the undo and redo stacks:

```kotlin
data class UndoEntry(
    val offset: Int,
    val oldText: String,
    val newText: String,
    val selectionBefore: TextRange,
    val selectionAfter: TextRange,
    val secondaryBefore: List<TextRange> = emptyList(),
    val secondaryAfter: List<TextRange> = emptyList(),
    val typing: Boolean = false,
)

class UndoManager(clock: () -> Long = System::currentTimeMillis, limit: Int = 1_000) {
    val canUndo: Boolean
    val canRedo: Boolean
    fun record(entry: UndoEntry)   // clears the redo stack
    fun undo(): UndoEntry?
    fun redo(): UndoEntry?
    fun breakMerge()
    fun clear()
}
```

`record` merges a new entry into the previous one only when both are pure insertions, the new
insertion continues the previous one (`n.offset == p.offset + p.newText.length`), its text is
exactly one non-whitespace character, and no more than 500 ms have passed. Two `typing` entries
with non-empty secondary sets merge under the caret-identity rules described under
[Multiple carets](#multiple-carets). `breakMerge` makes the next `record` start a new entry; the
editor calls it before and after every edit action so a whole action is one undo step.

`TextFileEditor` records every text change through `updateValue` and applies undo/redo through the
same path, so the highlighter stays in step with the text:

```kotlin
class TextFileEditor(
    file: VFile,
    readOnly: Boolean = false,
    clipboard: TextClipboard = SystemTextClipboard,
) : FileEditor {
    val undo: UndoManager
    val commenter: Commenter?
    var gotoLineVisible: Boolean
    val secondaryCarets: List<TextRange>       // oldest first
    val carets: List<TextRange>                // secondaryCarets + value.selection
    val columnMode: Boolean
    fun updateValue(newValue: TextFieldValue)   // program path; records the change, drops the secondary carets
    fun undo(): Boolean
    fun redo(): Boolean
    fun gotoLine(input: String): Boolean        // "12" or "12:5"; false for non-numeric input
    fun applyAction(action: ActionId): Boolean
    fun hippieComplete(otherTexts: List<String> = emptyList()): Boolean
    fun renameWord(original: String, replacement: String): Int
    fun unwrap(): Boolean                     // false when no control block encloses the caret
    fun reformat(): String                    // the status line to show
    fun replaceRange(start: Int, end: Int, replacement: String)
    fun applyResult(result: EditResult): Boolean  // one undo step; false when read-only
    fun removeSecondaryCarets(): Boolean
    internal fun moveCarets(move: CaretMove, extend: Boolean): Boolean   // false with only the primary caret
    fun deleteAtCarets(forward: Boolean, word: Boolean): Boolean
    fun pasteAtCarets(clip: String): Boolean
    fun extendBlock(dLine: Int, dCol: Int): Boolean            // column mode only
    fun selectBlock(anchorLine: Int, anchorCol: Int, leadLine: Int, leadCol: Int)
    fun toggleCaretAt(offset: Int)
    fun selectNextOccurrence(): Boolean
    fun selectAllOccurrences(): Boolean
    fun unselectPreviousOccurrence()
    fun cloneCaret(above: Boolean): Boolean                     // false when nothing was added
    fun toggleColumnMode(): Boolean                            // returns the new state
}
```

`applyAction` handles `EDITOR_DUPLICATE`, `EDITOR_DELETE_LINE`, `MOVE_LINE_UP`,
`MOVE_LINE_DOWN`, `INDENT`, `UNINDENT`, `COMMENT_LINE`, `COMMENT_BLOCK`, `UNDO`, `REDO`,
`GOTO_LINE`, `EDITOR_ESCAPE`, `EDITOR_SELECT_WORD`, `EDITOR_UNSELECT_WORD`,
`EDITOR_TOGGLE_CASE`, `EDITOR_JOIN_LINES`, `EDITOR_START_NEW_LINE`,
`EDITOR_START_NEW_LINE_BEFORE`, `EDITOR_MATCH_BRACE`, `EDITOR_CODE_BLOCK_START`,
`EDITOR_CODE_BLOCK_END`, `METHOD_UP`, `METHOD_DOWN`, `COPY_LINE`, `CUT_LINE`,
`MOVE_STATEMENT_UP`, `MOVE_STATEMENT_DOWN`, `EDITOR_COMPLETE_STATEMENT`, `UNWRAP`,
`REFORMAT_CODE`, `EDITOR_SCROLL_TO_CENTER`, `HIPPIE_COMPLETION` (without other editors' text),
`SELECT_NEXT_OCCURRENCE`, `UNSELECT_PREVIOUS_OCCURRENCE`, `SELECT_ALL_OCCURRENCES`,
`EDITOR_TOGGLE_COLUMN_MODE`, `EDITOR_CLONE_CARET_ABOVE` and `EDITOR_CLONE_CARET_BELOW`.
A read-only
editor consumes the edit actions without changing the text, and a file whose language has no
commenter consumes the comment actions. Everything else returns `false`.

`EDITOR_SELECT_WORD` keeps a stack of previous selections (cleared whenever the text or the
selection changes by another route) and `EDITOR_UNSELECT_WORD` pops it. `COPY_LINE` writes the
caret line, newline included, through `TextClipboard`; `CUT_LINE` also deletes it. Both return
`false` when a real selection exists or the clipboard refuses the write, so `BasicTextField`
keeps its own copy and cut for selections. With secondary carets both take the multiple-caret
path instead: the selections are joined, or the whole lines of every caret are copied, and a cut
deletes those ranges in one undo step. `hippieComplete` replaces the prefix before the caret
with the next candidate of `hippieCandidates` and restarts the cycle after any other edit.
`renameWord` replaces every whole-word occurrence through one `updateValue`, so one undo step
restores it, and returns the count. `replaceRange` is the popup's insert path.

`GOTO_LINE` sets `gotoLineVisible`, which draws a small `raised` panel in the editor's top-right
corner with a `Go to line:` label and a `line[:column]` field. Enter calls `gotoLine(input)` and
closes the panel on success; Esc closes it. While the field has focus it reports the
`EDITOR_FIELD` context through `TextFileEditor.onFocusContext`, so `EDITOR` bindings do not act
on the buffer while typing there; closing the panel restores `EDITOR`.

`dispatchAction` sends `UNDO`/`REDO` to the selected `TextFileEditor` and always returns `true`
(with no selected editor it does nothing): `BasicTextField` maps ⌘Z/⌘⇧Z to its own undo and
redo (Foundation `KeyMapping.kt:71,82`), and consuming them here keeps the two stacks from
diverging. The same table entry carries the other editor actions and returns their
`applyAction` result, which is `false` when no editor is selected. `UNWRAP` and `REFORMAT_CODE`
call `TextFileEditor.unwrap()` and `TextFileEditor.reformat()` and copy their result into the
status bar (`Nothing to unwrap`, `Reformatted N lines`, `Nothing to reformat`, `File is
read-only`). `HIPPIE_COMPLETION` also hands the other open editors' text to the editor.

### Multiple carets

`TextFileEditor` keeps the field's own selection as the primary caret and holds the rest in
`secondaryCarets`; `internal object MultiCaret` is the pure engine over a text and its carets.

**Model.** `value.selection` is the primary caret — the most recently added one.
`secondaryCarets` holds the others oldest first, and `carets` is
`secondaryCarets + value.selection`, so its last entry is the primary. `TextRange` direction is
kept: `start` is the anchor and `end` the caret position. After every operation
`MultiCaret.normalize` orders the carets and folds two carets into one when their ranges overlap
with positive length, their `end`s are equal, or a collapsed caret lies strictly inside another
caret's selection; the survivor keeps the merged union range and its own direction, and the most
recently added member is the survivor, so a folded primary stays primary. `columnMode` is a
per-editor flag that is not persisted.

Two alternatives were rejected. A text component owned by the editor, with every caret equal,
would have to rebuild mouse selection and drag, double-click, IME composition, clipboard and
scroll following, and would rewrite the existing tests and the frame-time path. A caret list as
the single source of truth with `TextFieldValue` derived from it would behave the same on the
outside while changing every reader of `value.selection`. Keeping the field in charge of the
primary caret leaves its input, IME and mouse paths untouched.

**Field changes.** The field still calls `onFieldValueChange`. With no secondary caret it behaves
like `updateValue`. With secondary carets, a change that only moves the selection drops them (and
forgets the caret step), a composition-only change keeps them, and a text change goes through
`MultiCaret.replicate`: it derives the changed region from the primary selection and the previous
composition, replays it at the same relative offsets at every secondary caret, carries the new
composition range across, and sets `typing` when the region is one non-whitespace character
inserted into collapsed carets with no composition. A change that cannot be modelled drops the
secondary carets and applies the single value. A press without Command, Control or Option (Shift
is allowed) is left to the field's own click path, so it drops the secondary carets without
consuming the event — clicking the primary caret's own position clears them too. A program path
that sets the value (`updateValue`, `replaceRange`, `renameWord`, and the move helpers) drops
them as well, and undo restores them from the entry.

**Keys.** `CaretKeys.commandFor` runs from the field's `onPreviewKeyEvent` only while secondary
carets exist or column mode is on; the window keymap runs first, so only strokes it does not
carry arrive. Control arrows, ⌘↑/⌘↓, ⌘⌥←/→, `Home`/`End`/`MoveHome`/`MoveEnd` and
`PageUp`/`PageDown` stay with the field, which then drops the secondary carets through the
selection path.

| key | effect |
|---|---|
| ← / → / ↑ / ↓ | move every caret; with a selection, ← and → fold to its min and max first, and ↑↓ move by line and clear the selection |
| ⇧ + arrows | extend every selection from its anchor; in column mode the arrows extend the block |
| ⌥ + ← / → | word-wise move or extension |
| ⌘ + ← / → | line start (column 0) / line end (before `\n`, and before `\r` in CRLF) |
| ⌫ / ⌦ (with ⌥: word) | delete the selection or one code point at every caret |
| ⌘V | paste at every caret |

Vertical movement uses the character column (a tab or a wide glyph is one column); consecutive
↑↓ presses remember each caret's desired column, and a caret on the first or last line does not
move past it.

**Copy, cut and paste.** ⌘C/⌘X are `COPY_LINE`/`CUT_LINE`. With secondary carets the editor
handles them itself: if any caret has a selection, the selected texts are joined in document
order with `\n`; otherwise the whole lines of every caret are copied in document order, each line
once, newline included. A cut deletes those ranges in one undo step. The dispatcher also records
the copied text in the clipboard history. ⌘V splits the clip by line breaks (a trailing newline
is dropped) and, when the number of lines equals a caret count of two or more, gives the i-th
line in document order to the i-th caret; otherwise every caret receives the whole clip. Each
caret's selection is replaced and the caret is left collapsed at the end of its inserted text.

**Column selection.** `MultiCaret.block` turns an anchor and a lead (line, column) into one caret
per line between them, `[lineStart + min(column), lineStart + max(column))`, with the caret at
the lead column's side. There is no virtual space: a short line ends with a collapsed caret on
its line end. In column mode ⇧←/⇧→ move the lead column by ∓1 (never below 0, no upper bound),
⇧↑/⇧↓ move the lead line and clamp to the document, and the first Shift arrow starts the block
from the primary caret. ⌥-drag and a plain drag in column mode build the same block from the
press and the dragged position; right of a line the column continues as
`lineLength + round((x - lineRight) / glyphWidth)`. Any other caret operation ends the block.

**Action scope.** The line-oriented actions run through `MultiCaret.perCaret` when secondary
carets exist; every other action takes the single-selection path, which drops the secondary
carets and records them in the undo entry.

| scope | actions |
|---|---|
| every caret | typing, Enter, IME composition, ⌫/⌦ (with ⌥), arrows with their ⌥, ⌘, ⇧ forms, ⌘C ⌘X ⌘V, undo/redo, Esc, the six caret actions below |
| every caret through `perCaret` | `COMMENT_LINE`, `EDITOR_DUPLICATE`, `EDITOR_DELETE_LINE`, `UNINDENT`, `INDENT` (line-based only when a caret has a selection), `EDITOR_TOGGLE_CASE` |
| primary caret only | everything else (line moves, statement moves, join, start new line, complete statement, unwrap, reformat, surround, block comment, ⌥↑/⌥↓, declaration moves, completion, rename, ⌥↩, paste history, find and replace, go to line) |
| view only, every caret kept | `EDITOR_SCROLL_TO_CENTER` and the four folding actions (see Code folding): they never set the value through the single-selection path; the primary caret picks the fold, and a caret the new fold would hide moves to its start |

`perCaret` applies the function bottom-up; for a line-based action it calls it once per group of
carets whose touched lines overlap, through the group's latest-added caret, and maps the other
carets past the edit. A group whose edit region overlaps the previously applied one is skipped.
The whole run is one undo step, and a read-only editor consumes the action without changing the
text.

**Caret actions.** Each new `ActionId` returns `false` when no editor is selected, which the
dispatcher leaves unconsumed.

| action | behaviour |
|---|---|
| `SELECT_NEXT_OCCURRENCE` (⌃G) | with no selection on any caret, selects the word at every caret and turns on whole-word matching; otherwise finds the primary caret's selected text (or the latest selected caret's when the primary is collapsed) after the primary's end, wraps around the document, skips matches that overlap a caret, is case-sensitive and applies the whole-word test when the flag is on, and adds the match as the new primary. With no match the status bar shows `No more occurrences`; a success clears it |
| `UNSELECT_PREVIOUS_OCCURRENCE` (⌃⇧G) | with two or more carets, drops the primary and promotes the previous one; with one caret it only collapses the selection to `end` |
| `SELECT_ALL_OCCURRENCES` (⌃⌘G) | a collapsed primary selects its word with whole-word matching; a selection uses its text (whole-word only when the flag is on). Every match in the file becomes a caret, in document order, with the match containing or touching the primary as the primary; a collapsed primary at either end of its word accepts that boundary |
| `EDITOR_TOGGLE_COLUMN_MODE` (⇧⌘8) | toggles `columnMode` and writes `Column selection mode on` / `Column selection mode off` |
| `EDITOR_CLONE_CARET_ABOVE` / `_BELOW` (⌥⌥↑ / ⌥⌥↓) | adds a collapsed caret in the same column on the line above the topmost (below the bottommost) caret and makes it primary. Consecutive clones keep the first clone's column; the opposite direction removes the last clone. A missing line ends the action |

`EDITOR_ESCAPE` closes the Go to Line popup, then drops the secondary carets (keeping the
primary selection), then collapses a lone selection. Column mode is not turned off by Esc.

**Undo.** `UndoEntry` carries `secondaryBefore`, `secondaryAfter` and `typing`; undo and redo
restore the text with both caret sets. An entry with no secondary carets restores an empty set.
Besides the single-caret insertion merge, a new entry merges into the previous one when both are
`typing`, both have non-empty secondary sets before and after, the new `selectionBefore` equals
the previous `selectionAfter`, the new `secondaryBefore` equals the previous `secondaryAfter`,
and the new old region `[offset, offset + oldText.length)` lies inside the previous new region,
all within `MERGE_MILLIS` (500 ms). The merged entry keeps the previous offset and old text and
splices the new text into the previous new text, while `selectionAfter` and `secondaryAfter`
come from the new entry. A multi-caret edit is recorded as one `computeTextChange` replacement
from the first to the last caret.

**Drawing.** The field's modifier chain grows by a `drawWithContent` only when secondary carets
exist, so the single-caret path keeps its chain and frame cost. The draw fills each selection with
`layout.getPathForRange` in `DesignTokens.selection` before `drawContent()` and paints a 2 dp
`DesignTokens.accent2` bar at each caret's `getCursorRect` after it — the same colour as the
primary cursor brush, and never blinking. Only carets that touch the visible lines are computed:
the window is derived from the scroll offset and viewport height exactly as for the background
canvas, selections are clipped to `lineStart(first)`..`lineEnd(last)` with the line indices
clamped to the layout, and a bar is drawn only when the caret's end lies on a visible line. The
decision is `visibleCaretRanges`, a pure function unit-tested without a layout. The background
canvas and the gutter paint `DesignTokens.currentLineGlow` and the 2 dp `DesignTokens.accent2Glow`
for the secondary caret lines as well, visible lines only, using the per-text line-start array and
binary search; only the primary caret line gets the accent line number.

Scroll following stays on the primary caret; secondary carets are not scrolled into view.

A read-only editor still adds, moves, selects and copies carets, but every text-changing caret
operation leaves the text alone and consumes the action.

Known differences: no virtual space, columns count characters (a tab or a wide glyph is one
column), secondary carets do not blink, and ⌥⇧⌘-drag, middle-button drag, ⇧⌥G (Add Carets to
Ends of Selected Lines) and ⌃G/⌃⌘G inside the find bar are out of scope.

### Completion and rename popups

`CODE_COMPLETION` calls `openCodeCompletion(state)`: it reads the caret's identifier prefix and
stores a `CompletionRequest` in `AppState.completion`. The popup collects the names once, off the
UI thread: the identifiers of the current file (the one under the caret left out), the other open
files, the `DeclarationIndex` names and the language keywords. `CodeCompletion.candidates` then
ranks those names again on every query change as the field is typed in. Collection stops early
once the popup closes. Editor identifier scans are published as one immutable revision snapshot
and, like declaration-line scans, are memoised per text revision. `RENAME_ELEMENT` calls
`openRename(state)`, which stores a `RenameRequest` with the word at the caret in `AppState.rename`.
Both refuse a read-only editor with a `File is read-only` status message, and both set
`AppState.focus = CONTEXT_POPUP`.

`App` draws `CompletionPopup` and `RenamePopup` over the whole window. They are modal: while one
is open `Keymap.actionFor` answers no `GLOBAL` binding, Escape closes it, and both closing paths
reset the request, call `refocusEditor` and give focus back to the text field. The completion
popup has its own field, prefilled with the prefix and with the caret at its end, so typing keeps
filtering inside the popup. ↑↓ move the selection, Enter or Tab inserts the chosen name in place
of the original prefix, and a click on a row inserts it too. The rename popup's field is prefilled
with the old name and selected; Enter applies `applyRename`, which sets the status message to
`Renamed N occurrences`, or to `Nothing to rename` when the name did not change. Enter on an
empty field keeps the popup open and shows a hint.

### Caret following

One effect watching `value.selection` keeps the caret visible on both axes, so every path
that moves it — arrow keys, `gotoLine`, find-bar stepping, undo/redo and edit actions — scrolls
the same way. When the caret leaves the viewport by more than a 24 dp margin the editor scrolls
to bring it back; a horizontal scrollbar sits along the bottom edge next to the vertical one.
`EDITOR_SCROLL_TO_CENTER` reuses the same geometry: it reads the caret line's top and bottom from
the layout and scrolls so that line's middle meets the middle of the visible viewport. The caret
stays inside the following margin, so that effect leaves the new offset alone.
The vertical scrollbar's adapter subtracts the chrome insets from its track, so the thumb reaches
the track's end at `maxValue` instead of stopping short by the inset height.
The gutter (line numbers) follows only the vertical scroll and stays outside the horizontal
scroll, which wraps the text body alone. The following effect watches only the primary caret;
secondary carets do not pull the viewport. With something folded, both read the caret rectangle
through `FoldView.toView`, so they follow the caret's row in the folded text.

### Code folding

`EXPAND_REGION` (⌘= and numpad ⌘+), `COLLAPSE_REGION` (⌘- and numpad ⌘-),
`EXPAND_ALL_REGIONS` (⇧⌘=) and `COLLAPSE_ALL_REGIONS` (⇧⌘-) fold the editor the way IntelliJ's
`ExpandRegion`, `CollapseRegion`, `ExpandAllRegions` and `CollapseAllRegions` do. The dispatcher
forwards them to `TextFileEditor.applyAction`, which consumes the key whenever an editor is
selected, also when nothing changes and also in a read-only editor.

**Representation.** The document stays the field's `TextFieldValue.text`; folding is a view.
`TextFileEditor.foldedRegions` holds the collapsed ranges as `FoldRegion(hiddenStart, hiddenEnd)`
in document offsets, outer ranges first. A range nested in a collapsed range stays collapsed when
the outer one opens; only the maximal ranges (`effectiveFolds`, always disjoint) are drawn.
`FoldView` turns them into the folded text: every effective range becomes the placeholder `...`,
so a function body shows as `{...}`. `FoldView` is the `OffsetMapping` the
`HighlightingVisualTransformation` returns, and it also maps document lines to rows
(`rowOf`, `visibleRowOf`, `lineOfRow`, `visibleLines`, `rowCount`). An offset inside a hidden
range maps to the placeholder's start; the first two placeholder positions map back to
`hiddenStart` and the last to `hiddenEnd`. With nothing folded `filter` takes the old path and
returns `OffsetMapping.Identity`, `Content` adds no modifier and every row computation is the
identity, so the unfolded editor keeps its frame cost. Fold state belongs to the editor
instance: it survives tab switches, is not saved across restarts and is not part of undo.

Two alternatives were rejected. Making the folded text the field's value would force every
editing path (`applyValue`, `onFieldValueChange`, `MultiCaret`, `EditActions`, undo, find,
navigation, intentions, highlighter edits) to translate offsets — the rejected caret-model
option C again. Hiding lines with zero-size styling under the identity mapping would keep the
hidden newlines breaking rows, and the fixed `codeLineHeight` would give each hidden line its
full height, so `{...}` could not share one row.

**Where regions come from.** There is no parse tree. `FoldRegions.candidates` runs only when one
of the four actions runs and reads `CodeScan`, the string and comment scan the brace actions
already use: every `{}` and `[]` pair whose ends are on different lines (hidden between the
brackets), every terminated multi-line block comment and KDoc (hidden between `/*` or `/**` and
`*/`), and a run of two or more `import` lines with only blank lines between them (hidden after
the first `import `, up to the end of the last import line, `\r` excluded). Only `kt`, `kts`,
`java` and `json` files have candidates; a candidate that partially overlaps an earlier one is
dropped, so the set is nested or disjoint. A region's start line is the line of its
`hiddenStart`.

**Actions.** The regions are the candidates plus `foldedRegions`; the primary caret
(`value.selection.end`) picks the target, and a region contains it when
`hiddenStart <= caret <= hiddenEnd`.

| action | target |
|---|---|
| Collapse (⌘-) | the only region starting on the caret's line if it is expanded; otherwise the innermost expanded region containing the caret |
| Expand (⌘=) | the only region starting on the caret's line if it is collapsed; otherwise the outermost collapsed region containing the caret |
| Collapse All (⇧⌘-) | every region |
| Expand All (⇧⌘=) | none stays collapsed |

After a collapse every caret end that lies strictly inside an effective fold moves to its
`hiddenStart`; the carets are normalised and the secondary carets are kept. Folding records no
undo entry.

**A fold never hides a caret.** Wherever the value changes (`applyValue`, `applyCarets`,
`restoreHistory`) the folds follow the text change: a range entirely after the change (an
insertion at `hiddenStart` included) shifts, a range entirely before it (an insertion at
`hiddenEnd` included) stays, and a range the change touches opens. Then any range that strictly
contains a caret end opens. Horizontal movement is the exception: when the field moves the
selection without changing the text, an end that lands inside a fold snaps to `hiddenEnd` when
it moved forward and to `hiddenStart` when it moved back, so ← and → (with ⇧ too) step over the
placeholder; `moveCarets` does the same for every caret on its horizontal moves. The field's
own vertical, word, line-start/end and mouse movement go through the layout, and the mapping
never returns an offset inside a fold. Consequently go to line, navigation and ⌘-click targets,
find-bar stepping, occurrence selection, undo/redo and any other path that puts a caret inside a
fold open it; typing or deleting that touches hidden text opens it; and replacing a selection
that covers a whole fold removes it.

**Drawing and input.** `Content` builds the `FoldView` from the text and `foldedRegions`. Caret
following and Scroll to Center read the caret rectangle through `toView`; the background canvas
and the gutter paint the current-line glow on rows; the gutter has one number per row (the row's
first document line), so the numbers of hidden lines are skipped, and bookmarks and run markers
on hidden lines are not drawn; the gutter height counts rows. Secondary carets are mapped into
the folded text before they are drawn, and ⌘-click and ⌥⇧-click map the pressed offset back with
`toDoc`. On a folded row the column-selection corner is taken from the document offset under the
pointer, so pressing the placeholder yields `hiddenStart` rather than an offset inside the fold.
Each visible placeholder is drawn in the gutter colour (the line numbers' contrast guarantee)
inside a 1 dp rounded outline of `accent2` at 55% alpha, drawn by a modifier that exists only
while something is folded; no token or search style reaches the placeholder. A plain click on a
placeholder opens that fold and puts the caret at its `hiddenStart`.

**Known differences.** No gutter fold markers, Expand/Collapse to Level, Fold Selection ⌘.,
`//region` folding, collapsed-by-default settings or placeholder preview popup; fold state is
not saved; a placeholder is always `...` (IntelliJ shows, for example, the first KDoc line);
containment uses the hidden range, so a caret right after `}` is not inside the block; secondary
carets move ↑/↓ by document line and open a fold they land in; line actions (delete, duplicate,
move, comment) act on document lines and open a fold their edit touches; and an edit right at a
fold edge can be diffed into the hidden text, which opens the fold.

## Tool windows

```kotlin
package stride.app.ui.toolwindow

class ToolWindow(
    val id: String,
    val title: String,
    val context: KeyContext,
    val content: @Composable (Modifier) -> Unit,
)

class ToolWindowManager {
    val windows: List<ToolWindow>   // Compose snapshot list, registration order
    var activeId: String?
    var visible: Boolean
    var height: Dp                  // default 240.dp, never below 120.dp
    var focusRequests: Int
    fun register(window: ToolWindow)      // replaces an existing window with the same id
    fun activate(id: String)              // visible = true, activeId = id, focusRequests++
    fun toggle(id: String, focused: Boolean)
    fun hide()                            // visible = false
    fun active(): ToolWindow?
}

@Composable fun ToolWindowPanel(manager: ToolWindowManager, onFocusContext: (KeyContext) -> Unit,
                                modifier: Modifier = Modifier,
                                contentInset: Dp = 0.dp, contentBottomInset: Dp = 0.dp)

data class ListWindowRow(
    val primary: AnnotatedString,
    val secondary: AnnotatedString? = null,
    val iconName: String? = null,
    val indent: Int = 0,
    val accent: Boolean = false,
)

@Composable fun ListToolWindow(
    rows: List<ListWindowRow>,
    focusRequests: Int,
    onOpen: (Int) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((Int) -> Unit)? = null,
    selectOnFocus: (() -> Int)? = null,
    onSelectionChange: ((Int) -> Unit)? = null,
)

@Composable fun ToolWindowEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    focusRequests: Int = 0,
    onEscape: (() -> Unit)? = null,
)
```

`toggle` follows the `ActivateToolWindow` rule: hidden or a different active id → `activate`;
visible, active and `focused` → `hide`; visible, active and not `focused` → only
`focusRequests++`.

`ToolWindowPanel` is edge-to-edge content with one floating capsule group over it: the group
sits `chromeGap` below the panel top and `contentInset` from its left edge, each tab is a
capsule inset 2/3 dp and only the active tab shows the selection pill (active label in `text`,
inactive in `textSecondaryOnGlass`), and the content plane starts below the group and `contentInset` from the
left, with `contentBottomInset` reserved for the status capsules. The panel root is focusable and
requests focus whenever `focusRequests` changes, reporting its context through `onFocusContext`.

`AppState` registers six windows in its constructor, in switcher order: `bookmarks` (title
Bookmarks, context `LIST_WINDOW`), `find` (title Find, `LIST_WINDOW`), `run` (title Run,
`RUN_WINDOW`), `problems` (title Problems, `LIST_WINDOW`), `structure` (title Structure,
`LIST_WINDOW`) and `terminal` (title Terminal, `TERMINAL`). The ids follow the IntelliJ
tool-window numbers: `⌘2`, `⌘3`, `⌘4`, `⌘6`, `⌘7` and `⌥F12`. Those keys go through
`AppState.toggleToolWindow(id: String, context: KeyContext)`, which applies the same toggle rule
and, when the press hides the active and focused window, hands the keyboard back to the selected
text editor (`focus = EDITOR` and a bump of `editorFocusRequests`). `⇧Esc` (`HIDE_ACTIVE_WINDOW`)
hides the active window through `hideActiveWindow()` with the same hand-back. A window with no
project shows one placeholder line (`Run — no configuration yet`, `Terminal — not started`); the
list windows show their empty state instead.

The four list windows share `ListToolWindow`; `ListWindowRow` carries the primary text, an
optional secondary text, a file icon, an indent depth and an `accent` flag that paints the
primary text in `accent2`. Inside the list, ↑/↓ move the selection, ↵ opens the selected row (and
the editor takes the keyboard), Esc hands the keyboard to the editor and leaves the window
visible, and `⇧Esc` hides it through the `LIST_WINDOW` binding. A click selects and a double click
opens. `onDelete` adds Delete/Backspace removal (Bookmarks only). The list scrolls only when the
selected row is outside the visible items, so moving within a fully visible list does not jump.
`selectOnFocus` runs when the window takes the keyboard and again when rows first arrive, so a
list parsed off the UI thread can start on the requested row; `onSelectionChange` reports the
selected index so a header button can act on it. `ToolWindowEmptyState` draws the one muted line
an empty window shows; with `onEscape` it takes the keyboard like the list does, so Esc still
hands focus back from an empty window.

The Bookmarks window lists `session.bookmarks.entries` in set order: primary
`relativePath:line` (1-based), secondary the line text. ↵ or a double click calls
`openBookmark`, and Delete/Backspace removes the selected bookmark while the window stays open.
The Structure window follows the selected `TextFileEditor`, renders `FileStructure.entries` with
the depth as the indent and class rows in `accent2`, and parses on `Dispatchers.Default` after a
150 ms debounce, only while the window is composed (active and visible). When the window takes
the keyboard it selects the last entry whose line is at or above the caret line; ↵ or a double
click calls `editor.gotoLine((entry.line + 1).toString())` and hands the keyboard to the editor.

Decision D1: the list windows are ordinary `ToolWindow`s registered in the existing
`ToolWindowManager` and drawn in the existing bottom `ToolWindowPanel`, with one shared list
composable. A side dock was rejected: IntelliJ's default position for Bookmarks and Structure is
the left side, but a second layout region beside the project tree would change the editor width
and the editor frame path even when nothing is shown, and it would add files on the frame-time
path. One bespoke composable per window was rejected because it would copy the selection,
keyboard and scroll code three times. The behavioural difference from IntelliJ is therefore the
dock position: stride shows Bookmarks, Find and Structure in the bottom panel. The panel sits
below the editor area and spans the full window width, under the floating sidebar and status
capsules; a `chromeGap` horizontal splitter carries the drag into `height` clamped to 120 dp …
80% of the window height. `closeSession()` hides the panel and resets `focus`.

`stride.app.ui.LocalAnimationsEnabled` (`staticCompositionLocalOf { true }`) turns UI animations
off in tests and snapshots.

## Find and navigation

```kotlin
package stride.app.editor.find

data class FindOptions(
    val caseSensitive: Boolean = false,
    val regex: Boolean = false,
    val wholeWord: Boolean = false,
)
data class FindMatch(val start: Int, val end: Int)
data class FindResult(val matches: List<FindMatch>, val error: String? = null)

object FindModel {
    fun search(text: String, query: String, options: FindOptions = FindOptions()): FindResult
    fun nextIndex(matches: List<FindMatch>, caret: Int): Int?
    fun previousIndex(matches: List<FindMatch>, caret: Int): Int?
    fun replaceCurrent(text: String, match: FindMatch, replacement: String): TextChange
    fun replaceAll(text: String, matches: List<FindMatch>, replacement: String): List<TextChange>
}
```

`FindModel` is pure logic. An empty query has no matches, an invalid regular expression
returns no matches plus `error`, and `replaceAll` returns changes back to front so applying
them in order keeps every untouched offset valid. In regular-expression mode a replacement may
refer to groups with `$1`; `FindBarState` expands them through `Regex.replace` rules.

```kotlin
class FindBarState {
    var visible: Boolean
    var replaceVisible: Boolean
    var query: String
    var replacement: String
    var caseSensitive: Boolean
    var regex: Boolean
    var matches: List<FindMatch>
    var currentIndex: Int?
    var error: String?
    fun open(editor: TextFileEditor?, replace: Boolean)
    fun close()
    fun refresh(editor: TextFileEditor?)
    fun next(editor: TextFileEditor?): Boolean
    fun previous(editor: TextFileEditor?): Boolean
    fun replaceCurrent(editor: TextFileEditor?)
    fun replaceAll(editor: TextFileEditor?)
}

@Composable fun FindBar(
    state: FindBarState,
    editor: TextFileEditor?,
    onFocusContext: (KeyContext) -> Unit,
    modifier: Modifier = Modifier,
)
```

`open` seeds the query from a single-line selection, expands the replace row for `⌘R` and
refreshes. `refresh` repaints every match through `TextFileEditor.searchSpans`: matches use
`DesignTokens.neonAmberGlow`, the current match `neonPinkGlow`. `next`/`previous` select the
current match through `updateValue` and then advance, wrapping around. `close` hides the bar
and clears the spans of the last editor it painted.

`FindBar` floats under the tab group as a `capsuleGlass` panel with an accent2 `neonUnderline`
on its bottom edge, inset by half the capsule height. The query field carries `testTag("find-query")`, the
replacement field `testTag("find-replacement")`; both are single line. Enter/`⌘G` goes to the
next match, ⇧Enter/`⌘⇧G` to the previous, and Esc closes the bar and hands focus back to the
editor's `focusRequester`. The `Aa` and `.*` toggles show `accent2` glow when active. The
counter reads `n / total`, `No matches`, or the regular-expression error in `neonPink`.

Focus reporting: the query and replacement fields report `FIND_BAR` while they have focus and
`EDITOR` when they leave. The editor area's outer focus box skips reporting while
`state.findBar.visible`, so it cannot overwrite the find bar's report.

```kotlin
package stride.app.search

data class FindInPathHit(
    val file: VFile, val relativePath: String, val line: Int, val column: Int,
    val lineText: String, val start: Int, val end: Int,
    val matchText: String = "",
)

object FindInPathScanner {
    const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024
    const val MAX_HITS: Int = 200
    fun scan(
        files: List<IndexedFile>, query: String, options: FindOptions,
        limit: Int = MAX_HITS, textOf: (Path) -> String? = { null },
        shouldStop: () -> Boolean = { false },
    ): List<FindInPathHit>
}

@Composable fun FindInPathPopup(
    index: FileNameIndex,
    initialQuery: String = "",
    initialWholeWord: Boolean = false,
    onChoose: (FindInPathHit) -> Unit,
    onClose: () -> Unit,
    textOf: (Path) -> String? = { null },
    onOpenInFindWindow: (query: String, options: FindOptions) -> Unit = { _, _ -> },
    replace: Boolean = false,
    initialReplacement: String = "",
    onOpenReplaceInFindWindow: (query: String, options: FindOptions, replacement: String) -> Unit =
        { _, _, _ -> },
    onReplaceAll: (ReplacePlan) -> Unit = {},
)

const val FIND_WINDOW_MAX_HITS: Int = 1000

enum class FindTabKind { USAGES, OCCURRENCES, REPLACE }

class FindTab(
    val id: Long, val kind: FindTabKind, val query: String, val options: FindOptions,
    val replacement: String?, val title: String,
) {
    var hits: List<FindInPathHit>   // Compose snapshot state, internal setter
    var searching: Boolean
    var truncated: Boolean
    var message: String?
    val header: String
}

class FindToolWindowState(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val tabs: List<FindTab>
    var selectedTabId: Long?
    val selectedTab: FindTab?
    fun select(tab: FindTab)
    fun addTab(kind: FindTabKind, query: String, options: FindOptions, title: String,
               files: List<IndexedFile>, replacement: String? = null,
               textOf: (Path) -> String? = { null }): FindTab
    fun close(tab: FindTab)
    fun closeSelected(): FindTab?
    fun showMessage(tab: FindTab, message: String?)
    fun replaceState(tab: FindTab): ReplaceTabState
    fun startReplaceAll(tab: FindTab, controller: FindReplaceController)
    fun finishReplaceSelected(tab: FindTab, file: VFile, outcome: ReplaceOutcome, hits: List<FindInPathHit>)
    fun refreshFileHits(tab: FindTab, file: VFile, hits: List<FindInPathHit>)
    fun finishReplace(tab: FindTab, message: String)
    fun clear()
    companion object { const val MAX_TABS: Int = 10 }
}

@Composable fun FindToolWindow(
    state: FindToolWindowState, focusRequests: Int, onOpen: (FindInPathHit) -> Unit,
    onEscape: () -> Unit, modifier: Modifier = Modifier,
    replace: FindReplaceController? = null,
)

fun openFindHit(state: AppState, session: ProjectSession, hit: FindInPathHit)

@Composable fun RecentFilesPopup(
    files: List<VFile>,
    projectRoot: Path,
    onChoose: (VFile) -> Unit,
    onClose: () -> Unit,
)
```

`⌘⇧F` opens `FindInPathPopup` over any project. It scans `FileNameIndex.snapshot()` on
`Dispatchers.Default`, skipping files over 2 MB, files with a NUL byte in their first 8 KB and
files that fail to read; a new query cancels the previous scan through coroutine cancellation.
`textOf` supplies the text of a file that is open in an editor, so an unsaved edit is searched
instead of the disk; the app passes `AppState.textOfOpenEditor`. Rows read `relativePath:line`
with the line body, the matched characters in `neonAmber`; the status line reads `n results`,
`searching…` or `200+ results (truncated)`. Enter opens the file and selects the match in the
editor. `⌘↵` closes the popup and opens the query in the Find window.

`⌘E` opens `RecentFilesPopup` with `FileEditorManager.recentFiles` — newest first, at most 50
entries, closed files still listed. The first selection is the second entry; Up/Down move,
Enter opens, Esc closes. Both popups follow the Search Everywhere popup frame: header, result
list, footer hints and the neon selection signal.

`FileEditorManager.recentFiles` is a snapshot list updated by `openFile` (new and existing
tabs) and by `selectNext`/`selectPrevious`; `closeFile` leaves entries in place and `dispose`
clears them. `AppState` adds `findBar`, `findInPathVisible`, `findInPathReplace` and
`recentFilesVisible`, and `closeSession()` closes and resets them.

Whole-word matching (`FindOptions.wholeWord`, the `W` toggle in the popup) keeps only matches
whose two sides are not identifier characters. `FindUsages` (`⌥F7`) no longer opens this popup:
it adds a `Usages of <name>` tab to the Find window with whole-word and case-sensitive matching,
then activates and focuses the Find window. The old popup route was a stopgap; IntelliJ shows
usages in the Find tool window.

### Find window

`FindToolWindowState` owns the result tabs and their scans. A tab is added from `⌥F7`
(`FindTabKind.USAGES`) or from the popup's `⌘↵` (`OCCURRENCES`, titled
`Occurrences of '<query>'`), and a Replace in Path tab is `REPLACE`, titled
`Replace '<query>' with '<replacement>'`. At most `MAX_TABS` (10) tabs are kept and the oldest is
dropped. A new search whose kind, query, options and replacement equal an existing tab's
replaces that tab in place and selects it. The scans run on a scope the state owns (not the
composition), so hiding the window leaves no tab stuck in `searching…`; `closeSession()` calls
`clear()`, which cancels the scans and replace plans and drops every tab. Results are applied in
one mutable snapshot, and a scan that was cancelled or whose tab was replaced writes nothing.
A tab scans up to `FIND_WINDOW_MAX_HITS` (1000); its header reads `n results`, `searching…` or
`1000+ results (truncated)`. Rows are a flat list, primary `relativePath:line`, secondary the line
body from `hitLine`, the highlighted line builder the popup shares. `⌘W` while the Find window has
the keyboard (`state.focus == LIST_WINDOW` and active id `find`) closes the selected tab instead
of the editor tab; this is the existing `CLOSE_TAB` branch, with no new binding.

`FindToolWindow` draws a horizontally scrolling capsule group of tabs, each with a small ×, and
keeps the selected tab visible. The window takes the keyboard like the other list windows; its
empty state reads `No results · ⇧⌘F Find in Path, ⌥F7 Find Usages`, and a searching tab with no
hits yet draws `searching…` as the focusable empty state.

Opening a hit goes through `openFindHit`, shared by the popup and the window: it opens the file
and selects `[hit.start, hit.end)` when the text there still equals `hit.matchText`, otherwise it
goes to `hit.line`; the landing position is one history entry.

### Replace in Path

`⇧⌘R` (`REPLACE_IN_PATH`) calls `AppState.openFindInPath(replace = true)`, which sets
`findInPathReplace`; `FindInPathPopup` then opens in replace mode with the title
`Replace in Path` and a `Replace with` row under the query (Tab moves between the two fields).
Rows strike the matched characters through in `neonAmber` and show ` → ` followed by the
replacement as typed, in `accentOnPopupPlate`. `↵` opens the selected hit, `⌘↵` opens the query
and replacement in the Find window as a REPLACE tab, and `⌥⌘↵` runs Replace All.

```kotlin
enum class ReplaceSource { EDITOR, DISK }

data class ReplaceFilePlan(
    val file: VFile, val relativePath: String, val source: ReplaceSource,
    val originalText: String, val edits: List<TextEdit>,
) {
    val newText: String
}

data class ReplacePlan(
    val files: List<ReplaceFilePlan>,
    val error: String? = null,
    val skippedEncoding: Int = 0,
) {
    val occurrences: Int
    val diskFiles: Int
}

data class ReplaceOutcome(
    val replaced: Int = 0, val files: Int = 0, val skippedReadOnly: Int = 0,
    val skippedChanged: Int = 0, val unsavedFiles: Int = 0, val failed: Int = 0,
    val skippedEncoding: Int = 0,
) {
    val message: String
}

data class HitPlan(val plan: ReplaceFilePlan?, val error: String? = null)

object ReplaceInPath {
    fun replacementsFor(text: String, query: String, options: FindOptions, replacement: String): List<TextEdit>
    fun plan(files: List<IndexedFile>, query: String, options: FindOptions, replacement: String,
             textOf: (Path) -> String? = { null }, shouldStop: () -> Boolean = { false }): ReplacePlan
    fun planHit(hit: FindInPathHit, query: String, options: FindOptions, replacement: String,
                textOf: (Path) -> String? = { null }): HitPlan
    fun apply(plan: ReplacePlan, openEditors: List<TextFileEditor>): ReplaceOutcome
    fun confirmation(plan: ReplacePlan, query: String, replacement: String): String
}

class FindReplaceController(
    files: () -> List<IndexedFile>,
    textOf: (Path) -> String?,
    openEditors: () -> List<TextFileEditor>,
) {
    fun planAll(tab: FindTab): ReplacePlan
    fun planHit(tab: FindTab, hit: FindInPathHit): HitPlan
    fun apply(plan: ReplacePlan): ReplaceOutcome
    fun scanFile(tab: FindTab, hit: FindInPathHit): List<FindInPathHit>
}

class ReplaceTabState {
    var busy: Boolean
    var pendingPlan: ReplacePlan?
    var confirmation: String?
}
```

Decision D3: Replace in Path is two-phase, plan then apply. The plan runs off the UI thread over
every indexed file — not only the displayed hits, so a truncated result list does not limit
Replace All. Each file's text comes from the overlay first (the open editor's unsaved text) and
from the disk otherwise; one edit per match is computed with `FindModel.search`. In regex mode
`$1`-style group references expand with `java.util.regex` semantics over the full text, so a
lookbehind or lookahead sees the real context; overlapping literal matches keep the first one.
Before Replace All both UIs show an inline confirmation (not a dialog) in IntelliJ's wording,
`Replace N occurrences of 'q' across M files with 'r'?`, plus `K files are written to disk and
cannot be undone.` when K planned files are not open, plus a notice for files that had a match
but are not UTF-8. `↵` confirms and Esc cancels.

Apply runs on the UI thread, per file. When an editor is open for the path now, the file is
skipped as read-only when that editor is read-only, skipped as changed when its text differs from
the planned original text, and otherwise takes `editor.applyEdits(edits)`: one undo step, and the
editor stays modified (unsaved). Otherwise the disk file is skipped as read-only when it is not
writable, re-read and skipped as changed when its text differs from the planned original, and
otherwise written with `VFile.writeText`. An `IOException` during a write counts that file's
occurrences as failed and the remaining files are still written. Replace Selected plans only the
hit's file and keeps the one edit whose range equals the hit's range; a hit whose match is gone
is reported as changed. `ReplaceOutcome.message` reads
`Replaced N occurrences in M files` followed by `, K skipped (read-only)`,
`, K skipped (changed)`, `, K failed`, `, K files skipped (not UTF-8)` and
`; K open files not saved` when they apply; the popup hands the plan to `AppState.applyReplacePlan`,
which writes that message to `AppState.statusMessage`.
The undo unit is therefore one step per open editor, through that editor's own ⌘Z; a file written
to the disk has no undo in stride, which is why the confirmation states the count.

Replace reads the disk strictly: `FindInPathScanner.readTextStrict` returns `null` for bytes that
are not valid UTF-8, so a file is never rewritten with U+FFFD replacement characters. A file that
matches when read leniently but is not valid UTF-8 is counted in `ReplacePlan.skippedEncoding`
and left untouched; BOM and CRLF survive because decoding and `VFile.writeText` are byte-faithful
for them. Searching itself stays lenient (`readText` replaces invalid bytes with U+FFFD), so a
non-UTF-8 file still yields its ASCII matches. A regex replacement that java.util.regex cannot
expand (`$2` with one group, say) becomes `Invalid replacement: <reason>`: the popup and the Find
window show it and never offer the confirmation, and both clear their counting state in a
`finally`, so the ⌥⌘↵ guard cannot stick. Writes stay `VFile.writeText`, with no temporary file
and atomic move: a rename would replace a symlink instead of writing through it and can fail on
directory permissions, and the confirmation already tells the user that disk files cannot be
undone.

The Find window's REPLACE tab draws the same preview rows and adds Replace Selected and Replace
All buttons above them. Replace Selected applies the selected row only, then rescans just that
file so the remaining rows carry the offsets the current text actually has (a second Replace
Selected in the same file then works); a row that was skipped keeps its place, and an error is
shown as the tab's message. Replace All plans through the tab and shows the same inline
confirmation with Replace and Cancel buttons; after applying, the tab keeps the outcome line and
no rows. The replace state lives per tab in `FindToolWindowState`, so switching tabs while a plan
runs neither drops the confirmation nor re-enables the buttons.

Rejected alternatives: opening every affected file in a tab and editing in memory only (tabs
explode on a large replace, and stride closes an unsaved tab without asking, so changes can be
lost silently); writing every file to the disk, including files open in editors (overwrites
unsaved editor text or diverges from it, and loses the editor undo); and a session-wide undo
record for Replace in Path (stride has no global undo, and it would be a non-IntelliJ action).

Known limits: the row preview shows the replacement as typed, so it does not expand `$1` (the
applied plan does); a disk write that fails midway leaves that file truncated and is counted as
failed; there is no Exclude, Group by or Merge; Structure has no sort, filter or autoscroll
option, and speed search is not included; open editors stay unsaved after Replace and are saved
with the rest.

## Problems

```kotlin
package stride.app.problems

data class CompilerMessage(
    val path: Path,
    val line: Int,          // 0-based
    val column: Int?,       // 0-based; javac has none
    val severity: ProblemSeverity,
    val message: String,
)

object CompilerMessageParser {
    fun parse(line: String): CompilerMessage?
}

class LineRemap {
    fun map(line: Int): Int?  // null hides the line
    companion object {
        fun of(base: List<String>, current: List<String>): LineRemap
        fun of(baseText: String, currentText: String): LineRemap
    }
}

enum class ProblemSource { EDITOR, BUILD }

data class FileProblem(
    val severity: ProblemSeverity,
    val message: String,
    val source: ProblemSource,
    val line: Int,          // 0-based
    val column: Int,        // 0-based
    val start: Int,         // text offset; -1 when the text is unknown
    val end: Int,
)

data class FileErrors(val path: Path, val errors: List<FileProblem>)

@Service(Service.Level.PROJECT)
class ProblemsModel(private val project: Project) {
    var buildVersion: Int  // Compose state; bumped when the build source is replaced
    fun publishBuild(messages: List<CompilerMessage>)
    fun compute(path: Path, fileName: String, text: String,
                area: ExtensionArea = Application.extensionArea): List<FileProblem>
    fun record(path: Path, problems: List<FileProblem>, version: Int = buildVersion)
    fun projectErrors(isOpen: (Path) -> Boolean): List<FileErrors>
}

object ProblemNavigation {
    fun target(problems: List<FileProblem>, caret: Int, forward: Boolean): FileProblem?
    fun at(problems: List<FileProblem>, caret: Int): List<FileProblem>
}

enum class ProblemScope { FILE, PROJECT_ERRORS }
class ProblemsToolWindowState {
    var scope: ProblemScope
    fun selectNext()
    fun selectPrevious()
    fun reset()
}

@Composable fun ProblemsToolWindow(editor: TextFileEditor?, model: ProblemsModel?,
                                   toolState: ProblemsToolWindowState, focusRequests: Int,
                                   keymap: Keymap, root: Path?, isOpen: (Path) -> Boolean,
                                   onOpenEditorProblem: (FileProblem) -> Unit,
                                   onOpenFileProblem: (Path, FileProblem) -> Unit,
                                   onEscape: () -> Unit, modifier: Modifier = Modifier)
@Composable fun EditorProblems(session: ProjectSession)

data class ErrorDescriptionRequest(val editor: TextFileEditor, val problems: List<FileProblem>)
@Composable fun ErrorDescriptionPopup(request: ErrorDescriptionRequest, onClose: () -> Unit)
```

`RunSession` gained a trailing constructor parameter with a default,
`onCompilerMessages: ((List<CompilerMessage>) -> Unit)? = null`. Its reader parses the console
lines as compiler messages, keeps at most `MAX_COMPILER_MESSAGES` (1000) and drops the rest, and
calls the callback before `exitCode` is set; a session that was stopped never calls it.
`RunManager.run` passes the callback only when `configuration.command == null` (a Gradle run) and
publishes through the project's `ProblemsModel`.

The four keys are `⌘6` (`ACTIVATE_PROBLEMS_TOOL_WINDOW`, GLOBAL, `ActivateProblemsViewToolWindow`),
`F2` (`GOTO_NEXT_ERROR`), `⇧F2` (`GOTO_PREVIOUS_ERROR`) and `⌘F1` (`SHOW_ERROR_DESCRIPTION`), the
last three in the editor context. `⇧⌘]` / `⇧⌘[` switch the window's two ranges while the window
has the keyboard.

Decision D1: the problems come from two sources. The editor source is the language plugin's
`stride.lang.problemProvider`; the build source is the compiler output of a Gradle run.

| Option | Where the problem comes from | False positives | Files |
|---|---|---|---|
| A (rejected) | The unresolved-symbol pass of the intention layer | High: JDK and library declarations are not indexed, so `println` or `listOf` cannot be told from a real error; restricting to names that have candidates misses typos and reports a top-level declaration that shares a local's name | language plugin + app |
| B (rejected) | `CodeScan`'s bracket pairs in the app | Medium: `CodeScan` does not read inside `${...}`, so a nested quote breaks the string boundary, and it does not count nested block comments; Markdown and text have no bracket rule | app |
| C (adopted) | The language plugin's lexer behind `ProblemProvider` | Low: the same lexer the highlighter uses already separates templates (recursively), raw strings and nested comments | lang + two plugins + app |
| D (adopted) | Gradle compiler output (`e:`, `w:`, javac) | None: the compiler's own verdict. The lines can drift after an edit, so they are remapped | app run + model |

Decision D2: a provider reports nothing when it cannot be sure (the contract in
`stride.lang.Problem.kt`). Name and type resolution belongs to the build source; the editor
source does not report unresolved references. A build message is shown only when its line is
certain (D4); otherwise it stays hidden until the next build rather than pointing at a wrong line.

Decision D3: the bracket-pair rule is a `stride.lang` function over the lexer's code bracket
tokens, so strings, characters, comments and `<`/`>` never count. It is part of the language side;
this app side only consumes `ProblemProvider.collect`, which already sorts by start and drops
problems outside the text.

Decision D4: the editor source is recomputed `300 ms` after the selected editor's text goes quiet,
on `Dispatchers.Default`, and when the selected editor changes; a hidden editor keeps its last
result until the next build. When the build source changes, every other open `TextFileEditor` is
recomputed at once, without the debounce, so its record moves onto the new build version; the
selected editor keeps the debounced path. When a Gradle run finishes without being stopped, its
whole compiler message set replaces the previous build source (an empty set clears it); a custom
command run and a stopped run do not.
At the end of a build the disk text of every file that has a message is kept as the base text,
and `LineRemap` maps a base line onto the current text in three steps:

1. Count the lines equal from the top (`p`) and from the bottom (`s`) without overlap.
2. A base line `l < p` stays `l`; a line `l >= baseCount - s` becomes `l - baseCount + currentCount`.
3. A line inside the changed span maps only when its text occurs exactly once in the base span
   and exactly once in the current span; otherwise `map` returns `null` and the message is hidden.

`compute` merges the editor source with the build messages remapped onto the given text, drops a
duplicate `(start, severity, message)`, and sorts by start with errors before warnings at the same
start. A build message's range starts at the line start plus its column (clamped to the line),
ends after the identifier that starts there (or one character, clamped), and without a column
covers the line's first non-blank character through its last non-blank character. A file whose
base text could not be read has no remap, so the editor source only.

A record keeps the `buildVersion` it was computed on; the editor effects read the version before
computing and pass it to `record`, so a build that lands mid-computation leaves that record
stale instead of stamping old build lines with the new version. `publishBuild` replaces the build files and
bumps `buildVersion` in one snapshot and keeps the records; a record older than the current
version contributes only its editor-sourced errors, so a build message never points at a line the
editing has moved until the file is recomputed.

A compiler line's URI path may contain a space. The path is read through `URI` when it parses;
otherwise (an unencoded space) the text after `file://` is taken as written and `%XX` is left
undecoded. A path that is not absolute reads as no message.

Decision D5: the Problems window is the existing bottom `ListToolWindow` in a `ToolWindow` named
`problems`, registered between Run (`⌘4`) and Structure (`⌘7`). It has two capsule range tabs with
counts, `File` and `Project Errors`, the last choice kept in `ProblemsToolWindowState`. A row's
primary text is a severity symbol with the message — `●` in `neonPinkOnGlass` for an error, `▲` in
`neonAmberOnGlass` for a warning — and its secondary text is the 1-based `line:column`, plus
` · build` for a build source. Project Errors groups the errors by file: a header row with the
relative path and `1 error` / `N errors`, then indented error rows. Its files are the build
files plus every open file that has a record. An open file reports its record's errors, the
editor-sourced ones only when the record predates the current build; an open file without a
record and a closed file report the build messages as they are (line and column known, offsets
unknown). ↵ or a double click moves the selected editor's caret to the problem start and hands it
the keyboard; in Project Errors it opens the file at its line and column as one history entry (a
header row opens that file's first error), and a file that no longer exists is reported as
`File not found: <relative path>` instead, opening nothing and adding no history entry. The
window stays open. Empty states are `No file selected`, `No problems in <file name>` and
`No errors · ⌘F9 Build Project` (the stroke from `shortcutText(keymap, BUILD_PROJECT)`).

Decision D6: the editor draws a wave underline under each problem — `DesignTokens.neonPink` for an
error, `DesignTokens.neonAmber` for a warning, a one-character width for an empty range — inside
the same visible-row window the fold drawing uses, and skips a problem that starts inside a
collapsed range. An empty range at a line end is kept on the line the range starts on and drawn at
that fallback width; an empty piece on a later line of a multi-line range is skipped. Because the
problems are start-sorted and the fold mapping is monotone, the walk stops once a problem starts
past the window's last line end and skips a problem that ends before the window. When the list is
empty the modifier chain does not grow, so the clean editor path keeps its frame cost. `F2` /
`⇧F2` walk the selected file only, with the IntelliJ default that errors are walked when the file
has one and warnings otherwise: forward takes the first start after the caret and wraps to the
top, backward takes the last start before it and wraps to the bottom, the caret moves to the
start (`TextFileEditor.jumpTo`, which drops the secondary carets,
unfolds through `reconcileFolds` and records one jump), and a file without a problem shows
`No problems in this file`. `⌘F1` shows `ErrorDescriptionPopup` for the problems at the caret
(`start ≤ caret ≤ end`); without one it shows `No problems at the caret`. The popup sits at
`DesignTokens.popupTop` with `Modifier.popupPanel("popup-error-description", …)`, no scrim, and
closes on Esc, `⌘F1`, ↵ or a click outside, handing the keyboard back to the editor; it closes when
the selected editor changes. Each block shows `Error` / `Warning` in its colour, `Build` for a
build source, the message over several lines, and `Ln x, Col y`. The error label uses the new
`DesignTokens.errorOnPopupPlate` (`errorOnPopupPlate(palette)`), because the palette's
`neonPink` is 4.04:1 on the dark plate over a white desktop and 4.34:1 on the light plate over a
black one; the token aims at 4.6 so the rendered 8-bit plate still clears 4.5. Both roles are
registered in `PopupNativeLegibilityTest` and `PopupPlateContrastTest`.

Differences from IntelliJ and limits: the window docks at the bottom, not the left; there is no
gutter icon (IntelliJ's default has none either); the editor source comes from a lexer, not from
compilation, so it reports bracket pairs and unused imports only (whatever the language plugin
contributes); an underline keeps its old offsets until the next recompute, up to `300 ms` after
the typing stops, where IntelliJ moves a range with the edit; and the Project Errors view is flat
by file with no scopes. Out of scope: errors from a compiler running in the editor, inspection
settings, apply-all quick fixes, grouping and filtering, scrollbar marks and the inspection
widget, saving before a run, and the Problems navigation actions.

## Navigation actions

Six IntelliJ navigation actions built on data stride already had: the navigation history, the
`⌥F7` usage search, and the project tree and path. The hierarchy actions (Implementation, Type
Declaration, Super Method, Type and Call Hierarchy) need a new source (supertypes and calls) and
are in 「Hierarchy navigation」.

| Action | `ActionId` | IntelliJ id | Key | Context |
|---|---|---|---|---|
| Recent Locations | `RECENT_LOCATIONS` | `RecentLocations` | `⇧⌘E` | GLOBAL |
| Last Edit Location | `JUMP_TO_LAST_CHANGE` | `JumpToLastChange` | `⇧⌘⌫` | GLOBAL |
| Show Usages | `SHOW_USAGES` | `ShowUsages` | `⌥⌘F7` | EDITOR |
| Highlight Usages in File | `HIGHLIGHT_USAGES_IN_FILE` | `HighlightUsagesInFile` | `⇧⌘F7` | EDITOR |
| Jump to Navigation Bar | `SHOW_NAV_BAR` | `ShowNavBar` | `⌥Home`; `⌘↑` (see D6) | GLOBAL; LIST_WINDOW |
| Edit Source | `EDIT_SOURCE` | `EditSource` | `F4`; `⌘↓` (see D6) | GLOBAL; LIST_WINDOW |

None of the six strokes had a binding before. `⌘⌫` (`EDITOR_DELETE_LINE`) is a different stroke
from `⇧⌘⌫`.

```kotlin
package stride.app.navigation

data class EditPlace(val path: Path, val offset: Int)
class EditLocations(maxPlaces: Int = MAX_PLACES) {        // ProjectSession.editLocations
    val places: List<EditPlace>                            // newest first
    fun change(path: Path, offset: Int, oldLength: Int, newLength: Int,
               lineOf: (Int) -> Int, recorded: Boolean)
    fun changes(path: Path, changes: List<TextChange>, lineOf: (Int) -> Int,
                recorded: Boolean, recordIndex: Int = 0)
    fun next(currentPath: Path?, caretLine: Int?, lineOf: (Int) -> Int): EditPlace?
    fun drop(path: Path)
    companion object { const val MAX_PLACES: Int = 50 }
}

fun openFileAtOffset(state: AppState, session: ProjectSession, path: Path, offset: Int): Boolean
fun jumpToLastChange(state: AppState): Boolean
fun showUsages(state: AppState): Boolean
fun openUsagesFindWindow(state: AppState, name: String)   // the tab ⌥F7 opens
fun highlightUsagesInFile(state: AppState): Boolean
object UsageMatches {
    fun ranges(text: String, name: String): List<IntRange>
    fun next(highlights: List<IntRange>, caret: Int): IntRange?
    fun previous(highlights: List<IntRange>, caret: Int): IntRange?
}

data class RecentLocationCodeLine(val number: Int, val text: String, val place: Boolean)
data class RecentLocationRow(val location: NavigationLocation, val fileName: String,
                             val directory: String, val relativePath: String, val line: Int?,
                             val code: List<RecentLocationCodeLine> = emptyList())
fun recentLocationRows(locations: List<NavigationLocation>, root: Path, caret: NavigationLocation?,
                       textOf: (Path) -> String?): List<RecentLocationRow>
fun recentLocationIndex(rows: List<RecentLocationRow>, selected: NavigationLocation?): Int
fun codeAround(text: String, line: Int): List<RecentLocationCodeLine>
@Composable fun RecentLocationsPopup(...)

data class NavigationBarSegment(val name: String, val path: Path, val isDirectory: Boolean)
data class NavigationBarList(val folder: Path, val children: List<VFile>, val selected: Int = 0)
data class NavigationBarState(val segments: List<NavigationBarSegment>, val selected: Int,
                              val list: NavigationBarList? = null) {
    val segment: NavigationBarSegment
    val selectedChild: VFile?
    fun previous(): NavigationBarState
    fun next(): NavigationBarState
    fun openList(children: (Path) -> List<VFile>): NavigationBarState
    fun moveInList(step: Int): NavigationBarState
    fun closeList(): NavigationBarState
    fun descend(folder: VFile, children: (Path) -> List<VFile>): NavigationBarState
    companion object { fun forFile(root: Path, file: Path?): NavigationBarState }
}
fun openNavigationBarFile(state: AppState, session: ProjectSession, file: VFile): Boolean
fun navigationBarListLeft(segmentLeft: Dp, windowWidth: Dp): Dp
@Composable fun NavigationBar(...)
@Composable fun NavigationBarChildrenPopup(...)

package stride.app.search
class ShowUsagesRequest(val id: Long, val name: String, val files: List<IndexedFile>,
                        val textOf: (Path) -> String? = { null }) {
    val hits: List<FindInPathHit>; val searching: Boolean; val truncated: Boolean
}
@Composable fun ShowUsagesPopup(request: ShowUsagesRequest, onOpen: (FindInPathHit) -> Unit,
                                onOpenInFindWindow: () -> Unit, onClose: () -> Unit)

package stride.app.editor
data class TextChange(val offset: Int, val oldLength: Int, val newText: String)  // now documented
class TextFileEditor {
    var usageHighlights: List<IntRange>
    var onTextChange: ((changes: List<TextChange>, recorded: Boolean, recordIndex: Int) -> Unit)?
    fun updateValue(newValue: TextFieldValue, changes: List<TextChange>)  // one undo entry, pieces reported
}
data class UndoEntry(..., val pieces: List<TextChange>? = null)      // trailing, defaulted

package stride.app
class AppState {
    var showUsages: ShowUsagesRequest?; val showUsagesGeneration: Long
    fun openShowUsagesPopup(name: String): ShowUsagesRequest?
    var recentLocationsVisible: Boolean; val recentLocationsGeneration: Int
    val recentLocationsCaret: NavigationLocation?
    fun openRecentLocations()
    var navigationBar: NavigationBarState?
    fun focusSelectedEditor(): Boolean
}
enum class KeyContext { ..., NAV_BAR }                        // modal, focus chip "Navigation"
```

Decision N1: Recent Locations is a `popupPanel` popup over the navigation history in which every
row carries the code around its place.

| Option | What a row shows | Files | IntelliJ | Frame path |
|---|---|---|---|---|
| A (rejected) | `File.kt` and `line N` from `NavigationHistory.locations()` | one popup | IntelliJ's rows are code, so a list of file names repeats Recent Files | popup only |
| B (adopted) | the file name, its directory, `line N` and three code lines (the place's line and one line on each side) | one popup + a text source | close: IntelliJ shows a short code fragment per place | popup only |
| C (rejected) | B over a separate visited-places store that `⌘[` / `⌘]` cannot truncate | B + a second store fed from every jump | closest | popup only, but every jump writes two stores |

The rows are `NavigationHistory.locations()` newest first. Two entries on the same file and line
keep the newest; an entry on the selected editor's caret line is left out. The code comes from the
open editor's text when the file is open (unsaved edits included) and otherwise from the disk
(decoded leniently, like the Find in Path search), read on `Dispatchers.IO` after the popup shows
its rows; the offset is clamped to the text it is read against. The place's line is drawn in
`text`, the neighbouring lines in `textSecondaryOnGlass`, all in the code font. ↵, `F4` or `⌘↓`
opens the place (one history entry, keyboard to the editor); a file that no longer exists shows
`File not found: <relative path>`. Esc closes and hands the keyboard back to the editor.
Differences: the forward entries that a new jump drops after `⌘[` are gone from the list too
(option C's cost was not worth it); no `Show changed only` toggle (pressing `⇧⌘E` again), no
speed search, no syntax colours in the fragment.

Decision N2: the last edit places are a project-session store, `EditLocations`, fed by a text
change callback on `TextFileEditor`.

| Option | Where the place comes from | Across files | Files |
|---|---|---|---|
| A (rejected) | the selected editor's undo stack (`UndoEntry.offset`) | no: one file, and it is gone when the file reloads | editor |
| B (adopted) | `TextFileEditor.onTextChange(offset, oldLength, newLength, recorded)` → `ProjectSession.editLocations` | yes | editor + new store + wiring |
| C (rejected) | flag edit entries inside `NavigationHistory` | yes | history; an edit after `⌘[` would drop the forward entries |

`onTextChange(changes, recorded, recordIndex)` runs once for every text change the editor applies;
`recorded` is true for an edit that enters the undo stack (typing, actions, paste, multi-caret
edits) and false for undo and redo (stride has no reload path). `changes` holds one piece per
replaced range, ascending, each in the coordinates left by the ones before it: a multi-range edit
(rename in file, Replace All, `applyEdits` for Reformat, Optimize Imports, intentions and Replace
in Path, multi-caret typing and the per-caret line actions) reports every piece instead of one
range from its first to its last piece, which would push every place in between to the end. The
undo entry of such an edit keeps the pieces (`UndoEntry.pieces`), so undo reports the inverse
pieces and redo the pieces again; a single-range entry reports as before. A recorded change adds
one place at piece `recordIndex` (the primary caret's piece; 0 for a non-caret edit). A change on
the same file and the same line as the newest place replaces that place, so typing a word is one
place; lines are computed from the file's current text when they are compared, never stored, so
inserting lines above a place does not break the rule. At most 50 places are kept,
the oldest dropped. Every change, recorded or not, shifts the places of that file: a place after
the replaced range moves by `newLength - oldLength`, a place inside it moves to the range's new
end. `⇧⌘⌫` goes to the newest place, or to the one before it when the caret is already on that
file and line; pressing it again keeps walking back, and a new recorded edit starts the walk from
the newest place again; walking past the oldest place shows `No edit location` and starts over. The jump opens the file, puts the caret at the place (one history entry,
so `⌘[` comes back) and hands the editor the keyboard. A file that no longer exists shows
`File not found: <relative path>`, its places are dropped and the walk stops there; with no place the status shows
`No edit location`. Differences: IntelliJ also has Next Edit Location; places do not survive a
restart.

Decision N3: Show Usages is a popup over the same search `⌥F7` runs, and `⌥F7` is unchanged.
`⌥⌘F7` takes the identifier at the caret through the same helper `findUsages` uses
(`identifierAt`, whole word, case-sensitive) and scans with `FindInPathScanner.scan` on
`Dispatchers.Default`, over the same files and editor texts as the Find window, up to
`FindInPathScanner.MAX_HITS` (200). The popup (`popupPanel`, at `DesignTokens.popupTop`, scrim like
the navigation popups) has a header `Usages of <name>` with the count (`200+` when cut, `Searching…`
while it runs), and one row per hit: the trimmed line with the match in `accentOnPopupPlate` and
`File.kt:line` at the right. ↑/↓ move, ↵ / `F4` / `⌘↓` open the hit like a Find window row (one
history entry, keyboard to the editor), `⌘↵` closes the popup and opens the same `Usages of <name>`
tab `⌥F7` opens (`openUsagesFindWindow`, which `findUsages` itself calls), Esc closes. The list
scrolls the selected row into view like Search Everywhere. With no identifier at the caret the status shows
`No identifier at the caret`. Differences: the popup sits at the top, not at the caret; no scope
or filter settings, no grouping, no "show more" past 200 (the Find window keeps 1000).

Decision N4: Highlight Usages in File keeps its ranges in `TextFileEditor.usageHighlights` and
draws them as background spans merged with the find bar's spans only when there are any.

| Option | How the ranges are drawn | Frame path |
|---|---|---|
| A (rejected) | write them into `searchSpans` | unchanged, but the find bar rewrites `searchSpans` on every query and erases them |
| B (adopted) | a separate `usageSpans` list; the transformation receives `searchSpans` itself when it is empty and `searchSpans + usageSpans` (remembered) otherwise | unchanged while empty |
| C (rejected) | a `drawWithContent` overlay like the problem underlines | a new modifier and its own row math |

`⇧⌘F7` finds the identifier at the caret in the selected file (whole word, case-sensitive, the same
matcher the Find window uses) and highlights every match in `DesignTokens.accent` at the find bar's
match alpha; the status shows `N usages of <name> · ⌘G next · ⇧⌘G previous · Esc clears`. While the
editor has highlights, `⌘G` / `⇧⌘G` move the caret to the next / previous highlight start, wrapping
at the ends (`jumpTo`, one jump each), before the find bar's own `⌘G`. Esc clears them along with
what it cleared before; any text change clears them; `⇧⌘F7` with the caret on a highlighted match
of the same name, or right after one, clears them. Clearing also clears the usages status line, unless another action
has written a status since. The colour follows the appearance (it is recomputed after a light/dark
switch). With no identifier at the caret the status shows `No identifier at the caret`. Differences: IntelliJ tells read and write usages apart by colour;
the matches are textual, so a same-named local in another scope is highlighted too.

Decision N5: the navigation bar is the bottom path capsule, made keyboard-driven.

| Option | Where | Files | IntelliJ | Frame path |
|---|---|---|---|---|
| A (adopted) | `⌘↑` / `⌥Home` turn the bottom path capsule into segments; the selected folder's children open in a popup above it | App.kt capsule + nav bar state and popup | the New UI places the navigation bar in the status bar at the bottom, the same place | the inactive capsule draws exactly as before |
| B (rejected) | a top popup with the path and a child list | one popup | the classic toolbar look, a different place | popup only |
| C (rejected) | a permanent top row | layout + every snapshot | the classic toolbar | every frame lays out a new row |

While active the capsule shows the project root, each folder and the file as segments with the
selected one in the selection pill, and the focus context is the new modal `KeyContext.NAV_BAR`
(focus chip `Navigation`). ←/→ move between segments; ↓ or ↵ on a folder opens its children
(folders first, then files, by name, the same entries the tree lists) in a `popupPanel` list above
that segment; ↵ / `F4` / `⌘↓` on the file segment hands the keyboard to its editor. In the list,
↑/↓ move; ↵, → , `F4` or `⌘↓` on a file opens it, closes the bar and hands the editor the keyboard;
on a folder it becomes the next segment (deeper segments dropped) and its children open; ← or Esc
closes the list. Esc on the segments closes the bar and hands the keyboard back to the editor.
With no file open the bar starts with the root segment. A click on a folder segment selects it and opens
its list (a file segment is only selected); a click outside closes the bar, and so does a change of
the selected editor (a tab click), which then gets the keyboard. While active the capsule never
grows past the room the row leaves beside the status capsules: its segments scroll sideways and
the selected one is kept in view. Every segment's position is recorded by path, and the list sits
at the selected segment's on-screen x, pulled left to stay inside the window. The list does not
take focus; the capsule keeps the keyboard and the list takes clicks. Differences: no file actions in the bar's menus, no
member segments for the file, and a click on the inactive capsule does nothing.

Decision N6: keys and Edit Source by context. Edit Source in the editor goes to the declaration at
the caret (`goToDeclaration`, like `⌘B`); in a list tool window it opens the selected row like ↵
(the dispatch returns `false` there and `ListToolWindow` handles `F4` and `⌘↓`, so `⌘↓` no longer
moves the selection down); in the new popups and the navigation bar it is ↵'s alias; with the tree
or nothing focused it hands the keyboard to the selected editor. Chosen from the Search Everywhere
Actions tab (focus still `SEARCH_POPUP`), Edit Source goes to the declaration and the navigation
bar opens. `F4` and `⌥Home` are not taken from the terminal, the Run window or a text field: the
dispatch returns `false` in `TERMINAL`, `RUN_WINDOW`, `FIND_BAR` and `EDITOR_FIELD`, so the key
reaches the focused component. The three new popups and the bar are part of `popupOwnsFocus`. In the editor
`⌘↑` / `⌘↓` keep moving the caret to the document start / end (Compose's macOS text mapping), so
`SHOW_NAV_BAR` and `EDIT_SOURCE` are bound to `⌘↑` / `⌘↓` only in `LIST_WINDOW`, and the editor uses
`⌥Home` / `F4`. Binding them in GLOBAL as IntelliJ does would replace the caret move in the editor
(IntelliJ moves to the document start with `⌘Home`), which is a decision left to the user.

## Hierarchy navigation

Five IntelliJ hierarchy actions over supertypes, overrides, declared types and calls. stride has
no compiler, so each action states what it treats as certain and what it leaves out.

| Action | `ActionId` | IntelliJ id | Key | Context |
|---|---|---|---|---|
| Go to Implementation | `GOTO_IMPLEMENTATION` | `GotoImplementation` | `⌥⌘B` | EDITOR |
| Go to Type Declaration | `GOTO_TYPE_DECLARATION` | `GotoTypeDeclaration` | `⇧⌘B`, `⌃⇧B` | EDITOR |
| Go to Super Method | `GOTO_SUPER_METHOD` | `GotoSuperMethod` | `⌘U` | EDITOR |
| Type Hierarchy | `TYPE_HIERARCHY` | `TypeHierarchy` | `⌃H` | EDITOR |
| Call Hierarchy | `CALL_HIERARCHY` | `CallHierarchy` | `⌃⌥H` | EDITOR |

None of the six strokes had a binding before (`KeyStroke.parse` ignores modifier order; the only
`B` and `U` bindings were `⌘B` and `⇧⌘U`). IntelliJ's macOS keymap gives the Hierarchy tool window
(`ActivateHierarchyToolWindow`) no stroke, so stride adds none.

```kotlin
// stride.app.navigation
class NavigationTargets(val title: String, val targets: List<Declaration>)
fun goToImplementation(state: AppState): Boolean
fun goToTypeDeclaration(state: AppState): Boolean
fun goToSuperMethod(state: AppState): Boolean
fun showTypeHierarchy(state: AppState): Boolean
fun showCallHierarchy(state: AppState): Boolean

// stride.app.hierarchy
data class HierarchyLocation(val path: Path, val offset: Int)
class HierarchyNode(
    val text: String,
    val detail: String? = null,
    val location: HierarchyLocation? = null,
    val iconName: String? = null,
    val accent: Boolean = false,
    val expanded: Boolean = false,
    val children: (suspend () -> List<HierarchyNode>)? = null,
)
class HierarchyTree(val title: String, val note: String?, val roots: List<HierarchyNode>)

// AppState
var navigationTargets: NavigationTargets?
val hierarchyTree: HierarchyTree?
fun openHierarchy(tree: HierarchyTree)
```

The data layer (`DeclarationParser.outline`, `TypeGraph`, `TypeReferences`, `CallSites`) is in
`docs/api-search.md` 「Declarations and the type graph」.

Decision H1: the supertype, override and import data come from the declaration index, extended.

| Option | Source | False positives | Files | Contract | Frame path |
|---|---|---|---|---|---|
| A (adopted) | `DeclarationParser` also records supertypes, `interface`, `override`, the name column and the file's imports; `TypeGraph` resolves names through imports and packages | a supertype resolves only through an import, the package or a star import, so a same-named class elsewhere is not linked; a header the scanner cannot read loses an edge (a miss, not a wrong link) | `:search` (parser, index, three new files) + app actions | trailing fields with defaults on `Declaration` and `ParsedDeclaration`, a new `DeclarationIndex.imports`: additive | index thread only; queries run when the action fires |
| B (rejected) | a new language bean extension point (`stride.lang.hierarchyProvider`) that reads one file and returns its types and calls, plus an app service that walks the project and calls it | same parsing quality, but the resolution still needs every file's imports | `:lang` contract + descriptor + Kotlin plugin + a second project walk in app | new extension point (additive) | a second walk of every file on its own thread |
| C (rejected) | lex every project file with `KotlinLexer` when the action fires | same as A for Kotlin | app only | none | a full project scan per action, growing with the project |

A keeps one walk and one incremental update (`VFS_CHANGES`) for both Kotlin and Java, which the
existing scanner already reads; B has no Java at all (no Java language plugin) and duplicates the
walk; C pays the whole scan on every key press. B can still be extracted later if a third language
needs hierarchies: it would be an additive extension point. The new parser fields must leave the
existing ones (`name`, `kind`, `line`, `container`, `isPrivate`) unchanged for every input, since
Search Everywhere, `⌘B`, the ⌥↩ import candidates and the Structure window read them. Two
deliberate exceptions were made later: a class body `{` on a later header line, and a member written
on the class line or after a closing brace, now yield the member with its container where the
earlier parser lost it — added declarations, never a changed existing one. A third exception does
change an existing declaration: a Kotlin extension property `val Config.size` is now named `size`,
where the earlier parser named it after its receiver (`Config`), which Search Everywhere then listed
as a property called `Config`.

Decision H2: certainty. The same criterion that rejected unresolved-symbol problems (「Problems」)
decides here: a judgement shown without being asked must be certain, because a wrong one
mislabels correct code; a list the user asked for may hold candidates when every row shows where
it comes from and the list says how it matched. So stride jumps directly only to exactly one
certain target, opens a chooser (or the Hierarchy window) for candidates, says so in the status
bar when it cannot know, and every statement of absence names its scope (`in project sources`):
library, JDK and standard library types are not indexed. The declarations the hierarchy actions
search are the index snapshot with every open editor's text replacing its file, so a declaration
that is not saved yet takes part and a jump into an edited buffer lands on that buffer's lines.

Decision H3: Go to Implementation and Go to Super Method share the `⌘B` chooser.

- The target is the declaration whose name holds the caret (`DeclarationParser.outline`), else
  the identifier at the caret resolved as a type (`TypeGraph.resolveType`) or, failing that, as a
  call (`TypeGraph.resolveCallee`), which may give several candidates.
- Implementation: `TypeGraph.implementations` of every target. One result opens at its name
  (`openFileAtColumn`), several open `TargetChooserPopup` titled `Choose Implementation of <name>
  (<n> found)` with rows `<Container>.<name> — <path>:<line>`, none reports `No implementations of
  <name> found in project sources`.
- Super Method: the member around the caret (`memberAt`). A function or property uses
  `TypeGraph.superMembers`; a Kotlin member without `override` reports `<name> does not override a
  method`; no project result reports `No super method of <name> in project sources`. In a class
  outside any member, its resolved supertypes are the targets (`No supertype of <name> in project
  sources` when none resolves). A caret inside a constructor or an `init` block counts as in the
  class outside any member and takes the supertypes branch: the parser records those as outline
  members, not as declarations. Outside any class: `Place the caret in a class or function`.
- The chooser is `TargetChooserPopup` again (`navigationTargets`), not the Show Usages popup:
  that popup is typed to matched lines (`FindInPathHit`) and these rows are declarations, which is
  also how IntelliJ's chooser looks. It is part of `popupOwnsFocus`; Esc hands the keyboard back.
- Differences: no gutter icons, no implementation preview, overloads are not told apart (a
  Kotlin `override` of the same name in a subtype counts for every overload of that name).

Decision H4: Go to Type Declaration only follows a type the text states.

- The declaration of the identifier: the nearest in-scope local or parameter
  (`TypeReferences.localDeclaration`), else a member named so in the enclosing classes and their
  supertypes, else a visible top-level property or function; a class name is its own type.
- Its type: `TypeReferences.declaredType`. An explicit type resolves from the declaration's own
  file; a constructor call `Foo(...)` counts only when `Foo` resolves to a project class and no
  visible top-level function or property is named `Foo` (a property can be invoked through its
  `invoke` operator).
- Anything else shows the reason in the status bar (`Cannot infer the type of <name> without a
  compiler; declare it explicitly`), and a type that does not resolve shows `<Type> is not in
  project sources`. A candidate list of every possible type was rejected: it would be the whole
  index, and a wrong direct jump is the false positive H2 forbids.
- For `List<Foo>` the type is `List` (outside the project), as in IntelliJ; type arguments are
  not offered.

Decision H5: Type and Call Hierarchy open a `Hierarchy` tool window, not a popup.

| Option | Where | Navigating several nodes | IntelliJ | Files |
|---|---|---|---|---|
| A (adopted) | a `ListToolWindow` in the bottom panel, the tree drawn by `indent` | the tree stays while rows open in the editor | Hierarchy is a tool window | one window + state |
| B (rejected) | a `popupPanel` popup | the popup closes on every jump, the tree is rebuilt each time | no popup form | one popup + a `PopupCase` |

- The window (`hierarchy`, `Hierarchy`, `LIST_WINDOW`) is registered on the first `openHierarchy`,
  after the other windows, so the tab strip and the existing snapshots do not change until a
  hierarchy is opened. Each action replaces the shown tree (IntelliJ keeps a tab per hierarchy).
- A header shows the title and the note. → expands a collapsed node (loading its children on a
  background dispatcher, with a `Searching…` row meanwhile) or moves to its first child, ← collapses
  an expanded node or moves to the parent, ↑/↓ move, ↵/`F4`/double click opens the node's location
  and keeps the window, Esc hands the keyboard to the editor and ⇧Esc hides the window, as in the
  other list windows.
- Type Hierarchy: one root per class the name at the caret resolves to. A single root opens
  itself; several roots (title `Type Hierarchy of <Name> (<n> classes)`) start collapsed, and each
  root's `path:line` detail says where it came from. A `Supertypes` node holds the resolved
  supertypes upward (a supertype outside the project is a leaf marked `outside project sources`),
  a `Subtypes` node the subtypes downward. Both are expanded; the graph is in memory. Every level
  keeps at most 200 children and ends with `… <n> more` when there are more.
- Differences: no scope, sort or `Base on this` controls, no separate supertypes-only or
  subtypes-only views, no Method Hierarchy (`⇧⌘H`).

Decision H6: Call Hierarchy matches calls by name and visibility.

- Callers: `CallSites.find` over every project Kotlin and Java file (the open editor's text when
  the file is open), kept when `TypeGraph.mayCall` allows it, grouped by the member around each
  call (`memberAt`). A caller node shows `<Container>.<member>`, its path and line, and
  `<n> calls` when there are several; it opens at its first call and expands to its own callers.
  A call outside every member (a script's top level, for example) is a row named after its file
  and does not expand. A member already on the path is marked `(recursive)` and does not expand.
- Callees: `CallSites.inRange` over the member's own range, each resolved with
  `TypeGraph.resolveCallee`; calls that reach no project declaration (library calls) are left out.
  A callee opens at its declaration and expands to its own callees.
- The root is the member. A call that resolves to several declarations opens one root per
  declaration (title `Call Hierarchy of <name> (<n> declarations)`) and starts collapsed; a single
  root opens itself. `Callers` is expanded, `Callees` loads on →. Each expansion keeps at most 200
  children and ends with `… <n> more` when there are more.
- The note says how the calls matched: `Calls are matched by name and visibility`, plus
  `<n> declarations share this name` when more than one project declaration is named so.
- Only-unique-name matching was rejected: most calls go through a receiver (`service.process()`),
  so it would hide real callers without saying so; a candidate row shows its call line, which costs
  a glance, unlike an asserted problem. An unqualified call inside an extension function or
  property is judged as a member of its receiver's type, and an unqualified call inside a lambda
  that resolves to nothing in its own scope stays a candidate (the subject's type of `with` and
  `apply` is not read). Calls through an unrelated receiver class are still left out by `mayCall`.
- Differences: no scope filter, no `Base on this method`, no usage preview pane; the root cannot
  switch between callers and callees, both are children of it. A supertype of an object expression
  (an anonymous class) is not read as an implicit receiver.

### Messages

Every status line, popup title, window title, note and row label the five actions and the
Hierarchy window write, and when each one appears. `<name>` is the identifier or declaration name
the action read, `<n>` a count and `<Type>` the type as it was written.

| Message | When it appears |
|---|---|
| `Indexing… try again in a moment` | any of the five actions before the first declaration scan finishes |
| `No identifier at the caret` | Go to Implementation with no declaration name under the caret and no identifier; Go to Type Declaration with no identifier |
| `Cannot find the declaration of <name>` | Go to Type Declaration when the name resolves to no declaration, and when a resolved declaration's type list is empty |
| `Choose Implementation of <name> (<n> found)` | Go to Implementation chooser title when more than one implementation resolves |
| `No implementations of <name> found in project sources` | Go to Implementation when nothing implements the class or member |
| `Choose Super Method of <name>` | Go to Super Method chooser title when the overridden member has several candidates |
| `Choose Super Class of <name>` | Go to Super Method chooser title when the enclosing class has several project supertypes |
| `No super method of <name> in project sources` | Go to Super Method when no project supertype declares a member of that name |
| `No supertype of <name> in project sources` | Go to Super Method in a class when no supertype is a project class |
| `<name> does not override a method` | Go to Super Method on a Kotlin function or property without `override` |
| `Place the caret in a class or function` | Go to Super Method outside every class and member |
| `Choose Type Declaration of <name>` | Go to Type Declaration chooser title when the declared type or the name resolves to several classes |
| `<Type> is not in project sources` | Go to Type Declaration when the stated type resolves to no project class |
| `Cannot infer the type of <name> without a compiler; declare it explicitly` | Go to Type Declaration when the text does not state the type |
| `Place the caret in a class` | Type Hierarchy when neither the identifier nor the enclosing classes give a class |
| `Place the caret in a function` | Call Hierarchy when neither the identifier, the call under the caret nor the enclosing member give a function |
| `Type Hierarchy of <name>` | Type Hierarchy window title with one root |
| `Type Hierarchy of <name> (<n> classes)` | Type Hierarchy window title with several roots |
| `Call Hierarchy of <name>` | Call Hierarchy window title with one root |
| `Call Hierarchy of <name> (<n> declarations)` | Call Hierarchy window title with several roots |
| `Types outside project sources are not expanded` | Type Hierarchy note under the title |
| `Calls are matched by name and visibility` | Call Hierarchy note under the title |
| `Calls are matched by name and visibility; <n> declarations share this name` | the same note when more than one project declaration, class or symbol, has the root's name |
| `Supertypes`, `Subtypes` | the bundles under a type root |
| `Callers`, `Callees` | the bundles under a call root |
| `outside project sources` | the detail of a supertype that resolves to no project class |
| `(recursive)` | a node whose declaration is already on the path; it does not expand |
| `… <n> more` | a level with more than 200 children, or more than 20 roots |

## Refactorings

Four IntelliJ refactoring actions that change code. stride has no compiler, and a wrong
refactoring breaks code without a sign, so each refactoring runs only when the text proves the
result means what the code meant and otherwise refuses with a one-line reason in the status bar.

| Action | `ActionId` | IntelliJ id | Key | Context |
|---|---|---|---|---|
| Refactor This | `REFACTOR_THIS` | `Refactorings.QuickListPopupAction` | `⌃T` | EDITOR |
| Introduce Variable | `INTRODUCE_VARIABLE` | `IntroduceVariable` | `⌥⌘V` | EDITOR |
| Extract Method | `EXTRACT_METHOD` | `ExtractMethod` | `⌥⌘M` | EDITOR |
| Inline | `INLINE` | `Inline` | `⌥⌘N` | EDITOR |

None of the four strokes had a binding before: every `bind(` string was compared through
`KeyStroke.parse`, which ignores modifier order, and the editor's own key handlers take `⌘V` only
without `⌥`. Rename (`⇧F6`) stays the existing text-level rename and appears in Refactor This.

```kotlin
// stride.app.refactor
fun openRefactorThis(state: AppState, keymap: Keymap = Keymap.forPlatform(KeyRouter.isMacOs())): Boolean
fun startRefactoring(state: AppState, kind: RefactoringKind): Boolean

internal sealed interface RefactorThisEntry {
    data object Rename
    class Prepared(val kind: RefactoringKind, val step: RefactoringStep)
}
internal class RefactorThisRequest(val editor: TextFileEditor, val preparedText: String,
                                   val preparedSelection: TextRange,
                                   val rows: List<ChooserRow<RefactorThisEntry>>)
internal class RefactoringChooseRequest(val editor: TextFileEditor, val kind: RefactoringKind,
                                        val preparedText: String, val preparedSelection: TextRange,
                                        val step: RefactoringStep.Choose,
                                        val restoreHighlights: List<IntRange>)
internal class RefactoringNameRequest(val editor: TextFileEditor, val kind: RefactoringKind,
                                      val preparedText: String, val preparedSelection: TextRange,
                                      val step: RefactoringStep.AskName) {
    var errorMessage: String?                    // the last rejected name's reason
}
internal fun prepareWithProviders(editor: TextFileEditor, kind: RefactoringKind): RefactoringStep
internal fun chooseRefactorThis(state: AppState, entry: RefactorThisEntry): Boolean
internal fun chooseRefactoringOption(state: AppState, option: RefactoringOption): Boolean
internal fun selectRefactoringOption(state: AppState, option: RefactoringOption)
internal fun closeRefactoringChoose(state: AppState)
internal fun submitRefactoringName(state: AppState, name: String): Boolean
internal fun closeRefactoringName(state: AppState)
@Composable fun RefactoringNamePopup(title: String, suggested: String, error: String?,
                                     onSubmit: (String) -> Unit, onClose: () -> Unit)

// stride.app.AppState
internal var refactorThis: RefactorThisRequest?
internal var refactoringChoose: RefactoringChooseRequest?
internal var refactoringName: RefactoringNameRequest?
internal var prepareRefactoring: (TextFileEditor, RefactoringKind) -> RefactoringStep
                                                 // defaults to ::prepareWithProviders; tests replace it

// stride.app.navigation
data class ChooserRow<T>(val text: String, val value: T, val hint: String? = null)
@Composable fun <T> TargetChooserPopup(title: String, rows: List<ChooserRow<T>>,
                                       onChoose: (T) -> Unit, onClose: () -> Unit,
                                       onSelect: (T) -> Unit = {})

// stride.app.editor.TextFileEditor
fun applyEdits(edits: List<TextEdit>, caret: Int? = null): Boolean   // one undo step; caret in the edited text
```

### R1 Where refactorings live

A refactoring needs a name from the user, edits at several places and one undo step. Three
places were compared.

| | A: widen `stride.lang.intentionProvider` | B: new `stride.lang.refactoringProvider` | C: app service over the `:search` scanners |
|---|---|---|---|
| Cost of a wrong edit | the language plugin's lexer sees every token, but ⌥↩ computes all edits when the menu opens and has no way to ask for a name | the Kotlin lexer (`codeTokens`) unfolds string templates, so `$x` and `${x}` are references and comments are never code | `sanitizeSource` blanks string templates: Inline would delete `val x` and leave `"$x"` reading another `x` without a compile error. Teaching it templates changes the scanner under every hierarchy and call judgment of the tenth round |
| Kotlin and Java | Kotlin | Kotlin; Java refuses because no Java language plugin exists, as with intentions and problems | the declaration scanner reads Java, but neither language has a statement grammar there |
| Files changed | `Intention.kt`, the ⌥↩ popup and its ordering, the Kotlin plugin, the app | a new `:lang` file and one descriptor line, the Kotlin plugin, the app | new `:search` files, the app |
| Extension point contract | `IntentionAction` is a data class; a field for name-dependent edits changes its constructor (binary break, major), and ⌥↩ would list refactorings | additive, minor | unchanged, minor |
| Frame path | unchanged | unchanged | unchanged |

**B is the contract.** The tenth round rejected a language extension point for hierarchy
navigation because hierarchy reads many files through the declaration index; a refactoring reads
one document's tokens, which the language plugin owns. The name check lives in the provider
because only the provider knows which names the code already uses. The contract is in
`docs/api-lang.md` 「Refactoring providers」:

```kotlin
// stride.lang
enum class RefactoringKind(val title: String) { INTRODUCE_VARIABLE, EXTRACT_METHOD, INLINE }
class RefactoringContext(val text: String, val selectionStart: Int, val selectionEnd: Int, val fileName: String)
data class RefactoringEdit(val edits: List<TextEdit>, val caret: Int, val message: String? = null)
sealed interface RefactoringStep {
    data class Refused(val reason: String)
    class Choose(val title: String, val options: List<RefactoringOption>)
    class AskName(val title: String, val suggested: String, val complete: (String) -> NameCheck)
    class Apply(val edit: RefactoringEdit)
}
class RefactoringOption(val text: String, val highlights: List<IntRange> = emptyList(), val next: () -> RefactoringStep)
sealed interface NameCheck { data class Invalid(val message: String); class Valid(val edit: RefactoringEdit) }
interface RefactoringProvider { fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep? }
```

The Kotlin plugin registers `stride.lang.kotlin.refactor.KotlinRefactorings`. A `.java`, `.json`
or plain file refuses with `<Refactoring> is not available for .java files`.

### R2 When a refactoring runs

A refactoring refuses unless the text proves four things: no expression runs more often, less
often, later or in another order when it has calls or reads that may change; no name starts
referring to another declaration; every type it writes is written in the code; and the edited
text has the statements and brackets it had. Types follow the tenth round's H4: a written type,
a constructor call or a literal, never an inferred expression type. The words below are used by
R3 to R5.

- **Tokens.** Kotlin lexer tokens with string-template code unfolded. String text and comments are
  never code. When the code brackets `()`, `[]` and `{}` of the whole file do not pair up, every
  refactoring refuses with `Brackets do not match in this file`.
- **Blocks.** Each `{` opens one of: a function body (after a `fun` header without `=` before the
  brace, a `get()`/`set(…)` accessor or a secondary `constructor(…)`), an initializer (`init`), a
  control body (after the header of `if`, `for`, `while`, `catch`, after a `when` branch `->`, or
  after `else`, `try`, `finally`, `do`), a when body (`when (…) {`, `when {`), a class body (a
  `class`, `object`, `interface` or `object :` header) or a lambda (every other `{`, anonymous
  `fun (…) {` included). Function bodies, initializers, control bodies and lambdas are statement
  blocks; `for`, `while` and `do` bodies are loop bodies.
- **Statements.** A statement block splits at `;` and at newlines at its own depth. A newline does
  not split when the line ends with a token that cannot end an expression (a binary operator, `.`,
  `?.`, `,`, `(`, `[`, `=`, `->`, `&&`, `||`, `?:`, `..`, `as`, `is`, `in`, `::`, `:`; a `>` that
  closes type arguments or type parameters and a `?` after a type are type syntax, not operators,
  so `val targets: List<Declaration>` and `val name: String?` end their line; the `>` of a function
  type's `->` inside type arguments closes nothing, so `List<(A) -> B>` ends its line too), when the next
  line starts with `.`, `?.`, `?:`, `&&`, `||`, `else`, `catch`, `finally`, or with `while` after a
  `do` body, or when the line holds only annotations. It does not split either when the line ends
  with a name that is not a keyword or soft keyword right after a token on the same line that ends
  an operand (a name that is not a modifier, a label, `by` or `where`; a literal; a string end; a
  `)` other than one closing an `if`, `for`, `while` or `catch` header; `]`; `}`; `this`; `!!`):
  Kotlin reads that name as an infix call (`a to`, `0 until`) and takes its right side from the
  next line. Newlines inside `()`, `[]` and nested `{}`
  never split. These shapes also keep one statement, all on the joining side: a control body
  without braces (a newline after `if (…)`, `for (…)`, `while (…)`, `catch (…)`, `else`, `try`,
  `do`, `finally`), a next line starting with `->` or `:`, and a next line starting with `{` when
  the line ends with a call's `)`, a control or declaration header (`if (…)`, `while (…)`,
  `when (…)`, `fun f()`, `class A`, `else`, `try`, `finally`, `do`, `init`, also when the header
  ends with its type: `fun f(): String?`, `fun f(): List<List<Int>>`, `class A : B<C>`), a name
  after `.` or `?.`, `->`, `>` (also merged into `>>`, `*>` or `?>`) or a token that cannot end an
  expression (Kotlin reads the block as a trailing
  lambda or as the body), and text inside strings and templates. After a literal, a string, `]`,
  `!!`, a grouping `)`, a lambda or a bare name a next-line `{` starts a statement of its own, as
  Kotlin 2.4.21 reads it. A member name before a next-line `{` joins although Kotlin joins it only
  when the member has a function type: the model cannot see types, and the other reading is a
  lambda that is never invoked. The trailing `while (…)` of `do … while` ends its statement,
  and a lambda's parameter list with its `->` is not a statement.
- **Locals and references.** Locals are `val`/`var` (single and destructured), function
  parameters, lambda parameters (`it` for a lambda without an arrow), `for` variables, `catch`
  parameters, `when (val x = …)` and local `fun`, `class` and `object` names. A reference is an
  identifier token that is not after `.`, `?.` or `::`, not a declaration name, not a named
  argument (`name =` inside call parentheses) and not a label. It resolves to the innermost local
  of that name whose scope holds it; otherwise it is non-local (a member, a top-level declaration
  or an import). A modifier word (`value`, `data`, `open`, `inline`, …) is a modifier only before
  a declaration keyword, another modifier or an annotation, or as a parameter or type-parameter
  modifier (`vararg x:`, `reified T`); anywhere else, as in `value to other`, it is a name.
- **Unread spellings.** The model reads tokens, not a parse tree, so a refactoring that renames,
  removes or adds a name first looks at every code token spelled like it. A token the model cannot
  place (not a reference it resolved, a declaration, a member name after `.`, `?.` or `::`, a
  named argument, a label, a modifier, or `by` after a declared name or type in a delegated
  declaration) refuses the refactoring instead of being skipped. Extract
  Method moves names into a new scope, so a name token in its selection that the model cannot place
  refuses it with the same message. Letters attached to a number (`1u`, `2UL`, `1f`) belong to the
  literal and are no name, and a name directly followed by `@` is a label, so the code after it
  (`loop@ println(x)`) is code, not an annotation.
- **Static types.** Kotlin picks overloads, extension functions and operator functions by each
  expression's static type, and a smart cast narrows the static type of a local, of a member or
  top-level `val` and of a chain of them (`h.q`) after a check. The model sees neither, so a
  refactoring refuses when it would move or copy a read to a place where that type can differ. It
  can differ when the function holds a smart-cast shape that narrows the read between the two
  places. A shape has an operand, the expression at its place: `<e> is`, `<e> !is`, `<e> as`,
  `<e>!!`, `<e> ?:`, an operand of `==`, `!=`, `===` or `!==` (either side, whatever the other
  side is: `x == y` and the `else` of `x != "a"` narrow a nullable `x`), `when (<e>)`,
  `<e>.isNullOrEmpty()`, `<e>.isNullOrBlank()`, `<e>` inside the parentheses of `require`,
  `check`, `requireNotNull`, `checkNotNull` or `assert`, and the left side of `=` when it is a
  local (a `var`, or a `val` declared without an initializer: Kotlin narrows it to the assigned
  value's type). A shape narrows the leading chain of its operand, names joined by `.` or `?.` with
  `this.` dropped, when the operand is that chain, and every part of that chain before a `?.`
  (`x?.length != null` and `x?.isEmpty() ?: true` narrow `x`). A read is narrowed when its own
  chain starts with a narrowed chain: `state.focus == k` narrows `state.focus.name`, not
  `state.size`. A local resolves by the model's references, any other name by its spelling. An
  equality shape does not narrow a local whose written type is not nullable and is not a type
  parameter declared in this file, since `==` only removes nullability (`x: Any` stays `Any`
  after `x == "a"`). A declaration with a written type and an initializer is not a shape
  (`val b: Any = "t"` keeps `Any`). This is the list Kotlin 2.4.21 was measured to narrow on.
  Contracts of other functions and a narrowing outside the list are not seen: such a read can
  still change its overload, the one gap of this rule. The refusals below name the narrowed
  chain as `<x>` (`h.q may be smart-cast at line 4; …`) and the shape's line as `<n>`.
- **Calls and effects.** A call is a `(` right after an identifier, `>`, `)` or `]`, a `[` right
  after an identifier, `)` or `]`, or a lambda `{` right after an identifier or `)`; constructor
  calls count, operator overloads written as operators cannot be seen. Effects are calls,
  assignments (`=`, `+=`, `-=`, `*=`, `/=`, `%=`) and `++`/`--`. A call takes effect at its closing
  bracket, an assignment at the end of its statement, `++`/`--` at the token.
- **Stable and read-only expressions.** A stable expression holds only literals (strings whose
  templates are stable), `this`, `null`, `true`, `false`, references to local `val`s and
  parameters, the operators `+ - * < > <= >= == != === !== && || ! ?: ..` and grouping
  parentheses. `/` and `%` can throw, so they are not stable. A read-only expression may also read
  `var`s and members through `.`, `?.` and `this.`; it has no calls, no `!!` and no indexing.
- **Conditional position.** A place X is conditional in its statement when the tokens from the
  statement start to X, leaving out bracket groups closed before X, hold `&&`, `||`, `?:`, `?.`,
  `else`, `->` or a when body `{`, or an `if`, `while`, `for`, `do`, `try`, `catch` or `finally` whose
  header or body holds X. Three places are not conditional: the parenthesized condition of the
  statement's first `if`, a `when (…)` subject and the iterable of `for (… in …)`. Inside those
  three the same markers, counted from the opening parenthesis, still make X conditional, so
  `foo()` in `if (a && foo())` is conditional. A `while` condition runs on every turn and stays
  conditional.
- **Receiver scopes.** A lambda can have a receiver, and an object expression or class body has
  one, so inside them `this`, `super` and every name that does not resolve to a local (members,
  top-level and imported names, called or read) may mean another declaration. Locals win over
  receiver members, so local names keep their meaning. Code holding `this`, `super` or such a name
  never moves into a lambda, object expression or class body that does not already hold it; the
  refusal reads `<y> could mean something else at line <n>`, with `<y>` the first such name.
- **Runs before.** The effects in X's statement that take effect before X: calls whose closing
  bracket comes before X and `++`/`--` before X. A call whose parentheses hold X runs after X.

### R3 Introduce Variable

| Case | Result |
|---|---|
| A selection | the trimmed selection is the expression; it must pass the expression rules below, otherwise `Select an expression`, which also answers a selection starting in a string or a comment (template code excluded) and a caret with no candidate. A selection of only whitespace counts as no selection |
| No selection | candidates from the caret token, innermost first: the postfix chains holding it (`a`, `a.b`, `a.b.c()`), each enclosing whole call argument or parenthesized expression, then the statement's whole value (right of `=`, after `return` or `throw`, or the whole expression statement); only candidates passing the rules stay. One is used directly; several open the `Expressions` chooser with the candidate highlighted and its text on one line, cut to 60 characters and `…` |
| Expression text in messages | `<expr>` is the expression on one line, cut to its first 37 characters and `...` past 40 |
| Expression rules | the first token starts an operand (a name that is a reference or a member name, a literal, a string, `(`, `this`, `super`, `if`, `when`, `try`, `object`, a lambda `{`, or a prefix `!`, `++` or `--`, and `-` or `+` only when the token before the selection does not end an operand) and the last token ends one (a name, a literal, a string end, `)`, `]`, `}`, `this`, `!!`, or a postfix `++` or `--`); `<…>` type arguments opened inside close inside, and the reverse; starts and ends on token boundaries, both outside strings or both in the code of one template; brackets inside pair up; a `(` or `[` group right after a name, `)`, `]` or `>` is a call's arguments or an index, not an expression; no declared name (the name a `val`, `var`, `fun`, `class`, `object`, parameter, destructuring, `for` or `catch` declares); no statement split, `;`, top-level `,`, `=` or `:` inside; no top-level `val`, `var`, `fun`, `class`, `object`, `interface`, `typealias`; does not start or end with an operator or with `as`, `as?`, `is`, `!is`, `in` or `!in`; does not start with `while`, `for` or `do`, or with an `if` that has no `else`; not after `.`, `?.` or `::`; not followed by `(`, `[`, a trailing-lambda `{`, `=`, a compound assignment, `++` or `--`; not a type (after `:`, `as`, `as?`, `is`, `!is`, or inside `<…>` type arguments); not inside an annotation, `import` or `package`; no `return`, `break`, `continue` or `throw` except `return@label` inside a lambda written within it |
| Not in a statement block | refuse: `Introduce Variable works inside a function body`; a function written `= expression` refuses with `Expression body: convert it to a block body first` |
| The statement shares its line | refuse: `The statement shares its line; put it on its own line first` |
| The expression is conditional (R2) | refuse: `<expr> runs only in some cases here; a variable would run it every time` |
| Not stable and something runs before it (R2) | refuse: `Code before <expr> in this statement would run after it` |
| Other occurrences | the same token sequence, whitespace and comments ignored, in the rest of the statement's block from the statement start (nested blocks included), each passing the expression rules, with every name in the same role as in the selection (a reference resolving to the same declaration, or not a reference in both), so a declared name, a named argument or a name that resolves to another local is never an occurrence. Occurrences inside a receiver scope the selection is not in follow the R2 receiver rule and are left out when the expression holds `this` or a non-local name. Stable: all of them. Read-only: those before the first effect after the statement start that do not sit, below the statement's block, inside a lambda, a local function, an object expression or class body, a loop body, or after the `while`, `do` or `for` keyword of a statement, since those run later or repeatedly. Anything else: none. With one or more, the `Occurrences` chooser offers `Replace this occurrence only` and `Replace all <n> occurrences` with every occurrence highlighted A read of the selection narrowed by an R2 smart-cast shape between the insertion point and an occurrence leaves that occurrence out; for the selected occurrence it refuses with `<x> may be smart-cast at line <n>; the variable would keep its unchecked type`. |
| Name | suggested from the expression: the last called or read member (`getName()` → `name`, a leading `get` before an uppercase letter dropped), a constructor call's class name with a lowercase first letter, `text` for a string literal, `number` for a number, otherwise `value`; made unique with 2, 3, … The `Introduce Variable` name popup checks it |
| Name check | a Kotlin identifier (`[A-Za-z_][A-Za-z0-9_]*`) not made only of `_`, which Kotlin reserves, and not a hard keyword (`Not a valid name`), and spelled by no code token other than a modifier or a member name after `.` or `?.` (a local cannot capture those) anywhere in the enclosing function, initializer or outermost lambda (`'<name>' is already used in this function`), so a new local never hides a member a later line reads |
| Result | `val <name> = <expression as written>` on a new line before the statement with its indentation; the expression and the chosen occurrences become `<name>`; the caret goes after the replaced expression; status `Introduced variable <name>` |

stride always writes `val` without a type. IntelliJ's `Declare with var` and `Specify type
explicitly` choices are not offered.

### R4 Extract Method

| Case | Result |
|---|---|
| No selection | refuse: `Select the code to extract` |
| Shape | statements (the trimmed selection starts at a statement start and ends at a statement end of one statement block, a closing `;` and comments after it included) or one expression (the R3 expression rules); otherwise `Select whole statements or one expression` |
| Enclosing function | the innermost function body holding the selection must be a `fun` declared in a class body or at the top level, and every block between it and the selection a control body or a `when` branch. A function written `= expression` refuses with `Expression body: convert it to a block body first`, also when the selection sits in a block inside that expression (a `when` branch, a lambda). This check runs before the shape check, so a class body, initializer or accessor reads `Extract Method works inside a function body`. Inside a lambda: `The selection is inside a lambda; this and it may mean something else`. Inside a local function: `The selection is inside a local function`. An initializer, accessor, constructor, class body or property initializer: `Extract Method works inside a function body` |
| Inputs | references in the selection resolving to locals of the function declared outside the selection, parameters included, in order of first use. Each needs a written type: the parameter's or local's written type, a constructor call that writes its type arguments (`Foo<Bar>(…)`) or of a class declared in this file without type parameters (`Foo(…)`), or an `Int`, `Long`, `Double`, `Float`, `Boolean`, `Char` or `String` literal, an integer past the `Int` range (decimal above 2147483647, hex or binary above `0x7FFFFFFF`) being `Long` and an unsigned literal refusing; otherwise `Cannot infer the type of <name> without a compiler; declare it explicitly`. A delegated local (`by`) runs its delegate on every read and write: `<name> is a delegated local`. A `var` from outside read or written inside a lambda, anonymous function, object expression or local function of the selection would be captured as the new function's copy: `The selection captures var <name> in a lambda`. A `vararg` parameter: `<name> is a vararg parameter`. A local function or class: `The selection uses local <name>` An input or another read of the selection narrowed by an R2 smart-cast shape in the function before the selection (a member `val` stays a member read but loses the narrowing in the new function) refuses with `<x> may be smart-cast at line <n>; the extracted function would see its declared type`. |
| Type parameters | when the function declares `<T, …>` and the selection or an input type names one: `The selection uses the type parameter <T>` |
| Outputs | locals declared in the selection and read after it in the function, and `var` locals declared before it that it assigns (`=`, compound, `++`, `--`; a compound assignment to a `val` calls `plusAssign` and is no output). Two or more: `The selection changes <a> and <b>, which are used later` (three names at most). A local function or class declared in it and used after: `<name> is declared in the selection and used after it`. A declared output needs a written type like an input |
| Exits | `return` anywhere in the selection, lambdas included, except `return@label` of a lambda or anonymous function written inside it and returns inside a local `fun` written inside it: `The selection contains return`. `break` or `continue` whose loop (the innermost one, or the labeled one) is outside the selection: `The selection contains break or continue for a loop outside it`. A `return@`, `this@` or `super@` label must name a lambda (written `label@` or the callee before it), anonymous function or loop written inside the selection: `The selection uses the label @<label> from outside it` |
| New function | `private fun <name>(<p>: <Type>, …)` with the function's extension receiver repeated (`private fun Receiver.<name>(…)`), `suspend` and `@Composable` copied and nothing else. Statements: the selected text with its common indentation removed and one indent unit added, lines that start inside a string kept as written and left out of the common indentation (the function's first body line indentation minus its own, four spaces when unknown); an output assigned from outside starts the body with `var <x> = <x>`; an output ends it with `return <x>` and the header gets `: <Type>`. Expression: `private fun <name>(…) = <expression>`, so Kotlin infers the return type |
| Value position | when the selected statements sit in a block whose value is used (an `if`, `when` or `try` used as an expression, its `catch` blocks included and its `finally` block not, followed outward through each last statement), the block's last statement must stay outside the selection unless the selection is that one expression, which is then extracted as an expression: `The selection is the block's value; extract one expression` |
| Expression type | an extracted expression becomes `= <expr>`, so Kotlin must infer its type alone: a call without a receiver and without written type arguments (`emptyList()`, `tween(300)`) may need the type expected at its place and refuses with `Cannot infer the type of <expr> without a compiler`; receiver calls and capitalized constructor calls pass. An expression of only literals and operators (`1`, `1 + 2`) or a lambda or anonymous function takes its type from its place and refuses the same way. An expression that assigns a local refuses with `The selection changes <x>; extract whole statements instead` |
| Placement | after the enclosing function's last line in the same container, with one blank line before it and the function's indentation. When that line holds code after the function's closing `}` (a one-line class), or a block comment that closes on a later line: `The function's last line holds other code; put it on its own line first` |
| Call | statements without output: `<name>(<args>)`; a declared output: `val` or `var` as declared, `<x> = <name>(<args>)`; an assigned output: `<x> = <name>(<args>)`; an expression: `<name>(<args>)` in its place |
| Name | suggested `extracted`, made unique with 2, 3, …; the `Extract Method` name popup checks it with the R3 identifier rule (`Not a valid name`) and that it is not used as any identifier in the file, modifiers such as `value` or `data` not counting (`'<name>' is already used in this file`), so a call never reaches another overload |
| Result | status `Extracted method <name>`; the caret goes to the call |

Smart casts do not carry over: a parameter keeps its declared type, and with an overload or
extension for the narrower type the call would compile and reach another function, so an input
that may be smart-cast refuses (Inputs) instead. Not offered from IntelliJ: the
signature dialog, duplicate search, several outputs returned as a pair, and extraction inside a
lambda.

### R5 Inline

| Case | Result |
|---|---|
| Target | the identifier at the caret, or a selection of one identifier. A local `val` when it resolves to one or is its declaration name; a function when it is the name of a `fun` declared in this file in a class body or at the top level, or the callee of an unqualified or `this.` call, with or without parentheses (`each { … }` passes only a trailing lambda), when exactly one function of that name is declared in this file and no local, parameter or import has that name. `when (val x = …)` subjects, `for` and `catch` variables, parameters and destructured names are not targets. Otherwise `Inline works on local variables and functions declared in this file` |
| Variable refusals | `var`: `<name> is a var; only vals are inlined`. Destructured, delegated or without an initializer: `<name> has no initializer to inline`. A written type, unless the initializer is a constructor call of exactly that type or a literal of it: `<name> declares type <T>; the inlined expression could get a different type`. No references: `<name> is never used`. A declaration of the same name in a nested scope: `<name> is declared again in a nested scope`. The declaration shares its lines (a closing `;` is part of it, and a block comment that closes on a later line is other code): `The declaration shares its line with other code`. A token spelled `<name>` in its scope that the model cannot place (R2 「Unread spellings」): `<name> appears where this refactoring cannot read it` |
| Stable initializer | every reference is replaced. Where a name in the initializer resolves to another declaration at a reference: `Inlining would make <y> refer to another declaration` |
| Other initializers | exactly one reference (`<name> is used <n> times and its initializer is not stable`); not inside a lambda, loop body, local function or object expression that does not also hold the declaration (`<name>'s initializer would run later or repeatedly`); not conditional in its statement and not inside a control body entered after the declaration (`<name>'s initializer would run only in some cases`); no effect between the end of the declaration and the reference (`Code between the declaration and the use of <name> would run before its initializer`). Local functions, written with a block or `= expression`, and every class body count as running later, property initializers included. All initializers follow the R2 receiver rule at each reference Every initializer, stable or not, refuses when a read in it is narrowed by an R2 smart-cast shape between the declaration and a reference: `<x> may be smart-cast at line <n>; the inlined expression could get a different type`. |
| Strings | a reference inside a string template takes the inlined text only on one line; an initializer or body spanning lines refuses with `The inlined code spans several lines and cannot go into a string template` |
| Substitution | the reference becomes the initializer as written, in parentheses unless the initializer is atomic (an identifier, a literal including `true`, `false` and `null`, `this`, a postfix chain of names, calls, indexing and `!!`, or one parenthesized group) or the reference fills a whole argument, a whole right side of `=`, a whole statement, the whole value after `return`, or a whole `if`, `when` or `while` condition. `$name` becomes `${<initializer>}` and `${name}` becomes `${<initializer>}` |
| Variable result | the declaration's lines are removed with their line break; status `Inlined <name> (<n> uses)`, `(1 use)` for one; the caret goes to the start of the first replaced use |
| Function body | `= <expression>`, a block holding only `return <expression>`, or, for a function without a return type or with `: Unit`, a block holding one expression statement. Otherwise `<name> has a body of several statements` |
| Function refusals | `open`, `override`, `abstract` or an interface member: `Calls of <name> may reach another implementation`. Type parameters: `<name> is generic`. An extension receiver: `<name> is an extension function`. `vararg`: `<name> has a vararg parameter`. The body calls `<name>`: `<name> is recursive`. A `return` besides the leading one, except `return@label` whose label names a lambda written in the body (`return@<name>` returns from the function itself): `<name> returns from inside its body`. A return type other than `Unit`, unless the body is a constructor call of exactly that type, a literal of it, or a comparison or logical expression for `Boolean`: `<name> declares type <T>; the inlined expression could get a different type` |
| Calls | `<name>(<args>)` or `this.<name>(<args>)`; named arguments map by name and a trailing lambda to the last parameter. Omitted arguments with defaults: `The call at line <n> uses default arguments`. A member function called from another class body: `The call at line <n> is in another class`. A statement-body function whose call is not a whole statement: `The call at line <n> uses the result of <name>` |
| Arguments | a stable argument replaces every use of its parameter. Any other argument needs exactly one use that is not inside a lambda, loop body or local function of the body and not conditional in the body (`The argument <arg> would run <n> times or only in some cases`); unused: `<arg> would no longer run`; such arguments' uses must come in argument order with no body effect (a call, an assignment, `++` or `--`) taking effect before them (`Arguments of the call at line <n> would run in a different order`) Each argument must have its parameter's type as the text shows it, since the body's calls resolve by the argument's static type once it replaces the parameter: a parameter typed `Int`, `Boolean`, `Char`, `String`, `Double` or a class declared in this file without `open`, `abstract`, `sealed`, `interface` or type parameters, not nullable, takes any argument; `Long`, `Short`, `Byte` and `Float` take any argument but one of only literals and operators without the matching suffix (`1` would become an `Int` in the body); any other parameter type takes only a reference to a local or parameter written with the same type, or a constructor call of exactly that type, otherwise `The argument <arg> may have a narrower type than <param>: <T>`. A reference argument for such a parameter narrowed by an R2 smart-cast shape before the call refuses with `<x> may be smart-cast at line <n>; the inlined expression could get a different type`. A lambda or anonymous function is neither a reference nor a constructor call, so a function-type parameter refuses it: at the call it takes the parameter's type, in the body it would infer its own (`{ 1 }` for `() -> Any` becomes `() -> Int`). A parameter whose type the model cannot read refuses with the R2 unread-spelling message. |
| Name capture | a body name that is not a parameter resolves to a local at the call, or a stable argument's name meets a declaration inside the body around its use: `<y> means something else at the call at line <n>`. A call inside a receiver scope that does not hold the declaration follows the R2 receiver rule for the body's `this`, its implicit member reads and its non-local names |
| Modes | the caret on a call: the `Inline <name>` chooser offers `Inline this call only` and, when the function is `private` and every use in the file is a call that passes, `Inline all and remove <name>`; with only the first it applies directly. The caret on the declaration: all and remove, which needs `private` (`<name> is not private; other files may call it. Put the caret on a call to inline only that call`), no `::<name>` reference (`<name> is used as a reference`), no call on another object (`<name> is also called on other objects`) and at least one call (`<name> is never used`; deleting an unused function is not inlining). A token spelled `<name>` in the file that is neither the declaration nor a use read above refuses all mode with `<name> appears where this refactoring cannot read it`. In all mode every call must pass; the first failing call's message is shown. The declaration must not share its first or last line with other code (a closing `;` and comments that close on the same line aside, a block comment that closes on a later line counting as code): `The declaration shares its line with other code`. Removal deletes the declaration with its KDoc and annotation lines and one adjacent blank line |
| Function result | each call becomes the body expression with arguments substituted and parenthesized by the variable rule, and the body itself is parenthesized by the same rule when the call does not fill one of the whole places listed there; a statement-body function replaces the call statement; status `Inlined a call of <name>` or `Inlined <n> calls of <name>`; the caret goes to the start of the first replaced call |

Not offered from IntelliJ: inlining parameters, properties, classes and type aliases, functions
declared in another file, bodies of several statements, and keeping the declaration while
inlining every call.

### R6 What the user sees

- **Refactor This.** `⌃T` opens a `TargetChooserPopup` titled `Refactor This` with the entries
  available at the caret, in this order, each showing its stroke on the right: `Rename…` (`⇧F6`,
  when `openRename` would open its popup), `Introduce Variable…`, `Extract Method…`, `Inline…`
  (when the provider's step is not `Refused`). An entry runs the same path as its stroke and
  reuses the step already prepared. With no entry the status reads `No refactorings available at
  the caret`; a read-only editor opens no popup and reads `File is read-only`. IntelliJ lists only
  applicable refactorings too; the out-of-scope ones (Change Signature `⌘F6`, Move `F6`, Safe
  Delete `⌘⌦`, Extract Parameter, Field and Constant) are not listed.
- **Choosers.** `RefactoringStep.Choose` opens the same popup with the step's title and options; a
  step with one option is followed without showing it. While an option is selected its ranges
  are drawn through `TextFileEditor.usageHighlights` (spans merged only when non-empty, as in N4)
  and cleared when the popup closes.
- **Name input: a popup, not in-place editing.** IntelliJ edits the new name in place and checks
  it after the fact with its compiler. In stride an in-place edit would be the refactored text
  plus multi-caret typing on every occurrence: the code would already be changed before the name
  could be checked, so a name that hides a member would land silently. The `RefactoringNamePopup`
  (`popupPanel` key `popup-refactoring-name`) shows the step's title, a field with the suggestion
  selected, and the `NameCheck.Invalid` message in `errorOnPopupPlate`; `Enter` with an empty
  field shows `Type a name`, `Esc` cancels.
- **Expression candidates.** Without a selection Introduce Variable offers the candidates (R3)
  instead of taking the innermost one; IntelliJ does the same, and the innermost candidate is
  often a bare name nobody wants as a variable.
- Every popup takes focus as `CONTEXT_POPUP` and is part of `popupOwnsFocus`. The actions read the
  selected editor, not `state.focus`, so they also run from the Search Everywhere Actions tab.

### R7 Applying and undo

`startRefactoring` refuses `File is read-only` before asking the provider. The app keeps the text
and the selection the step was prepared on. A popup that takes focus collapses the editor's
selection, so closing any refactoring popup with `Esc` puts the kept selection back, and applying
puts it back before the edits so that the undo step records it. When the editor's text differs
at apply time it applies nothing and shows `The file changed; run <Refactoring> again`. Otherwise it calls
`TextFileEditor.applyEdits(edits, caret)`: all edits form one undo step, `⌘Z` restores the text
and the selection from before the refactoring, and the caret lands where the provider said.
`Esc` in any popup changes nothing and restores the selection.

The frame path does not change: providers run on the key press, and highlights reuse the N4
spans.

### Refactoring messages

Messages written by the app; R3 to R5 list the provider's.

| Message | When it appears |
|---|---|
| `No refactorings available at the caret` | Refactor This with no entry |
| `File is read-only` | any of the four in a read-only editor |
| `<Refactoring> is not available for .<ext> files` | a file whose language has no refactoring provider; `this file` for a name without an extension |
| `<Refactoring> failed: <Exception>` | the provider threw; the exception is logged |
| `The file changed; run <Refactoring> again` | the text changed between preparing and applying |
| `<Refactoring> failed: the edits overlap` | `applyEdits` rejected the provider's edits, which overlap or leave the text |
| `Type a name` | `Enter` with an empty name field |

## Generate

IntelliJ's Generate menu for Kotlin and its two direct actions. Generation adds members to the
class at the caret and changes no existing text, but a new member can change which declaration an
existing call reaches, so a generator runs only when the text proves no existing call or
reference changes meaning, and otherwise refuses with a one-line reason in the status bar.

| Action | `ActionId` | IntelliJ id | Key | Context |
|---|---|---|---|---|
| Generate | `GENERATE` | `Generate` | `⌘N`, `⌃↩` | EDITOR |
| Override Methods | `OVERRIDE_METHODS` | `OverrideMethods` | `⌃O` | EDITOR |
| Implement Methods | `IMPLEMENT_METHODS` | `ImplementMethods` | `⌃I` | EDITOR |

None of the strokes had a binding: every `bind(` string was compared through `KeyStroke.parse`.
IntelliJ also binds `⌘N` and `⌃↩` to `NewElement` in the project tree; stride binds them in EDITOR
only, so the tree keeps them free. The editor field's own key handling must not see `⌃O`, `⌃I` or
`⌃↩` (a text field may read `⌃O` as a line break and `⌃I` as a tab), so the keymap consumes them.

```kotlin
// stride.app.generate
fun openGenerate(state: AppState, keymap: Keymap = Keymap.forPlatform(KeyRouter.isMacOs())): Boolean
fun startGenerate(state: AppState, kind: GenerateKind): Boolean          // ⌃O and ⌃I
internal class GenerateRequest(val editor: TextFileEditor, val preparedText: String,
                               val preparedSelection: TextRange,
                               val rows: List<ChooserRow<GeneratePrepared>>)
internal class GeneratePrepared(val kind: GenerateKind, val step: RefactoringStep)
internal class MemberChooseRequest(val editor: TextFileEditor, val title: String,   // the generator's name for messages
                                   val preparedText: String, val preparedSelection: TextRange,
                                   val step: RefactoringStep.ChooseMembers) {
    var errorMessage: String?                    // the last rejected choice's reason
}
internal fun prepareGenerateWithProviders(editor: TextFileEditor, kind: GenerateKind,
                                          types: ProjectTypes?): RefactoringStep
@Composable fun MemberChooserPopup(title: String, members: List<MemberOption>, error: String?,
                                   onSubmit: (List<MemberOption>) -> Unit, onClose: () -> Unit)

// stride.app.AppState
internal var generate: GenerateRequest?
internal var memberChoose: MemberChooseRequest?
internal var prepareGenerate: (TextFileEditor, GenerateKind) -> RefactoringStep
                                                 // defaults to the providers with the project's types
```

### G1 Where generators live

Generate needs the class at the caret, the members of its supertypes in other files and one undo
step. Three places were compared.

| | A: more `RefactoringKind` values on `stride.lang.refactoringProvider` | B: new `stride.lang.generateProvider` returning `RefactoringStep` | C: widen `stride.lang.intentionProvider` |
|---|---|---|---|
| Cost of a wrong edit | same lexer and model as B | the Kotlin plugin reads the class with the refactoring model (`KotlinCode`), and supertypes with the same model on their files' text | ⌥↩ computes every edit when the menu opens and cannot ask which members |
| Data outside the file | `RefactoringContext` reads one file by contract (`docs/api-lang.md`); adding a lookup field changes its constructor | `GenerateContext` carries `ProjectTypes`, which the app answers from `TypeGraph` with open editors' text, as `SymbolLookup` does for intentions | `IntentionContext` has only `SymbolLookup` |
| Files changed | `Refactoring.kt`, Refactor This (which lists every kind), the Kotlin plugin, the app | a new `:lang` file, one descriptor line each in the app and the Kotlin plugin, the Kotlin plugin, the app | `Intention.kt`, the ⌥↩ popup, the Kotlin plugin, the app |
| Extension point contract | a provider's exhaustive `when` over `RefactoringKind` throws `NoWhenBranchMatchedException` for a new value, which the companion turns into `failed` before later providers answer; the context constructor changes (binary break, major) | additive, minor: a new extension point, and `RefactoringStep` gains `ChooseMembers`, which only the app consumes | `IntentionAction` is a data class; a member list changes its constructor (binary break, major), as R1 found |
| Frame path | unchanged | unchanged | unchanged |

**B is the contract.** The app's step handling (`followStep`, `applyRefactoringEdit`, the chooser
and the prepared-selection restore of R7) takes a title instead of a `RefactoringKind`, so
generators and refactorings share it. IntelliJ checks several members at once and `Choose` picks
one, so the multi-select is a new step instead of a wider `Choose`: `Choose` keeps its one-row
meaning, and a provider that never returns `ChooseMembers` is unaffected. The contract is in
`docs/api-lang.md` 「Generate providers」:

```kotlin
// stride.lang
enum class GenerateKind(val title: String) {
    SECONDARY_CONSTRUCTOR, EQUALS_HASH_CODE, TO_STRING, OVERRIDE_METHODS, IMPLEMENT_METHODS
}
class TypeSource(val qualifiedName: String, val path: String, val text: String, val nameOffset: Int)
interface ProjectTypes {
    fun resolve(written: String, path: String, offset: Int): List<TypeSource>
    fun hasTopLevelFunction(name: String): Boolean
}
class GenerateContext(val text: String, val caretOffset: Int, val fileName: String, val path: String,
                      val types: ProjectTypes?)
interface GenerateProvider { fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep? }
// RefactoringStep gains:
class ChooseMembers(val title: String, val members: List<MemberOption>,
                    val complete: (List<MemberOption>) -> NameCheck) : RefactoringStep
class MemberOption(val text: String, val group: String? = null, val checked: Boolean = false,
                   val disabledReason: String? = null)
```

The Kotlin plugin registers `stride.lang.kotlin.generate.KotlinGenerate`. Java has no language
plugin, so a `.java` file refuses with `<Generator> is not available for .java files`, and
IntelliJ's Java-only getters and setters are not generated (see 「Generate differences」).

### G2 The class at the caret

The class is the innermost `class`, `object`, `interface`, `enum class` or `companion object`
declaration whose header or body holds the caret, read with the refactoring model's tokens and
blocks (R2 「Tokens」, 「Blocks」). An object expression (`object : Runnable { … }`), a function body
or a file without a class refuses with `No class at the caret`. Every generator also refuses with
R2's `Brackets do not match in this file`.

| Class | Secondary Constructor | equals() and hashCode() | toString() | Override | Implement |
|---|---|---|---|---|---|
| `class`, `open`/`abstract`/`sealed` class | when it has no primary constructor (G4) | yes | yes | yes | yes |
| `data class` | no (a primary constructor) | yes, as IntelliJ | yes, as IntelliJ | yes | yes |
| `object`, `companion object`, `data object` | no | no | yes | yes | yes |
| `enum class` | when it has no primary constructor (G4) | no (`Enum` makes them final) | yes | yes | yes |
| `interface`, `fun interface` | no | no | no | yes | no (an interface may leave members abstract) |
| `annotation class` | no | no | no | no | no |
| `value class`, `inline class` | no (a primary constructor) | no | yes | yes | yes |

The availability follows IntelliJ 2026.1.3's `isValidForClass` checks (measured from its Kotlin
plugin): Secondary Constructor needs a `KtClass` that is not an interface, annotation or enum
entry and has no explicit primary constructor; equals/hashCode also refuses enums and value
classes; toString refuses annotations and interfaces only. A refused kind says why in the status
bar when run by `⌃O`/`⌃I` and is left out of the `⌘N` menu.

An existing member that the generator would write again refuses: `equals() already exists in A`,
`hashCode() already exists in A`, `toString() already exists in A`. IntelliJ offers to delete and
regenerate them; stride never deletes code it did not write (see 「Generate differences」).

### G3 Where generated code goes

- **Place.** When the caret is in the class body at member level (between members, not inside
  one), the members go at the caret's line: after the member that ends before it, before the
  member that starts after it. When the caret is inside a member, they go after that member. When
  the caret is in the header, they go at the end of the body, before its `}`. In an `enum class`
  they never go before the `;` that ends the entries; entries without a `;` refuse with
  `Add ; after the enum entries first`.
- **No body.** A class without `{` (`class A(val x: Int)`, `data class P(val x: Int) : Shape`)
  gets ` {`, the members and `}` after its header, when the header ends its line and the next code
  token starts another declaration, closes the enclosing body or ends the file. Otherwise it
  refuses with `Add a body to A first`.
- **Layout.** Each member is indented one step deeper than the class's own line. The step is the
  class body's existing member indentation when there is one, otherwise the file's first indented
  line's indentation, otherwise four spaces. One blank line separates generated members from each
  other and from their neighbours; existing blank lines next to the insertion point count, so no
  run of blank lines grows.
- **Caret.** After applying, the caret is at the start of the first generated member's first body
  statement (`TODO(…)`, `return super…`, `if (this === other)`, `this.x = x`), or after the
  last parameter of a constructor without statements.
- **Undo.** One `TextFileEditor.applyEdits` call, one undo step, as R7. The edits are inserts at
  prepared offsets, plus one insert per added import line (G6).

### G4 Secondary Constructor

Runs in a class without an explicit primary constructor (G2). The chooser lists the class's own
properties that a constructor can assign, all checked: a `var` (also `lateinit var`) or a `val`
without an initializer, declared in the class body, not `abstract`, not delegated (`by`), not an
extension, without a custom getter and not assigned in an `init` block. A property whose type is
not written is listed with `type not written` and cannot be checked. The constructor's
parameters are the checked properties in declaration order, named like them, typed as written,
and its body assigns them: `this.x = x`. With no candidate the chooser is skipped and an empty
constructor is generated.

It refuses when the new constructor can change what an existing call reaches. Measured with
Kotlin 2.4.21:

| Shape | Effect of adding `constructor(x: Int)` | Rule |
|---|---|---|
| an existing constructor with the same parameter types | conflicting overloads | refuse: `constructor(Int) already exists` |
| an existing constructor that also accepts the same number of arguments through default values or a `vararg` (`constructor(x: Long, y: Int = 0)`, `constructor(vararg xs: Int)`) | `A(1)` silently moves to the new constructor | refuse: `A(…) with 1 argument would call the new constructor` |
| an existing constructor with the same number of parameters and other types (`constructor(x: Long)`) | `A(1)` silently moves when the literal fits both | refuse with the same message |
| `operator fun invoke` in the class's companion object | `A(i)` silently moves from `invoke` to the constructor | refuse: `A's companion invoke() would lose its calls` |
| a top-level function or property named `A` in project sources (`ProjectTypes.hasTopLevelFunction`) or in this file | same parameter types: conflicting overloads; other types: `A(1)` silently moves to the constructor | refuse: `A top-level function A(…) exists` |
| the class has no explicit constructor at all | the implicit `A()` disappears; `A()` stops compiling, enum entries without arguments stop compiling, and a top-level `fun A()` silently receives `A()` | write `constructor()` first, then the new constructor: `A()`, entries and subclass calls keep their target (measured). A callable reference `::A` without an expected type becomes ambiguous (a compile error, documented) |
| a supertype initialized in the header (`class A : Base()`) | `supertype initialization is impossible without a primary constructor` | refuse: `A initializes Base() in its header` |
| a class supertype without parentheses (`class A : Base`) | the constructor must call `super(…)` | when `Base` is in project sources, a `Choose` step lists its constructors (one is taken directly), their parameters come first and the body delegates `: super(a, b)`; otherwise refuse: `Base is not in project sources` |
| `types == null` with a class supertype or a top-level-function check pending | the index is not ready | refuse: `Indexing… try again in a moment` |

The empty constructor body follows IntelliJ's template, which is a comment; stride writes no
comment and leaves `{}` out, as Kotlin allows.

### G5 equals() and hashCode(), toString()

The chooser lists the class's properties with a backing field, all checked: primary-constructor
`val`/`var` parameters and body properties that have an initializer or `lateinit`, no delegate
(`by`), no custom getter, not `const`, not `abstract`, not extensions. The
type decides the comparison, so a property whose type is not written, a literal or a constructor
call (H4) is listed with `type not written` and cannot be checked. The generated text follows the
templates of IntelliJ's Kotlin plugin for the JVM (`defaultEquals.vm`, `defaultHashcode.vm` and
the four toString templates of its `k2/codeinsight/generate` package), for example:

```kotlin
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as A

    if (x != other.x) return false
    if (!names.contentEquals(other.names)) return false

    return true
}

override fun hashCode(): Int {
    var result = x
    result = 31 * result + (label?.hashCode() ?: 0)
    result = 31 * result + names.contentHashCode()
    return result
}

override fun toString(): String {
    return "A(x=$x, label=$label, names=${names.contentToString()})"
}
```

- Arrays (`Array<…>`, `IntArray` and the other primitive arrays, nullable or not) compare with
  `contentEquals`, hash with `contentHashCode` and print with `contentToString`; an array of arrays
  uses the `contentDeep…` forms. A nullable property hashes as `(p?.hashCode() ?: 0)`; an `Int`,
  `Short` or `Byte` property's hash is the value itself, other types call `hashCode()`. With one
  property and no super hash the body is `return p.hashCode()`; with none it is
  `return javaClass.hashCode()`. A generic class casts `other as A<*>`.
- A name that needs backticks prints as `${`name`}` (measured: both template forms compile, and
  the braced form leaves no doubt where the name ends).
- The locals `other` and `result` must not be the name of a checked property, which would compare
  or hash the wrong value without a compile error: the parameter becomes `other1` and the local
  `result1`, as IntelliJ's unique-name helper does, when such a property is checked.
- **Supertypes.** A superclass in project sources that overrides `equals` (or `hashCode`) adds
  `if (!super.equals(other)) return false` (and starts `var result = super.hashCode()`); one that
  declares them `final` refuses: `Base declares equals() final`. A class supertype that is not in
  project sources refuses equals/hashCode with `Base is not in project sources`: whether its
  `equals` compares fields cannot be read, and a wrong super call makes every comparison false.
  toString never calls super, as IntelliJ's default.
- Choosing no property is allowed, as in IntelliJ: equals keeps its identity and class checks and
  toString writes `"A()"`.

### G6 Override Methods and Implement Methods

Members come from the class's supertypes in project sources: each written supertype is resolved
with `ProjectTypes.resolve` at its header offset, and the walk continues through each resolved
declaration's own header, resolved at the offsets of its own file. Each supertype file is read with
the same tokens and blocks as the current file.

- **Candidates.** Functions and properties of the supertypes that the class can override: not
  `private`, not `final` (`final override` included), not constructors, `init` blocks or nested
  types; interface members are open; class members need `open`, `abstract` or `override`. The
  nearest declaration along the walk wins. A member the class already declares with the same
  name and the same written parameter types after substitution is left out. Override also lists
  `equals(other: Any?): Boolean`, `hashCode(): Int` and `toString(): String` of `Any` under the
  group `Any` unless the class or a project supertype declares them, or declares them `final`.
- **Override** lists every candidate grouped by supertype, none checked. A concrete member's body
  calls super: `return super.f(a, b)` (block body; `Unit` members drop the `return`), a `vararg`
  parameter passes as `*xs` (measured: without `*` the array arrives as one element), and when
  more than one direct supertype provides the member the call is `super<Owner>.f(…)` (measured: a
  bare `super` does not compile there). An abstract member's body is `TODO("Not yet implemented")`.
  A property becomes `override val x: T get() = super.x`, and a `var` adds
  `set(value) { super.x = value }` (measured: same behaviour as inheriting it).
- **Implement** lists the abstract candidates that no class along the walk implements, all
  checked. Bodies are `TODO("Not yet implemented")`; a `val` gets `get() = TODO("Not yet implemented")`
  and a `var` adds `set(value) {}`. Implement refuses when a class supertype anywhere along the
  walk is not in project sources: `Base is not in project sources; it may implement these members`
  (measured: `class C : Lib(), P` compiles without overriding when `Lib` has `f()`, so a
  generated `TODO()` would replace working code). A written supertype that is an interface outside
  project sources only drops its members, and the chooser title says
  `Runnable is not in project sources`; Kotlin requires an explicit override when two interfaces
  supply the same member, so an unread interface cannot implement a project member silently.
  Nothing to implement refuses with `No members to implement in project sources`, and the same for
  Override.
- **Signatures.** The header is copied from the supertype's text: modifiers `suspend`, `operator`,
  `infix` and `vararg` stay; visibility, `open`, `abstract`, `override`, `final`, annotations, KDoc
  and default values go (measured: an override may not declare defaults, and an omitted visibility
  keeps `protected`). Member type parameters, their bounds and `where` clauses stay.
- **Type arguments.** A generic supertype's type parameters are replaced, token by token outside
  qualified names, with the type arguments the subclass writes (`class A : Repo<User>` turns
  `save(x: T)` into `save(x: User)`), composed along the walk. A member refuses (it is listed with
  a reason and cannot be checked) when its signature uses a type parameter whose argument is
  missing, `*` or a projection (`out T`, `in T`), or when a member type parameter has the name of a
  type the substitution writes. A wrong substitution cannot compile silently: `override` on a
  signature that matches nothing is a compile error (measured), so these rules are exact rather
  than broad.
- **Imports.** Every simple type name the signature writes is checked in the target file: when it
  resolves there to the same project declaration it stays; when it resolves to nothing and the
  supertype's file resolves it to a project declaration, an import of that declaration's qualified
  name is added in sorted position (as the import quick fix of the third round); when the supertype
  file imports it explicitly (a library type), the same import line is added unless the target
  imports or declares that simple name otherwise; a name that comes from a star import or an alias
  in the supertype file, or that the target resolves to another declaration, makes the member
  `refers to another X here` and it cannot be checked. Default imports (`kotlin.*`,
  `kotlin.collections.*` and the rest of Kotlin's JVM defaults) need nothing.
- **Index.** With `types == null`, both refuse with `Indexing… try again in a moment` when the
  class has a supertype; with no supertype they list only `Any`'s members (Override) or refuse
  (Implement).
- Member extensions (`fun String.f()` inside a supertype) are listed with `member extensions are
  not generated` and cannot be checked.

### G7 Showing and choosing

- **⌘N** prepares every kind and lists those that did not refuse in IntelliJ's order: Secondary
  Constructor…, equals() and hashCode()…, toString()…, Override Methods… `⌃O`, Implement
  Methods… `⌃I`, in the `TargetChooserPopup` of R6 (`popupPanel`). Choosing a row follows the step
  it prepared. With nothing available the status bar says `Nothing to generate here`.
- **⌃O / ⌃I** prepare their kind and follow it; a refusal goes to the status bar.
- **Member chooser** (`MemberChooserPopup`, `popup-member-chooser`, a `popupPanel` popup registered
  in `PopupNativeLegibilityTest`): group headings, one row per member with a check mark, the
  disabled reason in the secondary text role. `↑`/`↓` move, `Space` toggles the row, `⌘A` checks
  every enabled row (again: unchecks them), `↵` submits the checked rows or, when none is checked,
  the row under the cursor, and `Esc` closes and restores the prepared selection (R7). A
  `NameCheck.Invalid` keeps the popup open with its message in `errorOnPopupPlate`.
- Read-only editors say `File is read-only`. A file that changed between preparing and applying
  says `The file changed; run <Generator> again`.

### Generate messages

| Message | When it appears |
|---|---|
| `Nothing to generate here` | ⌘N with every kind refused |
| `No class at the caret` | the caret is not in a class declaration (G2) |
| `<Generator> is not available for .<ext> files` | a file whose language has no generate provider |
| `<Generator> failed: <Exception>` | the provider threw; the exception is logged |
| `The file changed; run <Generator> again` | the text changed between preparing and applying |
| `Indexing… try again in a moment` | supertypes or top-level functions are needed before the index is ready |
| `File is read-only` | any of the three in a read-only editor |

G2 to G6 list the provider's other messages.

### Generate differences

- Getters and setters, Test, Copyright, Delegate Methods and IntelliJ's equals/hashCode wizard
  options (choose different properties for each, non-null fields) are not generated; Kotlin has no
  getters to write and stride has no Java plugin.
- Members of library supertypes are not listed (the tenth round does not read libraries), and
  Implement refuses when a library class sits in the hierarchy (G6).
- Existing `equals`, `hashCode` and `toString` refuse instead of being deleted and regenerated.
- Enum classes and object expressions: Secondary Constructor follows G4 in enums; object
  expressions are not classes for G2.

## Declaration navigation, tests and bookmarks

```kotlin
package stride.app.navigation

data class NavigationLocation(val path: Path, val offset: Int)

class NavigationHistory(maxEntries: Int = MAX_ENTRIES) {
    val size: Int
    val current: NavigationLocation?
    fun locations(): List<NavigationLocation>
    fun push(location: NavigationLocation)     // truncates forward, dedupes, caps at 100
    fun record(location: NavigationLocation)   // push unless recording is suspended
    fun updateCurrent(path: Path, offset: Int) // caret of the current entry only
    fun back(): NavigationLocation?
    fun forward(): NavigationLocation?
    fun clear()
    fun <T> withoutRecording(block: () -> T): T
    companion object { const val MAX_ENTRIES: Int = 100 }
}

fun navigationLocation(editor: FileEditor): NavigationLocation

object DeclarationNavigator {
    fun localTarget(fileName: String, text: String, name: String, caretLine: Int): ParsedDeclaration?
    fun ranked(candidates: List<Declaration>, currentPackage: String?, currentModule: String?): List<Declaration>
    fun packageOf(text: String, relativePath: String): String?
    fun packageOfPath(relativePath: String): String?
    fun moduleOfPath(relativePath: String): String?
}

object TestNavigation {
    fun baseName(fileName: String): String?
    fun isTestName(base: String): Boolean
    fun counterpartNames(base: String): List<String>
    fun candidates(fileName: String, currentRelativePath: String?, files: List<IndexedFile>): List<IndexedFile>
}

data class Bookmark(val path: Path, val relativePath: String, val line: Int, val lineText: String)

class BookmarkManager {
    val entries: List<Bookmark>                // Compose snapshot list
    fun toggle(bookmark: Bookmark): Boolean    // true when added
    fun remove(bookmark: Bookmark)
    fun linesOf(path: Path): Set<Int>
    fun isEmpty(): Boolean
    fun clear()
    companion object {
        fun lineIndex(text: String, offset: Int): Int
        fun lineText(text: String, line: Int): String
    }
}

data class FileStructureEntry(val name: String, val kind: DeclarationKind, val line: Int, val depth: Int)

object FileStructure {
    fun entries(fileName: String, text: String): List<FileStructureEntry>
    fun matching(entries: List<FileStructureEntry>, query: String): List<FileStructureEntry>
}

data class ChooserRow<T>(val text: String, val value: T, val hint: String? = null)
@Composable fun <T> TargetChooserPopup(title: String, rows: List<ChooserRow<T>>,
                                       onChoose: (T) -> Unit, onClose: () -> Unit,
                                       onSelect: (T) -> Unit = {})
@Composable fun FileStructurePopup(entries: List<FileStructureEntry>,
                                   onChoose: (FileStructureEntry) -> Unit, onClose: () -> Unit)
@Composable fun BookmarksPopup(bookmarks: List<Bookmark>, onChoose: (Bookmark) -> Unit,
                               onRemove: (Bookmark) -> Unit, onClose: () -> Unit)
```

`ProjectSession` owns one `NavigationHistory` and one `BookmarkManager`; it records every
selection change through `FileEditorManager.onSelectionChanged`, so a tab switch, a history
restore and a jump all land in the same stack. `App` keeps the current entry's caret in step
with `updateCurrent`, observing the caret and the selected editor through `snapshotFlow` so a
caret move never recomposes the window, and gives `TextFileEditor.onJump` a recorder, so
`GOTO_LINE`, the file structure popup and every other `gotoLine` path add an entry.
`withoutRecording` keeps the `openFile` a `back`/`forward` performs from truncating the forward
stack. A history entry whose file no longer exists is dropped when it is reached and reports
`File not found: <path>` instead of opening an empty tab.

`GOTO_DECLARATION` (`⌘B`, and `⌘`-click through `TextFileEditor.onGoToDeclarationAt`) reads
the word at the caret; a leading `$` is a string-template marker in every language but Java, so
it is left out of the name. A declaration of that name in the current file wins (nearest
preceding line, else the first one), using the declarations `TextFileEditor` parses once per
text revision. Otherwise the `DeclarationIndex` snapshot is filtered to the name and ordered by
the current package, the current module, then the path: one hit opens it, several open
`TargetChooserPopup` (`name — path:line`), none sets `Cannot find declaration to go to`. While
the initial scan has not finished the action reports `Indexing… try again in a moment` instead
of claiming the declaration does not exist. A package comes from the file's `package` line, else
from the path below `src/<set>/kotlin|java`; a module is the path before `/src/`.

`GOTO_TEST` maps `Foo.kt` to `FooTest.kt`, `FooTests.kt` or `TestFoo.kt` and a test name back to
its subject through `FileNameIndex` (which caches its sorted snapshot); several candidates open
the chooser, none sets `No test found for Foo`, and an unfinished scan reports the same
`Indexing…` message. `FILE_STRUCTURE_POPUP` lists `DeclarationParser` declarations indented
by their container depth and filters them with `NameMatcher`; the parse runs on
`Dispatchers.Default`. `TOGGLE_BOOKMARK` marks the caret line in the editor gutter
(`TextFileEditor.bookmarkLines`); bookmarks live in the session, so they survive a tab close, and
they do not move when lines above them are edited. `SHOW_BOOKMARKS` lists `path:line — text`;
Enter opens the line, Delete/Backspace removes the selected bookmark, and a bookmark whose file
is gone is dropped with `File not found: <path>`.

`COPY_PATHS` writes the absolute path and `COPY_REFERENCE` writes `relative/path:line` through
`AppState.clipboard` (replaced in tests), both reporting `Copied <text>`. `SELECT_IN_PROJECT_VIEW`
shows the tree, calls `ProjectTreeModel.reveal` and bumps `revealRequests`, which scrolls the
list to the selected row. `ACTIVATE_PROJECT_TOOL_WINDOW` toggles `projectTreeVisible`;
hiding the tree, the active tool window (`AppState.hideActiveWindow`) or both panels
(`HIDE_ALL_WINDOWS`) hands the focus context and the keyboard back to the selected text editor
through `editorFocusRequests`, and `HIDE_ALL_WINDOWS` restores only a layout its previous press
actually hid. `SWITCHER` opens `RecentFilesPopup` (which starts on the second entry and steps
down on Tab, up on ⇧Tab; `⌃⇧Tab` uses the same stroke), and
`REOPEN_CLOSED_TAB`/`CLOSE_OTHER_TABS` use `FileEditorManager`'s closed-tab stack. All new popups are modal: they open with `state.focus` set to `CONTEXT_POPUP`, Escape
closes them, and every close hands focus back to the editor.

## App composable

```kotlin
@Composable fun App(state: AppState, onOpenFolder: () -> Unit, searchInitialQuery: String = "")
```

`state` owns the open `ProjectSession`, `searchVisible`, `focus` and the `ToolWindowManager`;
`onOpenFolder` runs the folder chooser. `searchInitialQuery` is forwarded to
`SearchEverywherePopup` and is empty in the window; the snapshot renderer uses it to open the
popup with a query in place.

## Terminal

The panel's Terminal tab runs the project's login shell over a real PTY. Two libraries are
added for this: `org.jetbrains.pty4j:pty4j:0.14.0` from Maven Central and
`org.jetbrains.jediterm:jediterm-core:3.76`, which is only published on JetBrains' Maven
repository (`https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`, added to
`settings.gradle.kts`). Both are declared in `gradle/libs.versions.toml` and
`app/build.gradle.kts`.

```kotlin
package stride.app.terminal

class PtyTtyConnector(
    private val process: PtyProcess,
    private val charset: Charset = Charsets.UTF_8,
) : TtyConnector

object PtyShell {
    fun start(
        workingDir: Path,
        columns: Int,
        rows: Int,
        env: Map<String, String> = System.getenv(),
    ): PtyProcess
}

@Service(Service.Level.PROJECT)
class TerminalSession(private val project: Project) : Disposable {
    val buffer: TerminalTextBuffer
    val styleState: StyleState
    val terminal: JediTerminal
    var columns: Int
    var rows: Int
    var revision: Int                  // Compose state, +1 on every model change
    val isStarted: Boolean
    val cursorX: Int                   // 0-based; jediterm's getCursorX() is 1-based
    val cursorY: Int                   // 1-based
    fun start(connector: TtyConnector)
    fun startDefault()
    fun write(text: String)
    fun sendKey(key: Key, meta: Boolean, ctrl: Boolean, alt: Boolean, shift: Boolean, char: Char?): Boolean
    fun resize(columns: Int, rows: Int)
    fun lines(): List<String>
    fun historyLines(): List<String>
}

@Composable fun TerminalView(session: TerminalSession, modifier: Modifier = Modifier, focusRequests: Int = 0)
@Composable fun TerminalToolWindow(session: TerminalSession, modifier: Modifier = Modifier, focusRequests: Int = 0)
```

`TerminalSession` owns jediterm's `TerminalTextBuffer`, `StyleState` and `JediTerminal`, a
`TerminalStarter` on a daemon emulator thread named `stride-terminal-<project>`, and a
`TerminalDisplay` that only records the window title. The buffer's `TerminalModelListener`
bumps `revision`, so a view repaints by reading that state. The session is a project service:
`ProjectSession.dispose()` → `Project.dispose()` disposes it, which removes the listener,
closes the connector, destroys and waits for the process, and drops the emulator thread.

The shell is `$SHELL` (falling back to `/bin/zsh`) with `-l`, started in the project root with
`TERM=xterm-256color`. `TerminalToolWindow` calls `startDefault()` the first time the tab is
shown; `start()` is idempotent, so a session already started with a fake `TtyConnector` (tests,
snapshots) keeps that connector. Sizing goes both ways: the view derives the cell grid from
`codeFont`/`codeTextSize` and `codeLineHeight`, calls `TerminalSession.resize`, and the session
resizes the buffer through `JediTerminal.resize` and the PTY through `TtyConnector.resize` →
`PtyProcess.setWinSize`.

`TerminalView` draws the screen and scrollback on a `Canvas`: per-cell foreground and
background, bold and inverse, a neon cursor block with a glow when focused, and a whole-line
selection copied with ⌘C. The wheel scrolls the history and new output returns to the bottom.
ANSI indices 0–15 come from `TerminalPalette.ansi` for glyphs and `originalAnsi` for fills (the
same neon family as `DesignTokens`); on the native glass a foreground entry is moved through
`readableOn` while a background keeps its original colour, and inverse video swaps the original
colours. 256-color and truecolor values use the RGB `TerminalColor` directly.

Key flow: `handleWindowKeyEvent` routes through the keymap first. In the `TERMINAL` context
only GLOBAL bindings match (⌘S, ⌘W, ⌘O, ⌘⇧], ⌥F12, ⌘4, ⌘⇧F, ⌘⇧O, ⌘E, ⌃R, ⌘F2), so those never
reach the shell. Everything else reaches the focused `TerminalView`. Special keys become xterm
sequences (Enter `\r`, arrows `ESC[A`…, Backspace `0x7f`, ⌃C `0x03`, …); printable characters
are written as text; keys with ⌘/⌃/⌥ that are neither mapped nor a control sequence are left
unconsumed. ⌘V pastes the clipboard and ⌘C copies the selected lines; both follow the macOS
convention rather than a keymap entry.

Focus delivery: `ToolWindowPanel` requests focus on its root whenever `focusRequests` changes,
which would leave the keyboard on the panel rather than in the shell. `AppState` passes
`toolWindows.focusRequests` to `TerminalToolWindow`, which forwards it to `TerminalView`; the
view's `LaunchedEffect(focusRequests)` requests focus on its own `FocusRequester` after a
frame. The parent's effect starts and resumes first, so the view's later request wins. A click
on the view also requests focus. `KeyFlowUiTest` pins this by typing into a fake connector.

## Run

The Run tool window runs a project's Gradle build as a child process and turns the test
events its init script prints into a tree. It is a project service, like the terminal.

### Process model

`DefaultProcessStarter` materializes the init script resource
`stride/run/stride-test-events.init.gradle`, builds the command through `GradleCommand`,
and starts it in the configuration's working directory with stdout and stderr merged.

```kotlin
package stride.app.run

object GradleCommand {
    fun build(
        workingDir: Path,
        args: List<String>,
        initScript: Path,
        shell: String = System.getenv("SHELL") ?: "/bin/zsh",
        gradlewExists: (Path) -> Boolean = { Files.isExecutable(it) },
    ): List<String>                       // ProcessBuilder command
    fun display(command: List<String>): String
    fun testArgs(gradlePath: String, filter: String): List<String>
}

object GradleInitScript {
    fun materialize(): Path
}

data class RunConfiguration(
    val name: String,
    val workingDir: Path,
    val args: List<String> = emptyList(),
    val kind: RunKind,
    val command: List<String>? = null,      // a direct run; null means Gradle
) {
    val identity: RunIdentity              // kind + workingDir + args/command, not name
}

data class RunIdentity(
    val kind: RunKind,
    val workingDir: Path,
    val args: List<String>,
    val command: List<String>?,
)

enum class RunKind { TEST, TASK, APPLICATION }
```

The command is `$SHELL -l -c 'exec "$@"' stride <gradlew> --console=plain --init-script
<temp file> <args…>`. The login shell restores `JAVA_HOME`/`PATH` in a packaged `.app`;
`gradlew` resolves to the absolute wrapper in the working directory or, when it is absent,
to `gradle` on `PATH`. Arguments are not quoted in the command list because `exec "$@"`
forwards each positional argument as one element. `display` quotes only elements holding
whitespace or quotes for the console's first line. The child never gets `--no-daemon` or
`--stop`. The init script disables up-to-date checks and the build cache for every `Test`
task, so a repeated run still executes and reports its events instead of being skipped.

A script (`.py`, `.sh`, `.js`, `.rb`, `.kts`) runs directly even inside a Gradle project and
starts in the script's directory. `*.gradle.kts` is the Gradle DSL and is never run as a
script. Kotlin and Java use `strideRunMain` only when the file is under
`src/<sourceSet>/(kotlin|java)/` of its Gradle project; a Java file elsewhere runs with
`java <file>` when its main class is the file's first top-level type (`class`, `interface`,
`enum`, `record` or `@interface`). Direct commands go
through the same login shell (`exec "$@"`); a Kotlin script uses
`command -v kotlin >/dev/null && exec kotlin "$2" || exec kotlinc -script "$2"` so the
`kotlin` probe happens in the shell rather than on the UI thread (`$0` is the shell's name,
`$1` is the tool name and `$2` the script path).

### Event protocol

The init script hooks `beforeTest`, `afterTest` and `onOutput` on every `Test` task and
prints one line per event to stdout. Values escape `|` → `||`, `'` → `|'`, newline → `|n`,
carriage return → `|r`, `[` → `|[` and `]` → `|]`; the parser reverses them.

| moment | line |
|---|---|
| start | `##stride[test start class='<className>' name='<methodName>']` |
| pass | `##stride[test pass class='…' name='…' duration='<ms>']` |
| fail | `##stride[test fail class='…' name='…' duration='<ms>' message='…' stack='…']` |
| skip | `##stride[test skip class='…' name='…']` |
| output | `##stride[test out class='…' name='…' stream='stdout\|stderr' text='…']` |

`className` and `name` come from `TestDescriptor.className`/`TestDescriptor.name`; composite
suite descriptors are not printed, so only test methods reach the tree. A failure's stack is
truncated to 20 lines. Lines that do not start with `##stride[test `, and prefixed lines that
do not parse, are console output rather than events.

```kotlin
sealed class TestEvent { Start, Pass, Fail, Skip, Out }

sealed class ParsedLine {
    data class Event(val event: TestEvent) : ParsedLine()
    data class Output(val text: String) : ParsedLine()
}

object TestEventParser {
    fun parse(line: String): ParsedLine
}
```

### Sessions and the tree

```kotlin
class RunSession(
    val configuration: RunConfiguration,
    process: Process,
    displayCommand: String,
) {
    val consoleLines: SnapshotStateList<String>   // displayCommand, then the newest 10 000 lines
    val tree: TestTree
    val exitCode: Int?                            // Compose state, set at EOF
    val undeliveredInput: String?                 // a line the writer could not send
    val isRunning: Boolean                        // exitCode == null
    fun start()                                   // "stride-run-reader" + "stride-run-writer"
    fun writeInput(line: String): Boolean         // queues; false when ended, closed or full
    fun clearUndeliveredInput()
    fun stop()                                    // descendants, then the process
}

enum class TestStatus { RUNNING, PASSED, FAILED, SKIPPED }

class TestMethodNode(val className: String, val name: String) {
    val status: TestStatus
    val message: String?
    val stackTrace: String?
    val output: SnapshotStateList<String>
}

class TestClassNode(val className: String) { val methods: SnapshotStateList<TestMethodNode> }

class TestTree {
    val classes: SnapshotStateList<TestClassNode>
    val passed: Int
    val failed: Int
    val skipped: Int
    fun apply(event: TestEvent)
}
```

The reader thread sends event lines to the tree and the rest to `consoleLines`; it fills
`exitCode` with `Process.waitFor()` after EOF. A class node is `FAILED` when any method
failed, `RUNNING` while any is running, `SKIPPED` when every method was skipped, otherwise
`PASSED`. Standard input goes through a bounded queue (64 lines) consumed by a single
writer thread, so a child that stops reading cannot block the UI; `writeInput` returns
false when the process ended, a write already failed, or the queue is full, and the console
keeps the typed line. A write that still fails later lands in `undeliveredInput`, which the
input row puts back, and closes stdin for the rest of the session. The console holds at most
`MAX_CONSOLE_LINES` (10 000) lines; the oldest ones are dropped in batches when it overflows.

```kotlin
@Composable fun RunToolWindow(manager: RunManager, modifier: Modifier = Modifier, focusRequests: Int = 0)
@Composable fun TestGutterMarkers(
    session: ProjectSession,
    toolWindows: ToolWindowManager,
    onStatus: (String?) -> Unit = {},
)

fun runAtCaret(state: AppState): Boolean
fun runTarget(state: AppState, file: Path, target: TestLocator.TestTarget): Boolean
```

`RunToolWindow` is a header (configuration name and `Running…`/`Passed n`/`Failed n of m`/
`Exit code n`) over a 36/64 split of the test tree and console. When more than one
configuration is alive it shows one tab per session above the header; clicking a tab makes
it the one `Stop` and `Rerun` act on, and its × stops and closes it. Starting a
configuration that is already in a tab stops that tab's process and replaces it in place;
starting a different one adds a tab and leaves the other processes running. After a run,
finished sessions beyond the five most recent are dropped; running ones are kept. Passed is `neonLime`,
failed `neonPink`, running `accent2` and skipped `muted`; the glyph carries the state's
glow. Selecting a method uses the neon selection signal and shows its message, stack and
captured output below the console. The console uses `codeFont` and follows new lines. The
view requests focus from `focusRequests`, as the terminal does; the running pulse only
animates while `LocalAnimationsEnabled` is true.

`TestGutterMarkers` puts a ▶ on the class line and every `@Test fun` of a `.kt` file under
`/src/test/` and on the `main` of a `.kt`/`.java` file; it scans only those extensions and
does the scan on `Dispatchers.Default`. Clicking a marker starts that target, or reports the
specific reason it cannot start through `onStatus`. `runAtCaret` locates the test under the
caret and starts it. The four actions are:

| ActionId | when | meaning |
|---|---|---|
| `RUN` | ⌃R in any context | rerun the selected tab, else run the test at the caret, else `Nothing to run here` |
| `RUN_CONTEXT` | ⌃⇧R in the editor | run the test at the caret, else the file's main, else the reason or `Nothing to run here` |
| `STOP` | ⌘F2 | destroy the process tree |
| `RERUN` | ⌘R in the Run window | rerun the selected tab's configuration, else `lastRun`, else `Nothing to rerun` |

Starting a run opens the Run tool window with `activate("run")` and clears
`AppState.statusMessage`; `StatusBar` shows that message in `neonAmber` before the focus
chip.

```kotlin
object TestLocator {
    data class TestTarget(val className: String, val methodName: String?)
    data class TestMarker(val line: Int, val target: TestTarget)
    fun locate(text: String, caretOffset: Int): TestTarget?
    fun markers(text: String): List<TestMarker>
    fun gradleProjectPath(file: Path, root: Path): String?
    fun gradleProjectDir(file: Path, root: Path): Path?
}
```

`TestLocator` is regular expressions only (no PSI). The class is the nearest
`class <Name>` at or above the caret, and the method is the nearest `fun` at or above it
whose contiguous annotation lines include `@Test`. A caret between the class line and the
first test resolves to the class; above the class it resolves to `null`. The filter is
`<className>.<methodName>` with the backticked name unquoted. `gradleProjectDir` walks up to
the nearest directory with `build.gradle.kts` or `build.gradle`; `gradleProjectPath` returns
that directory's path from the project root with `:` separators (`:plugins:lang-kotlin`,
`""` for the root, `null` outside the root). `MainLocator` maps a file name to the class the
Kotlin compiler generates (`hello-world.kt` → `Hello_worldKt`) and reports whether a Java
main's class is the file's first top-level type.

```kotlin
@Service(Service.Level.PROJECT)
class RunManager(project: Project) : Disposable {
    var starter: ProcessStarter                 // tests replace DefaultProcessStarter
    var lastRun: RunConfiguration?
    val sessions: List<RunSession>              // one per configuration, oldest first
    var current: RunSession?                    // the selected tab
    val isRunning: Boolean
    val rerunTarget: RunConfiguration?          // the selected tab, else lastRun
    fun run(configuration: RunConfiguration): RunSession
    fun select(session: RunSession)
    fun close(session: RunSession)              // stops and removes the tab
    fun rerun(): RunSession?                    // runs rerunTarget
    fun stop()                                  // stops the selected tab
}

fun interface ProcessStarter { fun start(configuration: RunConfiguration): StartedProcess }
data class StartedProcess(val process: Process, val displayCommand: String)
object DefaultProcessStarter : ProcessStarter
```

`RunManager` is a project service: `ProjectSession.dispose()` → `Project.dispose()` →
`ComponentManager.dispose()` disposes it, which stops every child process. Recent entries
are deduplicated by `RunConfiguration.identity`, so two `MainKt` configurations from
different directories both survive; the chooser adds the relative working directory to the
secondary text when two rows share a name.

### Limits

Nested classes are not located. The Gradle path is the directory path, so a project whose
`settings.gradle.kts` `include` names a directory differently is reported under the
directory path. Run configurations are not persisted; `lastRun`, `recent` and the live
sessions live only in the project.

## Look

`stride.app.ui.theme.DesignTokens` is the single source of colors, type and spacing; UI
files declare no colors of their own. Every color is a getter into the palette of the
effective `AppearanceState`, so the whole window re-themes in place when the system
appearance changes; no screen keeps a color or a cached bitmap of its own across the swap.

`Appearance` is `DARK` or `LIGHT`, and `stride.app.glass.SystemAppearance` feeds it from
the effective macOS appearance. On the native path the JNI library queries
`NSApp.effectiveAppearance` and observes the distributed
`AppleInterfaceThemeChangedNotification`; without the library the same value comes from
`defaults read -g AppleInterfaceStyle` on a two-second background poll (the read has a timeout
so a hung `defaults` cannot stall the poller, and `stop()` interrupts the thread). The window
content starts it once it is composed, i.e. after AWT installed `NSApplicationAWT`; the native
query never creates an application object (`NativeAppearanceOrderTest` proves a fresh JVM that
queries first still ends up with `NSApplicationAWT`). The window follows the system live
without a restart. `main()` first calls `SystemAppearance.prepareProcess()`, which sets
`apple.awt.application.appearance` to `system` unless the process already set it, lifting JDK
21's pin of NSApp to Aqua; `GlassRuntime.applyAppearance` then sets the attached window's
`NSAppearance` from `AppearanceState`, so the glass material and the palette take the same
appearance.

**Dark palette** (`DarkPalette`, v1.3.0 values, unchanged): a cool graphite with a very low
blue-violet chroma: `background` #13141A with `backgroundLift` #1A1B23 at the top of the
field and `fieldBottom` #0B0C10 at the bottom, `surface` #0F1015 (the editor and console
tint, darker than the chrome glass), `raised` #21232B (hover and key caps), `border`
#2F313A, `text` #E6E8F0, `selectedText` (white), `muted` #8A93A6, `subtle` #5A6274,
`accent` (#7C5CFF), `accent2` (#00D1FF) and a selection of the accent at 22% alpha.

**Light palette** (`LightPalette`): neutral light surfaces with dark ink — `background`
#F3F4F8 with `backgroundLift` #FBFCFE and `fieldBottom` #E9ECF2, the opaque editor
`surface` #FFFFFF (the Skia path's reference light editor plane; the native path has no
opaque fill there), `raised` #F0F2F6, `border` #D2D7E0,
`text` #1D2330, `selectedText` #10151F, `muted` #576070, `subtle` #8A92A2. The accents are
darkened so text and indicators keep their contrast on white: `accent` #5B3FD6, `accent2`
#085683, `accentHover` #4A32B8, and the selection is the accent at 30% alpha. The neon
family is darkened the same way: `neonPink` #BD2269, `neonLime` #305C00, `neonAmber`
#704900. Every text role (including `muted` and the secondary-on-glass tokens) clears
4.5:1 on the white surface, the light field and the worst light glass; the accent2,
`neonLime` and `neonAmber` labels also clear 4.5:1 over the near-white floor the native
tint leaves over a dark desktop. The selection pill clears 1.15:1 against its panel, and the
blended editor selection stays visibly distinct from white. `DesignTokensContrastTest` asserts
both palettes.

Code highlighting takes the scheme of the effective appearance through
`stride.app.editor.activeColorScheme()`; `ColorScheme.LIGHT` colours every token key
(keyword, string, character, number, comments, identifiers, operators, braces, brackets,
parentheses, comma, dot, semicolon, bad character) at 4.5:1 or better on the white editor
surface. The gutter numbers, current-line glow, selection fill, caret and find-match
backgrounds are palette tokens, and `App` re-asserts the find-match spans when the
appearance changes. `TerminalPalette` exposes `foreground`, `background`, `defaultCellBackground`,
`cursor` and the 16 ANSI colors as appearance-aware getters; the light ANSI palette is a
darkened set in which every index clears 4.5:1 on the light terminal plane.

Chrome text uses the bundled Pretendard (SIL OFL 1.1) at 13 sp; `DesignTokens.uiFont` reads
`UiFont.family`, so the whole UI follows a runtime choice between Pretendard and the system
default. `ActionId.TOGGLE_UI_FONT` (`Switch UI Font`, no default stroke, reachable from the
Actions tab) flips it immediately and stores the choice with `java.util.prefs.Preferences` in
node `stride` under `uiFont` (`pretendard` / `system`), then shows `UI font: Pretendard` or
`UI font: System` in the status capsule group. `UiFontPreference` takes the node so tests inject their
own. The font files live in `app/src/main/resources/fonts/pretendard/` with `OFL.txt`. Code
uses monospace at 13 sp with a 20 sp line height. Chrome uses one 13 sp step (15 sp for the
search field, 24 sp for the wordmark fallback) with weights 400/500/600. Spacing follows a
4 dp grid; small controls use 6 dp radii, selection capsules 8 dp, control capsules half their
height, the floating sidebar 20 dp and the popup panes 18 dp, with nested radii kept at
`outer − inset` and never below 4 dp. Labels are sentence case (`Project`, not tracked caps).

The window is 1280×820 dp, centered, with the icon from `logo/stride-icon-256.png`. Content
runs edge to edge: the editor's dark content plane and the tool window's console fill the whole
window, and the chrome floats over them as separate glass capsules. A 280 dp project sidebar
floats `chromeInset` (10 dp) in from the top, left and bottom edges at radius 20; the open tabs
are one glass capsule group (`capsuleHeight` 34 dp, radius half the height) right of it; the
project name capsule sits at the top left of the content and the Search capsule (`⇧⇧`) at the
top right; the bottom row is the file path capsule at the left and one status capsule group at
the right holding `Ln`/`Col`, the file type name, `UTF-8`, a `Read-only` badge and the focus
chip. Capsule height is 34 dp (32 dp on the status row) with `chromeGap` (8 dp) between
capsules; the text column starts `contentGap` (10 dp) right of the sidebar, and the same inset
starts the tool-window content. Two `chromeGap`-wide strips are drag handles: the sidebar's
right edge and the editor/tool window split. Every capsule blocks presses from reaching the
editor under it, while the content between capsules stays clickable.

Chevrons, folder/file icons, the magnifier and the close × are drawn with `Canvas`. The empty
states load `logo/stride-wordmark-dark.svg` (or `logo/stride-wordmark-light.svg` on the light
palette) through `loadSvgPainter` and fall back to the PNG icon. The wordmark SVG has a
transparent background, so it sits directly on the field. No UI library beyond
`compose.desktop.currentOs` is used.

### Neon palette

`DesignTokens` keeps the neon palette (violet `accent`, cyan `accent2`, pink, lime, amber) but
uses it restrained: a focused panel carries the one neon line on its edge, and a selected
element is the soft selection pill. Icons, labels and file glyphs are `muted`; only the
selected row's icon may take `accent`. Nothing else glows, and ordinary glass has no outer
glow.

| token | dark | light |
|---|---|---|
| `neonPink` / `neonPinkGlow` | #FF2E97 / at 55% alpha | #BD2269 / at 55% alpha |
| `neonLime` / `neonLimeGlow` | #B6FF3B / at 55% alpha | #305C00 / at 55% alpha |
| `neonAmber` / `neonAmberGlow` | #FFC83D / at 55% alpha | #704900 / at 55% alpha |
| `accentGlow` / `accent2Glow` | `accent` / `accent2` at 55% alpha | same rule |
| `selectionPillLift` | white 10% | white 72% |
| `selectionPillTint` | `accent` 22% | `accent` 15% |
| `currentLineGlow` | `accent` at 10% alpha | `accent` at 10% alpha |
| `glowRadius` / `glowRadiusLarge` / `focusGlowRadius` | 10 dp / 18 dp / 6 dp | same |
| `neonFadeMillis` | 150 | same |

`selection` (dark: accent at 22%, light: accent at 30%) stays the editor's text-selection
color and is also the terminal's selection fill. On light the blended fill is clearly
darker than white, so the editor and terminal selection stays obvious on its own.

### Neon modifiers

`stride.app.ui.theme.Neon.kt` draws the glow with Skia's `MaskFilter.makeBlur` (sigma is half
the radius). It is the only file that imports `org.jetbrains.skia.*`.

```kotlin
enum class NeonEdge { TOP, BOTTOM }

fun Modifier.neonGlow(color: Color, radius: Dp = DesignTokens.glowRadius,
                      shape: Shape = RectangleShape, alpha: Float = 1f): Modifier
fun Modifier.neonBorder(color: Color, shape: Shape,
                        radius: Dp = DesignTokens.glowRadius, alpha: Float = 1f,
                        overContent: Boolean = false): Modifier
fun Modifier.neonUnderline(color: Color, thickness: Dp = 2.dp,
                           radius: Dp = DesignTokens.glowRadius, alpha: Float = 1f,
                           edge: NeonEdge = NeonEdge.BOTTOM): Modifier
fun Modifier.focusHalo(active: Boolean, color: Color = DesignTokens.accent2): Modifier
@Composable fun rememberSelectionAlpha(active: Boolean): Float
```

A glow color is multiplied by `0.8f * alpha` before drawing, so `alpha = 0f` draws nothing.
`neonBorder` draws behind the content by default; `overContent` keeps the same glow and stroke
but draws them after the content, so a fill the content paints above the border cannot hide it
(the Go to Line popup uses it for its native-path plate).

### Selection signal language

A selected tree row, tab, tool-window tab or popup entry is exactly one signal: a capsule
inset 2–6 dp from its row with a white lift (`selectionPillLift`, white 10% dark / 72% light),
the accent at low alpha (`selectionPillTint`, `accent` at 22% dark / 15% light) and a crisp
1 px rim (`capsuleRim`, white 16% dark / black 14% light, fading down the sides). No underline
sits under a selected tab and no bar sits on a selected row's edge. On light glass the pill is
white-lifted and only faintly tinted, so it reads light rather than as a dark slab; on dark
glass the accent keeps its neon hue. Capsules never bend the backdrop, so tree guide lines and
code behind them stay out of the selection. The selected label is `selectedText` (white dark /
#10151F light), or `text` on tabs, and its file icon takes `accent`.
Hover is the same pill at `HOVER_PILL_ALPHA` (45%) of its strength, so it stays a lighter
version of the selection; `selectionPillAlpha` keeps the stronger of the two, so a hovered row
that gains or loses selection never blinks down to nothing while the fade runs. A focused panel
draws a 1 dp `focusHalo` line on its top edge
and nothing else glows; the tool window drops the halo because its content plane is
edge-to-edge and the focused context is shown in the status capsule. Unselected rows and tabs
stay quiet. The status capsule group renders the `accent2` `FocusChip` without a glow.

The two helpers are `glassCapsule(shape, insetX, insetY, fillAlpha, selectionAlpha)` and
`selectionRow(selected, hovered)`, which fixes the popup capsule at 6/2 dp inset. The pill
fades through `rememberSelectionAlpha`.

Every popup footer uses `PopupHintBar(vararg hints)` (`stride.app.ui`): keycap glyphs followed
by sentence-case words, separated by spacing only, over the 8% white hairline. The welcome
screen's shortcuts come from `welcomeHints(keymap, hasProject)`, which reads every stroke from
`Keymap.strokesFor` (and the `KeyRouter.SEARCH_EVERYWHERE_GESTURE` double-Shift gesture the
keymap does not carry). With a project it shows Search Everywhere ⇧⇧, Go to File ⇧⌘O, Recent
Files ⌘E and Find Action ⇧⌘A; without one it shows Open Folder ⌘O, the stroke that
`GOTO_CLASS` — and therefore opening a folder — is bound to when no session exists.

### Animation rules

Motion is limited to state transitions: the 150 ms selection pill fade, the
200 ms tab-group selection pill slide/stretch (`animateDpAsState` on its left and width with
`standardEasing`), and the popup open fade. Nothing loops; the run window's running glyph no
longer pulses. When `stride.app.ui.LocalAnimationsEnabled` (a `staticCompositionLocalOf { true }`)
is false, `rememberSelectionAlpha` returns 0f/1f immediately and the pill uses `snap()`, so no
animation keeps the test clock busy. `SnapshotRenderer` and the UI tests provide `false`. The
wordmark caret blink keeps its own `LocalWordmarkCaretBlink` switch. `standardEasing`
(`CubicBezierEasing(0.2f, 0f, 0f, 1f)`) is the easing for the motion tokens
`motionShortMillis` 150, `motionMediumMillis` 200 and `motionPopupMillis` 250.

### Liquid glass, the field and the lens

The surface is three layers: **L0 field → L1 glass → L2 the accent** (the focus line and the
selection pill). `App` wraps its
root in `MeshBackground`; the sidebar uses `sidebarGlass` over the background extension, every
floating control capsule uses `capsuleGlass`, transient surfaces use `backdropGlass`, and the
editor body stays `contentTint`, one pre-blended opaque color that is the dark plane under the
floating chrome.

The field is a cool graphite fill with a gentle top-to-bottom luminance falloff (no hue
gradients and no blobs), drawn once per size into a bitmap.

| token | dark | light |
|---|---|---|
| `backgroundLift` / `background` / `fieldBottom` | #1A1B23 / #13141A / #0B0C10 | #FBFCFE / #F3F4F8 / #E9ECF2 |
| `glassFill` / `glassFillStrong` | white 7% / 10% | black 5% / 8% |
| `glassBorderTop` / `glassBorderSide` / `glassBorderBottom` | white 10% / 8% / 6% | black 10% / 8% / 6% |
| `capsuleLift` / `capsuleRim` | white 7% / 16% | white 55% / black 14% |
| `glassBlurRadius` / `glassBlurRadiusMax` | 16 dp / 20 dp (cached panel slices) | same |
| `liquidGlassBlurRadius` | 8 dp (floating glass) | same |
| `glassBase` / `glassBackdropMix` / `glassChromaGain` | #14151B / 0.52 / 0.80 | #F6F7FA / 0.52 / 0.80 |
| `glassBezel` / `glassLensStrength` / `glassAberration` | 18 dp / 17 dp / 0.5 dp | same |
| `glassSpecular` | 0.42 | same |
| `scrim` | black 30% under a popup and at the scroll edge | black 20% |
| `capsuleBezel` / `capsuleLensStrength` / `capsuleEndTaper` | 6 dp / 3.5 dp / 10 dp | same |
| `chromeInset` / `chromeGap` / `contentGap` | 10 dp / 8 dp / 10 dp | same |
| `capsuleHeight` / `statusCapsuleHeight` / `sidebarRadius` | 34 dp / 32 dp / 20 dp | same |
| `sidebarFill` / `capsuleFill` | #14151B at 30% / 58% alpha | #F2F4F8 at 62% / white at 62% |
| `capsuleFrostBlur` / `scrollEdgeBlur` / `scrollEdgeTail` | 20 dp (capsule backdrop) / 14 dp (edge band) / 24 dp (ease-out past each band) | same |
| `backgroundExtensionBlur` | 56 dp (mirrored strip, cached) | same |
| `slabShadow` / `slabShadowColor` | 16 dp / black 55% | 16 dp / black 20% |
| `slabRimTop` / `slabRimBottom` / `slabRimLeft` | white 22% / 6% / 12% | black 10% / 4% / 6% |
| `slabInnerRimInset` / `slabInnerRim` | 2 dp / white 6% | 2 dp / black 3% |
| `chromeLabelScrim` / `chromeLabelScrimInset` | black 45% / 2 dp | white 72% / 2 dp |
| `textOnBusyGlass` | `#C4CBDA` (tab labels over code and secondary popup runs) | `#4C5564` |
| `chromeTextShadow` / `chromeTextStyle` | black 100%, 2 px blur / the style carrying it | white 100% |
| `contentTintAlpha` | 0.92 | 0.92 |
| `glowCoreSigma` / `glowCoreAlpha` | 3 dp / 0.9 | same |
| `glowHaloSigma` / `glowHaloAlpha` | 10 dp / 0.5 | same |
| `glowBloomSigma` / `glowBloomAlpha` | 24 dp / 0.25 | same |
| `textSecondaryOnGlass` | `#919AAC` (muted→text at t=0.08; 4.51:1 on the worst strong glass) | `#4A5261` |
| `nativeGlassTint` | #14151B at 78% (tint of the chrome glass pieces) | #F5F6F9 at 80% |
| `editorGlassTint` | #14151B at 92% (editor and tool-window glass) | #F5F6F9 at 92% |
| `nativeTitleRowHeight` / `nativeTitleLightsInset` | 28 dp / 72 dp (merged title row) | same |
| `contentSurfaceRadius` | 12 dp (editor and tool-window glass corners) | same |
| `contrastText` / `contrastLarge` / `contrastUi` | 4.5 / 3.0 / 3.0 | 4.5 / 3.0 / 3.0 |

`scrollbarStyle` uses white 14% (dark) / black 16% (light) for `unhoverColor`, `accent2`
for `hoverColor` and `motionShortMillis` for the hover duration. Caches keyed by panel,
slab or document geometry also carry the appearance in their key, so a live switch never
keeps a baked image of the old palette.

`stride.app.ui.theme.Glass.kt` draws L0, L1 and the lens. It and `Neon.kt` are the only UI
files that import `org.jetbrains.skia.*` (`TerminalView` keeps its pre-existing cursor glow).

```kotlin
@Composable fun MeshBackground(modifier: Modifier = Modifier,
                               content: @Composable BoxScope.() -> Unit)
val LocalMeshBitmap: ProvidableCompositionLocal<MeshBitmapHolder?>
fun Modifier.capsuleGlass(shape: Shape): Modifier
fun Modifier.scrollEdgeBands(key: () -> Any, cache: EdgeBandCache, topBand: Dp, bottomBand: Dp,
                             clipStart: Dp, blurRadius: Dp = DesignTokens.scrollEdgeBlur,
                             tail: Dp = DesignTokens.scrollEdgeTail,
                             source: (Canvas) -> Unit): Modifier
class EdgeBandCache
fun Modifier.sidebarGlass(shape: Shape): Modifier
fun Modifier.glassCapsule(shape: Shape, insetX: Dp = 6.dp, insetY: Dp = 2.dp,
                          fillAlpha: Float = 0f, selectionAlpha: Float = 0f): Modifier
fun Modifier.glassLensCapsule(shape: Shape, insetX: Dp = 6.dp, insetY: Dp = 2.dp,
                              selectionAlpha: Float = 1f): Modifier
@Composable fun Modifier.selectionRow(selected: Boolean, hovered: Boolean): Modifier
fun Modifier.backdropGlass(shape: Shape = RectangleShape): Modifier
fun Modifier.slabShadow(shape: Shape): Modifier
fun Modifier.glassSlabRims(shape: Shape): Modifier
fun Modifier.labelScrim(active: Boolean): Modifier
fun Modifier.contentTint(): Modifier
fun Modifier.glassBorder(shape: Shape = RectangleShape): Modifier
fun Modifier.backgroundExtension(key: () -> Any, cache: MirroredStripCache,
                                 sourceLeft: Dp, sourceTop: Dp, sourceWidth: Dp,
                                 shapeLeft: Dp, shapeWidth: Dp, shapeRadius: Dp,
                                 luminanceCap: Double = Double.MAX_VALUE,
                                 blurRadius: Dp = DesignTokens.backgroundExtensionBlur,
                                 source: (Canvas) -> Unit): Modifier
val editorFieldLuminance: Double
class MirroredStripCache
data class SidebarChrome(val left: Dp, val width: Dp, val top: Dp, val bottom: Dp)
val LocalSidebarChrome: ProvidableCompositionLocal<SidebarChrome?>
data class EditorInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp,
                        val left: Dp = 0.dp)
val LocalEditorInsets: ProvidableCompositionLocal<EditorInsets>
```

`MeshBackground` draws the field into a bitmap once per size and exposes it through
`LocalMeshBitmap`. `glassCapsule` draws the selection pill directly and never samples the
backdrop; `glassLensCapsule` replaces it on the selected tree row and on the tab group's
selection pill: the same pill over a small 6 dp bezel lens of 3.5 dp whose displacement fades
to zero at the capsule ends, so the pill's edges stay crisp and nothing smears there.

`slabShadow` bakes a soft black 55% rounded-rect shadow, blurred at `0.55 × slabShadow`, into
one image per shape size; the interior of the shape is clipped out of the baked image, so a
translucent fill over content (the sidebar) does not sit on a dark plate. `glassSlabRims` draws
the specular rim over the content: a 1 dp stroke whose gradient is white 22% at the top and 6%
at the bottom, a white 12% highlight down the left edge, and a faint white 6% inner lower rim
inset 2 dp. Its Skia paths, paints and shaders are cached per size. `labelScrim` paints a black
45% band inset 2 dp behind the run tool window's status word.

`capsuleGlass` is the floating control material: a tinted fill (`capsuleFill`, #14151B at 58%)
plus the neutral lift over it, with `slabShadow` and `glassSlabRims`. Its own backdrop pass
(`capsuleFrost`) is the liquid lens blurring the content beneath at `capsuleFrostBlur` (20 dp)
on a 1/8-resolution copy, clipped to the capsule; when the editor scrolls in a layer the
backdrop cannot be sampled and the capsule paints a cached near-opaque frost fill instead.
`sidebarGlass` is the sidebar's material: a more translucent tint (`sidebarFill`, 30%) over the
background extension, plus the same shadow and rim.

**Background extension.** The editor renders the strip of its real document that lies right of
the sidebar's edge into a small offscreen image, mirrors it horizontally across that edge,
blurs it at `backgroundExtensionBlur` (56 dp), and caches it (`MirroredStripCache`) keyed by the
text layout, the spans, both scroll offsets, the sidebar geometry and the editor size. A static
frame only blits the cached image; scrolling or editing rebuilds it once. The mirror axis is
`sourceLeft`, the sidebar's right edge: the source pixel at that edge lands on the edge itself,
and the strip is drawn one screen pixel per source pixel. A rebuild measures and paints only the
lines that intersect the visible rows, and the two raster surfaces are reused across rebuilds,
so the pass costs the same on a 1 000-line and a 10 000-line document. `SidebarChrome` is
provided by `App` so the editor knows where the sidebar sits; a layered editor skips the pass.
The strip is painted at 1/16 resolution before the blur, so only a low-frequency colour field
survives: no line rhythm, glyph shape or stripe. The field is drawn only inside the sidebar's
rounded shape (`shapeLeft`, `shapeWidth`, `shapeRadius`); the window inset and the gap before
the gutter keep the editor's own field colour, so no rectangle or strip shows around the
sidebar. Its mean luminance is capped at `luminanceCap` by subtracting one level from every
pixel, so dense code cannot raise the mean until the glass reads as a grey slab while the
code's soft variation stays in the field. The editor passes `editorFieldLuminance`, the empty
field's own luminance; its current-line glow stops at the sidebar's left edge.

**Scroll edge effect.** A backdrop filter cannot sample the text-field's own drawing, so the
editor paints the effect itself: `scrollEdgeBands` renders the visible lines (text layout and
gutter numbers) into a small offscreen copy of each band, blurs it at `scrollEdgeBlur` (14 dp),
masks it opaque under the band and fades it out over `scrollEdgeTail` (24 dp) past the band's
inner edge, then blits it. Only the lines intersecting the viewport rows are measured and painted
(plus a few for the blur to sample), and each band is its own strip, so the blurred area does not
grow with the window or the document; the raster surfaces are reused and `LineSliceCache` keeps
the measured paragraph across scroll frames, so a scroll step costs the same whatever the file
length. The blurred copy covers the content instead of stacking glyphs
on glyphs, and its soft tail means sharpness never ends on a straight line. `EdgeBandCache` is
keyed by the layout, both scroll offsets and the editor geometry, so a static frame only blits;
scrolling or editing rebuilds it once. The bands are 52 dp (top) and 50 dp (bottom), inside the
40–56 dp the design calls for, and the content viewport itself reaches the window edges, so the
code keeps flowing under the control rows. Two gradients of `scrim` (black 30%), drawn by the
app over the editor, do the fade half; there is no painted bar. The editor's `EditorInsets` still
bound the region the caret scrolls into, so navigation never stops under a control row.

`backdropGlass` runs the liquid glass shader over what is already drawn beneath it.
`GlassShader` compiles one SkSL program (`RuntimeEffect.makeForShader`) whose uniforms are the
panel size, origin, radius, bezel, lens strength, chromatic split, the frosted base color, the
backdrop mix, the chroma gain and specular; the blurred backdrop arrives as the `content`
child shader.

- Thin base: the panel is `glassBase` #14151B with `glassBackdropMix` (52%) of the blurred
  backdrop's luminance and `glassChromaGain` (80%) of its hue. A white and a black stripe behind
  a scrimmed popup reach about **3.1:1** through the glass (measured 3.136 in `PopupGlassTest`;
  3.8:1 directly on the shader path), so code behind a popup is a visible soft impression rather
  than a flat panel. Text carries its own contrast: every popup and chrome text style takes
  `chromeTextStyle`, a black 78%, 3.5 px shadow, which keeps `textSecondaryOnGlass` above 4.5:1
  on the brightest glass.
- Lens: the backdrop is blurred at `liquidGlassBlurRadius` (8 dp) before sampling, so code lines
  keep their shape as a soft blur. Inside the 18 dp bezel the sample coordinate moves inward
  along the rounded-rect normal with a `4t(1-t)` profile peaking at 17 dp, so code lines visibly
  bend near the rim but the bend is zero at the outer 2 dp and at the inner bezel edge. The
  outer taper is passed to the shader in copy pixels (`LENS_TAPER.toPx() / LENS_DOWNSCALE`), so
  it stays 2 dp at any display density. A capsule passes `capsuleEndTaper` so the bend also
  fades to zero at the left and right ends. The red and blue channels split by at most 0.5 dp,
  only inside the bezel.
- Specular: light from the top-left gives a thin bright rim (the outer 28% of the bezel) on
  the upper edge that fades along the sides, with a faint darker inner rim at the bottom.
  Ordinary glass has no outer glow.
- Cost: the shader is evaluated on a 1/4-resolution copy (`LENS_DOWNSCALE`) and scaled back
  up; its size, radius, bezel, strength, aberration and outer-taper uniforms are expressed in
  that copy's pixels. Scaling the runtime shader's own output instead would
  magnify its bezel from the top-left corner, which is why the scale is applied inside the
  filter chain before the shader. The filter is cached per panel size and radius. The interior takes a
  single `content.eval`; only bezel pixels take three. The shader is used by `backdropGlass`
  (popups, the go-to-line field) and by the control capsules (`capsuleFrost`, on a coarser
  1/8 copy); the sidebar does not run its own backdrop pass.

When `RuntimeEffect.makeForShader` fails (or the filter cannot be built), `GlassShader` returns
`null` and the glass falls back to the previous blur-only path with the hairline border.
`GlassModifiersTest` asserts that the SkSL compiles, that the fallback still blurs, that the
lens bends the bezel backdrop (strength 0 against 6 dp on the same shader path), that the four
corner bezels match over a uniform backdrop past the specular rim, that the thin popup glass
shows a scrimmed backdrop at about 3.8:1, and that the selection capsule bends its backdrop in
the middle while the displacement fades to nothing at both ends. It also checks that the
background extension mirrors and blurs the strip right of its edge while leaving the source
strip untouched. `TreeEditorGlassTest` adds the pixel checks for the new chrome: rewriting the
document changes the pixels inside the sidebar (the mirror is real content), the 8 dp gap
between the top capsules follows the code under the floating row (no full-width bar), the top
capsule's rim is brighter than its bottom edge, the selected tab's pill moves to the newly
selected tab, the status capsule's label run clears 4.5:1 over scrolled code (measured 5.0:1)
and on the layered frost fill (5.6:1), and the search capsule's shortcut hint clears it on its
glass (5.5:1). The backdrop path was measured on `ImageComposeScene`: **real**, not the bitmap
fallback.

Text on glass uses `textSecondaryOnGlass`; on the dark palette `muted` (4.13:1) and `subtle`
(2.09:1) are below body contrast on the worst strong glass, so neither may carry information
text there. The light palette's `muted` clears 5.1:1 on its worst strong glass, so both
palettes run their information text through the same tokens.
`accent` is never text. `neonPink` reaches 3.70:1 on `glassFillStrong` and 4.09:1 on
`glassFill`, so it is limited to large text and graphics.

Those ratios come from `stride.app.ui.theme.Contrast.kt`, which the theme and surface tests share:

```kotlin
fun relativeLuminance(color: Color): Double
fun contrastRatio(a: Color, b: Color): Double
fun blendOver(fg: Color, bg: Color): Color
```

`relativeLuminance` linearises each sRGB channel at the 0.04045 threshold; `contrastRatio` returns
the WCAG `(L1 + 0.05) / (L2 + 0.05)`; `blendOver` is the source-over composite and always returns
an opaque color.

The glass is deliberately cheap:

- No `Modifier.blur` or `graphicsLayer { renderEffect }` on persistent panels; a blurred field
  is a cached bitmap that a static frame only blits.
- No looping animation and no blur-radius animation. The field is static.
- One glass layer per region; a popup's scrim covers the glass beneath it.
- No floating surface uses `Modifier.shadow` or `Modifier.clip`: each bakes one shadow image
  (`slabShadow`, with the shape's interior clipped out) and caches its rim paths
  (`glassSlabRims`), so a static capsule costs two image or path draws and no render layer.
- The editor and the tool-window console are `contentTint` content planes; the sidebar is a
  translucent tint over the cached mirror, and every control is a capsule over it.
- The selection capsule lens is a small 6 dp bezel filter, cached per row or pill size; only the
  selected tree row and the tab group's selection pill draw it.
- The background extension is rendered at 1/16 resolution and blurred and cached once per
  document/scroll/size key; a static frame only blits it. A layered editor (≥1 000 lines) skips
  it. Each floating capsule runs one backdrop layer per frame; a layered editor falls back to
  the capsule frost fill. The scroll edge bands are a cached offscreen copy of the document,
  painted once per scroll/edit and blitted per frame.
- The floating glass lens filter is cached per panel size and radius, and is evaluated on a
  1/4-resolution copy. `liquidGlassBlurRadius` must stay at 8 dp or above: at 5 dp Skia stops
  downscaling inside the blur and the Search Everywhere scene costs about 5 ms more.
- Popup scenes pay for one `backdropGlass` layer (the modal panel) on a 1/8-resolution backdrop
  blur.

`SnapshotFrameTimingTest` caps each scenario at `baseline × factor + 1.0 ms` (factor 1.5 for
content, 2.0 for popups). The baselines were measured on a solid background, so they cost
effectively 0 ms; one opaque full-screen field image at 2560×1640 costs 0.948 ms in the
software-raster harness, which is what the 1.0 ms L0 budget admits.

The twelve scenes measured for the frosted capsules (p50, software raster, medians of three
runs at 2560×1640) are 2.58, 13.00, 13.15, 22.44, 18.01, 12.97, 22.47, 15.49, 22.87, 12.85,
13.27 and 12.98 ms. The parent commit (cbdc5dd) measured 1.97, 9.82, 9.88, 18.67, 15.36, 9.39,
18.69, 10.93, 19.83, 8.87, 9.03 and 9.97 ms on the same machine. The editor scenes pay about
3 ms for the two edge bands and the capsule frost layers (one backdrop layer each); the popup
scenes pay 3–4 ms for the same chrome behind the modal. On the GPU path each backdrop layer is
one texture copy plus a blur shader, so the desktop window's cost stays bounded by the content
draw.

The material and the lens are built from three generic effects: a thin base (a neutral floor
carrying a visible luminance and hue impression of the blurred backdrop), lens refraction at the
panel edge (the sample point bends inward along the rounded-rect normal inside the bezel,
fading to zero at the outer rim), and a specular rim lit from the top-left. The SkSL
in `Glass.kt` implements the signed-distance geometry, the bezel profile and the sub-pixel
chromatic split directly; no third-party shader, asset or material library is used.

### Applied surfaces: tool windows, run and terminal

The bottom tool window panel is edge-to-edge content: its `Box` paints `contentTint()` (the same
opaque plane as the editor), the active window's content gets a start inset matching the editor's
text column, a top inset for the switcher row and a bottom inset for the status capsules, and the
panel ends at the window edges with no slab. This is a deliberate deviation from "content to the
edge" under the floating sidebar: the console and terminal keep their text and grid right of the
sidebar's column so they stay readable while the 30% sidebar glass covers that strip. `ToolWindowGlassTest` fixes the empty cell to the
content plane and checks that the switcher is one floating capsule over it.

The tool-window switcher is a capsule group (`capsuleGlass`, radius half its height) offset
`chromeGap` from the panel top and from the content inset; it carries the five tabs Bookmarks,
Find, Run, Structure and Terminal, each a `glassCapsule` inset 2/3 dp, so only the active tab
shows the selection pill. Inactive tab labels are `textSecondaryOnGlass`. `RunToolWindow`'s
session tabs and the Find window's result tabs are further capsule groups inside their content,
with the same language; the Find tab row scrolls horizontally and keeps the selected tab in view.
List rows on the content plane are `selectionRow` pills with a muted file icon, and the Find
window draws a replaced match's new text in `accent2` there (in the popup the same preview uses
`accentOnPopupPlate`).

Run window text sits on the content plane: the header status word (`accent2` while running,
`neonPink` on failure, `neonLime` on pass, `muted` otherwise), method names, and the detail
panel. `text`, `muted`, `accent2` and `neonLime` clear 4.5:1 on the plane; `neonPink` sits on
the black 45% `labelScrim` band (5.14:1). `MethodDetail` stays transparent. The selected method
row is a `selectionRow` pill.

Status glyphs keep their shapes and colors (✓ ✕ ●) and draw no halo; the running pulse was
removed.

The terminal no longer paints `TerminalPalette.background` as a full-canvas rect. Default cells
are left unpainted so the panel tint shows through (`defaultCellBackground` is transparent on
the native glass), while cells with an explicit non-default background are still filled from
`originalAnsi`, never from the contrast-adjusted foreground list; inverse video swaps those
original colours. `TerminalPalette` is appearance-aware: the light palette swaps in a darkened
16-colour ANSI set whose every index clears 4.5:1 on the light terminal plane, and the
foreground, background and cursor follow the palette. The cursor and the selection drawing are
unchanged.

### Applied surfaces: tree, tabs and editor

The project tree is one floating capsule-panel: inset `chromeInset` from the top, left and
bottom edges, radius 20, `slabShadow` + `glassSlabRims` + `sidebarGlass`. There is no baked
texture and no grid; the panel is a translucent tint over the background extension, which is
the editor's own document mirrored from the strip right of its edge (`backgroundExtension`). The
tree keeps its `focusHalo` on the panel's top edge, fed by an `onFocusChanged` read of the panel
subtree in `App` (`KeyContext` has no tree context, so `state.focus` is unchanged). The panel
registers a passive pointer node, so presses on its empty area do not reach the code under it;
its rows and theirs clickables still work. The selected tree row is a `glassLensCapsule` pill
inset 6/2 dp, the same pill lighter on hover and the small lens described above. Unselected
rows draw nothing. The tree
header is `Project` in 13 sp medium; indent guides use `glassBorderSide`. File icons are `muted`,
the selected row's icon `accent`. The panel's right edge carries an invisible `chromeGap`-wide
drag handle clamped to 200–480 dp.

The open tabs are one `capsuleGlass` group right of the sidebar: the selected tab is an inner
`glassLensCapsule` pill and nothing else, and the pill's left and width
animate with `motionMediumMillis` and `standardEasing` when the selection changes
(`snap()` when `LocalAnimationsEnabled` is false), so it slides and stretches between tabs. Tabs
keep their icons, labels, close buttons and horizontal overflow scrolling, including the
scroll-into-view effect for a tab that becomes selected off screen. Inactive labels use
`textOnBusyGlass` (`#C4CBDA`), which clears 4.5:1 over scrolled code; splitter hairlines use
`glassBorderSide`.

The editor body is `contentTint()`, never glass, and fills the whole window: it runs under the
sidebar, the capsule rows and the find bar. `EditorArea` supplies `LocalEditorInsets` (the top
band plus the find bar, the bottom band, the sidebar-derived left inset, and the right inset) and
`TextFileEditor` applies the left inset as the text column's start and the top/bottom insets to
the scrolled content's padding and the caret's viewport, so line 1, the last line and the caret
stay reachable. The text field spans the window and scrolls horizontally, but its drawing is
clipped at the gutter's right edge, so glyphs never appear left of or under the line numbers:
the gutter is an opaque `contentTint` overlay at the text column's start, and the line numbers
stay put while text slides under it up to that edge. The find bar floats under the tab group as a
`capsuleGlass` panel (`capsuleHeight / 2` radius) with a 1 dp `neonUnderline` inset by that
radius. The top row (project capsule, tab group, search capsule) and the bottom row (path
capsule, status capsule group) all use `capsuleGlass`; each capsule registers a passive pointer
node and blocks presses from reaching the editor, while the gaps between capsules stay
clickable. The scroll edge effect blurs the content in the 52 dp top and 50 dp bottom bands
(the editor's own offscreen copy, easing out 24 dp past each band) and fades it toward the field
(`scrim` gradients), so content passes under the floating controls without a bar or a cut line.
Secondary carets paint their selections in `DesignTokens.selection` under the text and a 2 dp
`DesignTokens.accent2` bar at each caret, never blinking, and their lines take the same
`currentLineGlow` with the 2 dp `accent2Glow` edge on visible lines only; the gutter's accent
line number stays on the primary caret's line.

The vertical and horizontal scroll views (`VerticalScrollViewport` / `HorizontalScrollViewport`,
`EditorScrollState`) have two paths. Below `LAYERED_SCROLL_ENTER_LINES` (1 000) the content is
placed without a render layer, so the background extension's offscreen strip is built from the
live document on each change. At or above it the viewport places the content with
`placeRelativeWithLayer`, which drops the per-scroll-frame cost but cannot be sampled, so the
editor skips the mirror and the sidebar shows the content plane under its tint. The mode has
hysteresis (`LAYERED_SCROLL_EXIT_LINES`, 800): once layered, a document must shrink below 800
lines before the sampling path returns, so editing around the threshold does not flip it. The
switch leaves the caret, the selection and `EditorScrollState.value` alone. The threshold comes
from `ScrollFrameTimingTest`: on the 2× snapshot harness the layer-free path costs 8.2 ms per
scroll frame at 1 000 lines (8.3 at 750; the harness floor is ~8.3) and 8.9 / 9.1 / 11.5 ms at
1 200 / 2 400 / 5 000, while the layered path is 6.1 / 6.0 / 6.6 / 8.3 ms. A 10 000-line
document is measured at density 1 (12.3 ms layer-free, 8.8 ms layered): at density 2 its
400 000 px content exceeds Compose's 262 143 px layout limit in the gutter, so it cannot be laid
out on that density at all.
The gutter overlay keeps the editor tint (`contentTint`), redraws the current-line glow, and
draws a 1 dp `glassBorderSide` separator; its line numbers are `muted`, which clears 4.5:1 on
the worst content tint (5.86:1). The go-to-line popup is `backdropGlass(shape)` with
`textSecondaryOnGlass` text on the Skia path, placed below the top band; `TreeEditorGlassTest`
measures the backdrop as real (dense document, adjacent-pixel difference under the blur limit)
with the faint document still visible behind the fill. On the native path `popupPanel` paints
the palette plate over the popup's glass, and the popup's neon border is drawn above that plate
(`neonBorder(..., overContent = true)`).

### Applied surfaces: popups and find bar

Search Everywhere, Find in Path and Recent Files draw their panel with `backdropGlass` in place
of the opaque `raised` fill; a full-screen `scrim` (black 30%) keeps the modal focus while the
blurred backdrop's detail still reaches the interior at about 3.1:1 on the popup path and
3.8:1 on the bare shader path, so code behind a popup reads as a soft, bent impression. Every
popup wraps its text in `CompositionLocalProvider(LocalTextStyle provides chromeTextStyle)`, and
every popup text field merges its own size and colour with `chromeTextStyle` instead of passing a
bare `TextStyle`, so the input glyphs carry the shadow too. On the brightest glass (a scrimmed
white backdrop) the worst core glyph pixel of the Recent Files rows measures 4.9:1 at the fifth
percentile and the Search Everywhere query 8.6:1 (`PopupGlassTest`); secondary popup labels use
`textOnBusyGlass`, because `textSecondaryOnGlass` reaches only about 1.7:1 without the shadow and
about 2.9:1 with it on that backdrop, which is below body contrast. The shadow is an opaque black
2 px blur, which is the compensation the measurement counts. The open fade
runs for `motionPopupMillis` with `standardEasing` and
is applied at draw time: the scrim alpha is multiplied by the fade, and only the panel contents
carry a `graphicsLayer` alpha. The glass node and its ancestors stay layer-free, because an
alpha layer above `backdropGlass` would leave the backdrop filter an empty layer (measured:
adjacent-pixel ratio 0.81 against the 0.5 limit, versus 0.0032 with the draw-side fade). With
animations enabled the content layer stays for the popup's lifetime; removing it mid-run
re-breaks the backdrop. The `shadow` and `clip` that precede `backdropGlass` do not block the
blur (ratios 0.0028 and 0.0008). Popup labels use `text` for primary and `textOnBusyGlass` for
secondary runs; the panel is the liquid glass with radius 18 dp and a soft neutral shadow, with
no neon border. A selected row
is a `selectionRow` pill inset 6/2 dp; hover is the same pill lighter. The Find in Path toggles
and the Search Everywhere tab chips follow the same one-pill language, and no popup draws an
outer glow.

The find bar is a floating `capsuleGlass` panel under the tab group with a 1 dp `neonUnderline`
inset by half the capsule height. Its labels and placeholders are `textSecondaryOnGlass`, the
match counter turns `neonAmber` on a pattern error and the query field gains a `neonPink`
border instead of pink text, and the row buttons use a `glassFill` fill.

### Native macOS glass and the merged title row

On macOS 26 and later, `stride.app.glass.GlassRuntime` replaces the Skia glass material with the
system's own glass views. Everything above is still the fallback path, and it is the only path
in the offscreen tests.

**Library.** `native/glass/stride_glass.mm` is a JNI library built by `:app:buildNativeGlass`
with the JDK 21 toolchain's `jni.h`/`jawt.h` and clang (no Maven dependency). The task runs only
when `clang++ --version` succeeds; otherwise it logs one line and the app uses the Skia glass
path. `-Pstride.nativeGlass=false` skips the native build on purpose. The library resolves
`JAWT_GetAWT` from the running runtime's own `libjawt.dylib`, finds the window through the
JAWT drawing surface (the surface's `windowLayer` chain leads to the `NSWindow` through its
theme-frame delegate), and inserts one container view below the AWT content view. Every piece
is an `NSGlassEffectView` in that container; `hitTest:` returns nil on both our classes so no
pointer event is swallowed (the method encoding is built from `@encode`, so its offsets follow
the arm64/x86_64 ABI). The window is made non-opaque with a clear background only after every
precondition passed, and the layer flags and backgrounds it changes are remembered. `detach`
(and a failed attach) restores the window's opaque flag and background and every remembered
layer, so a fallback never leaves a transparent window behind. The library reports
`supported`, `attach`, `sync`, `detach`, `state` (window opaque flag, window count, base view,
piece frames with their read-back style, tint and z index, and the transparency block with the
window and glass appearances), `layerClass`, `layerTree`,
`setDragRects`/`hitTestDragRegion`/`dragMonitorCount`, `setAppearance`, and the appearance
query/observer/application-class query, including `appAppearanceName` (`NSApp.appearance`'s
name). `setAppearance` only enqueues; `appAppearanceName` is a synchronous query bounded to one
second like the others. The appearance query never asks for the shared
application: it reads `NSApp` and returns "unknown" while it is nil, so calling it before AWT
cannot create a plain `NSApplication` and steal the `NSApplicationAWT` install.

**Threading.** The AppKit thread may be answering an accessibility or menu request that
synchronously calls into Java and waits for the AWT event dispatch thread in a nested run loop.
That loop runs in `AWTRunLoopMode` (`+[ThreadUtilities javaRunLoopMode]` in the runtime), and
the GCD main queue is not serviced in it. No bridge entry may therefore make a Java thread wait
for the main queue — that was the 1.4.0 launch deadlock (EDT in `nativeSupported`, AppKit in
`accessibilityFocusedUIElement`). Every entry follows these rules:

- `supported` needs only the OS version and the `NSGlassEffectView` class, both thread-safe. It
  never touches the AppKit thread and caches the answer for the process.
- Mutations (`attach`, `sync`, `setDragRects`, `detach`, `setAppearance`, the appearance
  observer install and remove) are scheduled with `CFRunLoopPerformBlock` for the default,
  modal-panel, event-tracking and `AWTRunLoopMode` modes and return immediately. Blocks run in
  the order they were added, so a sync follows its attach and a detach follows the last sync.
- Window handles are generated in Java and passed into `attach`, so it needs no answer from the
  AppKit thread. Only the JAWT window lookup stays on the calling thread: JAWT requires its
  lock there, and that lookup never waits for the AppKit thread.
- Queries that must answer synchronously (`state`, `stateKeys`, `layerClass`, `layerTree`,
  `hitTestDragRegion`, `dragMonitorCount`, `appAppearanceName`, the appearance and
  application-class queries) wait on the same run loop for at most one second. On timeout they
  log one line and return the fallback instead of joining the deadlock.

`NativeGlassDeadlockTest` reproduces the shape: a test hook holds the AppKit thread in the
`AWTRunLoopMode` nested loop (as AWT does while it waits for the EDT), then every entry is
called from the event dispatch thread and must return within two seconds. The pre-fix
`dispatch_sync` version of `nativeSupported` times out there.

The transparency block is what makes the glass show through: it finds the Skia drawing
layer under the content view (an `AWTMetalLayer`/`CAMetalLayer`; AWT's own `MTLLayer` is
skipped), and reports whether that layer and every ancestor up to the window frame are
non-opaque with a clear background, the window background alpha, and whether the glass
container sits below the content view. AWT's window backing layer stays opaque even when
the Skia layer is transparent, so `attach` and every `sync` clear `opaque` and the
background on that whole layer chain. Each changed layer's original `opaque` flag and
background is remembered once; `detach` restores them together with the window's opaque flag
and background colour, and a failed attach restores before returning. `NativeGlassSmokeTest`
asserts every value on a real Compose window peer that is never shown: the Skia layer is
non-opaque with background alpha 0, all 4 ancestors are clear, the window background alpha is
0 and the container is below the content view; after detach it reports the window opaque again
and the restored layer count. It also proves the popup shadow sibling: opacity above zero, a
shadow path matching the popup's frame size, ordered below its glass view, and gone from the
state (and the container) once the popup piece leaves the sync. The `layerTree` output
(classes, opaque flags, background alpha) is printed by the test as the evidence.

`NativeGlass` looks for the dylib at `stride.glass.library`, then
`compose.application.resources.dir/libstrideglass.dylib`, then `../Resources` under the runtime.
`appResourcesRootDir` copies it to `strIDE.app/Contents/app/resources/libstrideglass.dylib`
(ad-hoc signed with the bundle), and `:app:run` passes the build path. A missing library,
macOS < 26 or an unfound window logs one line and keeps the Skia glass unchanged.

**Path switch.** `GlassRuntime.prepare(window)` runs before the window is shown and sets the
Compose Skia layer's transparency, so the first frame is transparent where no content is
painted. The Compose panel may build its SkiaLayer only when the peer is created, so
`prepare` also installs a hierarchy listener that catches the layer at insertion time.
`GlassRuntime.attach(window)` runs when the window is shown; if the Skia layer has not been
inserted yet, or the window cannot be found, the next hierarchy event retries (up to a bounded
number of attempts) and the reason is logged once when the path is given up.
`GlassRuntime.active` is provided as `LocalNativeGlass`; every Skia glass pass checks it:

- `MeshBackground` paints nothing, `contentTint()` paints nothing and `capsuleGlass` /
  `sidebarGlass` / `backdropGlass` draw no fill, frost, rim or shadow.
- `glassLensCapsule` keeps only the selection pill (lift, accent tint and rim); the selection
  and hover language (`glassCapsule`, `selectionRow`) is unchanged, and the find bar keeps its
  `neonUnderline`.
- The editor and the tool window body are their own native glass pieces
  (`editor-surface`, `tool-window-surface`) with `editorGlassTint` (#14151B at 92% dark,
  #F5F6F9 at 92% light) and `contentSurfaceRadius` corners. Nothing fills them in Compose, so
  the desktop shows through under the code, the gutter, the console and the terminal. The run
  window's tab strip is a native piece too (`run-tabs`), and `capsuleGlass` has no
  `forceSkia` escape because no opaque panel is left.
- Every popup is drawn by one modifier, `popupPanel(key, shape, skiaShadow)`. On the native path
  that is the popup's own glass piece — `nativeGlass(..., hole = true, shadow = true)` reports
  its window rectangle to `GlassHoles`, asks the native side for its drop shadow, and marks the
  piece as an overlay — under a palette plate: the panel draws `glassBase` at
  `POPUP_PLATE_ALPHA` (0.90) over the glass. The native material's brightness is decided by the
  desktop and AppKit, so popup text contrast rests on the plate instead; the alpha keeps every
  popup text role at 4.5:1 over a white and a black desktop, and `popupPlateFloor(palette)` is
  the worse desktop for the palette's polarity. Every popup is composed outside the clipped
  content it casts a hole into; `EditorArea` hosts the Go to Line popup for that reason (inside
  `TextFileEditor.Content` it was clipped away by its own hole).
  Compose still clips the editor and console drawing to the surface minus those holes
  (`contentClip(shape, holes)`), because native glass is always below Compose drawing and code
  under a popup would otherwise show through the popup's own material.
- The modal scrim uses `scrimSurface(alpha, holes)`: on the native path it cuts every
  reportable hole out of the scrim and scales the palette scrim by
  `nativeScrimFactor` (0.25), so the window stays glass instead of turning flat grey (on the
  Skia path the popup is painted over the full scrim and the fill is unchanged).
- Text over the editor glass keeps 4.5:1 on every plate the glass can composite
  (`editorGlassPlates`: the tint over a white and a black desktop, under the selection,
  current line, glow and find-match overlays, and under the combinations that really stack —
  selection + match, selection + match + glow, current line + match, selection + glow).
  `editorGlassFloor` is the extreme plate of that set (the lightest for the dark palette, the
  darkest for the light one). `glassColorScheme` moves only the syntax tokens that fall short,
  `glassGutterColor` the line numbers, and `mutedOnGlass` / `accent2OnGlass` /
  `neonPinkOnGlass` / `neonLimeOnGlass` / `neonAmberOnGlass` the console labels.
  `TerminalPalette.ansi` maps every ANSI foreground entry through `readableOn` in the same
  way; ANSI backgrounds, including inverse-video blocks, keep the unadjusted
  `TerminalPalette.originalAnsi` and the default cell background stays transparent. The
  find-match plate on the glass uses `GLASS_FIND_MATCH_ALPHA` (0.22) instead of 0.55.

The merged title row and the title-bar client properties are one switch, decided by the pure
`chromePlan(eligible, attached, failed)` in `stride.app.glass`: the properties are applied only
when the native path is eligible (`NativeGlass.supported()`: library loaded and macOS 26), and
the merged layout (`chromeLayout(mergedTitleRow = true)`) exists only after a successful
attach. A failed attach reverts the properties at runtime (AWT applies the same client
properties live through `nativeSetNSWindowStyleBits`) and `active` stays false, so every
fallback branch — macOS < 26, no library, JAWT failure, layer not found — shows the standard
title bar and the v1.3.0 `chromeInset` layout.

**Pieces.** `Modifier.nativeGlass(key, shape, style, tint = nativeGlassTint, hole = false,
shadow = false)`
reports the element's window-relative bounds, its shape's corner radius, the style
(`regular`/`clear`), the tint and the overlay flag to `GlassRegistry`. The registry diffs by key
(`sameGlassPiece`, `diffGlassPieces`) and `App` flushes at most once per changed frame through
`snapshotFlow { revision }` + `withFrameNanos`; unchanged frames never cross the bridge. The
flushed list order is the native z order, overlays (every `hole` piece, i.e. a popup) last, and
the state query reports each view's `zIndex` plus the transparency block's `windowAppearance`
and `glassAppearance` (0 unknown, 1 light, 2 dark). The
reported surfaces are the sidebar, the editor and tool-window bodies, the three top
capsules, the bottom path/status capsules, the find bar, the tool-window switcher, the run
tab strip, the open-folder button and every popup. A piece with `shadow = true` gets a sibling
`NSView` in the container, ordered directly below its glass view: the glass layer sets
`masksToBounds` and would clip a shadow on itself. The sibling has no mask, follows the piece's
frame on every sync (rounded `shadowPath` of the same size and radius) and is removed in the
same sync as the piece, so it carries the drop shadow (black 26%, 24 pt radius, 8 pt below).
The popups use this instead of `Modifier.shadow`, whose penumbra would paint inside the
panel's transparent Compose content and read as an opaque fill. The state query reports the
shadow from the sibling: opacity, shadow-path bounds and whether it is ordered below its glass
view.

`Modifier.windowDragRegion(key)` reports the empty strip of the merged title row to the same
registry. On flush the reported rows are cut by every glass piece on that band
(`dragRectangles`, a pure subtraction), so a capsule keeps its own click, and the result is
sent to `nativeSetDragRects`. The native side stores the rectangles in top-left points and
tests them with `hitTestDragRegion`.

`nativeGlassTint` (#14151B at 78% dark, #F5F6F9 at 80% light) is the chrome pieces' floor over
the system material: in both palettes the primary text and selected text on the chrome keep
4.5:1 over a white and a black desktop, and `textSecondaryOnGlass` keeps it under its opaque
`chromeTextStyle` shadow (black on dark, white on light). This is asserted in
`DesignTokensContrastTest`; the dark palette's accent labels are included, while the light
accents are opaque-surface roles. Popup text is measured against the plate instead (see
`POPUP_PLATE_ALPHA`), not against this tint. The smoke test also reads the style and the tint
back from every material view, so a silently swallowed KVC write cannot pass unnoticed. The
window's `NSAppearance` is set from `AppearanceState` through `GlassRuntime.applyAppearance`, so
the system material and the palette switch appearance together.

**Title row.** The window is created with `create = { ComposeWindow() }`; when the native path
is eligible `GlassRuntime.prepareTitleBar` sets the root pane client properties
`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar` and
`apple.awt.windowTitleVisible=false`, so the content view fills the window and only the native
traffic lights remain. macOS centres them 14 pt below the window top; on the native path the
top capsule row starts at the window top with `nativeTitleRowHeight` (28 dp), which puts its
centre at the same 14 pt, and starts at `nativeTitleLightsInset` (72 dp) or the content column,
whichever is further right. The window title string stays for Mission Control and the Window
menu. The drag strip begins at `nativeTitleLightsInset`, so a press on the traffic lights still
reaches the system buttons.

The native side installs one local `NSEvent` mouse-down monitor per attached window. When the
real event lands on a reported drag rectangle, the monitor calls
`performWindowDragWithEvent:` with that real event on the main thread (no synthetic event, no
screen/window coordinate mix-up, and the EDT never blocks). A double click applies the system
`AppleActionOnDoubleClick` preference (maximize/zoom, minimize or none); only the left button
matches the mask, so right and other buttons fall through. The monitor is removed on detach;
`NativeGlassSmokeTest` asserts the rectangle hit test and the install/remove counts.

```kotlin
// stride.app.glass
val LocalNativeGlass: ProvidableCompositionLocal<Boolean>
data class GlassPiece(val key: String, val x: Float, val y: Float, val width: Float,
                      val height: Float, val cornerRadius: Float,
                      val style: GlassStyle = GlassStyle.REGULAR, val tint: Int? = null,
                      val shadow: Boolean = false, val overlay: Boolean = false)
enum class GlassStyle { REGULAR, CLEAR }
fun sameGlassPiece(first: GlassPiece, second: GlassPiece): Boolean
fun diffGlassPieces(previous: List<GlassPiece>, next: List<GlassPiece>): GlassPieceDiff
fun glassPiecesEqual(previous: List<GlassPiece>, next: List<GlassPiece>): Boolean
fun effectiveCornerRadius(value: Float, width: Float, height: Float): Float
data class DragRect(val x: Float, val y: Float, val width: Float, val height: Float)
fun dragRectangles(regions: List<DragRect>, blockers: List<DragRect>): List<DragRect>
class GlassRegistry(sync: (Long, List<GlassPiece>) -> Int = ...,
                    dragSync: (Long, List<DragRect>) -> Int = ...)
data class NativeGlassView(val key: String, val x: Double, val y: Double, val width: Double,
                           val height: Double, val cornerRadius: Double, val style: Int,
                           val tint: Int?, val shadowOpacity: Double,
                           val shadowPathWidth: Double, val shadowPathHeight: Double,
                           val shadowBelow: Boolean, val zIndex: Int)
data class NativeGlassState(val opaque: Boolean, val windowCount: Int, val baseView: Boolean,
                            val views: List<NativeGlassView>, val layer: NativeLayerState?)
data class NativeLayerState(val drawingLayer: Boolean, val className: String, val opaque: Boolean,
                            val backgroundAlpha: Double, val ancestorsClear: Boolean,
                            val ancestorCount: Int, val windowBackgroundAlpha: Double,
                            val containerBelowContent: Boolean, val contentViewOpaque: Boolean,
                            val containerOpaque: Boolean, val restoredLayers: Int,
                            val shadowViewCount: Int, val windowAppearance: Int,
                            val glassAppearance: Int)
object GlassRuntime { val active: State<Boolean>; fun prepareTitleBar(window: Window)
                      fun prepare(window: Window); fun attach(window: Window); fun detach()
                      fun applyAppearance(appearance: Appearance) }
object SystemAppearance { fun prepareProcess(); fun start(); fun stop() }
object GlassHoles
fun Modifier.nativeGlass(key: String, shape: Shape, style: GlassStyle = GlassStyle.REGULAR,
                         tint: Int? = null, hole: Boolean = false,
                         shadow: Boolean = false): Modifier
fun Modifier.windowDragRegion(key: String = "title-row"): Modifier
// stride.app.ui.theme
enum class Appearance { DARK, LIGHT }
object AppearanceState { var appearance: Appearance; fun set(value: Appearance) }
val DarkPalette: Palette
val LightPalette: Palette
fun paletteOf(appearance: Appearance): Palette
object DesignTokens { val popupPlate: Color }
fun Modifier.contentClip(shape: Shape, holes: List<Rect>): Modifier
fun Modifier.popupPanel(key: String, shape: Shape, skiaShadow: Boolean = true): Modifier
fun Modifier.popupShadow(shape: Shape, elevation: Dp = 24.dp): Modifier
fun Modifier.scrimSurface(alpha: Float, holes: List<Rect>): Modifier
fun Modifier.capsuleGlass(shape: Shape): Modifier
fun editorGlassPlates(palette: Palette): List<Color>
fun editorGlassFloor(palette: Palette): Color
fun editorGlassBase(palette: Palette): Color
fun readableOn(color: Color, background: Color, ratio: Double = 4.5): Color
fun popupPlateFloor(palette: Palette): Color
val POPUP_PLATE_ALPHA: Float
val GLASS_FIND_MATCH_ALPHA: Float
// stride.app.editor
fun activeColorScheme(): ColorScheme
fun glassColorScheme(appearance: Appearance = AppearanceState.appearance): ColorScheme
fun glassGutterColor(appearance: Appearance = AppearanceState.appearance): Color
```

## Offscreen snapshots

`stride.app.ui.UiSnapshotTest` renders the real `App` through `ImageComposeScene` at
2560×1640 px and density 2 (the 1280×820 dp default window) and writes PNGs to
`app/build/ui-snapshots/`
(`01-empty.png`, `02-editor-kotlin.png`, `03-editor-json.png`, `04-search.png`,
`05-search-empty-results.png`, `05-search-empty-results-light.png`,
`06-selection-editor.png`, `07-selection-popup.png`,
`08-find-bar.png`, `09-recent-files.png`, `10-terminal.png`, `11-run-window.png`,
`12-editor-scrolled.png`, `13-search-over-code.png`, `14-context-actions.png`,
`15-multi-caret.png`, `16-folding.png`, `17-find-window.png`, `18-replace-in-path.png`,
`19-problems.png`).
The dark scenes keep their names, and `01-empty-light.png`, `02-editor-kotlin-light.png`,
`04-search-light.png`, `08-find-bar-light.png`, `10-terminal-light.png`,
`11-run-window-light.png`, `12-editor-scrolled-light.png`,
`13-search-over-code-light.png` and `14-context-actions-light.png` render the same scenes on
the light palette.
`UiSnapshotTest` switches AWT to headless before it initialises, and AWT cannot become headful
again in that JVM, so the native tests that need a real AWT peer run in the separate
`nativeTest` task: a fresh JVM with `java.awt.headless=false`. `tasks.test` excludes the
`stride.app.glass.Native*` classes and finalises with `nativeTest`, so `./gradlew test` still
runs them; their outcome no longer depends on whether a UI test ran first.
`NativeGlassSmokeTest` never calls `setVisible`, so no window is ordered front; the snapshot
renderer itself is software-only and never opens a window. The snapshots always take the Skia
glass path.
`UiSnapshotTest` also switches `AppearanceState` inside one live scene and asserts that the
editor text, caret and scroll offset are unchanged while the rendered pixels re-theme.

## Running

```bash
./gradlew :app:run                       # empty state
./gradlew :app:run -PopenPath=<directory>  # opens the directory at startup
```

`-PopenPath` appends the directory as the first program argument (`compose.desktop
.application.args`). The run task's `javaHome` is resolved from the JDK 21 toolchain, so the app
runs on the same JVM as the build regardless of the Gradle daemon's JVM.

`jediterm-core` resolves from the Maven repository declared in `settings.gradle.kts`;
Maven Central alone does not carry it.

## Test coverage

| class | what it pins |
|---|---|
| `ProjectTreeModelTest` | hidden directories, lazy child loading, directories-first order, VFS invalidation |
| `FileEditorManagerTest` | tab reuse, right-then-left neighbour selection, save, recent-file ordering and cap, dispose on project dispose |
| `TextChangeTest` | common prefix/suffix insert/delete/replace and empty edges; incremental highlighter equals a fresh one |
| `HighlightStylesTest` | Kotlin spans map to the dark scheme styles |
| `KeyStrokeParserTest` | key strings: modifiers, letters, digit/function/numpad keys, unknown-token rejection |
| `KeymapTest` | every macOS binding in its context (including ⌃G, ⌃⇧G, ⌃⌘G, ⇧⌘8, both strokes of each folding action, ⌘2, ⌘3, ⌘6, ⌘7, F2, ⇧F2, ⌘F1, ⇧⌘R and ⇧Esc in `LIST_WINDOW`), context-over-global resolution, modal popup contexts never falling back to GLOBAL, non-mac meta↔control swap |
| `KeyStrokeTextTest` | macOS glyph formatting and order, platform modifier labels (`Ctrl+S`, `Shift+Ctrl+O`) for non-macOS keymaps, first bound stroke per action |
| `WelcomeHintsTest` | hints read from the keymap, Double Shift as the literal gesture, and platform modifier labels on a non-macOS keymap |
| `KeyRouterTest` | double Shift, cancellation, repeated modifier downs, the double-Option clone wait (surviving repeated arrows, ended by another modifier), platform shortcuts |
| `ActionDispatcherTest` | undo/redo consumption, next/previous tab wrap, run tool window toggle, hide resets focus, the source-set refusal message, direct runs starting in the file's directory, ⌥↩ import quick fix applied through `applyContextAction` and undone, occurrence and column-mode status messages including clearing a stale failure, the four folding actions reaching the selected editor, the ⌘2/⌘3/⌘6/⌘7 list-window toggles through `toggleToolWindow`, the tool windows registered in key-number order, ⌥F7 adding a whole-word case-sensitive Find tab, ⌘W closing the selected Find tab instead of the editor tab, `closeSession` clearing the Find tabs, ⇧⌘R opening the popup in replace mode, F2/⇧F2 moving onto the file's problems and reporting an empty file, ⌘F1 opening on the problems at the caret and reporting an empty caret, and ⇧⌘]/⇧⌘[ switching the Problems range only while that window has the keyboard |
| `ToolWindowManagerTest` | the three toggle rules, hide, activate, same-id replacement, height clamp |
| `ListToolWindowUiTest` | the Bookmarks window's ↑/↓ and Enter, Delete removing the selected bookmark with the window still visible, Esc handing the keyboard back from a list and from an empty window, the Structure window selecting the caret's entry and Enter moving the caret to it, and a move that keeps a fully visible row in place instead of scrolling it to the top |
| `CompilerMessageParserTest` | the Kotlin 1.8 URI form (with a `%20` path), the Kotlin 1.7 parenthesised form, javac's error and warning form without a column, and the lines it rejects (relative paths, zero positions, an empty message, task output) |
| `LineRemapTest` | fixed lines before and after the changed span, an inserted line shifting the tail, a unique line anchor inside the span, and duplicated, deleted and out-of-range lines mapping to `null` |
| `ProblemsModelTest` | publishing grouping and the version bump, editor and remapped build sources merging, the column-less line range and the identifier range, duplicate removal, a hidden message on a duplicated line, an unreadable base text, Project Errors preferring a recorded result and excluding files without errors, and publishing replacing the build and clearing the records |
| `ProblemNavigationTest` | errors walked before warnings, the forward and backward wrap, the caret inside a problem, records without an offset excluded, and an empty list having no target |
| `ProblemsToolWindowUiTest` | ⌘6 opening the File range and ↵ moving the caret onto the problem, a Project Errors tab click and ↓/↵ opening the file at its line and column, and an edit recomputing the problems after the debounce |
| `KeyFlowUiTest` | double Shift search, ⌘4 then ⇧Esc on the run panel, ⌥F12 terminal, ⌘⇧] next tab, ⌘F find bar stepping, ⌘E recent files, ⌘⇧F find in path, ⌘↵ with an empty query adding no tab, ⌥⌘↵ showing the Replace All confirmation and ↵ applying while Esc cancels, Replace Selected changing the file and dropping its row, a second Replace Selected in the same file, Replace Selected reporting an invalid regex replacement and a non-UTF-8 file while keeping the row, an invalid replacement message followed by a fixed one that confirms, ⌃⇧R run at caret, ⌘F2 stop, the no-test status message, ⌃R and Escape while the context actions popup is open, an Actions-tab entry returning global shortcuts, direct `EDITOR_ESCAPE` dispatch closing the goto-line popup and refocusing the editor, completion filtering/insertion with ⌃R blocked and Escape closing, rename through its popup and Escape cancelling |
| `FindModelTest` | case folding, regular expressions, invalid pattern errors, indexing wrap, replacement order |
| `FindBarStateTest` | span painting and current match, next cycling, replace all, close clearing spans |
| `FindInPathScannerTest` | line/column/body output, size and NUL exclusions, limit and stop, whole-word filtering, the open-editor overlay taking precedence over the disk, and a non-UTF-8 file still being searched leniently |
| `FindToolWindowStateTest` | a tab's scan and result count, an equal search replaced in place, another kind/query/options/replacement adding a tab, the ten-tab cap, closing a tab and clearing everything, the 1000-hit truncation notice, a cancelled scan not writing into the tab that replaced it, a replace plan finishing on another tab keeping its confirmation, and a skipped Replace Selected keeping its row |
| `FindHitNavigationTest` | a hit whose text still matches selecting its range and recording one history entry, and a hit whose text changed going to its line instead |
| `ReplaceInPathTest` | literal, case-insensitive and whole-word edits, overlapping matches keeping the first, regex `$1` expansion and a lookbehind seeing its context, an invalid replacement returning the error, a non-UTF-8 file skipped with `skippedEncoding` while BOM and CRLF survive, a failed disk write counted with the remaining files still written, the editor overlay in the plan, a stale hit planning nothing, plan-time errors for an invalid replacement and a non-UTF-8 hit, an open editor taking one undo step and staying unsaved, read-only editors and files, disk writes, a disk file changed after the plan, and the outcome and confirmation wording in singular and plural |
| `NavigationHistoryTest` | consecutive duplicate suppression, forward truncation on push, back/forward ends, the 100-entry cap, caret update and recording suspension |
| `DeclarationNavigatorTest` | same-file nearest preceding target, package/module ranking, package and module derivation |
| `TestNavigationTest` | source-to-test names in both directions, extension and directory ordering, no counterpart |
| `BookmarksTest` | toggle add/remove, per-file lines, remove, line index and text |
| `FileStructureTest` | nesting depth and line order, filtered and unfiltered rows |
| `NeonModifiersTest` | underline glow direction and `rememberSelectionAlpha` with animations on and off |
| `UiFontTest` | the `uiFont` preference round-trip (stored, missing and unknown values) and the toggle action switching the family and status message |
| `DesignTokensContrastTest` | the WCAG helpers against the WCAG examples and the 0.04045 luminance threshold, every body text token clearing 4.5 on the worst strong glass, `neonPink` clearing only large-text contrast, focus markers clearing 3.0, the selection pill reading at least 1.15 over the native tint on white and black desktops with the selected label at 4.5, `textSecondaryOnGlass` as the first muted→text step that clears 4.5, popup text on its shadow plate clearing 4.5, the unshadowed thin-glass floors (text 3.92, white 4.79, secondary 1.69), the rendered field staying neutral inside its luminance band, both palettes' glass code scheme, gutter, console labels and all 16 ANSI foregrounds clearing 4.5 on every stacked plate in `editorGlassPlates`, and on the native path ANSI 0 as a background keeping its original dark colour, as a foreground moving to the readable one, with inverse video swapping the original lists; the selection, current-line, find-match plates and the caret stay visible and their text stays at 4.5 |
| `GlassModifiersTest` | the neutral glass fill over the field, the brighter top border, the backdrop blur of the sibling behind, deterministic rendering, one blur per panel size and position, the opaque content tint, the opaque cached slice, the backdrop blur staying aligned past the bezel, the SkSL compiling, the top specular rim, the lens bending the bezel backdrop, the thin popup glass showing a scrimmed checkerboard at about 3.8:1, the selection capsule bending in the middle but not at its ends, the rounded clip staying inside the glass, the selection capsule drawing one pill with no edge line at density 2 and hover drawing it lighter, the background extension mirroring and blurring the strip right of its edge while leaving the source untouched, and the blur-only fallback when the shader is unavailable |
| `ToolWindowGlassTest` | on the Skia path only: the tool window content is the opaque content plane with one floating switcher capsule over it, the terminal and run console paint that plane, status glyphs draw no halo, run status colors clear 4.5 on the content plane, and the failed-status word clears it on its label band (the native path has no such plane; its body is the `tool-window-surface` glass piece) |
| `SearchSelectionPillTest` | the Search Everywhere popup on the Skia path in both palettes and on the native path for structure only: the selected tab and row carry one pill, no underline under the tab and no bar on the row's edge, and on Skia the pill reads at least 1.15 against the panel (the offscreen native render has no panel, so its contrast rests on the token model) |
| `SelectionPillAlphaTest` | the pill's strength through the click and arrow-key handovers, with the hovered row never dropping below the hover pill while the selection fades in or out |
| `PopupGlassTest` | the popup fade keeps a real backdrop at the mid and final frames, the shadow/clip chain still blurs, the Search Everywhere panel is translucent and its interior shows a checkerboard backdrop at about 3:1, the fifth percentile of the core glyph contrast clears 4.5 on a bright backdrop for Recent Files rows and the Search Everywhere query, the selected recent row carries one pill with no edge line, the find bar is a floating capsule whose glass follows its backdrop and whose rounded corner is outside the fill, and no fade runs with animations disabled |
| `UiSnapshotTest` | headless PNGs of the empty state, Kotlin and JSON editors, Search Everywhere in both palettes and its empty-query light frame, the find bar, recent files, the terminal, the run window, an editor scrolled to a far column, Search Everywhere over code, the context-actions popup over the `main` line, a Kotlin editor with several carets selecting the same word, a Kotlin editor with one function body folded (asserted folded before rendering), the Find window with a usages tab, the Replace in Path popup, and the Problems window's File range over an editor with an error and a warning, and the Hierarchy window's type hierarchy of a class with a supertype and a subtype |
| `MultiCaretTest` | normalization folding overlaps, shared ends and enclosed collapsed carets; typing replication through repeated characters, selections and out-of-document ranges; movement, word and line ends, deletion, paste distribution and copy ranges; occurrence selection and whole-word matching; caret cloning, blocks, toggling and the per-caret runner; thousands of carets staying under a second |
| `TextFileEditorMultiCaretTest` | field-change replication and undo, consecutive typing merging into one step, selection-only changes dropping the carets and composition-only changes keeping them, program edits and rename restoring them through undo, Esc order, every-caret actions, multi-caret copy/cut/paste, column mode and cloning, read-only behaviour |
| `MultiCaretUiTest` | typing and delete keys through the real composition at every caret, Esc, clipboard paste, column mode typing, the double-Option clone gesture, ⌥⇧-click and ⌥-drag, and a plain click on the primary caret dropping the secondary ones |
| `CaretKeysTest` | which strokes become caret commands with and without secondary carets or column mode and which stay with the field, the virtual column right of a line, and the sorted distinct secondary caret lines |
| `CodeFoldingTest` | fold candidates (imports, block comments and KDoc, multi-line `{}`/`[]` pairs, single-line and string/comment braces skipped, unterminated comments, CRLF import ends, a laminar set, brace extensions only), `FoldView` offsets and rows around one, two, adjacent and nested folds and a fold reaching an unterminated last line, effective folds without partial overlaps, twenty thousand sibling folds under a second, the folded text with no token or search style on a placeholder, and the identity mapping without folds and through the three-argument constructor |
| `TextFileEditorFoldingTest` | Collapse/Expand targets on a function line and the class line, Collapse All keeping nested folds, Expand All, carets moved out of a new fold with the secondary ones kept and no composition lost when nothing moves, folds opened by go to line, program selections and vertical secondary-caret moves, shifted by edits before them (typing, `replaceRange`, `applyEdits`, `renameWord`) and opened by edits inside, removed by typing over them, horizontal snapping for the field and for every caret, read-only folding, and files without candidates |
| `FoldingUiTest` | the field laying out `{...}` with a caret at every fold boundary, the gutter skipping hidden numbers, → at a fold start landing on its end, a placeholder click expanding the fold, ⌘-/⌘= through the window keymap, a fold change not re-running an old Scroll to Center request, an ⌥-drag from a placeholder keeping the fold, and caret following by row below a large fold |
| `VisibleCaretRangesTest` | carets outside the visible lines dropped, spanning selections clipped to the window, collapsed carets contributing only a bar, and a visible bar keeping its clipped range |
| `PopupPlateContrastTest` | every popup text role (including `errorOnPopupPlate`) clears 4.5:1 on the plate over a white and a black desktop in both palettes, the selected and hovered labels on their pills over the plate, and the plate floor is the desktop worse for the palette's polarity |
| `PopupNativeLegibilityTest` | each popup rendered offscreen on the native path over a white and a black desktop in both palettes: every text role the popup can draw (the error description draws `errorOnPopupPlate` and `neonAmber`) clears 4.5:1 on a glyph-free block of its plate and the selected row's label on its pill, and the hole and glass piece leave nothing behind when the scene closes |
| `GlassCompositionTest` | the real `App` composed with the native path forced on: an empty Search Everywhere popup carries the palette plate in every sampled pixel of its reported glass piece (only glyph ink may differ, and ink stays a minority of the filled samples), the popup hole maps into a drawing element's own space, the editor and tool-window pieces are reported with the regular style and the editor tint, grids over the tree, editor and tool window carry no Compose fill, the Go to Line popup's non-glyph samples are the plate or an accent decoration rather than a foreign fill and its text draws outside the editor clip, and the editor clip removes code from under an open popup |
| `NativeGlassOverlayTest` | on a real unshown window: the native side stacks pieces in the registry's list order with overlays last, a surface reported later still stays below a popup, the popup's shadow sits below its own view, and `setAppearance` moves the window's and the glass base view's appearance |
| `SnapshotFrameTimingTest` | the twelve snapshot scenes' frame times under `baseline × factor + 1.0 ms` (1.5 for content, 2.0 for popups) |
| `EditorScrollUiTest` | `EditorScrollState` viewports: caret moved past the right and bottom edges scrolls each axis and returning to `1:1` scrolls back, the gutter keeps its position while the text scrolls horizontally, and a small document stays layer-free while a large one layers and shrinks back across the hysteresis |
| `EditorScrollStateTest` | sub-pixel wheel deltas accumulate, an edge-clamped delta is dropped, the scrollbar adapter excludes the chrome insets from its track, and the layer threshold has hysteresis |
| `ScrollFrameTimingTest` | scroll frames (not static frames) at 1 000 / 5 000 / 10 000 lines on both viewport paths, three sequential runs each, and the layered path cheaper past the threshold |
| `OverlayHitTest` | a press on the status capsule over a scrolled document does not move the caret |
| `GradleCommandTest` | command order and `$0`, spaced test name as one argument, wrapper/PATH fallback, test args, shell fallback |
| `GradleInitScriptTest` | every event line uses the parser prefix and the five kinds, synthesized lines parse as events, the test task cannot be skipped as up-to-date or cached, `strideRunMain` registered for every project and its message without the java plugin |
| `TestEventParserTest` | the five event kinds, TeamCity escape reversal, non-event and malformed lines as output, order preservation |
| `TestLocatorTest` | caret-to-method/class resolution on real repository files, marker counts, Gradle project paths and directories |
| `RunSessionTest` | console versus event lines, tree statuses and failure details, exit code, stop on a fake process, queued stdin, an undelivered line closing stdin and the console line cap, and compiler messages reaching the callback only for a session that was not stopped |
| `RunManagerTest` | last run and starter, one session per configuration, replacing a tab on rerun, stop/rerun on the selected tab and its label, pruning finished sessions while keeping running ones, closing a tab, recent identity, dispose destroying every child, and a Gradle run publishing its compiler messages while a command run does not |
| `RunConfigurationPopupTest` | the kind secondary text, and the working directory added when two rows share a name |
| `GradleRunIntegrationTest` | a real `:core:test` child Gradle through the init script (tagged `slow`, opt-in with `-Dstride.runIntegration=true`) |
| `EditActionsTest` | duplicate, delete, move, line/block comment, indent and unindent with no selection, one line, many lines, the first line and a last line without a newline; `gotoLine` clamping; the word/selection ladder through brackets, strings and lines; toggle case, join lines, start new line, brace matching outside strings and comments, code block start/end, declaration targets, whole-word ranges, hippie candidate order and whole-line ranges |
| `CodeCompletionTest` | identifier order, prefix and camel-hump ranking with duplicates, blank-prefix ordering, the result limit and language keywords |
| `UndoManagerTest` | typing merge within 500 ms, whitespace and deletion separation, redo clearing, `breakMerge`, the entry limit, stored selections, and the multi-caret typing merge with caret identity |
| `TextFileEditorUndoTest` | undo restores text, selection and a freshly highlighted editor; duplicate undo; read-only actions; commenter resolution; goto line parsing and the popup flag |
| `TextFileEditorActionsTest` | extend/shrink selection and its reset, join and start-new-line undo steps, toggle case, brace and code-block caret moves, method up/down, whole-line copy and cut through a fake clipboard, hippie cycling and restart, one-step rename and replace range, `applyEdits` caret mapping, one undo step and rejection of overlapping or read-only edits |
| `ContextActionsTest` | run entry order, then quick fixes before intentions before run entries with the run order unchanged |
| `CommenterEpTest` | `lang.commenter` resolves kotlin and json to `//` and `/* */`, plain text to `null`, one registration per language plugin |
| `TerminalSessionTest` | fake-connector output, ANSI styles, carriage return and erase, resize, write, key encoding, dispose |
| `PtyShellIntegrationTest` | a real `/bin/zsh -lc` PTY writing into the terminal model (skipped when no PTY is available) |
| `BuiltInPluginLoadingTest` | the three bundled descriptors enabled, kt/json file types and highlighter lexers through the extension points, one core extension per interface EP |
| `TextEditorProviderTest` | a file at the highlighted-size limit stays editable and one byte above the limit is read-only |
| `RepositoryAcceptanceTest` | repository tree top level with generated directories hidden, `Main.kt` as a selected highlighted tab, double-Shift search opening `docs/api-search.md`, and the repository Kotlin and search snapshots |
| `TreeEditorGlassTest` | the sidebar showing the mirrored document (rewriting the code changes its pixels), the gap between the top capsules following the code under the row instead of a full-width bar, the top capsule's rim brighter than its bottom edge, the tab-group selection pill moving to the newly selected tab, the editor tint over the same field pixel, the status capsule's label run clearing 4.5:1 over scrolled code (5.6:1), the tree focus halo on focus, the go-to-line backdrop blur, the gutter line-number contrast on the worst content tint, the search capsule's shortcut hint clearing 4.5:1 on its glass (5.5:1), the gap left of the gutter carrying no glyph contrast while the text column does, and the top and bottom edge bands following the code with no single-row luminance jump |
| `HierarchyActionsTest` | the five actions needing an editor, an unfinished index reporting the indexing message, the search popup context, a class's only implementation and a member's overriding ones, the chooser leaving out sub-interfaces and keeping abstract and Java classes, absence reported in project sources, a missing identifier, super method through overrides, a Kotlin member without override and a supertype outside project sources, the chooser for a twice-inherited member and for a class's two interfaces, a class name opening its single supertype, the caret outside a class and function, type declaration from an explicit type, a local parameter and a constructor call, an initializer it cannot infer and a type outside project sources, a class name as its own type, the chooser for duplicate project types, a name with no declaration, type hierarchy around a class name and the innermost-class fallback, type hierarchy outside a class, call hierarchy rooted on a declaration, on the call at the caret and on the enclosing function, call hierarchy outside a function, callees opening on demand, the chooser state clearing when the session closes, a super method and a type hierarchy for a class the buffer has not saved, the edited buffer's line for an open target, a dirty target opened at its current line, the cached graph dropped on close, and one root per declaration for an ambiguous call |
| `TypeHierarchyNodesTest` | the root naming the class with its path and opening at its name, direct subtypes expanding further, a supertype outside the project as a leaf with its written name, a class already on the path as a recursive leaf, 200 children per level ending with a more leaf, several roots collapsed with the title counting them, and more than twenty roots ending with a more leaf |
| `CallHierarchyNodesTest` | callers grouped by member with the call count and first call, a recursive caller, a call outside every member as a file row, callees opening at the declaration in path-then-offset order, a recursive callee, 200 children per level ending with a more leaf, the note counting every declaration that shares the name (a class included), several roots collapsed with the title counting them, deeper expansions reusing the file texts, and an extension function's receiver linking its callers and callees |
| `HierarchyToolWindowStateTest` | a row's depth and expansion marker, expanded nodes opening as soon as they are shown with their children following, children running once across collapse and expand, the searching row while children load, a failing children call showing one error row without a retry, an empty children result as a leaf, a replaced tree ignoring the old tree's load, right expanding to the first child and left collapsing to the parent, a leaf consuming the horizontal keys, toggling, clear, background loads and UI toggles without snapshot conflicts, a cycle in the children graph, a load finishing after clear leaving no entries, and rows opening a node's location |
| `HierarchyToolWindowUiTest` | opening a hierarchy registering and focusing the window with its title and note, right expanding a lazy node and left collapsing it, enter opening a node's location while keeping the window, enter flipping a node without a location, and escape handing the keyboard to the editor with the window still visible |
| `HierarchyKeyFlowUiTest` | ⌃H opening the hierarchy without touching the editor text, ⌘⌥B opening the chooser with enter opening the chosen file, escape closing the chooser and returning the keyboard, ⌘U going to the super method, both type declaration strokes reaching the declared type, ⌃⌥H opening the call hierarchy, the chooser rows naming the container and the path, a letter key not reaching the editor while the chooser is open, and go to implementation from the actions tab |
| `HierarchyKeymapTest` | the six hierarchy strokes resolving in the editor, modifier order not changing the stroke, and the strokes not firing in the global or modal contexts |
| `RefactoringActionsTest` | a refused step's reason, the read-only refusal before the provider runs, the missing-editor `false`, an applied edit's text, caret and status line, one undo restoring the text and the pre-refactoring selection and redo restoring the caret, an invalid name keeping the popup open and a valid one applying, a one-option chooser followed directly, a changed text refusing the edit, overlapping edits refused with their failure message and the text unchanged, secondary carets dropped, running from the Search popup focus, Refactor This's row order, filtering and strokes with the empty message and its prepared steps reused without preparing again, the Rename row opening the rename popup, a chooser's highlights restored on close, and the provider path refusing `.java` and `.txt` files |
| `RefactoringEditsTest` | `applyEdits` with a caret as one undo step whose undo restores the text and the selection before it and whose redo returns the caret, and the mapped caret without one |
| `RefactoringPopupUiTest` | ⌃T opening Refactor This with its entries and strokes and ↓ then ↵ running the chosen kind, the name popup applying a typed name, a rejected name showing its reason with Esc cancelling back to the editor, and an empty name showing `Type a name` while the popup stays |
