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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 001").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) { state.session!!.editors.selected != null }
            val editor = state.session!!.editors.selected as TextFileEditor
            assertEquals(new.indexOf("LINE 002"), editor.value.selection.start)
            assertNull(state.diffRequest)

            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) { state.diffRequest != null }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 001").fetchSemanticsNodes().isNotEmpty()
            }

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
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("beta").fetchSemanticsNodes().isNotEmpty()
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shift f7 moves back to the previous change`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("previous-change-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        val lines = (1..120).map { "line %03d".format(it) }
        val changed = lines.toMutableList().also {
            it[1] = "LINE 002"
            it[89] = "LINE 090"
        }
        val old = lines.joinToString("\n") + "\n"
        val new = changed.joinToString("\n") + "\n"
        Files.writeString(file, new)
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 001").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { pressKey(Key.F7) }
            onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) {
                state.session!!.editors.selected?.let { (it as TextFileEditor).value.selection.start } ==
                    new.indexOf("LINE 002")
            }
            assertNull(state.diffRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `arrow and page keys move the selected row`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("move-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        val old = "alpha\nbeta\ngamma"
        val new = "alpha\nBETA\ngamma"
        Files.writeString(file, new)
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()

            fun closeAgain() {
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("beta").fetchSemanticsNodes().isEmpty()
                }
            }

            fun open() {
                state.diffRequest = request(file, old, new)
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("beta").fetchSemanticsNodes().isNotEmpty()
                }
            }

            fun caret(): Int? = (state.session!!.editors.selected as? TextFileEditor)?.value?.selection?.start

            open()
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { caret() == new.indexOf("BETA") }
            closeAgain()

            open()
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { caret() == new.indexOf("BETA") }
            closeAgain()

            open()
            onRoot().performKeyInput { pressKey(Key.PageDown) }
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { caret() == new.indexOf("gamma") }
            closeAgain()

            open()
            onRoot().performKeyInput { pressKey(Key.PageDown) }
            onRoot().performKeyInput { pressKey(Key.PageUp) }
            onRoot().performKeyInput { pressKey(Key.F4) }
            waitUntil(timeoutMillis = 5_000) { caret() == 0 }
            closeAgain()
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a fold row expands by click and by enter`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("fold-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        val lines = (1..20).map { "line $it" }
        val changed = lines.toMutableList().also {
            it[0] = "LINE 1"
            it[19] = "LINE 20"
        }
        val old = lines.joinToString("\n")
        val new = changed.joinToString("\n")
        Files.writeString(file, new)
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()

            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("⋯ 12 unchanged lines").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("⋯ 12 unchanged lines").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 5").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(onAllNodesWithText("⋯ 12 unchanged lines").fetchSemanticsNodes().isEmpty())

            state.diffRequest = null
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 5").fetchSemanticsNodes().isEmpty()
            }
            state.diffRequest = request(file, old, new)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("⋯ 12 unchanged lines").fetchSemanticsNodes().isNotEmpty()
            }
            repeat(5) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 5").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an initial line inside a fold opens it and selects the line`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("initial-line-project"))
        val file = root.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        val lines = (1..20).map { "line $it" }
        val changed = lines.toMutableList().also {
            it[0] = "LINE 1"
            it[19] = "LINE 20"
        }
        val old = lines.joinToString("\n")
        val new = changed.joinToString("\n")
        Files.writeString(file, new)
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = DiffRequest(
                title = "${file.fileName}  HEAD ↔ Local",
                files = listOf(
                    DiffFile(file, "src/Main.kt", BaseText.Text(old), BaseText.Text(new), openable = true),
                ),
                initialLine = 9,
            )
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("line 10").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.F4) }

            waitUntil(timeoutMillis = 5_000) {
                state.session!!.editors.selected?.let { (it as TextFileEditor).value.selection.start } ==
                    new.indexOf("line 10")
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the body stays empty until the rows are built off the ui thread`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("big-diff-project"))
        val file = root.resolve("src/Big.kt")
        Files.createDirectories(file.parent)
        val count = 300_000
        val oldLines = (1..count).map { "line $it" }
        val newLines = oldLines.toMutableList().also { lines ->
            for (index in 0 until 1_500) lines[index * 100] = "CHANGED $index"
        }
        val new = newLines.joinToString("\n")
        Files.writeString(file, new)
        val state = AppState()
        try {
            state.openFolder(root)
            setContent { DiffHarness(state) }
            waitForIdle()
            state.diffRequest = request(file, oldLines.joinToString("\n"), new)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
            waitForIdle()

            assertTrue(
                onAllNodesWithText("line 1").fetchSemanticsNodes().isEmpty(),
                "the body drew rows before the background build finished",
            )
            waitUntil(timeoutMillis = 30_000) {
                onAllNodesWithText("line 1").fetchSemanticsNodes().isNotEmpty()
            }
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
