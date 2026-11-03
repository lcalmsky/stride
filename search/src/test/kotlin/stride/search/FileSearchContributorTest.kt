package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Files
import java.nio.file.Path

class FileSearchContributorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ranks matches by score and caps results at 50`() {
        Application.resetForTests()
        val fixed = listOf("App.kt", "Application.kt", "MyApp.kt", "Reapp.kt")
        val many = (1..60).map { "file$it.kt" }
        (fixed + many).forEach { Files.writeString(tempDir.resolve(it), "") }

        val project = Project(tempDir)
        try {
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val contributor = FileSearchContributor(index)

            val ranked = contributor.fetch("App", 50)
            assertEquals("App.kt", ranked.first().presentationText)

            val texts = ranked.map { it.presentationText }
            assertTrue(
                texts.indexOf("Application.kt") < texts.indexOf("MyApp.kt"),
                "prefix should rank before word starts: $texts"
            )
            assertTrue(
                texts.indexOf("MyApp.kt") < texts.indexOf("Reapp.kt"),
                "word starts should rank before substring: $texts"
            )
            assertEquals("App.kt", ranked.first().secondaryText)
            assertFalse(contributor.process(ranked.first().item), "the caller opens the file")

            assertEquals(50, contributor.fetch("file", 50).size)
            assertEquals(50, contributor.fetch("file", 100).size, "must cap the result at 50")
            assertTrue(contributor.fetch("file", 0).isEmpty())
        } finally {
            project.dispose()
        }
    }
}
