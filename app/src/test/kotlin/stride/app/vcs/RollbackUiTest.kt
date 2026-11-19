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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class RollbackUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(RollbackUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt command Z from the message field checks only the selected row`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)

            pressAltCommand(Key.Z)

            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            val request = requireNotNull(state.rollbackRequest)
            assertEquals(listOf("Main.kt", "Other.kt"), request.rows.map { it.path.fileName.toString() })
            assertEquals(setOf(repo.root.resolve("src/Main.kt")), request.checked)
            onNodeWithText("Rollback Changes").assertExists()
            onNodeWithText("Rollback").assertExists()
            onNodeWithText("1 of 2").assertExists()
            onNodeWithText(
                "Open editors can undo this with ⌘Z; files that are not open cannot be brought back",
            ).assertExists()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `space toggles the current row`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()

            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.checked.isEmpty() }

            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) {
                state.rollbackRequest!!.checked == setOf(repo.root.resolve("src/Main.kt"))
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `return rolls back only the checked file`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        val other = repo.root.resolve("src/Other.kt")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Rolled back 1 file" }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            assertEquals("first\n", Files.readString(main))
            assertEquals("other changed\n", Files.readString(other))
            val status = repo.git("status", "--porcelain")
            assertFalse(status.contains("Main.kt"), status)
            assertTrue(status.contains("Other.kt"), status)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the popup and the part that had the keyboard takes it back`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            focusList()

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
            assertEquals("second\n", Files.readString(main))
            assertNull(state.statusMessage)

            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unsaved editor of the rolled back file takes the HEAD text and one undo brings it back`() =
        runComposeUiTest {
            val repo = changedRepo(committedOnly = true)
            val state = AppState()
            val main = repo.root.resolve("src/Main.kt")
            try {
                open(repo, state)
                val editor = state.session!!.editors.openFile(VFile(main)) as TextFileEditor
                editor.setText("mine\n")
                setContent { KeyHarness(state) }
                openCommitWindow(state)
                waitUntil(timeoutMillis = 10_000) {
                    state.session!!.commit.rows.any { it.path == main && it.unsaved }
                }
                selectRow("Main.kt")

                pressAltCommand(Key.Z)
                waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
                waitRollbackFocused()
                assertEquals(setOf(main), state.rollbackRequest!!.checked)

                onRoot().performKeyInput { pressKey(Key.Enter) }

                waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Rolled back 1 file" }
                waitUntil(timeoutMillis = 5_000) { editor.value.text == "first\n" }
                assertFalse(editor.isModified)
                assertEquals("first\n", Files.readString(main))
                assertTrue(
                    state.session!!.git.console.none { it.command.contains("restore") },
                    "an editor-only row must not reach a git restore: " +
                        state.session!!.git.console.joinToString("\n") { it.command },
                )
                assertTrue(editor.applyAction(ActionId.UNDO))
                waitForIdle()
                assertEquals("mine\n", editor.value.text)
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an added file stays on disk as unversioned and the message says so`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val added = repo.root.resolve("src/New.kt")
        try {
            repo.write("src/New.kt", "new\n")
            repo.git("add", "src/New.kt")
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            selectRow("New.kt")

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            assertEquals(setOf(added), state.rollbackRequest!!.checked)

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage == "Rolled back 1 file; 1 added file kept as unversioned"
            }
            assertEquals("new\n", Files.readString(added))
            assertTrue(repo.git("status", "--porcelain").contains("?? src/New.kt"))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `unversioned and conflicted rows are not listed`() = runComposeUiTest {
        val repo = conflictedRepo()
        val state = AppState()
        try {
            repo.write("src/Untracked.kt", "loose\n")
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            waitUntil(timeoutMillis = 10_000) {
                state.session!!.commit.rows.any { it.path.fileName.toString() == "Untracked.kt" } &&
                    state.session!!.commit.rows.any { it.kind == ChangeKind.CONFLICTED }
            }

            pressAltCommand(Key.Z)

            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            val names = state.rollbackRequest!!.rows.map { it.path.fileName.toString() }
            assertTrue(names.contains("Main.kt"), names.toString())
            assertFalse(names.contains("Untracked.kt"), names.toString())
            assertFalse(names.contains("Conflict.kt"), names.toString())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `vcs popup row nine opens with the editor's file checked and is dimmed without changes`() =
        runComposeUiTest {
            val repo = changedRepo()
            val state = AppState()
            val main = repo.root.resolve("src/Main.kt")
            val clean = repo.root.resolve("src/Clean.kt")
            try {
                open(repo, state)
                state.session!!.editors.openFile(VFile(main))
                state.focusSelectedEditor()
                setContent { KeyHarness(state) }

                pressControl(Key.V)
                waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
                assertTrue(state.vcsPopup!!.rollbackEnabled)
                onRoot().performKeyInput { pressKey(Key.Nine) }
                waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
                waitRollbackFocused()
                assertEquals(setOf(main), state.rollbackRequest!!.checked)
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }

                state.session!!.editors.openFile(VFile(clean))
                state.focusSelectedEditor()
                pressControl(Key.V)
                waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
                assertFalse(state.vcsPopup!!.rollbackEnabled)
                onRoot().performKeyInput { pressKey(Key.NumPad9) }
                waitForIdle()
                assertNull(state.rollbackRequest)
                assertNotNull(state.vcsPopup)
            } finally {
                state.dispose()
            }
        }

    /** Two changed files and a clean one, so the window lists two tracked rows. */
    private fun changedRepo(committedOnly: Boolean = false): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "other\n")
        repo.write("src/Clean.kt", "clean\n")
        repo.commitAll("Initial commit")
        if (!committedOnly) {
            repo.write("src/Main.kt", "second\n")
            repo.write("src/Other.kt", "other changed\n")
        }
        return repo
    }

    /** A repository stopped in a merge conflict on `src/Conflict.kt`. */
    private fun conflictedRepo(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Conflict.kt", "base\n")
        repo.commitAll("Initial commit")
        repo.git("checkout", "-q", "-b", "side")
        repo.write("src/Conflict.kt", "side\n")
        repo.commitAll("Side")
        repo.git("checkout", "-q", "main")
        repo.write("src/Conflict.kt", "mine\n")
        repo.write("src/Main.kt", "second\n")
        repo.commitAll("Main")
        val merge = TestGitRepo.run(
            listOf("git", "-C", repo.root.toString(), "merge", "side"),
            repo.root,
            repo.environment,
        )
        assertTrue(merge.exitCode != 0, "the merge was meant to conflict")
        repo.write("src/Main.kt", "second again\n")
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
        waitUntil(timeoutMillis = 10_000) { state.session!!.commit.rows.isNotEmpty() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.waitRollbackFocused() {
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(ROLLBACK_POPUP_TAG).assertIsFocused() }.isSuccess
        }
    }

    /** Tabs from the message field to the file list. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.focusList() {
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.selectRow(name: String) {
        onNode(hasText(name) and hasAnyAncestor(hasTestTag(COMMIT_LIST_TAG))).performClick()
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
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
    private fun ComposeUiTest.pressControl(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
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
