package stride.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

interface Greeting {
    val text: String
}

class GreetingImpl : Greeting {
    override val text: String = "constructed"
}

object ObjectGreeting : Greeting {
    override val text: String = "object"
}

class ExtensionBeanInstanceTest {
    private fun bean(attributes: Map<String, String> = emptyMap()): TestBean {
        val area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor("test.bean.instance", null, TestBean::class.java.name, "test")
        )
        area.registerExtension("test.bean.instance", ExtensionElement("test.plugin", attributes, null))
        return area.getExtensionList<TestBean>("test.bean.instance").single()
    }

    @Test
    fun `instance instantiates a no-arg class once per bean`() {
        val bean = bean(mapOf("implementationClass" to GreetingImpl::class.java.name))

        val first = bean.instance(Greeting::class)
        val second = bean.instance(Greeting::class)

        assertTrue(first is GreetingImpl, first::class.java.name)
        assertEquals("constructed", first.text)
        assertSame(first, second)
    }

    @Test
    fun `instance reuses a Kotlin object through its INSTANCE field`() {
        val bean = bean(mapOf("implementationClass" to ObjectGreeting::class.java.name))

        assertSame(ObjectGreeting, bean.instance(Greeting::class))
    }

    @Test
    fun `instance without implementationClass is rejected`() {
        val bean = bean()

        val exception = assertThrows(IllegalStateException::class.java) {
            bean.instance(Greeting::class)
        }

        assertTrue(exception.message!!.contains("implementationClass"), exception.message)
    }

    @Test
    fun `instance with an unknown class is rejected on every call`() {
        val bean = bean(mapOf("implementationClass" to "does.not.Exist"))

        assertThrows(ClassNotFoundException::class.java) { bean.instance(Greeting::class) }
        assertThrows(ClassNotFoundException::class.java) { bean.instance(Greeting::class) }
    }
}
