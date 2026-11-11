package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.core.vfs.VFile
import stride.search.IndexedFile
import java.nio.file.Paths

class TestNavigationTest {
    private fun indexed(relativePath: String) =
        IndexedFile(VFile(Paths.get("/project", relativePath)), relativePath)

    @Test
    fun `a source file offers its three test names`() {
        assertEquals(listOf("FooTest", "FooTests", "TestFoo"), TestNavigation.counterpartNames("Foo"))
    }

    @Test
    fun `a test file points back at its subject`() {
        assertEquals(listOf("Foo"), TestNavigation.counterpartNames("FooTest"))
        assertEquals(listOf("Foo"), TestNavigation.counterpartNames("FooTests"))
        assertEquals(listOf("Foo"), TestNavigation.counterpartNames("TestFoo"))
    }

    @Test
    fun `candidates keep the extension and put the same directory first`() {
        val files = listOf(
            indexed("src/main/kotlin/demo/Foo.kt"),
            indexed("other/FooTest.kt"),
            indexed("src/main/kotlin/demo/FooTest.kt"),
            indexed("src/main/kotlin/demo/FooTests.kt"),
            indexed("src/main/kotlin/demo/TestFoo.kt"),
            indexed("src/main/kotlin/demo/FooTest.java"),
        )

        val found = TestNavigation.candidates("Foo.kt", "src/main/kotlin/demo/Foo.kt", files)

        assertEquals(
            listOf("src/main/kotlin/demo/FooTest.kt", "src/main/kotlin/demo/FooTests.kt", "src/main/kotlin/demo/TestFoo.kt", "other/FooTest.kt"),
            found.map { it.relativePath },
        )
    }

    @Test
    fun `a test file finds its subject`() {
        val files = listOf(
            indexed("src/main/kotlin/demo/Foo.kt"),
            indexed("src/test/kotlin/demo/FooTest.kt"),
        )

        val found = TestNavigation.candidates("FooTest.kt", "src/test/kotlin/demo/FooTest.kt", files)

        assertEquals(listOf("src/main/kotlin/demo/Foo.kt"), found.map { it.relativePath })
    }

    @Test
    fun `no counterpart yields no candidates`() {
        val files = listOf(indexed("src/Bar.kt"))

        assertEquals(emptyList<IndexedFile>(), TestNavigation.candidates("Foo.kt", "src/Foo.kt", files))
    }
}
