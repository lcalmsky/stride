package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GradleCommandTest {
    @TempDir
    lateinit var temp: Path

    private val initScript = Path.of("/tmp/stride-test-events.init.gradle")

    @Test
    fun `uses the wrapper through a login shell`() {
        val args = GradleCommand.testArgs(":app", "demo.DemoTest")

        val command = GradleCommand.build(
            workingDir = temp,
            args = args,
            initScript = initScript,
            shell = "/bin/zsh",
            gradlewExists = { true },
        )

        assertEquals(
            listOf(
                "/bin/zsh",
                "-l",
                "-c",
                "exec \"\$@\"",
                "stride",
                temp.resolve("gradlew").toAbsolutePath().toString(),
                "--console=plain",
                "--init-script",
                initScript.toString(),
                ":app:test",
                "--tests",
                "demo.DemoTest",
            ),
            command,
        )
        assertEquals("stride", command[4])
    }

    @Test
    fun `keeps a spaced test name as one argument and quotes it in the display`() {
        val filter = "stride.app.KeyFlowUiTest.command 4 opens the run tool window and shift escape hides it"

        val command = GradleCommand.build(
            workingDir = temp,
            args = GradleCommand.testArgs(":app", filter),
            initScript = initScript,
            shell = "/bin/zsh",
            gradlewExists = { true },
        )

        assertEquals(filter, command[command.lastIndex])
        assertEquals(1, command.count { it == filter })
        val display = GradleCommand.display(command)
        assertTrue(display.contains("'$filter'"), display)
        assertTrue(display.contains(" --tests "), display)
        assertTrue(!display.contains("'--tests'"), display)
    }

    @Test
    fun `falls back to the gradle on PATH`() {
        val command = GradleCommand.build(
            workingDir = temp,
            args = listOf(":test"),
            initScript = initScript,
            shell = "/bin/zsh",
            gradlewExists = { false },
        )

        assertEquals("gradle", command[5])
    }

    @Test
    fun `test args use the Gradle project path`() {
        assertEquals(
            listOf(":plugins:lang-kotlin:test", "--tests", "x.Y"),
            GradleCommand.testArgs(":plugins:lang-kotlin", "x.Y"),
        )
        assertEquals(listOf(":test", "--tests", "x.Y"), GradleCommand.testArgs("", "x.Y"))
    }

    @Test
    fun `falls back to zsh when SHELL is missing`() {
        assertEquals("/bin/zsh", GradleCommand.shellOrDefault(null))
        assertEquals("/bin/zsh", GradleCommand.shellOrDefault(""))
        assertEquals("/bin/fish", GradleCommand.shellOrDefault("/bin/fish"))
    }
}
