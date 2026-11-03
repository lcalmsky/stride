package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.ActionId
import stride.core.services.Application
import stride.core.vfs.VFile
import stride.lang.LineStateHighlighter
import stride.lang.kotlin.KotlinLexer
import stride.lang.kotlin.KotlinSyntaxHighlighter
import java.nio.file.Files
import java.nio.file.Path

class TextFileEditorUndoTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `undo restores the text, the selection and a freshly highlighted editor`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "fun main() {}\n")
        val editor = TextFileEditor(VFile(path))
        val original = editor.value

        editor.updateValue(TextFieldValue("fun main() {\n    val x = 1\n}\n", TextRange(25)))

        assertTrue(editor.undo())
        assertEquals(original.text, editor.value.text)
        assertEquals(original.selection, editor.value.selection)
        val fresh = LineStateHighlighter(
            KotlinLexer(),
            original.text,
            KotlinSyntaxHighlighter()::getTokenHighlights,
        )
        assertEquals(fresh.spans(), editor.spans)
    }

    @Test
    fun `redo reapplies an undone change`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "fun main() {}\n")
        val editor = TextFileEditor(VFile(path))
        val changed = "fun main() {\n    val x = 1\n}\n"

        editor.updateValue(TextFieldValue(changed, TextRange(25)))
        assertTrue(editor.undo())
        assertTrue(editor.redo())

        assertEquals(changed, editor.value.text)
        assertEquals(TextRange(25), editor.value.selection)
    }

    @Test
    fun `undo after a duplicate action restores the selection`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "fun main() {}\n")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue("ab\ncd", TextRange(1)))
        val before = editor.value

        assertTrue(editor.applyAction(ActionId.EDITOR_DUPLICATE))
        assertEquals("ab\nab\ncd", editor.value.text)

        assertTrue(editor.undo())
        assertEquals(before.text, editor.value.text)
        assertEquals(before.selection, editor.value.selection)
    }

    @Test
    fun `a read-only editor consumes edit actions without changing the text`() {
        val path = temp.resolve("ReadOnly.kt")
        Files.writeString(path, "val a = 1\n")
        val editor = TextFileEditor(VFile(path), readOnly = true)
        val before = editor.value

        assertTrue(editor.applyAction(ActionId.EDITOR_DUPLICATE))
        assertTrue(editor.applyAction(ActionId.COMMENT_LINE))

        assertEquals(before.text, editor.value.text)
    }

    @Test
    fun `the commenter comes from the file's language`() {
        val ktPath = temp.resolve("Main.kt")
        Files.writeString(ktPath, "")
        val txtPath = temp.resolve("note.txt")
        Files.writeString(txtPath, "")

        val kotlin = TextFileEditor(VFile(ktPath))
        assertEquals("//", kotlin.commenter?.lineCommentPrefix)
        assertEquals("/*", kotlin.commenter?.blockCommentPrefix)
        assertEquals("*/", kotlin.commenter?.blockCommentSuffix)
        assertNull(TextFileEditor(VFile(txtPath)).commenter)
    }

    @Test
    fun `goto line parses line and line column input`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "one\ntwo\nthree")
        val editor = TextFileEditor(VFile(path))

        assertTrue(editor.gotoLine("2"))
        assertEquals(TextRange(4), editor.value.selection)

        assertTrue(editor.gotoLine("3:2"))
        assertEquals(TextRange(9), editor.value.selection)

        assertFalse(editor.gotoLine("abc"))
        assertEquals(TextRange(9), editor.value.selection)
    }

    @Test
    fun `gotoLine and escape drive the popup flag`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "one\ntwo")
        val editor = TextFileEditor(VFile(path))

        assertTrue(editor.applyAction(ActionId.GOTO_LINE))
        assertTrue(editor.gotoLineVisible)
        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))
        assertFalse(editor.gotoLineVisible)
    }

    @Test
    fun `escape collapses a selection and otherwise is not consumed`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "one\ntwo")
        val editor = TextFileEditor(VFile(path))

        assertFalse(editor.applyAction(ActionId.EDITOR_ESCAPE))

        editor.updateValue(TextFieldValue("one\ntwo", TextRange(1, 3)))
        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))
        assertEquals(TextRange(3), editor.value.selection)
    }

    @Test
    fun `an editor without a language commenter consumes the comment action`() {
        val path = temp.resolve("note.txt")
        Files.writeString(path, "plain")
        val editor = TextFileEditor(VFile(path))

        assertTrue(editor.applyAction(ActionId.COMMENT_LINE))
        assertEquals("plain", editor.value.text)
        assertTrue(editor.applyAction(ActionId.COMMENT_BLOCK))
        assertEquals("plain", editor.value.text)
    }

    @Test
    fun `an unknown action is not consumed`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "one")
        val editor = TextFileEditor(VFile(path))

        assertFalse(editor.applyAction(ActionId.SAVE))
    }

    @Test
    fun `request focus before composition returns false without throwing`() {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "fun main() {}\n")
        val editor = TextFileEditor(VFile(path))

        val result = runCatching { editor.focusRequester.requestFocus() }

        assertTrue(result.isSuccess)
        assertEquals(false, result.getOrNull())
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(TextFileEditorUndoTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
