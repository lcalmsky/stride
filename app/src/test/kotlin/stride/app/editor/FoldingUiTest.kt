package stride.app.editor

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.dispatchAction
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@OptIn(ExperimentalTestApi::class)
class FoldingUiTest {
    @TempDir
    lateinit var temp: Path

    private var state: AppState? = null

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(FoldingUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        state?.dispose()
        Application.resetForTests()
    }

    private val sample = """
        package demo

        import alpha.One
        import beta.Two

        class Greeter {
            fun greet(): String {
                val word = "hi"
                return word
            }
        }
    """.trimIndent()

    private val funOpen: Int get() = sample.indexOf("fun greet(): String {") + "fun greet(): String ".length
    private val functionFold: FoldRegion
        get() = FoldRegion(funOpen + 1, sample.indexOf("\n    }") + 5)
    private val importFold: FoldRegion
        get() = FoldRegion(
            sample.indexOf("import ") + "import ".length,
            sample.indexOf('\n', sample.indexOf("import beta.Two")),
        )

    private fun openEditor(name: String = "Main.kt", content: String = sample): TextFileEditor {
        val root = temp.resolve("folding-project")
        Files.createDirectories(root)
        val path = root.resolve(name)
        Files.writeString(path, content)
        val appState = AppState()
        state = appState
        appState.openFolder(root)
        val session = requireNotNull(appState.session)
        return session.editors.openFile(VFile(path)) as TextFileEditor
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun ComposeUiTest.verticalValue(): Float =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .single { !it.config.contains(SemanticsActions.SetText) }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun ComposeUiTest.collapseAt(editor: TextFileEditor, offset: Int) {
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(offset)))
        waitForIdle()
        editor.applyAction(ActionId.COLLAPSE_REGION)
        waitForIdle()
    }

    @Test
    fun `the field lays out the folded text and keeps a caret at every fold boundary`() = runComposeUiTest {
        val editor = openEditor()
        setContent { FoldingHarness(state!!) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state?.focus == KeyContext.EDITOR }

        collapseAt(editor, funOpen)
        assertEquals(listOf(functionFold), editor.foldedRegions)
        assertTrue(onNode(hasSetTextAction()).textLayout().layoutInput.text.text.contains("{...}"))

        val start = functionFold.hiddenStart
        val end = functionFold.hiddenEnd
        for (offset in listOf(start, end, end + 1)) {
            editor.updateValue(TextFieldValue(sample, TextRange(offset)))
            waitForIdle()
            assertEquals(TextRange(offset), editor.value.selection)
            assertEquals(listOf(functionFold), editor.foldedRegions)
        }
    }

    @Test
    fun `the gutter skips the numbers of hidden lines`() = runComposeUiTest {
        val content = "fun a() {\n    one\n    two\n}\nend\n"
        val editor = openEditor(content = content)
        setContent { FoldingComposeHarness(editor) }
        waitForIdle()

        val fold = FoldRegion(content.indexOf('{') + 1, content.indexOf('}'))
        editor.updateValue(TextFieldValue(content, TextRange(0)))
        waitForIdle()
        editor.applyAction(ActionId.COLLAPSE_REGION)
        waitForIdle()
        assertEquals(listOf(fold), editor.foldedRegions)

        val gutter = onNode(hasTestTag("gutter"), useUnmergedTree = true).fetchSemanticsNode()
        val text = gutter.children
            .filter { it.config.contains(SemanticsProperties.Text) }
            .flatMap { it.config[SemanticsProperties.Text] }
            .joinToString("\n") { it.text }
        // Lines 2..4 are hidden, so the rows number 1, 5, 6.
        assertEquals("1\n5\n6", text)
    }

    @Test
    fun `pressing right at a fold start lands on its end`() = runComposeUiTest {
        val editor = openEditor()
        setContent { FoldingHarness(state!!) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state?.focus == KeyContext.EDITOR }

        collapseAt(editor, funOpen)
        val start = functionFold.hiddenStart
        editor.updateValue(TextFieldValue(sample, TextRange(start)))
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000) { editor.value.selection == TextRange(functionFold.hiddenEnd) }
        assertEquals(listOf(functionFold), editor.foldedRegions)
    }

    @Test
    fun `a click on the placeholder expands the fold`() = runComposeUiTest {
        val editor = openEditor()
        setContent { FoldingHarness(state!!) }
        val field = onNode(hasSetTextAction())
        field.performClick()
        waitUntil(timeoutMillis = 5_000) { state?.focus == KeyContext.EDITOR }

        collapseAt(editor, funOpen)
        val layout = field.textLayout()
        val placeholder = layout.layoutInput.text.text.indexOf("...")
        assertTrue(placeholder > 0)
        val box = layout.getBoundingBox(placeholder)

        field.performMouseInput { click(Offset(box.left + 1f, box.center.y)) }
        waitUntil(timeoutMillis = 5_000) { editor.foldedRegions.isEmpty() }
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `the window keymap collapses and expands the region`() = runComposeUiTest {
        val editor = openEditor()
        setContent { FoldingHarness(state!!) }
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state?.focus == KeyContext.EDITOR }
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        waitForIdle()

        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Minus) } }
        waitUntil(timeoutMillis = 5_000) { editor.foldedRegions == listOf(functionFold) }

        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Equals) } }
        waitUntil(timeoutMillis = 5_000) { editor.foldedRegions.isEmpty() }
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `a fold change does not re-run an old scroll center request`() = runComposeUiTest {
        val body = (1..4).joinToString("\n") { "    val v$it = $it" }
        val tail = (1..400).joinToString("\n") { "val t$it = $it" }
        val content = "fun small() {\n$body\n}\n$tail\n"
        val editor = openEditor(content = content)
        setContent { FoldingHarness(state!!) }
        waitForIdle()

        editor.updateValue(TextFieldValue(content, TextRange(content.indexOf("val t300 = 300"))))
        waitForIdle()
        val router = KeyRouter(isMac = true)
        val center = router.route(KeyEventType.DOWN, Key.L, ctrl = true, focus = KeyContext.EDITOR)
        assertEquals(ActionId.EDITOR_SCROLL_TO_CENTER, center)
        dispatchAction(requireNotNull(center), state!!) { null }
        waitForIdle()
        val centered = editor.verticalScroll.value

        editor.verticalScroll.dispatchRawDelta(150f)
        waitForIdle()
        val before = editor.verticalScroll.value
        assertTrue(before > centered, "the manual scroll did not move past $centered")

        editor.applyAction(ActionId.COLLAPSE_ALL_REGIONS)
        waitForIdle()
        assertEquals(1, editor.foldedRegions.size)
        assertEquals(before, editor.verticalScroll.value, "folding re-centered the view")
    }

    @Test
    fun `an option drag from a placeholder keeps the fold and starts at its hidden start`() = runComposeUiTest {
        val editor = openEditor()
        setContent { FoldingHarness(state!!) }
        val field = onNode(hasSetTextAction())
        field.performClick()
        waitUntil(timeoutMillis = 5_000) { state?.focus == KeyContext.EDITOR }

        editor.updateValue(TextFieldValue(sample, TextRange(sample.indexOf("import alpha"))))
        waitForIdle()
        editor.applyAction(ActionId.COLLAPSE_REGION)
        waitForIdle()
        assertEquals(listOf(importFold), editor.foldedRegions)

        val layout = field.textLayout()
        val placeholder = layout.layoutInput.text.text.indexOf("...")
        assertTrue(placeholder > 0)
        val box = layout.getBoundingBox(placeholder)
        val press = Offset(box.left + box.width * 0.6f, box.center.y)

        onRoot().performKeyInput { keyDown(Key.AltLeft) }
        field.performMouseInput {
            moveTo(press)
            press()
            moveTo(Offset(press.x + 1f, press.y))
            release()
        }
        onRoot().performKeyInput { keyUp(Key.AltLeft) }
        waitForIdle()

        assertEquals(listOf(importFold), editor.foldedRegions)
        assertEquals(importFold.hiddenStart, editor.carets.single().start)
    }

    @Test
    fun `caret following scrolls to the row of a caret below a large fold`() = runComposeUiTest {
        val hidden = (2..300).joinToString("\n") { "    body $it" }
        val tail = (302..400).joinToString("\n") { "tail $it" }
        val content = "fun big() {\n$hidden\n}\n$tail\n"
        val editor = openEditor(content = content)
        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                Box(Modifier.size(480.dp, 240.dp)) {
                    editor.Content(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        editor.updateValue(TextFieldValue(content, TextRange(0)))
        waitForIdle()
        editor.applyAction(ActionId.COLLAPSE_ALL_REGIONS)
        waitForIdle()

        editor.gotoLine("302:1")
        waitForIdle()
        assertEquals(0f, verticalValue(), 1f)
    }
}

@Composable
private fun FoldingHarness(state: AppState) {
    val router = remember { KeyRouter(isMac = true) }
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event -> handleWindowKeyEvent(event, router, state) { null } }
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

@Composable
private fun FoldingComposeHarness(editor: TextFileEditor) {
    CompositionLocalProvider(LocalAnimationsEnabled provides false) {
        Box(Modifier.size(480.dp, 240.dp)) {
            editor.Content(Modifier.fillMaxSize())
        }
    }
}
