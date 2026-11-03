package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionPointDescriptor
import stride.core.plugins.PluginDescriptor
import stride.core.plugins.PluginDescriptorParser

class LangPluginIntegrationTest {
    private lateinit var area: ExtensionArea
    private lateinit var descriptors: List<PluginDescriptor>

    @BeforeEach
    fun setUp() {
        val loader = javaClass.classLoader
        descriptors = loader.getResources("META-INF/stride-plugin.xml").toList().map { url ->
            url.openStream().use { PluginDescriptorParser.parse(it, url.toString()) }
        }
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(FileTypeRegistry.FILE_TYPE_EP, null, FileTypeBean::class.java.name, "stride.core")
        )
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                SyntaxHighlighterFactory.EP_NAME,
                null,
                SyntaxHighlighterFactoryBean::class.java.name,
                "stride.core",
            )
        )
        for (descriptor in descriptors) {
            for ((extensionPoint, element) in descriptor.extensions) {
                area.registerExtension(extensionPoint, element)
            }
        }
    }

    @Test
    fun `both language plugin descriptors are parsed`() {
        assertEquals(setOf("stride.lang.kotlin", "stride.lang.json"), descriptors.map { it.id }.toSet())
        assertTrue(descriptors.all { "stride.core" in it.depends })
        val kotlin = descriptors.first { it.id == "stride.lang.kotlin" }
        assertEquals(2, kotlin.extensions.count { it.first == FileTypeRegistry.FILE_TYPE_EP })
        assertEquals(1, kotlin.extensions.count { it.first == SyntaxHighlighterFactory.EP_NAME })
    }

    @Test
    fun `file types resolve through the fileType extension point`() {
        val kotlin = FileTypeRegistry.getFileType("Main.kt", area)
        assertEquals("Kotlin", kotlin.name)
        assertEquals("kt", kotlin.defaultExtension)
        assertEquals(Language("kotlin"), (kotlin as LanguageFileType).language)
        assertSame(kotlin, FileTypeRegistry.getFileType("build.gradle.kts", area))

        val json = FileTypeRegistry.getFileType("package.json", area)
        assertEquals("JSON", json.name)
        assertEquals(Language("json"), (json as LanguageFileType).language)

        assertSame(PlainTextFileType, FileTypeRegistry.getFileType("analysis.md", area))
    }

    @Test
    fun `syntax highlighters resolve by language and drive the lexers`() {
        val kotlin = SyntaxHighlighterFactory.forLanguage(Language("kotlin"), area)
        assertNotNull(kotlin)
        assertTrue(tokens(kotlin!!, "fun main() { val x = 1 }").any { it.first == "fun" && it.second == "Kotlin:KEYWORD" })

        val json = SyntaxHighlighterFactory.forLanguage(Language("json"), area)
        assertNotNull(json)
        assertTrue(tokens(json!!, "{\"a\": true, \"b\": 12}").any { it.first == "12" && it.second == "JSON:NUMBER" })

        assertEquals(null, SyntaxHighlighterFactory.forLanguage(Language("plain"), area))
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
