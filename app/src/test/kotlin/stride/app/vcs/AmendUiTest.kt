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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application

@ExtendWith(FixedUiFontExtension::class)
class AmendUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(AmendUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control alt M and the checkbox turn Amend on and off`() = runComposeUiTest {
        val repo = committedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            val window = state.session!!.commit
            onNodeWithTag(COMMIT_MESSAGE_TAG).performTextInput("Work in progress")
            waitUntil(timeoutMillis = 5_000) { window.message == "Work in progress" }

            pressControlAlt(Key.M)
            waitUntil(timeoutMillis = 10_000) { window.amend != null }
            assertEquals("Initial commit", window.message)
            onNodeWithText("Amend Commit").assertExists()
            onNodeWithText("Amend Commit and Push…").assertExists()

            pressControlAlt(Key.M)
            waitUntil(timeoutMillis = 5_000) { window.amend == null }
            assertEquals("Work in progress", window.message)
            assertTrue(onAllNodesWithText("Amend Commit").fetchSemanticsNodes().isEmpty())

            onNodeWithTag(COMMIT_AMEND_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { window.amend != null }
            assertEquals("Initial commit", window.message)

            onNodeWithTag(COMMIT_AMEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { window.amend == null }
            assertEquals("Work in progress", window.message)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a message-only amend is enabled with nothing checked and replaces HEAD's message`() =
        runComposeUiTest {
            val repo = committedRepo()
            val state = AppState()
            try {
                open(repo, state)
                setContent { KeyHarness(state) }
                openCommitWindow(state)
                val window = state.session!!.commit
                assertTrue(window.rows.isEmpty())

                pressControlAlt(Key.M)
                waitUntil(timeoutMillis = 10_000) { window.amend != null }
                window.message = "New message"
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(COMMIT_CAPSULE_TAG).assertIsEnabled() }.isSuccess
                }

                pressCommand(Key.Enter)

                waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Amended ") == true }
                assertEquals("New message", repo.git("log", "-1", "--format=%B").trim())
                assertNull(window.amend)
                assertTrue(window.message.isEmpty())
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `amending with a checked file puts its content in HEAD`() = runComposeUiTest {
        val repo = committedRepo()
        repo.write("src/Main.kt", "second\n")
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            val window = state.session!!.commit
            waitUntil(timeoutMillis = 10_000) { window.rows.any { it.path.fileName.toString() == "Main.kt" } }

            pressControlAlt(Key.M)
            waitUntil(timeoutMillis = 10_000) { window.amend != null }
            window.message = "Amended message"

            pressCommand(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Amended ") == true }
            assertEquals("second\n", repo.git("show", "HEAD:src/Main.kt"))
            assertEquals("Amended message", repo.git("log", "-1", "--format=%B").trim())
            assertNull(window.amend)
            assertTrue(window.message.isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a pushed HEAD refuses on turning Amend on and the box stays off`() = runComposeUiTest {
        val repo = committedRepo()
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            val window = state.session!!.commit
            val refusal = "HEAD is already pushed to origin/main: amending it needs a force push"

            pressControlAlt(Key.M)

            waitUntil(timeoutMillis = 10_000) { state.statusMessage == refusal }
            assertEquals(refusal, window.error)
            assertNull(window.amend)
            onNodeWithTag(COMMIT_ERROR_TAG).assertTextEquals(refusal)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt command K with Amend on amends and opens the push popup`() = runComposeUiTest {
        val repo = committedRepo()
        repo.bareRemote()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            val window = state.session!!.commit

            pressControlAlt(Key.M)
            waitUntil(timeoutMillis = 10_000) { window.amend != null }
            window.message = "Reworded"

            pressAltCommand(Key.K)

            waitUntil(timeoutMillis = 20_000) { state.pushRequest != null }
            assertEquals("Reworded", repo.git("log", "-1", "--format=%s").trim())
            assertNull(window.amend)
        } finally {
            state.dispose()
        }
    }

    /** A repository with one commit and no working-tree changes. */
    private fun committedRepo(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        return repo
    }

    private fun open(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val git = requireNotNull(state.session).git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.openCommitWindow(state: AppState) {
        pressCommand(Key.K)
        waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressAltCommand(key: Key) {
        onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(key) } }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressControlAlt(key: Key) {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(key) } }
        }
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
