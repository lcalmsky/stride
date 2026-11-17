package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationParserOutlineTest {
    private val kotlinText = """
        package demo

        class Outer {
            val count = 1
            fun run() {
                val local = 2
            }
            init {
                println()
            }
            abstract class Inner {
                fun deep() {}
            }
        }
        fun topLevel() = 1
    """.trimIndent()

    @Test
    fun `outline lists classes, functions, properties, initializers and their ranges`() {
        val members = DeclarationParser.outline("Sample.kt", kotlinText)
        assertEquals(
            listOf("Outer", "count", "run", "init", "Inner", "deep", "topLevel"),
            members.map { it.name },
        )
        val outer = members.single { it.name == "Outer" }
        val count = members.single { it.name == "count" }
        val run = members.single { it.name == "run" }
        val init = members.single { it.name == "init" }
        val inner = members.single { it.name == "Inner" }
        val deep = members.single { it.name == "deep" }
        val topLevel = members.single { it.name == "topLevel" }

        assertEquals(OutlineKind.CLASS, outer.kind)
        assertEquals(OutlineKind.PROPERTY, count.kind)
        assertEquals(OutlineKind.FUNCTION, run.kind)
        assertEquals(OutlineKind.INITIALIZER, init.kind)
        assertEquals(OutlineKind.CLASS, inner.kind)
        assertEquals(OutlineKind.FUNCTION, deep.kind)
        assertEquals(OutlineKind.FUNCTION, topLevel.kind)

        assertEquals("Outer", count.container)
        assertEquals("Outer", run.container)
        assertEquals("Inner", deep.container)
        assertEquals(null, topLevel.container)

        assertEquals(kotlinText.indexOf("Outer"), outer.nameStart)
        assertEquals(kotlinText.indexOf("Outer") + "Outer".length, outer.nameEnd)
        assertEquals(kotlinText.indexOf("count"), count.nameStart)
        assertEquals(2, outer.line)
        assertEquals(3, count.line)

        assertEquals(lineStartOf(kotlinText, "fun run"), count.end, "the next member cuts the property range")
        assertTrue(run.end > kotlinText.indexOf("val local"))
        assertEquals(lineStartOf(kotlinText, "init {") - 1, run.end)
        assertEquals(lineStartOf(kotlinText, "abstract class Inner") - 1, init.end)
        assertEquals(10, inner.line)
        assertEquals(kotlinText.indexOf("fun topLevel"), topLevel.start)
        assertTrue(deep.end < inner.end)
    }

    @Test
    fun `memberAt picks the innermost member and classesAt lists classes inside out`() {
        val members = DeclarationParser.outline("Sample.kt", kotlinText)
        val deepOffset = kotlinText.indexOf("deep") + 1
        assertEquals("deep", members.memberAt(deepOffset)?.name)
        assertEquals(listOf("Inner", "Outer"), members.classesAt(deepOffset).map { it.name })

        val localOffset = kotlinText.indexOf("val local")
        assertEquals("run", members.memberAt(localOffset)?.name)
        assertEquals(listOf("Outer"), members.classesAt(localOffset).map { it.name })
    }

    @Test
    fun `an expression body function ends where the next member starts`() {
        val text = """
            class Sample {
                fun pick(flag: Boolean): Int =
                    if (flag) 1 else 2
                fun next() {}
            }
        """.trimIndent()
        val members = DeclarationParser.outline("Sample.kt", text)
        val pick = members.single { it.name == "pick" }
        val next = members.single { it.name == "next" }
        assertEquals(lineStartOf(text, "fun next"), pick.end)
        assertEquals(3, next.line)
    }

    @Test
    fun `top level declarations are outlined`() {
        val text = """
            val top = 1
            fun first() = 2
        """.trimIndent()
        val members = DeclarationParser.outline("Sample.kt", text)
        assertEquals(listOf("top", "first"), members.map { it.name })
        assertEquals(0, members[0].start)
        assertEquals(text.indexOf("fun first"), members[0].end)
    }

    @Test
    fun `java classes, fields, methods, constructors and initializer blocks are outlined`() {
        val text = """
            public class Sample {
                private int count = 1;
                public Sample(int x) {
                }
                public int add(int a) {
                    return a;
                }
                static {
                    setup();
                }
            }
        """.trimIndent()
        val members = DeclarationParser.outline("Sample.java", text)
        assertEquals(listOf("Sample", "count", "Sample", "add", "static"), members.map { it.name })
        assertEquals(OutlineKind.CLASS, members[0].kind)
        assertEquals(OutlineKind.PROPERTY, members[1].kind)
        assertEquals(OutlineKind.CONSTRUCTOR, members[2].kind)
        assertEquals(OutlineKind.FUNCTION, members[3].kind)
        assertEquals(OutlineKind.INITIALIZER, members[4].kind)
        assertEquals("Sample", members[2].container)
        assertEquals("Sample", members[3].container)
        assertTrue(members[3].end > text.indexOf("return a"))
        assertEquals(lineStartOf(text, "static {") - 1, members[3].end)
    }

    private fun lineStartOf(text: String, token: String): Int {
        val index = text.indexOf(token)
        require(index >= 0) { "missing $token" }
        return text.lastIndexOf('\n', index) + 1
    }

    @Test
    fun `members of a class whose body opens on a later line are outlined`() {
        val kotlinText = """
            class A
                : Base
            {
                fun f() {}
            }
        """.trimIndent()
        val kotlinMembers = DeclarationParser.outline("Sample.kt", kotlinText)
        assertEquals(listOf("A", "f"), kotlinMembers.map { it.name })
        assertEquals("f", kotlinMembers.memberAt(kotlinText.indexOf("f("))?.name)

        val javaText = """
            public class Sample
                    extends Base
            {
                public void run() {}
            }
        """.trimIndent()
        val javaMembers = DeclarationParser.outline("Sample.java", javaText)
        assertEquals(listOf("Sample", "run"), javaMembers.map { it.name })
        assertEquals("run", javaMembers.memberAt(javaText.indexOf("run()"))?.name)
    }

    @Test
    fun `members written on the class line are outlined`() {
        val text = "class A { fun f() {} }"
        val members = DeclarationParser.outline("Sample.kt", text)
        assertEquals(listOf("A", "f"), members.map { it.name })
        assertEquals("f", members.memberAt(text.indexOf("f("))?.name)
        assertEquals(listOf("A"), members.classesAt(text.indexOf("f(")).map { it.name })

        val java = "public class Sample { public void run() {} }"
        val javaMembers = DeclarationParser.outline("Sample.java", java)
        assertEquals(listOf("Sample", "run"), javaMembers.map { it.name })
        assertEquals("run", javaMembers.memberAt(java.indexOf("run()"))?.name)
    }

    @Test
    fun `a second declaration after a closing brace is outlined`() {
        val text = "fun a() {}\nfun b() {} fun c() {}"
        val members = DeclarationParser.outline("Sample.kt", text)
        assertEquals(listOf("a", "b", "c"), members.map { it.name })
        assertEquals("c", members.memberAt(text.indexOf("c("))?.name)
    }

    @Test
    fun `no local declarations are listed`() {
        val text = """
            fun build() {
                class Local
                fun helper() {}
                val local = 1
            }
        """.trimIndent()
        assertEquals(listOf("build"), DeclarationParser.outline("Sample.kt", text).map { it.name })
    }

    @Test
    fun `extension receivers are recorded as written`() {
        val text = """
            fun Config.reload() {}
            fun <T> Box<T>.open(): T = TODO()
            fun a.b.Config.reloadFrom() {}
            fun Config?.orEmpty() {}
            val Config.size: Int get() = 1
            fun reloadPlain() {}
            fun (Int) -> Unit.applyEach() {}
        """.trimIndent()
        val members = DeclarationParser.outline("Sample.kt", text)
        assertEquals("Config", members.single { it.name == "reload" }.receiverType)
        assertEquals("Box", members.single { it.name == "open" }.receiverType)
        assertEquals("a.b.Config", members.single { it.name == "reloadFrom" }.receiverType)
        assertEquals("Config", members.single { it.name == "orEmpty" }.receiverType)
        assertEquals("Config", members.single { it.name == "size" }.receiverType)
        assertNull(members.single { it.name == "reloadPlain" }.receiverType)
        assertNull(members.single { it.name == "applyEach" }.receiverType)
    }

    @Test
    fun `java members have no extension receiver`() {
        val members = DeclarationParser.outline("Sample.java", "public class Sample { public void run() {} }")
        assertNull(members.single { it.name == "run" }.receiverType)
    }

    @Test
    fun `extensionReceiverAt takes the innermost extension around the offset`() {
        val text = """
            fun Config.reload() {
                fun local() {
                    load()
                }
            }
            fun plain() {
                load()
            }
        """.trimIndent()
        val members = DeclarationParser.outline("Sample.kt", text)
        assertEquals("Config", members.extensionReceiverAt(text.indexOf("load()")))
        assertNull(members.extensionReceiverAt(text.lastIndexOf("load()")))
    }

    @Test
    fun `other file types have no outline`() {
        assertTrue(DeclarationParser.outline("notes.md", "class NotASource").isEmpty())
    }

    @Test
    fun `parse and outline of a large file finish within a second`() {
        val builder = StringBuilder()
        repeat(2_000) { index ->
            builder.append("class C").append(index).append(" {\n")
            builder.append("    val value").append(index).append(" = ").append(index).append('\n')
            builder.append("    fun run() {\n")
            builder.append("        repeat(1) { println(value").append(index).append(") }\n")
            builder.append("    }\n")
            builder.append("}\n")
        }
        repeat(2_000) { index -> builder.append("fun top").append(index).append("() = ").append(index).append('\n') }
        val text = builder.toString()
        val started = System.nanoTime()
        val declarations = DeclarationParser.parse("Large.kt", text)
        val outline = DeclarationParser.outline("Large.kt", text)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(declarations.size >= 6_000)
        assertTrue(outline.size >= 6_000)
        assertTrue(elapsedMillis < 1_000, "parse and outline took ${elapsedMillis}ms")
    }
}
