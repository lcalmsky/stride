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
import stride.app.editor.TextClipboard
import stride.app.editor.TextFileEditor
import stride.app.editor.applyPasteHistory
import stride.app.editor.applyRename
import stride.app.editor.find.FindOptions
import stride.app.navigation.openBookmark
import stride.app.navigation.openDeclaration
import stride.app.navigation.openFileAtColumn
import stride.app.navigation.openFileAtOffset
import stride.app.navigation.openUsagesFindWindow
import stride.app.problems.FileProblem
import stride.app.problems.ProblemScope
import stride.app.problems.ProblemSource
import stride.app.run.ContextActionKind
import stride.app.run.FakeProcess
import stride.app.run.applyContextAction
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.search.FindTabKind
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ProblemSeverity
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
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
    fun `unwrap and reformat report their result in the status bar`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("val a = 1", TextRange(0)))

        assertTrue(dispatchAction(ActionId.UNWRAP, state) { null })
        assertEquals("Nothing to unwrap", state.statusMessage)
        assertTrue(dispatchAction(ActionId.REFORMAT_CODE, state) { null })
        assertEquals("Nothing to reformat", state.statusMessage)

        editor.updateValue(TextFieldValue("class A {\n  fun f() {}\n}", TextRange(0)))
        assertTrue(dispatchAction(ActionId.REFORMAT_CODE, state) { null })
        assertEquals("Reformatted 1 lines", state.statusMessage)
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
    fun `the folding actions reach the selected editor`() {
        state.focus = KeyContext.EDITOR
        assertFalse(dispatchAction(ActionId.COLLAPSE_REGION, state) { null })

        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("fun f() {\n  val a = 1\n}\n", TextRange(0)))

        assertTrue(dispatchAction(ActionId.COLLAPSE_REGION, state) { null })
        assertEquals(1, editor.foldedRegions.size)
        assertTrue(dispatchAction(ActionId.EXPAND_REGION, state) { null })
        assertTrue(editor.foldedRegions.isEmpty())
        assertTrue(dispatchAction(ActionId.COLLAPSE_ALL_REGIONS, state) { null })
        assertEquals(1, editor.foldedRegions.size)
        assertTrue(dispatchAction(ActionId.EXPAND_ALL_REGIONS, state) { null })
        assertTrue(editor.foldedRegions.isEmpty())
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
    fun `replace in path opens the popup in replace mode`() {
        openProjectWith("a.kt")
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.REPLACE_IN_PATH, state) { null })

        assertTrue(state.findInPathVisible)
        assertTrue(state.findInPathReplace)
        assertEquals(KeyContext.SEARCH_POPUP, state.focus)

        state.closeSession()
        assertFalse(state.findInPathReplace)

        val withoutSession = AppState()
        try {
            assertTrue(dispatchAction(ActionId.REPLACE_IN_PATH, withoutSession) { null })
            assertFalse(withoutSession.findInPathVisible)
        } finally {
            withoutSession.dispose()
        }
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
    fun `context actions import an unresolved project symbol and undo restores the text`() {
        val root = temp.resolve("intention-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo/time"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(root.resolve("src/main/kotlin/demo/time/Clock.kt"), "package demo.time\n\nclass Clock\n")
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            "package demo\n\nfun main() {\n    Clock()\n}\n",
        )
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        val text = editor.value.text
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("Clock") + "Clock".length)))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.SHOW_INTENTION_ACTIONS, state) { null })

        assertTrue(state.contextActionsVisible)
        val import = state.contextActions.first()
        assertEquals(ContextActionKind.QUICK_FIX, import.kind)
        assertEquals("Import 'demo.time.Clock'", import.label)
        assertTrue(state.contextActions.any { it.kind == ContextActionKind.RUN_MAIN })

        val original = editor.value
        assertTrue(applyContextAction(state, import))
        assertEquals("package demo\n\nimport demo.time.Clock\n\nfun main() {\n    Clock()\n}\n", editor.value.text)
        assertNull(state.statusMessage)

        assertTrue(editor.undo())
        assertEquals(original.text, editor.value.text)
        assertEquals(original.selection, editor.value.selection)
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

        assertEquals("Cannot run App.java: the main class is not the first top-level type", state.statusMessage)
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

    @Test
    fun `editor actions without a selected editor are not consumed`() {
        assertFalse(dispatchAction(ActionId.EDITOR_JOIN_LINES, state) { null })
        assertFalse(dispatchAction(ActionId.EDITOR_SELECT_WORD, state) { null })
        assertFalse(dispatchAction(ActionId.CODE_COMPLETION, state) { null })
        assertFalse(dispatchAction(ActionId.RENAME_ELEMENT, state) { null })
        assertFalse(dispatchAction(ActionId.HIPPIE_COMPLETION, state) { null })
    }

    @Test
    fun `hippie completion falls back to the other open editors`() {
        val session = openProjectWith("a.kt", "b.kt")
        val root = temp.resolve("project")
        val other = session.editors.openFile(VFile(root.resolve("b.kt"))) as TextFileEditor
        other.updateValue(TextFieldValue("val validator", TextRange(13)))
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("val x = val", TextRange(11)))

        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.HIPPIE_COMPLETION, state) { null })

        assertEquals("val x = validator", editor.value.text)
    }

    @Test
    fun `code completion opens on the prefix before the caret`() {
        val root = temp.resolve("completion-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun main() { println(1) }\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        val caret = editor.value.text.indexOf("main") + 2
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))

        assertTrue(dispatchAction(ActionId.CODE_COMPLETION, state) { null })

        val request = requireNotNull(state.completion)
        assertEquals("ma", request.prefix)
        assertEquals(caret - 2, request.prefixStart)
        assertTrue("main" in request.candidates())
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
    }

    @Test
    fun `code completion leaves out the identifier being completed`() {
        val root = temp.resolve("completion-exclude-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "val foo = main + ma\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        val caret = editor.value.text.lastIndexOf("ma") + 2
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))

        assertTrue(dispatchAction(ActionId.CODE_COMPLETION, state) { null })

        val names = requireNotNull(state.completion).candidates()
        assertFalse("ma" in names)
        assertTrue("main" in names)
    }

    @Test
    fun `code completion and rename refuse a read-only editor`() {
        val root = temp.resolve("read-only-project")
        Files.createDirectories(root)
        state.openFolder(root)
        val session = requireNotNull(state.session)
        // A missing file fails to read and opens forced read-only.
        val editor = session.editors.openFile(VFile(root.resolve("missing.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo = foo", TextRange(0, 3)))
        assertTrue(editor.isReadOnly)

        assertTrue(dispatchAction(ActionId.CODE_COMPLETION, state) { null })
        assertNull(state.completion)
        assertEquals("File is read-only", state.statusMessage)

        assertTrue(dispatchAction(ActionId.RENAME_ELEMENT, state) { null })
        assertNull(state.rename)
        assertEquals("File is read-only", state.statusMessage)
    }

    @Test
    fun `rename opens on the identifier and reports the count`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo = foo", TextRange(0)))

        assertTrue(dispatchAction(ActionId.RENAME_ELEMENT, state) { null })

        val request = requireNotNull(state.rename)
        assertEquals("foo", request.original)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertEquals(2, applyRename(state, request, "bar"))
        assertEquals("bar = bar", editor.value.text)
        assertEquals("Renamed 2 occurrences", state.statusMessage)
    }

    @Test
    fun `renaming to the same name changes nothing`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo = foo", TextRange(0)))

        assertTrue(dispatchAction(ActionId.RENAME_ELEMENT, state) { null })

        val request = requireNotNull(state.rename)
        assertEquals(0, applyRename(state, request, "foo"))
        assertEquals("foo = foo", editor.value.text)
        assertEquals("Nothing to rename", state.statusMessage)
    }

    @Test
    fun `rename without an identifier reports it and opens nothing`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("a + b", TextRange(2)))

        assertTrue(dispatchAction(ActionId.RENAME_ELEMENT, state) { null })

        assertNull(state.rename)
        assertEquals("No identifier at caret", state.statusMessage)
    }

    @Test
    fun `go to declaration jumps to the declaration in the same file`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("declaration-local")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "package demo\n\nfun helper() {}\n\nfun main() {\n    helper()\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        val caret = editor.value.text.lastIndexOf("helper()") + 1
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))

        assertTrue(dispatchAction(ActionId.GOTO_DECLARATION, state) { null })

        val line = editor.value.text.take(editor.value.selection.start).count { it == '\n' }
        assertEquals(2, line)
        assertNull(state.statusMessage)
    }

    @Test
    fun `go to declaration finds the declaration in another file`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("declaration-index")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("src/main/kotlin/demo/Helper.kt"), "package demo\n\nobject Helper\n")
        Files.writeString(root.resolve("src/main/kotlin/demo/Main.kt"), "package demo\n\nfun main() {\n    Helper\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val main = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        main.updateValue(TextFieldValue(main.value.text, TextRange(main.value.text.indexOf("Helper") + 1)))

        assertTrue(dispatchAction(ActionId.GOTO_DECLARATION, state) { null })

        assertEquals("Helper.kt", session.editors.selected?.file?.name)
        val target = session.editors.selected as TextFileEditor
        assertEquals(2, target.value.text.take(target.value.selection.start).count { it == '\n' })
    }

    @Test
    fun `several index declarations open a chooser`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("declaration-chooser")
        Files.createDirectories(root.resolve("src/main/kotlin/a"))
        Files.createDirectories(root.resolve("src/main/kotlin/b"))
        Files.createDirectories(root.resolve("src/main/kotlin/c"))
        Files.writeString(root.resolve("src/main/kotlin/a/Alpha.kt"), "package a\n\nfun helper() {}\n")
        Files.writeString(root.resolve("src/main/kotlin/b/Beta.kt"), "package b\n\nfun helper() {}\n")
        Files.writeString(root.resolve("src/main/kotlin/c/Use.kt"), "package c\n\nfun use() { helper() }\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val use = session.editors.openFile(VFile(root.resolve("src/main/kotlin/c/Use.kt"))) as TextFileEditor
        use.updateValue(TextFieldValue(use.value.text, TextRange(use.value.text.indexOf("helper()") + 1)))

        assertTrue(dispatchAction(ActionId.GOTO_DECLARATION, state) { null })

        assertTrue(state.declarationChooserVisible)
        assertEquals(
            listOf("src/main/kotlin/a/Alpha.kt", "src/main/kotlin/b/Beta.kt"),
            state.declarationChoices.map { it.relativePath },
        )
        openDeclaration(state, session, state.declarationChoices.first())
        assertEquals("Alpha.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `go to declaration reports when nothing matches`() {
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("declaration-missing")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun main() {\n    missing()\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        // The definitive message is only reported once the initial scan has finished.
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("missing") + 1)))

        assertTrue(dispatchAction(ActionId.GOTO_DECLARATION, state) { null })

        assertEquals("Cannot find declaration to go to", state.statusMessage)
        assertFalse(state.declarationChooserVisible)
    }

    @Test
    fun `back and forward move between the visited files`() {
        val session = openProjectWith("a.kt", "b.kt")
        val root = temp.resolve("project")

        assertTrue(dispatchAction(ActionId.BACK, state) { null })
        assertEquals("a.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.FORWARD, state) { null })
        assertEquals("b.kt", session.editors.selected?.file?.name)
        assertEquals(root.resolve("b.kt").toAbsolutePath().normalize(), session.editors.selected?.file?.path)
    }

    @Test
    fun `back to a deleted file drops the entry and reports it`() {
        val session = openProjectWith("a.kt", "b.kt")
        val root = temp.resolve("project")
        Files.delete(root.resolve("a.kt"))

        assertTrue(dispatchAction(ActionId.BACK, state) { null })

        assertEquals("File not found: a.kt", state.statusMessage)
        assertEquals("b.kt", session.editors.selected?.file?.name)
        assertEquals(1, session.history.size)
    }

    @Test
    fun `forward to a deleted file drops the entry and reports it`() {
        val session = openProjectWith("a.kt", "b.kt", "c.kt")
        val root = temp.resolve("project")
        assertTrue(dispatchAction(ActionId.BACK, state) { null })
        assertTrue(dispatchAction(ActionId.BACK, state) { null })
        Files.delete(root.resolve("b.kt"))

        assertTrue(dispatchAction(ActionId.FORWARD, state) { null })

        assertEquals("File not found: b.kt", state.statusMessage)
        assertEquals("a.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.FORWARD, state) { null })
        assertEquals("c.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `a bookmark whose file is gone is dropped with a message`() {
        val session = openProjectWith("a.kt", "b.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one\n", TextRange(0)))
        assertTrue(dispatchAction(ActionId.TOGGLE_BOOKMARK, state) { null })
        session.editors.openFile(VFile(root.resolve("b.kt")))
        val tabs = session.editors.openFiles.map { it.file.name }
        Files.delete(root.resolve("a.kt"))

        openBookmark(state, session.bookmarks.entries.single())

        assertEquals("File not found: a.kt", state.statusMessage)
        assertTrue(session.bookmarks.isEmpty())
        assertEquals("b.kt", session.editors.selected?.file?.name)
        assertEquals(tabs, session.editors.openFiles.map { it.file.name })
    }

    @Test
    fun `go to test opens the counterpart and back to the subject`() {
        val root = temp.resolve("test-navigation")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Foo.kt"), "class Foo\n")
        Files.writeString(root.resolve("FooTest.kt"), "class FooTest\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000))
        session.editors.openFile(VFile(root.resolve("Foo.kt")))

        assertTrue(dispatchAction(ActionId.GOTO_TEST, state) { null })
        assertEquals("FooTest.kt", session.editors.selected?.file?.name)

        assertTrue(dispatchAction(ActionId.GOTO_TEST, state) { null })
        assertEquals("Foo.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `go to test chooses between several candidates and reports none`() {
        val root = temp.resolve("test-choices")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Foo.kt"), "class Foo\n")
        Files.writeString(root.resolve("FooTest.kt"), "class FooTest\n")
        Files.writeString(root.resolve("FooTests.kt"), "class FooTests\n")
        Files.writeString(root.resolve("Bar.kt"), "class Bar\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000))
        session.editors.openFile(VFile(root.resolve("Foo.kt")))

        assertTrue(dispatchAction(ActionId.GOTO_TEST, state) { null })
        assertTrue(state.testChooserVisible)
        assertEquals(listOf("FooTest.kt", "FooTests.kt"), state.testChoices.map { it.name })

        state.testChooserVisible = false
        session.editors.openFile(VFile(root.resolve("Bar.kt")))
        assertTrue(dispatchAction(ActionId.GOTO_TEST, state) { null })
        assertEquals("No test found for Bar", state.statusMessage)
    }

    @Test
    fun `toggling a bookmark marks the caret line and show bookmarks opens the popup`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo\n", TextRange(4)))

        assertTrue(dispatchAction(ActionId.TOGGLE_BOOKMARK, state) { null })
        assertEquals(listOf(1), session.bookmarks.entries.map { it.line })
        assertEquals(setOf(1), editor.bookmarkLines)

        assertTrue(dispatchAction(ActionId.SHOW_BOOKMARKS, state) { null })
        assertTrue(state.bookmarksVisible)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

        assertTrue(dispatchAction(ActionId.TOGGLE_BOOKMARK, state) { null })
        assertTrue(session.bookmarks.isEmpty())
        assertTrue(editor.bookmarkLines.isEmpty())
    }

    @Test
    fun `hide all windows hides the tree and the tool window and restores them`() {
        openProjectWith()
        state.toolWindows.activate("run")
        state.focus = KeyContext.RUN_WINDOW

        assertTrue(dispatchAction(ActionId.HIDE_ALL_WINDOWS, state) { null })
        assertFalse(state.projectTreeVisible)
        assertFalse(state.toolWindows.visible)

        assertTrue(dispatchAction(ActionId.HIDE_ALL_WINDOWS, state) { null })
        assertTrue(state.projectTreeVisible)
        assertTrue(state.toolWindows.visible)
    }

    @Test
    fun `hide all windows hands the keyboard back to the selected editor`() {
        openProjectWith("a.kt")
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.HIDE_ALL_WINDOWS, state) { null })

        assertFalse(state.projectTreeVisible)
        assertEquals(KeyContext.EDITOR, state.focus)
        assertEquals(1, state.editorFocusRequests)
    }

    @Test
    fun `hide all windows with nothing visible shows nothing`() {
        openProjectWith("a.kt")
        state.projectTreeVisible = false

        assertTrue(dispatchAction(ActionId.HIDE_ALL_WINDOWS, state) { null })

        assertFalse(state.projectTreeVisible)
        assertFalse(state.toolWindows.visible)
    }

    @Test
    fun `hiding the project tree hands the keyboard back to the editor`() {
        openProjectWith("a.kt")
        state.focus = KeyContext.GLOBAL

        assertTrue(dispatchAction(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, state) { null })

        assertFalse(state.projectTreeVisible)
        assertEquals(KeyContext.EDITOR, state.focus)
        assertEquals(1, state.editorFocusRequests)
    }

    @Test
    fun `hide active window hands the keyboard back to the editor`() {
        openProjectWith("a.kt")
        state.toolWindows.activate("run")
        state.focus = KeyContext.RUN_WINDOW

        assertTrue(dispatchAction(ActionId.HIDE_ACTIVE_WINDOW, state) { null })

        assertFalse(state.toolWindows.visible)
        assertEquals(KeyContext.EDITOR, state.focus)
        assertEquals(1, state.editorFocusRequests)
    }

    @Test
    fun `the project window action toggles the tree`() {
        openProjectWith()
        assertTrue(state.projectTreeVisible)

        assertTrue(dispatchAction(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, state) { null })
        assertFalse(state.projectTreeVisible)

        assertTrue(dispatchAction(ActionId.ACTIVATE_PROJECT_TOOL_WINDOW, state) { null })
        assertTrue(state.projectTreeVisible)
        assertEquals(1, state.treeFocusRequests)
    }

    @Test
    fun `copy path and copy reference write the expected text`() {
        val clipboard = RecordingClipboard()
        state.clipboard = clipboard
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo\n", TextRange(4)))
        val absolute = root.resolve("a.kt").toAbsolutePath().normalize().toString()

        assertTrue(dispatchAction(ActionId.COPY_PATHS, state) { null })
        assertEquals(absolute, clipboard.text)
        assertEquals("Copied $absolute", state.statusMessage)

        assertTrue(dispatchAction(ActionId.COPY_REFERENCE, state) { null })
        assertEquals("a.kt:2", clipboard.text)
    }

    @Test
    fun `select in project view expands the tree to the file`() {
        val root = temp.resolve("reveal-project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Alpha.kt"), "")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        session.editors.openFile(VFile(root.resolve("src/Alpha.kt")))
        assertFalse(session.tree.rows().any { it.file.name == "Alpha.kt" })

        assertTrue(dispatchAction(ActionId.SELECT_IN_PROJECT_VIEW, state) { null })

        assertTrue(state.projectTreeVisible)
        assertTrue(session.tree.rows().any { it.file.name == "Alpha.kt" })
        assertEquals(1, state.revealRequests)
    }

    @Test
    fun `file structure opens for the selected file`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("class A {\n    fun a() {}\n}\n", TextRange(0)))

        assertTrue(dispatchAction(ActionId.FILE_STRUCTURE_POPUP, state) { null })

        assertTrue(state.fileStructureVisible)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
    }

    @Test
    fun `find usages adds a whole word case sensitive tab and shows the find window`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(
            TextFieldValue(
                "fun helper() {}\n\nfun use() { helper() }\n",
                TextRange("fun helper() {}\n\nfun use() { helper() }\n".lastIndexOf("helper") + 1),
            ),
        )

        assertTrue(dispatchAction(ActionId.FIND_USAGES, state) { null })

        val tab = state.findToolWindow.tabs.single()
        assertEquals("Usages of helper", tab.title)
        assertEquals(FindTabKind.USAGES, tab.kind)
        assertEquals("helper", tab.query)
        assertEquals(FindOptions(caseSensitive = true, wholeWord = true), tab.options)
        assertTrue(state.toolWindows.visible)
        assertEquals("find", state.toolWindows.activeId)
        assertFalse(state.findInPathVisible)
    }

    @Test
    fun `find usages without an identifier is not consumed and adds no tab`() {
        state.focus = KeyContext.EDITOR
        assertFalse(dispatchAction(ActionId.FIND_USAGES, state) { null })

        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("a + b", TextRange(2)))

        assertFalse(dispatchAction(ActionId.FIND_USAGES, state) { null })
        assertTrue(state.findToolWindow.tabs.isEmpty())
    }

    @Test
    fun `the list tool window keys open focus and hide through one rule`() {
        openProjectWith("a.kt")
        state.focus = KeyContext.GLOBAL

        val windows = listOf(
            ActionId.ACTIVATE_BOOKMARKS_TOOL_WINDOW to "bookmarks",
            ActionId.ACTIVATE_FIND_TOOL_WINDOW to "find",
            ActionId.ACTIVATE_STRUCTURE_TOOL_WINDOW to "structure",
            ActionId.ACTIVATE_PROBLEMS_TOOL_WINDOW to "problems",
        )
        for ((action, id) in windows) {
            assertTrue(dispatchAction(action, state) { null })
            assertTrue(state.toolWindows.visible)
            assertEquals(id, state.toolWindows.activeId)

            state.focus = KeyContext.EDITOR
            assertTrue(dispatchAction(action, state) { null })
            assertTrue(state.toolWindows.visible)
            assertEquals(id, state.toolWindows.activeId)

            state.focus = KeyContext.LIST_WINDOW
            assertTrue(dispatchAction(action, state) { null })
            assertFalse(state.toolWindows.visible)
            assertEquals(KeyContext.EDITOR, state.focus)
        }
        assertEquals(4, state.editorFocusRequests)
    }

    @Test
    fun `the tool windows register in key number order`() {
        assertEquals(
            listOf("bookmarks", "find", "run", "problems", "structure", "terminal"),
            state.toolWindows.windows.map { it.id },
        )
        assertEquals(
            listOf("Bookmarks", "Find", "Run", "Problems", "Structure", "Terminal"),
            state.toolWindows.windows.map { it.title },
        )
        assertEquals(KeyContext.LIST_WINDOW, state.toolWindows.windows[0].context)
        assertEquals(KeyContext.LIST_WINDOW, state.toolWindows.windows[1].context)
        assertEquals(KeyContext.RUN_WINDOW, state.toolWindows.windows[2].context)
        assertEquals(KeyContext.LIST_WINDOW, state.toolWindows.windows[3].context)
        assertEquals(KeyContext.LIST_WINDOW, state.toolWindows.windows[4].context)
        assertEquals(KeyContext.TERMINAL, state.toolWindows.windows[5].context)
    }

    @Test
    fun `next and previous error move the caret onto the problems`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo\nthree\n", TextRange(1)))
        editor.problems = listOf(
            FileProblem(ProblemSeverity.ERROR, "first", ProblemSource.EDITOR, 0, 0, 0, 3),
            FileProblem(ProblemSeverity.ERROR, "second", ProblemSource.BUILD, 1, 0, 4, 7),
            FileProblem(ProblemSeverity.ERROR, "third", ProblemSource.BUILD, 2, 0, 8, 13),
        )

        editor.updateValue(TextFieldValue(editor.value.text, TextRange(5)))
        assertTrue(dispatchAction(ActionId.GOTO_PREVIOUS_ERROR, state) { null })
        assertEquals(4, editor.value.selection.start)
        assertNull(state.statusMessage)

        editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
        assertTrue(dispatchAction(ActionId.GOTO_PREVIOUS_ERROR, state) { null })
        assertEquals(8, editor.value.selection.start)

        editor.updateValue(TextFieldValue(editor.value.text, TextRange(9)))
        assertTrue(dispatchAction(ActionId.GOTO_NEXT_ERROR, state) { null })
        assertEquals(0, editor.value.selection.start)
    }

    @Test
    fun `opening a project error whose file is gone reports it and keeps the tabs`() {
        val session = openProjectWith("a.kt")
        val tabs = session.editors.openFiles.toList()
        val locations = session.history.locations()
        val missing = temp.resolve("project").resolve("gone.kt")

        openFileAtColumn(state, session, VFile(missing), 0, 0)

        assertEquals(tabs, session.editors.openFiles)
        assertEquals("File not found: gone.kt", state.statusMessage)
        assertEquals(locations, session.history.locations())
    }

    @Test
    fun `opening a file at an offset clamps the caret, records one entry and reports a gone file`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val target = root.resolve("b.kt")
        Files.writeString(target, "one\ntwo\n")
        val before = session.history.size

        assertTrue(openFileAtOffset(state, session, target, 5))
        val editor = session.editors.selected as TextFileEditor
        assertEquals(target, editor.file.path)
        assertEquals(5, editor.value.selection.start)
        assertEquals(KeyContext.EDITOR, state.focus)
        assertEquals(before + 1, session.history.size)
        assertEquals(5, session.history.current?.offset)

        assertTrue(openFileAtOffset(state, session, target, 999))
        assertEquals(editor.value.text.length, editor.value.selection.start)

        val tabs = session.editors.openFiles.toList()
        val locations = session.history.locations()
        assertFalse(openFileAtOffset(state, session, root.resolve("gone.kt"), 0))
        assertEquals("File not found: gone.kt", state.statusMessage)
        assertEquals(tabs, session.editors.openFiles)
        assertEquals(locations, session.history.locations())
    }

    @Test
    fun `error keys report when there is no editor or no problem`() {
        assertFalse(dispatchAction(ActionId.GOTO_NEXT_ERROR, state) { null })
        assertFalse(dispatchAction(ActionId.GOTO_PREVIOUS_ERROR, state) { null })
        assertFalse(dispatchAction(ActionId.SHOW_ERROR_DESCRIPTION, state) { null })

        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("one\n", TextRange(0)))

        assertTrue(dispatchAction(ActionId.GOTO_NEXT_ERROR, state) { null })
        assertEquals("No problems in this file", state.statusMessage)

        assertTrue(dispatchAction(ActionId.SHOW_ERROR_DESCRIPTION, state) { null })
        assertEquals("No problems at the caret", state.statusMessage)
        assertNull(state.errorDescription)
    }

    @Test
    fun `show error description opens on the problems at the caret`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(2)))
        val atCaret = FileProblem(ProblemSeverity.ERROR, "first", ProblemSource.BUILD, 0, 0, 0, 5)
        val elsewhere = FileProblem(ProblemSeverity.WARNING, "second", ProblemSource.EDITOR, 0, 6, 6, 10)
        editor.problems = listOf(atCaret, elsewhere)

        assertTrue(dispatchAction(ActionId.SHOW_ERROR_DESCRIPTION, state) { null })

        val request = requireNotNull(state.errorDescription)
        assertEquals(listOf(atCaret), request.problems)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertNull(state.statusMessage)
    }

    @Test
    fun `shift command bracket switches the problems range only while that window is focused`() {
        openProjectWith("a.kt")
        assertEquals(ProblemScope.FILE, state.problemsToolWindow.scope)
        state.toolWindows.activate("problems")
        state.focus = KeyContext.LIST_WINDOW

        assertTrue(dispatchAction(ActionId.NEXT_TAB, state) { null })
        assertEquals(ProblemScope.PROJECT_ERRORS, state.problemsToolWindow.scope)

        assertTrue(dispatchAction(ActionId.PREVIOUS_TAB, state) { null })
        assertEquals(ProblemScope.FILE, state.problemsToolWindow.scope)

        state.toolWindows.activate("find")
        assertTrue(dispatchAction(ActionId.NEXT_TAB, state) { null })
        assertEquals(ProblemScope.FILE, state.problemsToolWindow.scope)
    }

    @Test
    fun `close tab in the find window closes the find tab and keeps the editor tab`() {
        val session = openProjectWith("a.kt")
        state.openFindResults(FindTabKind.OCCURRENCES, "Occurrences of 'a'", "a", FindOptions())
        state.focus = KeyContext.LIST_WINDOW
        assertEquals("find", state.toolWindows.activeId)

        assertTrue(dispatchAction(ActionId.CLOSE_TAB, state) { null })

        assertTrue(state.findToolWindow.tabs.isEmpty())
        assertEquals(1, session.editors.openFiles.size)

        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.CLOSE_TAB, state) { null })
        assertTrue(session.editors.openFiles.isEmpty())
    }

    @Test
    fun `closing the session clears the find tabs`() {
        openProjectWith("a.kt")
        state.openFindResults(FindTabKind.OCCURRENCES, "Occurrences of 'a'", "a", FindOptions())
        assertEquals(1, state.findToolWindow.tabs.size)

        state.closeSession()

        assertTrue(state.findToolWindow.tabs.isEmpty())
        assertNull(state.findToolWindow.selectedTab)
    }

    @Test
    fun `reopen closed tab and close other tabs work on the open files`() {
        val session = openProjectWith("a.kt", "b.kt", "c.kt")
        val root = temp.resolve("project")
        session.editors.closeFile(VFile(root.resolve("c.kt")))

        assertTrue(dispatchAction(ActionId.REOPEN_CLOSED_TAB, state) { null })
        assertEquals("c.kt", session.editors.selected?.file?.name)

        session.editors.openFile(VFile(root.resolve("b.kt")))
        assertTrue(dispatchAction(ActionId.CLOSE_OTHER_TABS, state) { null })
        assertEquals(listOf("b.kt"), session.editors.openFiles.map { it.file.name })
    }

    @Test
    fun `the switcher opens the recent files popup`() {
        openProjectWith("a.kt", "b.kt")

        assertTrue(dispatchAction(ActionId.SWITCHER, state) { null })

        assertTrue(state.recentFilesVisible)
        assertEquals(KeyContext.SEARCH_POPUP, state.focus)
    }

    @Test
    fun `the occurrence actions dispatch to the selected editor`() {
        state.focus = KeyContext.EDITOR
        assertFalse(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })

        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo bar foo", TextRange(1)))

        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })
        assertEquals(TextRange(0, 3), editor.value.selection)

        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })
        assertEquals(2, editor.carets.size)

        assertTrue(dispatchAction(ActionId.UNSELECT_PREVIOUS_OCCURRENCE, state) { null })
        assertEquals(1, editor.carets.size)

        assertTrue(dispatchAction(ActionId.SELECT_ALL_OCCURRENCES, state) { null })
        assertEquals(2, editor.carets.size)

        assertTrue(dispatchAction(ActionId.EDITOR_CLONE_CARET_BELOW, state) { null })
        assertTrue(dispatchAction(ActionId.EDITOR_CLONE_CARET_ABOVE, state) { null })
        assertEquals(2, editor.carets.size)
    }

    @Test
    fun `select next occurrence reports when nothing is left`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo", TextRange(1)))

        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })
        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })

        assertEquals("No more occurrences", state.statusMessage)
    }

    @Test
    fun `a successful occurrence selection clears the previous failure message`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("foo", TextRange(1)))

        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })
        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })
        assertEquals("No more occurrences", state.statusMessage)

        editor.updateValue(TextFieldValue("foo foo", TextRange(1)))
        assertTrue(dispatchAction(ActionId.SELECT_NEXT_OCCURRENCE, state) { null })

        assertNull(state.statusMessage)
    }

    @Test
    fun `column selection mode reports its state`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        session.editors.openFile(VFile(root.resolve("a.kt")))

        assertTrue(dispatchAction(ActionId.EDITOR_TOGGLE_COLUMN_MODE, state) { null })
        assertEquals("Column selection mode on", state.statusMessage)

        assertTrue(dispatchAction(ActionId.EDITOR_TOGGLE_COLUMN_MODE, state) { null })
        assertEquals("Column selection mode off", state.statusMessage)
    }

    @Test
    fun `copy with several carets records every caret's text in the history`() {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("a.kt"), "")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val clipboard = RecordingClipboard()
        session.editors.clipboard = clipboard
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo", TextRange(0)))
        editor.toggleCaretAt(4)

        assertTrue(dispatchAction(ActionId.COPY_LINE, state) { null })

        assertEquals(listOf("one\ntwo"), state.clipboardHistory.items)
        assertEquals("one\ntwo", clipboard.text)
    }

    @Test
    fun `copy and cut record the clipboard text in the history`() {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("a.kt"), "")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val clipboard = RecordingClipboard()
        session.editors.clipboard = clipboard
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one\ntwo\n", TextRange(1)))

        assertTrue(dispatchAction(ActionId.COPY_LINE, state) { null })
        assertEquals(listOf("one\n"), state.clipboardHistory.items)
        assertEquals("one\n", clipboard.text)

        editor.updateValue(TextFieldValue(editor.value.text, TextRange(4)))
        assertTrue(dispatchAction(ActionId.CUT_LINE, state) { null })
        assertEquals(listOf("two\n", "one\n"), state.clipboardHistory.items)
        assertEquals("two\n", clipboard.text)
        assertEquals("one\n", editor.value.text)
    }

    @Test
    fun `copying a selection records it even though the key is left to the field`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("one two", TextRange(0, 3)))

        assertFalse(dispatchAction(ActionId.COPY_LINE, state) { null })

        assertEquals(listOf("one"), state.clipboardHistory.items)
    }

    @Test
    fun `paste from history replaces the selection and one undo restores it`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("alpha beta", TextRange(0, 5)))
        val clipboard = RecordingClipboard().apply { text = "gamma" }
        state.clipboard = clipboard
        state.clipboardHistory.record("delta")

        assertTrue(dispatchAction(ActionId.PASTE_MULTIPLE, state) { null })
        val request = requireNotNull(state.pasteHistory)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertEquals(listOf("gamma", "delta"), state.clipboardHistory.items)

        applyPasteHistory(state, request, "delta")
        assertEquals("delta beta", editor.value.text)
        assertEquals("delta", clipboard.text)
        assertEquals(listOf("delta", "gamma"), state.clipboardHistory.items)

        assertTrue(editor.undo())
        assertEquals("alpha beta", editor.value.text)
    }

    @Test
    fun `paste from history without anything to paste reports it`() {
        openProjectWith("a.kt")
        state.clipboard = RecordingClipboard()

        assertTrue(dispatchAction(ActionId.PASTE_MULTIPLE, state) { null })

        assertNull(state.pasteHistory)
        assertEquals("Clipboard history is empty", state.statusMessage)
    }

    @Test
    fun `paste from history refuses a read-only editor`() {
        val root = temp.resolve("read-only-paste-project")
        Files.createDirectories(root)
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(root.resolve("missing.kt"))) as TextFileEditor
        state.clipboardHistory.record("delta")

        assertTrue(dispatchAction(ActionId.PASTE_MULTIPLE, state) { null })

        assertNull(state.pasteHistory)
        assertEquals("File is read-only", state.statusMessage)
        assertTrue(editor.isReadOnly)
    }

    @Test
    fun `optimize imports reports the removed count and sorts the block`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(
            TextFieldValue(
                "package demo\n\nimport demo.b.Beta\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n",
                TextRange(0),
            ),
        )

        assertTrue(dispatchAction(ActionId.OPTIMIZE_IMPORTS, state) { null })

        assertEquals(
            "package demo\n\nimport demo.a.Alpha\n\nfun main() {\n    Alpha()\n}\n",
            editor.value.text,
        )
        assertEquals("Removed 1 import", state.statusMessage)
    }

    @Test
    fun `optimize imports tells sorting from no work`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(
            TextFieldValue(
                "package demo\n\nimport demo.b.Beta\nimport demo.a.Alpha\n\nfun main() { Alpha(); Beta() }\n",
                TextRange(0),
            ),
        )

        assertTrue(dispatchAction(ActionId.OPTIMIZE_IMPORTS, state) { null })
        assertEquals("Imports optimized", state.statusMessage)

        assertTrue(dispatchAction(ActionId.OPTIMIZE_IMPORTS, state) { null })
        assertEquals("Imports are already optimized", state.statusMessage)
    }

    @Test
    fun `optimize imports is unavailable without an optimizer and on a read-only file`() {
        val session = openProjectWith("a.txt")
        val root = temp.resolve("project")
        session.editors.openFile(VFile(root.resolve("a.txt")))

        assertTrue(dispatchAction(ActionId.OPTIMIZE_IMPORTS, state) { null })
        assertEquals("Optimize Imports is not available for this file", state.statusMessage)

        val readOnly = session.editors.openFile(VFile(root.resolve("missing.kt"))) as TextFileEditor
        assertTrue(readOnly.isReadOnly)
        assertTrue(dispatchAction(ActionId.OPTIMIZE_IMPORTS, state) { null })
        assertEquals("File is read-only", state.statusMessage)
    }

    @Test
    fun `smart completion keeps only the names the caret position can use`() {
        val root = temp.resolve("smart-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Clock.kt"), "package demo\n\nclass Clock\n")
        Files.writeString(root.resolve("Helpers.kt"), "package demo\n\nfun helper() {}\n")
        val main = root.resolve("Main.kt")
        Files.writeString(main, "package demo\n\nval time: Clo\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val editor = session.editors.openFile(VFile(main)) as TextFileEditor
        state.focus = KeyContext.EDITOR

        val typeText = "package demo\n\nval time: \n"
        editor.updateValue(TextFieldValue(typeText, TextRange(typeText.length)))
        assertTrue(dispatchAction(ActionId.SMART_TYPE_COMPLETION, state) { null })

        val typeRequest = requireNotNull(state.completion)
        assertEquals("Smart completion", typeRequest.title)
        val typeNames = typeRequest.candidates()
        assertTrue("Clock" in typeNames)
        assertFalse("helper" in typeNames)
        assertFalse("time" in typeNames)
        assertFalse("class" in typeNames)

        state.completion = null
        val expressionText = "package demo\n\nval clock = \n"
        editor.updateValue(TextFieldValue(expressionText, TextRange(expressionText.length)))
        assertTrue(dispatchAction(ActionId.SMART_TYPE_COMPLETION, state) { null })

        val expressionNames = requireNotNull(state.completion).candidates()
        assertTrue("helper" in expressionNames)
        assertFalse("Clock" in expressionNames)
        assertFalse("fun" in expressionNames)
    }

    @Test
    fun `plain code completion keeps its title`() {
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("val value = 1", TextRange(3)))

        assertTrue(dispatchAction(ActionId.CODE_COMPLETION, state) { null })

        assertEquals("Completion", requireNotNull(state.completion).title)
    }

    @Test
    fun `quick documentation opens on the declaration and closes on the second request`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val text = "/** Adds one. */\nfun helper() {}\n\nfun main() {\n    helper()\n}\n"
        editor.updateValue(TextFieldValue(text, TextRange(text.lastIndexOf("helper()") + 1)))

        assertTrue(dispatchAction(ActionId.QUICK_DOC, state) { null })

        val request = requireNotNull(state.quickDoc)
        val candidate = request.candidates.first()
        assertEquals("fun helper()", candidate.signature)
        assertEquals("Adds one.", candidate.doc)
        assertEquals(1, candidate.declaration.line)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

        assertTrue(dispatchAction(ActionId.QUICK_DOC, state) { null })
        assertNull(state.quickDoc)
    }

    @Test
    fun `quick definition opens on the declaration text`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val text = "class Foo {\n    fun a() {}\n}\n\nval use = Foo()\n"
        editor.updateValue(TextFieldValue(text, TextRange(text.lastIndexOf("Foo") + 1)))

        assertTrue(dispatchAction(ActionId.QUICK_DEFINITION, state) { null })

        val candidate = requireNotNull(state.quickDefinition).candidates.first()
        assertEquals("a.kt:1", "${candidate.declaration.relativePath}:${candidate.declaration.line + 1}")
        assertEquals("class Foo {\n    fun a() {}\n}", candidate.definition)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
    }

    @Test
    fun `the peek actions report when the caret is not on a name`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("a + b", TextRange(2)))

        assertTrue(dispatchAction(ActionId.QUICK_DOC, state) { null })
        assertNull(state.quickDoc)
        assertEquals("No declaration found", state.statusMessage)

        assertTrue(dispatchAction(ActionId.QUICK_DEFINITION, state) { null })
        assertNull(state.quickDefinition)
        assertEquals("No declaration found", state.statusMessage)
    }

    @Test
    fun `parameter info opens for the call at the caret and escape closes it`() {
        val session = openProjectWith("a.kt")
        state.focus = KeyContext.EDITOR
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val text = "fun greet(name: String, greeting: String) {}\n\nfun main() {\n    greet(\"a\", )\n}\n"
        val caret = text.indexOf("greet(\"a\", ") + "greet(\"a\", ".length
        editor.updateValue(TextFieldValue(text, TextRange(caret)))

        assertTrue(dispatchAction(ActionId.PARAMETER_INFO, state) { null })

        val request = requireNotNull(state.parameterInfo)
        assertEquals(listOf("fun greet(name: String, greeting: String)"), request.signatures)
        assertEquals(text.indexOf("greet(\"a\", ") + "greet(".length - 1, request.openOffset)
        assertEquals(KeyContext.EDITOR, state.focus)

        assertTrue(dispatchAction(ActionId.EDITOR_ESCAPE, state) { null })
        assertNull(state.parameterInfo)
        assertEquals(KeyContext.EDITOR, state.focus)
    }

    @Test
    fun `parameter info reports a missing call and unresolved parameters`() {
        val root = temp.resolve("parameter-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            "package demo\n\nfun main() {\n    missing(1)\n}\n",
        )
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        val text = editor.value.text
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("missing(1)") + "missing(".length)))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.PARAMETER_INFO, state) { null })
        assertEquals("No parameter info for missing", state.statusMessage)
        assertNull(state.parameterInfo)

        editor.updateValue(TextFieldValue("plainName", TextRange(9)))
        assertTrue(dispatchAction(ActionId.PARAMETER_INFO, state) { null })
        assertEquals("No call at caret", state.statusMessage)
        assertNull(state.parameterInfo)
    }

    @Test
    fun `the index path drops other files' private declarations and merges a file's own`() {
        val root = temp.resolve("peek-index-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Main.kt"),
            """
                package demo

                fun helper(a: Int) {}

                fun helper(a: String, b: Int) {}

                fun main() {
                    helper(1)
                }
            """.trimIndent() + "\n",
        )
        Files.writeString(
            root.resolve("src/main/kotlin/demo/Other.kt"),
            "package demo\n\nprivate fun helper(a: Long) {}\n",
        )
        state.openFolder(root)
        val session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000))
        val editor = session.editors.openFile(VFile(root.resolve("src/main/kotlin/demo/Main.kt"))) as TextFileEditor
        val text = editor.value.text
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("helper(1)") + 1)))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.QUICK_DOC, state) { null })

        val candidates = requireNotNull(state.quickDoc).candidates
        assertEquals(listOf(4, 2), candidates.map { it.declaration.line })
        assertTrue(candidates.all { it.declaration.relativePath == "src/main/kotlin/demo/Main.kt" })
    }

    @Test
    fun `surround with opens the templates of the file type and reports the rest`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt", "a.txt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue("val x = 1", TextRange(0)))

        assertTrue(dispatchAction(ActionId.SURROUND_WITH, state) { null })

        val request = requireNotNull(state.surround)
        assertEquals(
            listOf(
                "if", "if / else", "while", "do / while", "for", "try / catch",
                "try / finally", "try / catch / finally", "run { }",
            ),
            request.templates.map { it.label },
        )
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

        state.surround = null
        session.editors.openFile(VFile(root.resolve("a.txt")))
        assertTrue(dispatchAction(ActionId.SURROUND_WITH, state) { null })
        assertNull(state.surround)
        assertEquals("No surround templates for this file", state.statusMessage)

        val readOnly = session.editors.openFile(VFile(root.resolve("missing.kt"))) as TextFileEditor
        assertTrue(readOnly.isReadOnly)
        assertTrue(dispatchAction(ActionId.SURROUND_WITH, state) { null })
        assertNull(state.surround)
        assertEquals("File is read-only", state.statusMessage)
    }

    @Test
    fun `show usages opens a popup for the identifier at the caret`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val text = "fun helper() {}\n\nfun use() { helper() }\n"
        editor.updateValue(TextFieldValue(text, TextRange(text.lastIndexOf("helper") + 1)))

        assertTrue(dispatchAction(ActionId.SHOW_USAGES, state) { null })

        val request = requireNotNull(state.showUsages)
        assertEquals("helper", request.name)
        assertTrue(request.searching)
        assertEquals(KeyContext.SEARCH_POPUP, state.focus)
        assertEquals(1L, state.showUsagesGeneration)
    }

    @Test
    fun `show usages without an editor is not consumed and without an identifier reports it`() {
        state.focus = KeyContext.EDITOR
        assertFalse(dispatchAction(ActionId.SHOW_USAGES, state) { null })

        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("a + b", TextRange(2)))

        assertTrue(dispatchAction(ActionId.SHOW_USAGES, state) { null })
        assertEquals("No identifier at the caret", state.statusMessage)
        assertNull(state.showUsages)
    }

    @Test
    fun `show usages and find usages open the same find window tab`() {
        state.focus = KeyContext.EDITOR
        val session = openProjectWith("a.kt")
        val root = temp.resolve("project")
        val editor = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val text = "fun helper() {}\n\nfun use() { helper() }\n"
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("helper") + 1)))

        assertTrue(dispatchAction(ActionId.FIND_USAGES, state) { null })
        val fromFindUsages = state.findToolWindow.tabs.single()

        assertTrue(dispatchAction(ActionId.SHOW_USAGES, state) { null })
        val request = requireNotNull(state.showUsages)
        state.showUsages = null
        openUsagesFindWindow(state, request.name)

        val fromPopup = state.findToolWindow.tabs.single()
        assertEquals(fromFindUsages.id, fromPopup.id)
        assertEquals(fromFindUsages.kind, fromPopup.kind)
        assertEquals(fromFindUsages.title, fromPopup.title)
        assertEquals(fromFindUsages.query, fromPopup.query)
        assertEquals(fromFindUsages.options, fromPopup.options)
    }

    @Test
    fun `jump to last change opens the newest place and walks back on repeat`() {
        val session = openProjectWith("a.kt", "b.kt")
        val root = temp.resolve("project")
        val a = session.editors.openFile(VFile(root.resolve("a.kt"))) as TextFileEditor
        val b = session.editors.openFile(VFile(root.resolve("b.kt"))) as TextFileEditor
        a.updateValue(TextFieldValue("one\ntwo\n", TextRange(0)))
        b.updateValue(TextFieldValue("alpha\nbeta\n", TextRange(0)))
        val aPath = a.file.path.toAbsolutePath().normalize()
        val bPath = b.file.path.toAbsolutePath().normalize()
        session.editLocations.change(aPath, offset = 4, oldLength = 0, newLength = 0, line = 1, recorded = true)
        session.editLocations.change(bPath, offset = 6, oldLength = 0, newLength = 0, line = 1, recorded = true)
        session.editors.openFile(VFile(root.resolve("a.kt")))
        a.updateValue(TextFieldValue("one\ntwo\n", TextRange(0)))

        assertTrue(dispatchAction(ActionId.JUMP_TO_LAST_CHANGE, state) { null })
        assertEquals(b.file.path, session.editors.selected?.file?.path)
        assertEquals(6, b.value.selection.start)

        assertTrue(dispatchAction(ActionId.JUMP_TO_LAST_CHANGE, state) { null })
        assertEquals(a.file.path, session.editors.selected?.file?.path)
        assertEquals(4, (session.editors.selected as TextFileEditor).value.selection.start)
    }

    @Test
    fun `jump to last change reports no place and drops a gone file's places`() {
        val session = openProjectWith("a.kt")
        assertTrue(dispatchAction(ActionId.JUMP_TO_LAST_CHANGE, state) { null })
        assertEquals("No edit location", state.statusMessage)

        val gone = temp.resolve("project/gone.kt").toAbsolutePath().normalize()
        session.editLocations.change(gone, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)

        assertTrue(dispatchAction(ActionId.JUMP_TO_LAST_CHANGE, state) { null })
        assertEquals("File not found: gone.kt", state.statusMessage)
        assertTrue(session.editLocations.places.isEmpty())
    }

    @Test
    fun `highlight usages in file marks every match and clears on the next press`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        val text = "alpha beta\nalpha\n"
        editor.updateValue(TextFieldValue(text, TextRange(1)))

        assertTrue(dispatchAction(ActionId.HIGHLIGHT_USAGES_IN_FILE, state) { null })

        assertEquals(listOf(0..4, 11..15), editor.usageHighlights)
        assertEquals("2 usages of alpha · ⌘G next · ⇧⌘G previous · Esc clears", state.statusMessage)

        editor.updateValue(TextFieldValue(text, TextRange(2)))
        assertTrue(dispatchAction(ActionId.HIGHLIGHT_USAGES_IN_FILE, state) { null })
        assertTrue(editor.usageHighlights.isEmpty())
    }

    @Test
    fun `find next walks the usage highlights and wraps`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("alpha beta\nalpha gamma\nalpha\n", TextRange(0)))
        editor.usageHighlights = listOf(0..4, 11..15, 23..27)
        assertFalse(state.findBar.visible)

        assertTrue(dispatchAction(ActionId.FIND_NEXT, state) { null })
        assertEquals(11, editor.value.selection.start)
        assertTrue(dispatchAction(ActionId.FIND_NEXT, state) { null })
        assertEquals(23, editor.value.selection.start)
        assertTrue(dispatchAction(ActionId.FIND_NEXT, state) { null })
        assertEquals(0, editor.value.selection.start)
        assertTrue(dispatchAction(ActionId.FIND_PREVIOUS, state) { null })
        assertEquals(23, editor.value.selection.start)
    }

    @Test
    fun `find next without highlights still uses the find bar`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))

        assertFalse(dispatchAction(ActionId.FIND_NEXT, state) { null })

        state.findBar.open(editor, replace = false)
        state.findBar.query = "alpha"
        assertTrue(dispatchAction(ActionId.FIND_NEXT, state) { null })
        assertEquals(TextRange(0, 5), editor.value.selection)
    }

    @Test
    fun `escape and a text change clear the usage highlights`() {
        val session = openProjectWith("a.kt")
        val editor = session.editors.selected as TextFileEditor
        editor.updateValue(TextFieldValue("alpha beta\nalpha\n", TextRange(0)))
        editor.usageHighlights = listOf(0..4, 11..15)

        assertTrue(dispatchAction(ActionId.EDITOR_ESCAPE, state) { null })
        assertTrue(editor.usageHighlights.isEmpty())

        editor.usageHighlights = listOf(0..4, 11..15)
        editor.updateValue(TextFieldValue("alpha beta\nalpha\nx", TextRange(19)))
        assertTrue(editor.usageHighlights.isEmpty())
    }

    @Test
    fun `recent locations opens the popup only with a session`() {
        assertTrue(dispatchAction(ActionId.RECENT_LOCATIONS, state) { null })
        assertFalse(state.recentLocationsVisible)

        val session = openProjectWith("a.kt")
        assertTrue(dispatchAction(ActionId.RECENT_LOCATIONS, state) { null })

        assertTrue(state.recentLocationsVisible)
        assertEquals(KeyContext.SEARCH_POPUP, state.focus)
        assertEquals(1, state.recentLocationsGeneration)
        val second = dispatchAction(ActionId.RECENT_LOCATIONS, state) { null }
        assertTrue(second)
        assertEquals(2, state.recentLocationsGeneration)
        assertEquals("a.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `show nav bar opens in the editor, a list window and the global context only`() {
        assertFalse(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })

        val session = openProjectWith("a.kt")
        state.focus = KeyContext.EDITOR
        assertTrue(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
        assertEquals(KeyContext.NAV_BAR, state.focus)
        assertEquals("a.kt", requireNotNull(state.navigationBar).segment.name)
        assertEquals("a.kt", session.editors.selected?.file?.name)

        for (context in listOf(KeyContext.LIST_WINDOW, KeyContext.GLOBAL)) {
            state.navigationBar = null
            state.focus = context
            assertTrue(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
            assertEquals(KeyContext.NAV_BAR, state.focus)
            assertTrue(state.navigationBar != null)
        }

        for (context in listOf(
            KeyContext.TERMINAL,
            KeyContext.RUN_WINDOW,
            KeyContext.FIND_BAR,
            KeyContext.EDITOR_FIELD,
            KeyContext.SEARCH_POPUP,
            KeyContext.CONTEXT_POPUP,
        )) {
            state.navigationBar = null
            state.focus = context
            assertFalse(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
            assertEquals(context, state.focus)
            assertNull(state.navigationBar)
        }
    }

    @Test
    fun `edit source goes to the declaration in the editor`() {
        val root = temp.resolve("edit-source-declaration")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "fun helper() {}\n\nfun main() {\n    helper()\n}\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.lastIndexOf("helper") + 1)))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.EDIT_SOURCE, state) { null })

        val line = editor.value.text.take(editor.value.selection.start).count { it == '\n' }
        assertEquals(0, line)
    }

    @Test
    fun `edit source is left to a list window and the terminal`() {
        openProjectWith("a.kt")

        for (context in listOf(
            KeyContext.LIST_WINDOW,
            KeyContext.TERMINAL,
            KeyContext.RUN_WINDOW,
            KeyContext.FIND_BAR,
            KeyContext.EDITOR_FIELD,
            KeyContext.SEARCH_POPUP,
            KeyContext.CONTEXT_POPUP,
        )) {
            state.focus = context
            assertFalse(dispatchAction(ActionId.EDIT_SOURCE, state) { null })
        }
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
    }

    @Test
    fun `edit source in the global context hands the keyboard to the selected editor`() {
        val session = openProjectWith("a.kt")
        state.focus = KeyContext.GLOBAL
        val before = state.editorFocusRequests

        assertTrue(dispatchAction(ActionId.EDIT_SOURCE, state) { null })

        assertEquals(KeyContext.EDITOR, state.focus)
        assertEquals(before + 1, state.editorFocusRequests)
        assertEquals("a.kt", session.editors.selected?.file?.name)

        val empty = Files.createDirectories(temp.resolve("empty-project"))
        state.openFolder(empty)
        state.focus = KeyContext.GLOBAL
        assertFalse(dispatchAction(ActionId.EDIT_SOURCE, state) { null })
        assertEquals(KeyContext.GLOBAL, state.focus)
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

private class RecordingClipboard : TextClipboard {
    var text: String? = null

    override fun writeText(text: String): Boolean {
        this.text = text
        return true
    }

    override fun readText(): String? = text
}
