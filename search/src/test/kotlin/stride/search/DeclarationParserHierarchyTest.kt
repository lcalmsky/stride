package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationParserHierarchyTest {
    private fun kotlin(text: String) = DeclarationParser.parse("Sample.kt", text.trimIndent())

    private fun java(text: String) = DeclarationParser.parse("Sample.java", text.trimIndent())

    @Test
    fun `column points at the name inside its line`() {
        val declarations = kotlin(
            """
            class Alpha
                fun beta() {}
            fun `quoted name`() {}
            """.trimIndent()
        )
        assertEquals(6, declarations.single { it.name == "Alpha" }.column)
        assertEquals(8, declarations.single { it.name == "beta" }.column)
        assertEquals(5, declarations.single { it.name == "quoted name" }.column)
    }

    @Test
    fun `kotlin supertypes drop type arguments, constructor arguments, annotations and delegates`() {
        val declarations = kotlin(
            """
            class A(x: Int) : b.Base<T>(x), Outer.Iface by d
            object O : Base()
            companion object Named : Factory<X>
            enum class E : I
            sealed interface S : T
            fun interface F : G
            data class D(
                val x: Int,
            ) : B, C by c {
                fun member() {}
            }
            """.trimIndent()
        )
        assertEquals(listOf("b.Base", "Outer.Iface"), declarations.single { it.name == "A" }.supertypes)
        assertEquals(listOf("Base"), declarations.single { it.name == "O" }.supertypes)
        assertEquals(listOf("Factory"), declarations.single { it.name == "Named" }.supertypes)
        assertEquals(listOf("I"), declarations.single { it.name == "E" }.supertypes)
        assertEquals(listOf("T"), declarations.single { it.name == "S" }.supertypes)
        assertEquals(listOf("G"), declarations.single { it.name == "F" }.supertypes)
        assertEquals(listOf("B", "C"), declarations.single { it.name == "D" }.supertypes)
    }

    @Test
    fun `kotlin type parameter bounds and constructor parameters are not supertypes`() {
        val declarations = kotlin(
            """
            class A<T : Comparable<T>>(x: T) : B()
            class C(val x: Foo) : Bar
            typealias Handler = (String) -> Unit
            """.trimIndent()
        )
        assertEquals(listOf("B"), declarations.single { it.name == "A" }.supertypes)
        assertEquals(listOf("Bar"), declarations.single { it.name == "C" }.supertypes)
        assertTrue(declarations.single { it.name == "Handler" }.supertypes.isEmpty())
    }

    @Test
    fun `a kotlin where clause ends the supertype list`() {
        val declarations = kotlin(
            """
            class A<T> : B where T : Comparable<T> {
            }
            class C<T> where T : Comparable<T> {
            }
            class D<T, U> : B where T : X, U : Y {
            }
            class E<T> : B
                where T : Comparable<T> {
            }
            class F<T> where T : Comparable<T>
            """.trimIndent()
        )
        assertEquals(listOf("B"), declarations.single { it.name == "A" }.supertypes)
        assertTrue(declarations.single { it.name == "C" }.supertypes.isEmpty())
        assertEquals(listOf("B"), declarations.single { it.name == "D" }.supertypes)
        assertEquals(listOf("B"), declarations.single { it.name == "E" }.supertypes)
        assertTrue(declarations.single { it.name == "F" }.supertypes.isEmpty())
    }

    @Test
    fun `kotlin supertype annotations and nullability are stripped`() {
        val declarations = kotlin("class A : @Ann(1) Base<T>?, Other.Thing?")
        assertEquals(listOf("Base", "Other.Thing"), declarations.single { it.name == "A" }.supertypes)
    }

    @Test
    fun `an unnamed companion object is not a declaration`() {
        val declarations = kotlin(
            """
            class Outer {
                companion object : Factory<X>
            }
            """.trimIndent()
        )
        assertEquals(listOf("Outer"), declarations.map { it.name })
    }

    @Test
    fun `kotlin interfaces and overrides are flagged`() {
        val declarations = kotlin(
            """
            interface I
            sealed interface S
            fun interface F
            annotation class Marker
            object O
            class C : I {
                override fun run() {}
                fun plain() {}
                override val value = 1
            }
            """.trimIndent()
        )
        assertTrue(declarations.single { it.name == "I" }.isInterface)
        assertTrue(declarations.single { it.name == "S" }.isInterface)
        assertTrue(declarations.single { it.name == "F" }.isInterface)
        assertFalse(declarations.single { it.name == "Marker" }.isInterface)
        assertFalse(declarations.single { it.name == "O" }.isInterface)
        assertTrue(declarations.single { it.name == "run" }.isOverride)
        assertFalse(declarations.single { it.name == "plain" }.isOverride)
        assertTrue(declarations.single { it.name == "value" }.isOverride)
    }

    @Test
    fun `java extends and implements lists are supertypes`() {
        val declarations = java(
            """
            public class Sample<T extends Number> extends Base<Sample<T>> implements Runnable, AutoCloseable {
            }
            interface Named extends A, B {
            }
            enum Mode implements Runnable {
            }
            record Point(int x, int y) implements Comparable<Point> {
            }
            sealed class Sealed extends Base permits Sealed.A, Sealed.B {
            }
            """.trimIndent()
        )
        assertEquals(listOf("Base", "Runnable", "AutoCloseable"), declarations.single { it.name == "Sample" }.supertypes)
        assertEquals(listOf("A", "B"), declarations.single { it.name == "Named" }.supertypes)
        assertEquals(listOf("Runnable"), declarations.single { it.name == "Mode" }.supertypes)
        assertEquals(listOf("Comparable"), declarations.single { it.name == "Point" }.supertypes)
        assertEquals(listOf("Base"), declarations.single { it.name == "Sealed" }.supertypes)
        assertTrue(declarations.single { it.name == "Named" }.isInterface)
    }

    @Test
    fun `java headers that span several lines are read`() {
        val declarations = java(
            """
            public class Sample
                    extends Base
                    implements Runnable,
                            AutoCloseable
            {
                public void render(
                    String id
                ) {
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("Base", "Runnable", "AutoCloseable"), declarations.single { it.name == "Sample" }.supertypes)
    }

    @Test
    fun `java overrides are flagged from the same line and annotation lines above`() {
        val declarations = java(
            """
            class Sample implements Runnable {
                @Override
                public void run() {}

                @Deprecated
                @Override
                public void old() {}

                @Override public void inline() {}

                public void plain() {}

                @Override
                public void multi(
                    String id
                ) {}
            }
            """.trimIndent()
        )
        assertTrue(declarations.single { it.name == "run" }.isOverride)
        assertTrue(declarations.single { it.name == "old" }.isOverride)
        assertTrue(declarations.single { it.name == "inline" }.isOverride)
        assertFalse(declarations.single { it.name == "plain" }.isOverride)
        assertTrue(declarations.single { it.name == "multi" }.isOverride)
    }

    @Test
    fun `kotlin imports are read until the first declaration`() {
        val imports = DeclarationParser.imports(
            "Sample.kt",
            """
            package demo

            import a.b.C
            import a.b.*
            import a.b.C as D
            import `weird`.pkg.`Name`
            // import ignored.Comment
            class Alpha
            import a.b.Late
            """.trimIndent()
        )
        assertEquals(
            listOf(
                SourceImport("a.b.C"),
                SourceImport("a.b", isStar = true),
                SourceImport("a.b.C", alias = "D"),
                SourceImport("weird.pkg.Name"),
            ),
            imports,
        )
    }

    @Test
    fun `java imports skip static and stop at the first type`() {
        val imports = DeclarationParser.imports(
            "Sample.java",
            """
            package demo;

            import static a.b.Util.helper;
            import a.b.C;
            import a.b.*;
            import a.b.D;

            public class Sample {
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                SourceImport("a.b.C"),
                SourceImport("a.b", isStar = true),
                SourceImport("a.b.D"),
            ),
            imports,
        )
    }

    @Test
    fun `comments and strings do not produce imports`() {
        val imports = DeclarationParser.imports(
            "Sample.kt",
            """
            // import a.b.Hidden
            /* import a.b.Blocked */
            import a.b.Visible
            val text = "import a.b.InString"
            """.trimIndent()
        )
        assertEquals(listOf(SourceImport("a.b.Visible")), imports)
    }

    @Test
    fun `non source files have no imports`() {
        assertTrue(DeclarationParser.imports("notes.md", "import a.b.C").isEmpty())
    }
}
