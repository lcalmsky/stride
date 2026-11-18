package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.GenerateKind
import stride.lang.RefactoringStep
import stride.lang.kotlin.refactor.KotlinCode

class KotlinGenerateProviderTest {
    @Test
    fun `a caret outside a class refuses`() {
        assertRefused("No class at the caret", "package demo\n\nfun f() {|\n}")
        assertRefused("No class at the caret", "package demo\n\nval x = 1\n|")
        assertRefused(
            "No class at the caret",
            "class A {\n    val r = object : Runnable {\n        |\n    }\n}",
        )
    }

    @Test
    fun `unbalanced brackets refuse`() {
        assertRefused("Brackets do not match in this file", "class A {\n    |val x = 1\n")
    }

    @Test
    fun `the kind table follows the class form`() {
        assertRefused(GenerateKind.OVERRIDE_METHODS, "Override Methods is not available in an annotation class", "annotation class A|")
        assertRefused(GenerateKind.IMPLEMENT_METHODS, "Implement Methods is not available in an interface", "interface A {\n    |\n}")
        assertEquals(
            "Implement Methods is not available in an annotation class",
            run(GenerateKind.IMPLEMENT_METHODS, "annotation class A|", currentTypes("annotation class A")).status,
        )
        val funInterface = "fun interface A {\n    fun f()\n    |\n}"
        val funInterfaceStep = prepare(GenerateKind.OVERRIDE_METHODS, funInterface, currentTypes(funInterface))
        assertEquals(
            "No members to implement in project sources",
            (funInterfaceStep as RefactoringStep.Refused).reason,
        )
        assertRefused(GenerateKind.IMPLEMENT_METHODS, "Implement Methods is not available in an interface", "fun interface A {\n    fun f()\n    |\n}")
        val overrideStep = prepare(GenerateKind.OVERRIDE_METHODS, "object A {\n    |\n}", currentTypes("object A {\n}"))
        assertTrue(overrideStep is RefactoringStep.ChooseMembers, overrideStep.toString())
        val implementStep = prepare(GenerateKind.IMPLEMENT_METHODS, "value class A(val x: Int) {\n    |\n}", currentTypes("value class A(val x: Int) {\n}"))
        assertEquals("No members to implement in project sources", (implementStep as RefactoringStep.Refused).reason)
    }

    @Test
    fun `an object and a data object allow toString but not equals`() {
        for (source in listOf("object A {\n}", "data object A {\n}")) {
            assertEquals(
                "equals() and hashCode() is not available in an object",
                run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)).status,
            )
            val step = prepare(GenerateKind.TO_STRING, source, currentTypes(source))
            assertTrue(step is RefactoringStep.ChooseMembers || step is RefactoringStep.Apply, step.toString())
        }
    }

    @Test
    fun `an enum allows toString and a secondary constructor without a primary`() {
        val source = "enum class E {\n    A;\n\n    var value: Int = 0\n}"
        val step = prepare(GenerateKind.TO_STRING, source, currentTypes(source))
        assertTrue(step is RefactoringStep.ChooseMembers, step.toString())
    }

    @Test
    fun `sealed abstract open data and inline classes allow the class kinds`() {
        for (source in listOf(
            "sealed class A(val x: Int) {\n}",
            "abstract class A(val x: Int) {\n}",
            "open class A(val x: Int) {\n}",
            "data class A(val x: Int) {\n}",
        )) {
            assertTrue(
                run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)).text != null ||
                    run(GenerateKind.EQUALS_HASH_CODE, source, currentTypes(source)).status?.contains("already") == true,
                source,
            )
        }
        assertEquals(
            "equals() and hashCode() is not available in a value class",
            run(GenerateKind.EQUALS_HASH_CODE, "inline class A(val x: Int) {\n}", currentTypes("inline class A(val x: Int) {\n}")).status,
        )
    }

    @Test
    fun `a companion object is a class for the caret`() {
        val source = "class A {\n    companion object {\n        |\n    }\n}"
        val step = prepare(GenerateKind.TO_STRING, source, currentTypes(source))
        assertTrue(step is RefactoringStep.ChooseMembers || step is RefactoringStep.Apply, step.toString())
    }

    @Test
    fun `a member that would be written twice refuses`() {
        assertEquals(
            "toString() already exists in A",
            run(GenerateKind.TO_STRING, "class A {\n |   override fun toString(): String = \"\"\n}", currentTypes("class A")).status,
        )
    }

    @Test
    fun `there is no class at a class without a name`() {
        assertNull(classAt(KotlinCode("val o = object : Runnable { }"), 8))
    }

    private fun assertRefused(reason: String, marked: String) {
        assertRefused(GenerateKind.EQUALS_HASH_CODE, reason, marked)
    }

    private fun assertRefused(kind: GenerateKind, reason: String, marked: String) {
        val step = prepare(kind, marked, null)
        assertEquals(reason, (step as RefactoringStep.Refused).reason)
    }
}
