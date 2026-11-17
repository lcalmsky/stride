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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
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
import stride.app.hierarchy.HierarchyLocation
import stride.app.hierarchy.HierarchyNode
import stride.app.hierarchy.HierarchyTree
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class HierarchyToolWindowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(HierarchyToolWindowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `opening a hierarchy registers and focuses the window with its title and note`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("hierarchy-open-project"))
        Files.writeString(root.resolve("Main.kt"), "class Circle\n")
        val state = AppState()
        try {
            state.openFolder(root)
            assertTrue(state.toolWindows.windows.none { it.id == "hierarchy" })

            state.openHierarchy(tree(HierarchyNode("RootNode")))
            setContent { HierarchyWindowHarness(state) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            assertTrue(state.toolWindows.windows.any { it.id == "hierarchy" })
            assertEquals("hierarchy", state.toolWindows.activeId)
            assertTrue(state.toolWindows.visible)
            onNodeWithText("Type Hierarchy of RootNode").assertIsDisplayed()
            onNodeWithText("Resolved through imports").assertIsDisplayed()
            onNodeWithText("RootNode").assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `right expands a lazy node and left collapses it`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("hierarchy-expand-project"))
        Files.writeString(root.resolve("Main.kt"), "class Circle\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.openHierarchy(
                tree(
                    HierarchyNode(
                        "RootNode",
                        children = { listOf(HierarchyNode("ChildNode")) },
                    ),
                ),
            )
            setContent { HierarchyWindowHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            onNodeWithText("▸ RootNode").assertIsDisplayed()

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("ChildNode").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("▾ RootNode").assertIsDisplayed()

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("ChildNode").fetchSemanticsNodes().isEmpty()
            }
            onNodeWithText("▸ RootNode").assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enter opens the location of a node and keeps the window`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("hierarchy-open-location-project"))
        val content = "package demo\n\nclass OpenTarget\n"
        Files.writeString(root.resolve("OpenTarget.kt"), content)
        Files.writeString(root.resolve("Other.kt"), "class Other\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Other.kt")))
            val offset = content.indexOf("OpenTarget")
            state.openHierarchy(
                tree(
                    HierarchyNode(
                        "OpenTarget",
                        location = HierarchyLocation(root.resolve("OpenTarget.kt"), offset),
                    ),
                ),
            )
            setContent { HierarchyWindowHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) {
                session.editors.selected?.file?.name == "OpenTarget.kt"
            }
            val editor = session.editors.selected as TextFileEditor
            assertEquals(offset, editor.value.selection.start)
            assertTrue(state.toolWindows.visible)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enter flips the expansion of a node without a location`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("hierarchy-toggle-project"))
        Files.writeString(root.resolve("Main.kt"), "class Circle\n")
        val state = AppState()
        try {
            state.openFolder(root)
            state.openHierarchy(
                tree(
                    HierarchyNode(
                        "RootNode",
                        children = { listOf(HierarchyNode("ChildNode")) },
                    ),
                ),
            )
            setContent { HierarchyWindowHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("ChildNode").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("ChildNode").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(state.toolWindows.visible)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape hands the keyboard to the editor and leaves the window visible`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("hierarchy-escape-project"))
        Files.writeString(root.resolve("Main.kt"), "class Circle\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("Main.kt")))
            state.openHierarchy(tree(HierarchyNode("RootNode")))
            setContent { HierarchyWindowHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(state.toolWindows.visible)
            onNode(hasSetTextAction()).assertIsFocused()
        } finally {
            state.dispose()
        }
    }

    private fun tree(vararg roots: HierarchyNode): HierarchyTree =
        HierarchyTree(
            title = "Type Hierarchy of ${roots.firstOrNull()?.text.orEmpty()}",
            note = "Resolved through imports",
            roots = roots.toList(),
        )
}

@Composable
private fun HierarchyWindowHarness(state: AppState) {
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
