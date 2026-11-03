package stride.core.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionArea
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

interface CrossPluginMarker {
    val plugin: String
}

class CrossPluginMarkerImpl : CrossPluginMarker {
    override val plugin: String = "test.a"
}

class CrossPluginExtensionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun pluginDirectory(name: String, xml: String): Path {
        val metaInf = tempDir.resolve(name).resolve("META-INF")
        Files.createDirectories(metaInf)
        Files.writeString(metaInf.resolve("stride-plugin.xml"), xml)
        return tempDir.resolve(name)
    }

    private fun load(vararg directories: Path): Pair<ExtensionArea, LoadResult> {
        val loader = URLClassLoader(directories.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        val area = ExtensionArea()
        val result = loader.use { PluginManager(it).loadAll(area) }
        return area to result
    }

    @Test
    fun `a plugin registers an extension into the extension point of its dependency`() {
        val b = pluginDirectory(
            "b",
            """
            <stride-plugin>
              <id>test.b</id>
              <name>test.b</name>
              <extensionPoints>
                <extensionPoint qualifiedName="stride.crossPluginMarker"
                                interface="stride.core.plugins.CrossPluginMarker"/>
              </extensionPoints>
            </stride-plugin>
            """.trimIndent(),
        )
        val a = pluginDirectory(
            "a",
            """
            <stride-plugin>
              <id>test.a</id>
              <name>test.a</name>
              <depends>test.b</depends>
              <extensions defaultExtensionNs="stride">
                <crossPluginMarker implementation="stride.core.plugins.CrossPluginMarkerImpl"/>
              </extensions>
            </stride-plugin>
            """.trimIndent(),
        )

        val (area, result) = load(a, b)

        assertEquals(listOf("test.b", "test.a"), result.enabled.map { it.id })
        assertTrue(result.disabled.isEmpty(), result.disabled.toString())
        val extensions = area.getExtensionList<CrossPluginMarker>("stride.crossPluginMarker")
        assertEquals(1, extensions.size)
        assertTrue(extensions.single() is CrossPluginMarkerImpl)
    }

    @Test
    fun `defaultExtensionNs names the dependency extension point`() {
        val b = pluginDirectory(
            "b",
            """
            <stride-plugin>
              <id>test.b</id>
              <name>test.b</name>
              <extensionPoints>
                <extensionPoint name="marker" interface="stride.core.plugins.CrossPluginMarker"/>
              </extensionPoints>
            </stride-plugin>
            """.trimIndent(),
        )
        val a = pluginDirectory(
            "a",
            """
            <stride-plugin>
              <id>test.a</id>
              <name>test.a</name>
              <depends>test.b</depends>
              <extensions defaultExtensionNs="test.b">
                <marker implementation="stride.core.plugins.CrossPluginMarkerImpl"/>
              </extensions>
            </stride-plugin>
            """.trimIndent(),
        )

        val (area, result) = load(a, b)

        assertTrue(area.hasExtensionPoint("test.b.marker"), "test.b.marker was not registered")
        assertEquals(listOf("test.b", "test.a"), result.enabled.map { it.id })
        val extensions = area.getExtensionList<CrossPluginMarker>("test.b.marker")
        assertEquals(1, extensions.size)
        assertEquals("test.a", extensions.single().plugin)
    }
}
