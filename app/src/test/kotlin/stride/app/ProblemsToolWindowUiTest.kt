package stride.app

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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.problems.CompilerMessage
import stride.app.problems.ErrorDescriptionRequest
import stride.app.problems.FileProblem
import stride.app.problems.ProblemScope
import stride.app.problems.ProblemSource
import stride.app.problems.ProblemsModel
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ProblemSeverity

@ExtendWith(FixedUiFontExtension::class)
class ProblemsToolWindowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(ProblemsToolWindowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command 6 shows the file range and enter moves the caret onto the problem`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("problems-project"))
        val text = "fun main() {\n    println(1)\n}\n"
        val file = root.resolve("Main.kt")
        Files.writeString(file, text)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            val model = session.project.service<ProblemsModel>()
            model.publishBuild(
                listOf(
                    CompilerMessage(
                        file.toAbsolutePath().normalize(),
                        1,
                        4,
                        ProblemSeverity.ERROR,
                        "Unresolved reference: println",
                    ),
                ),
            )
            val expected = text.indexOf("println")
            check(expected >= 0)

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Six)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            waitUntil(timeoutMillis = 5_000) { editor.problems.isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Unresolved reference", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(expected, editor.value.selection.start)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `project errors rows open the file at the error's line and column`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("project-errors"))
        val openFile = root.resolve("A.kt")
        Files.writeString(openFile, "alpha\nbeta\n")
        val target = root.resolve("B.kt")
        val targetText = "one\ntwo\n"
        Files.writeString(target, targetText)
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val openEditor = session.editors.openFile(VFile(openFile)) as TextFileEditor
            val model = session.project.service<ProblemsModel>()
            model.publishBuild(
                listOf(
                    CompilerMessage(openFile.toAbsolutePath().normalize(), 0, 0, ProblemSeverity.ERROR, "A broke"),
                    CompilerMessage(target.toAbsolutePath().normalize(), 1, 2, ProblemSeverity.ERROR, "B broke"),
                ),
            )

            setContent { ListWindowHarness(state) }
            pressCommand(Key.Six)
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.LIST_WINDOW }
            onNodeWithText("Project Errors 2").performClick()
            waitUntil(timeoutMillis = 5_000) { state.problemsToolWindow.scope == ProblemScope.PROJECT_ERRORS }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("B broke", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "B.kt" }
            val opened = session.editors.selected as TextFileEditor
            assertEquals(targetText.indexOf("two") + 2, opened.value.selection.start)
            assertEquals(KeyContext.EDITOR, state.focus)
            assertEquals(openEditor, session.editors.openFiles.first())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `editing the text recomputes the problems after the debounce`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("debounce-project"))
        val file = root.resolve("Main.kt")
        Files.writeString(file, "alpha\nbeta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(file)) as TextFileEditor
            val model = session.project.service<ProblemsModel>()
            model.publishBuild(
                listOf(
                    CompilerMessage(
                        file.toAbsolutePath().normalize(),
                        0,
                        0,
                        ProblemSeverity.ERROR,
                        "alpha is not defined",
                    ),
                ),
            )

            setContent { ListWindowHarness(state) }
            waitUntil(timeoutMillis = 5_000) { editor.problems.size == 1 }

            editor.updateValue(TextFieldValue("x\nbeta\n", TextRange(0)))

            waitUntil(timeoutMillis = 5_000) { editor.problems.isEmpty() }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the error description closes and returns the keyboard when another file is selected`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("error-description-project"))
        val first = root.resolve("A.kt")
        Files.writeString(first, "alpha\n")
        val second = root.resolve("B.kt")
        Files.writeString(second, "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(first)) as TextFileEditor
            val problem = FileProblem(ProblemSeverity.ERROR, "broke", ProblemSource.EDITOR, 0, 0, 0, 5)
            state.errorDescription = ErrorDescriptionRequest(editor, listOf(problem))
            state.focus = KeyContext.CONTEXT_POPUP

            setContent { ListWindowHarness(state) }
            waitForIdle()

            session.editors.openFile(VFile(second))

            waitUntil(timeoutMillis = 5_000) { state.errorDescription == null }
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a build fills the problems of an open editor that is not selected`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("unselected-project"))
        val first = root.resolve("A.kt")
        Files.writeString(first, "alpha\n")
        val second = root.resolve("B.kt")
        Files.writeString(second, "beta\n")
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val firstEditor = session.editors.openFile(VFile(first)) as TextFileEditor
            session.editors.openFile(VFile(second))
            val model = session.project.service<ProblemsModel>()
            model.publishBuild(
                listOf(
                    CompilerMessage(
                        first.toAbsolutePath().normalize(),
                        0,
                        0,
                        ProblemSeverity.ERROR,
                        "alpha is not defined",
                    ),
                ),
            )

            setContent { ListWindowHarness(state) }

            waitUntil(timeoutMillis = 5_000) { firstEditor.problems.isNotEmpty() }
            assertEquals("alpha is not defined", firstEditor.problems.single().message)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }
}

@Composable
private fun ListWindowHarness(state: AppState) {
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
