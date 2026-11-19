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
import androidx.compose.ui.test.assertIsNotEnabled
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing the popup after moving and toggling gives the list the keyboard back`() = runComposeUiTest {
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
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.selected == 1 }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.checked.size == 2 }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
            }
            assertEquals("second\n", Files.readString(main))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing the popup after toggling gives the message field the keyboard back`() = runComposeUiTest {
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
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.checked.isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
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
    fun `returning after toggling rolls back and keeps the keyboard in the commit window`() = runComposeUiTest {
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
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.checked.isEmpty() }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.checked.isNotEmpty() }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Rolled back 1 file" }
            assertEquals(KeyContext.COMMIT_WINDOW, state.focus)
            assertEquals("first\n", Files.readString(main))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tracked row whose change was committed while the popup was open still runs git`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            selectRow("Main.kt")

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            assertEquals(setOf(main), state.rollbackRequest!!.checked)

            repo.git("add", "--", "src/Main.kt")
            repo.git("commit", "-q", "-m", "Committed while the popup was open")
            runBlocking { state.session!!.git.refreshNow() }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Rolled back 1 file" }
            val writes = state.session!!.git.console.filter { it.write }.map { it.command }
            assertTrue(
                writes.any { it.startsWith("git restore --source=HEAD --staged --worktree -- src/Main.kt") },
                writes.toString(),
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a repository that disappears while the popup is open refuses and keeps the unsaved text`() =
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

                deleteRecursively(repo.root.resolve(".git"))
                runBlocking { state.session!!.git.refreshNow() }

                // Losing the repository replaces the window's content, which takes the popup's
                // focus with it; the capsule runs the same confirmation as ↩.
                onNodeWithTag(ROLLBACK_CAPSULE_TAG).performClick()

                waitUntil(timeoutMillis = 5_000) { state.rollbackRequest == null }
                val refusal = "Not a Git repository: ${repo.root}"
                waitUntil(timeoutMillis = 15_000) {
                    state.statusMessage != null || state.session!!.commit.error != null
                }
                assertEquals(refusal, state.statusMessage, "window error: ${state.session!!.commit.error}")
                assertEquals(refusal, state.session!!.commit.error)
                assertTrue(editor.isModified)
                assertEquals("mine\n", editor.value.text)
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a partly failed rollback forgets the choices of the paths it restored`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            selectRow("Main.kt")
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) {
                val row = state.session!!.commit.rows.first { it.path == main }
                !state.session!!.commit.inclusion.isChecked(row)
            }

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest!!.selected == 1 }
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) {
                state.rollbackRequest!!.checked == setOf(main, repo.root.resolve("src/New.kt"))
            }

            state.session!!.git.executor = FailingCommandExecutor(
                CliGitExecutor(TEST_GIT, repo.environment),
                "rm",
                GitResult(128, ByteArray(0), "fatal: could not remove 'src/New.kt'\n", timedOut = false),
            )
            onRoot().performKeyInput { pressKey(Key.Enter) }

            val window = state.session!!.commit
            waitUntil(timeoutMillis = 15_000) { window.error?.startsWith("Rollback failed:") == true }
            assertEquals("first\n", Files.readString(main))

            repo.write("src/Main.kt", "third\n")
            runBlocking { state.session!!.git.refreshNow() }
            window.syncRows(commitRows(state.session!!, window))
            val row = window.rows.first { it.path == main }
            assertTrue(window.inclusion.isChecked(row), "Main.kt must start checked again")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a rename row rolled back through the popup restores the old path and keeps the edits`() =
        runComposeUiTest {
            val repo = changedRepo(committedOnly = true)
            val state = AppState()
            val original = repo.root.resolve("src/Main.kt")
            val renamed = repo.root.resolve("src/Renamed.kt")
            repo.git("mv", "src/Main.kt", "src/Renamed.kt")
            repo.write("src/Renamed.kt", "edited\n")
            try {
                open(repo, state)
                setContent { KeyHarness(state) }
                openCommitWindow(state)
                selectRow("Renamed.kt")

                pressAltCommand(Key.Z)
                waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
                waitRollbackFocused()
                assertEquals(setOf(renamed), state.rollbackRequest!!.checked)

                onRoot().performKeyInput { pressKey(Key.Enter) }

                waitUntil(timeoutMillis = 15_000) {
                    state.statusMessage == "Rolled back 1 file; 1 added file kept as unversioned"
                }
                assertEquals("first\n", Files.readString(original))
                assertEquals("edited\n", Files.readString(renamed))
                assertTrue(repo.git("status", "--porcelain").contains("?? src/Renamed.kt"))
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a rolled back path's unchecked choice is forgotten and it starts checked again`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            val window = state.session!!.commit
            selectRow("Main.kt")
            onRoot().performKeyInput { pressKey(Key.Spacebar) }
            waitUntil(timeoutMillis = 5_000) {
                val row = window.rows.first { it.path == main }
                !window.inclusion.isChecked(row)
            }

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            assertEquals(setOf(main), state.rollbackRequest!!.checked)
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Rolled back 1 file" }

            repo.write("src/Main.kt", "third\n")
            runBlocking { state.session!!.git.refreshNow() }
            window.syncRows(commitRows(state.session!!, window))
            val row = window.rows.first { it.path == main }
            assertTrue(window.inclusion.isChecked(row), "Main.kt must start checked again")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an added file with unsaved text keeps its text when it is only unstaged`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val added = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")
        try {
            open(repo, state)
            val editor = state.session!!.editors.openFile(VFile(added)) as TextFileEditor
            editor.setText("draft\n")
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            waitUntil(timeoutMillis = 10_000) {
                state.session!!.commit.rows.any { it.path == added && it.unsaved }
            }
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
            assertTrue(editor.isModified)
            assertEquals("draft\n", editor.value.text)
            assertTrue(repo.git("status", "--porcelain").contains("?? src/New.kt"))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unversioned selected row opens the popup with nothing checked`() = runComposeUiTest {
        val repo = changedRepo()
        val state = AppState()
        val loose = repo.write("src/Loose.kt", "loose\n")
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openCommitWindow(state)
            selectRow("Loose.kt")

            pressAltCommand(Key.Z)
            waitUntil(timeoutMillis = 5_000) { state.rollbackRequest != null }
            waitRollbackFocused()
            assertTrue(state.rollbackRequest!!.checked.isEmpty())
            onNodeWithText("0 of 2").assertExists()
            onNodeWithTag(ROLLBACK_CAPSULE_TAG).assertIsNotEnabled()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitForIdle()
            assertNotNull(state.rollbackRequest)
            assertNull(state.statusMessage)
            assertEquals("loose\n", Files.readString(loose))
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

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** Answers every command whose first argument is [name] with [result]. */
    private class FailingCommandExecutor(
        private val delegate: GitExecutor,
        private val name: String,
        private val result: GitResult,
    ) : GitExecutor {
        override fun run(command: GitCommand): GitResult =
            if (command.args.firstOrNull() == name) result else delegate.run(command)
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
