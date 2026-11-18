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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class PushUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(PushUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the popup shows the upstream branch and enter pushes the outgoing commit`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { PushHarness(state) }

            pressPush()
            waitUntil(timeoutMillis = 10_000) { state.pushRequest != null }
            onNodeWithText("main → origin/main").assertExists()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Second commit").fetchSemanticsNodes().isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Pushed 1 commits to origin/main" }
            assertNull(state.pushRequest)
            assertEquals(
                repo.git("rev-parse", "HEAD").trim(),
                repo.git("--git-dir", repo.root.resolveSibling("${repo.root.fileName}-origin.git").toString(), "rev-parse", "main").trim(),
            )

            pressPush()
            waitUntil(timeoutMillis = 10_000) { state.statusMessage == "Nothing to push" }
            assertNull(state.pushRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a branch without an upstream says so and pushes it with an upstream`() = runComposeUiTest {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.bareRemote()
        val state = AppState()
        try {
            open(repo, state)
            setContent { PushHarness(state) }

            pressPush()
            waitUntil(timeoutMillis = 10_000) { state.pushRequest != null }
            onNodeWithText("main → origin/main").assertExists()
            onNodeWithText("new branch").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Pushed 1 commits to origin/main" }
            assertEquals("origin/main", repo.git("rev-parse", "--abbrev-ref", "@{upstream}").trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the popup and leaves the remote unchanged`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val remoteBefore = repo.git(
            "--git-dir",
            repo.root.resolveSibling("${repo.root.fileName}-origin.git").toString(),
            "rev-parse",
            "main",
        ).trim()
        val state = AppState()
        try {
            open(repo, state)
            setContent { PushHarness(state) }

            pressPush()
            waitUntil(timeoutMillis = 10_000) { state.pushRequest != null }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Second commit").fetchSemanticsNodes().isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.pushRequest == null }
            assertEquals(
                remoteBefore,
                repo.git(
                    "--git-dir",
                    repo.root.resolveSibling("${repo.root.fileName}-origin.git").toString(),
                    "rev-parse",
                    "main",
                ).trim(),
            )
            assertTrue(state.statusMessage == null, state.statusMessage ?: "")
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
        repo.commitAll("Second commit")
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
    private fun ComposeUiTest.pressPush() {
        onRoot().performKeyInput {
            withKeyDown(Key.MetaLeft) {
                withKeyDown(Key.ShiftLeft) {
                    pressKey(Key.K)
                }
            }
        }
    }
}

@Composable
private fun PushHarness(state: AppState) {
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
