package stride.app

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.navigation.Bookmark
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowRow
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class ListToolWindowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ListToolWindowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `bookmarks window arrows and enter open the selected bookmark`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("bookmarks-project"))
        Files.writeString(root.resolve("A.kt"), "one\ntwo\nthree\n")
        Files.writeString(root.resolve("B.kt"), "alpha\nbeta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.bookmarks.toggle(bookmark(root, "A.kt", 1, "two"))
            session.bookmarks.toggle(bookmark(root, "B.kt", 0, "alpha"))

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Two)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "B.kt" }
            val editor = session.editors.selected as TextFileEditor
            assertEquals(0, editor.value.text.take(editor.value.selection.start).count { it == '\n' })
            assertTrue(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `delete removes the selected bookmark and keeps the window visible`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("delete-bookmark-project"))
        Files.writeString(root.resolve("A.kt"), "one\ntwo\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.bookmarks.toggle(bookmark(root, "A.kt", 1, "two"))

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Two)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.Delete) }

            waitUntil(timeoutMillis = 5_000) { session.bookmarks.isEmpty() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("No bookmarks · F3 toggles one").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape hands the keyboard back to the editor and leaves the window visible`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("escape-list-project"))
        Files.writeString(root.resolve("A.kt"), "one\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))
            session.bookmarks.toggle(bookmark(root, "A.kt", 0, "one"))

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Two)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(state.toolWindows.visible)
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `structure selects the entry at the caret and enter moves the caret to it`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("structure-project"))
        val content = "class Alpha {\n    fun uniqueEntry() {}\n}\n"
        Files.writeString(root.resolve("Struct.kt"), content)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Struct.kt"))) as TextFileEditor
            editor.updateValue(TextFieldValue(content, TextRange(content.indexOf("}"))))

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Seven)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("uniqueEntry").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) {
                editor.value.text.take(editor.value.selection.start).count { it == '\n' } == 1
            }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape also leaves an empty find window focused`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("empty-find-project"))
        Files.writeString(root.resolve("A.kt"), "one\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("A.kt")))

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Three)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            onNodeWithText("No results · ⇧⌘F Find in Path, ⌥F7 Find Usages").assertExists()

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(state.toolWindows.visible)
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `moving the selection does not scroll a visible row to the top`() = runComposeUiTest {
        val rows = List(200) { index -> ListWindowRow(AnnotatedString("row $index")) }
        var opened = -1
        setContent {
            Box(Modifier.size(400.dp)) {
                ListToolWindow(
                    rows = rows,
                    focusRequests = 1,
                    onOpen = { opened = it },
                    onEscape = {},
                )
            }
        }
        waitForIdle()

        repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        waitForIdle()
        onNodeWithText("row 0").assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { opened == 3 }

        repeat(60) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        waitForIdle()
        onNodeWithText("row 63").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `f4 and command down open the selected row and command down keeps the selection`() = runComposeUiTest {
        val rows = List(5) { index -> ListWindowRow(AnnotatedString("row $index")) }
        var opened = -1
        setContent {
            Box(Modifier.size(400.dp)) {
                ListToolWindow(
                    rows = rows,
                    focusRequests = 1,
                    onOpen = { opened = it },
                    onEscape = {},
                )
            }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.DirectionDown) } }
        waitUntil(timeoutMillis = 5_000) { opened == 1 }

        opened = -1
        onRoot().performKeyInput { pressKey(Key.F4) }
        waitUntil(timeoutMillis = 5_000) { opened == 1 }
    }

    private fun bookmark(root: Path, name: String, line: Int, lineText: String): Bookmark =
        Bookmark(root.resolve(name).toAbsolutePath().normalize(), name, line, lineText)
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.pressCommand(key: Key) {
    onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
}

@Composable
private fun ListWindowHarness(state: AppState) {
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
