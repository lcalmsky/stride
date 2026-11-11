package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MainLocatorTest {
    @Test
    fun `finds a top-level Kotlin main and derives the file class`() {
        val text = """
            package demo

            fun main() {
                println("hi")
            }
        """.trimIndent()

        assertEquals(MainLocator.MainTarget("demo.MainKt", 2, 4), MainLocator.locate(text, "Main.kt"))
    }

    @Test
    fun `uses the file name without a package and upper-cases its first letter`() {
        val text = "fun main() = println(\"hi\")\n"

        assertEquals(MainLocator.MainTarget("RunnerKt", 0, 0), MainLocator.locate(text, "runner.kt"))
    }

    @Test
    fun `honours a file-level JvmName`() {
        val text = """
            @file:JvmName("Entry")

            package demo

            fun main() {
                println("hi")
            }
        """.trimIndent()

        assertEquals(MainLocator.MainTarget("demo.Entry", 4, 6), MainLocator.locate(text, "Main.kt"))
    }

    @Test
    fun `finds a JvmStatic main in an object`() {
        val text = """
            package demo

            object Launcher {
                @JvmStatic
                fun main(args: Array<String>) {
                    println("hi")
                }
            }
        """.trimIndent()

        assertEquals(MainLocator.MainTarget("demo.Launcher", 4, 6), MainLocator.locate(text, "Main.kt"))
    }

    @Test
    fun `finds a JvmStatic main in a companion object and names the outer class`() {
        val text = """
            package demo

            class App {
                companion object {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println("hi")
                    }
                }
            }
        """.trimIndent()

        assertEquals(MainLocator.MainTarget("demo.App", 5, 7), MainLocator.locate(text, "Main.kt"))
    }

    @Test
    fun `finds a Java main inside a top-level class`() {
        val text = """
            package demo;

            public class Hello {
                public static void main(String... args) {
                    System.out.println("hi");
                }
            }
        """.trimIndent()

        assertEquals(MainLocator.MainTarget("demo.Hello", 3, 5), MainLocator.locate(text, "Hello.java"))
    }

    @Test
    fun `ignores main inside a comment or string and a nested function`() {
        val commented = """
            // fun main() {
            /* fun main() { */
            val text = "fun main() { }"
        """.trimIndent()
        assertNull(MainLocator.locate(commented, "Main.kt"))

        val nested = """
            fun outer() {
                fun main() { }
            }
        """.trimIndent()
        assertNull(MainLocator.locate(nested, "Main.kt"))
    }

    @Test
    fun `returns null when a file has no entry point`() {
        assertNull(MainLocator.locate("package demo\n\nclass Plain\n", "Plain.kt"))
        assertNull(MainLocator.locate("", "Empty.kt"))
    }

    @Test
    fun `maps an offset to its zero-based line`() {
        val text = "one\ntwo\nthree"

        assertEquals(0, MainLocator.lineAt(text, 0))
        assertEquals(1, MainLocator.lineAt(text, 5))
        assertEquals(2, MainLocator.lineAt(text, text.length))
    }
}
