package stride.app.actions

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
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
