package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallSitesTest {
    private val kotlinText = """
        fun greet() {}
        fun caller() {
            greet()
            this.greet()
            super.greet()
            user.greet()
            service?.greet()
            items.forEach { it.greet() }
            a.b().greet()
            a.b.greet()
            generic<Int>()
            greet { 1 }
            ::greet
            Foo::greet
            val text = "greet()"
            // greet()
            /* greet() */
            if (ready) greet()
            when (kind) { 1 -> greet() }
        }
        class Holder {
            fun greet() {}
        }
    """.trimIndent()

    @Test
    fun `finds unqualified, qualified and reference call sites`() {
        val calls = CallSites.find("Sample.kt", kotlinText, "greet")
        assertEquals(
            listOf(null, "this", "super", "user", "service", "it", "?", "?", null, null, null),
            calls.filter { !it.isReference }.map { it.receiver },
        )
        val references = calls.filter { it.isReference }
        assertEquals(listOf(null, "Foo"), references.map { it.receiver })
        assertEquals(2, references.size)
    }

    @Test
    fun `declaration names, keywords, strings and comments are not calls`() {
        val calls = CallSites.find("Sample.kt", kotlinText, "greet")
        assertEquals(2, calls.count { it.isReference })
        assertEquals(13, calls.size, "declarations inside fun and class, strings and comments are excluded")
        val text = """
            if (x) y()
            when (x) { 1 -> y() }
            for (x in y()) {}
            try { y() } catch (e: Exception) { y() }
            fun y() {}
            class Y
        """.trimIndent()
        assertEquals(5, CallSites.find("Sample.kt", text, "y").size)
    }

    @Test
    fun `generic calls and trailing lambdas are calls`() {
        val text = """
            fun run() {
                generic<Int>()
                configured<Map<String, Int>>()
                list.map { it }
            }
        """.trimIndent()
        val names = CallSites.inRange("Sample.kt", text, 0, text.length).map { it.name }
        assertTrue(names.containsAll(listOf("generic", "configured", "map")))
    }

    @Test
    fun `inRange keeps only calls inside the range`() {
        val text = """
            fun run() {
                first()
                second()
                third()
            }
        """.trimIndent()
        val firstStart = text.indexOf("first()")
        val secondStart = text.indexOf("second()")
        val thirdStart = text.indexOf("third()")
        assertEquals(
            listOf("second"),
            CallSites.inRange("Sample.kt", text, secondStart, thirdStart).map { it.name },
        )
        assertEquals(
            listOf("first", "second"),
            CallSites.inRange("Sample.kt", text, firstStart, thirdStart).map { it.name },
        )
        assertTrue(CallSites.inRange("Sample.kt", text, thirdStart + 1, text.length).isEmpty())
    }

    @Test
    fun `java calls and declarations are told apart`() {
        val text = """
            public class Sample {
                public void caller() {
                    run();
                    this.run();
                    other.run();
                    new Sample().run();
                }
                public void run() {
                }
                public Sample() {
                }
            }
        """.trimIndent()
        val calls = CallSites.find("Sample.java", text, "run")
        assertEquals(listOf(null, "this", "other", "?"), calls.map { it.receiver })
        assertEquals(4, calls.size)
        assertTrue(CallSites.find("Sample.java", text, "Sample").isEmpty(), "new and constructors are not calls")
    }

    @Test
    fun `property accessor declarations are not calls`() {
        val text = """
            class A {
                var x: Int = 0
                    get() = field
                    set(value) { field = value }
                val y: Int
                    private get() = 1
            }
        """.trimIndent()
        assertTrue(CallSites.find("Sample.kt", text, "get").isEmpty())
        assertTrue(CallSites.find("Sample.kt", text, "set").isEmpty())
        assertTrue(CallSites.find("Sample.kt", text, "field").isEmpty(), "field is not called either")
    }

    @Test
    fun `a class header delegate expression is not a trailing lambda call`() {
        val text = """
            class A : Iface by impl {
            }
            class B :
                Other by otherImpl {
            }
            fun run() {
                val x by lazy { 1 }
            }
        """.trimIndent()
        assertTrue(CallSites.find("Sample.kt", text, "impl").isEmpty())
        assertTrue(CallSites.find("Sample.kt", text, "otherImpl").isEmpty())
        assertEquals(1, CallSites.find("Sample.kt", text, "lazy").size)
    }

    @Test
    fun `java method and constructor heads are not calls`() {
        val split = """
            public class Sample
                    extends Base
            {
                public void run() {}
            }
        """.trimIndent()
        assertTrue(CallSites.find("Sample.java", split, "run").isEmpty())

        val expressions = """
            class Sample {
                void caller() {
                    String s = run();
                    other.run();
                    return run();
                }
            }
        """.trimIndent()
        assertEquals(listOf(null, "other", null), CallSites.find("Sample.java", expressions, "run").map { it.receiver })
    }

    @Test
    fun `modifier words do not hide a call of the same name`() {
        val text = """
            fun run(door: Door, x: Holder) {
                door.open()
                value(1)
                x.data()
            }
            open class A
            data class B(val x: Int)
            inline fun f() {}
            companion object {
            }
            enum class E {
            }
        """.trimIndent()
        assertEquals(1, CallSites.find("Sample.kt", text, "open").size)
        assertEquals(1, CallSites.find("Sample.kt", text, "value").size)
        assertEquals(1, CallSites.find("Sample.kt", text, "data").size)
        assertEquals(listOf("door"), CallSites.find("Sample.kt", text, "open").map { it.receiver })
        assertEquals(listOf("x"), CallSites.find("Sample.kt", text, "data").map { it.receiver })
        assertTrue(CallSites.find("Sample.kt", text, "inline").isEmpty())
        assertTrue(CallSites.find("Sample.kt", text, "companion").isEmpty())
        assertTrue(CallSites.find("Sample.kt", text, "enum").isEmpty())

        val javaText = """
            class Sample {
                static {
                    init();
                }
            }
        """.trimIndent()
        assertTrue(CallSites.find("Sample.java", javaText, "static").isEmpty())
    }

    @Test
    fun `a call inside a lambda literal is marked`() {
        fun lambdas(text: String) = CallSites.find("Sample.kt", text, "load")

        for (text in listOf(
            "with(config) { load() }",
            "config.apply { load() }",
            "run { load() }",
            "foo(1, { load() })",
            "val f = { load() }",
            "with(c) { if (x) { load() } }",
            "fun f() = run { load() }",
        )) {
            assertTrue(lambdas(text).single().inLambda, "expected a lambda: $text")
        }

        for (text in listOf(
            "fun f() { load() }",
            "fun f(): Int { return load() }",
            "if (x) { load() }",
            "class A : Base() { fun f() { load() } }",
            "init { load() }",
            "try { load() } finally { }",
            "when (x) { 1 -> { load() } }",
            "object : Base() { fun f() { load() } }",
        )) {
            assertFalse(lambdas(text).single().inLambda, "expected no lambda: $text")
        }

        val java = "class S { void f() { items.forEach(r -> { load(); }); } }"
        assertFalse(CallSites.find("Sample.java", java, "load").single().inLambda)

        val ranged = "with(c) {\n    load()\n}"
        val start = ranged.indexOf("{\n") + 2
        assertTrue(CallSites.inRange("Sample.kt", ranged, start, ranged.length).single().inLambda)
    }

    @Test
    fun `type arguments, return and receiver accessor heads open lambdas`() {
        fun lambdas(text: String) = CallSites.find("Sample.kt", text, "load")

        for (text in listOf(
            "configure<Settings> { load() }",
            "x.configure<A, B> { load() }",
            "fun f(): () -> Unit { return { load() } }",
            "fun f() { return { load() } }",
            "val v: Value = cache.get(key) { load() }",
            "val v: Value = cache?.get(key) { load() }",
        )) {
            assertTrue(lambdas(text).single().inLambda, "expected a lambda: $text")
        }

        for (text in listOf(
            "class A<T> { fun f() { load() } }",
            "fun <T> f() { load() }",
            "interface I<T> { fun f() { load() } }",
            "val x: Int get() { return load() }",
            "var y: Int = 0\n    set(value) { load() }",
            "fun f(): List<T> { load() }",
            "when (x) { is List<T> -> { load() } }",
        )) {
            assertFalse(lambdas(text).single().inLambda, "expected no lambda: $text")
        }

        val returned = "fun f(): () -> Unit {\n    prepare()\n    return { load() }\n}"
        assertFalse(CallSites.find("Sample.kt", returned, "prepare").single().inLambda)
    }

    @Test
    fun `a return type name is not a call and opens no lambda`() {
        fun lambdas(text: String) = CallSites.find("Sample.kt", text, "load")

        for ((text, type) in listOf(
            "fun f(): Int { load() }" to "Int",
            "fun f(): Result<T> { load() }" to "Result",
            "fun f(): () -> Unit { load() }" to "Unit",
            "fun f(): (Int) -> Result { load() }" to "Result",
            "fun f(): kotlin.Unit { load() }" to "Unit",
            "fun f(): List<() -> Unit> { load() }" to "List",
        )) {
            assertTrue(CallSites.find("Sample.kt", text, type).isEmpty(), "expected no call: $text")
            assertFalse(lambdas(text).single().inLambda, "expected no lambda: $text")
        }
    }

    @Test
    fun `an elvis or lambda arrow does not put a call in a type position`() {
        fun lambdas(text: String) = CallSites.find("Sample.kt", text, "load")

        for ((text, call) in listOf(
            "val y = x ?: run { load() }" to "run",
            "val y = x ?: configure<A> { load() }" to "configure",
            "list.map { x -> build { load() } }" to "build",
            "when (x) {\n    1 -> run { load() }\n}" to "run",
        )) {
            assertEquals(1, CallSites.find("Sample.kt", text, call).size, "expected a call: $text")
            assertTrue(lambdas(text).single().inLambda, "expected a lambda: $text")
        }

        assertTrue(lambdas("val f: () -> Unit = { load() }").single().inLambda)
    }

    @Test
    fun `other file types have no calls`() {
        assertTrue(CallSites.find("notes.md", "foo()", "foo").isEmpty())
    }
}
