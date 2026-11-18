package stride.app.generate

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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import stride.lang.RefactoringStep

@ExtendWith(FixedUiFontExtension::class)
class GenerateKeyUiTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the generate strokes run their actions without changing the editor text`() = runComposeUiTest {
        val text = "class Alpha\n"
        val editor = prepareProject(text)
        state.prepareGenerate = { _, _ -> RefactoringStep.Refused("not here") }

        setContent { GenerateHarness(state) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        editor.updateValue(TextFieldValue(text, TextRange(0)))

        state.statusMessage = null
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.N) } }
        waitUntil(timeoutMillis = 5_000) { state.statusMessage == "Nothing to generate here" }
        assertEquals(text, editor.value.text)

        state.statusMessage = null
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitUntil(timeoutMillis = 5_000) { state.statusMessage == "Nothing to generate here" }
        assertEquals(text, editor.value.text)

        state.statusMessage = null
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.O) } }
        waitUntil(timeoutMillis = 5_000) { state.statusMessage == "not here" }
        assertEquals(text, editor.value.text)

        state.statusMessage = null
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.I) } }
        waitUntil(timeoutMillis = 5_000) { state.statusMessage == "not here" }
        assertEquals(text, editor.value.text)
        assertNull(state.generate)
        assertNull(state.memberChoose)
    }

    private fun prepareProject(text: String): TextFileEditor {
        val root = temp.resolve("generate-key-project")
        Files.createDirectories(root)
        val file = root.resolve("Main.kt")
        Files.writeString(file, text)
        state.openFolder(root)
        return requireNotNull(state.session).editors.openFile(VFile(file)) as TextFileEditor
    }

    companion object {
        @org.junit.jupiter.api.BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(GenerateKeyUiTest::class.java.classLoader)
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}

@Composable
private fun GenerateHarness(state: AppState) {
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
