package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor

class RefactoringProviderEpTest {
    private lateinit var area: ExtensionArea

    @BeforeEach
    fun setUp() {
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                RefactoringProvider.EP_NAME,
                null,
                RefactoringProviderBean::class.java.name,
                "stride.core",
            )
        )
    }

    private fun register(language: String, provider: Class<out RefactoringProvider>) {
        area.registerExtension(
            RefactoringProvider.EP_NAME,
            ExtensionElement("test", mapOf("language" to language, "implementationClass" to provider.name), null),
        )
    }

    private fun context(fileName: String = "Sample.lisp"): RefactoringContext = RefactoringContext("code\n", 0, 4, fileName)

    @Test
    fun `providers resolve by language key`() {
        register("lisp", InlineOnly::class.java)
        register("other", ExtractOnly::class.java)

        assertEquals(
            listOf(InlineOnly::class.java.name),
            RefactoringProvider.forLanguage(Language("lisp"), area).map { it::class.java.name },
        )
        assertTrue(RefactoringProvider.forLanguage(Language("unknown"), area).isEmpty())
    }

    @Test
    fun `the first provider that implements the kind answers`() {
        register("lisp", InlineOnly::class.java)
        register("lisp", ExtractOnly::class.java)

        val inline = RefactoringProvider.prepare(Language("lisp"), RefactoringKind.INLINE, context(), area)
        val extract = RefactoringProvider.prepare(Language("lisp"), RefactoringKind.EXTRACT_METHOD, context(), area)

        assertEquals(RefactoringStep.Refused("inline answered"), inline)
        assertTrue(extract is RefactoringStep.Apply)
        assertEquals(listOf(TextEdit(0, 4, "done")), (extract as RefactoringStep.Apply).edit.edits)
    }

    @Test
    fun `a kind no provider implements is not available`() {
        register("lisp", InlineOnly::class.java)

        val step = RefactoringProvider.prepare(Language("lisp"), RefactoringKind.INTRODUCE_VARIABLE, context(), area)

        assertEquals(RefactoringStep.Refused("Introduce Variable is not available for .lisp files"), step)
    }

    @Test
    fun `a file without a language is not available`() {
        val step = RefactoringProvider.prepare(null, RefactoringKind.INLINE, context("Main.java"), area)

        assertEquals(RefactoringStep.Refused("Inline is not available for .java files"), step)
    }

    @Test
    fun `a throwing provider refuses instead of reaching the app`() {
        register("lisp", Throwing::class.java)
        register("lisp", InlineOnly::class.java)

        val step = RefactoringProvider.prepare(Language("lisp"), RefactoringKind.INLINE, context(), area)

        assertEquals(RefactoringStep.Refused("Inline failed: IllegalStateException"), step)
    }

    @Test
    fun `names without an extension read this file`() {
        assertEquals(
            "Extract Method is not available for this file",
            RefactoringProvider.notAvailable(RefactoringKind.EXTRACT_METHOD, "Makefile"),
        )
        assertEquals(
            "Inline is not available for this file",
            RefactoringProvider.notAvailable(RefactoringKind.INLINE, ".gitignore"),
        )
    }

    @Test
    fun `name checks carry the edit or the message`() {
        val edit = RefactoringEdit(listOf(TextEdit(0, 1, "x")), caret = 1, message = "Done")
        val ask = RefactoringStep.AskName("Name", "value") { name ->
            if (name.isEmpty()) NameCheck.Invalid("Type a name") else NameCheck.Valid(edit)
        }

        assertEquals(NameCheck.Invalid("Type a name"), ask.complete(""))
        assertSame(edit, (ask.complete("count") as NameCheck.Valid).edit)
    }

    private class InlineOnly : RefactoringProvider {
        override fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep? =
            if (kind == RefactoringKind.INLINE) RefactoringStep.Refused("inline answered") else null
    }

    private class ExtractOnly : RefactoringProvider {
        override fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep? =
            if (kind == RefactoringKind.EXTRACT_METHOD) {
                RefactoringStep.Apply(RefactoringEdit(listOf(TextEdit(0, 4, "done")), caret = 4))
            } else {
                null
            }
    }

    private class Throwing : RefactoringProvider {
        override fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep? =
            throw IllegalStateException("boom")
    }
}
