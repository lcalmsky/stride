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
        editor.onTextChange = { changes, recorded ->
            locations.changes(
                path = editor.file.path.toAbsolutePath().normalize(),
                changes = changes,
                lineOf = { offset -> BookmarkManager.lineIndex(editor.value.text, offset) },
                recorded = recorded,
            )
        }
        return editor to locations
    }

    private fun EditLocations.offsets(): List<Int> = places.map { it.offset }

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
