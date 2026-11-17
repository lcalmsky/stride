package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationPeekTest {
    @Test
    fun `a KDoc above an annotation is the documentation and joins the definition`() {
        val text = """
            /** Greets the caller.
             * More detail. */
            @Deprecated("demo")
            fun greet(name: String) {
                println(name)
            }
        """.trimIndent()

        val source = DeclarationSource.extract(text, 3)

        assertEquals("fun greet(name: String)", source.signature)
        assertEquals("Greets the caller.\nMore detail.", source.doc)
        assertEquals(text, source.definition)
        assertEquals(0, source.definitionTruncatedLines)
    }

    @Test
    fun `consecutive line comments are the documentation`() {
        val text = """
            // Adds one.
            // Returns it.
            fun add(a: Int): Int = a + 1
        """.trimIndent()

        val source = DeclarationSource.extract(text, 2)

        assertEquals("Adds one.\nReturns it.", source.doc)
        assertEquals("fun add(a: Int): Int", source.signature)
        assertEquals(text, source.definition)
    }

    @Test
    fun `a blank line between the comments and the declaration breaks the doc`() {
        val text = """
            // Not documentation.

            fun f() {}
        """.trimIndent()

        assertNull(DeclarationSource.extract(text, 2).doc)

        val blockText = """
            /** Not documentation. */

            fun f() {}
        """.trimIndent()

        assertNull(DeclarationSource.extract(blockText, 2).doc)
    }

    @Test
    fun `a multi-line signature joins its lines and stops at the block brace`() {
        val text = """
            fun long(
                a: Int,
                b: String,
            ): Unit {
                println(a)
            }
        """.trimIndent()

        val source = DeclarationSource.extract(text, 0)

        assertEquals("fun long( a: Int, b: String, ): Unit", source.signature)
        assertEquals(text, source.definition)
    }

    @Test
    fun `an expression body ends the signature at the top-level equals`() {
        assertEquals("fun f()", DeclarationSource.signature("fun f() = 1", 0))
    }

    @Test
    fun `a class definition runs to the matching closing brace`() {
        val text = """
            class Foo {
                fun a() {}
            }
        """.trimIndent()

        val source = DeclarationSource.extract(text, 0)

        assertEquals("class Foo", source.signature)
        assertEquals(text, source.definition)
    }

    @Test
    fun `a definition longer than forty lines is cut and reports the rest`() {
        val body = (1..60).joinToString("\n") { "    val a$it = $it" }
        val text = "class Foo {\n$body\n}"

        val source = DeclarationSource.extract(text, 0)

        assertEquals(40, source.definition.lines().size)
        assertEquals(22, source.definitionTruncatedLines)
    }

    @Test
    fun `a brace inside a string does not end the signature`() {
        val text = "fun f(x: String = \"{\") {}"

        assertEquals("fun f(x: String = \"{\")", DeclarationSource.signature(text, 0))
    }

    @Test
    fun `comments in a parameter list stay out of the signature`() {
        val text = """
            fun f(
                a: Int, // first
                /* second */ b: String,
            ) {}
        """.trimIndent()

        val signature = DeclarationSource.signature(text, 0)

        assertEquals("fun f( a: Int, b: String, )", signature)
        assertEquals(listOf("a: Int", "b: String"), CallText.parameters(signature))
    }

    @Test
    fun `a comparison in a default value does not join parameters`() {
        assertEquals(
            listOf("a: Boolean = 1 < 2", "b: Int"),
            CallText.parameters("fun f(a: Boolean = 1 < 2, b: Int)"),
        )
    }

    @Test
    fun `the call scan finds the innermost unclosed paren`() {
        val text = "foo(1, bar(2, 3"
        assertEquals(text.lastIndexOf('('), CallText.openParen(text, text.length))
        assertNull(CallText.openParen("foo(1)", 6))
    }

    @Test
    fun `the callee is the identifier before the paren or its generic arguments`() {
        assertEquals("foo", CallText.calleeName("foo(1", 3))
        assertEquals("foo", CallText.calleeName("foo (1", 4))
        val generic = "foo<String, Int>(1"
        assertEquals("foo", CallText.calleeName(generic, generic.indexOf('(')))
    }

    @Test
    fun `parameters split at top-level commas and drop an empty tail`() {
        assertEquals(
            listOf("a: Int", "b: Map<String, Int>"),
            CallText.parameters("fun f(a: Int, b: Map<String, Int>)"),
        )
        assertEquals(emptyList<String>(), CallText.parameters("fun f()"))
        assertEquals(listOf("first: Int"), CallText.parameters("fun f(first: Int,)").orEmpty())
        assertNull(CallText.parameters("class Foo"))
    }

    @Test
    fun `the active parameter counts top-level commas before the caret`() {
        val text = "foo(1, bar(2), "
        val open = CallText.openParen(text, text.length)
        assertEquals(2, CallText.activeIndex(text, requireNotNull(open), text.length))
        val generic = "foo(Map<String, Int>, b"
        assertEquals(1, CallText.activeIndex(generic, generic.indexOf('('), generic.length))
        assertNull(CallText.activeIndex("foo(1)", 3, 6))
        assertNull(CallText.activeIndex("foo()", 3, 3))
        assertNull(CallText.activeIndex("foo()", 3, 100))
    }

    @Test
    fun `a paren inside a string is not a call`() {
        val text = "val x = \"(\""
        assertNull(CallText.openParen(text, text.length))
        assertTrue(CallText.parameters("fun f(x: String = \"(\")").orEmpty().isNotEmpty())
    }
}
