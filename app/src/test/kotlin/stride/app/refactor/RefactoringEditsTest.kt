package stride.app.refactor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.vfs.VFile
import stride.lang.TextEdit
import java.nio.file.Files
import java.nio.file.Path

class RefactoringEditsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the given caret is one undo step with the text and the selection before it`() {
        val editor = editorWith("alpha beta\n", TextRange(0, 5))

        assertTrue(editor.applyEdits(listOf(TextEdit(0, 5, "gamma")), caret = 5))
        assertEquals("gamma beta\n", editor.value.text)
        assertEquals(TextRange(5), editor.value.selection)

        assertTrue(editor.undo())
        assertEquals("alpha beta\n", editor.value.text)
        assertEquals(TextRange(0, 5), editor.value.selection)

        assertTrue(editor.redo())
        assertEquals("gamma beta\n", editor.value.text)
        assertEquals(TextRange(5), editor.value.selection)
    }

    @Test
    fun `without a caret the mapped caret stays where the old behavior put it`() {
        val editor = editorWith("alpha beta\n", TextRange(9))

        assertTrue(editor.applyEdits(listOf(TextEdit(0, 5, "gammax"))))
        assertEquals("gammax beta\n", editor.value.text)
        assertEquals(TextRange(10), editor.value.selection)
    }

    private fun editorWith(text: String, selection: TextRange): TextFileEditor {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, text)
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(text, selection))
        return editor
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(RefactoringEditsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
