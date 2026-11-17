package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeReferencesTest {
    private val reason = "Cannot infer the type of %s without a compiler; declare it explicitly"

    @Test
    fun `local properties are found nearest first`() {
        val text = """
            fun run() {
                val value = 1
                println(value)
            }
        """.trimIndent()
        val declaration = text.indexOf("value = 1")
        val usage = text.lastIndexOf("value")
        assertEquals(declaration, TypeReferences.localDeclaration("Sample.kt", text, usage, "value"))
    }

    @Test
    fun `the nearest declaration shadows an outer one`() {
        val text = """
            fun run() {
                val x = 1
                run {
                    val x = 2
                    println(x)
                }
                println(x)
            }
        """.trimIndent()
        val firstUsage = text.indexOf("println(x)")
        val secondUsage = text.lastIndexOf("println(x)") + "println(".length
        val outer = text.indexOf("x = 1")
        val inner = text.indexOf("x = 2")
        assertEquals(inner, TypeReferences.localDeclaration("Sample.kt", text, firstUsage, "x"))
        assertEquals(outer, TypeReferences.localDeclaration("Sample.kt", text, secondUsage, "x"))
    }

    @Test
    fun `a declaration in a closed block is out of scope`() {
        val text = """
            fun run() {
                if (true) {
                    val inner = 1
                }
                println(inner)
            }
        """.trimIndent()
        val usage = text.lastIndexOf("inner")
        assertNull(TypeReferences.localDeclaration("Sample.kt", text, usage, "inner"))
    }

    @Test
    fun `function parameters are declarations`() {
        val text = """
            fun greet(name: String, count: Int) {
                println(name)
                println(count)
            }
        """.trimIndent()
        assertEquals(text.indexOf("name:"), TypeReferences.localDeclaration("Sample.kt", text, text.indexOf("name)"), "name"))
        assertEquals(text.indexOf("count:"), TypeReferences.localDeclaration("Sample.kt", text, text.indexOf("count)"), "count"))
    }

    @Test
    fun `lambda parameters are declarations, typed or not`() {
        val text = """
            fun run() {
                list.forEach { item -> println(item) }
                list.forEach { item: String -> println(item) }
                list.forEach { a, item -> println(item) }
            }
        """.trimIndent()
        val usages = Regex("""item\)""").findAll(text).map { it.range.first }.toList()
        assertEquals(3, usages.size)
        for (usage in usages) {
            val found = TypeReferences.localDeclaration("Sample.kt", text, usage, "item")
            assertTrue(
                found != null && found < usage && text.getOrNull(found + "item".length) != ')',
                "expected a declaration of item before $usage, got $found",
            )
        }
    }

    @Test
    fun `for and catch variables are declarations`() {
        val text = """
            fun run(items: List<String>) {
                for (item in items) {
                    println(item)
                }
                for ((index, item) in items.withIndex()) {
                    println(item)
                }
                try {
                } catch (failure: Exception) {
                    println(failure)
                }
            }
        """.trimIndent()
        val itemUsages = Regex("""println\(item\)""").findAll(text).map { it.range.first + "println(".length }.toList()
        for (usage in itemUsages) {
            val found = TypeReferences.localDeclaration("Sample.kt", text, usage, "item")
            assertTrue(found != null, "no declaration of item before offset $usage")
        }
        val failureUsage = text.lastIndexOf("failure")
        assertEquals(text.indexOf("failure:"), TypeReferences.localDeclaration("Sample.kt", text, failureUsage, "failure"))
    }

    @Test
    fun `java locals, parameters, for and catch variables are declarations`() {
        val text = """
            class Sample {
                int run(int value) {
                    int count = value;
                    for (String item : items) {
                        System.out.println(item);
                    }
                    try {
                    } catch (Exception failure) {
                        System.out.println(failure);
                    }
                    return count;
                }
            }
        """.trimIndent()
        assertEquals(
            text.indexOf("value)") ,
            TypeReferences.localDeclaration("Sample.java", text, text.indexOf("value;"), "value"),
        )
        assertEquals(
            text.indexOf("count ="),
            TypeReferences.localDeclaration("Sample.java", text, text.indexOf("return count"), "count"),
        )
        assertEquals(
            text.indexOf("item :"),
            TypeReferences.localDeclaration("Sample.java", text, text.indexOf("println(item)") + "println(".length, "item"),
        )
        assertEquals(
            text.indexOf("failure)"),
            TypeReferences.localDeclaration("Sample.java", text, text.lastIndexOf("failure"), "failure"),
        )
    }

    @Test
    fun `offset outside a member has no local declaration`() {
        val text = "val top = 1\nfun run() {}\n"
        assertNull(TypeReferences.localDeclaration("Sample.kt", text, 0, "top"))
    }

    @Test
    fun `kotlin declared types`() {
        val text = """
            class Sample {
                val a: Foo = make()
                val b: a.b.Foo<T>? = null
                val c = Foo()
                val d = a.b.Foo(1)
                val e = foo()
                val f = Foo().bar()
                val g: (A) -> B = { it }
                val h: suspend () -> Unit = {}
                fun i(): Foo = Foo()
                fun j() = Foo()
            }
        """.trimIndent()
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("a:")))
        assertEquals(DeclaredType.Explicit("a.b.Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("b:")))
        assertEquals(DeclaredType.ConstructorCall("Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("c =")))
        assertEquals(DeclaredType.ConstructorCall("a.b.Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("d =")))
        assertEquals(DeclaredType.Unknown(reason.format("e")), TypeReferences.declaredType("Sample.kt", text, text.indexOf("e =")))
        assertEquals(DeclaredType.Unknown(reason.format("f")), TypeReferences.declaredType("Sample.kt", text, text.indexOf("f =")))
        assertEquals(DeclaredType.Unknown(reason.format("g")), TypeReferences.declaredType("Sample.kt", text, text.indexOf("g:")))
        assertEquals(DeclaredType.Unknown(reason.format("h")), TypeReferences.declaredType("Sample.kt", text, text.indexOf("h:")))
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("i(")))
        assertEquals(DeclaredType.Unknown(reason.format("j")), TypeReferences.declaredType("Sample.kt", text, text.indexOf("j(")))

    }

    @Test
    fun `kotlin delegates and accessors do not join the written type`() {
        val text = """
            fun run() {
                val a: Foo by lazy { 1 }
                val b: Foo get() = field
                var count: Int get() = field
                val c: Int by remember { mutableStateOf(0) }
                val f: (Int) -> Unit = {}
            }
        """.trimIndent()
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("a:")))
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("b:")))
        assertEquals(DeclaredType.Explicit("Int"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("count:")))
        assertEquals(DeclaredType.Explicit("Int"), TypeReferences.declaredType("Sample.kt", text, text.indexOf("c:")))
        assertEquals(
            DeclaredType.Unknown(reason.format("f")),
            TypeReferences.declaredType("Sample.kt", text, text.indexOf("f:")),
        )
    }

    @Test
    fun `java multi catch variables are locals without a single type`() {
        val text = """
            class Sample {
                void run() {
                    try {
                        work();
                    } catch (java.io.IOException | RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            }
        """.trimIndent()
        val declaration = text.indexOf("RuntimeException e)") + "RuntimeException ".length
        assertEquals(declaration, TypeReferences.localDeclaration("Sample.java", text, text.indexOf("e.printStackTrace"), "e"))
        assertEquals(
            DeclaredType.Unknown(reason.format("e")),
            TypeReferences.declaredType("Sample.java", text, declaration),
        )
    }

    @Test
    fun `kotlin untyped lambda parameters and destructuring are unknown`() {
        val text = """
            fun run(pair: Pair<Int, Int>) {
                val (first, second) = pair
                list.forEach { item -> item }
            }
        """.trimIndent()
        assertEquals(
            DeclaredType.Unknown(reason.format("first")),
            TypeReferences.declaredType("Sample.kt", text, text.indexOf("first")),
        )
        assertEquals(
            DeclaredType.Unknown(reason.format("item")),
            TypeReferences.declaredType("Sample.kt", text, text.indexOf("item ->")),
        )
    }

    @Test
    fun `java declared types`() {
        val text = """
            public class Sample {
                private Foo count;
                private Map<String, Foo> map;
                public Foo add(int value) {
                    int sum = value;
                    var inferred = new Foo();
                    return null;
                }
            }
        """.trimIndent()
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.java", text, text.indexOf("count;")))
        assertEquals(DeclaredType.Explicit("Map"), TypeReferences.declaredType("Sample.java", text, text.indexOf("map;")))
        assertEquals(DeclaredType.Explicit("Foo"), TypeReferences.declaredType("Sample.java", text, text.indexOf("add(")))
        assertEquals(DeclaredType.Explicit("int"), TypeReferences.declaredType("Sample.java", text, text.indexOf("sum =")))
        assertEquals(
            DeclaredType.Unknown(reason.format("inferred")),
            TypeReferences.declaredType("Sample.java", text, text.indexOf("inferred =")),
        )
        assertEquals(DeclaredType.Explicit("int"), TypeReferences.declaredType("Sample.java", text, text.indexOf("value)")))
    }
}
