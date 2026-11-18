package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFS_CHANGES
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import java.nio.file.Files
import java.nio.file.Path

class ReloadFromDiskTest {
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
    fun `an unchanged file leaves the text, the caret and the undo stack alone`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(1)))

        assertEquals(ReloadResult.UNCHANGED, editor.reloadFromDisk())

        assertEquals("one\n", editor.value.text)
        assertEquals(TextRange(1), editor.value.selection)
        assertFalse(editor.undo.canUndo)
    }

    @Test
    fun `a changed file with no unsaved text is reloaded as one undo step`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))

        write("a.kt", "two\n")

        assertEquals(ReloadResult.RELOADED, editor.reloadFromDisk())
        assertEquals("two\n", editor.value.text)
        assertFalse(editor.isModified)

        assertTrue(editor.undo())
        assertEquals("one\n", editor.value.text)
        assertTrue(editor.isModified)
        assertFalse(editor.undo.canUndo)
    }

    @Test
    fun `an editor with unsaved text keeps it`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        editor.setText("mine\n")

        write("a.kt", "two\n")

        assertEquals(ReloadResult.KEPT_UNSAVED, editor.reloadFromDisk())
        assertEquals("mine\n", editor.value.text)
        assertTrue(editor.isModified)
    }

    @Test
    fun `a file that is not valid UTF-8 is left as it is`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))

        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))

        assertEquals(ReloadResult.FAILED, editor.reloadFromDisk())
        assertEquals("one\n", editor.value.text)
        assertFalse(editor.isModified)
    }

    @Test
    fun `the caret keeps its line and column when another line changes`() {
        val path = write("caret.kt", "alpha\nbeta\ngamma")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(8)))

        write("caret.kt", "alpha-longer\nbeta\ngamma")

        assertEquals(ReloadResult.RELOADED, editor.reloadFromDisk())
        assertEquals(TextRange(15), editor.value.selection)
    }

    @Test
    fun `a shortened line takes the caret to its end`() {
        val path = write("caret.kt", "aa\nbbbb\n")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(7)))

        write("caret.kt", "aa\nbb\n")

        assertEquals(ReloadResult.RELOADED, editor.reloadFromDisk())
        assertEquals(TextRange(5), editor.value.selection)
    }

    @Test
    fun `a caret line that is gone moves to the last line`() {
        val path = write("caret.kt", "one\ntwo\nthree")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue(editor.value.text, TextRange(12)))

        write("caret.kt", "one\ntwo")

        assertEquals(ReloadResult.RELOADED, editor.reloadFromDisk())
        assertEquals(TextRange(7), editor.value.selection)
    }

    @Test
    fun `a freshly opened file is unchanged for CRLF, a BOM and a missing final newline`() {
        val crlf = write("crlf.kt", "a\r\nb\r\n")
        val bom = write("bom.kt", "﻿val a = 1")
        val bare = write("bare.kt", "a\nb")

        for (path in listOf(crlf, bom, bare)) {
            val editor = TextFileEditor(VFile(path))
            assertEquals(ReloadResult.UNCHANGED, editor.reloadFromDisk(), path.fileName.toString())
            assertEquals(Files.readString(path), editor.value.text)
            assertFalse(editor.isModified)
        }
    }

    @Test
    fun `a VFS change reloads the open editor and a deletion keeps the tab`() {
        val path = write("a.kt", "one\n")
        val editor = manager.openFile(VFile(path)) as TextFileEditor

        write("a.kt", "two\n")
        project.messageBus.syncPublisher(VFS_CHANGES).after(listOf(VFileEvent.Changed(VFile(path))))

        assertEquals("two\n", editor.value.text)
        assertFalse(editor.isModified)

        Files.delete(path)
        project.messageBus.syncPublisher(VFS_CHANGES).after(listOf(VFileEvent.Deleted(VFile(path))))

        assertSame(editor, manager.openFiles.single())
        assertEquals("two\n", editor.value.text)
    }

    @Test
    fun `the change that follows a save leaves the text and the undo stack alone`() {
        val path = write("a.kt", "one\n")
        val editor = manager.openFile(VFile(path)) as TextFileEditor
        editor.setText("two\n")
        editor.save()
        val saved = editor.value

        project.messageBus.syncPublisher(VFS_CHANGES).after(listOf(VFileEvent.Changed(VFile(path))))

        assertEquals(saved, editor.value)
        assertFalse(editor.isModified)
        assertTrue(editor.undo())
        assertEquals("one\n", editor.value.text)
    }

    @Test
    fun `reloadChangedFromDisk reloads only the editors without unsaved text`() {
        val a = write("a.kt", "one\n")
        val b = write("b.kt", "one\n")
        val first = manager.openFile(VFile(a)) as TextFileEditor
        val second = manager.openFile(VFile(b)) as TextFileEditor
        second.setText("mine\n")

        write("a.kt", "two\n")
        write("b.kt", "two\n")

        assertEquals(ReloadSummary(1, 1), manager.reloadChangedFromDisk())
        assertEquals("two\n", first.value.text)
        assertFalse(first.isModified)
        assertEquals("mine\n", second.value.text)
        assertTrue(second.isModified)
    }

    @Test
    fun `a reload reports the text change to the document listener`() {
        val path = write("a.kt", "one\n")
        val editor = TextFileEditor(VFile(path))
        var calls = 0
        editor.onDocumentTextChanged = { calls++ }

        write("a.kt", "two\n")
        editor.reloadFromDisk()

        assertEquals(1, calls)
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
            Application.init(ReloadFromDiskTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
