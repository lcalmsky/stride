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
