package stride.app.refactor

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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
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
import stride.lang.NameCheck
import stride.lang.RefactoringEdit
import stride.lang.RefactoringKind
import stride.lang.RefactoringStep
import stride.lang.TextEdit

@ExtendWith(FixedUiFontExtension::class)
class RefactoringPopupUiTest {
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
    fun `refactor this lists the entries with their strokes and enter runs the selected one`() = runComposeUiTest {
        val editor = prepareProject("alpha beta\n")
        state.prepareRefactoring = { _, kind ->
            when (kind) {
                RefactoringKind.INTRODUCE_VARIABLE -> RefactoringStep.Apply(
                    RefactoringEdit(
                        listOf(TextEdit(0, 0, "val value = 1\n")),
                        caret = 14,
                        message = "Introduced variable value",
                    ),
                )
                RefactoringKind.EXTRACT_METHOD, RefactoringKind.INLINE -> RefactoringStep.Refused("not available")
            }
        }

        setContent { RefactoringHarness(state) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))

        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.T) }
        }
        waitUntil(timeoutMillis = 5_000) { state.refactorThis != null }
        waitForIdle()

        onNodeWithText("Refactor This").assertExists()
        onNodeWithText("Rename…").assertExists()
        onNodeWithText("Introduce Variable…").assertExists()
        onNodeWithText("⇧F6").assertExists()
        onNodeWithText("⌥⌘V").assertExists()

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { state.refactorThis == null }
        assertEquals("val value = 1\nalpha beta\n", editor.value.text)
        assertEquals("Introduced variable value", state.statusMessage)
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the name popup applies a typed name`() = runComposeUiTest {
        val editor = prepareProject("alpha beta\n")
        state.prepareRefactoring = { _, _ -> namingStep() }

        setContent { RefactoringHarness(state) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

        onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.V) } }
        }
        waitUntil(timeoutMillis = 5_000) { state.refactoringName != null }
        waitForIdle()
        onNodeWithText("Introduce Variable").assertExists()
        onNodeWithTag(REFACTORING_NAME_FIELD_TAG).assertIsFocused()

        onNodeWithTag(REFACTORING_NAME_FIELD_TAG).performTextClearance()
        onNodeWithTag(REFACTORING_NAME_FIELD_TAG).performTextInput("total")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { state.refactoringName == null }

        assertEquals("total beta\n", editor.value.text)
        assertEquals("Introduced variable total", state.statusMessage)
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an invalid name shows its reason and escape cancels`() = runComposeUiTest {
        prepareProject("alpha beta\n")
        state.prepareRefactoring = { _, _ -> namingStep() }

        setContent { RefactoringHarness(state) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

        onRoot().performKeyInput {
            withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(Key.V) } }
        }
        waitUntil(timeoutMillis = 5_000) { state.refactoringName != null }
        waitForIdle()

        onNodeWithTag(REFACTORING_NAME_FIELD_TAG).performTextClearance()
        onNodeWithTag(REFACTORING_NAME_FIELD_TAG).performTextInput("used")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { state.refactoringName?.errorMessage != null }
        waitForIdle()

        onNodeWithTag(REFACTORING_NAME_ERROR_TAG).assertExists()
        onNodeWithText("'used' is already used in this function").assertExists()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { state.refactoringName == null }
        assertNull(state.statusMessage)
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun namingStep(): RefactoringStep.AskName =
        RefactoringStep.AskName("Introduce Variable", "value") { name ->
            if (name == "used") {
                NameCheck.Invalid("'used' is already used in this function")
            } else {
                NameCheck.Valid(
                    RefactoringEdit(
                        listOf(TextEdit(0, 5, name)),
                        caret = name.length,
                        message = "Introduced variable $name",
                    ),
                )
            }
        }

    private fun prepareProject(text: String): TextFileEditor {
        val root = temp.resolve("refactoring-ui-project")
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
            Application.init(RefactoringPopupUiTest::class.java.classLoader)
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}

@Composable
private fun RefactoringHarness(state: AppState) {
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
