package stride.app.navigation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindBarState
import stride.core.vfs.VFile

/** Wires an editor to edit locations the way the app does and checks what multi-range edits leave. */
class EditLocationTrackingTest {
    @TempDir
    lateinit var temp: Path

    private fun trackedEditor(text: String, caret: Int): Pair<TextFileEditor, EditLocations> {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, text)
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(text, TextRange(caret)))
        val locations = EditLocations()
        editor.onTextChange = { changes, recorded, recordIndex ->
            locations.changes(
                path = editor.file.path.toAbsolutePath().normalize(),
                changes = changes,
                lineOf = { offset -> BookmarkManager.lineIndex(editor.value.text, offset) },
                recorded = recorded,
                recordIndex = recordIndex,
            )
        }
        return editor to locations
    }

    private fun EditLocations.offsets(): List<Int> = places.map { it.offset }

    private fun EditLocations.recordPlace(editor: TextFileEditor, offset: Int) {
        change(
            path = editor.file.path.toAbsolutePath().normalize(),
            offset = offset,
            oldLength = 0,
            newLength = 0,
            lineOf = { at -> BookmarkManager.lineIndex(editor.value.text, at) },
            recorded = true,
        )
    }

    private fun TextFileEditor.typeAtPrimary(text: String) {
        val selection = value.selection
        val newText = value.text.substring(0, selection.min) + text + value.text.substring(selection.max)
        onFieldValueChange(TextFieldValue(newText, TextRange(selection.min + text.length)))
    }

    @Test
    fun `a place between two carets stays put after multi caret typing`() {
        val (editor, locations) = trackedEditor("ab\ncd", caret = 0)
        locations.recordPlace(editor, 2)
        editor.toggleCaretAt(5)

        editor.typeAtPrimary("x")

        assertEquals("xab\ncdx", editor.value.text)
        assertTrue(3 in locations.offsets(), "the place between the carets moved: ${locations.offsets()}")
        assertEquals(6, locations.places.first().offset, "the recorded place is not the primary caret's")
    }

    @Test
    fun `undo moves a single range place back exactly as the change moved it`() {
        val (editor, locations) = trackedEditor("ab\ncd", caret = 1)
        locations.recordPlace(editor, 4)

        editor.typeAtPrimary("x")
        assertTrue(5 in locations.offsets(), "after typing: ${locations.offsets()}")

        assertTrue(editor.undo())

        assertTrue(4 in locations.offsets(), "after undo: ${locations.offsets()}")
    }

    @Test
    fun `a rename keeps a place that sits between two replaced words`() {
        val text = "foo\nfoo X foo Y foo\n"
        val (editor, locations) = trackedEditor(text, caret = 8)
        editor.updateValue(TextFieldValue(text.substring(0, 8) + "Z" + text.substring(8), TextRange(9)))
        assertEquals(listOf(8), locations.offsets())

        assertEquals(4, editor.renameWord("foo", "bar"))

        assertEquals("bar\nbar ZX bar Y bar\n", editor.value.text)
        assertTrue(8 in locations.offsets(), "the place on X moved: ${locations.offsets()}")
    }

    @Test
    fun `replace all from the find bar keeps a place between two replacements`() {
        val text = "foo\nfoo X foo Y foo\n"
        val (editor, locations) = trackedEditor(text, caret = 8)
        editor.updateValue(TextFieldValue(text.substring(0, 8) + "Z" + text.substring(8), TextRange(9)))
        assertEquals(listOf(8), locations.offsets())
        val findBar = FindBarState()
        findBar.open(editor, replace = true)
        findBar.query = "foo"
        findBar.replacement = "bar"
        findBar.refresh(editor)

        findBar.replaceAll(editor)

        assertEquals("bar\nbar ZX bar Y bar\n", editor.value.text)
        assertTrue(8 in locations.offsets(), "the place on X moved: ${locations.offsets()}")
    }

    @Test
    fun `a place between two renamed words stays put through undo and redo`() {
        val text = "foo\nfoo X foo Y foo\n"
        val (editor, locations) = trackedEditor(text, caret = 8)
        editor.updateValue(TextFieldValue(text.substring(0, 8) + "Z" + text.substring(8), TextRange(9)))
        assertEquals(listOf(8), locations.offsets())

        assertEquals(4, editor.renameWord("foo", "quux"))
        assertTrue(10 in locations.offsets(), "after rename: ${locations.offsets()}")

        assertTrue(editor.undo())
        assertTrue(8 in locations.offsets(), "after undo: ${locations.offsets()}")

        assertTrue(editor.redo())
        assertTrue(10 in locations.offsets(), "after redo: ${locations.offsets()}")
    }

    @Test
    fun `a place between two replace all results stays put through undo`() {
        val text = "foo\nfoo X foo Y foo\n"
        val (editor, locations) = trackedEditor(text, caret = 8)
        editor.updateValue(TextFieldValue(text.substring(0, 8) + "Z" + text.substring(8), TextRange(9)))
        assertEquals(listOf(8), locations.offsets())
        val findBar = FindBarState()
        findBar.open(editor, replace = true)
        findBar.query = "foo"
        findBar.replacement = "quux"
        findBar.refresh(editor)

        findBar.replaceAll(editor)

        assertEquals(10, locations.places.last().offset, "after replace all: ${locations.offsets()}")

        assertTrue(editor.undo())

        assertTrue(8 in locations.offsets(), "after undo: ${locations.offsets()}")
    }

    @Test
    fun `a reformat keeps a place on a line below the first reindented line`() {
        val text = "class A {\nval a = 1\nval b = 2\n}\n"
        val (editor, locations) = trackedEditor(text, caret = 0)
        val second = text.indexOf("val b")
        editor.updateValue(TextFieldValue(text.substring(0, second) + "x" + text.substring(second), TextRange(second + 1)))
        assertEquals(listOf(second), locations.offsets())

        assertEquals("Reformatted 2 lines", editor.reformat())

        assertEquals("class A {\n    val a = 1\n    xval b = 2\n}\n", editor.value.text)
        assertTrue(second + 4 in locations.offsets(), "the place on the second line moved: ${locations.offsets()}")
    }
}
