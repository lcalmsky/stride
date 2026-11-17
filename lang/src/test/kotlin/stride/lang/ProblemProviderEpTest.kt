package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor

class ProblemProviderEpTest {
    private lateinit var area: ExtensionArea

    @BeforeEach
    fun setUp() {
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                ProblemProvider.EP_NAME,
                null,
                ProblemProviderBean::class.java.name,
                "stride.core",
            )
        )
    }

    private fun register(language: String, provider: Class<out ProblemProvider>) {
        area.registerExtension(
            ProblemProvider.EP_NAME,
            ExtensionElement("test", mapOf("language" to language, "implementationClass" to provider.name), null),
        )
    }

    private fun context(): ProblemContext = ProblemContext("0123456789", "Sample.lisp")

    @Test
    fun `providers resolve by language key`() {
        register("lisp", LateProvider::class.java)
        register("lisp", EarlyProvider::class.java)
        register("other", EarlyProvider::class.java)

        assertEquals(
            listOf(LateProvider::class.java.name, EarlyProvider::class.java.name),
            ProblemProvider.forLanguage(Language("lisp"), area).map { it::class.java.name },
        )
        assertEquals(1, ProblemProvider.forLanguage(Language("other"), area).size)
        assertTrue(ProblemProvider.forLanguage(Language("unknown"), area).isEmpty())
    }

    @Test
    fun `problems sort by start with errors before warnings at the same start`() {
        register("lisp", LateProvider::class.java)
        register("lisp", EarlyProvider::class.java)

        val problems = ProblemProvider.collect(Language("lisp"), context(), area)

        assertEquals(listOf("early", "error at 5", "warning at 5", "late"), problems.map { it.message })
    }

    @Test
    fun `a throwing provider is skipped while the others still contribute`() {
        register("lisp", ThrowingProvider::class.java)
        register("lisp", EarlyProvider::class.java)

        val problems = ProblemProvider.collect(Language("lisp"), context(), area)

        assertEquals(listOf("early", "error at 5"), problems.map { it.message })
    }

    @Test
    fun `problems outside the text are dropped`() {
        register("lisp", OutOfRangeProvider::class.java)

        val problems = ProblemProvider.collect(Language("lisp"), context(), area)

        assertEquals(listOf("end of text"), problems.map { it.message })
    }

    @Test
    fun `an unknown language collects nothing`() {
        register("lisp", EarlyProvider::class.java)

        assertTrue(ProblemProvider.collect(Language("unknown"), context(), area).isEmpty())
    }

    private class EarlyProvider : ProblemProvider {
        override fun collect(context: ProblemContext): List<Problem> = listOf(
            Problem(5, 6, ProblemSeverity.ERROR, "error at 5"),
            Problem(0, 1, ProblemSeverity.WARNING, "early"),
        )
    }

    private class LateProvider : ProblemProvider {
        override fun collect(context: ProblemContext): List<Problem> = listOf(
            Problem(8, 9, ProblemSeverity.ERROR, "late"),
            Problem(5, 7, ProblemSeverity.WARNING, "warning at 5"),
        )
    }

    private class OutOfRangeProvider : ProblemProvider {
        override fun collect(context: ProblemContext): List<Problem> = listOf(
            Problem(-1, 2, ProblemSeverity.ERROR, "negative start"),
            Problem(4, 3, ProblemSeverity.ERROR, "reversed"),
            Problem(9, 11, ProblemSeverity.ERROR, "past the end"),
            Problem(10, 10, ProblemSeverity.WARNING, "end of text"),
        )
    }

    private class ThrowingProvider : ProblemProvider {
        override fun collect(context: ProblemContext): List<Problem> = throw IllegalStateException("boom")
    }
}
