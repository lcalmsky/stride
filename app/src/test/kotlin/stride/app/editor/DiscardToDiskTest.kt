package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile

class DiscardToDiskTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var project: Project
    private lateinit var manager: FileEditorManager

    @BeforeEach
    fun setUp() {
        project = Project(temp)
        manager = project.service()
    }

    @Test
    fun `a modified editor takes the disk text and one undo brings the unsaved text back`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.setText("mine\n")
        write("a.kt", "two\n")

        assertEquals(ReloadResult.RELOADED, editor.discardToDisk())

        assertEquals("two\n", editor.value.text)
        assertFalse(editor.isModified)
        assertTrue(editor.undo())
        assertEquals("mine\n", editor.value.text)
        assertTrue(editor.isModified)
    }

    @Test
    fun `an edit that only exists in the editor is discarded to the saved text`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.setText("mine\n")

        assertEquals(ReloadResult.RELOADED, editor.discardToDisk())

        assertEquals("one\n", editor.value.text)
        assertFalse(editor.isModified)
    }

    @Test
    fun `the caret keeps its line and column when a line is inserted above`() {
        val path = write("caret.kt", "alpha\nbeta\ngamma")
        val editor = TextFileEditor(VFile(path))
        editor.setText("alpha\nBETA\ngamma")
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(8)))
        write("caret.kt", "inserted\nalpha\nbeta\ngamma")

        assertEquals(ReloadResult.RELOADED, editor.discardToDisk())

        assertEquals("inserted\nalpha\nbeta\ngamma", editor.value.text)
        assertEquals(TextRange(11), editor.value.selection)
    }

    @Test
    fun `a read-only editor fails`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path), readOnly = true)
        write("a.kt", "two\n")

        assertEquals(ReloadResult.FAILED, editor.discardToDisk())

        assertEquals("one\n", editor.value.text)
        assertFalse(editor.isModified)
    }

    @Test
    fun `an editor whose text already is the disk text is unchanged`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))

        assertEquals(ReloadResult.UNCHANGED, editor.discardToDisk())

        assertEquals("one\n", editor.value.text)
        assertFalse(editor.undo.canUndo)
    }

    @Test
    fun `a file that is not valid UTF-8 fails`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.setText("mine\n")
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))

        assertEquals(ReloadResult.FAILED, editor.discardToDisk())

        assertEquals("mine\n", editor.value.text)
        assertTrue(editor.isModified)
    }

    @Test
    fun `reloadFromDisk still keeps the unsaved text of a modified editor`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.setText("mine\n")
        write("a.kt", "two\n")

        assertEquals(ReloadResult.KEPT_UNSAVED, editor.reloadFromDisk())

        assertEquals("mine\n", editor.value.text)
        assertTrue(editor.isModified)
    }

    @Test
    fun `the manager discards only the editors of the listed paths`() {
        val a = write("a.kt", "one\n")
        val b = write("b.kt", "one\n")
        val first = manager.openFile(VFile(a)) as TextFileEditor
        val second = manager.openFile(VFile(b)) as TextFileEditor
        first.setText("draft\n")
        second.setText("draft\n")
        write("a.kt", "two\n")
        write("b.kt", "two\n")

        assertEquals(1, manager.discardToDisk(listOf(a)))

        assertEquals("two\n", first.value.text)
        assertFalse(first.isModified)
        assertEquals("draft\n", second.value.text)
        assertTrue(second.isModified)
    }

    private fun write(name: String, content: String): Path {
        val path = temp.resolve(name)
        Files.writeString(path, content)
        return path
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(DiscardToDiskTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
