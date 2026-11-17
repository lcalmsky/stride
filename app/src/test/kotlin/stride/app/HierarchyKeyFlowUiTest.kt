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
import androidx.compose.ui.test.hasSetTextAction
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.DeclarationIndex
import stride.search.FileNameIndex

@ExtendWith(FixedUiFontExtension::class)
class HierarchyKeyFlowUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(HierarchyKeyFlowUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control H opens the hierarchy without touching the editor text`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            val editor = session.editors.openFile(VFile(root.resolve("demo/shapes/Shape.kt"))) as TextFileEditor
            val text = editor.value.text
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(editor, "interface Shape", 10)

            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.H) } }

            waitUntil(timeoutMillis = 5_000) { state.hierarchyTree != null }
            assertEquals(text, editor.value.text)
            assertEquals("hierarchy", state.toolWindows.activeId)
            assertEquals("Type Hierarchy of Shape", state.hierarchyTree?.title)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `option command B opens the chooser and enter opens the chosen file`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            session.editors.openFile(VFile(root.resolve("demo/shapes/Shape.kt")))
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(editorOf(session), "interface Shape", 10)

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.B) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.navigationTargets != null }
            onNodeWithText("Choose Implementation of Shape (4 found)").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Circle.kt" }
            assertNull(state.navigationTargets)
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the chooser and returns the keyboard to the editor`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            session.editors.openFile(VFile(root.resolve("demo/shapes/Shape.kt")))
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(editorOf(session), "interface Shape", 10)

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.B) } }
            }
            waitUntil(timeoutMillis = 5_000) { state.navigationTargets != null }

            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.navigationTargets == null }
            assertEquals(KeyContext.EDITOR, state.focus)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command U goes to the super method from the keyboard`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            session.editors.openFile(VFile(root.resolve("demo/shapes/Circle.kt")))
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(editorOf(session), "override fun area", 13)

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.U) } }

            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Shape.kt" }
            val shape = session.editors.selected as TextFileEditor
            assertEquals(shape.value.text.indexOf("area"), shape.value.selection.start)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `both type declaration strokes reach the declared type`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            val main = session.editors.openFile(VFile(root.resolve("demo/Main.kt"))) as TextFileEditor
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(main, "typed", 1)

            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.B) } }
            }
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Circle.kt" }

            session.editors.openFile(VFile(root.resolve("demo/Main.kt")))
            placeCaret(main, "typed", 1)
            waitForIdle()
            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.B) } }
            }
            waitUntil(timeoutMillis = 5_000) { session.editors.selected?.file?.name == "Circle.kt" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control option H opens the call hierarchy from the keyboard`() = runComposeUiTest {
        val root = hierarchyProject()
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            awaitIndexes(session)
            val editor = session.editors.openFile(VFile(root.resolve("demo/Main.kt"))) as TextFileEditor
            setContent { HierarchyKeyFlowHarness(state) }
            focusEditor(state)
            placeCaret(editor, "fun main", 4)

            onRoot().performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.H) } }
            }

            waitUntil(timeoutMillis = 5_000) { state.hierarchyTree != null }
            assertNotNull(state.hierarchyTree)
            assertEquals("Call Hierarchy of main", state.hierarchyTree?.title)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.focusEditor(state: AppState) {
        onNode(hasSetTextAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
    }

    private fun placeCaret(editor: TextFileEditor, marker: String, delta: Int) {
        val text = editor.value.text
        val offset = text.indexOf(marker)
        check(offset >= 0) { "marker not found: $marker" }
        editor.updateValue(TextFieldValue(text, TextRange(offset + delta)))
    }

    private fun editorOf(session: ProjectSession): TextFileEditor =
        session.editors.selected as TextFileEditor

    private fun awaitIndexes(session: ProjectSession) {
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")
        assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
    }

    private fun hierarchyProject(): Path {
        val root = temp.resolve("hierarchy-key-project")
        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write(
            "demo/shapes/Shape.kt",
            """
            package demo.shapes

            interface Shape {
                fun area(): Double
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Circle.kt",
            """
            package demo.shapes

            class Circle(val r: Double) : Shape {
                override fun area() = r * r
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Polygon.kt",
            """
            package demo.shapes

            abstract class Polygon : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Square.kt",
            """
            package demo.shapes

            class Square : Polygon() {
                override fun area() = 1.0
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Solid.kt",
            """
            package demo.shapes

            interface Solid : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Triangle.java",
            """
            package demo.shapes;

            public class Triangle implements Shape {
                public double area() {
                    return 0;
                }
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/Main.kt",
            """
            package demo

            import demo.shapes.Circle

            val typed: Circle = Circle(1.0)

            fun main() {
                println(typed.area())
            }
            """.trimIndent() + "\n",
        )
        return root
    }
}

@Composable
private fun HierarchyKeyFlowHarness(state: AppState) {
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
