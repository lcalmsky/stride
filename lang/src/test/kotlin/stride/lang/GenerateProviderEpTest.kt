package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor

class GenerateProviderEpTest {
    private lateinit var area: ExtensionArea

    @BeforeEach
    fun setUp() {
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                GenerateProvider.EP_NAME,
                null,
                GenerateProviderBean::class.java.name,
                "stride.core",
            )
        )
    }

    private fun register(language: String, provider: Class<out GenerateProvider>) {
        area.registerExtension(
            GenerateProvider.EP_NAME,
            ExtensionElement("test", mapOf("language" to language, "implementationClass" to provider.name), null),
        )
    }

    private fun context(fileName: String = "Sample.lisp"): GenerateContext =
        GenerateContext("class A\n", 0, fileName, "/project/$fileName", types = null)

    @Test
    fun `providers resolve by language key`() {
        register("lisp", ToStringOnly::class.java)
        register("other", ImplementOnly::class.java)

        assertEquals(
            listOf(ToStringOnly::class.java.name),
            GenerateProvider.forLanguage(Language("lisp"), area).map { it::class.java.name },
        )
        assertTrue(GenerateProvider.forLanguage(Language("unknown"), area).isEmpty())
    }

    @Test
    fun `the first provider that implements the kind answers`() {
        register("lisp", ToStringOnly::class.java)
        register("lisp", ImplementOnly::class.java)

        val toString = GenerateProvider.prepare(Language("lisp"), GenerateKind.TO_STRING, context(), area)
        val implement = GenerateProvider.prepare(Language("lisp"), GenerateKind.IMPLEMENT_METHODS, context(), area)

        assertEquals(RefactoringStep.Refused("toString answered"), toString)
        assertTrue(implement is RefactoringStep.ChooseMembers)
    }

    @Test
    fun `a kind no provider implements is not available`() {
        register("lisp", ToStringOnly::class.java)

        val step = GenerateProvider.prepare(Language("lisp"), GenerateKind.EQUALS_HASH_CODE, context(), area)

        assertEquals(RefactoringStep.Refused("equals() and hashCode() is not available for .lisp files"), step)
    }

    @Test
    fun `a file without a language is not available`() {
        val step = GenerateProvider.prepare(null, GenerateKind.SECONDARY_CONSTRUCTOR, context("Main.java"), area)

        assertEquals(RefactoringStep.Refused("Secondary Constructor is not available for .java files"), step)
    }

    @Test
    fun `a throwing provider refuses instead of reaching the app`() {
        register("lisp", Throwing::class.java)
        register("lisp", ToStringOnly::class.java)

        val step = GenerateProvider.prepare(Language("lisp"), GenerateKind.TO_STRING, context(), area)

        assertEquals(RefactoringStep.Refused("toString() failed: IllegalStateException"), step)
    }

    @Test
    fun `names without an extension read this file`() {
        assertEquals(
            "Override Methods is not available for this file",
            GenerateProvider.notAvailable(GenerateKind.OVERRIDE_METHODS, "Makefile"),
        )
    }

    @Test
    fun `member choosers hand back the checked rows`() {
        val edit = RefactoringEdit(listOf(TextEdit(0, 0, "x")), caret = 1)
        val first = MemberOption("run(): Unit", group = "Task", checked = true)
        val second = MemberOption("size: Int", group = "Task", disabledReason = "type not written")
        val step = RefactoringStep.ChooseMembers("Implement Members", listOf(first, second)) { checked ->
            if (checked.isEmpty()) NameCheck.Invalid("Check a member") else NameCheck.Valid(edit)
        }

        assertEquals(NameCheck.Invalid("Check a member"), step.complete(emptyList()))
        assertSame(edit, (step.complete(listOf(first)) as NameCheck.Valid).edit)
    }

    private class ToStringOnly : GenerateProvider {
        override fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep? =
            if (kind == GenerateKind.TO_STRING) RefactoringStep.Refused("toString answered") else null
    }

    private class ImplementOnly : GenerateProvider {
        override fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep? = when (kind) {
            GenerateKind.IMPLEMENT_METHODS -> RefactoringStep.ChooseMembers("Implement Members", emptyList()) {
                NameCheck.Invalid("nothing")
            }
            else -> null
        }
    }

    private class Throwing : GenerateProvider {
        override fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep? =
            throw IllegalStateException("boom")
    }
}
