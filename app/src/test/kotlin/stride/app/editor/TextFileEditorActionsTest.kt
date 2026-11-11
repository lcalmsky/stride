package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.ActionId
import stride.core.services.Application
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class TextFileEditorActionsTest {
    @TempDir
    lateinit var temp: Path

    private class FakeClipboard : TextClipboard {
        var text: String? = null
        var accepts = true

        override fun writeText(text: String): Boolean {
            if (accepts) this.text = text
            return accepts
        }
    }

    private fun editor(
        text: String,
        name: String = "Main.kt",
        clipboard: TextClipboard = FakeClipboard(),
        readOnly: Boolean = false,
    ): TextFileEditor {
        val path = temp.resolve(name)
        Files.writeString(path, text)
        return TextFileEditor(VFile(path), readOnly = readOnly, clipboard = clipboard)
    }

    @Test
    fun `extend selection walks the ladder and shrink walks back`() {
        val editor = editor("call(alpha, beta)")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(8)))

        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(5, 10), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(5, 16), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(4, 17), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(0, 17), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(0, 17), editor.value.selection)

        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(4, 17), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(5, 16), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(5, 10), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(8), editor.value.selection)

        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(8), editor.value.selection)
    }

    @Test
    fun `another selection change clears the selection stack`() {
        val editor = editor("call(alpha, beta)")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(8)))
        assertTrue(editor.applyAction(ActionId.EDITOR_SELECT_WORD))
        assertEquals(TextRange(5, 10), editor.value.selection)

        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))
        assertTrue(editor.applyAction(ActionId.EDITOR_UNSELECT_WORD))
        assertEquals(TextRange(1), editor.value.selection)
    }

    @Test
    fun `join lines and start new line are one undo step each`() {
        val editor = editor("  one\n    two")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(3)))

        assertTrue(editor.applyAction(ActionId.EDITOR_JOIN_LINES))
        assertEquals("  one two", editor.value.text)
        assertEquals(TextRange(6), editor.value.selection)
        assertTrue(editor.undo())
        assertEquals("  one\n    two", editor.value.text)

        assertTrue(editor.applyAction(ActionId.EDITOR_START_NEW_LINE_BEFORE))
        assertEquals("  \n  one\n    two", editor.value.text)
        assertEquals(TextRange(2), editor.value.selection)
        assertTrue(editor.undo())
        assertEquals("  one\n    two", editor.value.text)
    }

    @Test
    fun `toggle case edits the selected text`() {
        val editor = editor("hello world")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(0, 5)))

        assertTrue(editor.applyAction(ActionId.EDITOR_TOGGLE_CASE))
        assertEquals("HELLO world", editor.value.text)
        assertEquals(TextRange(0, 5), editor.value.selection)
    }

    @Test
    fun `match brace and code block actions move the caret without an undo step`() {
        val text = "fun f() {\n    call()\n}\n"
        val editor = editor(text)
        val call = text.indexOf("call")
        val open = text.indexOf('(', call)

        editor.updateValue(TextFieldValue(text, TextRange(open)))
        assertTrue(editor.applyAction(ActionId.EDITOR_MATCH_BRACE))
        assertEquals(TextRange(open + 1), editor.value.selection)

        editor.updateValue(TextFieldValue(text, TextRange(call)))
        assertTrue(editor.applyAction(ActionId.EDITOR_CODE_BLOCK_START))
        assertEquals(TextRange(text.indexOf('{')), editor.value.selection)

        editor.updateValue(TextFieldValue(text, TextRange(call)))
        assertTrue(editor.applyAction(ActionId.EDITOR_CODE_BLOCK_END))
        assertEquals(TextRange(text.lastIndexOf('}')), editor.value.selection)

        assertFalse(editor.undo.canUndo)
    }

    @Test
    fun `method down and up jump between declarations`() {
        val text = "fun a() {}\n\nclass B\nval c = 1"
        val editor = editor(text)
        editor.updateValue(TextFieldValue(text, TextRange(1)))

        assertTrue(editor.applyAction(ActionId.METHOD_DOWN))
        assertEquals(TextRange(12), editor.value.selection)
        assertTrue(editor.applyAction(ActionId.METHOD_UP))
        assertEquals(TextRange(0), editor.value.selection)
    }

    @Test
    fun `copy line with an empty selection writes the line and its newline`() {
        val clipboard = FakeClipboard()
        val editor = editor("one\ntwo", clipboard = clipboard)
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

        assertTrue(editor.applyAction(ActionId.COPY_LINE))
        assertEquals("one\n", clipboard.text)
        assertEquals("one\ntwo", editor.value.text)
        assertFalse(editor.undo.canUndo)
    }

    @Test
    fun `cut line removes the line and undo restores it`() {
        val clipboard = FakeClipboard()
        val editor = editor("one\ntwo", clipboard = clipboard)
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

        assertTrue(editor.applyAction(ActionId.CUT_LINE))
        assertEquals("one\n", clipboard.text)
        assertEquals("two", editor.value.text)
        assertEquals(TextRange(0), editor.value.selection)
        assertTrue(editor.undo())
        assertEquals("one\ntwo", editor.value.text)
    }

    @Test
    fun `copy and cut leave a real selection to the text field`() {
        val clipboard = FakeClipboard()
        val editor = editor("one\ntwo", clipboard = clipboard)
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(0, 3)))

        assertFalse(editor.applyAction(ActionId.COPY_LINE))
        assertFalse(editor.applyAction(ActionId.CUT_LINE))
        assertNull(clipboard.text)
        assertEquals("one\ntwo", editor.value.text)
    }

    @Test
    fun `a failing clipboard leaves the key to the text field`() {
        val clipboard = FakeClipboard().apply { accepts = false }
        val editor = editor("one", clipboard = clipboard)

        assertFalse(editor.applyAction(ActionId.COPY_LINE))
    }

    @Test
    fun `hippie completion cycles through the nearest words`() {
        val text = "val value = validator + val"
        val editor = editor(text)
        editor.updateValue(TextFieldValue(text, TextRange(text.length)))

        assertTrue(editor.hippieComplete())
        assertEquals("val value = validator + validator", editor.value.text)

        assertTrue(editor.hippieComplete())
        assertEquals("val value = validator + value", editor.value.text)

        assertTrue(editor.hippieComplete())
        assertEquals("val value = validator + validator", editor.value.text)
    }

    @Test
    fun `apply action routes hippie completion`() {
        val text = "val value = val"
        val editor = editor(text)
        editor.updateValue(TextFieldValue(text, TextRange(text.length)))

        assertTrue(editor.applyAction(ActionId.HIPPIE_COMPLETION))
        assertEquals("val value = value", editor.value.text)
    }

    @Test
    fun `hippie completion restarts after another edit`() {
        val text = "alpha alpine alp"
        val editor = editor(text)
        editor.updateValue(TextFieldValue(text, TextRange(16)))

        assertTrue(editor.hippieComplete())
        assertEquals("alpha alpine alpine", editor.value.text)
        assertEquals(TextRange(19), editor.value.selection)

        // An edit after the caret is still an edit: the next press starts a new cycle.
        editor.updateValue(TextFieldValue(editor.value.text + " ", TextRange(19)))
        assertTrue(editor.hippieComplete())
        assertEquals("alpha alpine alpine ", editor.value.text)
    }

    @Test
    fun `rename replaces whole words as one undo step`() {
        val editor = editor("foo = foo + fooBar")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))

        assertEquals(2, editor.renameWord("foo", "baz"))
        assertEquals("baz = baz + fooBar", editor.value.text)
        assertEquals(TextRange(3), editor.value.selection)

        assertTrue(editor.undo())
        assertEquals("foo = foo + fooBar", editor.value.text)
    }

    @Test
    fun `rename in a read-only editor changes nothing`() {
        val editor = editor("foo", readOnly = true)

        assertEquals(0, editor.renameWord("foo", "bar"))
        assertEquals("foo", editor.value.text)
    }

    @Test
    fun `identifier and declaration scans are cached per text revision`() {
        val editor = editor("fun a()\nval value = 1")
        assertSame(editor.identifiers(), editor.identifiers())
        assertEquals(listOf("fun", "a", "val", "value"), editor.identifiers())
        assertEquals(listOf(0, 1), editor.declarationLines)

        editor.updateValue(TextFieldValue("class B", TextRange(0)))
        assertEquals(listOf("class", "B"), editor.identifiers())
        assertEquals(listOf(0), editor.declarationLines)
    }

    @Test
    fun `concurrent identifier scans never mix revisions`() {
        val first = "alpha beta gamma"
        val second = "delta epsilon zeta"
        val expected = setOf(CodeCompletion.identifiers(first), CodeCompletion.identifiers(second))
        val editor = editor(first)
        val failures = Collections.synchronizedList(mutableListOf<List<String>>())
        val stop = AtomicBoolean(false)
        val readers = (1..4).map {
            Thread {
                while (!stop.get()) {
                    val names = editor.identifiers()
                    if (names !in expected) failures.add(names)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        try {
            repeat(500) { index ->
                editor.updateValue(TextFieldValue(if (index % 2 == 0) second else first, TextRange(0)))
            }
        } finally {
            stop.set(true)
            readers.forEach { it.join(5_000) }
        }

        assertTrue(failures.isEmpty(), "mixed revisions: ${failures.take(3)}")
    }

    @Test
    fun `replace range is one undo step`() {
        val editor = editor("val value = 1")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(3)))

        editor.replaceRange(0, 3, "var")
        assertEquals("var value = 1", editor.value.text)
        assertEquals(TextRange(3), editor.value.selection)
        assertTrue(editor.undo())
        assertEquals("val value = 1", editor.value.text)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(TextFileEditorActionsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
