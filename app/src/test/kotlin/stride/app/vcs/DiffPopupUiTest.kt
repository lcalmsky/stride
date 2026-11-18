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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
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

@ExtendWith(FixedUiFontExtension::class)
class DiffPopupUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(DiffPopupUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `f7 moves to the next hunk and f4 opens the file at its line`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("diff-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        val lines = (1..120).map { "line %03d".format(it) }
        val changed = lines.toMutableList().also {
            it[1] = "LINE 002"
            it[89] = "LINE 090"
        }
        Files.writeString(file, changed.joinToString("\n") + "\n")
        val old = lines.joinToString("\n") + "\n"
        val new = changed.joinToString("\n") + "\n"
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) { state.session!!.editors.selected != null }
            val editor = state.session!!.editors.selected as TextFileEditor
            assertEquals(new.indexOf("LINE 002"), editor.value.selection.start)
            assertNull(state.diffRequest)

            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) {
                state.session!!.editors.selected?.let { (it as TextFileEditor).value.selection.start } ==
                    new.indexOf("LINE 090")
            }
            assertNull(state.diffRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control shift right and left switch between the diff's files`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("diff-files-project"))
        val first = root.resolve("src/First.kt")
        val second = root.resolve("src/Second.kt")
        Files.createDirectories(first.parent)
        Files.writeString(first, "alphaOnly\n")
        Files.writeString(second, "betaOnly\n")
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = DiffRequest(
                title = "One commit",
                files = listOf(
                    DiffFile(first, "src/First.kt", BaseText.Text("alpha"), BaseText.Text("alphaOnly"), openable = false),
                    DiffFile(second, "src/Second.kt", BaseText.Text("beta"), BaseText.Text("betaOnly"), openable = false),
                ),
            )
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("alphaOnly").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("betaOnly").fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithText("alphaOnly").fetchSemanticsNodes().isEmpty()
            }

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("alphaOnly").fetchSemanticsNodes().isNotEmpty()
            }
            state.diffRequest = null
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `f4 does nothing on a commit diff that is not openable and escape closes`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("commit-diff-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = DiffRequest(
                title = "abc1234  Change the text",
                files = listOf(
                    DiffFile(file, "src/Main.kt", BaseText.Text("alpha\n"), BaseText.Text("beta\n"), openable = false),
                ),
            )
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("abc1234  Change the text").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.F4) }
            waitForIdle()
            assertTrue(state.diffRequest != null)
            assertTrue(state.session!!.editors.openFiles.isEmpty())

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.diffRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    private fun request(file: Path, old: String, new: String): DiffRequest = DiffRequest(
        title = "${file.fileName}  HEAD ↔ Local",
        files = listOf(DiffFile(file, "src/${file.fileName}", BaseText.Text(old), BaseText.Text(new), openable = true)),
    )
}

@Composable
private fun DiffHarness(state: AppState) {
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
