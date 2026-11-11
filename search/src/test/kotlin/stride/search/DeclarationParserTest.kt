package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationParserTest {
    private fun parseKotlin(text: String) = DeclarationParser.parse("Sample.kt", text)

    private fun parseJava(text: String) = DeclarationParser.parse("Sample.java", text)

    @Test
    fun `kotlin classes, objects, typealiases and modifiers`() {
        val declarations = parseKotlin(
            """
            package demo

            typealias Handler = (String) -> Unit

            sealed interface Shape
            data class Circle(val radius: Double) : Shape
            enum class Color { RED, GREEN }
            annotation class Marker
            object Registry {
                companion object Named {
                    val shared = 1
                }
                fun register() {}
            }
            """.trimIndent()
        )

        val classes = declarations.filter { it.kind == DeclarationKind.CLASS }
        assertEquals(
            listOf("Handler", "Shape", "Circle", "Color", "Marker", "Registry", "Named"),
            classes.map { it.name }
        )
        assertTrue(classes.none { it.name == "radius" }, "a constructor property is not a class")
        assertEquals("Registry", classes.single { it.name == "Named" }.container)
        val shared = declarations.single { it.name == "shared" }
        assertEquals(DeclarationKind.SYMBOL, shared.kind)
        assertEquals("Named", shared.container)
        assertEquals("Registry", declarations.single { it.name == "register" }.container)
    }

    @Test
    fun `kotlin nested classes and members keep the enclosing class`() {
        val declarations = parseKotlin(
            """
            class Outer {
                class Inner
                val count = 0
                fun run() {
                    val local = 1
                    fun nested() {}
                }
            }
            """.trimIndent()
        )

        assertEquals("Outer", declarations.single { it.name == "Inner" }.container)
        assertEquals("Outer", declarations.single { it.name == "count" }.container)
        assertEquals("Outer", declarations.single { it.name == "run" }.container)
        assertFalse(declarations.any { it.name == "local" }, "locals inside a function body are skipped")
        assertFalse(declarations.any { it.name == "nested" }, "local functions are skipped")
    }

    @Test
    fun `kotlin extension functions take the last segment and top level properties are symbols`() {
        val declarations = parseKotlin(
            """
            fun String.shout(): String = this
            fun <T> List<T>.secondOrNull(): T? = getOrNull(1)
            val topLevel = 1
            var mutableTop = 2
            """.trimIndent()
        )

        val symbols = declarations.filter { it.kind == DeclarationKind.SYMBOL }.map { it.name }
        assertEquals(listOf("shout", "secondOrNull", "topLevel", "mutableTop"), symbols)
        val shout = declarations.first { it.name == "shout" }
        assertEquals(0, shout.line)
        assertEquals(null, shout.container)
    }

    @Test
    fun `zero based lines count from the first line of the file`() {
        val declarations = parseKotlin("package demo\n\nclass Alpha\n\nfun beta() {}\n")

        assertEquals(2, declarations.single { it.name == "Alpha" }.line)
        assertEquals(4, declarations.single { it.name == "beta" }.line)
    }

    @Test
    fun `comments and string literals do not produce declarations`() {
        val declarations = parseKotlin(
            """
            // class Commented
            /* fun blocked() {} */
            val text = "class InString"
            val raw = ""${'"'}
            class RawString
            ""${'"'}
            """.trimIndent()
        )

        assertEquals(listOf("text", "raw"), declarations.map { it.name })
    }

    @Test
    fun `java classes, interfaces, enums and records with methods and fields`() {
        val declarations = parseJava(
            """
            package demo;

            public class Sample {
                private static final int MAX = 10;
                private int count;

                public int add(int a, int b) {
                    int sum = a + b;
                    return sum;
                }
            }

            interface Named {
                String name();
            }

            enum Color { RED, GREEN }

            record Point(int x, int y) {
                public int sum() { return x + y; }
            }
            """.trimIndent()
        )

        val classes = declarations.filter { it.kind == DeclarationKind.CLASS }.map { it.name }
        assertEquals(listOf("Sample", "Named", "Color", "Point"), classes)

        val sampleSymbols = declarations.filter { it.container == "Sample" }.map { it.name }
        assertEquals(listOf("MAX", "count", "add"), sampleSymbols)
        assertEquals("name", declarations.single { it.container == "Named" }.name)
        assertEquals("sum", declarations.single { it.container == "Point" }.name)
        assertFalse(declarations.any { it.name == "sum" && it.container == null }, "java locals are skipped")
        assertFalse(declarations.any { it.name == "return" }, "keywords are not symbols")
    }

    @Test
    fun `unsupported extensions parse to nothing`() {
        assertTrue(DeclarationParser.parse("notes.md", "class NotASource").isEmpty())
        assertFalse(DeclarationParser.isSourceFile("notes.md"))
        assertTrue(DeclarationParser.isSourceFile("Main.kt"))
        assertTrue(DeclarationParser.isSourceFile("build.gradle.kts"))
        assertTrue(DeclarationParser.isSourceFile("Main.java"))
    }
}
