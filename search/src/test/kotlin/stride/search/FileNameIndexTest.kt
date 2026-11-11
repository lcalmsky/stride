package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFS_CHANGES
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import stride.core.vfs.VirtualFileManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class FileNameIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `indexes files and follows created and deleted entries`() {
        Application.resetForTests()
        Files.createDirectories(tempDir.resolve("src/util"))
        Files.createDirectories(tempDir.resolve("docs"))
        Files.createDirectories(tempDir.resolve("build"))
        Files.createDirectories(tempDir.resolve("node_modules"))
        Files.writeString(tempDir.resolve("src/App.kt"), "")
        Files.writeString(tempDir.resolve("src/util/Util.kt"), "")
        Files.writeString(tempDir.resolve("docs/readme.md"), "")
        Files.writeString(tempDir.resolve("build/generated.kt"), "")
        Files.writeString(tempDir.resolve("node_modules/library.js"), "")

        val project = Project(tempDir)
        try {
            project.service<VirtualFileManager>().start()
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            assertTrue(index.isReady)

            assertEquals(
                listOf("docs/readme.md", "src/App.kt", "src/util/Util.kt"),
                index.snapshot().map { it.relativePath }
            )

            val created = tempDir.resolve("src/New.kt")
            Files.writeString(created, "")
            await(index) { index.snapshot().any { it.relativePath == "src/New.kt" } }

            val extra = tempDir.resolve("extra")
            Files.createDirectories(extra)
            Thread.sleep(150)
            Files.writeString(extra.resolve("More.kt"), "")
            await(index) { index.snapshot().any { it.relativePath == "extra/More.kt" } }

            Files.delete(created)
            await(index) { index.snapshot().none { it.relativePath == "src/New.kt" } }

            Files.delete(extra.resolve("More.kt"))
            Files.delete(extra)
            await(index) { index.snapshot().none { it.relativePath.startsWith("extra/") } }

            val doomed = tempDir.resolve("doomed")
            Files.createDirectories(doomed.resolve("sub"))
            Thread.sleep(150)
            Files.writeString(doomed.resolve("A.kt"), "")
            Files.writeString(doomed.resolve("sub/B.kt"), "")
            await(index) { index.snapshot().count { it.relativePath.startsWith("doomed/") } == 2 }

            deleteRecursively(doomed)
            await(index) { index.snapshot().none { it.relativePath.startsWith("doomed/") } }
        } finally {
            project.dispose()
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `a directory delete event removes every file under that prefix only`() {
        Application.resetForTests()
        Files.createDirectories(tempDir.resolve("doomed/sub"))
        Files.createDirectories(tempDir.resolve("keep"))
        Files.writeString(tempDir.resolve("doomed/A.kt"), "")
        Files.writeString(tempDir.resolve("doomed/sub/B.kt"), "")
        Files.writeString(tempDir.resolve("doomed2.txt"), "")
        Files.writeString(tempDir.resolve("keep/keep.kt"), "")

        val project = Project(tempDir)
        try {
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            assertEquals(
                listOf("doomed/A.kt", "doomed/sub/B.kt", "doomed2.txt", "keep/keep.kt"),
                index.snapshot().map { it.relativePath },
            )

            project.messageBus.syncPublisher(VFS_CHANGES)
                .after(listOf(VFileEvent.Deleted(VFile(tempDir.resolve("doomed")))))

            assertEquals(
                listOf("doomed2.txt", "keep/keep.kt"),
                index.snapshot().map { it.relativePath },
            )
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `an unchanged index reuses the sorted snapshot`() {
        Application.resetForTests()
        Files.writeString(tempDir.resolve("B.kt"), "")
        Files.writeString(tempDir.resolve("A.kt"), "")

        val project = Project(tempDir)
        try {
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")

            val first = index.snapshot()
            assertSame(first, index.snapshot())

            project.messageBus.syncPublisher(VFS_CHANGES)
                .after(listOf(VFileEvent.Deleted(VFile(tempDir.resolve("A.kt")))))

            assertEquals(listOf("B.kt"), index.snapshot().map { it.relativePath })
            assertNotSame(first, index.snapshot())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `a gitdir pointer file is not indexed`() {
        Application.resetForTests()
        Files.writeString(tempDir.resolve(".git"), "gitdir: /elsewhere\n")
        Files.writeString(tempDir.resolve("Main.kt"), "")

        val project = Project(tempDir)
        try {
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")

            assertEquals(listOf("Main.kt"), index.snapshot().map { it.relativePath })
        } finally {
            project.dispose()
        }
    }

    private fun await(index: FileNameIndex, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), "index did not reflect the change within 5s: ${index.snapshot().map { it.relativePath }}")
    }
}
