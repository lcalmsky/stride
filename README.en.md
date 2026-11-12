<h1 align="center">strIDE</h1>

<p align="center">A keyboard-driven code editor for macOS.</p>

<p align="center"><a href="README.md">한국어</a> · <b>English</b></p>

<p align="center">
  <a href="https://github.com/lcalmsky/stride/releases/latest"><img src="https://img.shields.io/github/v/release/lcalmsky/stride?label=release&color=00d1ff" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/macOS-Apple%20Silicon-111111" alt="macOS Apple Silicon">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ea44f" alt="MIT license"></a>
</p>

<p align="center"><img src="docs/images/editor.png" alt="The strIDE window with a project tree and a Kotlin file open" width="720"></p>

<p align="center">
  Project tree &nbsp;·&nbsp; tabbed editor &nbsp;·&nbsp; Kotlin and JSON syntax highlighting &nbsp;·&nbsp; find and replace &nbsp;·&nbsp; liquid glass UI<br>
  Built with Kotlin and Compose Desktop for Apple Silicon.
</p>

<br>

## Quick start

```sh
brew tap lcalmsky/stride https://github.com/lcalmsky/stride
brew trust lcalmsky/stride
brew install --cask lcalmsky/stride/stride
```

The app installs to `/Applications/strIDE.app`. It is Apple Silicon (arm64) only, and the bundled runtime means you do not need to install a JDK.

<br>

## Features

### Editor

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/editor.png" alt="Project tree and Kotlin editor" width="480"></td>
<td valign="top">

- **Project tree** a directory expands on a single click and a file opens on a single click. `.git`, `build`, `.gradle`, `node_modules`, `.kotlin`, and `.idea` are hidden. Drag the splitter to set the width between 200 and 480 dp, `⌘1` toggles the tree, and `⌥F1` reveals the current file in it
- **Tabbed editor** open files become tabs, and opening the same file again selects its tab. Close with `⌘W` and switch with `⌘⇧]`·`⌃→` and `⌘⇧[`·`⌃←`. `Reopen Closed Tab` and `Close Other Tabs` in the Actions tab bring back a closed tab or close the rest
- **Syntax highlighting** the Kotlin and JSON plugins each provide a lexer and a highlighter. Files larger than 2 MB open read-only without highlighting
- **Editing** duplicate, delete, and move lines, toggle line and block comments, indent, and undo or redo from the keyboard. `⌘L` jumps to a `line` or `line:column`. With no selection, `⌘C`·`⌘X` copy or cut the whole line
- **Editing helpers** `⌥↑`·`⌥↓` grow and shrink the selection from the caret through word, string contents, brackets, and lines. `⌘⇧U` changes case, `⌃⇧J` joins lines, `⇧↩`·`⌘⌥↩` start a new line, `⌃M` jumps to the matching brace, `⌘⌥[`·`⌘⌥]` move to the code block edges, and `⌃⇧↑`·`⌃⇧↓` move between methods. `⌥/` completes from words in open editors, `⌃Space`·`⌃⌥Space` complete from the declaration index and keywords, and `⇧F6` renames within the file
- **Navigation** `⌘B` or `⌘`-click goes to a declaration, and `⌘[`·`⌘]` or `⌘⌥←`·`⌘⌥→` move back and forward. `⌘F12` shows the file structure, `⌘⇧T` goes to the test, `⌥F7` finds usages, and `F3`·`⌘F3` handle bookmarks
- **Status capsules** the path capsule at the bottom shows the relative path, and the status capsule group shows `Ln`·`Col`, the file type, `UTF-8`, and a read-only badge

</td>
</tr>
</table>

### Search

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/search.png" alt="Search Everywhere results" width="480"></td>
<td valign="top">

- **Search Everywhere** press `Shift` twice to open the All tab. It groups file, class, symbol, and action results (`Files`, `Classes`, and so on, up to 4 per contributor and 50 overall); `Tab`·`⇧Tab` cycle through the All·Classes·Files·Symbols·Actions tabs. Move with `↑`·`↓` and open with `Enter`
- **Classes and symbols** `⌘O` opens class search and `⌘⌥O` opens symbol search. A background declaration index scans Kotlin and Java classes, functions, and properties, shows `path:line` for classes and `container — path:line` for symbols, and `Enter` opens that line
- **Go to file** `⌘⇧O` matches file names and relative paths fuzzily and lists them by score
- **Actions** `⌘⇧A` lists every action with its name and shortcut and runs the chosen one
- **Find in path** `⌘⇧F` searches the text of every project file. It skips files over 2 MB and binary files and shows up to 200 results. `W` turns on whole-word matching. Each row reads `path:line` with the line body, and the matched characters are highlighted. `Enter` opens the file and selects the match
- **Recent files** `⌘E` lists the most recently opened files, newest first, up to 50. Closed files stay in the list

</td>
</tr>
</table>

### Find

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/find.png" alt="The find bar open below the top capsules" width="480"></td>
<td valign="top">

- **Find bar** `⌘F` opens it below the top capsule row. `Enter`·`⌘G` moves to the next match and `⇧Enter`·`⌘⇧G` to the previous one, wrapping around at either end
- **Replace** `⌘R` expands the replace row. Replace the current match or all matches
- **Options** `Aa` matches case and `.*` uses a regular expression. In regular-expression replacements, `$1` refers to a group. An invalid expression shows an error
- **Display** the current match and the other matches are painted in different neon colors, with an `n / total` counter. `Esc` closes the bar and returns focus to the editor

</td>
</tr>
</table>

### Run

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/run.png" alt="The Run window with a test tree and console" width="480"></td>
<td valign="top">

- **Running tests** every class line and `@Test` function in a `.kt` file under `src/test` gets a `▶` in the gutter; click it to run that test. `⌃⇧R` runs the test at the caret, or the file's main or script when there is none
- **Running main** the `main` function in Kotlin and Java also gets a `▶` in the gutter. `⌃R` reruns the last run, otherwise it runs the same target as `⌃⇧R`. `.py`·`.sh`·`.js`·`.rb`·`.kts` scripts and a single-file Java source outside Gradle run directly; Kotlin and Java in a Gradle source set run through the `strideRunMain` task
- **Context actions and run configurations** `⌥↩` opens a popup with the main and test runs available at the caret plus rerun and stop. `⌃⌥R` lists the current file's run entries and the 10 most recent runs, and `Enter` runs the selection. `⌘F9` builds `classes` and `testClasses`
- **Run window** when more than one session exists, or a session has finished, the window shows a tab per session with `×`. Test runs split into the test tree and console, count passed, failed, and skipped tests, and selecting a method shows its message, stack, and captured output. Application runs show the console only, with an input line at the bottom for standard input. `⌘F2` stops, `⌘R` reruns from the window, and `⌘4` opens it
- **Always runs** the Gradle init script disables up-to-date checks and the build cache for every `Test` task, so running the same test again still executes it and reports its events

</td>
</tr>
</table>

### Terminal

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/terminal.png" alt="The built-in terminal showing ANSI colors" width="480"></td>
<td valign="top">

- **Built-in terminal** `⌥F12` opens the Terminal window. It runs the login shell in the project directory over a real PTY (pty4j), and jediterm draws the screen
- **Neon ANSI palette** the 16 ANSI colors belong to the same neon family as the rest of the window. The cursor is `accent2`
- **Hide** `⇧Esc` hides the window and returns focus to where it was

</td>
</tr>
</table>

### Liquid glass UI

<table width="100%">
<tr>
<td width="44%" valign="top"><img src="docs/images/editor.png" alt="Cyan selection on the project tree and the active tab" width="480"></td>
<td valign="top">

- **System glass** on macOS 26 or later the chrome is drawn by the system's own glass views behind the window content, so the desktop and other windows show through the sidebar, capsules, find bar, and popups. The editor and console are opaque rounded surfaces floating over it
- **Title bar** the title bar is hidden and the traffic lights sit in the top capsule row. Drag the empty part of that row to move the window, and double-clicking it follows the system title-bar setting
- **Light and dark** the window follows the system appearance live. The palette, syntax colours, and terminal colours each have a light and a dark set, so a change needs no restart
- **Older macOS** on macOS versions before 26, or when the native library is missing, the previous in-window glass and the standard title bar are used
- **Background** on the path without native glass, a neutral graphite base with a soft top-to-bottom luminance falloff sits behind the window content. Color is reserved for emphasis
- **Glass only on the control layer** glass is used only for the sidebar, the top and bottom capsules, the find bar, popups, and the tool window switcher; the code fills the window edge to edge. No bar or slab crosses the window
- **Floating glass sidebar** a rounded glass panel inset 10 dp from the top, left, and bottom edges. On the fallback path the real code right next to it is mirrored and blurred into a soft colour field under the panel, so the sidebar carries the tone of the code beside it, and outside the panel the pixels are the editor field. On the native path the panel itself shows the desktop through
- **Capsules instead of bars** the top row is split into a project name capsule, a tab capsule group, and a Search capsule; the bottom row into a file path capsule and a status capsule group. The selected tab is a glass pill that slides across the group, and a 2 dp cyan underline marks the selection instantly. The status group carries `Ln`·`Col`, the file type, `UTF-8`, and the read-only badge. Each capsule frosts what is behind it so labels stay legible, and the gaps between capsules stay visible and clickable
- **Scroll edge effect** scrolled code softens under the top and bottom capsule rows and fades out toward the window edge. Instead of a bar covering and cutting it, the blur eases out gradually
- **Thin popups** on the fallback path, Search Everywhere, recent files, and the context actions popup float as thin glass over the code, showing it behind and bending it at the rim. A strong text shadow keeps labels legible, and they fall back to blur plus hairlines when the shader is unavailable. On the native path popups use the system glass too
- **Welcome screen** without a project, the wordmark is the content, with an `Open Folder…` glass capsule and keycap hints below it
- **Selection signal** a selected tree row floats as a capsule with a small lens and shows a 2 dp cyan bar, a bleeding glow, and white text. A focused tree panel draws a thin line around its border
- **Large files** documents of 1,000 lines or more scroll through a constant-cost layer, and the capsules use a cached frost fill; the layer is released once the document shrinks below 800 lines
- **Motion and cost** the selected tab's pill slides for 200 ms and nothing animates continuously. Capsule frost and the popup lens are computed on lower-resolution copies, and the background extension and scroll edge blur are cached so static frames do not compute them again
- **UI font** the bundled UI font can be swapped for the system font with `Switch UI Font` in the Actions tab. The choice is saved for the next launch

</td>
</tr>
</table>

<p align="center"><img src="docs/images/glass.png" alt="The Search Everywhere glass popup over code" width="720"></p>

<p align="center"><img src="docs/images/editor-light.png" alt="The project tree and Kotlin editor in the light appearance" width="720"></p>

<br>

## Usage

### Keyboard

These are the default keys. Shortcuts are compatible with the IntelliJ IDEA macOS default keymap; on non-macOS platforms Command and Control are swapped (`⌘S` becomes `Ctrl+S`, `⌃R` becomes `Meta+R`) and keys are shown with `Ctrl`, `Alt`, `Shift` and `Meta` labels. The full list and the context precedence are defined by the `bind(` calls in `app/src/main/kotlin/stride/app/keys/Keymap.kt`.

| Key | Action |
|---|---|
| **File and tabs** | |
| `⌘S` | Save |
| `⌘W` | Close tab |
| `⌘⇧]` `⌃→` / `⌘⇧[` `⌃←` | Next tab / previous tab |
| `⌘1` / `⌘⇧F12` | Toggle the project tree / hide and restore all tool windows |
| Actions tab | Reopen Closed Tab · Close Other Tabs |
| **Search and navigation** | |
| `⇧⇧` | Search Everywhere (All tab) |
| `⌘O` | Go to class. Opens the folder chooser when there is no project |
| `⌘⇧O` / `⌘⌥O` | Go to file / symbol |
| `⌘⇧A` | Go to action |
| `⌘⇧F` | Find in path |
| `⌘E` / `⌃Tab` `⌃⇧Tab` | Recent files / recent-file switcher |
| `⌥F1` / `⌘⇧C` / `⌘⌥⇧C` | Reveal the current file in the tree / copy path / copy reference |
| **Editor** | |
| `⌘D` | Duplicate the line or selection |
| `⌘⌫` | Delete the line |
| `⌥⇧↑` / `⌥⇧↓` | Move the line up / down |
| `⌘/` · `⌘⌥/` | Toggle line comment · toggle block comment |
| `Tab` / `⇧Tab` | Indent / unindent |
| `⌘L` | Go to line |
| `Esc` | Close Go to Line, collapse the selection to the caret |
| `⌘Z` / `⌘⇧Z` | Undo / redo |
| `⌥↑` / `⌥↓` | Grow / shrink the selection |
| `⌘⇧U` | Toggle case |
| `⌃⇧J` | Join lines |
| `⇧↩` / `⌘⌥↩` | Start a new line below / above |
| `⌃M` | Go to the matching brace |
| `⌘⌥[` / `⌘⌥]` | Go to the code block start / end |
| `⌃⇧↑` / `⌃⇧↓` | Move to the previous / next method |
| `⌥/` | Word completion |
| `⌃Space` · `⌃⌥Space` | Completion popup |
| `⇧F6` | Rename in the file |
| `⌘C` / `⌘X` | Copy / cut the whole line when there is no selection |
| **Find** | |
| `⌘F` | Open the find bar |
| `⌘R` | Expand the replace row |
| `⌘G` / `⌘⇧G` | Next match / previous match |
| **Navigation** | |
| `⌘B` · `⌘`-click | Go to declaration |
| `⌘[` `⌘⌥←` / `⌘]` `⌘⌥→` | Back / forward |
| `⌘F12` | File structure |
| `⌘⇧T` | Go to test |
| `⌥F7` | Find usages |
| `F3` / `⌘F3` | Toggle bookmark / show bookmarks |
| **Run** | |
| `⌥↩` | Context actions |
| `⌃⇧R` | Run the test at the caret, otherwise the file's main |
| `⌃R` | Rerun the last run, otherwise the test at the caret or the file's main |
| `⌃⌥R` | Choose a run configuration |
| `⌘F9` | Build |
| `⌘F2` | Stop the run |
| `⌘4` · `⌘R` (Run window) | Open the Run window · rerun from the Run window |
| **Terminal** | |
| `⌥F12` | Open the Terminal window |
| `⇧Esc` | Hide the tool window (Terminal and Run windows) |
| **Other** | |
| Actions tab | Switch UI Font |

<br>

## Installation

### Homebrew

```sh
brew tap lcalmsky/stride https://github.com/lcalmsky/stride
brew trust lcalmsky/stride
brew install --cask lcalmsky/stride/stride
```

The repository is its own tap and the cask lives in `Casks/stride.rb`. The short name `stride` can collide with another tap, so install with the full `lcalmsky/stride/stride` name. `brew trust` marks a non-official tap as trusted so Homebrew may load its casks.

### Build from source

```sh
git clone https://github.com/lcalmsky/stride.git
cd stride
./gradlew :app:run                       # development run
./gradlew :app:createDistributable       # app/build/compose/binaries/main/app/strIDE.app
```

JDK 21 is required; the Gradle toolchain asks for 21.

### Requirements

| Requirement | Notes |
|---|---|
| macOS Apple Silicon (arm64) | The release app is arm64 only. The bundled runtime means no JDK installation is needed |
| macOS 26 or later (optional) | Needed for the system glass; below that the app draws its own glass in the window |
| JDK 21 | Needed only to build from source |
| Gatekeeper | When you download and unzip the app yourself, the first launch may show a warning. Right-click the app in Finder and choose `Open` |

<br>

## How it works

The app is split into six modules. `core` provides services, extension points, and `Disposer`; `lang` defines the language, file type, highlighter, and commenter APIs. `search` holds the file name index, the Kotlin and Java declaration index, and the Search Everywhere model, and `app` contains the Compose Desktop UI with the editor, navigation, bookmarks, Run window, and terminal. `plugins/lang-kotlin` and `plugins/lang-json` register the Kotlin and JSON lexers, highlighters, and file types.

Every module declares extension points and registers extensions through a `META-INF/stride-plugin.xml` descriptor. The app descriptor opens the file type, syntax highlighter, commenter, editor, tree structure, and Search Everywhere contributor extension points, and the language plugins and the app itself register into them. Class and symbol search register as contributors over the declaration index, and the app assembles action search directly. The full contracts are in [core](docs/api-core.md) · [lang](docs/api-lang.md) · [search](docs/api-search.md) · [app](docs/api-app.md).

The editor keeps its scroll viewport and chrome insets separate: content runs to the window edge while the glass capsules and the sidebar float over it, and the insets feed caret scrolling, the gutter, and scrollbar padding. Documents of 1,000 lines or more place their content in a separate scroll layer and drop back below 800 lines.

On the native path a JNI library places system glass views behind the window content and Compose only reports the pieces above them. When the library is missing or macOS is older than 26, the same piece reports are drawn with Skia materials instead.

<br>

## Development

```sh
./gradlew test                    # tests; must report zero failures
./gradlew :app:createDistributable
./gradlew :app:run                # opens a window, so run it yourself
```

- The tests number about 580 and take roughly two minutes. `UiSnapshotTest` writes screen PNGs to `app/build/ui-snapshots/`
- `:app:buildNativeGlass` runs only when `clang++` (Xcode Command Line Tools) is available; otherwise it logs one line and the app uses the Skia glass path. Pass `-Pstride.nativeGlass=false` to skip the native build
- Release with `scripts/release.sh X.Y.Z -m "<summary of changed features>"`. `scripts/release-selftest.sh` checks that script
- Repository rules are in [CLAUDE.md](CLAUDE.md)

<br>

<p align="center"><a href="LICENSE">MIT</a></p>
