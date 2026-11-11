package stride.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.app.editor.FILE_EDITOR_PROVIDER_EP
import stride.app.editor.FileEditorProvider
import stride.app.tree.TREE_STRUCTURE_PROVIDER_EP
import stride.app.tree.TreeStructureProvider
import stride.core.plugins.LoadResult
import stride.core.services.Application
import stride.lang.FileTypeRegistry
import stride.lang.SyntaxHighlighter
import stride.lang.SyntaxHighlighterFactory
import stride.lang.json.JsonFileType
import stride.lang.json.JsonLanguage
import stride.lang.kotlin.KotlinFileType
import stride.lang.kotlin.KotlinLanguage
import stride.search.SEARCH_EVERYWHERE_CONTRIBUTOR_EP
import stride.search.SearchEverywhereContributorFactory

class BuiltInPluginLoadingTest {
    private lateinit var result: LoadResult

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        result = Application.init(BuiltInPluginLoadingTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `every bundled descriptor is enabled`() {
        assertEquals(
            setOf("stride.core", "stride.lang.kotlin", "stride.lang.json"),
            result.enabled.map { it.id }.toSet(),
        )
        assertEquals(3, result.enabled.size)
        assertTrue(result.disabled.isEmpty(), result.disabled.toString())
    }

    @Test
    fun `kt and json resolve to their file types through the fileType extension point`() {
        assertSame(KotlinFileType, FileTypeRegistry.getFileType("Main.kt"))
        assertSame(KotlinFileType, FileTypeRegistry.getFileType("build.gradle.kts"))
        assertSame(JsonFileType, FileTypeRegistry.getFileType("settings.json"))

        assertEquals("Kotlin", FileTypeRegistry.getFileTypeByExtension("kt").name)
        assertEquals("JSON", FileTypeRegistry.getFileTypeByExtension("json").name)
    }

    @Test
    fun `syntax highlighter factories give a lexer that emits tokens for each language`() {
        val kotlin = requireNotNull(SyntaxHighlighterFactory.forLanguage(KotlinLanguage))
        assertTrue(tokens(kotlin, "fun main() { val x = 1 }").any { it.first == "fun" && it.second == "Kotlin:KEYWORD" })

        val json = requireNotNull(SyntaxHighlighterFactory.forLanguage(JsonLanguage))
        assertTrue(tokens(json, "{\"enabled\": true}").any { it.first == "true" && it.second == "JSON:KEYWORD" })
    }

    @Test
    fun `the core descriptor declares exactly one extension for each interface extension point`() {
        val core = result.enabled.single { it.id == "stride.core" }

        assertEquals(3, core.extensions.count { it.first == SEARCH_EVERYWHERE_CONTRIBUTOR_EP })
        assertEquals(1, core.extensions.count { it.first == FILE_EDITOR_PROVIDER_EP })
        assertEquals(1, core.extensions.count { it.first == TREE_STRUCTURE_PROVIDER_EP })

        val area = Application.extensionArea
        val contributors = area.getExtensionList<Any>(SEARCH_EVERYWHERE_CONTRIBUTOR_EP)
        assertEquals(3, contributors.size)
        assertTrue(contributors.all { it is SearchEverywhereContributorFactory<*> })
        assertTrue(area.getExtensionList<Any>(FILE_EDITOR_PROVIDER_EP).single() is FileEditorProvider)
        assertTrue(area.getExtensionList<Any>(TREE_STRUCTURE_PROVIDER_EP).single() is TreeStructureProvider)
    }

    private fun tokens(highlighter: SyntaxHighlighter, text: String): List<Pair<String, String>> {
        val lexer = highlighter.getHighlightingLexer()
        val result = ArrayList<Pair<String, String>>()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.getTokenType() ?: break
            result.add(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()) to type.debugName)
            lexer.advance()
        }
        return result
    }
}
