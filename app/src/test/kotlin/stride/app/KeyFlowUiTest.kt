package stride.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.COMPLETION_FIELD_TAG
import stride.app.editor.COMPLETION_SELECTED_ROW_TAG
import stride.app.editor.PASTE_HISTORY_SELECTED_ROW_TAG
import stride.app.editor.RENAME_FIELD_TAG
import stride.app.editor.TextClipboard
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindOptions
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.dispatchAction
import stride.app.keys.handleWindowKeyEvent
import stride.app.navigation.NavigationLocation
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.run.TestStatus
import stride.app.search.FindTabKind
import stride.app.search.SEARCH_SELECTED_ROW_TAG
import stride.app.search.USAGE_SELECTED_ROW_TAG
import stride.app.terminal.FakeTtyConnector
import stride.app.terminal.TerminalSession
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.DeclarationIndex
import stride.search.FileNameIndex
import stride.search.SearchTab

@ExtendWith(FixedUiFontExtension::class)
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
    fun `choosing an action that opens no popup returns global shortcuts`() = runComposeUiTest {
        val root = temp.resolve("action-focus-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package demo\n")
        Files.writeString(root.resolve("B.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.editors.openFile(VFile(root.resolve("B.kt")))

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

            onNode(hasSetTextAction() and isFocused()).performTextInput("close tab")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Close Tab").fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.searchVisible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(1, session.editors.openFiles.size)

            // The popup field is gone and nothing holds widget focus, so route through the
            // same keymap and dispatcher the window handler uses from the state's focus.
            val router = KeyRouter(isMac = true)
            val recent = router.route(KeyEventType.DOWN, Key.E, meta = true, focus = state.focus)
            assertEquals(ActionId.RECENT_FILES, recent)
            assertTrue(dispatchAction(requireNotNull(recent), state) { null })
            waitUntil(timeoutMillis = 5_000) { state.recentFilesVisible }
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
    fun `command enter with an empty find in path query adds no tab`() = runComposeUiTest {
        val root = temp.resolve("empty-find-in-path-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.findInPathVisible }

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
            waitForIdle()

            assertTrue(state.findInPathVisible)
            assertTrue(state.findToolWindow.tabs.isEmpty())
            assertFalse(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt command enter confirms and applies replace all`() = runComposeUiTest {
        val root = temp.resolve("replace-all-project")
        Files.createDirectories(root)
        val file = root.resolve("A.kt")
        Files.writeString(file, "alpha one\nalpha two\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.R) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.findInPathVisible && state.findInPathReplace }

            onNodeWithTag("find-in-path-query").performTextInput("alpha")
            onNodeWithTag("find-in-path-replacement").performTextInput("beta")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("A.kt:1").fetchSemanticsNodes().isNotEmpty()
            }

            val prompt = "Replace 2 occurrences of 'alpha' across 1 file with 'beta'? " +
                "1 file is written to disk and cannot be undone."
            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(prompt).fetchSemanticsNodes().isNotEmpty()
            }

            // Esc cancels the confirmation only; the popup stays open.
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertTrue(state.findInPathVisible)
            assertTrue(onAllNodesWithText(prompt).fetchSemanticsNodes().isEmpty())

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(prompt).fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.findInPathVisible }
            assertEquals("beta one\nbeta two\n", Files.readString(file))
            assertEquals("Replaced 2 occurrences in 1 file", state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `replace selected in the find window changes the file and drops the row`() = runComposeUiTest {
        val root = temp.resolve("replace-selected-project")
        Files.createDirectories(root)
        val file = root.resolve("A.kt")
        Files.writeString(file, "alpha one\nalpha two\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            state.openFindResults(
                kind = FindTabKind.REPLACE,
                title = "Replace 'alpha' with 'beta'",
                query = "alpha",
                options = FindOptions(),
                replacement = "beta",
            )
            val tab = state.findToolWindow.tabs.single()
            waitUntil(timeoutMillis = 5_000) { !tab.searching }
            state.toolWindows.activate("find")

            setContent { KeyFlowHarness(state) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Replace Selected").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Replace Selected").performClick()

            waitUntil(timeoutMillis = 5_000) { tab.hits.size == 1 }
            assertEquals("beta one\nalpha two\n", Files.readString(file))
            assertEquals("Replaced 1 occurrence in 1 file", tab.message)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `replace selected twice in the same file replaces both hits`() = runComposeUiTest {
        val root = temp.resolve("replace-selected-twice-project")
        Files.createDirectories(root)
        val file = root.resolve("A.kt")
        Files.writeString(file, "alpha one\nalpha two\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            state.openFindResults(
                kind = FindTabKind.REPLACE,
                title = "Replace 'alpha' with 'beta'",
                query = "alpha",
                options = FindOptions(),
                replacement = "beta",
            )
            val tab = state.findToolWindow.tabs.single()
            waitUntil(timeoutMillis = 5_000) { !tab.searching }
            state.toolWindows.activate("find")

            setContent { KeyFlowHarness(state) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Replace Selected").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Replace Selected").performClick()
            waitUntil(timeoutMillis = 5_000) {
                tab.hits.size == 1 && !state.findToolWindow.replaceState(tab).busy
            }
            assertEquals("beta one\nalpha two\n", Files.readString(file))

            onNodeWithText("Replace Selected").performClick()
            waitUntil(timeoutMillis = 5_000) { tab.hits.isEmpty() }
            assertEquals("beta one\nbeta two\n", Files.readString(file))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `replace selected with an invalid regex replacement shows the error and keeps the row`() = runComposeUiTest {
        val root = temp.resolve("replace-selected-invalid-replacement-project")
        Files.createDirectories(root)
        val file = root.resolve("A.kt")
        Files.writeString(file, "alpha one\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            state.openFindResults(
                kind = FindTabKind.REPLACE,
                title = "Replace '(alpha)' with '\$2'",
                query = "(alpha)",
                options = FindOptions(regex = true),
                replacement = "\$2",
            )
            val tab = state.findToolWindow.tabs.single()
            waitUntil(timeoutMillis = 5_000) { !tab.searching }
            state.toolWindows.activate("find")

            setContent { KeyFlowHarness(state) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Replace Selected").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Replace Selected").performClick()

            waitUntil(timeoutMillis = 5_000) { tab.message == "Invalid replacement: No group 2" }
            assertEquals(1, tab.hits.size)
            assertEquals("alpha one\n", Files.readString(file))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `replace selected on a file that is not valid UTF-8 shows the error and keeps the row`() = runComposeUiTest {
        val root = temp.resolve("replace-selected-legacy-encoding-project")
        Files.createDirectories(root)
        val file = root.resolve("Legacy.kt")
        Files.write(file, "alpha café\n".toByteArray(Charsets.ISO_8859_1))
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            state.openFindResults(
                kind = FindTabKind.REPLACE,
                title = "Replace 'alpha' with 'beta'",
                query = "alpha",
                options = FindOptions(),
                replacement = "beta",
            )
            val tab = state.findToolWindow.tabs.single()
            waitUntil(timeoutMillis = 5_000) { !tab.searching }
            state.toolWindows.activate("find")

            setContent { KeyFlowHarness(state) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Replace Selected").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Replace Selected").performClick()

            waitUntil(timeoutMillis = 5_000) { tab.message == "Legacy.kt is not UTF-8" }
            assertEquals(1, tab.hits.size)
            assertEquals("alpha café\n", String(Files.readAllBytes(file), Charsets.ISO_8859_1))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an invalid regex replacement shows the message and a fixed one confirms`() = runComposeUiTest {
        val root = temp.resolve("invalid-replacement-project")
        Files.createDirectories(root)
        val file = root.resolve("A.kt")
        Files.writeString(file, "alpha one\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")

            setContent { KeyFlowHarness(state) }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.R) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.findInPathVisible && state.findInPathReplace }

            onNodeWithTag("find-in-path-query").performTextInput("(alpha)")
            onNodeWithText(".*").performClick()
            onNodeWithTag("find-in-path-replacement").performTextInput("\$2")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("A.kt:1").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Invalid replacement:", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                onAllNodesWithText("Replace 1 occurrence", substring = true).fetchSemanticsNodes().isEmpty(),
            )
            assertEquals("alpha one\n", Files.readString(file))

            onNodeWithTag("find-in-path-replacement").performTextClearance()
            onNodeWithTag("find-in-path-replacement").performTextInput("beta")
            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("with 'beta'?", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.findInPathVisible }
            assertEquals("beta one\n", Files.readString(file))
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
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val file = root.resolve("src/main/kotlin/demo/Main.kt")
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
    fun `context actions popup blocks global shortcuts and escape closes it`() = runComposeUiTest {
        val root = temp.resolve("context-modal-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val file = root.resolve("src/main/kotlin/demo/Main.kt")
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

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { pressKey(Key.Enter) }
            }
            waitUntil(timeoutMillis = 5_000) { state.contextActionsVisible }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.R) }
            }
            waitForIdle()
            assertNull(manager.lastRun)
            assertTrue(state.contextActionsVisible)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { !state.contextActionsVisible }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `code completion filters, inserts and blocks global shortcuts`() = runComposeUiTest {
        val root = temp.resolve("completion-ui-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun main() {}\nval ma = 1\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val caret = editor.value.text.lastIndexOf("ma") + 2
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) }
            }
            waitUntil(timeoutMillis = 5_000) { state.completion != null }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
            waitForIdle()
            onNodeWithTag(COMPLETION_FIELD_TAG).assertIsFocused()
            // Candidates are computed off the UI thread, so the list arrives a moment later.
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(COMPLETION_SELECTED_ROW_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.R) }
            }
            waitForIdle()
            assertTrue(state.completion != null)
            assertNull(state.statusMessage)

            onNodeWithTag(COMPLETION_FIELD_TAG).performTextInput("i")
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.completion == null }
            assertEquals("fun main() {}\nval main = 1\n", editor.value.text)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onNode(hasSetTextAction()).assertIsFocused()

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) }
            }
            waitUntil(timeoutMillis = 5_000) { state.completion != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.completion == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rename popup renames every occurrence and escape cancels`() = runComposeUiTest {
        val root = temp.resolve("rename-ui-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "foo = foo + fooBar\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) }
            }
            waitUntil(timeoutMillis = 5_000) { state.rename != null }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
            waitForIdle()
            onNodeWithTag(RENAME_FIELD_TAG).assertIsFocused()

            onNodeWithTag(RENAME_FIELD_TAG).performTextInput("bar")
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.rename == null }
            assertEquals("bar = bar + fooBar\n", editor.value.text)
            assertEquals("Renamed 2 occurrences", state.statusMessage)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onNode(hasSetTextAction()).assertIsFocused()

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) }
            }
            waitUntil(timeoutMillis = 5_000) { state.rename != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.rename == null }
            assertEquals("bar = bar + fooBar\n", editor.value.text)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rename with an empty field stays open and shows a hint`() = runComposeUiTest {
        val root = temp.resolve("rename-empty-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "foo = foo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.F6) }
            }
            waitUntil(timeoutMillis = 5_000) { state.rename != null }
            waitForIdle()
            onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            assertTrue(state.rename != null)
            onNodeWithText("Type a new name").assertExists()
            assertEquals("foo = foo\n", editor.value.text)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.rename == null }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `paste from history replaces the selection and escape closes it`() = runComposeUiTest {
        val root = temp.resolve("paste-history-ui-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "alpha beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            state.clipboard = object : TextClipboard {
                var text: String? = null

                override fun writeText(text: String): Boolean {
                    this.text = text
                    return true
                }

                override fun readText(): String? = text
            }
            state.clipboardHistory.record("gamma")
            state.clipboardHistory.record("delta\ndelta two")

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0, 5)))

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.V) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.pasteHistory != null }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
            waitForIdle()
            onNodeWithTag(PASTE_HISTORY_SELECTED_ROW_TAG).assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.pasteHistory == null }
            assertEquals("delta\ndelta two beta\n", editor.value.text)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.V) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.pasteHistory != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.pasteHistory == null }
            assertEquals("delta\ndelta two beta\n", editor.value.text)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `editor shortcuts stay off the buffer while the goto line field is focused`() = runComposeUiTest {
        val root = temp.resolve("goto-field-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "one\ntwo\nthree")
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
            waitUntil(timeoutMillis = 5_000) { editor.gotoLineVisible }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR_FIELD }

            val before = editor.value.text
            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.C) }
            }
            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) }
            }
            waitForIdle()

            assertEquals(before, editor.value.text)
            assertNull(state.completion)
            assertTrue(editor.gotoLineVisible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `choosing go to line again focuses the already open field`() = runComposeUiTest {
        val root = temp.resolve("goto-refocus-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "line 1\nline 2")
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
            waitUntil(timeoutMillis = 5_000) { editor.gotoLineVisible }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()

            // An action chosen from the Actions tab hands focus to the editor while the popup stays.
            onNode(hasSetTextAction() and hasText("line 1", substring = true)).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            assertTrue(dispatchAction(ActionId.GOTO_LINE, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR_FIELD }
            onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).assertIsFocused()
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `moving the caret keeps the history entry in step`() = runComposeUiTest {
        val root = temp.resolve("caret-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Notes.kt"), "one\ntwo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Notes.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            waitForIdle()

            editor.updateValue(TextFieldValue(editor.value.text, TextRange(4)))

            waitUntil(timeoutMillis = 5_000) { session.history.current?.offset == 4 }
        } finally {
            state.dispose()
        }
    }
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `quick definition opens with alt space and enter jumps to the declaration`() = runComposeUiTest {
        val root = temp.resolve("quick-definition-ui-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun helper() {}\n\nfun main() {\n    helper()\n}\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val text = editor.value.text
            editor.updateValue(TextFieldValue(text, TextRange(text.lastIndexOf("helper()") + 1)))

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { pressKey(Key.Spacebar) }
            }
            waitUntil(timeoutMillis = 5_000) { state.quickDefinition != null }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.quickDefinition == null }
            assertEquals(0, editor.value.text.take(editor.value.selection.start).count { it == '\n' })
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `surround with opens from the keyboard and a shortcut key applies the template`() = runComposeUiTest {
        val root = temp.resolve("surround-ui-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "val x = 1\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.surround != null }
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.One) }
            waitUntil(timeoutMillis = 5_000) { state.surround == null }
            assertEquals("if () {\n    val x = 1\n}\n", editor.value.text)
            assertEquals("if (".length, editor.value.selection.start)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a modified shortcut key does not apply a surround template`() = runComposeUiTest {
        val root = temp.resolve("surround-modifier-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, "val x = 1\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.T) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.surround != null }
            waitForIdle()

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.One) } }
            waitForIdle()

            assertEquals("val x = 1\n", editor.value.text)
            assertTrue(state.surround != null)

            onRoot().performKeyInput { pressKey(Key.One) }
            waitUntil(timeoutMillis = 5_000) { state.surround == null }
            assertEquals("if () {\n    val x = 1\n}\n", editor.value.text)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `parameter info closes when go to line takes the focus`() = runComposeUiTest {
        val root = temp.resolve("parameter-info-focus-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        val text = "fun greet(name: String) {}\n\nfun main() {\n    greet(1)\n}\n"
        Files.writeString(file, text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("greet(1)") + "greet(".length)))

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.P) } }
            waitUntil(timeoutMillis = 5_000) { state.parameterInfo != null }
            assertEquals(KeyContext.EDITOR, state.focus)

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.L) } }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR_FIELD }
            waitUntil(timeoutMillis = 5_000) { state.parameterInfo == null }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command up and command down keep moving the caret to the document ends`() = runComposeUiTest {
        val root = temp.resolve("caret-ends-project")
        Files.createDirectories(root)
        val content = "one\ntwo\nthree\n"
        Files.writeString(root.resolve("Main.kt"), content)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(content, TextRange(2)))

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionDown) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.selection.start == content.length }
            assertNull(state.navigationBar)

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionUp) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.selection.start == 0 }
            assertNull(state.navigationBar)
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `typing clears the usage highlights and their status line`() = runComposeUiTest {
        val root = temp.resolve("usages-typing-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "alpha beta\nalpha\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.F7) } }
            }
            waitUntil(timeoutMillis = 5_000) { editor.usageHighlights.isNotEmpty() }
            assertEquals("2 usages of alpha · ⌘G next · ⇧⌘G previous · Esc clears", state.statusMessage)

            onNode(hasSetTextAction()).performTextInput("x")

            waitUntil(timeoutMillis = 5_000) { editor.usageHighlights.isEmpty() }
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the selected usage row scrolls into view`() = runComposeUiTest {
        val root = temp.resolve("usages-scroll-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), (1..30).joinToString("\n") { "alpha $it" } + "\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.F7) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.showUsages != null }
            val request = requireNotNull(state.showUsages)
            waitUntil(timeoutMillis = 5_000) { !request.searching && request.hits.size == 30 }
            waitForIdle()

            onRoot().performKeyInput { repeat(15) { pressKey(Key.DirectionDown) } }
            waitForIdle()

            onNodeWithTag(USAGE_SELECTED_ROW_TAG).assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command enter in the show usages popup opens the same find tab as option f7`() = runComposeUiTest {
        val root = temp.resolve("usages-find-window-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "fun helper() {}\n\nfun use() { helper() }\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val caret = editor.value.text.indexOf("helper") + 1
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(caret)))

            onRoot().performKeyInput {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.F7) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.showUsages != null }
            waitForIdle()

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }

            waitUntil(timeoutMillis = 5_000) { state.showUsages == null }
            val fromPopup = state.findToolWindow.tabs.single()
            assertEquals(TextRange(caret), editor.value.selection)

            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
            waitForIdle()

            val fromFindUsages = state.findToolWindow.tabs.single()
            assertEquals(fromFindUsages.id, fromPopup.id)
            assertEquals(fromFindUsages.kind, fromPopup.kind)
            assertEquals(fromFindUsages.title, fromPopup.title)
            assertEquals(fromFindUsages.query, fromPopup.query)
            assertEquals(fromFindUsages.options, fromPopup.options)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `f4 and command down open the selected usage from the popup`() = runComposeUiTest {
        val root = temp.resolve("usages-open-project")
        Files.createDirectories(root)
        val text = "fun helper() {}\n\nfun use() { helper() }\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("helper") + 1)))

            fun openPopup() {
                onRoot().performKeyInput {
                    withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.F7) } }
                }
                waitUntil(timeoutMillis = 5_000) { state.showUsages != null }
                val request = requireNotNull(state.showUsages)
                waitUntil(timeoutMillis = 5_000) { !request.searching && request.hits.size == 2 }
                waitForIdle()
            }

            openPopup()
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { state.showUsages == null }
            val first = text.indexOf("helper")
            assertEquals(TextRange(first, first + "helper".length), editor.value.selection)

            openPopup()
            onRoot().performKeyInput {
                pressKey(Key.DirectionDown)
                withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionDown) }
            }
            waitUntil(timeoutMillis = 5_000) { state.showUsages == null }
            val second = text.lastIndexOf("helper")
            assertEquals(TextRange(second, second + "helper".length), editor.value.selection)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tab click closes the navigation bar and keeps the editor on the new tab`() = runComposeUiTest {
        val root = temp.resolve("nav-bar-tab-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "package demo\n")
        Files.writeString(root.resolve("B.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val a = session.editors.openFile(VFile(root.resolve("A.kt"))) as TextFileEditor
            session.editors.openFile(VFile(root.resolve("B.kt")))
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            assertTrue(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.navigationBar != null }
            assertEquals(KeyContext.NAV_BAR, state.focus)

            onNode(hasText("A.kt") and SemanticsMatcher("tab row") { node -> node.boundsInRoot.top < 120f })
                .performClick()

            waitUntil(timeoutMillis = 5_000) { state.navigationBar == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(session.editors.selected === a)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit source from the actions tab reaches the declaration at the caret`() = runComposeUiTest {
        val root = temp.resolve("actions-edit-source-project")
        Files.createDirectories(root)
        val text = "fun helper() {}\n\nfun main() {\n    helper()\n}\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Main.kt"))) as TextFileEditor
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(text, TextRange(text.lastIndexOf("helper") + 1)))

            state.openSearch(SearchTab.ACTIONS)
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasSetTextAction() and isFocused()).performTextInput("edit source")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Edit Source").fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.searchVisible }
            waitUntil(timeoutMillis = 5_000) {
                editor.value.text.take(editor.value.selection.start).count { it == '\n' } == 0
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `jump to navigation bar from the actions tab opens the bar`() = runComposeUiTest {
        val root = temp.resolve("actions-nav-bar-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Main.kt")))
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            state.openSearch(SearchTab.ACTIONS)
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasSetTextAction() and isFocused()).performTextInput("jump to navigation bar")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Jump to Navigation Bar").fetchSemanticsNodes().isNotEmpty()
            }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { !state.searchVisible }
            waitUntil(timeoutMillis = 5_000) { state.navigationBar != null }
            assertEquals(KeyContext.NAV_BAR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `recent locations from the actions tab keeps the search popup focus`() = runComposeUiTest {
        val root = temp.resolve("actions-recent-locations-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Main.kt")))
            state.projectTreeVisible = false

            val seen = mutableListOf<KeyContext>()
            setContent {
                LaunchedEffect(Unit) {
                    snapshotFlow { state.focus }.collect { synchronized(seen) { seen += it } }
                }
                KeyFlowHarness(state)
            }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            state.openSearch(SearchTab.ACTIONS)
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNode(hasSetTextAction() and isFocused()).performTextInput("recent locations")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Recent Locations").fetchSemanticsNodes().isNotEmpty()
            }
            synchronized(seen) { seen.clear() }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { state.recentLocationsVisible }
            waitForIdle()
            assertEquals(KeyContext.SEARCH_POPUP, state.focus)
            // Nothing hands the keyboard to the editor between the search closing and the popup.
            assertFalse(synchronized(seen) { seen.contains(KeyContext.EDITOR) }, "focus went $seen")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the child list sits over the folder segment it lists`() = runComposeUiTest {
        val root = temp.resolve("nav-bar-list-position-project")
        Files.createDirectories(root.resolve("src/main"))
        Files.writeString(root.resolve("src/main/App.kt"), "package demo\n")
        Files.writeString(root.resolve("src/Util.kt"), "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("src/main/App.kt")))
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            assertTrue(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.navigationBar != null }
            waitForIdle()

            // From the file segment, ← twice reaches the src folder, and ↓ lists its children.
            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = 5_000) { state.navigationBar?.segment?.name == "src" }
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis = 5_000) { state.navigationBar?.list != null }
            waitForIdle()

            val segment = onNodeWithText("src").fetchSemanticsNode().boundsInRoot
            val row = onNodeWithText("Util.kt").fetchSemanticsNode().boundsInRoot
            assertEquals(segment.left, row.left, 1f)
            assertTrue(row.bottom <= segment.top)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a long path scrolls inside the bar and never moves the status capsule`() = runComposeUiTest {
        val root = temp.resolve("nav-bar-long-path-project")
        var directory = root
        for (index in 1..12) {
            directory = directory.resolve("nested-folder-" + index.toString().padStart(2, '0'))
        }
        Files.createDirectories(directory)
        val file = directory.resolve("Deep.kt")
        Files.writeString(file, "package demo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(file))
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val windowRight = onRoot().fetchSemanticsNode().boundsInRoot.right
            val statusBefore = onAllNodesWithText("Ln ", substring = true).fetchSemanticsNodes().single().boundsInRoot
            // The focus chip is the status group's last item, so its right edge is the group's.
            val labelBefore = onNodeWithText("Editor").fetchSemanticsNode().boundsInRoot

            assertTrue(dispatchAction(ActionId.SHOW_NAV_BAR, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.navigationBar != null }
            waitForIdle()
            val statusAfter = onAllNodesWithText("Ln ", substring = true).fetchSemanticsNodes().single().boundsInRoot
            // A bar that takes the whole row leaves the status capsule no width or pushes it out.
            assertTrue(statusAfter.right <= windowRight)
            assertTrue(statusAfter.width >= statusBefore.width)
            val labelAfter = onNodeWithText("Navigation").fetchSemanticsNode().boundsInRoot
            assertEquals(labelBefore.right, labelAfter.right, 1f)

            for (name in listOf("nested-folder-12", "nested-folder-11")) {
                onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
                waitUntil(timeoutMillis = 5_000) { state.navigationBar?.segment?.name == name }
                waitForIdle()
                val segment = onNodeWithText(name).fetchSemanticsNode().boundsInRoot
                assertTrue(segment.left >= 0f, "$name starts left of the window")
                assertTrue(segment.right <= statusAfter.left, "$name is not inside the bar")
                assertTrue(segment.width >= 80f, "$name is squeezed instead of scrolled into view")
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `recent locations keeps the caret line it opened on`() = runComposeUiTest {
        val root = temp.resolve("recent-locations-caret-project")
        Files.createDirectories(root)
        val text = "alpha\nbeta\ngamma\ndelta\n"
        val file = root.resolve("Main.kt")
        Files.writeString(file, text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            state.projectTreeVisible = false

            setContent { KeyFlowHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val gamma = NavigationLocation(file, text.indexOf("gamma"))
            editor.updateValue(TextFieldValue(text, TextRange(gamma.offset)))
            waitForIdle()
            session.history.record(NavigationLocation(file, text.indexOf("delta")))

            assertTrue(dispatchAction(ActionId.RECENT_LOCATIONS, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.recentLocationsVisible }
            waitForIdle()

            assertEquals(gamma, state.recentLocationsCaret)
            onAllNodesWithText("line 4").assertCountEquals(1)
            onAllNodesWithText("line 3").assertCountEquals(0)

            editor.updateValue(TextFieldValue(text, TextRange(text.indexOf("alpha"))))
            waitForIdle()
            onAllNodesWithText("line 3").assertCountEquals(0)
            assertEquals(gamma, state.recentLocationsCaret)
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
