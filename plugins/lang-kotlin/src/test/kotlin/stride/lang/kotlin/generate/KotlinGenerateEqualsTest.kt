package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.GenerateKind
import stride.lang.RefactoringStep

class KotlinGenerateEqualsTest {
    @Test
    fun `equals and hashCode follow the template`() {
        val source = """
            class Order(val x: Int, val label: String?, val names: Array<String>) {
            }
        """.trimIndent()
        val result = run(GenerateKind.EQUALS_HASH_CODE, source)
        val text = result.text ?: error(result.status ?: "refused")
        assertTrue(text.contains("if (this === other) return true"), text)
        assertTrue(text.contains("if (javaClass != other?.javaClass) return false"), text)
        assertTrue(text.contains("other as Order"), text)
        assertTrue(text.contains("if (x != other.x) return false"), text)
        assertTrue(text.contains("if (!names.contentEquals(other.names)) return false"), text)
        assertTrue(text.contains("result = 31 * result + (label?.hashCode() ?: 0)"), text)
        assertTrue(text.contains("result = 31 * result + names.contentHashCode()"), text)
        assertTrue(text.contains("var result = x"), text)
    }

    @Test
    fun `one property and no super hashes without the multiplier`() {
        val source = "class A(val x: String) {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source).text ?: error("refused")
        assertTrue(text.contains("return x.hashCode()"), text)
    }

    @Test
    fun `no properties keeps equals identity and hashes the class`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A {\n}").text ?: error("refused")
        assertTrue(text.contains("return javaClass.hashCode()"), text)
        assertTrue(!text.contains("other as"), text)
    }

    @Test
    fun `int hash is the value itself`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val x: Int, val y: Int) {\n}").text ?: error("refused")
        assertTrue(text.contains("var result = x"), text)
        assertTrue(text.contains("result = 31 * result + y"), text)
    }

    @Test
    fun `short and byte hashes call hashCode`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val s: Short, val b: Byte) {\n}").text ?: error("refused")
        assertTrue(text.contains("var result = s.hashCode()"), text)
        assertTrue(text.contains("result = 31 * result + b.hashCode()"), text)
    }

    @Test
    fun `one short property hashes with hashCode`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val s: Short) {\n}").text ?: error("refused")
        assertTrue(text.contains("return s.hashCode()"), text)
    }

    @Test
    fun `a nullable short hashes with hashCode and a zero default`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val s: Short?) {\n}").text ?: error("refused")
        assertTrue(text.contains("return s?.hashCode() ?: 0"), text)
    }

    @Test
    fun `nested arrays compare and hash deeply`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val grid: Array<Array<Int>>) {\n}").text ?: error("refused")
        assertTrue(text.contains("!grid.contentDeepEquals(other.grid)"), text)
        assertTrue(text.contains("grid.contentDeepHashCode()"), text)
    }

    @Test
    fun `a generic class casts with a star`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A<T>(val x: T) {\n}").text ?: error("refused")
        assertTrue(text.contains("other as A<*>"), text)
    }

    @Test
    fun `a property named other renames the parameter`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val other: Int) {\n}").text ?: error("refused")
        assertTrue(text.contains("equals(other1: Any?)"), text)
        assertTrue(text.contains("if (this === other1) return true"), text)
        assertTrue(text.contains("if (other != other1.other) return false"), text)
    }

    @Test
    fun `a property named result renames the local`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val result: Int, val y: Int) {\n}").text ?: error("refused")
        assertTrue(text.contains("var result1 = result"), text)
        assertTrue(text.contains("return result1"), text)
    }

    @Test
    fun `backticked names print braced templates`() {
        val text = run(GenerateKind.TO_STRING, "class A(val `some name`: Int) {\n}").text ?: error("refused")
        assertTrue(text.contains("some name=\${`some name`}"), text)
    }

    @Test
    fun `toString quotes non-null strings and prints arrays`() {
        val text = run(GenerateKind.TO_STRING, "class A(val s: String, val maybe: String?, val xs: Array<Int>) {\n}").text ?: error("refused")
        assertTrue(text.contains("return \"A(s='\$s', maybe=\$maybe, xs=\${xs.contentToString()})\""), text)
    }

    @Test
    fun `toString of no properties writes empty parentheses`() {
        val text = run(GenerateKind.TO_STRING, "class A {\n}").text ?: error("refused")
        assertTrue(text.contains("return \"A()\""), text)
    }

    @Test
    fun `types not written cannot be checked`() {
        val step = prepare(GenerateKind.EQUALS_HASH_CODE, "class A(val x = 1) {\n}")
        assertTrue(step is RefactoringStep.ChooseMembers, step.toString())
        step as RefactoringStep.ChooseMembers
        assertEquals("type not written", step.members.single().disabledReason)
    }

    @Test
    fun `an existing equals refuses`() {
        val result = run(GenerateKind.EQUALS_HASH_CODE, "class A(val x: Int) {\n    override fun equals(other: Any?) = false\n}")
        assertEquals("equals() already exists in A", result.status)
    }

    @Test
    fun `an existing hashCode refuses`() {
        val result = run(GenerateKind.EQUALS_HASH_CODE, "class A {\n    override fun hashCode() = 1\n}")
        assertEquals("hashCode() already exists in A", result.status)
    }

    @Test
    fun `an existing toString refuses`() {
        val result = run(GenerateKind.TO_STRING, "class A {\n    override fun toString() = \"A\"\n}")
        assertEquals("toString() already exists in A", result.status)
    }

    @Test
    fun `equals and hashCode are refused for objects enums and value classes`() {
        assertEquals("equals() and hashCode() is not available in an object", run(GenerateKind.EQUALS_HASH_CODE, "object A {\n}").status)
        assertEquals(
            "equals() and hashCode() is not available in an enum class",
            run(GenerateKind.EQUALS_HASH_CODE, "enum class A {\n}").status,
        )
        assertEquals(
            "equals() and hashCode() is not available in a value class",
            run(GenerateKind.EQUALS_HASH_CODE, "value class A(val x: Int) {\n}").status,
        )
    }

    @Test
    fun `toString is refused for interfaces and annotations`() {
        assertEquals("toString() is not available in an interface", run(GenerateKind.TO_STRING, "interface A {\n}").status)
        assertEquals("toString() is not available in an annotation class", run(GenerateKind.TO_STRING, "annotation class A").status)
    }

    @Test
    fun `a super equals adds the super check and starts the hash with super`() {
        val base = "open class Base {\n    override fun equals(other: Any?): Boolean = false\n    override fun hashCode(): Int = 1\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val result = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base)))
        val text = result.text ?: error(result.status ?: "refused")
        assertTrue(text.contains("if (!super.equals(other)) return false"), text)
        assertTrue(text.contains("var result = super.hashCode()"), text)
    }

    @Test
    fun `a project superclass that overrides neither adds no super call`() {
        val base = "open class Base(val label: String)"
        val source = "class A(val x: Int) : Base(\"b\") {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertFalse(text.contains("super.equals"), text)
        assertFalse(text.contains("super.hashCode"), text)
        assertTrue(text.contains("return x"), text)
    }

    @Test
    fun `a superclass that overrides only hashCode starts the hash with super`() {
        val base = "open class Base {\n    override fun hashCode(): Int = 1\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertFalse(text.contains("super.equals"), text)
        assertTrue(text.contains("var result = super.hashCode()"), text)
    }

    @Test
    fun `a superclass that overrides only equals adds the super check`() {
        val base = "open class Base {\n    override fun equals(other: Any?): Boolean = false\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertTrue(text.contains("if (!super.equals(other)) return false"), text)
        assertTrue(text.contains("return x"), text)
        assertFalse(text.contains("super.hashCode"), text)
    }

    @Test
    fun `an equals or hashCode overload in a superclass is not an override`() {
        val base = "open class Base {\n    fun equals(o: Base): Boolean = false\n    fun hashCode(bits: Int): Int = bits\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertFalse(text.contains("super.equals"), text)
        assertFalse(text.contains("super.hashCode"), text)
    }

    @Test
    fun `a final equals overload in a superclass is not a final override`() {
        val base = "open class Base {\n    final fun equals(o: Base): Boolean = false\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertFalse(text.contains("super.equals"), text)
    }

    @Test
    fun `an equals or hashCode overload in the class is not the member`() {
        val source = "class A(val x: Int) {\n    fun equals(o: A): Boolean = false\n    fun hashCode(bits: Int): Int = bits\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source).text ?: error("refused")
        assertTrue(text.contains("override fun equals(other: Any?)"), text)
        assertTrue(text.contains("override fun hashCode(): Int"), text)
    }

    @Test
    fun `the superclass chain is walked through project sources`() {
        val base = "open class Base {\n    override fun equals(other: Any?): Boolean = false\n    override fun hashCode(): Int = 1\n}"
        val middle = "open class Middle : Base() {\n}"
        val source = "class A(val x: Int) : Middle() {\n}"
        val files = mapOf(BASE_PATH to base, MIDDLE_PATH to middle)
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, files)).text ?: error("refused")
        assertTrue(text.contains("if (!super.equals(other)) return false"), text)
        assertTrue(text.contains("var result = super.hashCode()"), text)
    }

    @Test
    fun `a library class along the chain refuses`() {
        val middle = "open class Middle : Lib() {\n}"
        val source = "class A(val x: Int) : Middle() {\n}"
        val result = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(MIDDLE_PATH to middle)))
        assertEquals("Lib is not in project sources", result.status)
    }

    @Test
    fun `an unparenthesized supertype may be a class only with a secondary constructor and no primary`() {
        val refused = "class A : Base {\n    constructor(x: Int) : super()\n}"
        assertEquals("Base is not in project sources", run(GenerateKind.EQUALS_HASH_CODE, refused, currentTypes(refused)).status)
        val accepted = "class A(val x: Int) : Base {\n}"
        assertTrue(run(GenerateKind.EQUALS_HASH_CODE, accepted, currentTypes(accepted)).text != null)
    }

    @Test
    fun `an unparenthesized supertype of a class with a primary constructor is an interface`() {
        for (supertype in listOf("Comparable<A>", "Serializable")) {
            val source = "class A(val id: Int) : $supertype {\n}"
            val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)).text
                ?: error("refused: $supertype")
            assertFalse(text.contains("super.equals"), text)
            assertFalse(text.contains("super.hashCode"), text)
        }
    }

    @Test
    fun `the primary constructor rule holds along the superclass chain`() {
        val middle = "open class Middle(val y: Int) : Lib"
        val source = "class A(val x: Int) : Middle(1) {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(MIDDLE_PATH to middle))).text
            ?: error("refused")
        assertFalse(text.contains("super.equals"), text)
        assertFalse(text.contains("super.hashCode"), text)
    }

    @Test
    fun `an unparenthesized supertype without a constructor needs no super call`() {
        val source = "class A : Runnable {\n    val x: Int = 0\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)).text ?: error("refused")
        assertFalse(text.contains("super"), text)
    }

    @Test
    fun `a member named javaClass replaces the class check`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val javaClass: Int) {\n}").text ?: error("refused")
        assertTrue(text.contains("if (other == null || this::class != other::class) return false"), text)
        assertFalse(text.contains("javaClass != other?.javaClass"), text)
    }

    @Test
    fun `a javaClass member in a superclass replaces the class check`() {
        val base = "open class Base(val javaClass: Int)"
        val source = "class A(val x: Int) : Base(1) {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base))).text
            ?: error("refused")
        assertTrue(text.contains("if (other == null || this::class != other::class) return false"), text)
    }

    @Test
    fun `a var with a custom setter keeps its equals and hash`() {
        val source = """
            class A {
                var x: Int = 0
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        val text = run(GenerateKind.EQUALS_HASH_CODE, source).text ?: error("refused")
        assertTrue(text.contains("if (x != other.x) return false"), text)
        assertTrue(text.contains("return x"), text)
    }

    @Test
    fun `a nested array prints with contentDeepToString`() {
        val text = run(GenerateKind.TO_STRING, "class A(val grid: Array<Array<Int>>) {\n}").text ?: error("refused")
        assertTrue(text.contains("grid=\${grid.contentDeepToString()}"), text)
    }

    @Test
    fun `a final super equals refuses`() {
        val base = "open class Base {\n    final override fun equals(other: Any?): Boolean = false\n}"
        val source = "class A(val x: Int) : Base() {\n}"
        val result = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source, mapOf(BASE_PATH to base)))
        assertEquals("Base declares equals() final", result.status)
    }

    @Test
    fun `a library class supertype refuses equals`() {
        val source = "class A(val x: Int) : Base() {\n}"
        val result = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source))
        assertEquals("Base is not in project sources", result.status)
    }

    @Test
    fun `a supertype without an index refuses`() {
        val source = "class A(val x: Int) : Base() {\n}"
        assertEquals("Indexing… try again in a moment", run(GenerateKind.EQUALS_HASH_CODE, source, null).status)
    }

    @Test
    fun `the caret starts at the first body statement`() {
        val source = "class A(val x: Int) {\n}"
        val text = run(GenerateKind.EQUALS_HASH_CODE, source).text ?: error("refused")
        val caret = run(GenerateKind.EQUALS_HASH_CODE, source).caret ?: error("no caret")
        assertTrue(text.substring(caret).startsWith("if (this === other)"), text.substring(caret))
    }

    private companion object {
        const val BASE_PATH = "/project/src/main/kotlin/demo/Base.kt"
        const val MIDDLE_PATH = "/project/src/main/kotlin/demo/Middle.kt"
    }
}
