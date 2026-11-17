package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.ActionId
import stride.core.vfs.VFile

class TextFileEditorChangeReportTest {
    @TempDir
    lateinit var temp: Path

    private data class Report(val offset: Int, val oldLength: Int, val newLength: Int, val recorded: Boolean)

    private fun editorWithReports(text: String, caret: Int = 0): Pair<TextFileEditor, MutableList<Report>> {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, text)
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(text, TextRange(caret)))
        val reports = mutableListOf<Report>()
        editor.onTextChange = { changes, recorded ->
            for (change in changes) {
                reports.add(Report(change.offset, change.oldLength, change.newText.length, recorded))
            }
        }
        return editor to reports
    }

    private fun TextFileEditor.typeAtPrimary(text: String) {
        val selection = value.selection
        val newText = value.text.substring(0, selection.min) + text + value.text.substring(selection.max)
        onFieldValueChange(TextFieldValue(newText, TextRange(selection.min + text.length)))
    }

    @Test
    fun `typing reports the replaced range as recorded`() {
        val (editor, reports) = editorWithReports("ab", caret = 1)

        editor.typeAtPrimary("x")

        assertEquals(listOf(Report(1, 0, 1, recorded = true)), reports)
    }

    @Test
    fun `an action reports its change as recorded`() {
        val (editor, reports) = editorWithReports("ab\ncd", caret = 1)

        assertTrue(editor.applyAction(ActionId.EDITOR_DUPLICATE))

        assertEquals(1, reports.size)
        assertTrue(reports.single().recorded)
        assertEquals("ab\nab\ncd", editor.value.text)
    }

    @Test
    fun `multi caret typing reports one change as recorded`() {
        val (editor, reports) = editorWithReports("aa", caret = 1)
        editor.toggleCaretAt(0)
        assertEquals(2, editor.carets.size)

        editor.typeAtPrimary("b")

        assertEquals("baba", editor.value.text)
        assertEquals(1, reports.size)
        assertTrue(reports.single().recorded)
    }

    @Test
    fun `undo and redo report their change as unrecorded`() {
        val (editor, reports) = editorWithReports("ab", caret = 1)
        editor.typeAtPrimary("x")
        reports.clear()

        assertTrue(editor.undo())
        assertEquals(listOf(Report(1, 1, 0, recorded = false)), reports)

        reports.clear()
        assertTrue(editor.redo())
        assertEquals(listOf(Report(1, 0, 1, recorded = false)), reports)
    }

    @Test
    fun `a rename reports one change per replaced word`() {
        val (editor, reports) = editorWithReports("foo X foo Y foo", caret = 0)

        assertEquals(3, editor.renameWord("foo", "bar"))

        assertEquals(
            listOf(
                Report(0, 3, 3, recorded = true),
                Report(6, 3, 3, recorded = true),
                Report(12, 3, 3, recorded = true),
            ),
            reports,
        )
    }

    @Test
    fun `a reformat reports each replaced line, the later ones after the earlier insertions`() {
        val text = "class A {\nval a = 1\nval b = 2\n}\n"
        val (editor, reports) = editorWithReports(text, caret = 0)

        assertEquals("Reformatted 2 lines", editor.reformat())

        assertEquals(
            listOf(
                Report(10, 9, 13, recorded = true),
                Report(24, 9, 13, recorded = true),
            ),
            reports,
        )
    }

    @Test
    fun `a selection only change reports nothing`() {
        val (editor, reports) = editorWithReports("abc", caret = 0)

        editor.onFieldValueChange(TextFieldValue("abc", TextRange(2)))

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a text change clears the usage highlights`() {
        val (editor, reports) = editorWithReports("alpha", caret = 5)
        editor.usageHighlights = listOf(0 until 5)

        editor.typeAtPrimary("x")

        assertTrue(reports.single().recorded)
        assertTrue(editor.usageHighlights.isEmpty())
    }

    @Test
    fun `an escape with no other work clears the usage highlights`() {
        val (editor, reports) = editorWithReports("alpha", caret = 5)
        editor.usageHighlights = listOf(0 until 5)

        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))

        assertTrue(editor.usageHighlights.isEmpty())
        assertFalse(reports.any { it.recorded })
    }
}
