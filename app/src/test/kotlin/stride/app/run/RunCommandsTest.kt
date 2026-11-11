package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RunCommandsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `main args name the task, the class and the source set`() {
        assertEquals(
            listOf(
                ":app:strideRunMain",
                "-Pstride.mainClass=demo.MainKt",
                "-Pstride.sourceSet=main",
            ),
            RunCommands.gradleMainArgs(":app", "demo.MainKt", "main"),
        )
        assertEquals(
            listOf(
                ":strideRunMain",
                "-Pstride.mainClass=Demo",
                "-Pstride.sourceSet=test",
            ),
            RunCommands.gradleMainArgs("", "Demo", "test"),
        )
    }

    @Test
    fun `the source set is read from the src language directory`() {
        val project = Path.of("/p/app")

        assertEquals("main", RunCommands.sourceSetIn(project, Path.of("/p/app/src/main/kotlin/A.kt")))
        assertEquals("test", RunCommands.sourceSetIn(project, Path.of("/p/app/src/test/java/A.java")))
        assertEquals("integrationTest", RunCommands.sourceSetIn(project, Path.of("/p/app/src/integrationTest/kotlin/A.kt")))
        assertNull(RunCommands.sourceSetIn(project, Path.of("/p/app/playground/A.kt")))
        assertNull(RunCommands.sourceSetIn(project, Path.of("/p/app/src/main/resources/A.kt")))
        assertNull(RunCommands.sourceSetIn(project, Path.of("/p/other/src/main/kotlin/A.kt")))
    }

    @Test
    fun `each script extension is a direct run`() {
        for (name in listOf("a.py", "a.sh", "a.js", "a.rb", "a.kts")) {
            assertTrue(RunCommands.isScript(temp.resolve(name)), name)
        }
        assertFalse(RunCommands.isScript(temp.resolve("A.kt")))
        assertFalse(RunCommands.isScript(temp.resolve("A.java")))
    }

    @Test
    fun `gradle build scripts are never scripts`() {
        for (name in listOf("build.gradle.kts", "settings.gradle.kts", "module.gradle.kts")) {
            val file = temp.resolve(name)
            assertTrue(RunCommands.isGradleBuildScript(file), name)
            assertFalse(RunCommands.isScript(file), name)
            assertNull(RunCommands.tool(file), name)
            assertNull(RunCommands.direct(file, shell = "/bin/zsh"), name)
        }
    }

    @Test
    fun `builds the tool command for every script type`() {
        val base = temp.toAbsolutePath()
        assertEquals(listOf("java", base.resolve("A.java").toString()), RunCommands.tool(base.resolve("A.java")))
        assertEquals(listOf("kotlin", base.resolve("a.kts").toString()), RunCommands.tool(base.resolve("a.kts")))
        assertEquals(listOf("python3", base.resolve("a.py").toString()), RunCommands.tool(base.resolve("a.py")))
        assertEquals(listOf("bash", base.resolve("a.sh").toString()), RunCommands.tool(base.resolve("a.sh")))
        assertEquals(listOf("node", base.resolve("a.js").toString()), RunCommands.tool(base.resolve("a.js")))
        assertEquals(listOf("ruby", base.resolve("a.rb").toString()), RunCommands.tool(base.resolve("a.rb")))
        assertNull(RunCommands.tool(base.resolve("A.kt")))
    }

    @Test
    fun `wraps a direct command in the login shell`() {
        val file = temp.resolve("a.py").toAbsolutePath()

        assertEquals(
            listOf("/bin/zsh", "-l", "-c", "exec \"\$@\"", "stride", "python3", file.toString()),
            RunCommands.direct(file, shell = "/bin/zsh"),
        )
        assertNull(RunCommands.direct(temp.resolve("A.kt"), shell = "/bin/zsh"))
    }

    @Test
    fun `kotlin scripts fall back to kotlinc inside the login shell`() {
        val file = temp.resolve("a.kts").toAbsolutePath()

        assertEquals(
            listOf(
                "/bin/zsh",
                "-l",
                "-c",
                "command -v kotlin >/dev/null && exec kotlin \"\$1\" || exec kotlinc -script \"\$1\"",
                "stride",
                "kotlin",
                file.toString(),
            ),
            RunCommands.direct(file, shell = "/bin/zsh"),
        )
    }
}
