package stride.app.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.EditorInsets
import stride.app.ui.theme.LocalEditorInsets
import stride.core.services.Application
import stride.core.vfs.VFile

@OptIn(ExperimentalTestApi::class)
class EditorScrollUiTest {
    @TempDir
    lateinit var temp: Path

    private var state: AppState? = null

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(EditorScrollUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        state?.dispose()
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `caret moved past the right edge scrolls horizontally`() = runComposeUiTest {
        val editor = openEditor("Long.kt", buildString {
            append("x".repeat(400))
            repeat(5) { append("\nshort $it") }
        })

        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                editor.Content(Modifier.size(480.dp, 240.dp))
            }
        }
        waitForIdle()

        editor.gotoLine("1:400")
        waitForIdle()
        assertTrue(awaitHorizontal { it > 0f }, "horizontal offset stayed ${horizontalValue()}")

        editor.gotoLine("1:1")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { horizontalValue() == 0f }
        assertEquals(0f, horizontalValue())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `caret moved below the bottom edge scrolls vertically`() = runComposeUiTest {
        val editor = openEditor("Many.kt", (1..200).joinToString("\n") { "line $it" })

        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                editor.Content(Modifier.size(480.dp, 240.dp))
            }
        }
        waitForIdle()

        editor.gotoLine("180:1")
        waitForIdle()
        assertTrue(awaitVertical { it > 0f }, "vertical offset stayed ${verticalValue()}")

        editor.gotoLine("1:1")
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { verticalValue() == 0f }
        assertEquals(0f, verticalValue())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `large documents layer their scroll and shrink back to the sampling path`() = runComposeUiTest {
        val editor = openEditor("Growing.kt", (1..50).joinToString("\n") { "line $it" })

        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                editor.Content(Modifier.size(480.dp, 240.dp))
            }
        }
        waitForIdle()
        assertFalse(editor.verticalScroll.layered, "a 50-line document should stay layer-free")

        editor.updateValue(TextFieldValue((1..3_000).joinToString("\n") { "line $it" }))
        waitForIdle()
        assertTrue(editor.verticalScroll.layered, "a 3 000-line document should scroll in a layer")

        editor.updateValue(TextFieldValue((1..40).joinToString("\n") { "line $it" }))
        waitForIdle()
        assertFalse(editor.verticalScroll.layered, "shrinking below the hysteresis should restore sampling")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `widening the left inset scrolls the caret back into the viewport`() = runComposeUiTest {
        val editor = openEditor("Long.kt", buildString {
            append("x".repeat(600))
            repeat(5) { append("\nshort $it") }
        })
        var leftInset by mutableStateOf(0.dp)

        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                Box(Modifier.size(480.dp, 240.dp)) {
                    CompositionLocalProvider(
                        LocalEditorInsets provides EditorInsets(left = leftInset),
                    ) {
                        editor.Content(Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        editor.gotoLine("1:180")
        waitForIdle()
        val before = horizontalValue()
        assertTrue(before > 0f, "the caret should have scrolled into view, offset $before")

        // Toggling the tree pushes the text column right; the same caret must stay visible.
        leftInset = 220.dp
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { horizontalValue() > before + 100f }
        assertTrue(
            horizontalValue() > before + 100f,
            "the horizontal offset stayed ${horizontalValue()} after the inset grew from $before",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `gutter stays in place when the text scrolls horizontally`() = runComposeUiTest {
        val editor = openEditor("Long.kt", buildString {
            append("x".repeat(400))
            repeat(5) { append("\nshort $it") }
        })

        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                editor.Content(Modifier.size(480.dp, 240.dp))
            }
        }
        waitForIdle()

        val gutterLeftBefore = gutterNode().boundsInRoot.left
        val textLeftBefore = textFieldNode().boundsInRoot.left

        editor.gotoLine("1:400")
        waitForIdle()
        assertTrue(awaitHorizontal { it > 0f }, "horizontal offset stayed ${horizontalValue()}")

        assertEquals(gutterLeftBefore, gutterNode().boundsInRoot.left, 0f)
        assertTrue(
            textFieldNode().boundsInRoot.left < textLeftBefore,
            "text field left stayed ${textFieldNode().boundsInRoot.left}",
        )
    }

    private fun openEditor(name: String, content: String): TextFileEditor {
        val root = temp.resolve("scroll-project")
        Files.createDirectories(root)
        val path = root.resolve(name)
        Files.writeString(path, content)
        val appState = AppState()
        state = appState
        appState.openFolder(root)
        val session = requireNotNull(appState.session)
        return session.editors.openFile(VFile(path)) as TextFileEditor
    }

    private fun ComposeUiTest.gutterNode() =
        onNodeWithTag("gutter", useUnmergedTree = true).fetchSemanticsNode()

    private fun ComposeUiTest.textFieldNode() =
        onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode()

    private fun ComposeUiTest.horizontalValue(): Float =
        onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun ComposeUiTest.verticalValue(): Float =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .single { !it.config.contains(SemanticsActions.SetText) }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun ComposeUiTest.awaitHorizontal(predicate: (Float) -> Boolean): Boolean {
        waitUntil(timeoutMillis = 5_000) { predicate(horizontalValue()) }
        return predicate(horizontalValue())
    }

    private fun ComposeUiTest.awaitVertical(predicate: (Float) -> Boolean): Boolean {
        waitUntil(timeoutMillis = 5_000) { predicate(verticalValue()) }
        return predicate(verticalValue())
    }
}
