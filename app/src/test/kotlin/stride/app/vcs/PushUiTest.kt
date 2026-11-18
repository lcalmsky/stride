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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape from the push popup gives the commit window the keyboard back`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { PushHarness(state) }

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.K) } }
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }

            pressPush()
            waitUntil(timeoutMillis = 10_000) { state.pushRequest != null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.pushRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            assertNull(state.statusMessage)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `two returns in the push popup push once`() = runComposeUiTest {
        val repo = repoWithUpstream()
        val state = AppState()
        val gate = CountDownLatch(1)
        try {
            open(repo, state)
            val session = requireNotNull(state.session)
            val counting = GatedPushExecutor(CliGitExecutor(TEST_GIT, repo.environment), gate)
            session.git.executor = counting
            val plan = runBlocking { session.git.pushPlan() } as PushPlan.Ready
            // The popup renders on its own so its close does not remove it between the two keys.
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    PushPopup(PushRequest(plan), state, onClose = {})
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("↵ Push").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput {
                pressKey(Key.Enter)
                pressKey(Key.Enter)
            }

            assertTrue(eventually { counting.pushes.get() == 1 })
            assertFalse(
                eventually(1_500) { state.statusMessage?.startsWith("Git is busy") == true },
                "the second Enter must not start a second write: ${state.statusMessage}",
            )
            gate.countDown()
            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Pushed 1 commits to origin/main" }
            assertEquals(1, counting.pushes.get())
        } finally {
            gate.countDown()
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the popup names the commits the remote has while the branch is behind`() = runComposeUiTest {
        val plan = PushPlan.Ready(
            branch = "main",
            remote = "origin",
            remoteRef = "refs/heads/main",
            newBranch = false,
            outgoing = emptyList(),
            moreOutgoing = 0,
            behind = 2,
        )
        val state = AppState()
        try {
            setContent { PushPopup(PushRequest(plan), state, onClose = {}) }

            onNodeWithText("The remote has 2 commits you don't have; the push will be rejected").assertExists()
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a detached head and several remotes without origin refuse the push`() {
        val detached = TestGitRepo.create(temp)
        detached.write("src/Main.kt", "first\n")
        detached.commitAll("Initial commit")
        detached.git("checkout", "--detach")
        val state = AppState()
        try {
            open(detached, state)
            openPush(state)
            assertTrue(eventually { state.statusMessage == "Push needs a branch: HEAD is detached" }, state.statusMessage)
            assertNull(state.pushRequest)
        } finally {
            state.dispose()
        }

        val several = TestGitRepo.create(temp, name = "web-dashboard")
        several.write("src/Main.kt", "first\n")
        several.commitAll("Initial commit")
        several.bareRemote("one")
        several.bareRemote("two")
        val other = AppState()
        try {
            open(several, other)
            openPush(other)
            assertTrue(eventually { other.statusMessage == "No upstream: set one in the terminal" }, other.statusMessage)
            assertNull(other.pushRequest)
        } finally {
            other.dispose()
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

/** Counts `git push` runs and holds the first one until [gate] opens, so a second request overlaps. */
private class GatedPushExecutor(private val inner: GitExecutor, private val gate: CountDownLatch) : GitExecutor {
    val pushes = AtomicInteger()

    override fun run(command: GitCommand): GitResult {
        if (command.args.firstOrNull() == "push") {
            pushes.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
        }
        return inner.run(command)
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
