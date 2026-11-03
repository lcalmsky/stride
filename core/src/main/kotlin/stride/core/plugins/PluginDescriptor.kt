package stride.core.plugins

import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

data class PluginDescriptor(
    val id: String,
    val name: String,
    val depends: List<String>,
    val extensionPoints: List<ExtensionPointDescriptor>,
    val extensions: List<Pair<String, ExtensionElement>>,
    val source: String
)

object PluginDescriptorParser {
    private const val CORE_PLUGIN_ID = "stride.core"
    private const val DEFAULT_EXTENSION_NS = "stride"

    fun parse(xml: InputStream, source: String): PluginDescriptor {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        val reader = factory.createXMLStreamReader(xml)

        var id: String? = null
        var name: String? = null
        val depends = ArrayList<String>()
        val extensionPoints = ArrayList<ExtensionPointDescriptor>()
        val extensions = ArrayList<Pair<String, ExtensionElement>>()
        var extensionPointsDepth = -1
        var extensionsDepth = -1
        var defaultNamespace = DEFAULT_EXTENSION_NS
        var rootSeen = false
        var depth = -1
        val text = StringBuilder()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    depth++
                    val localName = reader.localName
                    when {
                        localName == "stride-plugin" && depth == 0 -> rootSeen = true
                        localName == "extensionPoints" && depth == 1 -> extensionPointsDepth = depth
                        localName == "extensions" && depth == 1 -> {
                            extensionsDepth = depth
                            defaultNamespace = reader.getAttributeValue(null, "defaultExtensionNs") ?: DEFAULT_EXTENSION_NS
                        }
                        localName == "extensionPoint" && extensionPointsDepth >= 0 -> {
                            extensionPoints.add(parseExtensionPoint(reader, requireId(id, source)))
                        }
                        extensionsDepth >= 0 && depth == extensionsDepth + 1 -> {
                            val attributes = attributes(reader)
                            extensions.add(
                                "$defaultNamespace.$localName" to
                                    ExtensionElement(requireId(id, source), attributes, attributes["order"])
                            )
                        }
                    }
                    text.clear()
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.text)
                XMLStreamConstants.END_ELEMENT -> {
                    val localName = reader.localName
                    when {
                        depth == 1 && localName == "id" -> id = text.toString().trim()
                        depth == 1 && localName == "name" -> name = text.toString().trim()
                        depth == 1 && localName == "depends" -> depends.add(text.toString().trim())
                        depth == 1 && localName == "extensionPoints" -> extensionPointsDepth = -1
                        depth == 1 && localName == "extensions" -> extensionsDepth = -1
                    }
                    text.clear()
                    depth--
                }
            }
        }

        if (!rootSeen) throw IllegalStateException("Not a stride plugin descriptor: $source")
        val descriptorId = requireId(id, source)
        return PluginDescriptor(
            id = descriptorId,
            name = name ?: descriptorId,
            depends = depends,
            extensionPoints = extensionPoints,
            extensions = extensions,
            source = source
        )
    }

    private fun requireId(id: String?, source: String): String =
        id ?: throw IllegalStateException("Plugin id is missing or not declared before nested elements: $source")

    private fun parseExtensionPoint(reader: XMLStreamReader, pluginId: String): ExtensionPointDescriptor {
        val qualifiedName = reader.getAttributeValue(null, "qualifiedName")
        val simpleName = reader.getAttributeValue(null, "name")
        val resolvedName = qualifiedName
            ?: simpleName?.let { qualify(pluginId, it) }
            ?: throw IllegalStateException("extensionPoint without name or qualifiedName in plugin $pluginId")
        if (resolvedName.isBlank()) throw IllegalStateException("extensionPoint with blank name in plugin $pluginId")
        return ExtensionPointDescriptor(
            qualifiedName = resolvedName,
            interfaceClass = reader.getAttributeValue(null, "interface"),
            beanClass = reader.getAttributeValue(null, "beanClass"),
            pluginId = pluginId
        )
    }

    private fun qualify(pluginId: String, name: String): String =
        if (pluginId == CORE_PLUGIN_ID) "$DEFAULT_EXTENSION_NS.$name" else "$pluginId.$name"

    private fun attributes(reader: XMLStreamReader): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (i in 0 until reader.attributeCount) {
            result[reader.getAttributeLocalName(i)] = reader.getAttributeValue(i)
        }
        return result
    }
}
