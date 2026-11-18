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
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
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
class CommitAndPushUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(CommitAndPushUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option command K in the window commits and opens the push popup`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")

            pressCommitAndPush()

            waitUntil(timeoutMillis = 15_000) { state.pushRequest != null }
            assertEquals("Change", repo.git("log", "-1", "--format=%s").trim())
            assertEquals(listOf("Change"), state.pushRequest!!.plan.outgoing.map { it.subject })

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.pushRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `return in the push popup pushes the commit just made`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")
            pressCommitAndPush()
            waitUntil(timeoutMillis = 15_000) { state.pushRequest != null }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Pushed 1 commits to origin/main" }
            assertNull(state.pushRequest)
            assertEquals(
                repo.git("rev-parse", "HEAD").trim(),
                repo.git(
                    "--git-dir",
                    repo.root.resolveSibling("${repo.root.fileName}-origin.git").toString(),
                    "rev-parse",
                    "main",
                ).trim(),
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a repository without a remote commits and puts the refusal after the commit`() = runComposeUiTest {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")

            pressCommitAndPush()

            waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Committed 1 files to main: ") == true }
            val short = repo.git("rev-parse", "--short", "HEAD").trim()
            val subject = repo.git("log", "-1", "--format=%s").trim()
            waitUntil(timeoutMillis = 5_000) {
                state.statusMessage == "Committed 1 files to main: $short $subject; No upstream: set one in the terminal"
            }
            assertNull(state.pushRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an empty message refuses and opens nothing`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val head = repo.git("rev-parse", "HEAD").trim()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }

            pressCommitAndPush()

            waitUntil(timeoutMillis = 5_000) { state.statusMessage == "Enter a commit message" }
            assertTrue(eventually { state.pushRequest == null })
            assertEquals(head, repo.git("rev-parse", "HEAD").trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option command K in the editor opens the window without committing`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val head = repo.git("rev-parse", "HEAD").trim()
        val state = AppState()
        try {
            open(repo, state)
            state.focusSelectedEditor()
            setContent { KeyHarness(state) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            pressCommitAndPush()

            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            assertEquals(head, repo.git("rev-parse", "HEAD").trim())
            assertNull(state.pushRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the commit and push capsule commits and opens the push popup`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_AND_PUSH_CAPSULE_TAG).assertIsEnabled() }.isSuccess
            }

            onNodeWithTag(COMMIT_AND_PUSH_CAPSULE_TAG).performClick()

            waitUntil(timeoutMillis = 15_000) { state.pushRequest != null }
            assertEquals("Change", repo.git("log", "-1", "--format=%s").trim())
            assertEquals(listOf("Change"), state.pushRequest!!.plan.outgoing.map { it.subject })
        } finally {
            state.dispose()
        }
    }

    private fun repoWithUpstream(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        repo.write("src/Main.kt", "second\n")
        return repo
    }

    private fun open(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        session.editors.openFile(VFile(repo.root.resolve("src/Main.kt")))
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommitAndPush() {
        onRoot().performKeyInput {
            withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.K) } }
        }
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
