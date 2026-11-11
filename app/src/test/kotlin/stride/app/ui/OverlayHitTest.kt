package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyStroke
import stride.app.keys.MacKeymap
import stride.app.keys.dispatchAction
import stride.core.services.Application
import stride.core.vfs.VFile

/** The floating bars sit over the editor; a press on them must not move the caret. */
@ExtendWith(FixedUiFontExtension::class)
class OverlayHitTest {
    @TempDir
    lateinit var temp: Path

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking the status pill does not move the caret`() = runSkikoComposeUiTest(
        size = Size(1280f, 820f),
        density = Density(1f),
    ) {
        val root = temp.resolve("overlay-project")
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("Long.kt"),
            (1..120).joinToString("\n") { line -> "line $line " + "y".repeat(340) },
        )
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            // Scroll away from the top so the pill floats over code.
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val before = editor.value.selection.start

            onNodeWithText("UTF-8").performClick()
            waitForIdle()

            assertEquals(before, editor.value.selection.start, "the status pill press moved the caret")
        } finally {
            state.dispose()
        }
    }

    /**
     * The no-project screen has no Search capsule: opening the popup there would take the modal
     * focus while nothing renders, and every global shortcut would stop answering.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `without a project there is no search capsule and command-O still dispatches`() =
        runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
            val state = AppState()
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalAnimationsEnabled provides false,
                        LocalWordmarkCaretBlink provides false,
                    ) {
                        App(state, onOpenFolder = {})
                    }
                }
                waitForIdle()

                assertEquals(KeyContext.GLOBAL, state.focus, "the welcome screen must not take modal focus")
                assertEquals(
                    0,
                    onAllNodesWithText("Search", useUnmergedTree = true).fetchSemanticsNodes().size,
                    "the welcome screen should not offer the Search capsule",
                )

                val stroke = KeyStroke.parse("meta O")
                assertEquals(ActionId.GOTO_CLASS, MacKeymap.keymap.actionFor(stroke, state.focus))

                val root = temp.resolve("opened-project")
                Files.createDirectories(root)
                var asked = false
                assertTrue(dispatchAction(ActionId.GOTO_CLASS, state) { asked = true; root })
                assertTrue(asked, "command-O should ask for a folder with no project open")
                assertEquals(root.toAbsolutePath().normalize(), requireNotNull(state.session).project.root)

                // A focus left in a modal context by any earlier screen must not survive.
                state.focus = KeyContext.SEARCH_POPUP
                val second = temp.resolve("second-project")
                Files.createDirectories(second)
                state.openFolder(second)
                assertEquals(KeyContext.GLOBAL, state.focus)
            } finally {
                state.dispose()
            }
        }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(OverlayHitTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
