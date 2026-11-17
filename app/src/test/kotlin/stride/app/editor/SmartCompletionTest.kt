package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartCompletionTest {
    private fun positionOf(text: String): CompletionPosition =
        SmartCompletion.positionAt(text, text.length)

    @Test
    fun `a type follows a colon`() {
        assertEquals(CompletionPosition.TYPE, positionOf("val a: Str"))
        assertEquals(CompletionPosition.TYPE, positionOf("val a:\n    Str"))
    }

    @Test
    fun `a cast or a check expects a type`() {
        assertEquals(CompletionPosition.TYPE, positionOf("val a = b as Str"))
        assertEquals(CompletionPosition.TYPE, positionOf("val a = b as? Str"))
        assertEquals(CompletionPosition.TYPE, positionOf("if (b is Str"))
        assertEquals(CompletionPosition.TYPE, positionOf("if (b !is Str"))
    }

    @Test
    fun `a generic argument list expects a type`() {
        assertEquals(CompletionPosition.TYPE, positionOf("val a: List<Str"))
        assertEquals(CompletionPosition.TYPE, positionOf("val a: Map<Int, Str"))
    }

    @Test
    fun `a call or an assignment expects an expression`() {
        assertEquals(CompletionPosition.EXPRESSION, positionOf("val a = Str"))
        assertEquals(CompletionPosition.EXPRESSION, positionOf("foo(Str"))
        assertEquals(CompletionPosition.EXPRESSION, positionOf("val a = b::Str"))
    }

    @Test
    fun `a prefix inside a string or a comment expects an expression`() {
        assertEquals(CompletionPosition.EXPRESSION, positionOf("val a = \"Str"))
        assertEquals(CompletionPosition.EXPRESSION, positionOf("// Str"))
    }

    @Test
    fun `a comma outside angle brackets expects an expression`() {
        assertEquals(CompletionPosition.EXPRESSION, positionOf("foo(a, Str"))
    }
}
