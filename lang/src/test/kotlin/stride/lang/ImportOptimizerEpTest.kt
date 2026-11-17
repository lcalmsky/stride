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

class ImportOptimizerEpTest {
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
            ExtensionPointDescriptor(
                ImportOptimizer.EP_NAME,
                null,
                ImportOptimizerBean::class.java.name,
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
    fun `the kotlin plugin registers one import optimizer`() {
        assertEquals(
            1,
            descriptors.first { it.id == "stride.lang.kotlin" }.extensions.count { it.first == ImportOptimizer.EP_NAME },
        )
        val optimizer = ImportOptimizer.forLanguage(Language("kotlin"), area)
        assertNotNull(optimizer)
        assertEquals("stride.lang.kotlin.KotlinImportOptimizer", optimizer!!::class.java.name)
    }

    @Test
    fun `an unknown language resolves to null`() {
        assertNull(ImportOptimizer.forLanguage(Language("plain"), area))
    }
}
