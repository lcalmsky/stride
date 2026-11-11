package stride.app.keys

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.run.ContextActionKind
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.SearchTab
import java.nio.file.Files
import java.nio.file.Path

class ActionDispatcherTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @Test
    fun `undo and redo are consumed in the editor and duplicate needs an editor`() {
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.UNDO, state) { null })
        assertTrue(dispatchAction(ActionId.REDO, state) { null })
        assertFalse(dispatchAction(ActionId.EDITOR_DUPLICATE, state) { null })

        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo", TextRange(1)))

        assertTrue(dispatchAction(ActionId.EDITOR_DUPLICATE, state) { null })
        assertEquals("one\none\ntwo", editor.value.text)

        assertTrue(dispatchAction(ActionId.UNDO, state) { null })
        assertEquals("one\ntwo", editor.value.text)
    }

    @Test
    fun `comment line uses the language commenter`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("project")
        val session = openProjectWith("a.kt", "b.txt")

        val kotlin = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        kotlin.updateValue(TextFieldValue("val x = 1", TextRange(0)))
        assertTrue(dispatchAction(ActionId.COMMENT_LINE, state) { null })
        assertEquals("// val x = 1", kotlin.value.text)

        val text = session.editors.openFile(VFile(root.resolve("b.txt"))) as TextFileEditor
        assertTrue(dispatchAction(ActionId.COMMENT_LINE, state) { null })
        assertEquals("", text.value.text)
    }

    @Test
    fun `goto line opens the popup and escape closes it`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor

        assertTrue(dispatchAction(ActionId.GOTO_LINE, state) { null })
        assertTrue(editor.gotoLineVisible)

        assertTrue(dispatchAction(ActionId.EDITOR_ESCAPE, state) { null })
        assertFalse(editor.gotoLineVisible)
    }

    @Test
    fun `next tab wraps from the last tab to the first`() {
        val session = openProjectWith("a.kt", "b.kt", "c.kt")
        assertEquals("c.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.NEXT_TAB, state) { null })
        assertEquals("a.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.PREVIOUS_TAB, state) { null })
        assertEquals("c.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `activate run tool window opens then hides when already focused`() {
        state.focus = KeyContext.GLOBAL

        assertTrue(dispatchAction(ActionId.ACTIVATE_RUN_TOOL_WINDOW, state) { null })
        assertTrue(state.toolWindows.visible)
        assertEquals("run", state.toolWindows.activeId)

        state.focus = KeyContext.RUN_WINDOW
        assertTrue(dispatchAction(ActionId.ACTIVATE_RUN_TOOL_WINDOW, state) { null })
        assertFalse(state.toolWindows.visible)
    }

    @Test
    fun `hide active window hides the panel and resets the focus context`() {
        state.toolWindows.activate("run")
        state.focus = KeyContext.RUN_WINDOW

        assertTrue(dispatchAction(ActionId.HIDE_ACTIVE_WINDOW, state) { null })

        assertFalse(state.toolWindows.visible)
        assertEquals(KeyContext.GLOBAL, state.focus)
    }

    @Test
    fun `find without an editor is not consumed`() {
        openProjectWith()
        assertFalse(dispatchAction(ActionId.FIND, state) { null })
        assertFalse(dispatchAction(ActionId.REPLACE, state) { null })
        assertFalse(state.findBar.visible)

        val withoutSession = AppState()
        try {
            assertFalse(dispatchAction(ActionId.FIND, withoutSession) { null })
        } finally {
            withoutSession.dispose()
        }
    }

    @Test
    fun `go to class without a session opens the folder chooser`() {
        var chooserCalls = 0
        val root = temp.resolve("chosen")
        Files.createDirectories(root)

        assertTrue(dispatchAction(ActionId.GOTO_CLASS, state) {
            chooserCalls++
            root
        })

        assertEquals(1, chooserCalls)
        assertEquals(root.toAbsolutePath().normalize(), state.session?.project?.root)
        assertFalse(state.searchVisible)
    }

    @Test
    fun `the go to actions open search on their tab`() {
        openProjectWith()

        assertTrue(dispatchAction(ActionId.GOTO_CLASS, state) { null })
        assertTrue(state.searchVisible)
        assertEquals(SearchTab.CLASSES, state.searchTab)

        assertTrue(dispatchAction(ActionId.GOTO_FILE, state) { null })
        assertEquals(SearchTab.FILES, state.searchTab)

        assertTrue(dispatchAction(ActionId.GOTO_SYMBOL, state) { null })
        assertEquals(SearchTab.SYMBOLS, state.searchTab)

        assertTrue(dispatchAction(ActionId.GOTO_ACTION, state) { null })
        assertEquals(SearchTab.ACTIONS, state.searchTab)

        assertTrue(dispatchAction(ActionId.SEARCH_EVERYWHERE, state) { null })
        assertEquals(SearchTab.ALL, state.searchTab)
    }

    @Test
    fun `recent files without a session is consumed but opens nothing`() {
        assertTrue(dispatchAction(ActionId.RECENT_FILES, state) { null })
        assertFalse(state.recentFilesVisible)
        assertTrue(dispatchAction(ActionId.FIND_IN_PATH, state) { null })
        assertFalse(state.findInPathVisible)
    }

    @Test
    fun `context actions need an editor and report when nothing applies`() {
        state.focus = KeyContext.EDITOR
        assertFalse(dispatchAction(ActionId.SHOW_INTENTION_ACTIONS, state) { null })

        val session = openProjectWith("notes.txt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("plain text", TextRange(0)))

        assertTrue(dispatchAction(ActionId.SHOW_INTENTION_ACTIONS, state) { null })
        assertEquals("No context actions here", state.statusMessage)
        assertFalse(state.contextActionsVisible)
    }

    @Test
    fun `context actions open with the main first under the caret`() {
        val root = temp.resolve("context-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(root.resolve("src/main/kotlin/demo/Main.kt"), "package demo\n\nfun main() {\n    println(1)\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))

        assertTrue(dispatchAction(ActionId.SHOW_INTENTION_ACTIONS, state) { null })

        assertTrue(state.contextActionsVisible)
        assertEquals(ContextActionKind.RUN_MAIN, state.contextActions.first().kind)
    }

    @Test
    fun `run context falls back from the test to the file main`() {
        val root = temp.resolve("main-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(root.resolve("src/main/kotlin/demo/Main.kt"), "package demo\n\nfun main() {\n    println(1)\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))

        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })

        val configuration = requireNotNull(manager.lastRun)
        assertEquals(RunKind.APPLICATION, configuration.kind)
        assertEquals(
            listOf(":strideRunMain", "-Pstride.mainClass=demo.MainKt", "-Pstride.sourceSet=main"),
            configuration.args,
        )
        assertTrue(state.toolWindows.visible)
    }

    @Test
    fun `run without a previous run behaves like run context and reports nothing to run`() {
        val session = openProjectWith("notes.txt")
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

        assertTrue(dispatchAction(ActionId.RUN, state) { null })
        assertEquals("Nothing to run here", state.statusMessage)

        val root = temp.resolve("plain-run-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(root.resolve("src/main/kotlin/demo/Main.kt"), "package demo\n\nfun main() {\n}\n")
        state.openFolder(root)
        val mainEditor = requireNotNull(state.session).editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        mainEditor.updateValue(TextFieldValue(mainEditor.value.text, TextRange(mainEditor.value.text.indexOf("fun main"))))
        val mainManager = requireNotNull(state.session).project.service<RunManager>()
        mainManager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

        assertTrue(dispatchAction(ActionId.RUN, state) { null })
        assertEquals(RunKind.APPLICATION, mainManager.lastRun?.kind)
        assertNull(state.statusMessage)
    }

    @Test
    fun `build project runs classes and test classes as a task`() {
        val session = openProjectWith()
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

        assertTrue(dispatchAction(ActionId.BUILD_PROJECT, state) { null })

        val configuration = requireNotNull(manager.lastRun)
        assertEquals("Build", configuration.name)
        assertEquals(listOf("classes", "testClasses"), configuration.args)
        assertEquals(RunKind.TASK, configuration.kind)
        assertTrue(state.toolWindows.visible)
    }

    @Test
    fun `choose run configuration lists the file entries then the recent runs`() {
        val root = temp.resolve("choose-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(root.resolve("src/main/kotlin/demo/Main.kt"), "package demo\n\nfun main() {\n    println(1)\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
        manager.run(
            RunConfiguration(
                name = "previous",
                workingDir = root,
                args = listOf(":test"),
                kind = RunKind.TEST,
            ),
        )
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))

        assertTrue(dispatchAction(ActionId.CHOOSE_RUN_CONFIGURATION, state) { null })

        assertTrue(state.runConfigurationsVisible)
        assertEquals(listOf("MainKt", "previous"), state.runConfigurationChoices.map { it.name })
    }

    @Test
    fun `a kotlin main outside a source set reports the reason`() {
        val root = temp.resolve("outside-source-set")
        Files.createDirectories(root.resolve("playground"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val file = root.resolve("playground/Main.kt")
        Files.writeString(file, "package demo\n\nfun main() {\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("fun main"))))

        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })

        assertEquals("Cannot run Main.kt: not in a source set", state.statusMessage)
        assertNull(session.project.service<RunManager>().lastRun)
    }

    @Test
    fun `a java main in a later top-level class is not run directly`() {
        val root = temp.resolve("java-second-class")
        Files.createDirectories(root)
        val file = root.resolve("App.java")
        Files.writeString(
            file,
            "class Helper {\n}\n\nclass App {\n    public static void main(String[] args) {\n    }\n}\n",
        )
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("main"))))

        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })

        assertEquals("Cannot run App.java: the main class is not the first top-level class", state.statusMessage)
        assertNull(session.project.service<RunManager>().lastRun)
    }

    @Test
    fun `a direct script run starts in the script's directory`() {
        val root = temp.resolve("script-project")
        Files.createDirectories(root.resolve("scripts"))
        Files.writeString(root.resolve("scripts/report.py"), "print('hi')\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
        session.editors.openFile(VFile(root.resolve("scripts/report.py")))

        assertTrue(dispatchAction(ActionId.RUN, state) { null })

        val configuration = requireNotNull(manager.lastRun)
        assertEquals(root.resolve("scripts").toAbsolutePath().normalize(), configuration.workingDir.toAbsolutePath().normalize())
        assertEquals(RunKind.APPLICATION, configuration.kind)
    }

    private fun openProjectWith(vararg names: String): ProjectSession {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        state.openFolder(root)
        val session = requireNotNull(state.session)
        for (name in names) {
            Files.writeString(root.resolve(name), "")
            session.editors.openFile(VFile(root.resolve(name)))
        }
        return session
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ActionDispatcherTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
