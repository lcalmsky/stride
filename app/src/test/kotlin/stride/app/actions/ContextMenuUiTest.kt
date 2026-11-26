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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
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
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.PROJECT_TREE_TAG
import stride.app.ui.theme.DesignTokens
import stride.core.services.Application
import stride.core.vfs.VFile

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
    private fun ComposeUiTest.waitForMenu(state: AppState) {
        waitUntil(timeoutMillis = 5_000) { state.contextMenu != null }
        waitForIdle()
    }

    private fun downPressesTo(request: ContextMenuRequest, action: ActionId): Int {
        fun choosable(row: MenuRow) = when (row) {
            is MenuRow.Submenu -> true
            is MenuRow.Action -> row.enabled
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
