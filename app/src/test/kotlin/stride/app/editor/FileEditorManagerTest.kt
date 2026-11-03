package stride.app.editor

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
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class FileEditorManagerTest {
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
    fun `closing a file keeps it in the recent list`() {
        val a = write("a.kt")
        manager.openFile(VFile(a))

        manager.closeFile(VFile(a))

        assertEquals(listOf("a.kt"), manager.recentFiles.map { it.name })
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
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
