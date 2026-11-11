package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeCompletionTest {
    @Test
    fun `identifiers come back in document order`() {
        assertEquals(listOf("val", "value", "x"), CodeCompletion.identifiers("val value = x"))
    }

    @Test
    fun `identifiers leave out the one being completed`() {
        assertEquals(
            listOf("val", "main"),
            CodeCompletion.identifiers("val ma = main", excluded = 4..5),
        )
        assertEquals(listOf("val", "ma"), CodeCompletion.identifiers("val ma", excluded = 6 until 6))
    }

    @Test
    fun `candidates rank prefix matches and drop duplicates`() {
        assertEquals(
            listOf("map", "main"),
            CodeCompletion.candidates("ma", listOf("main", "map", "main", "value")),
        )
    }

    @Test
    fun `candidates accept camel humps`() {
        assertEquals(listOf("myClass"), CodeCompletion.candidates("mC", listOf("myClass", "other")))
    }

    @Test
    fun `a blank prefix keeps every name`() {
        assertEquals(
            listOf("a", "b", "cc"),
            CodeCompletion.candidates("", listOf("cc", "a", "b", "a")),
        )
    }

    @Test
    fun `the limit caps the result`() {
        assertEquals(1, CodeCompletion.candidates("", listOf("a", "b", "c"), limit = 1).size)
        assertTrue(CodeCompletion.candidates("", listOf("a"), limit = 0).isEmpty())
    }

    @Test
    fun `keywords follow the file language`() {
        assertTrue("fun" in CodeCompletion.keywordsFor("Main.kt"))
        assertTrue("fun" in CodeCompletion.keywordsFor("build.gradle.kts"))
        assertTrue("class" in CodeCompletion.keywordsFor("App.java"))
        assertTrue(CodeCompletion.keywordsFor("notes.txt").isEmpty())
    }
}
