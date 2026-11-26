package stride.app.files

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.navigation.Bookmark
import stride.app.vcs.GitExecutor
import stride.app.vcs.GitResult
import stride.core.services.Application
import stride.core.vfs.VFile
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class FileOperationsTest {
    @TempDir
    lateinit var temp: Path

    @TempDir
    lateinit var trash: Path

    private lateinit var fixture: FileOperationsFixture
    private val files get() = fixture.files
    private val editors get() = fixture.editors
    private val tree get() = fixture.tree
    private val bookmarks get() = fixture.bookmarks

    @BeforeEach
    fun setUp() {
        fixture = FileOperationsFixture(temp, trash)
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a file is created empty and reads Created with its relative path`() {
        val result = files.createFile(temp, "a.txt")

        assertEquals("Created a.txt", result.message)
        assertEquals(temp.resolve("a.txt"), (result as FileOperationResult.Success).path)
        assertEquals("", Files.readString(temp.resolve("a.txt")))
        assertEquals(temp.resolve("a.txt"), tree.selected)
    }

    @Test
    fun `a nested file creates the missing directories`() {
        val result = files.createFile(temp, "src/a/b/C.kt")

        assertEquals("Created src/a/b/C.kt", result.message)
        assertTrue(Files.isRegularFile(temp.resolve("src/a/b/C.kt")))
    }

    @Test
    fun `a directory is created and expanded in the tree`() {
        val result = files.createDirectory(temp, "x/y")

        assertEquals("Created x/y", result.message)
        assertTrue(Files.isDirectory(temp.resolve("x")))
        assertTrue(Files.isDirectory(temp.resolve("x/y")))
        flushEdt()
        assertTrue(tree.rows().any { it.file.path == temp.resolve("x/y") && it.isExpanded })
    }

    @Test
    fun `an existing target is refused`() {
        fixture.write("a.txt", "keep")

        val result = files.createFile(temp, "a.txt")

        assertEquals("a.txt already exists", result.message)
        assertEquals("keep", Files.readString(temp.resolve("a.txt")))
    }

    @Test
    fun `invalid names are refused with the P13 text`() {
        assertEquals(".. is not a valid name", files.createFile(temp, "..").message)
        assertEquals("a//b is not a valid name", files.createFile(temp, "a//b").message)
        assertEquals("/a is not a valid name", files.createFile(temp, "/a").message)
        assertEquals("a\u0000b is not a valid name", files.createFile(temp, "a\u0000b").message)
        assertEquals(" is not a valid name", files.createFile(temp, "").message)
        assertEquals("a/b is not a valid name", files.nameError("a/b"))
        assertNull(files.nameError("b.txt"))
        assertNull(files.relativePathError("a/b/c.txt"))
    }

    @Test
    fun `a Kotlin name must be an identifier and a trailing kt is dropped`() {
        assertEquals("1Bad is not a valid Kotlin name", files.kotlinNameError("1Bad"))
        assertEquals("a-b is not a valid Kotlin name", files.kotlinNameError("a-b"))
        assertNull(files.kotlinNameError("Good.kt"))

        files.createKotlin(temp, "Good.kt", KotlinFileKind.CLASS)

        assertTrue(Files.isRegularFile(temp.resolve("Good.kt")))
        assertFalse(Files.exists(temp.resolve("Good.kt.kt")))
    }

    @Test
    fun `each Kotlin kind writes its template under the package line`() {
        val dir = fixture.directory("src/main/kotlin/a/b")
        val cases = listOf(
            Triple(KotlinFileKind.CLASS, "Classy", "class Classy {\n}\n"),
            Triple(KotlinFileKind.FILE, "Plain", ""),
            Triple(KotlinFileKind.INTERFACE, "Face", "interface Face {\n}\n"),
            Triple(KotlinFileKind.DATA_CLASS, "Data", "data class Data()\n"),
            Triple(KotlinFileKind.ENUM_CLASS, "En", "enum class En {\n}\n"),
            Triple(KotlinFileKind.OBJECT, "Obj", "object Obj {\n}\n"),
        )

        for ((kind, name, body) in cases) {
            files.createKotlin(dir, name, kind)
            assertEquals("package a.b\n\n$body", Files.readString(dir.resolve("$name.kt")))
        }
    }

    @Test
    fun `a Kotlin file outside src writes no package line`() {
        val dir = fixture.directory("src/main/kotlin/my-dir")

        files.createKotlin(dir, "Thing", KotlinFileKind.DATA_CLASS)

        assertEquals("data class Thing()\n", Files.readString(dir.resolve("Thing.kt")))
        assertNull(files.packageFor(dir))
        assertEquals("a.b", files.packageFor(fixture.directory("src/main/kotlin/a/b")))
        assertNull(files.packageFor(fixture.directory("src/main/kotlin")))
    }

    @Test
    fun `a move into another directory reads Moved with the directory`() {
        val file = fixture.write("a.txt", "x")
        val dir = fixture.directory("sub")
        tree.select(VFile(file))

        val result = files.move(file, dir.resolve("a.txt"))

        assertEquals("Moved a.txt to sub", result.message)
        assertTrue(Files.isRegularFile(dir.resolve("a.txt")))
        assertFalse(Files.exists(file))
        assertEquals(dir.resolve("a.txt"), tree.selected)
    }

    @Test
    fun `a rename reads Renamed old to new`() {
        val file = fixture.write("a.txt", "x")

        val result = files.move(file, temp.resolve("b.txt"))

        assertEquals("Renamed a.txt to b.txt", result.message)
        assertTrue(Files.isRegularFile(temp.resolve("b.txt")))
    }

    @Test
    fun `the same name is a no-op`() {
        val file = fixture.write("a.txt", "x")

        val result = files.move(file, temp.resolve("a.txt"))

        assertEquals(null, result.message)
        assertTrue(Files.isRegularFile(file))
    }

    @Test
    fun `a move into itself is refused`() {
        val dir = fixture.directory("d")

        val result = files.move(dir, dir.resolve("inner"))

        assertEquals("Cannot move d into itself", result.message)
        assertTrue(Files.isDirectory(dir))
    }

    @Test
    fun `a move onto an existing target is refused`() {
        val file = fixture.write("a.txt", "a")
        fixture.write("b.txt", "b")

        val result = files.move(file, temp.resolve("b.txt"))

        assertEquals("b.txt already exists", result.message)
        assertEquals("a", Files.readString(file))
    }

    @Test
    fun `the project root cannot be moved`() {
        val result = files.move(temp, temp.resolveSibling("elsewhere"))

        assertEquals("The project root cannot be changed here", result.message)
    }

    @Test
    fun `a source deleted after the plan reads no longer exists`() {
        val file = fixture.write("gone.txt", "x")
        Files.delete(file)

        assertEquals("gone.txt no longer exists", files.move(file, temp.resolve("x.txt")).message)
        assertEquals("gone.txt no longer exists", files.delete(file).message)
    }

    @Test
    fun `a write while git is busy is refused`() {
        val file = fixture.write("a.txt", "x")
        files.busyProvider = { "Committing…" }

        assertEquals("Git is busy: Committing…", files.move(file, temp.resolve("b.txt")).message)
        assertEquals("Git is busy: Committing…", files.createFile(temp, "c.txt").message)
        assertTrue(Files.isRegularFile(file))
    }

    @Test
    fun `a case-only rename ends with the new name on disk and the editor follows`() {
        val dir = fixture.directory("case")
        val probe = Files.createFile(dir.resolve("Probe.txt"))
        assumeTrue(Files.exists(dir.resolve("probe.txt")), "the test volume is case-sensitive")
        Files.delete(probe)
        val file = fixture.write("case/Foo.kt", "package case\n")
        val editor = editors.openFile(VFile(file)) as TextFileEditor

        val result = files.move(file, dir.resolve("foo.kt"))

        assertEquals("Renamed case/Foo.kt to case/foo.kt", result.message)
        val names = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertTrue(names.contains("foo.kt"))
        assertFalse(names.contains("Foo.kt"))
        assertTrue(editor.isDisposed)
        assertEquals(dir.resolve("foo.kt"), editors.openFiles.single().file.path)
    }

    @Test
    fun `a tracked case-only rename adds the git suffix and a plain rename does not`() {
        val dir = fixture.directory("case")
        val probe = Files.createFile(dir.resolve("Probe.txt"))
        assumeTrue(Files.exists(dir.resolve("probe.txt")), "the test volume is case-sensitive")
        Files.delete(probe)
        val commands = mutableListOf<List<String>>()
        fixture.git.executor = GitExecutor { command ->
            commands += command.args
            GitResult(0, ByteArray(0), "", timedOut = false)
        }
        val file = fixture.write("case/Foo.kt", "")

        val result = files.move(file, dir.resolve("foo.kt"))

        assertTrue((result.message ?: "").endsWith("; git still tracks case/Foo.kt"))
        assertTrue(commands.any { it.take(2) == listOf("ls-files", "--error-unmatch") })

        val plain = fixture.write("plain.txt", "")
        assertEquals("Renamed plain.txt to other.txt", files.move(plain, temp.resolve("other.txt")).message)
    }

    @Test
    fun `a moved directory keeps its open editor text tab index and selection`() {
        val dir = fixture.directory("pkg")
        val movedFile = fixture.write("pkg/A.kt", "package pkg\n")
        val other = fixture.write("B.kt", "")
        val otherEditor = editors.openFile(VFile(other))!!
        val editor = editors.openFile(VFile(movedFile)) as TextFileEditor
        editor.updateValue(TextFieldValue("edited", TextRange(2, 5)))
        val target = fixture.directory("moved")

        val result = files.move(dir, target.resolve("pkg"))

        assertEquals("Moved pkg to moved", result.message)
        assertEquals(2, editors.openFiles.size)
        assertSame(otherEditor, editors.openFiles[0])
        val replacement = editors.openFiles[1] as TextFileEditor
        assertSame(replacement, editors.selected)
        assertEquals(target.resolve("pkg/A.kt"), replacement.file.path)
        assertTrue(replacement.isModified)
        assertEquals("edited", replacement.value.text)
        assertEquals(TextRange(2, 5), replacement.value.selection)
        assertTrue(editor.isDisposed)
        assertEquals("package pkg\n", Files.readString(target.resolve("pkg/A.kt")))
    }

    @Test
    fun `recent closed files and bookmarks follow a moved directory`() {
        val dir = fixture.directory("old")
        val a = fixture.write("old/A.kt", "a")
        val c = fixture.write("old/C.kt", "c")
        val b = fixture.write("B.kt", "b")
        editors.openFile(VFile(a))
        editors.openFile(VFile(b))
        val cEditor = editors.openFile(VFile(c))!!
        editors.closeFile(cEditor.file)
        bookmarks.toggle(Bookmark(a, "old/A.kt", 3, "line"))
        bookmarks.toggle(Bookmark(b, "B.kt", 7, "other"))
        val target = fixture.directory("new")

        files.move(dir, target.resolve("old"))

        assertEquals(listOf("C.kt", "B.kt", "A.kt"), editors.recentFiles.map { it.name })
        assertEquals(target.resolve("old/C.kt"), editors.recentFiles[0].path)
        assertEquals(target.resolve("old/A.kt"), editors.recentFiles.last().path)
        assertEquals(target.resolve("old/C.kt"), editors.reopenClosed()?.file?.path)
        assertEquals(target.resolve("old/A.kt"), bookmarks.entries[0].path)
        assertEquals("new/old/A.kt", bookmarks.entries[0].relativePath)
        assertEquals(3, bookmarks.entries[0].line)
        assertEquals(b, bookmarks.entries[1].path)
    }

    @Test
    fun `the tree carries expanded rows and the selection to a moved directory`() {
        val dir = fixture.directory("old")
        val file = fixture.write("old/A.kt", "")
        fixture.refreshTree()
        tree.expand(VFile(dir))
        tree.select(VFile(file))
        val target = temp.resolve("new")

        files.move(dir, target)
        flushEdt()

        assertEquals(target.resolve("A.kt"), tree.selected)
        assertTrue(tree.rows().any { it.file.path == target.resolve("A.kt") })
        assertTrue(tree.rows().none { it.file.path.startsWith(dir) })
    }

    @Test
    fun `delete through the trash closes the tabs under it including the modified one`() {
        val dir = fixture.directory("d")
        val a = editors.openFile(VFile(fixture.write("d/A.kt", "a"))) as TextFileEditor
        val b = editors.openFile(VFile(fixture.write("d/B.kt", "b"))) as TextFileEditor
        b.setText("unsaved")
        val keep = editors.openFile(VFile(fixture.write("keep.kt", "")))!!

        val result = files.delete(dir)

        assertEquals("Moved d to the Trash", result.message)
        assertFalse(Files.exists(dir))
        assertTrue(Files.isDirectory(trash.resolve("d")))
        assertEquals(listOf(keep), editors.openFiles)
        assertTrue(a.isDisposed)
        assertTrue(b.isDisposed)
    }

    @Test
    fun `a refusing trash leaves the file and reads the P9 text`() {
        files.moveToTrash = { false }
        val file = fixture.write("keep.txt", "x")

        assertEquals("Delete failed: could not move keep.txt to the Trash", files.delete(file).message)
        assertTrue(Files.isRegularFile(file))

        files.moveToTrash = { throw IOException("no trash") }

        assertEquals("Delete failed: could not move keep.txt to the Trash", files.delete(file).message)
        assertTrue(Files.isRegularFile(file))
    }

    @Test
    fun `the default seams refuse under the test property`() {
        val defaults = FileOperations(fixture.project.root, editors, tree, bookmarks, fixture.git)
        val file = fixture.write("a.txt", "")

        assertTrue(defaults.trashSupported())
        assertFalse(defaults.moveToTrash(file))
        val revealed = defaults.revealInFinder(file)
        assertTrue(revealed is FileOperationResult.Failure)
        assertTrue((revealed.message ?: "").startsWith("Reveal failed: "))
    }

    @Test
    fun `the delete popup facts count files without following links`() {
        fixture.write("outside/x.txt", "")
        val dir = fixture.directory("d")
        fixture.write("d/a.txt", "")
        fixture.write("d/b/c.txt", "")
        Files.createSymbolicLink(dir.resolve("link"), temp.resolve("outside"))
        val open = editors.openFile(VFile(dir.resolve("a.txt"))) as TextFileEditor
        editors.openFile(VFile(dir.resolve("b/c.txt")))
        open.setText("unsaved")

        assertEquals("3 files", files.fileCountText(dir))
        assertEquals("1 file", files.fileCountText(dir.resolve("a.txt")))
        assertEquals(2, files.openTabCount(dir))
        assertEquals(1, files.unsavedTabCount(dir))
        assertEquals(1, files.unsavedEditorCount(dir))
        files.maxCountedFiles = 1
        assertEquals("1+ files", files.fileCountText(dir))
    }

    @Test
    fun `copy copies a file and reads the relative target`() {
        val source = fixture.write("a.txt", "x")

        val result = files.copy(source, temp.resolve("b.txt"))

        assertEquals("Copied a.txt to b.txt", result.message)
        assertEquals("x", Files.readString(temp.resolve("b.txt")))
        assertEquals(temp.resolve("b.txt"), tree.selected)
    }

    @Test
    fun `copy copies a directory recursively and a link as a link`() {
        val dir = fixture.directory("d")
        fixture.write("d/a.txt", "a")
        fixture.write("d/sub/b.txt", "b")
        Files.createSymbolicLink(dir.resolve("link.txt"), dir.resolve("a.txt"))

        val result = files.copy(dir, temp.resolve("e"))

        assertEquals("Copied d to e", result.message)
        assertEquals("a", Files.readString(temp.resolve("e/a.txt")))
        assertEquals("b", Files.readString(temp.resolve("e/sub/b.txt")))
        assertTrue(Files.isSymbolicLink(temp.resolve("e/link.txt")))
    }

    @Test
    fun `copy into itself and onto an existing target are refused`() {
        val dir = fixture.directory("d")
        fixture.write("a.txt", "a")
        fixture.write("b.txt", "b")

        assertEquals("Cannot copy d into itself", files.copy(dir, dir.resolve("inner")).message)
        assertEquals("b.txt already exists", files.copy(temp.resolve("a.txt"), temp.resolve("b.txt")).message)
    }

    @Test
    fun `cut then paste moves the path and empties the clipboard`() {
        val source = fixture.write("a.txt", "x")
        val dir = fixture.directory("d")

        assertEquals("Cut a.txt", files.cut(source).message)
        val pasted = files.paste(dir)!!

        assertEquals("Moved a.txt to d", pasted.message)
        assertTrue(Files.isRegularFile(dir.resolve("a.txt")))
        assertFalse(Files.exists(source))
        assertNull(files.clipboard)
    }

    @Test
    fun `copy then paste copies and keeps the clipboard`() {
        val source = fixture.write("a.txt", "x")
        val dir = fixture.directory("d")

        assertEquals("Copied a.txt", files.copy(source).message)
        val pasted = files.paste(dir)!!

        assertEquals("Copied a.txt to d/a.txt", pasted.message)
        assertEquals("x", Files.readString(dir.resolve("a.txt")))
        assertNotNull(files.clipboard)
    }

    @Test
    fun `a paste of a copied name that exists asks for the Copy popup`() {
        val source = fixture.write("a.txt", "new")
        val dir = fixture.directory("d")
        fixture.write("d/a.txt", "old")
        files.copy(source)

        val pasted = files.paste(dir)!!

        assertTrue(pasted is FileOperationResult.OpenCopyPopup)
        assertEquals(dir.resolve("a.txt"), (pasted as FileOperationResult.OpenCopyPopup).path)
        assertEquals("a.txt already exists", pasted.message)
        assertEquals("old", Files.readString(dir.resolve("a.txt")))
    }

    @Test
    fun `a cut paste onto an existing name reads the P13 conflict and a gone source empties the clipboard`() {
        val source = fixture.write("a.txt", "new")
        val dir = fixture.directory("d")
        fixture.write("d/a.txt", "old")
        files.cut(source)

        assertEquals("a.txt already exists in d", files.paste(dir)?.message)
        assertNotNull(files.clipboard)

        Files.delete(source)

        assertEquals("a.txt no longer exists", files.paste(dir)?.message)
        assertNull(files.clipboard)
        assertNull(files.paste(dir))
    }

    @Test
    fun `reveal goes through the launcher and refuses when unsupported`() {
        val launched = mutableListOf<List<String>>()
        files.launcher = { launched += it }
        files.revealSupported = { true }
        val file = fixture.write("a.txt", "")

        assertNull(files.revealInFinder(file).message)
        assertEquals(listOf(listOf("open", "-R", file.toString())), launched)

        files.revealSupported = { false }

        assertEquals("Reveal is not supported on this system", files.revealInFinder(file).message)

        files.revealSupported = { true }
        files.launcher = { throw IOException("no Finder") }

        assertTrue((files.revealInFinder(file).message ?: "").startsWith("Reveal failed: "))
    }

    private fun flushEdt() {
        if (!EventQueue.isDispatchThread()) EventQueue.invokeAndWait {}
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(FileOperationsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
