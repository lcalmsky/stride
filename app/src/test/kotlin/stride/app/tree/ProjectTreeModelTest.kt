package stride.app.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectTreeModelTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `hidden directories are absent`() {
        for (name in HiddenDirectoriesTreeStructureProvider.HIDDEN_DIRECTORIES) {
            Files.createDirectories(temp.resolve(name))
        }
        Files.createDirectories(temp.resolve("src"))
        val model = ProjectTreeModel(VFile(temp), listOf(HiddenDirectoriesTreeStructureProvider()))

        val names = model.rows().map { it.file.name }

        assertTrue(names.none { it in HiddenDirectoriesTreeStructureProvider.HIDDEN_DIRECTORIES })
        assertTrue(names.contains("src"))
    }

    @Test
    fun `a git entry is hidden whether it is a file or a directory`() {
        val withFile = temp.resolve("withFile")
        Files.createDirectories(withFile)
        Files.writeString(withFile.resolve(".git"), "gitdir: /elsewhere\n")
        Files.writeString(withFile.resolve("build"), "")
        Files.writeString(withFile.resolve("Main.kt"), "")
        val withDirectory = temp.resolve("withDirectory")
        Files.createDirectories(withDirectory.resolve(".git"))
        Files.createDirectories(withDirectory.resolve("build"))

        val provider = HiddenDirectoriesTreeStructureProvider()

        assertEquals(
            listOf("build", "Main.kt"),
            provider.modify(VFile(withFile), VFile(withFile).children()).map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            provider.modify(VFile(withDirectory), VFile(withDirectory).children()).map { it.name },
        )
    }

    @Test
    fun `children are read lazily`() {
        Files.createDirectories(temp.resolve("sub"))
        Files.writeString(temp.resolve("sub/a.txt"), "")
        val loaded = mutableListOf<String>()
        val model = ProjectTreeModel(
            VFile(temp),
            emptyList(),
            childrenLoader = { file ->
                loaded.add(file.name)
                file.children()
            },
        )

        assertEquals(listOf(temp.fileName.toString()), loaded)
        assertFalse(model.rows().any { it.file.name == "a.txt" })

        model.toggle(VFile(temp.resolve("sub")))

        assertTrue(loaded.contains("sub"))
        assertTrue(model.rows().any { it.file.name == "a.txt" })
    }

    @Test
    fun `directories come before files in the flattened rows`() {
        Files.createDirectories(temp.resolve("zdir"))
        Files.writeString(temp.resolve("a.txt"), "")
        val model = ProjectTreeModel(VFile(temp), emptyList())

        val children = model.rows().drop(1).map { it.file.name }

        assertEquals(listOf("zdir", "a.txt"), children)
    }

    @Test
    fun `created and deleted file events invalidate the parent children`() {
        Files.createDirectories(temp.resolve("sub"))
        val model = ProjectTreeModel(VFile(temp), emptyList())
        model.expand(VFile(temp.resolve("sub")))
        val created = temp.resolve("sub/new.txt")

        Files.writeString(created, "")
        assertFalse(model.rows().any { it.file.name == "new.txt" })

        model.handleEvents(listOf(VFileEvent.Created(VFile(created))))
        flushEdt()
        assertTrue(model.rows().any { it.file.name == "new.txt" })

        Files.delete(created)
        model.handleEvents(listOf(VFileEvent.Deleted(VFile(created))))
        flushEdt()
        assertFalse(model.rows().any { it.file.name == "new.txt" })
    }

    @Test
    fun `moved carries expanded entries the cached children and the selection`() {
        val old = Files.createDirectories(temp.resolve("old"))
        Files.writeString(old.resolve("A.kt"), "")
        val model = ProjectTreeModel(VFile(temp), emptyList())
        model.expand(VFile(old))
        model.select(VFile(old.resolve("A.kt")))
        val new = temp.resolve("new")

        Files.move(old, new)
        model.moved(old, new)

        assertEquals(new.resolve("A.kt"), model.selected)
        assertTrue(model.rows().any { it.file.path == new.resolve("A.kt") })
        assertFalse(model.rows().any { it.file.path.startsWith(old) })
    }

    @Test
    fun `handleEvents from another thread rebuilds only on the UI thread`() {
        val model = ProjectTreeModel(VFile(temp), emptyList())
        val created = temp.resolve("new.txt")
        Files.writeString(created, "")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        EventQueue.invokeLater {
            holding.countDown()
            release.await()
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        try {
            val caller = Thread { model.handleEvents(listOf(VFileEvent.Created(VFile(created)))) }
            caller.start()
            caller.join()

            assertFalse(model.rows().any { it.file.name == "new.txt" })
        } finally {
            release.countDown()
        }
        EventQueue.invokeAndWait {}

        assertTrue(model.rows().any { it.file.name == "new.txt" })
    }

    private fun flushEdt() {
        if (!EventQueue.isDispatchThread()) EventQueue.invokeAndWait {}
    }
}
