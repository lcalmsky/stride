package stride.core.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionArea
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class PluginManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun pluginDirectory(name: String, xml: String): Path {
        val directory = tempDir.resolve(name)
        val metaInf = directory.resolve("META-INF")
        Files.createDirectories(metaInf)
        Files.writeString(metaInf.resolve("stride-plugin.xml"), xml)
        return directory
    }

    private fun pluginXml(id: String, depends: List<String> = emptyList(), extra: String = ""): String {
        val dependencies = depends.joinToString("") { "<depends>$it</depends>" }
        return """
            <stride-plugin>
              <id>$id</id>
              <name>$id</name>
              $dependencies
              <extensionPoints>
                <extensionPoint name="marker" beanClass="stride.core.extensions.ExtensionBean"/>
              </extensionPoints>
              $extra
            </stride-plugin>
        """.trimIndent()
    }

    private fun load(vararg directories: Path): Pair<ExtensionArea, LoadResult> {
        val loader = URLClassLoader(
            directories.map { it.toUri().toURL() }.toTypedArray(),
            javaClass.classLoader
        )
        val area = ExtensionArea()
        val result = loader.use { PluginManager(it).loadAll(area) }
        return area to result
    }

    @Test
    fun `dependencies are loaded before dependents`() {
        val a = pluginDirectory("a", pluginXml("test.a"))
        val b = pluginDirectory("b", pluginXml("test.b", listOf("test.a")))

        val (area, result) = load(a, b)

        assertEquals(listOf("test.a", "test.b"), result.enabled.map { it.id })
        assertTrue(result.disabled.isEmpty())
        assertTrue(area.hasExtensionPoint("test.a.marker"))
        assertTrue(area.hasExtensionPoint("test.b.marker"))
    }

    @Test
    fun `plugin with missing dependency is disabled`() {
        val a = pluginDirectory("a", pluginXml("test.a", listOf("test.missing")))

        val (_, result) = load(a)

        assertTrue(result.enabled.isEmpty())
        assertEquals("Missing dependency: test.missing", result.disabled["test.a"])
    }

    @Test
    fun `plugins in a dependency cycle are disabled`() {
        val a = pluginDirectory("a", pluginXml("test.a", listOf("test.b")))
        val b = pluginDirectory("b", pluginXml("test.b", listOf("test.a")))

        val (_, result) = load(a, b)

        assertTrue(result.enabled.isEmpty())
        assertEquals("Dependency cycle", result.disabled["test.a"])
        assertEquals("Dependency cycle", result.disabled["test.b"])
    }

    @Test
    fun `duplicate plugin ids keep the first one`() {
        val a = pluginDirectory("a", pluginXml("test.duplicate"))
        val b = pluginDirectory("b", pluginXml("test.duplicate"))

        val (_, result) = load(a, b)

        assertEquals(1, result.enabled.size)
        assertEquals("test.duplicate", result.enabled[0].id)
        assertEquals("Duplicate plugin id", result.disabled["test.duplicate"])
    }

    @Test
    fun `malformed descriptor disables only that plugin`() {
        val broken = pluginDirectory("broken", "<stride-plugin><id>test.broken")
        val good = pluginDirectory("good", pluginXml("test.good"))

        val (_, result) = load(broken, good)

        assertEquals(listOf("test.good"), result.enabled.map { it.id })
        assertEquals(1, result.disabled.size)
        assertTrue(result.disabled.values.single().startsWith("Malformed descriptor"))
    }

    @Test
    fun `extension for unknown extension point is skipped`() {
        val directory = pluginDirectory(
            "a",
            pluginXml(
                "test.a",
                extra = """
                    <extensions defaultExtensionNs="stride">
                      <noSuchPoint name="x"/>
                    </extensions>
                """.trimIndent()
            )
        )

        val (area, result) = load(directory)

        assertEquals(listOf("test.a"), result.enabled.map { it.id })
        assertFalse(area.hasExtensionPoint("stride.noSuchPoint"))
    }
}
