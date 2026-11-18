package stride.app.vcs

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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
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
class VcsPopupUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(VcsPopupUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the popup lists the six commands with their shortcuts and five toggles the commit window`() =
        runComposeUiTest {
            withRepo("popup-shortcuts") { state, _ ->
                setContent { VcsHarness(state) }
                waitForIdle()
                openVcsPopup(state)
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Commit…").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                for (text in listOf("Commit…", "Push…", "Rollback Lines", "Show Diff", "Commit Window", "Git Log")) {
                    onNodeWithText(text).assertIsDisplayed()
                }
                for (shortcut in listOf("⌘K", "⇧⌘K", "⌥⌘Z", "⌘D", "⌘0", "⌘9")) {
                    onNodeWithText(shortcut).assertIsDisplayed()
                }
                onNodeWithText("main").assertIsDisplayed()

                onRoot().performKeyInput { pressKey(Key.Five) }

                waitUntil(timeoutMillis = 5_000) {
                    state.toolWindows.visible && state.toolWindows.activeId == "commit"
                }
                assertNull(state.vcsPopup)
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `show diff does nothing without an editor or a change`() = runComposeUiTest {
        withRepo("popup-dimmed") { state, _ ->
            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.Four) }
            waitForIdle()
            assertNull(state.diffRequest)
            assertTrue(state.vcsPopup != null)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup == null }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `show diff opens the working tree diff once the editor's changes are known`() = runComposeUiTest {
        withRepo("popup-show-diff") { state, repo ->
            val file = repo.write("src/Main.kt", "alpha\n")
            repo.commitAll("Initial commit")
            val git = state.session!!.git
            runBlocking { git.refreshNow() }
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("beta\n")

            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) {
                state.vcsPopup?.editorRowsEnabled == true
            }
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.Four) }

            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            assertEquals("src/Main.kt  HEAD ↔ Local", state.diffRequest!!.title)
            assertNull(state.vcsPopup)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `outside a repository the popup is one dimmed row`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("plain-project"))
        val state = AppState()
        try {
            state.openFolder(root)
            state.session!!.git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = Files.createDirectories(temp.resolve("plain-home"))),
            )
            runBlocking { state.session!!.git.refreshNow() }

            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Not a Git repository").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Not a Git repository").assertIsDisplayed()
            assertTrue(onAllNodesWithText("Commit…").fetchSemanticsNodes().isEmpty())

            onRoot().performKeyInput { pressKey(Key.One) }
            waitForIdle()
            assertTrue(state.vcsPopup != null)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `without git the popup shows the error instead of the repository message`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("no-git-project"))
        val state = AppState()
        try {
            state.openFolder(root)
            val git = state.session!!.git
            git.executor = MissingGitExecutor
            runBlocking { git.refreshNow() }
            assertEquals("Git is not installed", git.snapshot.error)

            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Git is not installed").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(onAllNodesWithText("Not a Git repository").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Commit…").fetchSemanticsNodes().isEmpty())

            onRoot().performKeyInput { pressKey(Key.One) }
            waitForIdle()
            assertTrue(state.vcsPopup != null)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a numpad digit runs its row`() = runComposeUiTest {
        withRepo("popup-numpad") { state, _ ->
            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Commit…").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.NumPad5) }

            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.toolWindows.activeId == "commit"
            }
            assertNull(state.vcsPopup)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `arrows move the selection and enter runs the row`() = runComposeUiTest {
        withRepo("popup-keys") { state, _ ->
            setContent { VcsHarness(state) }
            waitForIdle()
            openVcsPopup(state)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Commit…").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) {
                state.statusMessage == "No upstream: set one in the terminal"
            }
            assertNull(state.vcsPopup)
            assertFalse(state.toolWindows.visible && state.toolWindows.activeId == "commit")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control V closes back onto the commit window's list when it had the keyboard`() = runComposeUiTest {
        withRepo("popup-return-commit") { state, repo ->
            repo.write("src/Main.kt", "first\n")
            repo.commitAll("Initial commit")
            repo.write("src/Main.kt", "second\n")
            runBlocking { state.session!!.git.refreshNow() }

            setContent { VcsHarness(state) }
            waitForIdle()
            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.toolWindows.activeId == "commit"
            }
            waitUntil(timeoutMillis = 5_000) { commitRowShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) {
                state.vcsPopup != null && state.focus == KeyContext.CONTEXT_POPUP
            }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.vcsPopup == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control V closes back onto the git window's log when it had the keyboard`() = runComposeUiTest {
        withRepo("popup-return-git") { state, repo ->
            repo.write("src/Main.kt", "one\n")
            repo.commitAll("Initial commit")
            runBlocking { state.session!!.git.refreshNow() }

            setContent { VcsHarness(state) }
            waitForIdle()
            pressCommand(Key.Nine)
            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.toolWindows.activeId == "git"
            }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Initial commit").fetchSemanticsNodes().isNotEmpty()
            }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) {
                state.vcsPopup != null && state.focus == KeyContext.CONTEXT_POPUP
            }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.vcsPopup == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            // The log holds the keyboard again: Enter opens the row it still has selected.
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            assertTrue(requireNotNull(state.diffRequest).title.endsWith("Initial commit"))
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control V from the editor returns to the editor`() = runComposeUiTest {
        withRepo("popup-return-editor") { state, repo ->
            val file = repo.write("src/Main.kt", "alpha\n")
            repo.commitAll("Initial commit")
            runBlocking { state.session!!.git.refreshNow() }
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(file))
            state.focusSelectedEditor()

            setContent { VcsHarness(state) }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) {
                state.vcsPopup != null && state.focus == KeyContext.CONTEXT_POPUP
            }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.vcsPopup == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(session.editors.selected is TextFileEditor)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control V then 1 leaves the keyboard in the commit window's message field`() = runComposeUiTest {
        withRepo("popup-return-commit-row") { state, repo ->
            repo.write("src/Main.kt", "first\n")
            repo.commitAll("Initial commit")
            repo.write("src/Main.kt", "second\n")
            runBlocking { state.session!!.git.refreshNow() }

            setContent { VcsHarness(state) }
            waitForIdle()
            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Commit…").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.One) }

            waitUntil(timeoutMillis = 5_000) {
                state.toolWindows.visible && state.toolWindows.activeId == "commit"
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            assertNull(state.vcsPopup)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `commit from the popup over the commit window moves the keyboard to the message field`() = runComposeUiTest {
        withRepo("popup-return-commit-window-row") { state, repo ->
            repo.write("src/Main.kt", "first\n")
            repo.commitAll("Initial commit")
            repo.write("src/Main.kt", "second\n")
            runBlocking { state.session!!.git.refreshNow() }

            setContent { VcsHarness(state) }
            waitForIdle()
            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { commitRowShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) {
                state.vcsPopup != null && state.focus == KeyContext.CONTEXT_POPUP
            }
            onRoot().performKeyInput { pressKey(Key.One) }

            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.commitRowShown(): Boolean =
        onAllNodes(hasText("Main.kt") and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG)))
            .fetchSemanticsNodes()
            .isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressControl(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.withRepo(name: String, block: (AppState, TestGitRepo) -> Unit) {
        val repo = TestGitRepo.create(temp, name)
        val state = AppState()
        try {
            state.openFolder(repo.root)
            state.session!!.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
            runBlocking { state.session!!.git.refreshNow() }
            block(state, repo)
        } finally {
            state.dispose()
        }
    }
}

@Composable
private fun VcsHarness(state: AppState) {
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
