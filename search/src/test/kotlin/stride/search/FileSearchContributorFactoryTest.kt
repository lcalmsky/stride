package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor
import stride.core.extensions.ExtensionPointName
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class FileSearchContributorFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `contributes through the extension point`() {
        Application.resetForTests()
        Files.writeString(tempDir.resolve("App.kt"), "")
        Files.writeString(tempDir.resolve("Application.kt"), "")

        val area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                SEARCH_EVERYWHERE_CONTRIBUTOR_EP,
                SearchEverywhereContributorFactory::class.java.name,
                null,
                "test"
            )
        )
        area.registerExtension(
            SEARCH_EVERYWHERE_CONTRIBUTOR_EP,
            ExtensionElement(
                "test",
                mapOf("implementation" to FileSearchContributorFactory::class.java.name),
                null
            )
        )

        val factories =
            ExtensionPointName<SearchEverywhereContributorFactory<VFile>>(SEARCH_EVERYWHERE_CONTRIBUTOR_EP)
                .extensionList(area)
        assertEquals(1, factories.size)

        val project = Project(tempDir)
        try {
            val index = project.service<FileNameIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val contributor = factories.single().createContributor(project)

            assertEquals(FileSearchContributor.ID, contributor.id)
            val ranked = contributor.fetch("App", 10)
            assertEquals("App.kt", ranked.first().presentationText)
            assertTrue(ranked.any { it.presentationText == "Application.kt" }, "ranked: $ranked")
        } finally {
            project.dispose()
        }
    }
}
