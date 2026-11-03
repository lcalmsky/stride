package stride.core.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginDescriptorParserTest {
    private fun parse(xml: String): PluginDescriptor =
        PluginDescriptorParser.parse(xml.byteInputStream(), "test-source")

    @Test
    fun `parses all descriptor fields`() {
        val descriptor = parse(
            """
            <stride-plugin>
              <id>stride.lang.kotlin</id>
              <name>Kotlin</name>
              <depends>stride.core</depends>
              <depends>stride.lang</depends>
              <extensionPoints>
                <extensionPoint name="fileType" beanClass="stride.lang.FileTypeBean"/>
                <extensionPoint qualifiedName="stride.searchEverywhereContributor" interface="stride.search.Contributor"/>
              </extensionPoints>
              <extensions defaultExtensionNs="stride">
                <fileType name="Kotlin" extensions="kt;kts" implementationClass="stride.lang.KotlinFileType" order="first"/>
              </extensions>
            </stride-plugin>
            """.trimIndent()
        )

        assertEquals("stride.lang.kotlin", descriptor.id)
        assertEquals("Kotlin", descriptor.name)
        assertEquals(listOf("stride.core", "stride.lang"), descriptor.depends)
        assertEquals("test-source", descriptor.source)

        assertEquals(2, descriptor.extensionPoints.size)
        val fileTypePoint = descriptor.extensionPoints[0]
        assertEquals("stride.lang.kotlin.fileType", fileTypePoint.qualifiedName)
        assertEquals("stride.lang.FileTypeBean", fileTypePoint.beanClass)
        assertNull(fileTypePoint.interfaceClass)
        assertEquals("stride.lang.kotlin", fileTypePoint.pluginId)

        val searchPoint = descriptor.extensionPoints[1]
        assertEquals("stride.searchEverywhereContributor", searchPoint.qualifiedName)
        assertEquals("stride.search.Contributor", searchPoint.interfaceClass)
        assertNull(searchPoint.beanClass)

        assertEquals(1, descriptor.extensions.size)
        val (pointName, element) = descriptor.extensions[0]
        assertEquals("stride.fileType", pointName)
        assertEquals("stride.lang.kotlin", element.pluginId)
        assertEquals("Kotlin", element.attributes["name"])
        assertEquals("kt;kts", element.attributes["extensions"])
        assertEquals("stride.lang.KotlinFileType", element.attributes["implementationClass"])
        assertEquals("first", element.order)
    }

    @Test
    fun `core plugin names are prefixed with stride`() {
        val descriptor = parse(
            """
            <stride-plugin>
              <id>stride.core</id>
              <name>Core</name>
              <extensionPoints>
                <extensionPoint name="fileType" beanClass="stride.core.FileTypeBean"/>
              </extensionPoints>
            </stride-plugin>
            """.trimIndent()
        )

        assertEquals("stride.fileType", descriptor.extensionPoints[0].qualifiedName)
    }

    @Test
    fun `other plugin names are prefixed with the plugin id`() {
        val descriptor = parse(
            """
            <stride-plugin>
              <id>stride.lang.json</id>
              <name>JSON</name>
              <extensionPoints>
                <extensionPoint name="fileType" interface="stride.core.FileType"/>
              </extensionPoints>
              <extensions defaultExtensionNs="stride.lang.json">
                <fileType name="JSON" implementationClass="stride.lang.json.JsonFileType"/>
              </extensions>
            </stride-plugin>
            """.trimIndent()
        )

        assertEquals("stride.lang.json.fileType", descriptor.extensionPoints[0].qualifiedName)
        assertEquals("stride.lang.json.fileType", descriptor.extensions[0].first)
    }

    @Test
    fun `malformed xml throws`() {
        assertThrows(Exception::class.java) {
            parse("<stride-plugin><id>broken")
        }
    }

    @Test
    fun `missing plugin id throws`() {
        assertThrows(IllegalStateException::class.java) {
            parse("<stride-plugin><name>No id</name></stride-plugin>")
        }
    }
}
