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
    fun `the source set follows the src test path`() {
        assertEquals("test", RunCommands.sourceSetFor(Path.of("/p/app/src/test/kotlin/A.kt")))
        assertEquals("main", RunCommands.sourceSetFor(Path.of("/p/app/src/main/kotlin/A.kt")))
        assertEquals("main", RunCommands.sourceSetFor(Path.of("/p/app/A.kt")))
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
    fun `builds the tool command for every script type`() {
        val base = temp.toAbsolutePath()
        assertEquals(listOf("java", base.resolve("A.java").toString()), RunCommands.tool(base.resolve("A.java"), false))
        assertEquals(listOf("kotlin", base.resolve("a.kts").toString()), RunCommands.tool(base.resolve("a.kts"), true))
        assertEquals(
            listOf("kotlinc", "-script", base.resolve("a.kts").toString()),
            RunCommands.tool(base.resolve("a.kts"), false),
        )
        assertEquals(listOf("python3", base.resolve("a.py").toString()), RunCommands.tool(base.resolve("a.py"), false))
        assertEquals(listOf("bash", base.resolve("a.sh").toString()), RunCommands.tool(base.resolve("a.sh"), false))
        assertEquals(listOf("node", base.resolve("a.js").toString()), RunCommands.tool(base.resolve("a.js"), false))
        assertEquals(listOf("ruby", base.resolve("a.rb").toString()), RunCommands.tool(base.resolve("a.rb"), false))
        assertNull(RunCommands.tool(base.resolve("A.kt"), true))
    }

    @Test
    fun `wraps a direct command in the login shell`() {
        val file = temp.resolve("a.py").toAbsolutePath()

        assertEquals(
            listOf("/bin/zsh", "-l", "-c", "exec \"\$@\"", "stride", "python3", file.toString()),
            RunCommands.direct(file, shell = "/bin/zsh", kotlinAvailable = false),
        )
        assertNull(RunCommands.direct(temp.resolve("A.kt"), shell = "/bin/zsh", kotlinAvailable = true))
    }
}
