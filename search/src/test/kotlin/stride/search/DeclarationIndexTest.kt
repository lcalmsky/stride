package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

class DeclarationIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `indexes kotlin and java sources and skips hidden and build directories`() {
        Application.resetForTests()
        Files.createDirectories(tempDir.resolve("src"))
        Files.createDirectories(tempDir.resolve("build"))
        Files.createDirectories(tempDir.resolve("node_modules"))
        Files.writeString(tempDir.resolve("src/Alpha.kt"), "package demo\n\nclass Alpha {\n    fun run() {}\n}\n")
        Files.writeString(tempDir.resolve("src/Beta.java"), "package demo;\n\nclass Beta {\n    int count;\n}\n")
        Files.writeString(tempDir.resolve("src/notes.md"), "class NotIndexed\n")
        Files.writeString(tempDir.resolve("build/Generated.kt"), "class Generated\n")
        Files.writeString(tempDir.resolve("node_modules/Hidden.kt"), "class Hidden\n")

        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            assertTrue(index.isReady)

            assertEquals(
                listOf("Alpha", "run", "Beta", "count"),
                index.snapshot().map { it.name }
            )
            val run = index.snapshot().single { it.name == "run" }
            assertEquals(DeclarationKind.SYMBOL, run.kind)
            assertEquals("src/Alpha.kt", run.relativePath)
            assertEquals(3, run.line)
            assertEquals("Alpha", run.container)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `vfs events add, reparse and remove declarations`() {
        Application.resetForTests()
        Files.createDirectories(tempDir.resolve("src"))
        Files.writeString(tempDir.resolve("src/Alpha.kt"), "class Alpha\n")

        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val publisher = project.messageBus.syncPublisher(VFS_CHANGES)

            val created = tempDir.resolve("src/Beta.kt")
            Files.writeString(created, "class Beta\n")
            publisher.after(listOf(VFileEvent.Created(VFile(created))))
            assertTrue(index.snapshot().any { it.name == "Beta" })

            Files.writeString(created, "class Gamma\n")
            publisher.after(listOf(VFileEvent.Changed(VFile(created))))
            assertTrue(index.snapshot().any { it.name == "Gamma" })
            assertTrue(index.snapshot().none { it.name == "Beta" })

            publisher.after(listOf(VFileEvent.Deleted(VFile(created))))
            assertTrue(index.snapshot().none { it.name == "Gamma" })
            assertEquals(listOf("Alpha"), index.snapshot().map { it.name })

            val directory = tempDir.resolve("extra")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("Delta.kt"), "class Delta\n")
            publisher.after(listOf(VFileEvent.Created(VFile(directory))))
            assertTrue(index.snapshot().any { it.name == "Delta" })

            publisher.after(listOf(VFileEvent.Deleted(VFile(directory))))
            assertTrue(index.snapshot().none { it.name == "Delta" })
        } finally {
            project.dispose()
        }
    }
}
