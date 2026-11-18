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
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }

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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape from the update popup returns the keyboard to the message field`() = runComposeUiTest {
        val repo = upstreamWithIncoming()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.updateRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `f4 on the window's diff opens the file with the keyboard in the editor`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }

            pressCommand(Key.D)
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }

            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) { state.diffRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            waitForIdle()
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `two command enters commit once`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val gate = CountDownLatch(1)
        try {
            open(repo, state)
            val session = requireNotNull(state.session)
            val counting = GatedCommitExecutor(CliGitExecutor(TEST_GIT, repo.environment), gate)
            session.git.executor = counting
            setContent { KeyHarness(state) }

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) }
                withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) }
            }

            assertTrue(eventually { counting.commits.get() == 1 })
            assertFalse(
                eventually(1_500) { session.commit.error != null },
                "the second Enter must not reach the service: ${session.commit.error}",
            )
            gate.countDown()
            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage?.startsWith("Committed 1 files to main: ") == true
            }
            assertEquals(1, counting.commits.get())
        } finally {
            gate.countDown()
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `space and return with the message focused insert into it and do not act on the list`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(repo.root.resolve("src/Main.kt")))
            setContent { KeyHarness(state) }
            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            val checkedBefore = session.commit.checkedCount()
            val selectedBefore = session.editors.selected?.file?.path

            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput(" ")
            onNodeWithTag(COMMIT_MESSAGE_TAG).performKeyInput {
                pressKey(Key.Spacebar)
                pressKey(Key.Enter)
            }
            waitForIdle()

            assertEquals(" \n", session.commit.message)
            assertEquals(checkedBefore, session.commit.checkedCount())
            assertEquals(selectedBefore, session.editors.selected?.file?.path)
            assertEquals(KeyContext.COMMIT_WINDOW, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `return, F4 and a double click open the selected row`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(repo.root.resolve("src/Main.kt")))
            setContent { KeyHarness(state) }
            val target = repo.root.resolve("src/Main.kt")

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            focusList()
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(target, session.editors.selected?.file?.path)

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.activeId == "commit" && state.toolWindows.visible }
            focusList()
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(target, session.editors.selected?.file?.path)

            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.activeId == "commit" && state.toolWindows.visible }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            row().performTouchInput { doubleClick() }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(target, session.editors.selected?.file?.path)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the capsule click commits`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            pressCommand(Key.K)
            waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 5_000) { commitRowsShown() }
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Change")
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_CAPSULE_TAG).assertIsEnabled() }.isSuccess
            }

            onNodeWithTag(COMMIT_CAPSULE_TAG).performClick()

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage?.startsWith("Committed 1 files to main: ") == true
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command 0 outside a repository shows the message in place of the list`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("web-dashboard"))
        Files.writeString(root.resolve("Main.kt"), "val x = 1\n")
        Files.createDirectories(temp.resolve("web-dashboard-home"))
        val state = AppState()
        try {
            state.openFolder(root)
            requireNotNull(state.session).git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = temp.resolve("web-dashboard-home")),
            )
            setContent { KeyHarness(state) }

            pressCommand(Key.Zero)

            waitUntil(timeoutMillis = 10_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasText("Not a Git repository: $root")).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            state.dispose()
        }
    }

    /** Tabs from the message field to the file list, as the window's own binding does. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.focusList() {
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
        }
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.row() = onNode(hasText("Main.kt") and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG)))

    private fun changedRepo(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        return repo
    }

    /** A repository with a local change and one commit waiting on its upstream. */
    private fun upstreamWithIncoming(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        val clone = Files.createTempDirectory(temp, "clone")
        val result = TestGitRepo.run(listOf("git", "clone", bare.toString(), clone.toString()), temp, repo.environment)
        assertEquals(0, result.exitCode, result.stderr)
        commitIn(repo, clone, "src/Remote.kt", "remote\n", "Remote one")
        repo.write("src/Main.kt", "second\n")
        return repo
    }

    private fun commitIn(repo: TestGitRepo, clone: Path, file: String, text: String, message: String) {
        val target = clone.resolve(file)
        target.parent.createDirectories()
        target.writeText(text)
        for (args in listOf(listOf("add", "-A"), listOf("commit", "-q", "-m", message), listOf("push"))) {
            val result = TestGitRepo.run(listOf("git", "-C", clone.toString()) + args, clone, repo.environment)
            assertEquals(0, result.exitCode, result.stderr)
        }
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

/** Counts `git commit` runs and holds the first one until [gate] opens, so a second request overlaps. */
private class GatedCommitExecutor(private val inner: GitExecutor, private val gate: CountDownLatch) : GitExecutor {
    val commits = AtomicInteger()

    override fun run(command: GitCommand): GitResult {
        if (command.args.firstOrNull() == "commit") {
            commits.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
        }
        return inner.run(command)
    }
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
