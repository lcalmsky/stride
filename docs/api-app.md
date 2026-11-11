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
    EDITOR_DUPLICATE, EDITOR_DELETE_LINE, COMMENT_LINE, COMMENT_BLOCK, MOVE_LINE_UP, MOVE_LINE_DOWN,
    GOTO_LINE, UNDO, REDO, INDENT, UNINDENT, EDITOR_ESCAPE,
    FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, FIND_IN_PATH, GOTO_FILE, RECENT_FILES,
    ACTIVATE_TERMINAL_TOOL_WINDOW,
    EDITOR_SELECT_WORD, EDITOR_UNSELECT_WORD, EDITOR_TOGGLE_CASE, EDITOR_JOIN_LINES,
    EDITOR_START_NEW_LINE, EDITOR_START_NEW_LINE_BEFORE, EDITOR_MATCH_BRACE,
    EDITOR_CODE_BLOCK_START, EDITOR_CODE_BLOCK_END, METHOD_UP, METHOD_DOWN,
    HIPPIE_COMPLETION, CODE_COMPLETION, RENAME_ELEMENT, COPY_LINE, CUT_LINE,
    GOTO_DECLARATION, BACK, FORWARD, FILE_STRUCTURE_POPUP, GOTO_TEST, FIND_USAGES,
    TOGGLE_BOOKMARK, SHOW_BOOKMARKS, ACTIVATE_PROJECT_TOOL_WINDOW, HIDE_ALL_WINDOWS,
    COPY_PATHS, COPY_REFERENCE, SWITCHER, SELECT_IN_PROJECT_VIEW, REOPEN_CLOSED_TAB, CLOSE_OTHER_TABS,
    TOGGLE_UI_FONT,
}

enum class KeyContext {
    GLOBAL, EDITOR, EDITOR_FIELD, FIND_BAR, SEARCH_POPUP, RUN_WINDOW, TERMINAL, CONTEXT_POPUP,
}

data class Binding(val action: ActionId, val context: KeyContext)

class Keymap(val bindings: Map<KeyStroke, List<Binding>>) {
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
| `OPEN_FOLDER` | GLOBAL | ⌘O | (none) | :65 |
| `SAVE` | GLOBAL | ⌘S | `SaveAll` | :67 |
| `CLOSE_TAB` | GLOBAL | ⌘W | `CloseContent` | :69 |
| `SEARCH_EVERYWHERE` | GLOBAL | ⇧⇧ | `SearchEverywhere` | — |
| `NEXT_TAB` | GLOBAL | ⌘⇧] , ⌃→ | `NextTab` | :71, :72 |
| `PREVIOUS_TAB` | GLOBAL | ⌘⇧[ , ⌃← | `PreviousTab` | :74, :75 |
| `HIDE_ACTIVE_WINDOW` | RUN_WINDOW, TERMINAL | ⇧Esc | `HideActiveWindow` | :77 |
| `RUN` | GLOBAL | ⌃R | `Run` | :79 |
| `RUN_CONTEXT` | EDITOR | ⌃⇧R | `RunClass` | :81 |
| `STOP` | GLOBAL | ⌘F2 | `Stop` | :83 |
| `RERUN` | RUN_WINDOW | ⌘R | `Rerun` | :85 |
| `ACTIVATE_RUN_TOOL_WINDOW` | GLOBAL | ⌘4 | `ActivateRunToolWindow` | :87 |
| `EDITOR_DUPLICATE` | EDITOR | ⌘D | `EditorDuplicate` | :89 |
| `EDITOR_DELETE_LINE` | EDITOR | ⌘⌫ | `EditorDeleteLine` | :91 |
| `COMMENT_LINE` | EDITOR | ⌘/ , ⌘(numpad /) | `CommentByLineComment` | :93, :94 |
| `COMMENT_BLOCK` | EDITOR | ⌘⌥/ | `CommentByBlockComment` | :96 |
| `MOVE_LINE_UP` | EDITOR | ⌥⇧↑ | `MoveLineUp` | :98 |
| `MOVE_LINE_DOWN` | EDITOR | ⌥⇧↓ | `MoveLineDown` | :100 |
| `GOTO_LINE` | EDITOR | ⌘L | `GotoLine` | :102 |
| `UNDO` | EDITOR | ⌘Z | `$Undo` | :104 |
| `REDO` | EDITOR | ⌘⇧Z | `$Redo` | :106 |
| `INDENT` | EDITOR | Tab | `EditorIndentSelection` | :108 |
| `UNINDENT` | EDITOR | ⇧Tab | `EditorUnindentSelection` | :110 |
| `EDITOR_ESCAPE` | EDITOR | Esc | `EditorEscape` | :112 |
| `FIND` | EDITOR | ⌘F | `Find` | :114 |
| `REPLACE` | EDITOR | ⌘R | `Replace` | :116 |
| `FIND_NEXT` | EDITOR, FIND_BAR | ⌘G | `FindNext` | :118 |
| `FIND_PREVIOUS` | EDITOR, FIND_BAR | ⌘⇧G | `FindPrevious` | :120 |
| `FIND_IN_PATH` | GLOBAL | ⌘⇧F | `FindInPath` | :122 |
| `GOTO_FILE` | GLOBAL | ⌘⇧O | `GotoFile` | :124 |
| `GOTO_SYMBOL` | GLOBAL | ⌘⌥O | `GotoSymbol` | :126 |
| `GOTO_ACTION` | GLOBAL | ⌘⇧A | `GotoAction` | :128 |
| `RECENT_FILES` | GLOBAL | ⌘E | `RecentFiles` | :130 |
| `ACTIVATE_TERMINAL_TOOL_WINDOW` | GLOBAL | ⌥F12 | `ActivateTerminalToolWindow` | :132 |
| `SHOW_INTENTION_ACTIONS` | EDITOR | ⌥↩ | `ShowIntentionActions` | :134 |
| `CHOOSE_RUN_CONFIGURATION` | GLOBAL | ⌃⌥R | `ChooseRunConfiguration` | :136 |
| `BUILD_PROJECT` | GLOBAL | ⌘F9 | `CompileDirty` | :138 |
| `EDITOR_SELECT_WORD` | EDITOR | ⌥↑ | `EditorSelectWord` | :140 |
| `EDITOR_UNSELECT_WORD` | EDITOR | ⌥↓ | `EditorUnSelectWord` | :142 |
| `EDITOR_TOGGLE_CASE` | EDITOR | ⌘⇧U | `EditorToggleCase` | :144 |
| `EDITOR_JOIN_LINES` | EDITOR | ⌃⇧J | `EditorJoinLines` | :146 |
| `EDITOR_START_NEW_LINE` | EDITOR | ⇧↩ | `EditorStartNewLine` | :148 |
| `EDITOR_START_NEW_LINE_BEFORE` | EDITOR | ⌘⌥↩ | `EditorStartNewLineBefore` | :150 |
| `EDITOR_MATCH_BRACE` | EDITOR | ⌃M | `EditorMatchBrace` | :152 |
| `EDITOR_CODE_BLOCK_START` | EDITOR | ⌘⌥[ | `EditorCodeBlockStart` | :154 |
| `EDITOR_CODE_BLOCK_END` | EDITOR | ⌘⌥] | `EditorCodeBlockEnd` | :156 |
| `METHOD_UP` | EDITOR | ⌃⇧↑ | `MethodUp` | :158 |
| `METHOD_DOWN` | EDITOR | ⌃⇧↓ | `MethodDown` | :160 |
| `HIPPIE_COMPLETION` | EDITOR | ⌥/ | `HippieCompletion` | :162 |
| `CODE_COMPLETION` | EDITOR | ⌃Space | `CodeCompletion` | :164 |
| `RENAME_ELEMENT` | EDITOR | ⇧F6 | `RenameElement` | :166 |
| `COPY_LINE` | EDITOR | ⌘C | `$Copy` | :168 |
| `CUT_LINE` | EDITOR | ⌘X | `$Cut` | :169 |
| `GOTO_DECLARATION` | EDITOR | ⌘B | `GotoDeclaration` | :178 |
| `BACK` | GLOBAL | ⌘[ , ⌘⌥← | `Back` | :180, :181 |
| `FORWARD` | GLOBAL | ⌘] , ⌘⌥→ | `Forward` | :183, :184 |
| `FILE_STRUCTURE_POPUP` | EDITOR | ⌘F12 | `FileStructurePopup` | :186 |
| `GOTO_TEST` | GLOBAL | ⌘⇧T | `GotoTest` | :188 |
| `FIND_USAGES` | EDITOR | ⌥F7 | `FindUsages` | :190 |
| `TOGGLE_BOOKMARK` | EDITOR | F3 | `ToggleBookmark` | :192 |
| `SHOW_BOOKMARKS` | GLOBAL | ⌘F3 | `ShowBookmarks` | :194 |
| `ACTIVATE_PROJECT_TOOL_WINDOW` | GLOBAL | ⌘1 | `ActivateProjectToolWindow` | :196 |
| `HIDE_ALL_WINDOWS` | GLOBAL | ⌘⇧F12 | `HideAllWindows` | :198 |
| `COPY_PATHS` | GLOBAL | ⌘⇧C | `CopyPaths` | :200 |
| `COPY_REFERENCE` | GLOBAL | ⌘⌥⇧C | `CopyReference` | :202 |
| `SWITCHER` | GLOBAL | ⌃Tab , ⌃⇧Tab | `Switcher` | :204, :205 |
| `SELECT_IN_PROJECT_VIEW` | GLOBAL | ⌥F1 | `SelectInProjectView` | :206 |
| `REOPEN_CLOSED_TAB` | — | (none) | — | — |
| `TOGGLE_UI_FONT` | — | (none) | — | — |
| `CLOSE_OTHER_TABS` | — | (none) | — | — |

`KeyRouter` has no window dependency and is unit-tested. Modifier keys map to `ModifierKey`; a
repeated down of an already-pressed modifier is ignored. `DoubleModifierDetector` turns a double
Shift into `SEARCH_EVERYWHERE` (on the second release), and any other key in between cancels it.
Non-modifier downs call `otherKeyPressed()` and then `keymap.actionFor(stroke, focus)`.
`KeyRouter.isMacOs()` reads `os.name`; `KeyRouter(isMac, clock)` builds the platform keymap.

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

`CodeCompletion.candidates(prefix, names, limit)` ranks names with `NameMatcher`;
`CodeCompletion.identifiers(text, excluded)` lists a file's identifiers (leaving out the range
that is exactly the one being completed) and `keywordsFor(fileName)` the Kotlin or Java keywords.

`stride.app.editor.UndoManager` keeps the undo and redo stacks:

```kotlin
data class UndoEntry(
    val offset: Int,
    val oldText: String,
    val newText: String,
    val selectionBefore: TextRange,
    val selectionAfter: TextRange,
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
exactly one non-whitespace character, and no more than 500 ms have passed. `breakMerge` makes the
next `record` start a new entry; the editor calls it before and after every edit action so a whole
action is one undo step.

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
    fun updateValue(newValue: TextFieldValue)   // records the change
    fun undo(): Boolean
    fun redo(): Boolean
    fun gotoLine(input: String): Boolean        // "12" or "12:5"; false for non-numeric input
    fun applyAction(action: ActionId): Boolean
    fun hippieComplete(otherTexts: List<String> = emptyList()): Boolean
    fun renameWord(original: String, replacement: String): Int
    fun replaceRange(start: Int, end: Int, replacement: String)
}
```

`applyAction` handles `EDITOR_DUPLICATE`, `EDITOR_DELETE_LINE`, `MOVE_LINE_UP`,
`MOVE_LINE_DOWN`, `INDENT`, `UNINDENT`, `COMMENT_LINE`, `COMMENT_BLOCK`, `UNDO`, `REDO`,
`GOTO_LINE`, `EDITOR_ESCAPE`, `EDITOR_SELECT_WORD`, `EDITOR_UNSELECT_WORD`,
`EDITOR_TOGGLE_CASE`, `EDITOR_JOIN_LINES`, `EDITOR_START_NEW_LINE`,
`EDITOR_START_NEW_LINE_BEFORE`, `EDITOR_MATCH_BRACE`, `EDITOR_CODE_BLOCK_START`,
`EDITOR_CODE_BLOCK_END`, `METHOD_UP`, `METHOD_DOWN`, `COPY_LINE`, `CUT_LINE` and
`HIPPIE_COMPLETION` (without other editors' text). A read-only
editor consumes the edit actions without changing the text, and a file whose language has no
commenter consumes the comment actions. Everything else returns `false`.

`EDITOR_SELECT_WORD` keeps a stack of previous selections (cleared whenever the text or the
selection changes by another route) and `EDITOR_UNSELECT_WORD` pops it. `COPY_LINE` writes the
caret line, newline included, through `TextClipboard`; `CUT_LINE` also deletes it. Both return
`false` when a real selection exists or the clipboard refuses the write, so `BasicTextField`
keeps its own copy and cut for selections. `hippieComplete` replaces the prefix before the caret
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
`applyAction` result, which is `false` when no editor is selected. `HIPPIE_COMPLETION` also hands
the other open editors' text to the editor.

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
with the old name and selected; Enter applies `applyRename`, which sets the status bar to
`Renamed N occurrences`, or to `Nothing to rename` when the name did not change. Enter on an
empty field keeps the popup open and shows a hint.

### Caret following

One effect watching `value.selection` keeps the caret visible on both axes, so every path
that moves it — arrow keys, `gotoLine`, find-bar stepping, undo/redo and edit actions — scrolls
the same way. When the caret leaves the viewport by more than a 24 dp margin the editor scrolls
to bring it back; a horizontal scrollbar sits along the bottom edge next to the vertical one.
The gutter (line numbers) follows only the vertical scroll and stays outside the horizontal
scroll, which wraps the text body alone.

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

@Composable fun ToolWindowPanel(manager: ToolWindowManager, onFocusContext: (KeyContext) -> Unit, modifier: Modifier = Modifier)
```

`toggle` follows the `ActivateToolWindow` rule: hidden or a different active id → `activate`;
visible, active and `focused` → `hide`; visible, active and not `focused` → only
`focusRequests++`.

`ToolWindowPanel` is a 28 dp tab strip (title, active tab in `text` with a 2 dp `accent` underline,
inactive in `muted`) over the active window's content. The panel root is focusable and requests
focus whenever `focusRequests` changes, reporting its context through `onFocusContext`.

`AppState` registers two windows in its constructor: `run` (title Run, context `RUN_WINDOW`) and
`terminal` (title Terminal, context `TERMINAL`), each showing a placeholder line. `⌘4`, `⌥F12`
and `⇧Esc` drive them through `dispatchAction`. The panel sits below the tree/editor row and
above the status bar, spanning the full window width; a 4 dp horizontal splitter carries the drag
into `height` clamped to 120 dp … 80% of the window height. `closeSession()` hides the panel and
resets `focus`.

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

`FindBar` sits below the tab strip, on a `DesignTokens.surface` strip with an accent2
`neonUnderline` on its bottom edge. The query field carries `testTag("find-query")`, the
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
)

object FindInPathScanner {
    const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024
    const val MAX_HITS: Int = 200
    fun scan(
        files: List<IndexedFile>, query: String, options: FindOptions,
        limit: Int = MAX_HITS, shouldStop: () -> Boolean = { false },
    ): List<FindInPathHit>
}

@Composable fun FindInPathPopup(
    index: FileNameIndex,
    initialQuery: String = "",
    initialWholeWord: Boolean = false,
    onChoose: (FindInPathHit) -> Unit,
    onClose: () -> Unit,
)

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
Rows read `relativePath:line` with the line body, the matched characters in `neonAmber`; the
status line reads `n results`, `searching…` or `200+ results (truncated)`. Enter opens the
file and selects the match in the editor.

`⌘E` opens `RecentFilesPopup` with `FileEditorManager.recentFiles` — newest first, at most 50
entries, closed files still listed. The first selection is the second entry; Up/Down move,
Enter opens, Esc closes. Both popups follow the Search Everywhere popup frame: header, result
list, footer hints and the neon selection signal.

`FileEditorManager.recentFiles` is a snapshot list updated by `openFile` (new and existing
tabs) and by `selectNext`/`selectPrevious`; `closeFile` leaves entries in place and `dispose`
clears them. `AppState` adds `findBar`, `findInPathVisible` and `recentFilesVisible`, and
`closeSession()` resets all three.

Whole-word matching (`FindOptions.wholeWord`, the `W` toggle in the popup) keeps only matches
whose two sides are not identifier characters. `FindUsages` (`⌥F7`) opens this popup prefilled
with the identifier at the caret and the toggle on, through `AppState.openFindInPath`, which
bumps `findInPathGeneration` so the popup is recreated.

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

data class ChooserRow<T>(val text: String, val value: T)
@Composable fun <T> TargetChooserPopup(title: String, rows: List<ChooserRow<T>>,
                                       onChoose: (T) -> Unit, onClose: () -> Unit)
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
ANSI indices 0–15 come from `TerminalPalette.ansi` (the same neon family as `DesignTokens`);
256-color and truecolor values use the RGB `TerminalColor` directly.

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
files declare no colors of their own. The base palette is a cool graphite with a very low
blue-violet chroma: `background` #13141A with `backgroundLift` #1A1B23 at the top of the
field and `fieldBottom` #0B0C10 at the bottom, `surface` #0F1015 (the editor and console
tint, darker than the chrome glass), `raised` #21232B (hover and key caps), `border`
#2F313A, `text` #E6E8F0, `selectedText` (white), `muted` #8A93A6, `subtle` #5A6274,
`accent` (#7C5CFF), `accent2` (#00D1FF) and a selection of the accent at 22% alpha. The
dark theme is the only theme.

Chrome text uses the bundled Pretendard (SIL OFL 1.1) at 13 sp; `DesignTokens.uiFont` reads
`UiFont.family`, so the whole UI follows a runtime choice between Pretendard and the system
default. `ActionId.TOGGLE_UI_FONT` (`Switch UI Font`, no default stroke, reachable from the
Actions tab) flips it immediately and stores the choice with `java.util.prefs.Preferences` in
node `stride` under `uiFont` (`pretendard` / `system`), then shows `UI font: Pretendard` or
`UI font: System` in the status bar. `UiFontPreference` takes the node so tests inject their
own. The font files live in `app/src/main/resources/fonts/pretendard/` with `OFL.txt`. Code
uses monospace at 13 sp with a 20 sp line height. Chrome uses one 13 sp step (15 sp for the
search field, 24 sp for the wordmark fallback) with weights 400/500/600. Spacing follows a
4 dp grid; small controls use 6 dp radii, capsules 8 dp and the floating panes 18 dp, with
nested radii kept at `outer − inset` and never below 4 dp. Labels are sentence case (`Project`,
not tracked caps).

The window is 1280×820 dp, centered, with the icon from `logo/stride-icon-256.png`. It is a
36 dp top bar (logo, project name, clickable Search pill with the `⇧⇧` hint), a 280 dp
project tree with a draggable splitter clamped to 200–480 dp, a tab strip and editor, and a
24 dp status bar showing the relative path, `Ln`/`Col`, the file type name, `UTF-8` and a
`Read-only` badge when the editor is read-only.

Chevrons, folder/file icons, the magnifier and the close × are drawn with `Canvas`. The empty
states load `logo/stride-wordmark-dark.svg` through `loadSvgPainter` and fall back to the PNG
icon. The wordmark SVG has a transparent background, so it sits directly on the field. No UI
library beyond `compose.desktop.currentOs` is used.

### Neon palette

`DesignTokens` keeps the neon palette (violet `accent`, cyan `accent2`, pink, lime, amber) but
uses it restrained: glow appears only on the selected or focused element, as the one neon line
on its edge. Icons, labels and file glyphs are `muted`; only the selected row's icon may take
`accent`. Nothing else glows, and ordinary glass has no outer glow.

| token | value |
|---|---|
| `neonPink` / `neonPinkGlow` | #FF2E97 / #FF2E97 at 55% alpha |
| `neonLime` / `neonLimeGlow` | #B6FF3B / #B6FF3B at 55% alpha |
| `neonAmber` / `neonAmberGlow` | #FFC83D / #FFC83D at 55% alpha |
| `accentGlow` / `accent2Glow` | `accent` / `accent2` at 55% alpha |
| `selectionBar` | `accent2` |
| `currentLineGlow` | `accent` at 10% alpha |
| `glowRadius` / `glowRadiusLarge` / `focusGlowRadius` | 10 dp / 18 dp / 6 dp |
| `selectionBarWidth` | 3 dp |
| `neonFadeMillis` | 150 |

`selection` (the accent at 22%) stays the editor's text-selection color and is now also the
terminal's selection fill.

### Neon modifiers

`stride.app.ui.theme.Neon.kt` draws the glow with Skia's `MaskFilter.makeBlur` (sigma is half
the radius). It is the only file that imports `org.jetbrains.skia.*`.

```kotlin
enum class NeonEdge { TOP, BOTTOM }

fun Modifier.neonGlow(color: Color, radius: Dp = DesignTokens.glowRadius,
                      shape: Shape = RectangleShape, alpha: Float = 1f): Modifier
fun Modifier.neonBorder(color: Color, shape: Shape,
                        radius: Dp = DesignTokens.glowRadius, alpha: Float = 1f): Modifier
fun Modifier.neonUnderline(color: Color, thickness: Dp = 2.dp,
                           radius: Dp = DesignTokens.glowRadius, alpha: Float = 1f,
                           edge: NeonEdge = NeonEdge.BOTTOM): Modifier
fun Modifier.focusHalo(active: Boolean, color: Color = DesignTokens.accent2): Modifier
fun Modifier.neonSelectionBar(color: Color = DesignTokens.selectionBar, alpha: Float = 1f): Modifier
@Composable fun rememberNeonAlpha(active: Boolean): Float
```

A glow color is multiplied by `0.8f * alpha` before drawing, so `alpha = 0f` draws nothing.

### Selection signal language

A selected tree row, tab, tool-window tab or popup entry is a capsule inset 2–6 dp from its
row: a neutral interior lift (`capsuleLift`, white 7%), a crisp top-lit 1 px rim
(`capsuleRim`, white 16%, fading down the sides) and one 2 dp `accent2` line on its left edge
(tabs use the bottom edge) with a soft 2.5 dp-blur glow at 30% alpha. Capsules never bend the
backdrop, so tree guide lines and code behind them stay out of the selection. The selected
label is `selectedText` (white) and its file icon takes `accent`. Hover is a faint neutral
step (`hoverFill`, white 4%). A focused panel draws a 1 dp `focusHalo` line on its top edge
and nothing else glows. Unselected rows and tabs stay quiet. `StatusBar` takes an optional
`focusLabel` and renders it as a right-aligned `accent2` `FocusChip` without a glow;
`EditorArea`, `ProjectTreePanel` and `ToolWindowPanel` draw the halo when focused.

The two helpers are `glassCapsule(shape, insetX, insetY, fillAlpha, lineEdge, lineAlpha)` and
`selectionRow(selected, hovered)`, which fixes the popup capsule at 6/2 dp inset with a left
line. The line and capsule alpha fade through `rememberNeonAlpha`.

Every popup footer uses `PopupHintBar(vararg hints)` (`stride.app.ui`): keycap glyphs followed
by sentence-case words, separated by spacing only, over the 8% white hairline. The welcome
screen's shortcuts come from `welcomeHints(keymap, hasProject)`, which reads every stroke from
`Keymap.strokesFor` (and the `KeyRouter.SEARCH_EVERYWHERE_GESTURE` double-Shift gesture the
keymap does not carry). With a project it shows Search Everywhere ⇧⇧, Go to File ⇧⌘O, Recent
Files ⌘E and Find Action ⇧⌘A; without one it shows Open Folder ⌘O, the stroke that
`GOTO_CLASS` — and therefore opening a folder — is bound to when no session exists.

### Animation rules

Motion is limited to state transitions: the 150 ms selection capsule and neon line fade, and
the popup open fade. Nothing loops; the run window's running glyph no longer pulses. When
`stride.app.ui.LocalAnimationsEnabled` (a `staticCompositionLocalOf { true }`) is false,
`rememberNeonAlpha` returns 0f/1f immediately and no animation keeps the test clock busy.
`SnapshotRenderer` and the UI tests provide `false`. The wordmark caret blink keeps its own
`LocalWordmarkCaretBlink` switch. `standardEasing` (`CubicBezierEasing(0.2f, 0f, 0f, 1f)`) is
the easing for the motion tokens `motionShortMillis` 150, `motionMediumMillis` 200 and
`motionPopupMillis` 250.

### Liquid glass, the field and the lens

The surface is three layers: **L0 field → L1 glass → L2 the one neon line**. `App` wraps its
root in `MeshBackground`; slabs and capsules drawn on it use `glassPanel`/`glassCapsule`;
transient surfaces use `backdropGlass`. Everything else is opaque: the editor, terminal and
console use `contentTint`, one pre-blended opaque color, so the field is not drawn behind them.

The field is a cool graphite fill with a gentle top-to-bottom luminance falloff (no hue
gradients and no blobs), drawn once per size into a bitmap.

| token | value |
|---|---|
| `backgroundLift` / `background` / `fieldBottom` | #1A1B23 / #13141A / #0B0C10 |
| `glassFill` / `glassFillStrong` | white 7% / 10% |
| `glassBorderTop` / `glassBorderSide` / `glassBorderBottom` | white 10% / 8% / 6% |
| `hoverFill` / `capsuleLift` / `capsuleRim` | white 4% / 7% / 16% |
| `glassBlurRadius` / `glassBlurRadiusMax` | 16 dp / 20 dp (slabs) |
| `liquidGlassBlurRadius` | 14 dp (floating glass) |
| `glassBase` / `glassBackdropMix` / `glassChromaGain` | #17181E / 0.06 / 0.35 |
| `glassBezel` / `glassLensStrength` / `glassAberration` | 12 dp / 6 dp / 0.5 dp |
| `glassSpecular` | 0.42 |
| `contentTintAlpha` | 0.92 |
| `glowCoreSigma` / `glowCoreAlpha` | 3 dp / 0.9 |
| `glowHaloSigma` / `glowHaloAlpha` | 10 dp / 0.5 |
| `glowBloomSigma` / `glowBloomAlpha` | 24 dp / 0.25 |
| `textSecondaryOnGlass` | `#919AAC` (muted→text at t=0.08; 4.51:1 on the worst strong glass) |
| `contrastText` / `contrastLarge` / `contrastUi` | 4.5 / 3.0 / 3.0 |

`scrollbarStyle` uses white 14% for `unhoverColor`, `accent2` for `hoverColor` and
`motionShortMillis` for the hover duration.

`stride.app.ui.theme.Glass.kt` draws L0, L1 and the lens. It and `Neon.kt` are the only UI
files that import `org.jetbrains.skia.*` (`TerminalView` keeps its pre-existing cursor glow).

```kotlin
@Composable fun MeshBackground(modifier: Modifier = Modifier,
                               content: @Composable BoxScope.() -> Unit)
val LocalMeshBitmap: ProvidableCompositionLocal<MeshBitmapHolder?>
fun Modifier.glassPanel(shape: Shape = RectangleShape): Modifier
fun Modifier.glassCapsule(shape: Shape, insetX: Dp = 6.dp, insetY: Dp = 2.dp,
                          fillAlpha: Float = 0f, lineEdge: CapsuleEdge = CapsuleEdge.LEFT,
                          lineAlpha: Float = 1f): Modifier
@Composable fun Modifier.selectionRow(selected: Boolean, hovered: Boolean): Modifier
fun Modifier.backdropGlass(shape: Shape = RectangleShape): Modifier
fun Modifier.contentTint(): Modifier
fun Modifier.glassBorder(shape: Shape = RectangleShape): Modifier
```

`MeshBackground` draws the field into a bitmap once per size and exposes it through
`LocalMeshBitmap`. `glassPanel` blurs its slice of that bitmap once and bakes the `glassFill`
into an opaque cached image keyed by panel size, field size, root position and field version;
each frame only copies that image and draws the hairline rim. `glassCapsule` draws the lift
and rim directly: it never samples or blurs the backdrop.

`backdropGlass` runs the liquid glass shader over what is already drawn beneath it.
`GlassShader` compiles one SkSL program (`RuntimeEffect.makeForShader`) whose uniforms are the
panel size, origin, radius, bezel, lens strength, chromatic split, the frosted base color, the
backdrop mix, the chroma gain and specular; the blurred backdrop arrives as the `content`
child shader.

- Frosted base: the panel is `glassBase` #17181E with only `glassBackdropMix` (6%) of the
  backdrop's luminance and `glassChromaGain` (35%) of its hue. Backdrop detail through the
  glass stays under 1.3:1, and popup text clears 4.5:1 on the base whatever is behind it.
- Lens: the backdrop is blurred at `liquidGlassBlurRadius` (14 dp) before sampling. Inside the
  12 dp bezel the sample coordinate moves inward along the rounded-rect normal with a
  `4t(1-t)` profile peaking at 6 dp, so the bend is zero at the outer 2 dp and at the inner
  bezel edge and never exceeds 6 dp. The outer taper is passed to the shader in copy pixels
  (`LENS_TAPER.toPx() / LENS_DOWNSCALE`), so it stays 2 dp at any display density. The red and
  blue channels split by at most 0.5 dp, only inside the bezel.
- Specular: light from the top-left gives a thin bright rim (the outer 28% of the bezel) on
  the upper edge that fades along the sides, with a faint darker inner rim at the bottom.
  Ordinary glass has no outer glow.
- Cost: the shader is evaluated on a 1/4-resolution copy and scaled back up
  (`LENS_DOWNSCALE`); its size, radius, bezel, strength, aberration and outer-taper uniforms
  are expressed in that copy's pixels. Scaling the runtime shader's own output instead would
  magnify its bezel from the top-left corner, which is why the scale is applied inside the
  filter chain before the shader. The filter is cached per panel size and radius. The interior takes a
  single `content.eval`; only bezel pixels take three.

When `RuntimeEffect.makeForShader` fails (or the filter cannot be built), `GlassShader` returns
`null` and `backdropGlass` falls back to the previous blur-only glass with the hairline border.
`GlassModifiersTest` asserts that the SkSL compiles, that the fallback still blurs, that the
lens bends the bezel backdrop (strength 0 against 6 dp on the same shader path), that the four
corner bezels match over a uniform backdrop, and that the frosted base keeps backdrop detail
under 1.3:1. The backdrop path was measured on `ImageComposeScene`:
**real**, not the bitmap fallback.

Text on glass uses `textSecondaryOnGlass`; `muted` (4.13:1) and `subtle` (2.09:1) are below
body contrast on the worst strong glass, so neither may carry information text there.
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

- No `Modifier.blur` or `graphicsLayer { renderEffect }` on persistent panels; the blur is a
  cached bitmap (`GlassCacheStats.panelBlurCount` must not grow frame to frame).
- No looping animation and no blur-radius animation. The field is static.
- One glass layer per region; a popup's scrim covers the glass beneath it.
- The editor, terminal and console are `contentTint`, not glass. The tint is opaque, so it takes
  the opaque fill path; its single color does not follow the field.
- The capsule lens is baked into the cached slice; only the selected row or active tab pays for
  it, and it is paid once per size and position. Unselected capsules draw nothing.
- The floating glass lens filter is cached per panel size and radius, and is evaluated on a
  1/4-resolution copy.
- The backdrop blur runs on a 1/8-resolution copy of the backdrop, not at full resolution.

`SnapshotFrameTimingTest` caps each scenario at `baseline × factor + 1.0 ms` (factor 1.5 for
content, 2.0 for popups). The baselines were measured on a solid background, so they cost
effectively 0 ms; one opaque full-screen field image at 2560×1640 costs 0.948 ms in the
software-raster harness, which is what the 1.0 ms L0 budget admits.

With the liquid glass the twelve scenes measure (p50, software raster, unchanged baselines):
1.48, 2.37, 2.78, 18.37, 13.17, 2.27, 16.92, 2.86, 13.71, 2.64, 2.87 and 2.30 ms. The popup
scenes stay under their 2.0× limits because the lens filter runs on a 1/4-resolution copy; the
GPU path evaluates the same SkSL as one shader pass.

The material and the lens are built from three generic effects: a frosted base (a mostly opaque
neutral fill carrying a faint luminance and hue impression of the blurred backdrop), lens
refraction at the panel edge (the sample point bends inward along the rounded-rect normal inside
the bezel, fading to zero at the outer rim), and a specular rim lit from the top-left. The SkSL
in `Glass.kt` implements the signed-distance geometry, the bezel profile and the sub-pixel
chromatic split directly; no third-party shader, asset or material library is used.

### Applied surfaces: tool windows, run and terminal

The bottom tool window panel owns the reading tint: its `Column` is `contentTint()` for the
whole panel, so `RunToolWindow` and `TerminalView` draw no background of their own. That leaves
one opaque tint per pixel, and the terminal and console pixel assertions in `ToolWindowGlassTest`
fix that single value.

The tool-window tab strip `Row` is `glassPanel()`; each tab is a `glassCapsule` inset 2/4 dp
with the neon line on its bottom edge, so only the active tab shows a capsule and a line.
Inactive tab labels are `textSecondaryOnGlass`.

Run window text sits directly on the tint: the header status word (`accent2` while running,
`neonPink` on failure, `neonLime` on pass, `muted` otherwise), method names, and the detail
panel. `MethodDetail` stays transparent, so its text is read on the single tint layer; on the
worst tint (the lift under `contentTintAlpha`) `neonPink` reaches 5.25:1 and `muted` 5.86:1.
The selected method row is a `selectionRow` capsule with the accent2 line.

Status glyphs keep their shapes and colors (✓ ✕ ●) and draw no halo; the running pulse was
removed.

The terminal no longer paints `TerminalPalette.background` as a full-canvas rect. Default cells
are left unpainted so the panel tint shows through, while cells with an explicit non-default
background are still filled. `TerminalPalette`, the cursor and the selection drawing are
unchanged.

### Applied surfaces: tree, tabs and editor

The project tree and the tab strip are `glassPanel`; the tree keeps its `focusHalo`, fed by an
`onFocusChanged` read of the panel subtree in `App` (`KeyContext` has no tree context, so
`state.focus` is unchanged). The selected tree row and the active tab are `glassCapsule`s inset
6/2 dp (tabs 4/4 dp) with the accent2 line on the left (tabs bottom) and a `hoverFill` step on
hover. Unselected rows and tabs draw nothing, so the line is the only bright mark. The tree
header is `Project` in 13 sp medium; inactive tab labels use `textSecondaryOnGlass`; splitter
hairlines and indent guides use `glassBorderSide`. File icons are `muted`, the selected row's
icon `accent`.

The editor body is `contentTint()`, never glass: `EditorArea` has no fill, so the opaque tint is
painted once by `TextFileEditor`. The gutter no longer paints `DesignTokens.gutter`; it
keeps the same tint and draws a 1 dp `glassBorderSide` separator, and its line numbers are
`muted`, which clears 4.5:1 on the worst content tint (5.86:1). The go-to-line popup is
`backdropGlass(shape)` with `textSecondaryOnGlass` text; `TreeEditorGlassTest` measures the
backdrop as real (dense document, adjacent-pixel difference under the blur limit) with the faint
document still visible behind the fill.

### Applied surfaces: popups and find bar

Search Everywhere, Find in Path and Recent Files draw their panel with `backdropGlass` in place
of the opaque `raised` fill; the full-screen `scrim` under them stays, so the blur samples the
scrimmed surface beneath. The open fade runs for `motionPopupMillis` with `standardEasing` and
is applied at draw time: the scrim alpha is multiplied by the fade, and only the panel contents
carry a `graphicsLayer` alpha. The glass node and its ancestors stay layer-free, because an
alpha layer above `backdropGlass` would leave the backdrop filter an empty layer (measured:
adjacent-pixel ratio 0.81 against the 0.5 limit, versus 0.0032 with the draw-side fade). With
animations enabled the content layer stays for the popup's lifetime; removing it mid-run
re-breaks the backdrop. The `shadow` and `clip` that precede `backdropGlass` do not block the
blur (ratios 0.0028 and 0.0008). Popup text uses `textSecondaryOnGlass`; the panel is the
liquid glass with radius 18 dp and a soft neutral shadow, with no neon border. A selected row
is a `selectionRow` capsule inset 6/2 dp with the accent2 line on its left edge; hover is a
`hoverFill` step. The Find in Path toggles and the Search Everywhere tab chips follow the same
capsule-and-line language, and no popup draws an outer glow.

The find bar uses `glassPanel()` (cached field slice) with a 1 dp `neonUnderline`. Its labels
and placeholders are `textSecondaryOnGlass`, the match counter turns `neonAmber` on a pattern
error and the query field gains a `neonPink` border instead of pink text, and the row buttons
use a `glassFill` fill.

## Offscreen snapshots

`stride.app.ui.UiSnapshotTest` renders the real `App` through `ImageComposeScene` at
2560×1640 px and density 2 (the 1280×820 dp default window) and writes PNGs to
`app/build/ui-snapshots/`
(`01-empty.png`, `02-editor-kotlin.png`, `03-editor-json.png`, `04-search.png`,
`05-search-empty-results.png`, `06-selection-editor.png`, `07-selection-popup.png`,
`08-find-bar.png`, `09-recent-files.png`, `10-terminal.png`, `11-run-window.png`,
`12-editor-scrolled.png`).
`tasks.test` sets `java.awt.headless=true`, so the renderer never opens a window or touches
the display.

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
| `KeymapTest` | every macOS binding in its context, context-over-global resolution, modal popup contexts never falling back to GLOBAL, non-mac meta↔control swap |
| `KeyStrokeTextTest` | macOS glyph formatting and order, platform modifier labels (`Ctrl+S`, `Shift+Ctrl+O`) for non-macOS keymaps, first bound stroke per action |
| `WelcomeHintsTest` | hints read from the keymap, Double Shift as the literal gesture, and platform modifier labels on a non-macOS keymap |
| `KeyRouterTest` | double Shift, cancellation, repeated modifier downs, platform shortcuts |
| `ActionDispatcherTest` | undo/redo consumption, next/previous tab wrap, run tool window toggle, hide resets focus, the source-set refusal message, direct runs starting in the file's directory |
| `ToolWindowManagerTest` | the three toggle rules, hide, activate, same-id replacement, height clamp |
| `KeyFlowUiTest` | double Shift search, ⌘4 then ⇧Esc on the run panel, ⌥F12 terminal, ⌘⇧] next tab, ⌘F find bar stepping, ⌘E recent files, ⌘⇧F find in path, ⌃⇧R run at caret, ⌘F2 stop, the no-test status message, ⌃R and Escape while the context actions popup is open, an Actions-tab entry returning global shortcuts, direct `EDITOR_ESCAPE` dispatch closing the goto-line popup and refocusing the editor, completion filtering/insertion with ⌃R blocked and Escape closing, rename through its popup and Escape cancelling |
| `FindModelTest` | case folding, regular expressions, invalid pattern errors, indexing wrap, replacement order |
| `FindBarStateTest` | span painting and current match, next cycling, replace all, close clearing spans |
| `FindInPathScannerTest` | line/column/body output, size and NUL exclusions, limit and stop, whole-word filtering |
| `NavigationHistoryTest` | consecutive duplicate suppression, forward truncation on push, back/forward ends, the 100-entry cap, caret update and recording suspension |
| `DeclarationNavigatorTest` | same-file nearest preceding target, package/module ranking, package and module derivation |
| `TestNavigationTest` | source-to-test names in both directions, extension and directory ordering, no counterpart |
| `BookmarksTest` | toggle add/remove, per-file lines, remove, line index and text |
| `FileStructureTest` | nesting depth and line order, filtered and unfiltered rows |
| `NeonModifiersTest` | underline glow direction and `rememberNeonAlpha` with animations on and off |
| `UiFontTest` | the `uiFont` preference round-trip (stored, missing and unknown values) and the toggle action switching the family and status message |
| `DesignTokensContrastTest` | the WCAG helpers against the WCAG examples and the 0.04045 luminance threshold, every body text token clearing 4.5 on the worst strong glass, `neonPink` clearing only large-text contrast, focus and selection markers clearing 3.0, `textSecondaryOnGlass` as the first muted→text step that clears 4.5, and the rendered field staying neutral inside its luminance band |
| `GlassModifiersTest` | the neutral glass fill over the field, the brighter top border, the backdrop blur of the sibling behind, deterministic rendering, one blur per panel size and position, the opaque content tint, the opaque cached slice, the backdrop blur staying aligned past the bezel, the SkSL compiling, the top specular rim, the lens bending the bezel backdrop, the rounded clip staying inside the glass, the 2 dp selection line and its cyan halo at density 2, and the blur-only fallback when the shader is unavailable |
| `ToolWindowGlassTest` | the tab strip is the neutral glass fill over the field, the terminal and run console paint exactly one opaque tint, status glyphs draw no halo, run status colors clear 4.5 on the worst tint |
| `PopupGlassTest` | the popup fade keeps a real backdrop at the mid and final frames, the shadow/clip chain still blurs, the Search Everywhere panel is translucent, the selected row carries the neon line on its capsule edge, the find bar is the neutral glass fill, and no fade runs with animations disabled |
| `UiSnapshotTest` | headless PNGs of the empty state, Kotlin and JSON editors, Search Everywhere, the find bar, recent files, the terminal, the run window and an editor scrolled to a far column |
| `SnapshotFrameTimingTest` | the twelve snapshot scenes' frame times under `baseline × factor + 1.0 ms` (1.5 for content, 2.0 for popups) |
| `EditorScrollUiTest` | caret moved past the right and bottom edges scrolls each axis and returning to `1:1` scrolls back, and the gutter keeps its position while the text scrolls horizontally |
| `GradleCommandTest` | command order and `$0`, spaced test name as one argument, wrapper/PATH fallback, test args, shell fallback |
| `GradleInitScriptTest` | every event line uses the parser prefix and the five kinds, synthesized lines parse as events, the test task cannot be skipped as up-to-date or cached, `strideRunMain` registered for every project and its message without the java plugin |
| `TestEventParserTest` | the five event kinds, TeamCity escape reversal, non-event and malformed lines as output, order preservation |
| `TestLocatorTest` | caret-to-method/class resolution on real repository files, marker counts, Gradle project paths and directories |
| `RunSessionTest` | console versus event lines, tree statuses and failure details, exit code, stop on a fake process, queued stdin, an undelivered line closing stdin and the console line cap |
| `RunManagerTest` | last run and starter, one session per configuration, replacing a tab on rerun, stop/rerun on the selected tab and its label, pruning finished sessions while keeping running ones, closing a tab, recent identity, dispose destroying every child |
| `RunConfigurationPopupTest` | the kind secondary text, and the working directory added when two rows share a name |
| `GradleRunIntegrationTest` | a real `:core:test` child Gradle through the init script (tagged `slow`, opt-in with `-Dstride.runIntegration=true`) |
| `EditActionsTest` | duplicate, delete, move, line/block comment, indent and unindent with no selection, one line, many lines, the first line and a last line without a newline; `gotoLine` clamping; the word/selection ladder through brackets, strings and lines; toggle case, join lines, start new line, brace matching outside strings and comments, code block start/end, declaration targets, whole-word ranges, hippie candidate order and whole-line ranges |
| `CodeCompletionTest` | identifier order, prefix and camel-hump ranking with duplicates, blank-prefix ordering, the result limit and language keywords |
| `UndoManagerTest` | typing merge within 500 ms, whitespace and deletion separation, redo clearing, `breakMerge`, the entry limit, stored selections |
| `TextFileEditorUndoTest` | undo restores text, selection and a freshly highlighted editor; duplicate undo; read-only actions; commenter resolution; goto line parsing and the popup flag |
| `TextFileEditorActionsTest` | extend/shrink selection and its reset, join and start-new-line undo steps, toggle case, brace and code-block caret moves, method up/down, whole-line copy and cut through a fake clipboard, hippie cycling and restart, one-step rename and replace range |
| `CommenterEpTest` | `lang.commenter` resolves kotlin and json to `//` and `/* */`, plain text to `null`, one registration per language plugin |
| `TerminalSessionTest` | fake-connector output, ANSI styles, carriage return and erase, resize, write, key encoding, dispose |
| `PtyShellIntegrationTest` | a real `/bin/zsh -lc` PTY writing into the terminal model (skipped when no PTY is available) |
| `BuiltInPluginLoadingTest` | the three bundled descriptors enabled, kt/json file types and highlighter lexers through the extension points, one core extension per interface EP |
| `TextEditorProviderTest` | a file at the highlighted-size limit stays editable and one byte above the limit is read-only |
| `RepositoryAcceptanceTest` | repository tree top level with generated directories hidden, `Main.kt` as a selected highlighted tab, double-Shift search opening `docs/api-search.md`, and the repository Kotlin and search snapshots |
| `TreeEditorGlassTest` | tree glass over the field, the editor tint over the same field pixel, the tree focus halo on focus, the go-to-line backdrop blur, the gutter line-number contrast on the worst content tint and the search pill shortcut hint clearing 4.5:1 on its fill |
