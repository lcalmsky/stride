package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.actions.ActionGroups
import stride.app.actions.openContextMenu
import stride.app.editor.FoldRegion
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindOptions
import stride.app.files.FileOperationPage
import stride.app.files.FileOperationRequest
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.dispatchAction
import stride.app.navigation.NavigationBarState
import stride.app.navigation.NavigationLocation
import stride.app.problems.CompilerMessage
import stride.app.problems.ProblemSource
import stride.app.problems.ProblemsModel
import stride.app.refactor.openRefactorThis
import stride.app.search.FindTabKind
import stride.app.run.FakeProcess
import stride.app.run.GradleCommand
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.run.openContextActions
import stride.app.terminal.FakeTtyConnector
import stride.app.terminal.TerminalSession
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ProblemSeverity
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import stride.search.SearchTab
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.vcs.BranchList
import stride.app.vcs.CliGitExecutor
import stride.app.vcs.DiffRow
import stride.app.vcs.DiffRows
import stride.app.vcs.HunkKind
import stride.app.vcs.TEST_GIT
import stride.app.vcs.TestGitRepo
import stride.app.vcs.commitRows
import stride.app.vcs.openBranches
import stride.app.vcs.openWorkingTreeDiff

/**
 * Renders the real [App] composable through [ImageComposeScene] and writes the result to
 * `app/build/ui-snapshots`. Software-only, so no window or display is required.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UiSnapshotTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun darkBeforeEach() {
        AppearanceState.set(Appearance.DARK)
    }

    @AfterEach
    fun darkAfterEach() {
        AppearanceState.set(Appearance.DARK)
    }

    @Test
    @Order(1)
    fun `empty state snapshot`() {
        val state = AppState()
        try {
            renderApp("01-empty.png", state, renderMillis = 600)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(2)
    fun `kotlin editor snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("docs/notes.md"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            placeCaret(greeting, "\"\$prefix, \$name!\"")
            renderApp("02-editor-kotlin.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(3)
    fun `json editor snapshot`() {
        // The repository has no .json file outside build/, so this snapshot uses a temp project.
        val root = temp.resolve("sample-project")
        Files.createDirectories(root.resolve("config"))
        Files.createDirectories(root.resolve("docs"))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("build.gradle.kts"), "plugins {\n    kotlin(\"jvm\")\n}\n")
        Files.writeString(root.resolve("docs/api-app.md"), "# app api\n\nLayout and editor contract.\n")
        Files.writeString(root.resolve("src/App.kt"), "package sample\n\nfun main() {\n    println(\"strIDE\")\n}\n")
        val json = root.resolve("config/app-settings.json")
        Files.writeString(
            json,
            """
            {
              "name": "strIDE",
              "theme": "dark",
              "window": {
                "width": 1280,
                "height": 820,
                "maximized": false
              },
              "autoSave": true,
              "lastOpened": null,
              "recentFolders": [
                "~/git-repo/stride"
              ]
            }
            """.trimIndent(),
        )

        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "config")
            expand(session, root, "src")
            openEditor(session, root.resolve("build.gradle.kts"))
            openEditor(session, root.resolve("docs/api-app.md"))
            openEditor(session, root.resolve("src/App.kt"))
            val editor = openEditor(session, json)
            placeCaret(editor, "\"maximized\"")
            renderApp("03-editor-json.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(4)
    fun `search with results snapshot`() {
        val state = AppState()
        try {
            val root = analysisProject()
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            state.searchVisible = true
            renderApp("04-search.png", state, initialQuery = "analysis", renderMillis = 1_800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(5)
    fun `search without results snapshot`() {
        val state = AppState()
        try {
            val root = analysisProject()
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            state.searchVisible = true
            renderApp("05-search-empty-results.png", state, initialQuery = "zzzzqqq", renderMillis = 1_200)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(15)
    fun `search without results light snapshot`() {
        val state = AppState()
        try {
            val root = analysisProject()
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            state.searchVisible = true
            renderApp("05-search-empty-results-light.png", state, initialQuery = "", renderMillis = 1_200, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(6)
    fun `selection editor snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            renderApp("06-selection-editor.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(7)
    fun `selection popup snapshot`() {
        val state = AppState()
        try {
            val root = analysisProject()
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            state.searchVisible = true
            renderApp("07-selection-popup.png", state, initialQuery = "analysis", renderMillis = 1_800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(8)
    fun `find bar snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.findBar.open(editor, replace = true)
            state.findBar.query = "name"
            state.findBar.refresh(editor)
            renderApp("08-find-bar.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(9)
    fun `recent files snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            openEditor(session, root.resolve("docs/notes.md"))
            state.recentFilesVisible = true
            renderApp("09-recent-files.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(10)
    fun `terminal snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val fake = FakeTtyConnector(
                "stride src % ls\r\n" +
                    "\u001b[34mdocs\u001b[0m   \u001b[33mbuild.gradle.kts\u001b[0m   \u001b[35msrc\u001b[0m\r\n" +
                    "stride src % git status\r\n" +
                    "\u001b[32mOn branch main\u001b[0m\r\n" +
                    "\u001b[31mnothing to commit\u001b[0m, \u001b[36mworking tree clean\u001b[0m\r\n" +
                    "stride src % ",
            )
            session.project.service<TerminalSession>().start(fake)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                session.project.service<TerminalSession>().lines().none { it.contains("working tree clean") }
            ) {
                Thread.sleep(20)
            }
            state.toolWindows.activate("terminal")
            renderApp("10-terminal.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("10-terminal.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(11)
    fun `run window snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val manager = session.project.service<RunManager>()
            val events = listOf(
                "##stride[test start class='demo.GreeterTest' name='greets once']",
                "##stride[test out class='demo.GreeterTest' name='greets once' stream='stdout' text='running greeter']",
                "##stride[test pass class='demo.GreeterTest' name='greets once' duration='12']",
                "##stride[test start class='demo.GreeterTest' name='greets three times']",
                "##stride[test pass class='demo.GreeterTest' name='greets three times' duration='7']",
                "##stride[test start class='demo.GreeterTest' name='rejects a blank prefix']",
                "##stride[test fail class='demo.GreeterTest' name='rejects a blank prefix' duration='4' " +
                    "message='expected <Hello> but was <>' stack='at demo.GreeterTest.rejects(GreeterTest.kt:12)']",
                "> Task :test",
                "3 tests completed, 1 failed",
            ).joinToString("\n") + "\n"
            manager.starter = ProcessStarter { configuration ->
                StartedProcess(FakeProcess(events.toByteArray(), 1), GradleCommand.display(emptyList()))
            }
            manager.run(
                RunConfiguration(
                    name = "demo.GreeterTest",
                    workingDir = root,
                    args = GradleCommand.testArgs("", "demo.GreeterTest"),
                    kind = RunKind.TEST,
                ),
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && manager.current?.exitCode == null) {
                Thread.sleep(20)
            }
            state.toolWindows.activate("run")
            renderApp("11-run-window.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("11-run-window.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(12)
    fun `editor scrolled snapshot`() {
        val root = temp.resolve("scroll-project")
        Files.createDirectories(root)
        // Long lines on purpose: the content scrolled under the glass bars must be visible.
        // Every line is long and the view is scrolled to column 290, so the code also runs
        // under the tab bar and the status bar.
        val content = (1..120).joinToString("\n") { line -> "line $line " + "y".repeat(340) }
        Files.writeString(root.resolve("Long.kt"), content)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = openEditor(session, root.resolve("Long.kt"))
            editor.gotoLine("100:290")
            renderApp("12-editor-scrolled.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(13)
    fun `search over code snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            openEditor(session, root.resolve("docs/notes.md"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.openSearch(SearchTab.ALL)
            renderApp("13-search-over-code.png", state, initialQuery = "greet", renderMillis = 1_800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(14)
    fun `context actions over code snapshot`() {
        val root = intentionProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<DeclarationIndex>().awaitReady(5_000)) { "declaration index did not finish" }
            val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            placeCaret(editor, "Clock")
            check(openContextActions(state) && state.contextActionsVisible) {
                "no context actions for the Clock call"
            }
            renderApp("14-context-actions.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(16)
    fun `empty state light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val state = AppState()
        try {
            renderApp("01-empty-light.png", state, renderMillis = 600, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(17)
    fun `kotlin editor light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("docs/notes.md"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            placeCaret(greeting, "\"\$prefix, \$name!\"")
            renderApp("02-editor-kotlin-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(18)
    fun `search light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val state = AppState()
        try {
            val root = analysisProject("analysis-project-light")
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            state.searchVisible = true
            renderApp(
                "04-search-light.png",
                state,
                initialQuery = "analysis",
                renderMillis = 1_800,
                appearance = Appearance.LIGHT,
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(19)
    fun `find bar light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.findBar.open(editor, replace = true)
            state.findBar.query = "name"
            state.findBar.refresh(editor)
            renderApp("08-find-bar-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(20)
    fun `terminal light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val fake = FakeTtyConnector(
                "stride src % ls\r\n" +
                    "\u001b[34mdocs\u001b[0m   \u001b[33mbuild.gradle.kts\u001b[0m   \u001b[35msrc\u001b[0m\r\n" +
                    "stride src % git status\r\n" +
                    "\u001b[32mOn branch main\u001b[0m\r\n" +
                    "\u001b[31mnothing to commit\u001b[0m, \u001b[36mworking tree clean\u001b[0m\r\n" +
                    "stride src % ",
            )
            session.project.service<TerminalSession>().start(fake)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                session.project.service<TerminalSession>().lines().none { it.contains("working tree clean") }
            ) {
                Thread.sleep(20)
            }
            state.toolWindows.activate("terminal")
            renderApp("10-terminal-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("10-terminal-light.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(21)
    fun `run window light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val manager = session.project.service<RunManager>()
            val events = listOf(
                "##stride[test start class='demo.GreeterTest' name='greets once']",
                "##stride[test out class='demo.GreeterTest' name='greets once' stream='stdout' text='running greeter']",
                "##stride[test pass class='demo.GreeterTest' name='greets once' duration='12']",
                "##stride[test start class='demo.GreeterTest' name='greets three times']",
                "##stride[test pass class='demo.GreeterTest' name='greets three times' duration='7']",
                "##stride[test start class='demo.GreeterTest' name='rejects a blank prefix']",
                "##stride[test fail class='demo.GreeterTest' name='rejects a blank prefix' duration='4' " +
                    "message='expected <Hello> but was <>' stack='at demo.GreeterTest.rejects(GreeterTest.kt:12)']",
                "> Task :test",
                "3 tests completed, 1 failed",
            ).joinToString("\n") + "\n"
            manager.starter = ProcessStarter { configuration ->
                StartedProcess(FakeProcess(events.toByteArray(), 1), GradleCommand.display(emptyList()))
            }
            manager.run(
                RunConfiguration(
                    name = "demo.GreeterTest",
                    workingDir = root,
                    args = GradleCommand.testArgs("", "demo.GreeterTest"),
                    kind = RunKind.TEST,
                ),
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && manager.current?.exitCode == null) {
                Thread.sleep(20)
            }
            state.toolWindows.activate("run")
            renderApp("11-run-window-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("11-run-window-light.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(22)
    fun `editor scrolled light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = temp.resolve("scroll-project-light")
        Files.createDirectories(root)
        val content = (1..120).joinToString("\n") { line -> "line $line " + "y".repeat(340) }
        Files.writeString(root.resolve("Long.kt"), content)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = openEditor(session, root.resolve("Long.kt"))
            editor.gotoLine("100:290")
            renderApp("12-editor-scrolled-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(23)
    fun `search over code light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            openEditor(session, root.resolve("docs/notes.md"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.openSearch(SearchTab.ALL)
            renderApp(
                "13-search-over-code-light.png",
                state,
                initialQuery = "greet",
                renderMillis = 1_800,
                appearance = Appearance.LIGHT,
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(25)
    fun `context actions over code light snapshot`() {
        AppearanceState.set(Appearance.LIGHT)
        val root = intentionProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<DeclarationIndex>().awaitReady(5_000)) { "declaration index did not finish" }
            val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            placeCaret(editor, "Clock")
            check(openContextActions(state) && state.contextActionsVisible) {
                "no context actions for the Clock call"
            }
            renderApp("14-context-actions-light.png", state, renderMillis = 800, appearance = Appearance.LIGHT)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(26)
    fun `multi caret editor snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("docs/notes.md"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val text = greeting.value.text
            // `count` appears on three lines, so the occurrence selection paints three bands.
            greeting.updateValue(TextFieldValue(text, TextRange(text.indexOf("count"))))
            greeting.selectAllOccurrences()
            greeting.toggleCaretAt(text.indexOf("message") + "message".length)
            renderApp("15-multi-caret.png", state, renderMillis = 800)
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(27)
    fun `folding editor snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val text = greeting.value.text
            val funLine = text.indexOf("fun greet(name: String, count: Int = 3): String {")
            val caret = funLine + "fun greet".length
            greeting.updateValue(TextFieldValue(text, TextRange(caret)))
            check(greeting.applyAction(ActionId.COLLAPSE_REGION)) { "the function body did not collapse" }
            val body = funLine + "fun greet(name: String, count: Int = 3): String ".length + 1
            assertEquals(
                listOf(FoldRegion(body, text.indexOf("\n    }") + 5)),
                greeting.foldedRegions,
                "the snapshot must show one folded function body",
            )
            // The caret stays on the folded row, which now shows `{...}`.
            greeting.updateValue(TextFieldValue(text, TextRange(caret)))
            renderApp("16-folding.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("16-folding.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(28)
    fun `find window snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.openFindResults(
                kind = FindTabKind.USAGES,
                title = "Usages of greet",
                query = "greet",
                options = FindOptions(caseSensitive = true, wholeWord = true),
            )
            val tab = state.findToolWindow.tabs.single()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && tab.searching) Thread.sleep(10)
            check(!tab.searching) { "the usages scan did not finish" }
            check(tab.hits.isNotEmpty()) { "the usages tab has no hits" }
            renderApp("17-find-window.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("17-find-window.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(29)
    fun `replace in path snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            expand(session, root, "src", "main", "kotlin", "demo")
            openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            state.openFindInPath(query = "greet", replace = true)
            renderApp("18-replace-in-path.png", state, renderMillis = 1_800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("18-replace-in-path.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(0)
    fun `problems window snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            expand(session, root, "src", "main", "kotlin", "demo")
            val greetingFile = root.resolve("src/main/kotlin/demo/Greeting.kt")
            Files.writeString(
                greetingFile,
                """
                package demo

                import java.time.Clock

                /**
                 * Greets one person per request.
                 *
                 * The prefix defaults to "Hello"; keep custom prefixes under 8 characters so the
                 * greeting still reads well in the console.
                 */
                @Suppress("unused")
                class Greeter(private val prefix: String = "Hello") {
                    // Templates keep the wording in one place.
                    fun greet(name: String, count: Int = 3): String {
                        val message = "${'$'}prefix, ${'$'}name!"
                        if (count <= 0) return ""
                        return (1..count).joinToString(separator = ", ") { mesage }
                    }
                }
                """.trimIndent(),
            )
            val greeting = openEditor(session, greetingFile)
            val text = greeting.value.text
            val path = greeting.file.path.toAbsolutePath().normalize()
            fun lineOf(needle: String): Int = text.substring(0, text.indexOf(needle)).count { it == '\n' }
            fun columnOf(needle: String): Int {
                val index = text.indexOf(needle)
                return index - (text.lastIndexOf('\n', index) + 1)
            }
            val model = session.project.service<ProblemsModel>()
            model.publishBuild(
                listOf(
                    CompilerMessage(
                        path,
                        lineOf("mesage"),
                        columnOf("mesage"),
                        ProblemSeverity.ERROR,
                        "Unresolved reference 'mesage'.",
                    ),
                ),
            )
            val problems = model.compute(path, greeting.file.name, text)
            greeting.problems = problems
            val error = problems.single { it.source == ProblemSource.BUILD }
            greeting.updateValue(TextFieldValue(text, TextRange(error.start)))
            state.toolWindows.activate("problems")
            state.focus = KeyContext.LIST_WINDOW

            assertEquals(2, problems.size)
            val warning = problems.single { it.severity == ProblemSeverity.WARNING }
            assertEquals(ProblemSource.EDITOR, warning.source)
            assertEquals("Unused import directive", warning.message)
            assertEquals("Unresolved reference 'mesage'.", error.message)
            assertEquals(1, model.projectErrors { it == path }.sumOf { it.errors.size })

            renderApp("19-problems.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("19-problems.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(24)
    fun `switching the appearance re-themes without losing the caret or scroll`() {
        val root = demoProject()
        val state = AppState()
        var scene: ImageComposeScene? = null
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            placeCaret(editor, "\"\$prefix, \$name!\"")
            val textBefore = editor.value.text
            val caretBefore = editor.value.selection
            scene = ImageComposeScene(2560, 1640, Density(2f)) {
                CompositionLocalProvider(
                    LocalWordmarkCaretBlink provides false,
                    LocalAnimationsEnabled provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            var nanos = 0L
            repeat(30) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            val scrollBefore = editor.verticalScroll.value
            val darkMean = editorRegionLuminance(scene.render(nanos))
            nanos += 16_000_000L

            AppearanceState.set(Appearance.LIGHT)
            repeat(30) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            val lightMean = editorRegionLuminance(scene.render(nanos))

            assertEquals(textBefore, editor.value.text, "the document changed across the theme switch")
            assertEquals(caretBefore, editor.value.selection, "the caret moved across the theme switch")
            assertEquals(scrollBefore, editor.verticalScroll.value, "the scroll offset changed across the theme switch")
            assertTrue(
                lightMean > darkMean + 0.2,
                "the editor did not re-theme: dark mean %.3f, light mean %.3f".format(darkMean, lightMean),
            )
            System.err.println("appearance-switch dark=%.3f light=%.3f".format(darkMean, lightMean))
        } finally {
            scene?.close()
            state.dispose()
        }
    }

    private fun editorRegionLuminance(image: org.jetbrains.skia.Image): Double {
        try {
            val bitmap = image.toComposeImageBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            var total = 0.0
            var count = 0
            for (y in 300 until 1200 step 4) {
                for (x in 600 until 2400 step 4) {
                    total += stride.app.ui.theme.relativeLuminance(Color(pixels[y * bitmap.width + x]))
                    count++
                }
            }
            return total / count
        } finally {
            image.close()
        }
    }

    @Test
    @Order(31)
    fun `show usages popup snapshot`() {
        val root = demoProject()
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            """
            package demo

            fun main() {
                val greeting = Greeter()
                println(greeting.greet("strIDE"))
                println(greeting.greet("team"))
                println(greeting.greet("world"))
            }
            """.trimIndent() + "\n",
        )
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
            expand(session, root, "src", "main", "kotlin", "demo")
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val text = greeting.value.text
            greeting.updateValue(TextFieldValue(text, TextRange(text.indexOf("fun greet(") + 4)))

            assertTrue(dispatchAction(ActionId.SHOW_USAGES, state) { null })
            val request = requireNotNull(state.showUsages)
            assertEquals("greet", request.name)

            renderApp("20-show-usages.png", state, renderMillis = 1_800)

            check(!request.searching) { "the usages scan did not finish" }
            check(request.hits.size >= 3) { "the popup has ${request.hits.size} usage rows" }
            assertEquals(2, request.hits.map { it.relativePath }.distinct().size)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("20-show-usages.png")))
        } finally {
            state.dispose()
        }
    }


    @Test
    @Order(32)
    fun `recent locations popup snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            val main = openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val mainText = main.value.text
            val greetingText = greeting.value.text
            val mainPath = main.file.path.toAbsolutePath().normalize()
            val greetingPath = greeting.file.path.toAbsolutePath().normalize()
            session.history.push(NavigationLocation(mainPath, mainText.indexOf("println")))
            session.history.push(NavigationLocation(greetingPath, greetingText.indexOf("val message")))
            session.history.push(NavigationLocation(mainPath, mainText.indexOf("fun main")))
            session.history.push(NavigationLocation(greetingPath, greetingText.indexOf("if (count")))
            greeting.updateValue(TextFieldValue(greetingText, TextRange(greetingText.indexOf("class Greeter") + 6)))

            assertTrue(dispatchAction(ActionId.RECENT_LOCATIONS, state) { null })
            renderApp("21-recent-locations.png", state, renderMillis = 1_500)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("21-recent-locations.png")))
        } finally {
            state.dispose()
        }
    }


    @Test
    @Order(33)
    fun `navigation bar snapshot`() {
        val root = demoProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            expand(session, root, "src", "main", "kotlin", "demo")
            val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            val bar = NavigationBarState.forFile(root, greeting.file.path)
            val folder = bar.segments.indexOfLast { it.isDirectory }
            state.navigationBar = bar.copy(selected = folder).openList { path ->
                session.tree.children(VFile(path))
            }
            state.focus = KeyContext.NAV_BAR

            renderApp("22-navigation-bar.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("22-navigation-bar.png")))
        } finally {
            state.dispose()
        }
    }


    @Test
    @Order(34)
    fun `type hierarchy window snapshot`() {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<DeclarationIndex>().awaitReady(5_000)) { "declaration index did not finish" }
            expand(session, root, "demo", "shapes")
            val polygon = openEditor(session, root.resolve("demo/shapes/Polygon.kt"))
            val text = polygon.value.text
            polygon.updateValue(TextFieldValue(text, TextRange(text.indexOf("class Polygon") + 6)))

            assertTrue(dispatchAction(ActionId.TYPE_HIERARCHY, state) { null })
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                state.hierarchyToolWindow.rows.none { it.node?.text == "Square" }
            ) {
                Thread.sleep(20)
            }
            val rows = state.hierarchyToolWindow.rows
            assertTrue(rows.any { it.node?.text == "Supertypes" }, "the Supertypes row did not load")
            assertTrue(rows.any { it.node?.text == "Subtypes" }, "the Subtypes row did not load")
            assertTrue(rows.any { it.node?.text == "Shape" }, "the supertype rows did not load")
            assertTrue(rows.any { it.node?.text == "Square" }, "the subtype rows did not load")

            state.toolWindows.height = 420.dp
            renderApp("23-type-hierarchy.png", state, renderMillis = 1_500)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("23-type-hierarchy.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(35)
    fun `refactor this snapshot`() {
        val root = refactoringProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = openEditor(session, root.resolve("src/main/kotlin/api/OrderService.kt"))
            val text = editor.value.text
            val use = text.indexOf("discount", text.indexOf("return"))
            check(use > 0) { "the discount use did not move into the return statement" }
            editor.updateValue(TextFieldValue(text, TextRange(use, use + "discount".length)))

            assertTrue(openRefactorThis(state))
            val request = requireNotNull(state.refactorThis) { "Refactor This opened no popup" }
            assertEquals(
                listOf("Rename…", "Introduce Variable…", "Extract Method…", "Inline…"),
                request.rows.map { it.text },
            )
            assertEquals(listOf("⇧F6", "⌥⌘V", "⌥⌘M", "⌥⌘N"), request.rows.map { it.hint })
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

            renderApp("24-refactor-this.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("24-refactor-this.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(36)
    fun `generate implement methods snapshot`() {
        val root = generateProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            check(session.project.service<DeclarationIndex>().awaitReady(5_000)) { "declaration index did not finish" }
            expand(session, root, "src", "main", "kotlin", "com", "example", "orders")
            val editor = openEditor(session, root.resolve("src/main/kotlin/com/example/orders/OrderRepository.kt"))
            val text = editor.value.text
            val member = text.indexOf("private val orders")
            check(member > 0) { "the order list member did not land in the class body" }
            editor.updateValue(TextFieldValue(text, TextRange(member)))

            val stroke = KeyRouter(isMac = true)
                .route(KeyEventType.DOWN, Key.I, ctrl = true, focus = KeyContext.EDITOR)
            assertEquals(ActionId.IMPLEMENT_METHODS, stroke)
            assertTrue(dispatchAction(requireNotNull(stroke), state) { null })

            val request = requireNotNull(state.memberChoose) { "⌃I opened no member chooser" }
            assertEquals("Implement Methods", request.step.title)
            assertEquals(
                listOf(
                    "fun find(id: Long): Order?",
                    "fun save(item: Order): Order",
                    "fun delete(id: Long)",
                    "val size: Int",
                ),
                request.step.members.map { it.text },
            )
            assertEquals(List(4) { "Repository" }, request.step.members.map { it.group })
            assertTrue(request.step.members.all { it.checked && it.disabledReason == null })
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

            renderApp("25-generate-implement.png", state, renderMillis = 800)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("25-generate-implement.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(37)
    fun `commit window snapshot`() {
        val repo = retryProject()
        val state = AppState()
        try {
            val session = openGitProject(repo, state)
            val policy = repo.root.resolve(POLICY_PATH)
            val editor = openEditor(session, policy)
            editor.setText(EDITED_POLICY)
            editor.save()
            repo.write(CLIENT_PATH, EDITED_CLIENT)
            repo.write(BACKOFF_PATH, BACKOFF)
            runBlocking { session.git.refreshNow() }
            waitForChangeHunks(editor)

            assertTrue(dispatchAction(ActionId.COMMIT, state) { null })
            val commit = session.commit
            commit.syncRows(commitRows(session))
            commit.message = "Cap retry delay at 30 seconds"
            val backoff = requireNotNull(commit.rows.firstOrNull { it.path.fileName.toString() == "Backoff.kt" }) {
                "the untracked file did not reach the Commit window"
            }
            commit.inclusion.toggle(backoff)
            state.toolWindows.height = 360.dp

            renderApp("26-commit-window.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("26-commit-window.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(38)
    fun `diff popup snapshot`() {
        val repo = retryProject()
        val state = AppState()
        try {
            val session = openGitProject(repo, state)
            val policy = repo.root.resolve(POLICY_PATH)
            val editor = openEditor(session, policy)
            editor.setText(EDITED_POLICY)
            editor.save()
            repo.write(CLIENT_PATH, EDITED_CLIENT)
            repo.write(BACKOFF_PATH, BACKOFF)
            runBlocking { session.git.refreshNow() }
            waitForChangeHunks(editor)

            assertTrue(openWorkingTreeDiff(state, policy, line = MODIFIED_LINE))
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && state.diffRequest == null) Thread.sleep(20)
            val request = checkNotNull(state.diffRequest) { "the diff popup did not open" }
            val rows = DiffRows.build(request.files.single())
            assertTrue(
                rows.any { it is DiffRow.Code && it.sign == '+' && it.text.contains("minOf(delayMillis, capMillis)") },
                "the popup has no row for the changed line",
            )

            renderApp("27-diff.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("27-diff.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(39)
    fun `branches popup snapshot`() {
        val repo = retryProject()
        repo.bareRemote()
        repo.git("push", "-q", "-u", "origin", "main")
        repo.git("branch", "feature/login")
        repo.git("branch", "fix/retry-delay")
        repo.git("push", "-q", "origin", "feature/login")
        val state = AppState()
        try {
            openGitProject(repo, state)
            assertTrue(openBranches(state))
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && state.branchesPopup?.list !is BranchList.Ready) {
                Thread.sleep(20)
            }
            val list = checkNotNull(state.branchesPopup?.list as? BranchList.Ready) {
                "the branch list did not load"
            }
            assertEquals("main", list.current)
            assertEquals(listOf("main", "feature/login", "fix/retry-delay"), list.local.map { it.name })
            val current = requireNotNull(list.local.firstOrNull { it.current })
            assertEquals("origin/main", current.upstream)
            assertEquals(0, current.ahead)
            assertEquals(0, current.behind)
            assertTrue(list.remote.any { it.display == "origin/main" })
            assertTrue(list.remote.any { it.display == "origin/feature/login" })

            renderApp("28-branches.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("28-branches.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(40)
    fun `project tree files snapshot`() {
        val root = temp.resolve("files-project")
        val packageDirectory = root.resolve("src/main/kotlin/api")
        Files.createDirectories(packageDirectory)
        Files.writeString(packageDirectory.resolve("OrderService.kt"), "package api\n\nclass OrderService\n")
        Files.writeString(root.resolve("README.md"), "# api-server\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.tree.reveal(VFile(packageDirectory.resolve("OrderService.kt")))
            session.tree.select(VFile(packageDirectory))
            state.focusProjectTree()
            state.fileOperation = FileOperationRequest(
                page = FileOperationPage.NEW_LIST,
                target = packageDirectory,
                returnContext = KeyContext.PROJECT_TREE,
            )
            state.focus = KeyContext.CONTEXT_POPUP

            renderApp("29-files.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("29-files.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(41)
    fun `context menu snapshot`() {
        val root = temp.resolve("menu-project")
        val packageDirectory = root.resolve("src/main/kotlin/api")
        Files.createDirectories(packageDirectory)
        Files.writeString(packageDirectory.resolve("OrderService.kt"), "package api\n\nclass OrderService\n")
        Files.writeString(root.resolve("README.md"), "# api-server\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.tree.reveal(VFile(packageDirectory.resolve("OrderService.kt")))
            session.tree.select(VFile(packageDirectory))
            state.focusProjectTree()
            // The api row's line in the tree at the default window size; the menu's New submenu
            // is open with Kotlin Class/File under it.
            openContextMenu(
                state = state,
                groupId = ActionGroups.PROJECT_VIEW_POPUP_MENU,
                openedFrom = KeyContext.PROJECT_TREE,
                position = Offset(150f, 300f),
                initialSubmenus = listOf(0),
            )
            state.focus = KeyContext.CONTEXT_POPUP

            renderApp("30-context-menu.png", state, renderMillis = 1_200)
            assertTrue(Files.exists(SnapshotRenderer.snapshotDir().resolve("30-context-menu.png")))
        } finally {
            state.dispose()
        }
    }

    @Test
    @Order(99)
    fun `every snapshot was written`() {
        for (name in SNAPSHOTS) {
            val file = SnapshotRenderer.snapshotDir().resolve(name)
            assertTrue(Files.exists(file) && Files.size(file) > 0, "$name is missing or empty")
        }
    }

    private fun hierarchyProject(): Path {
        val root = temp.resolve("hierarchy-project")
        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write(
            "demo/shapes/Shape.kt",
            """
            package demo.shapes

            interface Shape {
                fun area(): Double
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Circle.kt",
            """
            package demo.shapes

            class Circle(val r: Double) : Shape {
                override fun area() = r * r
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Polygon.kt",
            """
            package demo.shapes

            abstract class Polygon : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Square.kt",
            """
            package demo.shapes

            class Square : Polygon() {
                override fun area() = 1.0
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Solid.kt",
            """
            package demo.shapes

            interface Solid : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Triangle.java",
            """
            package demo.shapes;

            public class Triangle implements Shape {
                public double area() {
                    return 0;
                }
            }
            """.trimIndent() + "\n",
        )
        return root
    }

    private fun refactoringProject(): Path {
        val root = temp.resolve("api-server")
        Files.createDirectories(root.resolve("src/main/kotlin/api"))
        Files.writeString(root.resolve("build.gradle.kts"), "plugins {\n    kotlin(\"jvm\")\n}\n")
        Files.writeString(
            root.resolve("src/main/kotlin/api/OrderService.kt"),
            """
            package api

            class OrderService(private val orders: List<Order>) {
                fun total(): Double {
                    val discount: Double = 0.1
                    val subtotal = orders.sumOf { it.price }
                    return subtotal * (1 - discount)
                }

                fun cheapest(): Order = orders.minBy { it.price }

                fun describe(): String {
                    val amount = total()
                    return "total: ${'$'}amount"
                }
            }

            class Order(val price: Double)
            """.trimIndent() + "\n",
        )
        return root
    }

    private fun generateProject(): Path {
        val root = temp.resolve("api-server")
        val packageDirectory = root.resolve("src/main/kotlin/com/example/orders")
        Files.createDirectories(packageDirectory)
        Files.writeString(root.resolve("build.gradle.kts"), "plugins {\n    kotlin(\"jvm\")\n}\n")
        Files.writeString(
            packageDirectory.resolve("Repository.kt"),
            """
            package com.example.orders

            interface Repository<T> {
                fun find(id: Long): T?
                fun save(item: T): T
                fun delete(id: Long)
                val size: Int
            }
            """.trimIndent() + "\n",
        )
        Files.writeString(
            packageDirectory.resolve("OrderRepository.kt"),
            """
            package com.example.orders

            class OrderRepository : Repository<Order> {
                private val orders = mutableListOf<Order>()

                fun add(order: Order) {
                    orders += order
                }
            }

            class Order(val id: Long, val total: Double)
            """.trimIndent() + "\n",
        )
        return root
    }

    private fun demoProject(): Path {
        val root = temp.resolve("demo-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("build.gradle.kts"), "plugins {\n    kotlin(\"jvm\")\n}\n")
        Files.writeString(root.resolve("docs/notes.md"), "# demo notes\n\nSample sources for the Kotlin snapshot.\n")
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            "package demo\n\nfun main() {\n    println(Greeter().greet(\"strIDE\"))\n}\n",
        )
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Greeting.kt"),
            """
            package demo

            /**
             * Greets one person per request.
             *
             * The prefix defaults to "Hello"; keep custom prefixes under 8 characters so the
             * greeting still reads well in the console.
             */
            @Suppress("unused")
            class Greeter(private val prefix: String = "Hello") {
                // Templates keep the wording in one place.
                fun greet(name: String, count: Int = 3): String {
                    val message = "${'$'}prefix, ${'$'}name!"
                    if (count <= 0) return ""
                    return (1..count).joinToString(separator = ", ") { message }
                }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun intentionProject(): Path {
        val root = temp.resolve("intention-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo/time"))
        Files.createDirectories(root.resolve("src/main/kotlin/demo/text"))
        Files.writeString(root.resolve("build.gradle.kts"), "plugins {\n    kotlin(\"jvm\")\n}\n")
        Files.writeString(
            root.resolve("src/main/kotlin/demo/time/Clock.kt"),
            "package demo.time\n\nclass Clock {\n    fun now(): Long = 0L\n}\n",
        )
        Files.writeString(
            root.resolve("src/main/kotlin/demo/text/Banner.kt"),
            "package demo.text\n\nobject Banner {\n    fun render(value: Long): String = \"tick ${'$'}value\"\n}\n",
        )
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            "package demo\n\nimport demo.text.Banner\n\nfun main() {\n    val clock = Clock()\n" +
                "    println(Banner.render(clock.now()))\n}\n",
        )
        return root
    }

    private fun analysisProject(name: String = "analysis-project"): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root.resolve("core/analysis"))
        Files.createDirectories(root.resolve("config"))
        Files.createDirectories(root.resolve("docs"))
        Files.createDirectories(root.resolve("src/main/kotlin"))
        Files.writeString(root.resolve("core/analysis-engine.kt"), "package core\n\nclass AnalysisEngine\n")
        Files.writeString(root.resolve("core/analysis/AnalysisRunner.kt"), "package core.analysis\n\nclass AnalysisRunner\n")
        Files.writeString(root.resolve("core/analysis/analysis-result.kt"), "package core.analysis\n\ndata class AnalysisResult(val score: Int)\n")
        Files.writeString(root.resolve("config/analysis-settings.json"), "{\n  \"enabled\": true\n}\n")
        Files.writeString(root.resolve("docs/analysis-report.md"), "# analysis report\n")
        Files.writeString(root.resolve("src/main/kotlin/Main.kt"), "fun main() {\n    println(\"strIDE\")\n}\n")
        Files.writeString(root.resolve("README.md"), "# sample project\n")
        return root
    }

    /** A committed repository holding a small retry client, its policy and a README. */
    private fun retryProject(): TestGitRepo {
        val repo = TestGitRepo.create(temp, "api-server")
        repo.write("README.md", "# api-server\n\nA small service client that retries failed requests.\n")
        repo.write(POLICY_PATH, RETRY_POLICY)
        repo.write(CLIENT_PATH, CLIENT)
        repo.commitAll("Add the retry client")
        return repo
    }

    private fun openGitProject(repo: TestGitRepo, state: AppState): ProjectSession {
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        return session
    }

    /** Waits for the editor's diff against HEAD, then checks the three hunks the picture shows. */
    private fun waitForChangeHunks(editor: TextFileEditor) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && editor.vcsHunks.size != 3) Thread.sleep(20)
        assertEquals(
            listOf(HunkKind.ADDED, HunkKind.MODIFIED, HunkKind.DELETED),
            editor.vcsHunks.sortedBy { it.newStart }.map { it.kind },
            "hunks: ${editor.vcsHunks}",
        )
        assertEquals(2, editor.vcsHunks.first { it.kind == HunkKind.ADDED }.newCount)
    }

    private fun openEditor(session: ProjectSession, path: Path): TextFileEditor =
        session.editors.openFile(VFile(path)) as TextFileEditor

    private fun expand(session: ProjectSession, root: Path, vararg segments: String) {
        var directory = root
        for (segment in segments) {
            directory = directory.resolve(segment)
            session.tree.expand(VFile(directory))
        }
    }

    private fun placeCaret(editor: TextFileEditor, needle: String) {
        val text = editor.value.text
        val found = text.indexOf(needle)
        val offset = if (found < 0) 0 else found + needle.length
        editor.updateValue(TextFieldValue(text, TextRange(offset)))
    }

    private fun renderApp(
        name: String,
        state: AppState,
        initialQuery: String = "",
        renderMillis: Long = 800,
        appearance: Appearance = Appearance.DARK,
    ) {
        AppearanceState.set(appearance)
        SnapshotRenderer.render(
            SnapshotRenderer.snapshotDir().resolve(name),
            state,
            initialQuery = initialQuery,
            renderMillis = renderMillis,
        )
    }

    companion object {
        private val SNAPSHOTS = listOf(
            "01-empty.png",
            "02-editor-kotlin.png",
            "03-editor-json.png",
            "04-search.png",
            "05-search-empty-results.png",
            "05-search-empty-results-light.png",
            "06-selection-editor.png",
            "07-selection-popup.png",
            "08-find-bar.png",
            "09-recent-files.png",
            "10-terminal.png",
            "11-run-window.png",
            "12-editor-scrolled.png",
            "13-search-over-code.png",
            "14-context-actions.png",
            "14-context-actions-light.png",
            "15-multi-caret.png",
            "16-folding.png",
            "17-find-window.png",
            "18-replace-in-path.png",
            "19-problems.png",
            "20-show-usages.png",
            "21-recent-locations.png",
            "22-navigation-bar.png",
            "23-type-hierarchy.png",
            "24-refactor-this.png",
            "25-generate-implement.png",
            "01-empty-light.png",
            "02-editor-kotlin-light.png",
            "04-search-light.png",
            "08-find-bar-light.png",
            "10-terminal-light.png",
            "11-run-window-light.png",
            "12-editor-scrolled-light.png",
            "13-search-over-code-light.png",
            "26-commit-window.png",
            "27-diff.png",
            "28-branches.png",
            "29-files.png",
            "30-context-menu.png",
        )

        private const val POLICY_PATH = "src/main/kotlin/demo/api/RetryPolicy.kt"
        private const val CLIENT_PATH = "src/main/kotlin/demo/api/Client.kt"
        private const val BACKOFF_PATH = "src/main/kotlin/demo/api/Backoff.kt"
        private val RETRY_POLICY = """
            package demo.api

            /**
             * How often and how long the client waits between retries.
             *
             * The delay doubles after every failed attempt, starting from [delayMillis], so a request
             * that keeps failing waits longer each time but never for very long.
             */
            data class RetryPolicy(
                val maxAttempts: Int = 4,
                val delayMillis: Long = 500,
            ) {
                /** The wait before [attempt], counting from 1; 0 when there is no wait left. */
                fun nextDelay(attempt: Int): Long {
                    if (attempt <= 0) return 0
                    if (attempt > maxAttempts) return 0
                    var delay = delayMillis
                    repeat(attempt - 1) { delay *= 2 }
                    return delay
                }

                fun describe(): String = "up to ${'$'}maxAttempts attempts, then give up"
            }
        """.trimIndent() + "\n"

        /** The two added lines, the changed line and the removed line stay apart; touching edits merge. */
        private val EDITED_POLICY = """
            package demo.api

            /**
             * How often and how long the client waits between retries.
             *
             * The delay doubles after every failed attempt, starting from [delayMillis], so a request
             * that keeps failing waits longer each time but never for very long.
             */
            data class RetryPolicy(
                val maxAttempts: Int = 4,
                val delayMillis: Long = 500,
                val jitterMillis: Long = 50,
                val capMillis: Long = 30_000,
            ) {
                /** The wait before [attempt], counting from 1; 0 when there is no wait left. */
                fun nextDelay(attempt: Int): Long {
                    if (attempt <= 0) return 0
                    if (attempt > maxAttempts) return 0
                    var delay = minOf(delayMillis, capMillis)
                    repeat(attempt - 1) { delay *= 2 }
                    return delay
                }

            }
        """.trimIndent() + "\n"

        private val MODIFIED_LINE = EDITED_POLICY.lineSequence().indexOfFirst { "minOf(" in it }

        private val CLIENT = """
            package demo.api

            /** Sends requests through [RetryPolicy] until one succeeds. */
            class Client(private val policy: RetryPolicy = RetryPolicy()) {

                fun fetch(url: String): String {
                    var attempt = 1
                    while (true) {
                        val answer = send(url)
                        if (answer != null) return answer
                        if (attempt >= policy.maxAttempts) return "failed"
                        attempt += 1
                        Thread.sleep(policy.nextDelay(attempt))
                    }
                }

                private fun send(url: String): String? = null
            }
        """.trimIndent() + "\n"

        private val EDITED_CLIENT = CLIENT.replace(
            "if (attempt >= policy.maxAttempts) return \"failed\"",
            "if (attempt >= policy.maxAttempts) return \"gave up\"",
        )

        private val BACKOFF = """
            package demo.api

            /** The time the client has spent waiting between attempts. */
            class Backoff {

                private var sleptMillis = 0L

                fun record(waitMillis: Long) {
                    sleptMillis += waitMillis
                }

                fun total(): Long = sleptMillis
            }
        """.trimIndent() + "\n"

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(UiSnapshotTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
