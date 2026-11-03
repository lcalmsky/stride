package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionPointDescriptor
import stride.core.plugins.PluginDescriptor
import stride.core.plugins.PluginDescriptorParser

class CommenterEpTest {
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
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                Commenter.EP_NAME,
                null,
                CommenterBean::class.java.name,
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
    fun `kotlin and json commenters resolve by language`() {
        val kotlin = Commenter.forLanguage(Language("kotlin"), area)
        assertNotNull(kotlin)
        assertEquals("//", kotlin!!.lineCommentPrefix)
        assertEquals("/*", kotlin.blockCommentPrefix)
        assertEquals("*/", kotlin.blockCommentSuffix)

        val json = Commenter.forLanguage(Language("json"), area)
        assertNotNull(json)
        assertEquals("//", json!!.lineCommentPrefix)
        assertEquals("/*", json.blockCommentPrefix)
        assertEquals("*/", json.blockCommentSuffix)
    }

    @Test
    fun `a language without a commenter resolves to null`() {
        assertNull(Commenter.forLanguage(Language("text"), area))
    }

    @Test
    fun `each language plugin registers exactly one commenter`() {
        assertEquals(
            1,
            descriptors.first { it.id == "stride.lang.kotlin" }.extensions.count { it.first == Commenter.EP_NAME },
        )
        assertEquals(
            1,
            descriptors.first { it.id == "stride.lang.json" }.extensions.count { it.first == Commenter.EP_NAME },
        )
    }
}
