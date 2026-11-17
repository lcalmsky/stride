package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor

class IntentionProviderEpTest {
    private lateinit var area: ExtensionArea

    @BeforeEach
    fun setUp() {
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                IntentionProvider.EP_NAME,
                null,
                IntentionProviderBean::class.java.name,
                "stride.core",
            )
        )
    }

    private fun register(language: String, provider: Class<out IntentionProvider>) {
        area.registerExtension(
            IntentionProvider.EP_NAME,
            ExtensionElement("test", mapOf("language" to language, "implementationClass" to provider.name), null),
        )
    }

    private fun context(): IntentionContext = IntentionContext("code\n", 4, "Sample.lisp")

    @Test
    fun `providers resolve by language key`() {
        register("lisp", QuickFixProvider::class.java)
        register("lisp", IntentionProviderX::class.java)
        register("other", QuickFixProvider::class.java)

        assertEquals(
            listOf(QuickFixProvider::class.java.name, IntentionProviderX::class.java.name),
            IntentionProvider.forLanguage(Language("lisp"), area).map { it::class.java.name },
        )
        assertEquals(1, IntentionProvider.forLanguage(Language("other"), area).size)
        assertTrue(IntentionProvider.forLanguage(Language("unknown"), area).isEmpty())
    }

    @Test
    fun `quick fixes sort before intentions across providers`() {
        register("lisp", IntentionProviderX::class.java)
        register("lisp", SecondIntentionProvider::class.java)
        register("lisp", QuickFixProvider::class.java)
        register("lisp", SecondQuickFixProvider::class.java)

        val actions = IntentionProvider.collect(Language("lisp"), context(), area)

        assertEquals(listOf("fix a", "fix b", "intend a", "intend b"), actions.map { it.text })
    }

    @Test
    fun `a throwing provider is skipped while the others still contribute`() {
        register("lisp", QuickFixProvider::class.java)
        register("lisp", ThrowingProvider::class.java)
        register("lisp", IntentionProviderX::class.java)

        val actions = IntentionProvider.collect(Language("lisp"), context(), area)

        assertEquals(listOf("fix a", "intend a"), actions.map { it.text })
    }

    @Test
    fun `an unknown language collects nothing`() {
        register("lisp", QuickFixProvider::class.java)

        assertTrue(IntentionProvider.collect(Language("unknown"), context(), area).isEmpty())
    }

    private class QuickFixProvider : IntentionProvider {
        override fun collect(context: IntentionContext): List<IntentionAction> =
            listOf(IntentionAction("fix a", IntentionCategory.QUICK_FIX, emptyList()))
    }

    private class SecondQuickFixProvider : IntentionProvider {
        override fun collect(context: IntentionContext): List<IntentionAction> =
            listOf(IntentionAction("fix b", IntentionCategory.QUICK_FIX, emptyList()))
    }

    private class IntentionProviderX : IntentionProvider {
        override fun collect(context: IntentionContext): List<IntentionAction> =
            listOf(IntentionAction("intend a", IntentionCategory.INTENTION, emptyList()))
    }

    private class SecondIntentionProvider : IntentionProvider {
        override fun collect(context: IntentionContext): List<IntentionAction> =
            listOf(IntentionAction("intend b", IntentionCategory.INTENTION, emptyList()))
    }

    private class ThrowingProvider : IntentionProvider {
        override fun collect(context: IntentionContext): List<IntentionAction> =
            throw IllegalStateException("boom")
    }
}
