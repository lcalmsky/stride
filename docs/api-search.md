# stride search public API

This document is the contract for `:search`. Everything here lives in package
`stride.search` and depends only on `:core` (`docs/api-core.md`). The `:lang` dependency in
`search/build.gradle.kts` is present for the module graph but no `stride.lang` type is used.

## `Matcher` and `NameMatcher`

```kotlin
interface Matcher {
    fun match(query: String, name: String, relativePath: String = name): Int?
}

object NameMatcher : Matcher
```

- Returns a score where a larger value is a better match, or `null` for no match.
- The query is trimmed. A blank query matches every candidate.
- `name` includes the extension; `relativePath` is relative to the project root and uses
  `/` separators. When the query contains `/`, only the path is matched; otherwise only
  the name is matched.

Score tiers, highest first:

| Tier | Base | Example |
|---|---|---|
| exact name or stem (name without extension) | 10 000 | `App` → `App.kt` |
| name or stem prefix | 8 000 | `App` → `Application.kt` |
| camel humps and word starts | 6 000 | `FEM` / `fiEdMa` → `FileEditorManager.kt`; `fbn` → `foo-bar-name.kt` |
| case-insensitive substring | 4 000 | `itor` → `FileEditorManager.kt` |
| path segments in order | 2 000 | `core/ext` → `core/src/main/kotlin/stride/core/extensions/Extensions.kt` |

Word starts are the first character, characters following `_`, `-`, `.`, `/`, a space or
`\`, the lower-case→upper-case transition, the upper-case run→upper-case+lower-case
transition (`XMLParser` → `P`), and a letter/digit change. Every matched run must begin at
a word start.

Within a tier a shorter file name scores higher (up to 999 points); the caller breaks a
remaining tie alphabetically. Path segments each match an exact segment first and a
prefix otherwise, in order.

## `SearchEverywhereContributor`

```kotlin
const val SEARCH_EVERYWHERE_CONTRIBUTOR_EP: String = "stride.searchEverywhereContributor"

data class FoundItem<T : Any>(
    val item: T,
    val score: Int,
    val presentationText: String,
    val secondaryText: String = ""
)

interface SearchEverywhereContributor<T : Any> {
    val id: String
    val displayName: String
    val sortWeight: Int
    fun fetch(query: String, limit: Int): List<FoundItem<T>>
    fun process(item: T): Boolean
}

interface SearchEverywhereContributorFactory<T : Any> {
    fun createContributor(project: Project): SearchEverywhereContributor<T>
}
```

- `fetch` returns at most `limit` results, best match first. A zero or negative limit yields
  an empty list.
- `sortWeight` orders contributors against each other; a higher weight is shown first.
- `process` handles the selected item and returns `true` when the popup should close.

This is an interface extension point holding [SearchEverywhereContributorFactory]
instances. The plugin descriptor declares it exactly as:

```xml
<extensionPoints>
  <extensionPoint qualifiedName="stride.searchEverywhereContributor"
                  interface="stride.search.SearchEverywhereContributorFactory"/>
</extensionPoints>

<extensions defaultExtensionNs="stride">
  <searchEverywhereContributor implementation="stride.search.FileSearchContributorFactory"/>
</extensions>
```

The extension's `implementation` attribute names the factory class, which needs a no-arg
constructor (or Kotlin `object`), because extension points are instantiated without a
project. The popup calls `createContributor(project)` for each project it searches in.

## `FileNameIndex`

```kotlin
data class IndexedFile(val file: VFile, val relativePath: String) {
    val name: String
}

@Service(Service.Level.PROJECT)
class FileNameIndex(project: Project) : Disposable {
    val isReady: Boolean
    fun awaitReady(timeoutMillis: Long = 5_000L): Boolean
    fun snapshot(): List<IndexedFile>
    override fun dispose()
}
```

- Project light service: `project.service<FileNameIndex>()`.
- Construction starts a background daemon thread that walks `project.root` and indexes
  every file (directories are skipped). Directories named `.git`, `build`, `.gradle`,
  `node_modules`, `.idea` and `out` are never descended into, and a file named `.git`
  (a gitdir pointer in worktrees and submodules) is not indexed.
- After the scan, `VFS_CHANGES` on `project.messageBus` keeps the list current: created
  files are added (a created directory is walked), deleted files are removed, and deleting
  a directory removes every file below it. `Changed` events are ignored.
- `isReady` becomes `true` when the initial scan finishes; `awaitReady` waits for it and
  returns `false` on timeout. Tests use this instead of sleeping.
- `snapshot()` returns an immutable copy sorted by relative path and is safe to call from
  any thread. `relativePath` always uses `/` separators.
- Disposing the service (directly or through `Project.dispose()`) interrupts the scan and
  unsubscribes from the message bus.

The index does not start `VirtualFileManager`; the application starts it, and the index
follows the events it publishes.

## Declarations and the type graph

`DeclarationParser` scans Kotlin and Java text line by line (comments and strings blanked, brace
depth tracked); `DeclarationIndex` keeps its result for every project source file. The hierarchy
actions (`docs/api-app.md` 「Hierarchy navigation」) read three more things from the same scan and
three pure helpers on top of it. Nothing here resolves types the way a compiler does: names
resolve through imports and packages only, and library, JDK and standard library types are not
indexed.

```kotlin
enum class DeclarationKind { CLASS, SYMBOL }

data class ParsedDeclaration(
    val name: String, val kind: DeclarationKind, val line: Int,
    val container: String? = null, val isPrivate: Boolean = false,
    val column: Int = 0,
    val supertypes: List<String> = emptyList(),
    val isInterface: Boolean = false,
    val isOverride: Boolean = false,
)

data class Declaration(
    val name: String, val kind: DeclarationKind, val file: VFile, val relativePath: String,
    val line: Int, val container: String? = null, val packageName: String? = null,
    val isPrivate: Boolean = false,
    val column: Int = 0,
    val supertypes: List<String> = emptyList(),
    val isInterface: Boolean = false,
    val isOverride: Boolean = false,
)

data class SourceImport(val qualifiedName: String, val alias: String? = null, val isStar: Boolean = false) {
    val visibleName: String?
}

enum class OutlineKind { CLASS, FUNCTION, PROPERTY, INITIALIZER, CONSTRUCTOR }
data class OutlineMember(
    val name: String, val kind: OutlineKind, val container: String?, val line: Int,
    val start: Int, val nameStart: Int, val nameEnd: Int, val end: Int, val isOverride: Boolean = false,
    val receiverType: String? = null,
)
fun List<OutlineMember>.memberAt(offset: Int): OutlineMember?
fun List<OutlineMember>.classesAt(offset: Int): List<OutlineMember>
fun List<OutlineMember>.extensionReceiverAt(offset: Int): String?

object DeclarationParser {
    fun parse(fileName: String, text: String): List<ParsedDeclaration>
    fun imports(fileName: String, text: String): List<SourceImport>
    fun outline(fileName: String, text: String): List<OutlineMember>
    fun packageOf(text: String): String?
}

class DeclarationIndex {
    fun snapshot(): List<Declaration>
    fun imports(file: VFile): List<SourceImport>
}

data class NameContext(val file: VFile?, val packageName: String?, val imports: List<SourceImport>,
                       val enclosingClasses: List<String> = emptyList(), val line: Int? = null,
                       val extensionReceiver: String? = null)
data class SupertypeRef(val written: String, val resolved: List<Declaration>)

class TypeGraph(declarations: List<Declaration>, importsOf: (VFile) -> List<SourceImport>) {
    fun resolveType(written: String, context: NameContext): List<Declaration>
    fun contextOf(declaration: Declaration): NameContext
    fun classOf(member: Declaration): Declaration?
    fun supertypes(type: Declaration): List<SupertypeRef>
    fun directSubtypes(type: Declaration): List<Declaration>
    fun allSubtypes(type: Declaration): List<Declaration>
    fun implementations(target: Declaration): List<Declaration>
    fun superMembers(member: Declaration): List<Declaration>
    fun membersNamed(name: String, type: Declaration, includeSupertypes: Boolean): List<Declaration>
    fun resolveCallee(site: CallSite, context: NameContext): List<Declaration>
    fun mayCall(target: Declaration, site: CallSite, context: NameContext): Boolean
}

data class CallSite(val name: String, val start: Int, val end: Int,
                    val receiver: String? = null, val isReference: Boolean = false,
                    val inLambda: Boolean = false)
object CallSites {
    fun find(fileName: String, text: String, name: String): List<CallSite>
    fun inRange(fileName: String, text: String, start: Int, end: Int): List<CallSite>
}

sealed interface DeclaredType {
    data class Explicit(val written: String) : DeclaredType
    data class ConstructorCall(val written: String) : DeclaredType
    data class Unknown(val reason: String) : DeclaredType
}
object TypeReferences {
    fun localDeclaration(fileName: String, text: String, offset: Int, name: String): Int?
    fun declaredType(fileName: String, text: String, nameStart: Int): DeclaredType
}
```

- The fields that existed before (`name`, `kind`, `line`, `container`, `isPrivate`) are the same
  for every input as before the hierarchy fields were added; Search Everywhere, `⌘B`, the ⌥↩
  import candidates and the Structure window read them. The new fields are trailing and defaulted.
- `supertypes` are written names without type arguments, `?`, annotations, constructor arguments
  or a `by` delegate, read across header lines (`class A(\n x: X,\n) : B(x),\n C {`). Kotlin's
  list ends at the body `{` or the `where` clause, whichever comes first, so a type parameter bound
  is never a supertype; an item that does not clean to a single dotted name (two names separated by
  whitespace, a bound) is dropped. Kotlin type parameter bounds (`<T : Comparable<T>>`) and primary
  constructor parameters are not supertypes. Java reads `extends` and `implements` (an interface's
  `extends` list too) up to `permits` or `{`.
- `imports` stops at the first declaration; Java `import static` is skipped.
- `outline` lists members at every class level but no local declaration; `memberAt` picks the
  innermost member holding an offset, `classesAt` the classes, innermost first.
- `OutlineMember.receiverType` is the receiver written on a Kotlin extension function or property,
  dots kept and type arguments and a trailing `?` dropped (`fun <T> Box<T>.open()` gives `Box`);
  `null` for a plain member, a Java member and a function type receiver (`(Int) -> Unit.f()`).
  `extensionReceiverAt` gives the receiver of the innermost extension member holding an offset, so
  a call inside a local function of an extension function takes the outer receiver.
- `NameContext.extensionReceiver` is that receiver type at the query position: its members are
  implicit receivers the call does not name.
- `TypeGraph.resolveType` order: dotted name, nested or sibling class in the file, explicit import
  (an import that names no project class ends the search: the name is outside the project), same
  package, star import. Empty means outside project sources. KDoc in `TypeGraph.kt` is the full
  contract of every query.
- `TypeGraph.resolveCallee` receiver rules: no receiver and `this` share one path (members of the
  class `NameContext.extensionReceiver` resolves to and its supertypes, then members of the
  enclosing classes and their supertypes, then visible top-level functions, then a visible class,
  then, for a call inside a lambda, every member and visible top-level function of that name since
  the lambda's subject may provide it); `super` walks the supertypes of the innermost enclosing
  class breadth first and takes the members of the first class on each path that has one; any other receiver limits the result to the class
  it resolves to, and an unresolved receiver gives every visible member or top-level function of
  that name. A private member of a supertype is never inherited, so the supertype part of the
  unqualified/`this` path, the `super` path and `superMembers` leave it out; `implementations`
  returns nothing for a private target.
- `TypeGraph.mayCall` accepts a member an unqualified or `this` call reaches when the extension
  receiver resolves to the member's class or a subtype, and, inside a lambda, when `resolveCallee`
  lists the member; a call outside both keeps the enclosing-class rule.
- `NameContext.line` is the 0-based line the query is written on: a simple class name that two
  classes in the file share resolves to the one declared nearest at or before that line
  (`classInContext`, and the innermost enclosing class in `resolveType`), and a null line to the
  first one in the file.
- `TypeGraph.classOf` picks, among the classes of the member's file with its simple container name,
  the one declared nearest before the member, so two nested classes with the same simple name in
  one file stay apart (`membersByClass` uses the same result).
- `CallSites` finds calls by text only: an identifier followed by `(`, by type arguments and `(`,
  or in Kotlin by a trailing lambda `{`, or a `::name` reference, outside comments and strings;
  keywords, annotations and declaration names are not calls. The `{` of a `by <expression>` in a
  class header opens the class body, a Kotlin property accessor `get()`/`set()` after modifiers and
  annotations only is a declaration, and a Java method or constructor head is a declaration even
  when `outline` missed it. A name is in a type position only when the scan back to its `:` passes
  no word that cannot stand in a type (`by`, `val`, `return`, ...) and no lambda arrow `->` whose
  left side is not a parameter list `)`, and a `fun interface` head is a class header, not a call.
- `CallSite.inLambda` marks a Kotlin call inside a lambda literal, counted from the file start: the
  `{` of a call's trailing lambda, the `{` after a call's `)`, and a `{` after `(`, `,` or `=`. A
  declared body, a control block, a `when` branch, an accessor and a class header are not lambdas;
  Java calls are never inside one.
- `TypeReferences.declaredType` returns `Explicit` only for a written type and `ConstructorCall`
  only for an initializer that is a single upper-case call; everything else is `Unknown` with the
  reason the status bar shows.

## `FileSearchContributor`

```kotlin
class FileSearchContributor(index: FileNameIndex) : SearchEverywhereContributor<VFile> {
    override val id: String            // "stride.files"
    override val displayName: String   // "Files"
    override val sortWeight: Int       // 100
    override fun fetch(query: String, limit: Int): List<FoundItem<VFile>>
    override fun process(item: VFile): Boolean  // false
    companion object { const val MAX_RESULTS: Int = 50 }
}

class FileSearchContributorFactory : SearchEverywhereContributorFactory<VFile> {
    override fun createContributor(project: Project): SearchEverywhereContributor<VFile>
}
```

- Registered through the extension point as `FileSearchContributorFactory`; the root
  plugin descriptor in `app` adds the extension shown above. `createContributor` builds
  `FileSearchContributor(project.service<FileNameIndex>())`.
- Scores every indexed file with `NameMatcher` using its name and relative path, sorts by
  score, then case-insensitive name, then exact name, and returns the best 50. The
  `fetch` limit is honored on top of that cap.
- `FoundItem.presentationText` is the file name and `secondaryText` is the relative path.
- UI-free: `process` performs no action and returns `false`, so the caller opens the
  selected `VFile` and decides whether to close the popup.

## `DoubleModifierDetector`

```kotlin
enum class ModifierKey { SHIFT, CONTROL, ALT, META }

class DoubleModifierDetector(
    clock: () -> Long,
    onDoubleModifier: (ModifierKey) -> Unit = {}
) {
    fun modifierPressed(key: ModifierKey)
    fun modifierReleased(key: ModifierKey)
    fun otherKeyPressed()

    companion object {
        const val PRESS_WINDOW_MILLIS: Long = 500L
        const val HOLD_TIMEOUT_MILLIS: Long = 300L
    }
}
```

Pure state machine; no AWT or Compose types. All times are milliseconds from `clock`.

- The callback fires on the **release of the second press** of the same modifier.
- The second press must start within 500 ms of the first press (exactly 500 ms is
  allowed).
- A press held longer than 300 ms (exactly 300 ms is allowed) before its release resets
  the sequence.
- Any other key (`otherKeyPressed`) or a different modifier cancels the sequence.
- After firing, the state resets, so a third tap starts a new sequence and does not fire
  again on its own.
