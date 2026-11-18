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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
class CommitWindowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(CommitWindowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command 0 toggles the Commit window through the real keymap`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("api-server"))
        Files.writeString(root.resolve("Main.kt"), "val x = 1\n")
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { KeyHarness(state) }

            pressCommand(Key.Zero)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }

            pressCommand(Key.Zero)
            waitUntil(timeoutMillis = 5_000) { !state.toolWindows.visible }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command K opens the window with the message field focused`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)

            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `space, command D, command enter and escape work in the window`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(repo.root.resolve("src/Main.kt")))
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.activeId == "commit" && state.toolWindows.visible }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            onNodeWithTag(COMMIT_CAPSULE_TAG).assertIsNotEnabled()

            onNode(hasText("Main.kt") and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG))).performClick()
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.session!!.commit.checkedCount() == 0 }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.session!!.commit.checkedCount() == 1 }

            pressCommand(Key.D)
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            assertEquals("src/Main.kt  HEAD ↔ Local", state.diffRequest?.title)
            state.diffRequest = null

            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_CAPSULE_TAG).assertIsEnabled() }.isSuccess
            }

            pressCommand(Key.Enter)
            waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Committed 1 files to main: ") == true }
            waitUntil(timeoutMillis = 5_000) { state.session!!.commit.message.isEmpty() }
            waitUntil(timeoutMillis = 5_000) { !commitRowsShown() }

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertTrue(session.editors.selected is TextFileEditor)
        } finally {
            state.dispose()
        }
    }

    private fun changedRepo(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        return repo
    }

    private fun open(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val git = requireNotNull(state.session).git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.commitRowsShown(): Boolean =
        onAllNodes(hasText("Main.kt") and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG)))
            .fetchSemanticsNodes()
            .isNotEmpty()
}

@Composable
private fun KeyHarness(state: AppState) {
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
