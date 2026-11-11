package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationKind
import java.nio.file.Paths

class DeclarationNavigatorTest {
    private val text = """
        package demo

        class Foo {
            fun foo() {}
            fun call() { foo() }
        }
    """.trimIndent()

    @Test
    fun `the nearest preceding declaration in the same file wins`() {
        val target = DeclarationNavigator.localTarget("Foo.kt", text, "foo", caretLine = 4)

        assertEquals(3, target?.line)
    }

    @Test
    fun `without a preceding declaration the first one is used`() {
        val target = DeclarationNavigator.localTarget("Foo.kt", text, "Foo", caretLine = 1)

        assertEquals(2, target?.line)
    }

    @Test
    fun `an unknown name has no local target`() {
        assertNull(DeclarationNavigator.localTarget("Foo.kt", text, "bar", caretLine = 2))
    }

    @Test
    fun `index candidates prefer the same package then the same module`() {
        val candidates = listOf(
            declaration("src/main/kotlin/other/A.kt", 1),
            declaration("plugins/x/src/main/kotlin/demo/A.kt", 2),
            declaration("src/main/kotlin/demo/A.kt", 3),
        )

        val ranked = DeclarationNavigator.ranked(candidates, currentPackage = "demo", currentModule = "")

        assertEquals(
            listOf(
                "src/main/kotlin/demo/A.kt",
                "plugins/x/src/main/kotlin/demo/A.kt",
                "src/main/kotlin/other/A.kt",
            ),
            ranked.map { it.relativePath },
        )
    }

    @Test
    fun `the package comes from the file, else from the source root path`() {
        assertEquals("demo.app", DeclarationNavigator.packageOf("package demo.app\n", "src/main/kotlin/x/A.kt"))
        assertEquals("demo.app", DeclarationNavigator.packageOf("", "src/main/kotlin/demo/app/A.kt"))
        assertNull(DeclarationNavigator.packageOf("", "notes/A.txt"))
    }

    @Test
    fun `the module is the path before the source root`() {
        assertEquals("", DeclarationNavigator.moduleOfPath("src/main/kotlin/demo/A.kt"))
        assertEquals("plugins/lang", DeclarationNavigator.moduleOfPath("plugins/lang/src/main/kotlin/A.kt"))
    }

    private fun declaration(relativePath: String, line: Int) = Declaration(
        name = "A",
        kind = DeclarationKind.SYMBOL,
        file = VFile(Paths.get("/project", relativePath)),
        relativePath = relativePath,
        line = line,
    )
}
