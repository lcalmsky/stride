package stride.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.dispatchAction
import stride.app.keys.handleWindowKeyEvent
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.run.TestStatus
import stride.app.search.SEARCH_SELECTED_ROW_TAG
import stride.app.terminal.FakeTtyConnector
import stride.app.terminal.TerminalSession
import stride.app.ui.App
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.DeclarationIndex
import stride.search.FileNameIndex

class KeyFlowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(KeyFlowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `double shift then enter opens the matched file in a tab`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Alpha.kt"), "package demo\n")
        Files.writeString(root.resolve("src/Beta.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNodeWithText("Search everywhere").assertExists()

            onNode(hasSetTextAction()).performTextInput("Alpha")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Alpha.kt").fetchSemanticsNodes().isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Alpha.kt" }
            assertFalse(state.searchVisible)
            assertEquals(1, session.editors.openFiles.size)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tab cycles the search tabs and keeps the query`() = runComposeUiTest {
        val root = temp.resolve("tabs-project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Alpha.kt"), "package demo\n\nclass AlphaService\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")

            setContent { KeyFlowHarness(state) }
            onRoot().performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }

            onNode(hasSetTextAction()).performTextInput("Alpha")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Alpha.kt").fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("AlphaService").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Alpha.kt").fetchSemanticsNodes().isEmpty()
            }
            onNodeWithText("AlphaService").assertExists()

            onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Alpha.kt").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `choosing go to class from the actions tab switches to the classes tab`() = runComposeUiTest {
        val root = temp.resolve("actions-project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/AlphaService.kt"), "package demo\n\nclass AlphaService\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.A)
                keyUp(Key.ShiftLeft)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasText("Actions") and isSelected()).assertExists()

            onNode(hasSetTextAction()).performTextInput("go to cla")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Go to Class").fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("Classes") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Search everywhere").assertExists()
            onNodeWithText("Go to Class").assertDoesNotExist()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the toolbar search button opens the all tab`() = runComposeUiTest {
        val root = temp.resolve("toolbar-project")
        Files.createDirectories(root)
        val state = AppState()
        try {
            state.openFolder(root)

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.O) }
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasText("Classes") and isSelected()).assertExists()

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { !state.searchVisible }

            onNode(hasText("Search") and hasClickAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasText("All") and isSelected()).assertExists()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the selected search row stays visible and a tab switch returns to the top`() = runComposeUiTest {
        val root = temp.resolve("scroll-project")
        Files.createDirectories(root.resolve("src"))
        for (number in 1..12) {
            val suffix = number.toString().padStart(2, '0')
            Files.writeString(
                root.resolve("src/Item$suffix.kt"),
                "package demo\n\nclass ItemKlass$suffix\n",
            )
        }
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasSetTextAction()).performTextInput("Item")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Item04.kt").fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("ItemKlass01").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { repeat(10) { pressKey(Key.DirectionDown) } }
            waitForIdle()
            onNodeWithTag(SEARCH_SELECTED_ROW_TAG).assertIsDisplayed()

            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("Classes") and isSelected()).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("ItemKlass01").fetchSemanticsNodes().size == 1
            }
            onNodeWithText("ItemKlass01").assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command 4 opens the run tool window and shift escape hides it`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        val state = AppState()
        try {
            state.openFolder(root)

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                pressKey(Key.Four)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.RUN_WINDOW }
            // The tool window tab is clickable; the status bar's focus chip also reads "Run".
            onNode(hasText("Run") and hasClickAction()).assertExists()
            onAllNodesWithText("Run").assertCountEquals(2)

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.Escape) }
            }
            waitUntil(timeoutMillis = 5_000) { !state.toolWindows.visible }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option f12 shows the terminal tool window`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        val state = AppState()
        try {
            state.openFolder(root)

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { pressKey(Key.F12) }
            }
            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.toolWindows.activeId == "terminal"
            }
            onNode(hasText("Terminal") and hasClickAction()).assertExists()
            onAllNodesWithText("Terminal").assertCountEquals(2)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command shift close bracket selects the next tab`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package demo\n")
        Files.writeString(root.resolve("B.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.editors.openFile(VFile(root.resolve("B.kt")))
            assertEquals("B.kt", session.editors.selected?.file?.name)

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.RightBracket)
                keyUp(Key.ShiftLeft)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "A.kt" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command d duplicates the current line and command z undoes it`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "val a = 1\nval b = 2\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(2)))

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.D) }
            }
            waitUntil(timeoutMillis = 5_000) {
                editor.value.text == "val a = 1\nval a = 1\nval b = 2\n"
            }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.Z) }
            }
            waitUntil(timeoutMillis = 5_000) {
                editor.value.text == "val a = 1\nval b = 2\n"
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command l opens the goto line popup and enter moves the caret`() = runComposeUiTest {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        val content = (1..15).joinToString("\n") { "line $it" }
        Files.writeString(root.resolve("Main.kt"), content)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.L) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Go to line:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).performTextInput("12")
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { !editor.gotoLineVisible }

            val lineStart = content.lines().take(11).sumOf { it.length + 1 }
            assertEquals(TextRange(lineStart), editor.value.selection)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command f opens the find bar and steps through matches`() = runComposeUiTest {
        val root = temp.resolve("find-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "fun a()\nfun b()\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("A.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(editor.value.copy(selection = TextRange(0)))

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                pressKey(Key.F)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.findBar.visible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.FIND_BAR }

            onNodeWithTag("find-query").performTextInput("fun")
            onRoot().performKeyInput { pressKey(Key.Enter) }
            assertEquals(TextRange(0, 3), editor.value.selection)

            onRoot().performKeyInput { pressKey(Key.Enter) }
            assertEquals(TextRange(8, 11), editor.value.selection)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { !state.findBar.visible }
            assertTrue(editor.searchSpans.isEmpty())
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command e opens recent files and enter picks the second entry`() = runComposeUiTest {
        val root = temp.resolve("recent-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package a\n")
        Files.writeString(root.resolve("B.kt"), "package b\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.editors.openFile(VFile(root.resolve("B.kt")))

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                pressKey(Key.E)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.recentFilesVisible }
            onNodeWithText("Recent Files").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.recentFilesVisible }
            assertEquals("A.kt", session.editors.selected?.file?.name)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command shift f finds in path and opens the match`() = runComposeUiTest {
        val root = temp.resolve("find-in-path-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package demo\nfun alpha()\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.F)
                keyUp(Key.ShiftLeft)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.findInPathVisible }

            onNodeWithTag("find-in-path-query").performTextInput("alpha")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("A.kt:2").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.findInPathVisible }
            val editor = session.editors.selected as TextFileEditor
            assertEquals(TextRange(17, 22), editor.value.selection)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `terminal tool window takes focus and keeps global shortcuts`() = runComposeUiTest {
        val root = temp.resolve("terminal-project")
        Files.createDirectories(root)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val fake = FakeTtyConnector("$ ")
            session.project.service<TerminalSession>().start(fake)

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { pressKey(Key.F12) }
            }
            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.focus == KeyContext.TERMINAL
            }

            onRoot().performKeyInput {
                pressKey(Key.L)
                pressKey(Key.S)
                pressKey(Key.Enter)
            }
            waitUntil(timeoutMillis = 5_000) { fake.written.toString() == "ls\r" }

            val beforeEditorShortcut = fake.written.toString()
            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.D) }
            }
            Thread.sleep(200)
            assertEquals(beforeEditorShortcut, fake.written.toString())

            val router = KeyRouter(isMac = true)
            val save = router.route(KeyEventType.DOWN, Key.S, meta = true, focus = state.focus)
            assertEquals(ActionId.SAVE, save)
            assertTrue(dispatchAction(requireNotNull(save), state) { null })

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.Escape) }
            }
            waitUntil(timeoutMillis = 5_000) { !state.toolWindows.visible }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape in the find bar returns focus to the editor text field`() = runComposeUiTest {
        val root = temp.resolve("find-focus-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "fun a()\nfun b()\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput {
                keyDown(Key.MetaLeft)
                pressKey(Key.F)
                keyUp(Key.MetaLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.findBar.visible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.FIND_BAR }
            onNodeWithTag("find-query").assertIsFocused()

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { !state.findBar.visible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enter in the goto line popup returns focus to the editor text field`() = runComposeUiTest {
        val root = temp.resolve("goto-focus-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "line 1\nline 2\nline 3")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.L) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Go to line:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()

            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).performTextInput("2")
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { !editor.gotoLineVisible }
            onNode(hasSetTextAction()).assertIsFocused()

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.L) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Go to line:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { !editor.gotoLineVisible }
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control shift r runs the test at the caret through the run manager`() = runComposeUiTest {
        val root = temp.resolve("run-project")
        Files.createDirectories(root.resolve("src/test/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val testFile = root.resolve("src/test/kotlin/demo/DemoTest.kt")
        Files.writeString(
            testFile,
            """
            package demo

            import org.junit.jupiter.api.Test

            class DemoTest {
                @Test
                fun `adds`() {
                    println(1 + 1)
                }
            }
            """.trimIndent(),
        )
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(testFile)) as TextFileEditor
            var recorded: RunConfiguration? = null
            session.project.service<RunManager>().starter = ProcessStarter { configuration ->
                recorded = configuration
                val events = "##stride[test start class='demo.DemoTest' name='adds']\n" +
                    "##stride[test pass class='demo.DemoTest' name='adds' duration='3']\n"
                StartedProcess(FakeProcess(events.toByteArray(), 0), "fake")
            }

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))

            onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.R)
                keyUp(Key.ShiftLeft)
                keyUp(Key.CtrlLeft)
            }

            waitUntil(timeoutMillis = 5_000) { recorded != null }
            val manager = session.project.service<RunManager>()
            waitUntil(timeoutMillis = 5_000) {
                manager.current?.tree?.classes
                    ?.flatMap { it.methods }
                    ?.any { it.name == "adds" && it.status == TestStatus.PASSED } == true
            }

            assertEquals(listOf(":test", "--tests", "demo.DemoTest.adds"), requireNotNull(recorded).args)
            assertTrue(state.toolWindows.visible)
            assertEquals("run", state.toolWindows.activeId)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command f2 stops the running session`() = runComposeUiTest {
        val root = temp.resolve("run-stop-project")
        Files.createDirectories(root.resolve("src/test/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val testFile = root.resolve("src/test/kotlin/demo/DemoTest.kt")
        Files.writeString(
            testFile,
            """
            package demo

            import org.junit.jupiter.api.Test

            class DemoTest {
                @Test
                fun `adds`() {
                    println(1 + 1)
                }
            }
            """.trimIndent(),
        )
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(testFile)) as TextFileEditor
            val process = FakeProcess(blocking = true)
            session.project.service<RunManager>().starter = ProcessStarter { StartedProcess(process, "fake") }

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.R) }
            }
            val manager = session.project.service<RunManager>()
            waitUntil(timeoutMillis = 5_000) { manager.current != null && manager.isRunning }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.F2) }
            }

            waitUntil(timeoutMillis = 5_000) { process.destroyed }
            waitUntil(timeoutMillis = 5_000) { !manager.isRunning }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control shift r with no test at the caret shows the status message`() = runComposeUiTest {
        val root = temp.resolve("run-status-project")
        Files.createDirectories(root.resolve("src/test/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val file = root.resolve("src/test/kotlin/demo/Main.kt")
        Files.writeString(file, "package demo\n\nval answer = 42\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.length)))

            onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.R)
                keyUp(Key.ShiftLeft)
                keyUp(Key.CtrlLeft)
            }

            waitUntil(timeoutMillis = 5_000) { state.statusMessage == "Nothing to run here" }
            onNodeWithTag("status-message").assertExists()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt enter opens context actions and enter runs the main`() = runComposeUiTest {
        val root = temp.resolve("context-actions-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val file = root.resolve("Main.kt")
        Files.writeString(file, "package demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val manager = session.project.service<RunManager>()
            manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("println"))))
            val before = editor.value.text

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { pressKey(Key.Enter) }
            }
            waitUntil(timeoutMillis = 5_000) { state.contextActionsVisible }
            assertEquals(before, editor.value.text)
            onNodeWithText("Run 'MainKt.main()'").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { manager.lastRun?.kind == RunKind.APPLICATION }
            assertFalse(state.contextActionsVisible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dispatching editor escape directly closes the goto line popup and refocuses the editor`() = runComposeUiTest {
        val root = temp.resolve("goto-dispatch-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "line 1\nline 2\nline 3")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.L) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Go to line:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()
            assertTrue(editor.gotoLineVisible)

            val handled = dispatchAction(ActionId.EDITOR_ESCAPE, state) { null }
            waitForIdle()
            assertTrue(handled)
            waitUntil(timeoutMillis = 5_000) { !editor.gotoLineVisible }
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }
}

@Composable
private fun KeyFlowHarness(state: AppState) {
    val router = remember { KeyRouter(isMac = true) }
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                handleWindowKeyEvent(event, router, state) { null }
            }
            .focusable(),
    ) {
        CompositionLocalProvider(
            LocalAnimationsEnabled provides false,
            LocalWordmarkCaretBlink provides false,
        ) {
            App(state, onOpenFolder = {})
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}
