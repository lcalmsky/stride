package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TestLocatorTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("stride.repoRoot")) { "stride.repoRoot is not set" })

    @Test
    fun `locates a test method, its class and nothing above the class`() {
        val text = Files.readString(root.resolve("app/src/test/kotlin/stride/app/KeyFlowUiTest.kt"))
        val method = "fun `command 4 opens the run tool window and shift escape hides it`"
        val methodIndex = text.indexOf(method)
        val target = TestLocator.locate(text, methodIndex + 12)
        assertEquals(
            TestLocator.TestTarget("stride.app.KeyFlowUiTest", "command 4 opens the run tool window and shift escape hides it"),
            target,
        )

        val classIndex = text.indexOf("class KeyFlowUiTest")
        assertEquals(TestLocator.TestTarget("stride.app.KeyFlowUiTest", null), TestLocator.locate(text, classIndex + 5))

        assertNull(TestLocator.locate(text, 0))
    }

    @Test
    fun `locates the second test of another module`() {
        val text = Files.readString(root.resolve("search/src/test/kotlin/stride/search/NameMatcherTest.kt"))
        val method = "fun `matches case-insensitive substrings`"
        val index = text.indexOf(method)

        assertEquals(
            TestLocator.TestTarget("stride.search.NameMatcherTest", "matches case-insensitive substrings"),
            TestLocator.locate(text, index + 10),
        )
    }

    @Test
    fun `marks the class and every annotated test function`() {
        val text = Files.readString(root.resolve("app/src/test/kotlin/stride/app/KeyFlowUiTest.kt"))
        val annotations = Regex("(?m)^\\s*@Test\\b").findAll(text).count()

        val markers = TestLocator.markers(text)

        assertEquals(annotations + 1, markers.size)
        assertEquals(TestLocator.TestTarget("stride.app.KeyFlowUiTest", null), markers.first().target)
        assertEquals(TestLocator.TestTarget("stride.app.KeyFlowUiTest", "double shift then enter opens the matched file in a tab"), markers[1].target)
    }

    @Test
    fun `derives the Gradle project path`() {
        assertEquals(":app", TestLocator.gradleProjectPath(root.resolve("app/src/test/kotlin/stride/app/KeyFlowUiTest.kt"), root))
        assertEquals(
            ":plugins:lang-kotlin",
            TestLocator.gradleProjectPath(
                root.resolve("plugins/lang-kotlin/src/test/kotlin/stride/lang/kotlin/KotlinLexerTest.kt"),
                root,
            ),
        )
        assertEquals("", TestLocator.gradleProjectPath(root.resolve("build.gradle.kts"), root))
        assertNull(TestLocator.gradleProjectPath(Path.of("/tmp/outside/src/test/kotlin/A.kt"), root))
    }
}
