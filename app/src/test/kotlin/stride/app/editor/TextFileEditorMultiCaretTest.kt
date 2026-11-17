package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.ActionId
import stride.core.services.Application
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class TextFileEditorMultiCaretTest {
    @TempDir
    lateinit var temp: Path

    private class FakeClipboard : TextClipboard {
        var text: String? = null

        override fun writeText(text: String): Boolean {
            this.text = text
            return true
        }

        override fun readText(): String? = text
    }

    private fun editor(
        text: String,
        clip: FakeClipboard = FakeClipboard(),
        readOnly: Boolean = false,
    ): TextFileEditor {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, text)
        return TextFileEditor(VFile(path), readOnly = readOnly, clipboard = clip)
    }

    /** A primary caret at [primary] with empty secondary carets at [secondaries], in that order. */
    private fun TextFileEditor.placeCarets(primary: Int, vararg secondaries: Int) {
        if (secondaries.isEmpty()) {
            updateValue(TextFieldValue(value.text, TextRange(primary)))
            return
        }
        updateValue(TextFieldValue(value.text, TextRange(secondaries.first())))
        for (offset in secondaries.drop(1)) toggleCaretAt(offset)
        toggleCaretAt(primary)
    }

    /** The field value a keystroke produces: [text] replaces the primary selection. */
    private fun TextFileEditor.typeAtPrimary(text: String) {
        val selection = value.selection
        val newText = value.text.substring(0, selection.min) + text + value.text.substring(selection.max)
        onFieldValueChange(TextFieldValue(newText, TextRange(selection.min + text.length)))
    }

    @Test
    fun `a field change replicates into the secondary carets and undo restores them`() {
        val editor = editor("aa")
        editor.placeCarets(1, 0)
        assertEquals(listOf(TextRange(0), TextRange(1)), editor.carets)

        editor.onFieldValueChange(TextFieldValue("aaa", TextRange(2)))

        assertEquals("aaaa", editor.value.text)
        assertEquals(listOf(TextRange(1), TextRange(3)), editor.carets)

        assertTrue(editor.undo())
        assertEquals("aa", editor.value.text)
        assertEquals(listOf(TextRange(0), TextRange(1)), editor.carets)

        assertTrue(editor.redo())
        assertEquals("aaaa", editor.value.text)
        assertEquals(listOf(TextRange(1), TextRange(3)), editor.carets)
    }

    @Test
    fun `consecutive typing at multiple carets is one undo step`() {
        val editor = editor("abcd")
        editor.placeCarets(2, 4)

        editor.onFieldValueChange(TextFieldValue("abxcd", TextRange(3)))
        assertEquals("abxcdx", editor.value.text)

        editor.onFieldValueChange(TextFieldValue("abxycdx", TextRange(4)))
        assertEquals("abxycdxy", editor.value.text)

        assertTrue(editor.undo())
        assertEquals("abcd", editor.value.text)
        assertEquals(listOf(TextRange(4), TextRange(2)), editor.carets)
        assertFalse(editor.undo())
    }

    @Test
    fun `typing the character after every caret merges into one undo step`() {
        val editor = editor("a a")
        editor.placeCarets(2, 0)

        editor.typeAtPrimary("a")
        assertEquals("aa aa", editor.value.text)
        editor.typeAtPrimary("a")
        assertEquals("aaa aaa", editor.value.text)

        assertTrue(editor.undo())
        assertEquals("a a", editor.value.text)
        assertEquals(listOf(TextRange(0), TextRange(2)), editor.carets)
        assertFalse(editor.undo())
    }

    @Test
    fun `a selection-only field change drops the secondary carets`() {
        val editor = editor("abcdef")
        editor.placeCarets(1, 4)

        editor.onFieldValueChange(TextFieldValue("abcdef", TextRange(0, 2)))

        assertTrue(editor.secondaryCarets.isEmpty())
        assertEquals(TextRange(0, 2), editor.value.selection)
    }

    @Test
    fun `a composition-only field change keeps the secondary carets`() {
        val editor = editor("ab")
        editor.placeCarets(2, 1)

        editor.onFieldValueChange(TextFieldValue("ab", TextRange(2), TextRange(1, 2)))

        assertEquals(listOf(TextRange(1)), editor.secondaryCarets)
        assertEquals(TextRange(1, 2), editor.value.composition)
    }

    @Test
    fun `a program edit drops the secondary carets and undo brings them back`() {
        val editor = editor("abc")
        editor.placeCarets(2, 0)

        editor.replaceRange(0, 1, "X")

        assertEquals("Xbc", editor.value.text)
        assertTrue(editor.secondaryCarets.isEmpty())

        assertTrue(editor.undo())
        assertEquals("abc", editor.value.text)
        assertEquals(listOf(TextRange(0)), editor.secondaryCarets)
    }

    @Test
    fun `renaming drops the secondary carets and undo brings them back`() {
        val editor = editor("b b")
        editor.placeCarets(0, 2)

        assertEquals(2, editor.renameWord("b", "B"))
        assertTrue(editor.secondaryCarets.isEmpty())

        assertTrue(editor.undo())
        assertEquals(listOf(TextRange(2)), editor.secondaryCarets)
    }

    @Test
    fun `escape closes go to line then the secondary carets then the selection`() {
        val editor = editor("abcdef")
        editor.updateValue(TextFieldValue("abcdef", TextRange(0, 3)))
        editor.toggleCaretAt(5)
        assertTrue(editor.applyAction(ActionId.GOTO_LINE))

        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))
        assertFalse(editor.gotoLineVisible)
        assertEquals(2, editor.carets.size)

        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))
        assertEquals(listOf(TextRange(5)), editor.carets)

        assertFalse(editor.applyAction(ActionId.EDITOR_ESCAPE))
    }

    @Test
    fun `escape collapses a lone selection`() {
        val editor = editor("abcdef")
        editor.updateValue(TextFieldValue("abcdef", TextRange(0, 3)))

        assertTrue(editor.applyAction(ActionId.EDITOR_ESCAPE))

        assertEquals(TextRange(3), editor.value.selection)
    }

    @Test
    fun `comment, duplicate and delete line run at every caret`() {
        val editor = editor("one\ntwo")
        editor.placeCarets(0, 4)
        assertTrue(editor.applyAction(ActionId.COMMENT_LINE))
        assertEquals("// one\n// two", editor.value.text)

        val duplicate = editor("a\nb")
        duplicate.placeCarets(0, 2)
        assertTrue(duplicate.applyAction(ActionId.EDITOR_DUPLICATE))
        assertEquals("a\na\nb\nb", duplicate.value.text)

        val delete = editor("one\ntwo\nthree")
        delete.placeCarets(0, 8)
        assertTrue(delete.applyAction(ActionId.EDITOR_DELETE_LINE))
        assertEquals("two", delete.value.text)
    }

    @Test
    fun `indent, unindent and toggle case run at every caret`() {
        val indent = editor("a\nb")
        indent.placeCarets(0, 2)
        assertTrue(indent.applyAction(ActionId.INDENT))
        assertEquals("    a\n    b", indent.value.text)

        val unindent = editor("    a\n    b")
        unindent.placeCarets(4, 10)
        assertTrue(unindent.applyAction(ActionId.UNINDENT))
        assertEquals("a\nb", unindent.value.text)

        val toggle = editor("abc\ndef")
        toggle.placeCarets(1, 5)
        assertTrue(toggle.applyAction(ActionId.EDITOR_TOGGLE_CASE))
        assertEquals("ABC\nDEF", toggle.value.text)
    }

    @Test
    fun `copy and cut take every caret's line`() {
        val clipboard = FakeClipboard()
        val editor = editor("one\ntwo\nthree", clipboard)
        editor.placeCarets(0, 8)

        assertTrue(editor.applyAction(ActionId.COPY_LINE))
        assertEquals("one\nthree", clipboard.text)

        assertTrue(editor.applyAction(ActionId.CUT_LINE))
        assertEquals("two\n", editor.value.text)
    }

    @Test
    fun `copy joins the selections of every caret`() {
        val clipboard = FakeClipboard()
        val editor = editor("one two", clipboard)
        editor.updateValue(TextFieldValue("one two", TextRange(0, 3)))
        editor.toggleCaretAt(6)

        assertTrue(editor.applyAction(ActionId.COPY_LINE))

        assertEquals("one", clipboard.text)
    }

    @Test
    fun `paste distributes lines and replays the whole clip on a mismatch`() {
        val editor = editor("a\nb")
        editor.placeCarets(0, 2)
        assertTrue(editor.pasteAtCarets("x\ny"))
        assertEquals("xa\nyb", editor.value.text)

        val whole = editor("a\nb")
        whole.placeCarets(0, 2)
        assertTrue(whole.pasteAtCarets("x\ny\nz"))
        assertEquals("x\ny\nza\nx\ny\nzb", whole.value.text)
    }

    @Test
    fun `column mode and block extension select a rectangle`() {
        val editor = editor("abcd\nefgh\nijkl")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

        assertTrue(editor.toggleColumnMode())
        assertTrue(editor.extendBlock(0, 2))
        assertEquals(listOf(TextRange(1, 3)), editor.carets)

        assertTrue(editor.extendBlock(1, 0))
        assertEquals(listOf(TextRange(1, 3), TextRange(6, 8)), editor.carets)

        assertFalse(editor.toggleColumnMode())
    }

    @Test
    fun `clone continues down and a reverse direction removes the last caret`() {
        val editor = editor("ab\ncd\nef")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

        assertTrue(editor.cloneCaret(above = false))
        assertEquals(listOf(TextRange(1), TextRange(4)), editor.carets)

        assertTrue(editor.cloneCaret(above = false))
        assertEquals(listOf(TextRange(1), TextRange(4), TextRange(7)), editor.carets)

        assertTrue(editor.cloneCaret(above = true))
        assertEquals(listOf(TextRange(1), TextRange(4)), editor.carets)
    }

    @Test
    fun `select next occurrence grows the caret set through its actions`() {
        val editor = editor("foo bar foo")
        editor.updateValue(TextFieldValue("foo bar foo", TextRange(1)))

        assertTrue(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
        assertEquals(listOf(TextRange(0, 3)), editor.carets)

        assertTrue(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
        assertEquals(listOf(TextRange(0, 3), TextRange(8, 11)), editor.carets)

        assertTrue(editor.applyAction(ActionId.UNSELECT_PREVIOUS_OCCURRENCE))
        assertEquals(listOf(TextRange(0, 3)), editor.carets)

        assertTrue(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
        assertEquals(listOf(TextRange(0, 3), TextRange(8, 11)), editor.carets)
        assertFalse(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
    }

    @Test
    fun `selecting occurrences after cloning clears the clone history`() {
        val editor = editor("ab\ncd\nef\ngh")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(4)))

        assertTrue(editor.cloneCaret(above = false))
        assertEquals(listOf(TextRange(4), TextRange(7)), editor.carets)

        assertTrue(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
        assertEquals(listOf(TextRange(3, 5), TextRange(6, 8)), editor.carets)

        assertTrue(editor.cloneCaret(above = true))
        assertEquals(listOf(TextRange(3, 5), TextRange(6, 8), TextRange(2)), editor.carets)
    }

    @Test
    fun `selecting occurrences during a block starts a new block afterwards`() {
        val editor = editor("abcd\nabcd\nabcd")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))
        assertTrue(editor.toggleColumnMode())
        assertTrue(editor.extendBlock(1, 0))
        assertEquals(listOf(TextRange(1), TextRange(6)), editor.carets)

        assertTrue(editor.applyAction(ActionId.SELECT_NEXT_OCCURRENCE))
        assertEquals(listOf(TextRange(0, 4), TextRange(5, 9)), editor.carets)

        assertTrue(editor.extendBlock(0, -1))
        assertEquals(listOf(TextRange(9, 8)), editor.carets)
    }

    @Test
    fun `a read-only editor adds and moves carets but does not change the text`() {
        val clipboard = FakeClipboard()
        val editor = editor("abc def", clipboard, readOnly = true)
        editor.updateValue(TextFieldValue("abc def", TextRange(0, 3)))
        editor.toggleCaretAt(5)

        assertTrue(editor.pasteAtCarets("x"))
        assertTrue(editor.deleteAtCarets(forward = false, word = false))
        assertTrue(editor.applyAction(ActionId.COMMENT_LINE))
        assertEquals("abc def", editor.value.text)

        assertTrue(editor.applyAction(ActionId.COPY_LINE))
        assertEquals("abc", clipboard.text)
    }

    @Test
    fun `moving carets folds and extends their selections`() {
        val editor = editor("abc\ndef")
        editor.placeCarets(1, 5)

        assertTrue(editor.moveCarets(CaretMove.LEFT, extend = false))
        assertEquals(listOf(TextRange(4), TextRange(0)), editor.carets)

        assertTrue(editor.moveCarets(CaretMove.RIGHT, extend = true))
        assertEquals(listOf(TextRange(4, 5), TextRange(0, 1)), editor.carets)
    }

    @Test
    fun `undo of a multi-caret deletion restores the text and carets`() {
        val editor = editor("abc\ndef")
        editor.placeCarets(2, 6)

        assertTrue(editor.deleteAtCarets(forward = false, word = false))
        assertEquals("ac\ndf", editor.value.text)

        assertTrue(editor.undo())
        assertEquals("abc\ndef", editor.value.text)
        assertEquals(listOf(TextRange(6), TextRange(2)), editor.carets)
        assertNull(editor.value.composition)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(TextFileEditorMultiCaretTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
