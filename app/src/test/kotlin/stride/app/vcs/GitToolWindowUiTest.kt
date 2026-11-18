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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
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
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application

@ExtendWith(FixedUiFontExtension::class)
class GitToolWindowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(GitToolWindowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the log lists the commits in order with their refs and enter opens a commit diff`() =
        runComposeUiTest {
            val repo = threeCommits()
            val state = openRepository(repo)
            try {
                val git = state.session!!.git
                val rootCommit = runBlocking { git.log() }.last()

                setContent { GitHarness(state) }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Third commit").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("Third commit").assertIsDisplayed()
                onNodeWithText("Second commit").assertIsDisplayed()
                onNodeWithText("Initial commit").assertIsDisplayed()
                onNodeWithText("HEAD -> main").assertIsDisplayed()
                onNodeWithText("feature").assertIsDisplayed()

                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                onRoot().performKeyInput { pressKey(Key.Enter) }

                waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
                val request = requireNotNull(state.diffRequest)
                assertEquals("${rootCommit.shortId}  Initial commit", request.title)
                val file = request.files.single()
                assertEquals("src/Main.kt", file.displayPath)
                assertEquals(BaseText.Text(""), file.old)
                assertEquals(BaseText.Text("one\n"), file.new)
                assertFalse(file.openable)
                val rows = DiffRows.build(file).filterIsInstance<DiffRow.Code>()
                assertTrue(rows.isNotEmpty())
                assertTrue(rows.all { it.sign == '+' }, "the root commit adds every line: $rows")
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the console shows a failed command with its exit code`() = runComposeUiTest {
        val repo = threeCommits()
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        assertTrue(hook.toFile().setExecutable(true))
        val state = openRepository(repo)
        try {
            val git = state.session!!.git
            val file = repo.write("src/Main.kt", "one\ntwo\nthree\n")
            val result = runBlocking { git.commit(listOf(file), "Fourth commit") }
            assertFalse(result.ok)

            setContent { GitHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }

            onNodeWithText("Console").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("$ git commit", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("exit 1", substring = true).assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `outside a repository the window names the project root`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("plain-window"))
        val state = AppState()
        try {
            state.openFolder(root)
            state.session!!.git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(ceiling = temp, home = Files.createDirectories(temp.resolve("plain-window-home"))),
            )
            runBlocking { state.session!!.git.refreshNow() }
            state.toggleToolWindow("git", KeyContext.LIST_WINDOW)

            setContent { GitHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Not a Git repository: $root").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Not a Git repository: $root").assertIsDisplayed()
        } finally {
            state.dispose()
        }
    }

    private fun threeCommits(): TestGitRepo {
        val repo = TestGitRepo.create(temp, "git-window")
        repo.write("src/Main.kt", "one\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "one\ntwo\n")
        repo.commitAll("Second commit")
        repo.git("branch", "feature")
        repo.write("src/Other.kt", "x\n")
        repo.commitAll("Third commit")
        return repo
    }

    private fun openRepository(repo: TestGitRepo): AppState {
        val state = AppState()
        state.openFolder(repo.root)
        state.session!!.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { state.session!!.git.refreshNow() }
        state.toggleToolWindow("git", KeyContext.LIST_WINDOW)
        return state
    }
}

@Composable
private fun GitHarness(state: AppState) {
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
