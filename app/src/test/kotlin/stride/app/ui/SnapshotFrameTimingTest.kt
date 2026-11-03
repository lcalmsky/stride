package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
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
 * Measures the single-frame cost of each `UiSnapshotTest` scenario and compares it with the
 * baseline in `stride/ui/frame-baseline.properties`. Each scenario is first settled with the
 * same render loop `SnapshotRenderer.render` uses, then [SnapshotRenderer.renderFrameMillis]
 * reports the median of 20 measured frames.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SnapshotFrameTimingTest {
    @TempDir
    lateinit var temp: Path

    @Test
    @Order(1)
    fun `empty state frame time`() {
        measure("01-empty.png", renderMillis = 600) { AppState() }
    }

    @Test
    @Order(2)
    fun `kotlin editor frame time`() {
        val root = demoProject()
        measure("02-editor-kotlin.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                expand(session, root, "src", "main", "kotlin", "demo")
                openEditor(session, root.resolve("docs/notes.md"))
                openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
                val greeting = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
                placeCaret(greeting, "\"\$prefix, \$name!\"")
            }
        }
    }

    @Test
    @Order(3)
    fun `json editor frame time`() {
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
        measure("03-editor-json.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                expand(session, root, "config")
                expand(session, root, "src")
                openEditor(session, root.resolve("build.gradle.kts"))
                openEditor(session, root.resolve("docs/api-app.md"))
                openEditor(session, root.resolve("src/App.kt"))
                val editor = openEditor(session, json)
                placeCaret(editor, "\"maximized\"")
            }
        }
    }

    @Test
    @Order(4)
    fun `search with results frame time`() {
        val root = analysisProject()
        measure("04-search.png", renderMillis = 1_800, initialQuery = "analysis") {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
                state.searchVisible = true
            }
        }
    }

    @Test
    @Order(5)
    fun `search without results frame time`() {
        val root = analysisProject()
        measure("05-search-empty-results.png", renderMillis = 1_200, initialQuery = "zzzzqqq") {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
                state.searchVisible = true
            }
        }
    }

    @Test
    @Order(6)
    fun `selection editor frame time`() {
        val root = demoProject()
        measure("06-selection-editor.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                expand(session, root, "src", "main", "kotlin", "demo")
                openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
                openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
            }
        }
    }

    @Test
    @Order(7)
    fun `selection popup frame time`() {
        val root = analysisProject()
        measure("07-selection-popup.png", renderMillis = 1_800, initialQuery = "analysis") {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                check(session.project.service<FileNameIndex>().awaitReady(5_000)) { "file index did not finish" }
                state.searchVisible = true
            }
        }
    }

    @Test
    @Order(8)
    fun `find bar frame time`() {
        val root = demoProject()
        measure("08-find-bar.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                expand(session, root, "src", "main", "kotlin", "demo")
                openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
                val editor = openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
                state.findBar.open(editor, replace = true)
                state.findBar.query = "name"
                state.findBar.refresh(editor)
            }
        }
    }

    @Test
    @Order(9)
    fun `recent files frame time`() {
        val root = demoProject()
        measure("09-recent-files.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                expand(session, root, "src", "main", "kotlin", "demo")
                openEditor(session, root.resolve("src/main/kotlin/demo/Main.kt"))
                openEditor(session, root.resolve("src/main/kotlin/demo/Greeting.kt"))
                openEditor(session, root.resolve("docs/notes.md"))
                state.recentFilesVisible = true
            }
        }
    }

    @Test
    @Order(10)
    fun `terminal frame time`() {
        val root = demoProject()
        measure("10-terminal.png", renderMillis = 800) {
            AppState().also { state ->
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
            }
        }
    }

    @Test
    @Order(11)
    fun `run window frame time`() {
        val root = demoProject()
        measure("11-run-window.png", renderMillis = 800) {
            AppState().also { state ->
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
            }
        }
    }

    @Test
    @Order(12)
    fun `editor scrolled frame time`() {
        val root = temp.resolve("scroll-project")
        Files.createDirectories(root)
        val content = (1..120).joinToString("\n") { line ->
            if (line == 100) "y".repeat(300) else "line $line"
        }
        Files.writeString(root.resolve("Long.kt"), content)
        measure("12-editor-scrolled.png", renderMillis = 800) {
            AppState().also { state ->
                state.openFolder(root)
                val session = requireNotNull(state.session)
                val editor = openEditor(session, root.resolve("Long.kt"))
                editor.gotoLine("100:290")
            }
        }
    }

    @Test
    @Order(13)
    fun `frame times stay under the baseline`() {
        assertTrue(measured.size == SNAPSHOTS.size, "measured ${measured.size} of ${SNAPSHOTS.size} scenarios")
        val baseline = loadBaseline()
        val problems = mutableListOf<String>()
        if (baseline == null) problems += "baseline resource $BASELINE_RESOURCE is missing"
        for (name in SNAPSHOTS) {
            val value = requireNotNull(measured[name]) { "$name was not measured" }
            val base = baseline?.get(name)
            if (base == null) {
                problems += "$name: no baseline"
                continue
            }
            val factor = if (name in POPUPS) 2.0 else 1.5
            val limit = base * factor + L0_MESH_BUDGET_MS
            if (value > limit) {
                problems += (
                    "$name: measured %.3f ms > limit %.3f ms " +
                        "(baseline %.3f × %.1f + %.1f ms L0 budget)"
                    ).format(Locale.ROOT, value, limit, base, factor, L0_MESH_BUDGET_MS)
            }
        }
        if (problems.isNotEmpty()) {
            val all = SNAPSHOTS.map { "$it=%.3f".format(Locale.ROOT, measured[it] ?: -1.0) }.joinToString(", ")
            throw AssertionError("frame timing limits failed [${problems.joinToString("; ")}]; measured: $all")
        }
    }

    private fun loadBaseline(): Map<String, Double>? {
        val stream = javaClass.classLoader.getResourceAsStream(BASELINE_RESOURCE) ?: return null
        return stream.use {
            val properties = Properties()
            properties.load(it)
            SNAPSHOTS.associateWith { name -> properties.getProperty(name)?.toDoubleOrNull() }
                .filterValues { it != null }
                .mapValues { entry -> requireNotNull(entry.value) }
        }
    }

    private fun measure(
        name: String,
        renderMillis: Long,
        initialQuery: String = "",
        build: () -> AppState,
    ) {
        val state = build()
        var scene: ImageComposeScene? = null
        try {
            scene = ImageComposeScene(
                width = 2560,
                height = 1640,
                density = Density(2f),
            ) {
                CompositionLocalProvider(
                    LocalWordmarkCaretBlink provides false,
                    LocalAnimationsEnabled provides false,
                ) {
                    App(state, onOpenFolder = {}, searchInitialQuery = initialQuery)
                }
            }
            val start = System.nanoTime()
            var nanos = 0L
            while ((System.nanoTime() - start) / 1_000_000 < renderMillis) {
                scene.render(nanos).close()
                nanos += 16_000_000L
                Thread.sleep(16)
            }
            val p50 = SnapshotRenderer.renderFrameMillis(scene, startNanos = nanos)
            measured[name] = p50
            System.err.println("frame-timing name=$name p50=%.3f".format(Locale.ROOT, p50))
        } finally {
            scene?.close()
            state.dispose()
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

    companion object {
        private const val BASELINE_RESOURCE = "stride/ui/frame-baseline.properties"

        /**
         * The baselines were measured on a solid background, so they cost effectively 0 ms. One
         * opaque full-screen mesh image at 2560×1640 costs 0.948 ms to draw in the software-raster
         * harness (G2 probe), so a factor alone could never admit any full-screen layer.
         */
        private const val L0_MESH_BUDGET_MS = 1.0

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

        private val POPUPS = setOf(
            "04-search.png",
            "05-search-empty-results.png",
            "07-selection-popup.png",
            "09-recent-files.png",
        )

        private val measured = linkedMapOf<String, Double>()

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(SnapshotFrameTimingTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
