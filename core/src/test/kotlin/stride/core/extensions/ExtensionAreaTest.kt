package stride.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CountingExtension {
    init {
        count.incrementAndGet()
    }

    companion object {
        val count = AtomicInteger()
    }
}

class SecondExtension

class ThirdExtension

class TestBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]
}

class CyclicA {
    init {
        CyclicTestArea.area.getExtensionList<Any>("test.cyclic.b")
    }
}

class CyclicB {
    init {
        CyclicTestArea.area.getExtensionList<Any>("test.cyclic.a")
    }
}

object CyclicTestArea {
    lateinit var area: ExtensionArea
}

class ExtensionAreaTest {
    private fun interfacePoint(area: ExtensionArea, name: String) {
        area.registerExtensionPoint(ExtensionPointDescriptor(name, null, null, "test"))
    }

    private fun implementationElement(implementation: Class<*>, order: String? = null): ExtensionElement =
        ExtensionElement("test", mapOf("implementation" to implementation.name), order)

    @Test
    fun `extension instance is created once and cached`() {
        CountingExtension.count.set(0)
        val area = ExtensionArea()
        interfacePoint(area, "test.counting")
        area.registerExtension("test.counting", implementationElement(CountingExtension::class.java))

        val first = area.getExtensionList<CountingExtension>("test.counting")
        val second = area.getExtensionList<CountingExtension>("test.counting")

        assertEquals(1, first.size)
        assertSame(first[0], second[0])
        assertEquals(1, CountingExtension.count.get())
    }

    @Test
    fun `first and last order attributes are honored`() {
        val area = ExtensionArea()
        interfacePoint(area, "test.ordered")
        area.registerExtension("test.ordered", implementationElement(SecondExtension::class.java))
        area.registerExtension("test.ordered", implementationElement(ThirdExtension::class.java, "last"))
        area.registerExtension("test.ordered", implementationElement(CountingExtension::class.java, "first"))

        val list = area.getExtensionList<Any>("test.ordered")

        assertEquals(
            listOf("CountingExtension", "SecondExtension", "ThirdExtension"),
            list.map { it::class.java.simpleName }
        )
    }

    @Test
    fun `cyclic initialization throws`() {
        val area = ExtensionArea()
        CyclicTestArea.area = area
        interfacePoint(area, "test.cyclic.a")
        interfacePoint(area, "test.cyclic.b")
        area.registerExtension("test.cyclic.a", implementationElement(CyclicA::class.java))
        area.registerExtension("test.cyclic.b", implementationElement(CyclicB::class.java))

        val exception = assertThrows(IllegalStateException::class.java) {
            area.getExtensionList<Any>("test.cyclic.a")
        }

        assertTrue(exception.message!!.contains("Cyclic extension initialization: test.cyclic.a"))
    }

    @Test
    fun `unloadable extension class is skipped`() {
        val area = ExtensionArea()
        interfacePoint(area, "test.bad")
        area.registerExtension(
            "test.bad",
            ExtensionElement("test", mapOf("implementation" to "does.not.Exist"), null)
        )
        area.registerExtension("test.bad", implementationElement(CountingExtension::class.java))

        val list = area.getExtensionList<Any>("test.bad")

        assertEquals(1, list.size)
        assertTrue(list[0] is CountingExtension)
    }

    @Test
    fun `bean extension point is filtered by key`() {
        val area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor("test.beans", null, TestBean::class.java.name, "test")
        )
        area.registerExtension(
            "test.beans",
            ExtensionElement("test", mapOf("language" to "kotlin", "extra" to "1"), null)
        )
        area.registerExtension("test.beans", ExtensionElement("test", mapOf("language" to "json"), null))

        val point = BeanExtensionPointName<TestBean>("test.beans")
        val beans = point.beans(area)

        assertEquals(2, beans.size)
        assertEquals("kotlin", beans[0].attributes["language"])
        assertEquals("1", beans[0].attributes["extra"])
        assertEquals("test", beans[0].pluginId)
        assertEquals(1, point.forKey("kotlin", area).size)
        assertEquals(1, point.forKey("json", area).size)
        assertEquals(0, point.forKey("python", area).size)
    }

    @Test
    fun `duplicate extension point registration throws`() {
        val area = ExtensionArea()
        area.registerExtensionPoint(ExtensionPointDescriptor("test.duplicate", null, null, "test"))

        assertThrows(IllegalStateException::class.java) {
            area.registerExtensionPoint(ExtensionPointDescriptor("test.duplicate", null, null, "test"))
        }
    }
}
