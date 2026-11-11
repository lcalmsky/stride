package stride.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.run.FakeProcess
import stride.app.run.GradleCommand
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.terminal.FakeTtyConnector
import stride.app.terminal.TerminalSession
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.FileNameIndex

/**
 * Renders the real [App] composable through [ImageComposeScene] and writes the result to
 * `app/build/ui-snapshots`. Software-only, so no window or display is required.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UiSnapshotTest {
    @TempDir
    lateinit var temp: Path

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
    fun `every snapshot was written`() {
        for (name in SNAPSHOTS) {
            val file = SnapshotRenderer.snapshotDir().resolve(name)
            assertTrue(Files.exists(file) && Files.size(file) > 0, "$name is missing or empty")
        }
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

    private fun analysisProject(): Path {
        val root = temp.resolve("analysis-project")
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
    ) {
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
            "06-selection-editor.png",
            "07-selection-popup.png",
            "08-find-bar.png",
            "09-recent-files.png",
            "10-terminal.png",
            "11-run-window.png",
            "12-editor-scrolled.png",
        )

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
