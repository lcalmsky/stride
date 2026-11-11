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

class DeclarationSearchContributorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `classes match only class declarations with a path and line`() {
        val project = project(
            "src/Alpha.kt" to "package demo\n\nclass AlphaService\n",
            "src/Beta.kt" to "package demo\n\nclass Beta\n\nfun alphaFun() {}\n",
        )
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val contributor = ClassSearchContributor(index)

            val ranked = contributor.fetch("Alpha", 10)
            assertEquals(listOf("AlphaService"), ranked.map { it.presentationText })
            assertEquals("src/Alpha.kt:3", ranked.single().secondaryText)
            assertEquals(SearchTab.CLASSES, contributor.tab)
            assertFalse(contributor.process(ranked.single().item), "the caller opens the file")

            assertTrue(contributor.fetch("Alpha", 0).isEmpty())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `symbols match classes and functions with the container in the secondary text`() {
        val project = project(
            "src/Greeting.kt" to "package demo\n\nclass Greeter {\n    fun greet() {}\n}\n\nfun standalone() {}\n",
        )
        try {
            val index = project.service<DeclarationIndex>()
            assertTrue(index.awaitReady(5_000), "initial scan did not finish")
            val contributor = SymbolSearchContributor(index)

            val names = contributor.fetch("greet", 10).map { it.presentationText }
            assertEquals(listOf("greet", "Greeter"), names)
            val method = contributor.fetch("greet", 10).single { it.presentationText == "greet" }
            assertEquals("Greeter — src/Greeting.kt:4", method.secondaryText)
            assertEquals(SearchTab.SYMBOLS, contributor.tab)

            val standalone = contributor.fetch("standalone", 10).single()
            assertEquals("src/Greeting.kt:7", standalone.secondaryText)
        } finally {
            project.dispose()
        }
    }

    private fun project(vararg files: Pair<String, String>): Project {
        Application.resetForTests()
        for ((name, content) in files) {
            val path = tempDir.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
        return Project(tempDir)
    }
}
