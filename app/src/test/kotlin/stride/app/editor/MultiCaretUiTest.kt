package stride.app.editor

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
class MultiCaretUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(MultiCaretUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    private class FakeClipboard(var text: String? = null) : TextClipboard {
        override fun writeText(text: String): Boolean {
            this.text = text
            return true
        }

        override fun readText(): String? = text
    }

    private fun openEditor(text: String, clipboard: TextClipboard? = null): Pair<AppState, TextFileEditor> {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        val path = root.resolve("Main.kt")
        Files.writeString(path, text)
        val state = AppState()
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val editor = session.editors.openFile(VFile(path)) as TextFileEditor
        if (clipboard != null) editor.clipboard = clipboard
        return state to editor
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `typing after two occurrence selections changes every match and survives idle`() = runComposeUiTest {
        val (state, editor) = openEditor("foo foo foo\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.selection == TextRange(0, 3) }
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
            waitUntil(timeoutMillis = 5_000) { editor.carets.size == 2 }

            waitForIdle()
            assertEquals(2, editor.carets.size)

            onNode(hasSetTextAction()).performTextInput("z")
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "z z foo\n" }
            assertEquals(2, editor.carets.size)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `arrows and backspace act at every caret`() = runComposeUiTest {
        val (state, editor) = openEditor("ab\ncd\nef\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))
            editor.toggleCaretAt(4)
            assertEquals(listOf(TextRange(1), TextRange(4)), editor.carets)

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = 5_000) { editor.carets == listOf(TextRange(0), TextRange(3)) }

            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis = 5_000) { editor.carets == listOf(TextRange(3), TextRange(6)) }

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            waitUntil(timeoutMillis = 5_000) { editor.carets == listOf(TextRange(4), TextRange(7)) }

            onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
            waitUntil(timeoutMillis = 5_000) {
                editor.carets == listOf(TextRange(4, 5), TextRange(7, 8))
            }

            onRoot().performKeyInput { pressKey(Key.Backspace) }
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "ab\nc\ne\n" }
            assertEquals(listOf(TextRange(4), TextRange(6)), editor.carets)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape drops the secondary carets`() = runComposeUiTest {
        val (state, editor) = openEditor("foo bar foo\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))
            editor.toggleCaretAt(9)
            assertEquals(2, editor.carets.size)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { editor.carets.size == 1 }
            assertTrue(editor.carets.single().collapsed)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command v splits as many lines as there are carets`() = runComposeUiTest {
        val clipboard = FakeClipboard("x\ny")
        val (state, editor) = openEditor("a\nb", clipboard)
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
            editor.toggleCaretAt(2)
            assertEquals(listOf(TextRange(0), TextRange(2)), editor.carets)

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.V) } }
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "xa\nyb" }
            assertEquals(listOf(TextRange(1), TextRange(4)), editor.carets)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `column mode typing fills three lines after two shift downs`() = runComposeUiTest {
        val (state, editor) = openEditor("abc\ndef\nghi\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.Eight) } }
            }
            waitUntil(timeoutMillis = 5_000) { editor.columnMode }
            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) }
            }
            onRoot().performKeyInput {
                withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) }
            }
            waitUntil(timeoutMillis = 5_000) {
                editor.carets == listOf(TextRange(1), TextRange(5), TextRange(9))
            }

            onNode(hasSetTextAction()).performTextInput("x")
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "axbc\ndxef\ngxhi\n" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `double option with up clones the caret above`() = runComposeUiTest {
        val (state, editor) = openEditor("ab\ncd\nef\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(4)))

            onRoot().performKeyInput {
                pressKey(Key.AltLeft)
                keyDown(Key.AltLeft)
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
                keyUp(Key.AltLeft)
            }
            waitUntil(timeoutMillis = 5_000) { editor.carets == listOf(TextRange(4), TextRange(1)) }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option shift click adds a caret`() = runComposeUiTest {
        val (state, editor) = openEditor("ab\ncd\nef\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
            assertEquals(1, editor.carets.size)

            onRoot().performKeyInput {
                keyDown(Key.AltLeft)
                keyDown(Key.ShiftLeft)
            }
            onNode(hasSetTextAction()).performMouseInput { click(Offset(20f, 30f)) }
            onRoot().performKeyInput {
                keyUp(Key.ShiftLeft)
                keyUp(Key.AltLeft)
            }

            waitUntil(timeoutMillis = 5_000) { editor.carets.size == 2 }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option drag builds a block`() = runComposeUiTest {
        val (state, editor) = openEditor("abcd\nefgh\nijkl\nmnop\n")
        try {
            setContent { MultiCaretHarness(state) }
            onNode(hasSetTextAction()).performClick()
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

            onRoot().performKeyInput { keyDown(Key.AltLeft) }
            onNode(hasSetTextAction()).performMouseInput {
                moveTo(Offset(10f, 5f))
                press()
                moveTo(Offset(30f, 55f))
                release()
            }
            onRoot().performKeyInput { keyUp(Key.AltLeft) }

            waitUntil(timeoutMillis = 5_000) { editor.carets.size >= 2 }
            assertTrue(editor.carets.any { !it.collapsed })
        } finally {
            state.dispose()
        }
    }
}

@Composable
private fun MultiCaretHarness(state: AppState) {
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
