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

class DeclarationIndexImportsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `imports are stored and updated with the file`() {
        Application.resetForTests()
        val file = tempDir.resolve("Alpha.kt")
        Files.writeString(
            file,
            """
            package demo

            import a.b.C
            import a.b.*
            import a.b.C as D

            class Alpha : C
            """.trimIndent(),
        )

        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")

            assertEquals(
                listOf(SourceImport("a.b.C"), SourceImport("a.b", isStar = true), SourceImport("a.b.C", alias = "D")),
                index.imports(VFile(file)),
            )
            val alpha = index.snapshot().single { it.name == "Alpha" }
            assertEquals(listOf("C"), alpha.supertypes)
            assertEquals(6, alpha.line)
            assertEquals(6, alpha.column)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `imports follow a change and disappear on deletion`() {
        Application.resetForTests()
        val file = tempDir.resolve("Alpha.kt")
        Files.writeString(file, "import a.b.First\n\nclass Alpha\n")

        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            assertEquals(listOf(SourceImport("a.b.First")), index.imports(VFile(file)))

            Files.writeString(file, "import a.b.Second\n\nclass Alpha\n")
            project.messageBus.syncPublisher(VFS_CHANGES).after(listOf(VFileEvent.Changed(VFile(file))))
            assertEquals(listOf(SourceImport("a.b.Second")), index.imports(VFile(file)))

            project.messageBus.syncPublisher(VFS_CHANGES).after(listOf(VFileEvent.Deleted(VFile(file))))
            assertTrue(index.imports(VFile(file)).isEmpty())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `an unindexed file has no imports`() {
        Application.resetForTests()
        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            assertTrue(index.imports(VFile(tempDir.resolve("Missing.kt"))).isEmpty())
        } finally {
            project.dispose()
        }
    }
}
