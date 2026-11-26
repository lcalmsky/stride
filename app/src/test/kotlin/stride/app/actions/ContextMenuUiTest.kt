package stride.app.actions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.GutterMarker
import stride.app.editor.SystemTextClipboard
import stride.app.editor.TextFileEditor
import stride.app.files.FileOperationPage
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.navigation.Bookmark
import stride.app.problems.CompilerMessage
import stride.app.problems.ProblemsModel
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.run.contextRun
import stride.app.run.RUN_WINDOW_TAG
import stride.app.terminal.FakeTtyConnector
import stride.app.terminal.TERMINAL_TAG
import stride.app.terminal.TerminalSession
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.PROJECT_TREE_TAG
import stride.app.ui.theme.DesignTokens
import stride.app.ui.toolwindow.LIST_WINDOW_TAG
import stride.app.vcs.COMMIT_LIST_TAG
import stride.app.vcs.CliGitExecutor
import stride.app.vcs.GIT_LOG_TAG
import stride.app.vcs.TEST_GIT
import stride.app.vcs.TestGitRepo
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ProblemSeverity

@ExtendWith(FixedUiFontExtension::class)
class ContextMenuUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ContextMenuUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click selects a directory row without expanding it`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val directory = session.tree.root.path.resolve("dest")

            rightClick("dest")
            waitForMenu(state)

            assertEquals(directory, session.tree.selected)
            assertFalse(session.tree.rows().any { it.file.name == "nested.txt" })
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click selects a file row without opening its editor`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val file = session.tree.root.path.resolve("source.txt")

            rightClick("source.txt")
            waitForMenu(state)

            assertEquals(file, session.tree.selected)
            assertNull(session.editors.selected)
            assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `commands that need the primary button's modifiers also open the menu`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithText("source.txt").performMouseInput { click(center) }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            waitForMenu(state)

            assertEquals(session.tree.root.path.resolve("source.txt"), session.tree.selected)
            assertNull(session.editors.selected)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the menu's rename row opens the popup and escape gives the tree the keyboard back`() =
        runComposeUiTest {
            val (state, _) = openProject()
            try {
                setContent { ContextMenuHarness(state) }
                waitForIdle()

                rightClick("source.txt")
                waitForMenu(state)
                val downPresses = downPressesTo(requireNotNull(state.contextMenu), ActionId.RENAME_FILE)
                repeat(downPresses) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
                onRoot().performKeyInput { pressKey(Key.Enter) }

                waitUntil(timeoutMillis = 5_000) {
                    state.fileOperation?.page == FileOperationPage.RENAME
                }

                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.fileOperation == null }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.PROJECT_TREE }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(PROJECT_TREE_TAG).assertIsFocused() }.isSuccess
                }
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape on a menu that ran nothing returns to the tree`() = runComposeUiTest {
        val (state, _) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            rightClick("source.txt")
            waitForMenu(state)
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.PROJECT_TREE }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(PROJECT_TREE_TAG).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `right opens a submenu, left closes it and enter picks a row inside`() = runComposeUiTest {
        val (state, _) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            rightClick("source.txt")
            waitForMenu(state)

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            waitForIdle()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.NEW_KOTLIN_FILE.name).assertExists()

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            waitForIdle()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.NEW_KOTLIN_FILE.name).assertDoesNotExist()

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { state.fileOperation != null }
            assertEquals(FileOperationPage.NEW_NAME, state.fileOperation?.page)
            assertEquals(false, state.fileOperation?.newDirectory)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a click outside the menu closes it`() = runComposeUiTest {
        val (state, _) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            rightClick("source.txt")
            waitForMenu(state)

            val size = onRoot().fetchSemanticsNode().size
            onRoot().performMouseInput { click(Offset(size.width - 20f, size.height - 20f)) }

            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.PROJECT_TREE }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `paste is enabled exactly when it pastes`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val destination = session.tree.root.path.resolve("dest")

            rightClick("dest")
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name)
                .assertIsNotEnabled()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).performClick()
            waitForIdle()
            assertNotNull(state.contextMenu)
            assertFalse(Files.exists(destination.resolve("source.txt")))

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }

            session.files.copy(session.tree.root.path.resolve("source.txt"))
            rightClick("dest")
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).assertIsEnabled()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).performClick()

            waitUntil(timeoutMillis = 5_000) { Files.exists(destination.resolve("source.txt")) }
            assertNull(state.contextMenu)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit source is disabled for a directory and its dispatch opens nothing`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            rightClick("dest")
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name)
                .assertIsNotEnabled()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitForIdle()

            assertNotNull(state.contextMenu)
            assertNull(session.editors.selected)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `hovering a submenu row opens it`() = runComposeUiTest {
        val (state, _) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            rightClick("source.txt")
            waitForMenu(state)
            val newRow = onNodeWithTag(CONTEXT_MENU_SUBMENU_TAG_PREFIX + "0-0")
            newRow.performMouseInput { moveTo(center) }

            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.NEW_KOTLIN_FILE.name).assertExists()
                }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click outside the selection moves the caret and opens the editor menu`() = runComposeUiTest {
        val (state, _) = openTextProject("Main.kt", "alpha beta gamma\ndelta\n")
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val editor = editorOf(state)
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0, 5)))
            editor.toggleCaretAt(12)
            waitForIdle()
            assertEquals(1, editor.secondaryCarets.size)

            val field = onNode(hasSetTextAction())
            val layout = field.textLayout()
            val outside = layout.getBoundingBox(editor.value.text.indexOf("delta"))
            field.performMouseInput { rightClick(Offset(outside.center.x, outside.center.y)) }
            waitForMenu(state)

            assertTrue(editor.secondaryCarets.isEmpty())
            assertTrue(editor.value.selection.collapsed)
            // The caret moved to the pointer's line, the second one.
            assertEquals(1, editor.value.text.substring(0, editor.value.selection.start).count { it == '\n' })
            onNodeWithText("Select All").assertDoesNotExist()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click inside the selection keeps the caret and the selection`() = runComposeUiTest {
        val (state, _) = openTextProject("Main.kt", "alpha beta gamma\n")
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val editor = editorOf(state)
            val selection = TextRange(0, editor.value.text.length)
            editor.updateValue(TextFieldValue(editor.value.text, selection))
            waitForIdle()

            val field = onNode(hasSetTextAction())
            val layout = field.textLayout()
            val inside = layout.getBoundingBox(6)
            field.performMouseInput { rightClick(Offset(inside.center.x, inside.center.y)) }
            waitForMenu(state)

            assertEquals(selection, editor.value.selection)
            assertTrue(editor.secondaryCarets.isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the editor menu copies, cuts and pastes exactly as the keys do`() = runComposeUiTest {
        val (state, _) = openTextProject("Main.kt", "alpha beta gamma\n")
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val editor = editorOf(state)
            val field = onNode(hasSetTextAction())
            field.performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            // Copy
            setClipboard("stale")
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0, 5)))
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { readClipboard() == "alpha" }
            val keyCopy = readClipboard()

            setClipboard("stale")
            rightClickInside(field, at = 2)
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.COPY_LINE.name).performClick()
            waitUntil(timeoutMillis = 5_000) { readClipboard() == "alpha" }
            assertEquals(keyCopy, readClipboard())

            // Cut
            setClipboard("stale")
            editor.updateValue(TextFieldValue("alpha beta gamma\n", TextRange(0, 5)))
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.X) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.text == " beta gamma\n" }
            val keyCut = readClipboard()

            setClipboard("stale")
            editor.updateValue(TextFieldValue("alpha beta gamma\n", TextRange(0, 5)))
            waitForIdle()
            rightClickInside(field, at = 2)
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.CUT_LINE.name).performClick()
            assertEquals(" beta gamma\n", editor.value.text)
            assertEquals(keyCut, readClipboard())

            // Paste
            setClipboard("XYZ")
            editor.updateValue(TextFieldValue("alpha beta gamma\n", TextRange(6, 10)))
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.V) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "alpha XYZ gamma\n" }
            val keyPaste = editor.value.text

            setClipboard("XYZ")
            editor.updateValue(TextFieldValue("alpha beta gamma\n", TextRange(6, 10)))
            waitForIdle()
            rightClickInside(field, at = 7)
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE.name).performClick()
            assertEquals(keyPaste, editor.value.text)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a fold placeholder does not expand it`() = runComposeUiTest {
        val content = "class Greeter {\n    fun greet(): String {\n        val word = \"hi\"\n        return word\n    }\n}\n"
        val (state, _) = openTextProject("Main.kt", content)
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val editor = editorOf(state)
            val field = onNode(hasSetTextAction())
            field.performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(content.indexOf("val word"))))
            waitForIdle()
            editor.applyAction(ActionId.COLLAPSE_REGION)
            waitForIdle()
            val folded = editor.foldedRegions
            assertTrue(folded.isNotEmpty())

            val layout = field.textLayout()
            val placeholder = layout.layoutInput.text.text.indexOf("...")
            assertTrue(placeholder > 0)
            val box = layout.getBoundingBox(placeholder)
            field.performMouseInput { rightClick(Offset(box.left + 1f, box.center.y)) }
            waitForMenu(state)

            assertEquals(folded, editor.foldedRegions)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a gutter right click moves the caret to the line under the pointer`() = runComposeUiTest {
        val content = "line one\nline two\nline three\nline four\n"
        val (state, _) = openTextProject("Main.kt", content)
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val editor = editorOf(state)
            editor.updateValue(TextFieldValue(content, TextRange(0)))
            waitForIdle()

            val gutter = onNode(hasTestTag("gutter"), useUnmergedTree = true)
            val linePx = with(density) { DesignTokens.codeLineHeight.toPx() }
            val gutterWidth = gutter.fetchSemanticsNode().size.width
            gutter.performMouseInput {
                rightClick(Offset(gutterWidth / 2f, linePx * 2 + linePx / 2))
            }
            waitForMenu(state)

            assertEquals(content.indexOf("line three"), editor.value.selection.start)
            assertEquals(ActionGroups.EDITOR_GUTTER_POPUP_MENU, state.contextMenu?.groupId)
            onNodeWithText("Toggle Bookmark").assertExists()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a gutter run marker opens the menu instead of running`() {
        val root = temp.resolve("marker-project")
        Files.createDirectories(root)
        val path = root.resolve("Main.kt")
        Files.writeString(path, "one\ntwo\nthree\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val editor = requireNotNull(state.session).editors.openFile(VFile(path)) as TextFileEditor
            var menuRequests = 0
            var runs = 0
            editor.onGutterContextMenuRequest = { menuRequests++ }
            editor.gutterMarkers = listOf(GutterMarker(0) { runs++ })
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(editor.value.text.indexOf("three"))))
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                        Box(Modifier.size(480.dp, 240.dp)) {
                            editor.Content(Modifier.fillMaxSize())
                        }
                    }
                }
                waitForIdle()
                val linePx = with(density) { DesignTokens.codeLineHeight.toPx() }
                onNode(hasTestTag("gutter"), useUnmergedTree = true).performMouseInput {
                    rightClick(Offset(with(density) { 7.dp.toPx() }, linePx / 2))
                }
                waitForIdle()
            }

            assertEquals(0, runs)
            assertEquals(1, menuRequests)
            assertEquals(0, editor.value.selection.start)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tab right click selects the tab and its close rows act on it`() = runComposeUiTest {
        val root = temp.resolve("tab-project")
        Files.createDirectories(root)
        for (name in listOf("alpha.kt", "beta.kt", "gamma.kt")) Files.writeString(root.resolve(name), name)
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            session.editors.openFile(VFile(root.resolve("beta.kt")))
            session.editors.openFile(VFile(root.resolve("gamma.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            onNodeWithText("alpha.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            assertEquals("alpha.kt", session.editors.selected?.file?.name)
            assertEquals(ActionGroups.EDITOR_TAB_POPUP_MENU, state.contextMenu?.groupId)

            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.CLOSE_TABS_TO_THE_RIGHT.name).performClick()
            waitUntil(timeoutMillis = 5_000) {
                session.editors.openFiles.map { it.file.name } == listOf("alpha.kt")
            }
            assertEquals("alpha.kt", session.editors.selected?.file?.name)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing the tab menu gives the editor the keyboard back`() = runComposeUiTest {
        val root = temp.resolve("tab-menu-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("alpha.kt"), "alpha\n")
        Files.writeString(root.resolve("beta.kt"), "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            session.editors.openFile(VFile(root.resolve("beta.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            onNodeWithText("alpha.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNode(hasSetTextAction()).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `running a tab row gives the editor the keyboard back`() = runComposeUiTest {
        val root = temp.resolve("tab-row-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("alpha.kt"), "alpha\n")
        Files.writeString(root.resolve("beta.kt"), "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            session.editors.openFile(VFile(root.resolve("beta.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            onNodeWithText("alpha.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.CLOSE_TAB.name).performClick()
            waitUntil(timeoutMillis = 5_000) { session.editors.openFiles.map { it.file.name } == listOf("beta.kt") }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNode(hasSetTextAction()).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing the last tab with the shortcut leaves the global context`() = runComposeUiTest {
        val root = temp.resolve("last-tab-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("alpha.kt"), "alpha\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.W) } }

            waitUntil(timeoutMillis = 5_000) { session.editors.openFiles.isEmpty() }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.GLOBAL }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `close all from the tab menu leaves the context the last tab's shortcut leaves`() = runComposeUiTest {
        val root = temp.resolve("close-all-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("alpha.kt"), "alpha\n")
        Files.writeString(root.resolve("beta.kt"), "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            session.editors.openFile(VFile(root.resolve("beta.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            onNodeWithText("alpha.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.CLOSE_ALL_TABS.name).performClick()

            waitUntil(timeoutMillis = 5_000) { session.editors.openFiles.isEmpty() }
            // The same place a plain ⌘W on the last tab leaves: no editor, so GLOBAL.
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.GLOBAL }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on the selected tab reaches the tab row through the root hit test`() =
        runComposeUiTest {
            val root = temp.resolve("selected-tab-project")
            Files.createDirectories(root)
            for (name in listOf("alpha.kt", "beta.kt", "gamma.kt")) {
                Files.writeString(root.resolve(name), "$name\n")
            }
            val state = AppState()
            try {
                state.openFolder(root)
                state.projectTreeVisible = false
                val session = requireNotNull(state.session)
                session.editors.openFile(VFile(root.resolve("alpha.kt")))
                session.editors.openFile(VFile(root.resolve("beta.kt")))
                session.editors.openFile(VFile(root.resolve("gamma.kt")))
                session.editors.openFile(VFile(root.resolve("alpha.kt")))
                assertEquals("alpha.kt", session.editors.selected?.file?.name)
                assertEquals(
                    listOf("alpha.kt", "beta.kt", "gamma.kt"),
                    session.editors.openFiles.map { it.file.name },
                )
                setContent { ContextMenuHarness(state) }
                waitForIdle()

                // The selected tab carries the sliding pill; the pointer is injected on the root at
                // the tab's own coordinates, so the real hit test decides which layer gets it.
                val at = onNode(hasText("alpha.kt") and hasClickAction())
                    .fetchSemanticsNode().boundsInRoot.center
                onRoot().performMouseInput { rightClick(at) }
                waitForMenu(state)

                assertEquals(ActionGroups.EDITOR_TAB_POPUP_MENU, state.contextMenu?.groupId)
                assertEquals("alpha.kt", session.editors.selected?.file?.name)
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.CLOSE_TABS_TO_THE_RIGHT.name)
                    .performClick()
                waitUntil(timeoutMillis = 5_000) {
                    session.editors.openFiles.map { it.file.name } == listOf("alpha.kt")
                }
                assertEquals("alpha.kt", session.editors.selected?.file?.name)
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a commit file row selects it and leaves its check box`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { state.session!!.commit.rows.size == 2 }
            val other = session.commit.rows.first { it.path.fileName.toString() == "Other.kt" }
            val checkedBefore = session.commit.inclusion.isChecked(other)
            // The window selects the first row; the right click must move the selection.
            assertEquals("Main.kt", session.commit.selectedRow()?.path?.fileName.toString())

            commitRow("Other.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)

            assertEquals(ActionGroups.CHANGES_VIEW_POPUP_MENU, state.contextMenu?.groupId)
            assertEquals(other.path, session.commit.selectedPath)
            assertEquals(checkedBefore, session.commit.inclusion.isChecked(other))
            assertNull(state.diffRequest)

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a control right click on a commit check box leaves its check alone`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 2 }
            val other = session.commit.rows.first { it.path.fileName.toString() == "Other.kt" }
            val checkedBefore = session.commit.inclusion.isChecked(other)

            val listLeft = onNodeWithTag(COMMIT_LIST_TAG).fetchSemanticsNode().boundsInRoot.left
            val rowY = commitRow("Other.kt").fetchSemanticsNode().boundsInRoot.center.y
            val checkBox = Offset(listLeft + with(density) { 18.dp.toPx() }, rowY)

            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onRoot().performMouseInput { click(checkBox) }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            waitForMenu(state)

            assertEquals(checkedBefore, session.commit.inclusion.isChecked(other))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the commit window's show diff and jump to source rows run their actions`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { state.session!!.commit.rows.size == 2 }
            val other = session.commit.rows.first { it.path.fileName.toString() == "Other.kt" }

            // A7: Show Diff opens the selected row's working-tree diff, and its Esc returns to
            // the list the same way the Commit window's own ⌘D does.
            commitRow("Other.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.SHOW_DIFF.name).performClick()
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }

            // A7: Jump to Source is openCommitRow, which F4 runs.
            commitRow("Other.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitUntil(timeoutMillis = 5_000) {
                session.editors.selected?.file?.path == other.path
            }
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the tree offers a run for a test source directory and none for main sources`() =
        runComposeUiTest {
            val root = temp.resolve("tree-run-project")
            Files.createDirectories(root.resolve("app/src/test/kotlin/sample/orders"))
            Files.createDirectories(root.resolve("app/src/main/kotlin/sample"))
            Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
            Files.writeString(root.resolve("build.gradle.kts"), "")
            Files.writeString(root.resolve("app/build.gradle.kts"), "")
            Files.writeString(
                root.resolve("app/src/test/kotlin/sample/orders/OrderServiceTest.kt"),
                "package sample.orders\n\nclass OrderServiceTest {\n    @Test\n    fun placesOrder() {\n    }\n}\n",
            )
            Files.writeString(root.resolve("app/src/main/kotlin/sample/Orders.kt"), "package sample\n")
            val state = AppState()
            try {
                state.openFolder(root)
                val session = requireNotNull(state.session)
                session.tree.expand(VFile(root.resolve("app")))
                session.tree.expand(VFile(root.resolve("app/src")))
                setContent { ContextMenuHarness(state) }
                waitForIdle()

                session.tree.select(VFile(root.resolve("app/src/test")))
                state.focus = KeyContext.PROJECT_TREE
                val expected = requireNotNull(contextRun(state)).name

                rightClick("test")
                waitForMenu(state)
                val runRow = requireNotNull(state.contextMenu).rows
                    .filterIsInstance<MenuRow.Action>()
                    .firstOrNull { it.action == ActionId.RUN_CONTEXT }
                assertEquals("Run '$expected'", runRow?.text)
                assertTrue(runRow?.enabled == true)

                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }

                rightClick("main")
                waitForMenu(state)
                assertTrue(
                    requireNotNull(state.contextMenu).rows
                        .filterIsInstance<MenuRow.Action>()
                        .none { it.action == ActionId.RUN_CONTEXT },
                )
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a git log row opens its menu without opening the diff`() = runComposeUiTest {
        val repo = threeCommits()
        val state = AppState()
        try {
            openRepository(repo, state)
            state.toggleToolWindow("git", KeyContext.LIST_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Second commit").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Second commit").performMouseInput { rightClick(center) }
            waitForMenu(state)

            assertEquals(ActionGroups.VCS_LOG_CONTEXT_MENU, state.contextMenu?.groupId)
            assertNull(state.diffRequest)

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(GIT_LOG_TAG).assertIsFocused() }.isSuccess
            }

            onNodeWithText("Second commit").performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.SHOW_DIFF.name).performClick()
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            assertTrue(requireNotNull(state.diffRequest).title.endsWith("Second commit"))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a run test row opens the test tree menu and escape returns`() =
        runComposeUiTest {
            val root = temp.resolve("run-menu-project")
            Files.createDirectories(root.resolve("src/test/kotlin/demo"))
            Files.writeString(root.resolve("build.gradle.kts"), "")
            val state = AppState()
            try {
                state.openFolder(root)
                val session = requireNotNull(state.session)
                val manager = session.project.service<RunManager>()
                val events = "##stride[test start class='demo.DemoTest' name='adds']\n" +
                    "##stride[test pass class='demo.DemoTest' name='adds' duration='3']\n"
                manager.starter = ProcessStarter { StartedProcess(FakeProcess(events.toByteArray(), 0), "fake") }
                state.toggleToolWindow("run", KeyContext.RUN_WINDOW)
                setContent { ContextMenuHarness(state) }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.RUN_WINDOW }
                // The run starts after the first composition, as a user's key would.
                manager.run(
                    RunConfiguration(
                        name = "demo.DemoTest",
                        workingDir = root,
                        args = listOf(":test", "--tests", "demo.DemoTest"),
                        kind = RunKind.TEST,
                    ),
                )
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("adds").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("adds").performMouseInput { rightClick(center) }
                waitForMenu(state)

                assertEquals(ActionGroups.TEST_TREE_POPUP_MENU, state.contextMenu?.groupId)
                val rows = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
                // A10: the method row's run is the gutter's filter, built from the tree's Gradle path.
                assertEquals("Run 'demo.DemoTest.adds'", rows.first { it.action == ActionId.RUN_CONTEXT }.text)
                assertTrue(rows.first { it.action == ActionId.RUN_CONTEXT }.enabled)
                // The run finished, so Rerun has a configuration and Stop has nothing to stop.
                assertTrue(rows.first { it.action == ActionId.RERUN }.enabled)
                assertFalse(rows.first { it.action == ActionId.STOP }.enabled)

                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.RUN_WINDOW }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(RUN_WINDOW_TAG).assertIsFocused() }.isSuccess
                }
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on the run console opens the console menu`() = runComposeUiTest {
        val root = temp.resolve("run-console-project")
        Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val manager = session.project.service<RunManager>()
            manager.starter = ProcessStarter { StartedProcess(FakeProcess("hello\n".toByteArray(), 0), "fake") }
            state.toggleToolWindow("run", KeyContext.RUN_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.RUN_WINDOW }
            // The run starts after the first composition, as a user's key would.
            manager.run(
                RunConfiguration(
                    name = "app",
                    workingDir = root,
                    args = listOf("run"),
                    kind = RunKind.APPLICATION,
                ),
            )
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("hello").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("hello").performMouseInput { rightClick(center) }
            waitForMenu(state)

            assertEquals(ActionGroups.CONSOLE_VIEW_POPUP_MENU, state.contextMenu?.groupId)
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.RUN_WINDOW }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on the terminal keeps the selection and its copy matches the key`() =
        runComposeUiTest {
            val root = temp.resolve("terminal-menu-project")
            Files.createDirectories(root)
            val state = AppState()
            try {
                state.openFolder(root)
                val session = requireNotNull(state.session)
                val terminal = session.project.service<TerminalSession>()
                terminal.start(FakeTtyConnector("one\ntwo\nthree\n"))
                // The emulator thread runs in real time; the composition's clock is virtual.
                awaitReal { terminal.lines().getOrNull(2)?.trim() == "three" }
                state.toggleToolWindow("terminal", KeyContext.TERMINAL)
                setContent { ContextMenuHarness(state) }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.TERMINAL }

                val box = onNodeWithTag(TERMINAL_TAG).fetchSemanticsNode().boundsInRoot
                val line = with(density) { DesignTokens.codeLineHeight.toPx() }
                val firstRow = Offset(box.left + 12f, box.top + line / 2f)

                // No selection yet: Copy is disabled and running it leaves the clipboard alone.
                setClipboard("stale")
                onRoot().performMouseInput { rightClick(firstRow) }
                waitForMenu(state)
                assertEquals(ActionGroups.TERMINAL_CONTEXT_MENU, state.contextMenu?.groupId)
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.COPY.name).assertIsNotEnabled()
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.COPY.name).performClick()
                assertEquals("stale", readClipboard())
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(TERMINAL_TAG).assertIsFocused() }.isSuccess
                }

                // A drag selects the first two rows; ⌘C records what the menu's Copy must repeat.
                onNodeWithTag(TERMINAL_TAG).performTouchInput {
                    swipe(
                        start = Offset(12f, line / 2f),
                        end = Offset(80f, line * 1.5f),
                        durationMillis = 200,
                    )
                }
                waitForIdle()
                setClipboard("stale")
                onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
                waitUntil(timeoutMillis = 5_000) { readClipboard()?.contains("one") == true }
                val keyCopy = requireNotNull(readClipboard())
                assertTrue(keyCopy.contains("two"))

                // A5: the right press keeps the selection, so Copy is enabled and copies the same text.
                setClipboard("stale")
                onRoot().performMouseInput { rightClick(firstRow) }
                waitForMenu(state)
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.COPY.name).assertIsEnabled()
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.COPY.name).performClick()
                waitUntil(timeoutMillis = 5_000) { readClipboard() == keyCopy }

                waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.TERMINAL }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(TERMINAL_TAG).assertIsFocused() }.isSuccess
                }
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a bookmark row opens the list menu and jump to source opens it`() =
        runComposeUiTest {
            val root = temp.resolve("bookmark-menu-project")
            Files.createDirectories(root)
            Files.writeString(root.resolve("A.kt"), "one\n")
            Files.writeString(root.resolve("B.kt"), "alpha\nbeta\n")
            val state = AppState()
            try {
                state.openFolder(root)
                val session = requireNotNull(state.session)
                session.editors.openFile(VFile(root.resolve("A.kt")))
                val target = root.resolve("B.kt").toAbsolutePath().normalize()
                session.bookmarks.toggle(Bookmark(target, "B.kt", 1, "beta"))
                state.toggleToolWindow("bookmarks", KeyContext.LIST_WINDOW)
                setContent { ContextMenuHarness(state) }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("B.kt:2").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("B.kt:2").performMouseInput { rightClick(center) }
                waitForMenu(state)

                assertEquals(ActionGroups.BOOKMARKS_TOOL_WINDOW_POPUP_MENU, state.contextMenu?.groupId)
                // A5: the right press selects the row; it never opens it.
                assertEquals("A.kt", session.editors.selected?.file?.name)

                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(LIST_WINDOW_TAG).assertIsFocused() }.isSuccess
                }

                onNodeWithText("B.kt:2").performMouseInput { rightClick(center) }
                waitForMenu(state)
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
                waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "B.kt" }
                assertEquals(1, (session.editors.selected as TextFileEditor).value.text
                    .take((session.editors.selected as TextFileEditor).value.selection.start).count { it == '\n' })
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a problems row opens it and shows the quick fixes`() = runComposeUiTest {
        val root = temp.resolve("problems-menu-project")
        Files.createDirectories(root)
        val text = "fun main() {\n    println(1)\n}\n"
        val file = root.resolve("Main.kt")
        Files.writeString(file, text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            session.project.service<ProblemsModel>().publishBuild(
                listOf(
                    CompilerMessage(
                        file.toAbsolutePath().normalize(),
                        1,
                        4,
                        ProblemSeverity.ERROR,
                        "Unresolved reference: println",
                    ),
                ),
            )
            state.toggleToolWindow("problems", KeyContext.LIST_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) { editor.problems.isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Unresolved reference", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            editor.updateValue(TextFieldValue(text, TextRange(0)))

            onNodeWithText("Unresolved reference", substring = true).performMouseInput { rightClick(center) }
            waitForMenu(state)

            assertEquals(ActionGroups.PROBLEMS_VIEW_TREE_POPUP, state.contextMenu?.groupId)
            // A5: the right press does not open the problem.
            assertEquals(0, editor.value.selection.start)

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(LIST_WINDOW_TAG).assertIsFocused() }.isSuccess
            }

            // A7: Show Quick-Fixes opens the problem and then the ⌥↩ popup at it.
            onNodeWithText("Unresolved reference", substring = true).performMouseInput { rightClick(center) }
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.SHOW_INTENTION_ACTIONS.name).performClick()
            waitUntil(timeoutMillis = 5_000) { state.contextActionsVisible }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click below the last tree row keeps the selection and opens its menu`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val source = session.tree.root.path.resolve("source.txt")

            rightClick("source.txt")
            waitForMenu(state)
            assertEquals(source, session.tree.selected)
            assertNull(session.editors.selected)
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }

            val panel = onNodeWithTag(PROJECT_TREE_TAG).fetchSemanticsNode().boundsInRoot
            onRoot().performMouseInput { rightClick(Offset(panel.left + 40f, panel.bottom - 12f)) }
            waitForMenu(state)

            assertEquals(source, session.tree.selected)
            assertNull(session.editors.selected)
            assertEquals(ActionGroups.PROJECT_VIEW_POPUP_MENU, state.contextMenu?.groupId)
            // The rows resolved against the tree's selection: a file target keeps Jump to Source.
            val editSource = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
                .first { it.action == ActionId.EDIT_SOURCE }
            assertTrue(editSource.enabled)

            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.path == source }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a meta right click on an identifier opens the editor menu instead of navigating`() = runComposeUiTest {
        val content = "val order = OrderService()\n"
        val (state, editor) = openTextProject("Main.kt", content)
        try {
            val root = requireNotNull(editor.file.path.parent)
            Files.writeString(root.resolve("Other.kt"), "class OrderService\n")
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Other.kt")))
            session.editors.openFile(editor.file)
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            editor.updateValue(TextFieldValue(content, TextRange(0)))
            waitForIdle()
            val name = content.indexOf("OrderService")
            val field = onNode(hasSetTextAction())
            val box = field.textLayout().getBoundingBox(name)

            onRoot().performKeyInput { keyDown(Key.MetaLeft) }
            field.performMouseInput { rightClick(Offset(box.center.x, box.center.y)) }
            onRoot().performKeyInput { keyUp(Key.MetaLeft) }
            waitForMenu(state)

            assertEquals(ActionGroups.EDITOR_POPUP_MENU, state.contextMenu?.groupId)
            // The press kept the tab and put the caret where it landed, not at the declaration.
            assertEquals("Main.kt", session.editors.selected?.file?.name)
            assertEquals(2, session.editors.openFiles.size)
            assertTrue(editor.value.selection.start in name..name + "OrderService".length)
            onNodeWithText("Select All").assertDoesNotExist()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a meta click on an empty spot stays silent while the key reports it`() = runComposeUiTest {
        val content = "val order = OrderService()\n\n"
        val (state, editor) = openTextProject("Main.kt", content)
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val field = onNode(hasSetTextAction())
            field.performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            val blankAt = content.indexOf('\n') + 1
            editor.updateValue(TextFieldValue(content, TextRange(blankAt)))
            waitForIdle()
            val blank = field.textLayout().getBoundingBox(blankAt)
            state.statusMessage = "No identifier at the caret"

            onRoot().performKeyInput { keyDown(Key.MetaLeft) }
            field.performMouseInput { click(Offset(blank.center.x, blank.center.y)) }
            onRoot().performKeyInput { keyUp(Key.MetaLeft) }
            waitForIdle()

            assertNull(state.statusMessage)

            editor.updateValue(TextFieldValue(content, TextRange(blankAt)))
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.B) } }
            waitUntil(timeoutMillis = 5_000) { state.statusMessage == "No identifier at the caret" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a tab's close button opens that tab's menu without closing it`() = runComposeUiTest {
        val root = temp.resolve("tab-close-project")
        Files.createDirectories(root)
        for (name in listOf("alpha.kt", "beta.kt", "gamma.kt")) Files.writeString(root.resolve(name), "$name\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.projectTreeVisible = false
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("alpha.kt")))
            session.editors.openFile(VFile(root.resolve("beta.kt")))
            session.editors.openFile(VFile(root.resolve("gamma.kt")))
            setContent { ContextMenuHarness(state) }
            waitForIdle()

            val tab = onNode(hasText("beta.kt") and hasClickAction())
            val bounds = tab.fetchSemanticsNode().boundsInRoot
            val close = Offset(bounds.right - with(density) { 10.dp.toPx() }, bounds.center.y)
            onRoot().performMouseInput { rightClick(close) }
            waitForMenu(state)

            assertEquals(ActionGroups.EDITOR_TAB_POPUP_MENU, state.contextMenu?.groupId)
            assertEquals("beta.kt", session.editors.selected?.file?.name)
            assertEquals(
                listOf("alpha.kt", "beta.kt", "gamma.kt"),
                session.editors.openFiles.map { it.file.name },
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a right click on a commit group row keeps the selected file row`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 2 }
            assertEquals("Main.kt", session.commit.selectedRow()?.path?.fileName.toString())

            onNodeWithText("Changes (2)").performMouseInput { rightClick(center) }
            waitForMenu(state)

            assertEquals(ActionGroups.CHANGES_VIEW_POPUP_MENU, state.contextMenu?.groupId)
            assertEquals("Main.kt", session.commit.selectedRow()?.path?.fileName.toString())
            val rows = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
            assertTrue(rows.first { it.action == ActionId.SHOW_DIFF }.enabled)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a stale commit selection disables every row of the group menu`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 2 }

            // The selected file leaves the list while `selectedPath` still names it.
            val otherPath = repo.root.resolve("src/Other.kt")
            commitRow("Other.kt").performClick()
            waitUntil(timeoutMillis = 5_000) { session.commit.selectedPath == otherPath }
            repo.git("checkout", "--", "src/Other.kt")
            runBlocking { session.git.refreshNow() }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 1 }
            assertNull(session.commit.selectedRow())

            onNodeWithText("Changes (1)").performMouseInput { rightClick(center) }
            waitForMenu(state)
            val stale = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
            for (action in listOf(ActionId.EDIT_SOURCE, ActionId.ROLLBACK_CHANGES, ActionId.SHOW_DIFF)) {
                assertFalse(stale.first { it.action == action }.enabled, action.name)
                onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + action.name).assertIsNotEnabled()
            }
            closeMenu(state)
            assertTrue(session.editors.openFiles.isEmpty())

            // A file row the list still holds enables all three, and Jump to Source opens it.
            commitRow("Main.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            val live = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
            for (action in listOf(ActionId.EDIT_SOURCE, ActionId.ROLLBACK_CHANGES, ActionId.SHOW_DIFF)) {
                assertTrue(live.first { it.action == action }.enabled, action.name)
            }
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Main.kt" }
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a commit menu the editor opened judges every row against the window`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(repo.root.resolve("src/Other.kt")))
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 2 }

            // The user presses Esc in the window: the keyboard moves to the editor, the window stays.
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            fun assertKeyboardInEditor() {
                assertEquals(KeyContext.EDITOR, state.focus)
                assertTrue(state.toolWindows.visible)
                assertEquals("commit", state.toolWindows.activeId)
            }

            fun assertEveryRow(enabled: Boolean) {
                val rows = requireNotNull(state.contextMenu).rows.filterIsInstance<MenuRow.Action>()
                for (action in listOf(ActionId.EDIT_SOURCE, ActionId.ROLLBACK_CHANGES, ActionId.SHOW_DIFF)) {
                    assertEquals(enabled, rows.first { it.action == action }.enabled, action.name)
                    val node = onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + action.name)
                    if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
                }
            }

            fun openGroupMenu() {
                assertKeyboardInEditor()
                onNodeWithText("Changes (2)").performMouseInput { rightClick(center) }
                waitForMenu(state)
            }

            fun backToEditor() {
                assertEquals(KeyContext.COMMIT_WINDOW, state.focus)
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            }

            // (iii) A file row keeps the judgment: the editor owns the keyboard, Jump to Source opens it.
            assertKeyboardInEditor()
            commitRow("Main.kt").performMouseInput { rightClick(center) }
            waitForMenu(state)
            assertEveryRow(true)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Main.kt" }
            assertEquals(KeyContext.EDITOR, state.focus)

            // Select the other row, so (i)'s Jump to Source has a different file to open.
            commitRow("Other.kt").performClick()
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
            assertEquals("Other.kt", session.commit.selectedRow()?.path?.fileName.toString())
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            // (i) A live selection: all three rows are enabled, and each one carries out its effect.
            openGroupMenu()
            assertEveryRow(true)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.EDIT_SOURCE.name).performClick()
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Other.kt" }
            assertEquals(KeyContext.EDITOR, state.focus)
            assertNull(state.contextMenu)

            openGroupMenu()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.SHOW_DIFF.name).performClick()
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            backToEditor()

            openGroupMenu()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.ROLLBACK_CHANGES.name).performClick()
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            backToEditor()

            // (ii) A selection the list no longer holds disables every row from the editor too.
            repo.git("checkout", "--", "src/Other.kt")
            runBlocking { session.git.refreshNow() }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 1 }
            assertNull(session.commit.selectedRow())
            assertKeyboardInEditor()
            onNodeWithText("Changes (1)").performMouseInput { rightClick(center) }
            waitForMenu(state)
            assertEveryRow(false)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `two quick right clicks on a commit row open no editor`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            openRepository(repo, state)
            val session = requireNotNull(state.session)
            state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) { session.commit.rows.size == 2 }

            commitRow("Other.kt").performMouseInput {
                rightClick(center)
                advanceEventTime(170)
                rightClick(center)
            }
            waitForIdle()

            assertTrue(session.editors.openFiles.isEmpty())
            assertNull(state.diffRequest)
            closeMenu(state)

            // The same row with the primary button: the second press reaches the row's double click.
            commitRow("Other.kt").performMouseInput { doubleClick(center) }
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Other.kt" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `two quick right clicks on a list row open no editor`() = runComposeUiTest {
        val root = temp.resolve("quick-right-list-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("A.kt"), "one\n")
        Files.writeString(root.resolve("B.kt"), "alpha\nbeta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.bookmarks.toggle(Bookmark(root.resolve("B.kt").toAbsolutePath().normalize(), "B.kt", 1, "beta"))
            state.toggleToolWindow("bookmarks", KeyContext.LIST_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("B.kt:2").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("B.kt:2").performMouseInput {
                rightClick(center)
                advanceEventTime(170)
                rightClick(center)
            }
            waitForIdle()

            assertEquals("A.kt", session.editors.selected?.file?.name)
            assertEquals(listOf("A.kt"), session.editors.openFiles.map { it.file.name })
            closeMenu(state)

            // The same row with the primary button: the second press reaches the row's double click.
            onNodeWithText("B.kt:2").performMouseInput { doubleClick(center) }
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "B.kt" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `two quick right clicks on a git log row open no diff`() = runComposeUiTest {
        val repo = threeCommits()
        val state = AppState()
        try {
            openRepository(repo, state)
            state.toggleToolWindow("git", KeyContext.LIST_WINDOW)
            setContent { ContextMenuHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Second commit").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Second commit").performMouseInput {
                rightClick(center)
                advanceEventTime(170)
                rightClick(center)
            }
            waitForIdle()

            assertTrue(heldFor(500) { state.diffRequest == null }, "the right clicks opened a diff")
            closeMenu(state)

            // The same row with the primary button: the second press reaches the row's double click.
            onNodeWithText("Second commit").performMouseInput { doubleClick(center) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `paste reports a clipboard source that is gone and clears the clipboard`() = runComposeUiTest {
        val (state, session) = openProject()
        try {
            setContent { ContextMenuHarness(state) }
            waitForIdle()
            val source = session.tree.root.path.resolve("source.txt")
            session.files.copy(source)
            Files.delete(source)

            rightClick("dest")
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).assertIsEnabled()
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).performClick()

            waitUntil(timeoutMillis = 5_000) { state.statusMessage == "source.txt no longer exists" }
            assertNull(session.files.clipboard)

            waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
            rightClick("dest")
            waitForMenu(state)
            onNodeWithTag(CONTEXT_MENU_ROW_TAG_PREFIX + ActionId.PASTE_FILE.name).assertIsNotEnabled()
        } finally {
            state.dispose()
        }
    }

    private fun heldFor(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (!condition()) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun changedRepo(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "alpha\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        repo.write("src/Other.kt", "beta\n")
        return repo
    }

    private fun threeCommits(): TestGitRepo {
        val repo = TestGitRepo.create(temp, "git-menu")
        repo.write("src/Main.kt", "one\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "one\ntwo\n")
        repo.commitAll("Second commit")
        return repo
    }

    private fun openRepository(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val git = requireNotNull(state.session).git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
    }

    private fun awaitReal(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(10)
        assertTrue(condition(), "condition not met before the deadline")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.commitRow(name: String): SemanticsNodeInteraction =
        onNode(hasText(name) and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG)))

    private fun openTextProject(name: String, content: String): Pair<AppState, TextFileEditor> {
        val root = temp.resolve("editor-project")
        Files.createDirectories(root)
        val file = root.resolve(name)
        Files.writeString(file, content)
        val state = AppState()
        state.openFolder(root)
        val editor = requireNotNull(state.session).editors.openFile(VFile(file)) as TextFileEditor
        return state to editor
    }

    private fun editorOf(state: AppState): TextFileEditor =
        requireNotNull(state.session).editors.selected as TextFileEditor

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.rightClickInside(field: SemanticsNodeInteraction, at: Int) {
        val box = field.textLayout().getBoundingBox(at)
        field.performMouseInput { rightClick(Offset(box.center.x, box.center.y)) }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun setClipboard(text: String) {
        SystemTextClipboard.writeText(text)
    }

    private fun readClipboard(): String? = SystemTextClipboard.readText()

    private fun openProject(): Pair<AppState, ProjectSession> {
        val root = temp.resolve("menu-project")
        Files.createDirectories(root.resolve("dest"))
        Files.writeString(root.resolve("dest/nested.txt"), "nested\n")
        Files.writeString(root.resolve("source.txt"), "hello\n")
        val state = AppState()
        state.openFolder(root)
        return state to requireNotNull(state.session)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.rightClick(name: String) {
        onNodeWithText(name).performMouseInput { rightClick(center) }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.closeMenu(state: AppState) {
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { state.contextMenu == null }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.waitForMenu(state: AppState) {
        waitUntil(timeoutMillis = 5_000) { state.contextMenu != null }
        waitForIdle()
    }

    private fun downPressesTo(request: ContextMenuRequest, action: ActionId): Int {
        fun choosable(row: MenuRow) = when (row) {
            is MenuRow.Submenu -> true
            is MenuRow.Action -> row.enabled
            is MenuRow.Command -> row.enabled
            MenuRow.Separator -> false
        }
        val rows = request.rows
        val start = rows.indexOfFirst { choosable(it) }
        val target = rows.indexOfFirst { it is MenuRow.Action && it.action == action }
        assertTrue(start >= 0 && target > start && choosable(rows[target]), "the row is not reachable")
        return (start + 1..target).count { choosable(rows[it]) }
    }
}

@Composable
private fun ContextMenuHarness(state: AppState) {
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
