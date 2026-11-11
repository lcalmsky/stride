package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
                "command -v kotlin >/dev/null && exec kotlin \"\$2\" || exec kotlinc -script \"\$2\"",
                "stride",
                "kotlin",
                file.toString(),
            ),
            RunCommands.direct(file, shell = "/bin/zsh"),
        )
    }

    @Test
    fun `the shell runs the script path, not the tool name, through kotlin`() {
        val bin = temp.resolve("bin")
        writeExecutable(bin.resolve("kotlin"), "for arg in \"\$@\"; do printf 'arg:%s\\n' \"\$arg\"; done")
        val file = Files.writeString(temp.resolve("hello.kts"), "println(\"hi\")\n").toAbsolutePath()

        val output = runWithoutLoginShell(requireNotNull(RunCommands.direct(file, shell = "/bin/sh")), bin)

        assertEquals("arg:${file}", output.trim())
    }

    @Test
    fun `the shell runs the script path through kotlinc when kotlin is missing`() {
        val bin = temp.resolve("bin-kotlinc")
        writeExecutable(bin.resolve("kotlinc"), "printf 'kotlinc:%s\\n' \"\$*\"")
        val file = Files.writeString(temp.resolve("hello.kts"), "println(\"hi\")\n").toAbsolutePath()

        val output = runWithoutLoginShell(requireNotNull(RunCommands.direct(file, shell = "/bin/sh")), bin)

        assertEquals("kotlinc:-script ${file}", output.trim())
    }

    private fun writeExecutable(file: Path, body: String): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        assertTrue(file.toFile().setExecutable(true), "could not make $file executable")
        return file
    }

    /**
     * Runs the command with only [bin] on `PATH` and without `-l`: a login shell would read the
     * user's profile files, which can replace `PATH` and hide the fakes.
     */
    private fun runWithoutLoginShell(argv: List<String>, bin: Path): String {
        val command = argv.toMutableList()
        assertTrue(command.remove("-l"), "expected the login flag in $argv")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = bin.toString() }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "shell did not finish: $output")
        return output
    }
}
