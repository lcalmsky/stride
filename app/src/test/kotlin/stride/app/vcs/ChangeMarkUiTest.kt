package stride.app.vcs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import java.nio.file.Files
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

/** The marks in the gutter, their popup and the actions reached from it. */
@ExtendWith(FixedUiFontExtension::class)
class ChangeMarkUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ChangeMarkUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `marks are drawn only when the editor has hunks`() = runComposeUiTest {
        val file = Files.createDirectories(temp.resolve("marks-project")).resolve("Main.kt")
        Files.writeString(file, "alpha\nbeta\n")
        val editor = TextFileEditor(VFile(file))
        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                editor.Content(Modifier.size(480.dp, 240.dp))
            }
        }
        waitForIdle()
        onNodeWithTag(TextFileEditor.CHANGE_MARKS_TAG).assertDoesNotExist()

        Snapshot.withMutableSnapshot { editor.vcsHunks = listOf(LineHunk(0, 1, 0, 1)) }
        waitForIdle()
        onNodeWithTag(TextFileEditor.CHANGE_MARKS_TAG).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking a mark opens the popup and its button rolls the hunk back`() = runComposeUiTest {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\n")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            val editor = openEdited(state, repo, file, "new\nalpha\nbeta\n")
            setContent { Harness(state) }
            waitUntil(timeoutMillis = 5_000) { editor.vcsHunks.isNotEmpty() }

            clickFirstMark()
            waitUntil(timeoutMillis = 5_000) { state.changeMarkPopup != null }
            onNodeWithTag(CHANGE_MARK_POPUP_TAG).assertExists()
            assertTrue(textCount("Lines 1–1 changed") == 1)

            onNodeWithText("Rollback ⌥⌘Z").performClick()
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "alpha\nbeta\n" }
            assertNull(state.changeMarkPopup)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the popup and alt meta z rolls back from inside it`() = runComposeUiTest {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\n")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            val editor = openEdited(state, repo, file, "new\nalpha\nbeta\n")
            setContent { Harness(state) }
            waitUntil(timeoutMillis = 5_000) { editor.vcsHunks.isNotEmpty() }

            clickFirstMark()
            waitUntil(timeoutMillis = 5_000) { state.changeMarkPopup != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.changeMarkPopup == null }

            clickFirstMark()
            waitUntil(timeoutMillis = 5_000) { state.changeMarkPopup != null }
            pressAltMetaZ()
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "alpha\nbeta\n" }
            assertNull(state.changeMarkPopup)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt meta z and control alt shift down work from the editor`() = runComposeUiTest {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/Main.kt", "alpha\nbeta\ngamma\n")
        repo.commitAll("Initial commit")
        val state = AppState()
        try {
            val editor = openEdited(state, repo, file, "alpha\nBETA\ngamma\nNEW\n")
            setContent { Harness(state) }
            waitUntil(timeoutMillis = 5_000) { editor.vcsHunks.size == 2 }
            editor.jumpTo(0)

            state.focus = KeyContext.EDITOR
            pressControlAltShiftDown()
            waitUntil(timeoutMillis = 5_000) { state.changeMarkPopup?.hunk?.newStart == 1 }
            assertEquals(1, caretLine(editor))

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) {
                state.changeMarkPopup == null && state.focus == KeyContext.EDITOR
            }
            pressAltMetaZ()
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "alpha\nbeta\ngamma\nNEW\n" }
        } finally {
            state.dispose()
        }
    }

    private fun openEdited(state: AppState, repo: TestGitRepo, file: Path, edited: String): TextFileEditor {
        state.openFolder(repo.root)
        val git = state.session!!.git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
        val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
        assertTrue(eventually { ChangeMarks.trackerOf(editor)?.baseState() != null })
        editor.setText(edited)
        waitForHunks(editor)
        return editor
    }

    private fun waitForHunks(editor: TextFileEditor) {
        assertTrue(eventually { editor.vcsHunks.isNotEmpty() }, "hunks: ${editor.vcsHunks}")
    }

    /** The first hunk starts on the first row, so a click a few pixels in lands on it. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickFirstMark() {
        onNodeWithTag(TextFileEditor.CHANGE_MARKS_TAG).performMouseInput {
            click(Offset(width / 2f, 2f))
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressAltMetaZ() {
        onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressControlAltShiftDown() {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.AltLeft) {
                    withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.textCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun caretLine(editor: TextFileEditor): Int {
        val text = editor.value.text
        var line = 0
        for (index in 0 until editor.value.selection.end) if (text[index] == '\n') line++
        return line
    }
}

@Composable
private fun Harness(state: AppState) {
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
