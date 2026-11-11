package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileStructureTest {
    private val text = """
        class A {
            fun a() {}
            class B {
                fun b() {}
            }
        }

        fun top() {}
    """.trimIndent()

    @Test
    fun `entries follow the nesting depth`() {
        val entries = FileStructure.entries("A.kt", text)

        assertEquals(listOf("A", "a", "B", "b", "top"), entries.map { it.name })
        assertEquals(listOf(0, 1, 1, 2, 0), entries.map { it.depth })
        assertEquals(listOf(0, 1, 2, 3, 7), entries.map { it.line })
    }

    @Test
    fun `an empty query keeps the file order and a query ranks by name`() {
        val entries = FileStructure.entries("A.kt", text)

        assertEquals(entries, FileStructure.matching(entries, ""))
        assertEquals(listOf("top"), FileStructure.matching(entries, "top").map { it.name })
    }
}
