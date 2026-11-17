package stride.app.intention

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.search.DeclarationIndex
import java.nio.file.Files
import java.nio.file.Path

class ProjectSymbolLookupTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `private top level declarations are not offered`() {
        Application.resetForTests()
        Files.createDirectories(tempDir.resolve("demo/a"))
        Files.writeString(
            tempDir.resolve("demo/a/Formatter.kt"),
            "package demo.a\n\nclass Formatter\n\nprivate fun format() = Unit\n\ninternal fun internalFormat() = Unit\n",
        )

        val project = Project(tempDir)
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val lookup = ProjectSymbolLookup(index)

            assertEquals(listOf("demo.a.Formatter"), lookup.qualifiedNames("Formatter"))
            assertTrue(lookup.qualifiedNames("format").isEmpty())
            assertEquals(listOf("demo.a.internalFormat"), lookup.qualifiedNames("internalFormat"))
        } finally {
            project.dispose()
        }
    }
}
