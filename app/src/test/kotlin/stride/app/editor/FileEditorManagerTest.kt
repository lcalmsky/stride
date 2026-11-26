package stride.app.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionElement
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class FileEditorManagerTest {
    @TempDir
    lateinit var temp: Path

    @TempDir
    lateinit var outside: Path

    private lateinit var project: Project
    private lateinit var manager: FileEditorManager

    @BeforeEach
    fun setUp() {
        project = Project(temp)
        manager = project.service()
    }

    @Test
    fun `opening an already open file reuses its tab`() {
        val path = write("a.kt")

        val first = manager.openFile(VFile(path))!!
        val second = manager.openFile(VFile(path))!!

        assertSame(first, second)
        assertEquals(1, manager.openFiles.size)
        assertSame(first, manager.selected)
    }

    @Test
    fun `close selects the right neighbour then the left`() {
        val a = write("a.kt")
        val b = write("b.kt")
        manager.openFile(VFile(a))
        manager.openFile(VFile(b))
        manager.openFile(VFile(a))

        manager.closeFile(VFile(a))
        assertEquals(listOf("b.kt"), manager.openFiles.map { it.file.name })
        assertEquals("b.kt", manager.selected?.file?.name)

        manager.openFile(VFile(a))
        manager.closeFile(VFile(a))
        assertEquals(listOf("b.kt"), manager.openFiles.map { it.file.name })
        assertEquals("b.kt", manager.selected?.file?.name)
    }

    @Test
    fun `save writes to disk and clears modified`() {
        val path = write("a.kt", "package a")
        val editor = manager.openFile(VFile(path)) as TextFileEditor

        editor.setText("package b")
        assertTrue(editor.isModified)

        manager.saveSelected()

        assertEquals("package b", Files.readString(path))
        assertFalse(editor.isModified)
    }

    @Test
    fun `save all saves every modified editor including one outside the root`() {
        val a = write("a.kt", "a")
        val b = write("b.kt", "b")
        val outer = outside.resolve("o.kt")
        Files.writeString(outer, "o")
        val editorA = manager.openFile(VFile(a)) as TextFileEditor
        val editorB = manager.openFile(VFile(b)) as TextFileEditor
        val editorOuter = manager.openFile(VFile(outer)) as TextFileEditor
        editorA.setText("A")
        editorB.setText("B")
        editorOuter.setText("O")

        assertNull(manager.saveAll())

        assertEquals("A", Files.readString(a))
        assertEquals("B", Files.readString(b))
        assertEquals("O", Files.readString(outer))
        assertFalse(editorA.isModified)
        assertFalse(editorOuter.isModified)
    }

    @Test
    fun `save all continues after a failure and reads the first with how many more`() {
        val good = write("good.kt", "")
        val badOne = write("one.kt", "1")
        val badTwo = write("two.kt", "2")
        val editorGood = manager.openFile(VFile(good)) as TextFileEditor
        manager.openFile(VFile(badOne)) as TextFileEditor
        manager.openFile(VFile(badTwo)) as TextFileEditor
        editorGood.setText("ok")
        (manager.openFiles[1] as TextFileEditor).setText("1x")
        (manager.openFiles[2] as TextFileEditor).setText("2x")
        Files.delete(badOne)
        Files.createDirectory(badOne)
        Files.delete(badTwo)
        Files.createDirectory(badTwo)

        val message = manager.saveAll()

        assertTrue(message!!.startsWith("Save failed: one.kt: "))
        assertTrue(message.endsWith("(and 1 more)"))
        assertEquals("ok", Files.readString(good))
    }

    @Test
    fun `disposing the project disposes every editor`() {
        val editor = manager.openFile(VFile(write("a.kt"))) as TextFileEditor

        project.dispose()

        assertTrue(editor.isDisposed)
        assertTrue(manager.openFiles.isEmpty())
    }

    @Test
    fun `opening and selecting move files to the front of the recent list`() {
        val a = write("a.kt")
        val b = write("b.kt")
        manager.openFile(VFile(a))
        manager.openFile(VFile(b))
        assertEquals(listOf("b.kt", "a.kt"), manager.recentFiles.map { it.name })

        manager.selectPrevious()

        assertEquals("a.kt", manager.selected?.file?.name)
        assertEquals(listOf("a.kt", "b.kt"), manager.recentFiles.map { it.name })
    }

    @Test
    fun `reopening a file does not duplicate it in the recent list`() {
        val a = write("a.kt")
        val b = write("b.kt")
        manager.openFile(VFile(a))
        manager.openFile(VFile(b))
        manager.openFile(VFile(a))

        assertEquals(listOf("a.kt", "b.kt"), manager.recentFiles.map { it.name })
    }

    @Test
    fun `the recent list keeps at most fifty files`() {
        repeat(51) { manager.openFile(VFile(write("file$it.kt"))) }

        assertEquals(50, manager.recentFiles.size)
        assertEquals("file50.kt", manager.recentFiles.first().name)
        assertEquals("file1.kt", manager.recentFiles.last().name)
    }

    @Test
    fun `a list read before a change keeps the editors it had`() {
        val pathA = write("a.kt")
        val pathB = write("b.kt")
        val a = manager.openFile(VFile(pathA))!!
        val b = manager.openFile(VFile(pathB))!!
        val before = manager.openFiles
        val iterator = before.iterator()
        assertSame(a, iterator.next())

        manager.openFile(VFile(write("c.kt")))
        manager.closeFile(VFile(pathA))

        val walked = mutableListOf(a)
        while (iterator.hasNext()) walked += iterator.next()
        assertEquals(listOf(a, b), walked)
        assertEquals(listOf("b.kt", "c.kt"), manager.openFiles.map { it.file.name })

        project.dispose()
        assertEquals(listOf("a.kt", "b.kt"), before.map { it.file.name })
    }

    @Test
    fun `opening a file at a line puts the caret at that line start`() {
        val content = "line 1\nline 2\nline 3"
        val editor = manager.openFileAtLine(VFile(write("a.kt", content)), 1) as TextFileEditor

        val lineStart = content.lines().take(1).sumOf { it.length + 1 }
        assertEquals(TextRange(lineStart), editor.value.selection)
    }

    @Test
    fun `closing a file keeps it in the recent list`() {
        val a = write("a.kt")
        manager.openFile(VFile(a))

        manager.closeFile(VFile(a))

        assertEquals(listOf("a.kt"), manager.recentFiles.map { it.name })
    }

    @Test
    fun `forgetting a path drops its recent and closed entries`() {
        val dir = Files.createDirectories(temp.resolve("d"))
        val a = write("d/A.kt")
        val b = write("b.kt")
        manager.openFile(VFile(a))
        manager.openFile(VFile(b))
        manager.closeFile(VFile(a))

        manager.forgetPaths(dir)

        assertEquals(listOf("b.kt"), manager.recentFiles.map { it.name })
        assertNull(manager.reopenClosed())
        assertEquals(listOf("b.kt"), manager.recentFiles.map { it.name })
    }

    @Test
    fun `save all saves a modified editor that is not a text editor`() {
        val path = write("a.fake")

        val editor = manager.openFile(VFile(path)) as FakeFileEditor
        assertTrue(editor.isModified)

        assertNull(manager.saveAll())

        assertTrue(editor.saved)
        assertFalse(editor.isModified)
    }

    private fun write(name: String, content: String = ""): Path {
        val path = temp.resolve(name)
        Files.writeString(path, content)
        return path
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(FileEditorManagerTest::class.java.classLoader)
            // Save All takes every FileEditor; this provider supplies one that is not text.
            Application.extensionArea.registerExtension(
                FILE_EDITOR_PROVIDER_EP,
                ExtensionElement(
                    pluginId = "test",
                    attributes = mapOf("implementation" to FakeFileEditorProvider::class.java.name),
                    order = "first",
                ),
            )
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}

/** Editor for `.fake` files, so a test can see Save All reach a [FileEditor] that is not text. */
class FakeFileEditor(override val file: VFile) : FileEditor {
    override var isModified: Boolean = true
        private set

    var saved: Boolean = false
        private set

    override fun save() {
        saved = true
        isModified = false
    }

    @Composable
    override fun Content(modifier: Modifier) = Unit

    override fun dispose() = Unit
}

class FakeFileEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VFile): Boolean = file.name.endsWith(".fake")

    override fun createEditor(project: Project, file: VFile): FileEditor = FakeFileEditor(file)
}
