package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.kotlin.refactor.KotlinCode

class KotlinClassModelTest {
    private fun klass(text: String, name: String = "A"): KtClazz {
        val code = KotlinCode(text)
        val found = kotlinClasses(code).first { it.name == name }
        return parseClassAt(code, found.keyword.index)!!
    }

    @Test
    fun `the forms and modifiers are read`() {
        assertTrue(klass("data class A(val x: Int)").isData)
        assertTrue(klass("enum class A {\n    X\n}").isEnum)
        assertTrue(klass("annotation class A")!!.isAnnotation)
        assertTrue(klass("value class A(val x: Int)").isValue)
        assertTrue(klass("inline class A(val x: Int)").isValue)
        assertTrue(klass("sealed class A {\n}")!!.modifiers.contains("sealed"))
        assertTrue(klass("abstract class A {\n}")!!.modifiers.contains("abstract"))
        assertTrue(klass("open class A {\n}")!!.modifiers.contains("open"))
        assertTrue(klass("fun interface A {\n    fun f()\n}").isFunInterface)
        assertTrue(klass("interface A {\n}").isInterface)
        assertTrue(klass("data object A {\n}").isObject)
        assertTrue(klass("class A {\n    companion object {\n    }\n}").nestedClasses.single().isCompanion)
        assertEquals("Companion", klass("class A {\n    companion object {\n    }\n}").nestedClasses.single().name)
    }

    @Test
    fun `an object expression is not a declaration`() {
        assertTrue(kotlinClasses(KotlinCode("val x = object : Runnable {\n}")).isEmpty())
        assertNull(classAt(KotlinCode("val x = object : Runnable {\n val y = 1\n}"), 30))
    }

    @Test
    fun `the primary constructor and its property parameters are read`() {
        val declaration = klass("class A @Inject constructor(private val x: Int, var y: String? = null) : Base(1)")
        val primary = declaration.primaryConstructor!!
        assertEquals(listOf("x", "y"), primary.parameters.map { it.name })
        assertEquals(listOf(true, true), primary.parameters.map { it.property })
        assertEquals(listOf(false, true), primary.parameters.map { it.isVar })
        assertEquals(listOf("Int", "String?"), primary.parameters.map { it.type })
    }

    @Test
    fun `supertypes keep their written form arguments and call`() {
        val declaration = klass("class A : Outer.Inner<User>, Runnable, Base()")
        assertEquals(listOf("Outer.Inner", "Runnable", "Base"), declaration.supertypes.map { it.written })
        assertEquals(listOf("User"), declaration.supertypes[0].typeArguments)
        assertEquals(listOf(false, false, true), declaration.supertypes.map { it.hasCall })
    }

    @Test
    fun `members of a class body are read`() {
        val declaration = klass(
            """
            class A {
                val x: Int = 1
                var y by lazy { 2 }
                lateinit var z: String
                val computed: Int
                    get() = 3
                constructor(a: Int) {
                    this.x = a
                }
                init {
                    println()
                }
                fun f(a: Int) = a
                abstract fun g(x: Int): String
                class Nested {
                }
                companion object {
                    operator fun invoke(): A = A(0)
                }
            }
            """.trimIndent(),
        )
        val kinds = declaration.members.map { it.kind }
        assertEquals(
            listOf(
                MemberKind.PROPERTY, MemberKind.PROPERTY, MemberKind.PROPERTY, MemberKind.PROPERTY,
                MemberKind.CONSTRUCTOR, MemberKind.INIT, MemberKind.FUNCTION, MemberKind.FUNCTION,
                MemberKind.NESTED_CLASS, MemberKind.NESTED_CLASS,
            ),
            kinds,
        )
        val delegated = declaration.properties.first { it.name == "y" }
        assertTrue(delegated.delegated)
        assertTrue(declaration.properties.first { it.name == "z" }.lateinit)
        assertTrue(declaration.properties.first { it.name == "computed" }.hasCustomGetter)
        assertTrue(declaration.properties.first { it.name == "x" }!!.initializer != null)
        assertNotNull(declaration.functions.first { it.name == "g" }.returnType)
        assertFalse(declaration.functions.first { it.name == "g" }.hasBody)
        assertTrue(declaration.functions.first { it.name == "f" }.hasBody)
        assertTrue(declaration.nestedClasses.first { it.isCompanion }.functions.single().modifiers.contains("operator"))
    }

    @Test
    fun `a custom setter leaves the getter and the backing field alone`() {
        val declaration = klass(
            """
            class A {
                var x: Int = 0
                    set(value) {
                        field = value
                    }
                val y: Int
                    get() = 1
            }
            """.trimIndent(),
        )
        val x = declaration.properties.first { it.name == "x" }
        assertFalse(x.hasCustomGetter)
        assertTrue(x.hasCustomSetter)
        assertTrue(declaration.properties.first { it.name == "y" }.hasCustomGetter)
    }

    @Test
    fun `a primary constructor vararg is read`() {
        val declaration = klass("class A(vararg xs: Int)")
        assertTrue(declaration.primaryConstructor!!.parameters.single().vararg)
    }

    @Test
    fun `enum entries end at the semicolon`() {
        val text = "enum class E {\n    A(1),\n    B(2);\n\n    var value: Int = 0\n}"
        val declaration = klass(text, "E")
        assertTrue(declaration.hasEnumEntries)
        assertEquals(text.indexOf(';'), declaration.enumEntriesEnd)
        assertEquals(listOf("value"), declaration.properties.map { it.name })
    }

    @Test
    fun `an enum without a semicolon has entries and no terminator`() {
        val declaration = klass("enum class E {\n    A, B\n}", "E")
        assertTrue(declaration.hasEnumEntries)
        assertNull(declaration.enumEntriesEnd)
    }

    @Test
    fun `the innermost class at the caret wins`() {
        val text = "class Outer {\n    class Inner {\n        val x = 1\n    }\n}"
        assertEquals("Inner", classAt(KotlinCode(text), text.indexOf("val x"))!!.name)
        assertEquals("Inner", classAt(KotlinCode(text), text.indexOf("class Inner"))!!.name)
        assertEquals("Outer", classAt(KotlinCode(text), text.indexOf("class Outer"))!!.name)
    }

    @Test
    fun `a class without a body ends at its header`() {
        val text = "class A(val x: Int) : Base()\nclass B"
        val declaration = klass(text)
        assertNull(declaration.body)
        assertEquals(text.indexOf("\nclass B"), declaration.headerEnd)
    }
}
