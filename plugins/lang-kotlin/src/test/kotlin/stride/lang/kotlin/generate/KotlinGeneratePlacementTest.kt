package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.GenerateKind

class KotlinGeneratePlacementTest {
    private fun equalsRun(source: String) = run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source))

    @Test
    fun `members go between the members around the caret`() {
        val source = """
            class A(val x: Int) {
                val a = 1

                |
                val b = 2
            }
        """.trimIndent()
        val text = equalsRun(source).text ?: error("refused")
        val equals = text.indexOf("override fun equals")
        assertTrue(text.indexOf("val a = 1") < equals, text)
        assertTrue(equals < text.indexOf("val b = 2"), text)
        assertTrue(text.contains("val a = 1\n\n    override fun equals"), text)
        assertTrue(text.contains("}\n\n    val b = 2"), text)
    }

    @Test
    fun `members go after the member holding the caret`() {
        val source = """
            class A(val x: Int) {
                fun f() {|
                }

                val b = 2
            }
        """.trimIndent()
        val text = equalsRun(source).text ?: error("refused")
        assertTrue(text.indexOf("fun f()") < text.indexOf("override fun equals"), text)
        assertTrue(text.indexOf("override fun equals") < text.indexOf("val b = 2"), text)
    }

    @Test
    fun `a caret in the header appends at the end of the body`() {
        val source = "class A(val x: Int)| {\n    val a = 1\n}"
        val text = equalsRun(source).text ?: error("refused")
        assertTrue(text.indexOf("val a = 1") < text.indexOf("override fun equals"), text)
        assertTrue(text.trimEnd().endsWith("}"), text)
    }

    @Test
    fun `an enum puts members after the entry semicolon`() {
        val source = "enum class E {\n    A, B;\n\n    |\n}"
        val text = run(GenerateKind.TO_STRING, source, currentTypes(source)).text ?: error("refused")
        assertTrue(text.indexOf(";") < text.indexOf("override fun toString"), text)
    }

    @Test
    fun `a class without a body gets one`() {
        val text = equalsRun("class A(val x: Int)").text ?: error("refused")
        assertTrue(text.startsWith("class A(val x: Int) {"), text)
        assertTrue(text.trimEnd().endsWith("}"), text)
    }

    @Test
    fun `a header that does not end its line refuses a body`() {
        val result = equalsRun("class A(val x: Int)| fun f() {}")
        assertEquals("Add a body to A first", result.status)
    }

    @Test
    fun `the existing member indentation is the step`() {
        val source = "class A(val x: Int) {\n  val a = 1\n}"
        val text = equalsRun(source).text ?: error("refused")
        assertTrue(text.contains("\n  override fun equals"), text)
    }

    @Test
    fun `the file's first indented line is the step`() {
        val source = "fun f() {\n        println()\n}\n\nclass A|"
        val text = equalsRun(source).text ?: error("refused")
        assertTrue(text.contains("\n        override fun equals"), text)
    }

    @Test
    fun `four spaces are the last resort`() {
        val text = equalsRun("class A").text ?: error("refused")
        assertTrue(text.contains("\n    override fun equals"), text)
    }

    @Test
    fun `a member on the class opening line keeps the generated member inside the body`() {
        val text = equalsRun("class A {| val x = 1 }").text ?: error("refused")
        val close = text.lastIndexOf('}')
        assertTrue(text.indexOf("override fun equals") in 0 until close, text)
        assertTrue(text.indexOf("override fun hashCode") in 0 until close, text)
    }

    @Test
    fun `a member sharing the class closing line keeps the generated member inside the body`() {
        val text = equalsRun("class A {\n    fun f() {}|}").text ?: error("refused")
        val close = text.lastIndexOf('}')
        assertTrue(text.indexOf("override fun equals") in 0 until close, text)
        assertTrue(text.indexOf("override fun hashCode") in 0 until close, text)
    }

    @Test
    fun `an existing blank line is not doubled`() {
        val source = "class A(val x: Int) {\n\n}"
        val text = equalsRun(source).text ?: error("refused")
        assertTrue(!text.contains("\n\n\n"), text)
    }

    @Test
    fun `generated members are separated by one blank line`() {
        val text = run(GenerateKind.EQUALS_HASH_CODE, "class A(val x: Int) {\n}", currentTypes("class A(val x: Int) {\n}")).text
            ?: error("refused")
        val between = text.substringAfter("    }").substringBefore("override fun hashCode")
        assertEquals("\n\n    ", between)
    }

    @Test
    fun `one undo edit carries the generated text`() {
        val source = "class A(val x: Int) {\n}"
        val step = prepare(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)) as stride.lang.RefactoringStep.ChooseMembers
        val valid = step.complete(step.members) as stride.lang.NameCheck.Valid
        assertEquals(1, valid.edit.edits.size)
        assertTrue(valid.edit.edits.all { it.start == it.end }, valid.edit.edits.toString())
    }
}
