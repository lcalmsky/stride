package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RunSessionTest {
    @TempDir
    lateinit var temp: Path

    private fun configuration() = RunConfiguration(
        name = "demo.DemoTest",
        workingDir = temp,
        args = GradleCommand.testArgs("", "demo.DemoTest"),
        kind = RunKind.TEST,
    )

    @Test
    fun `reads output into the console and events into the tree`() {
        val output = listOf(
            "##stride[test start class='demo.DemoTest' name='passes']",
            "hello stdout",
            "##stride[test pass class='demo.DemoTest' name='passes' duration='4']",
            "##stride[test start class='demo.DemoTest' name='fails']",
            "##stride[test out class='demo.DemoTest' name='fails' stream='stdout' text='inside']",
            "##stride[test fail class='demo.DemoTest' name='fails' duration='5' message='boom' stack='at demo.DemoTest.fails']",
            "BUILD FAILED",
        ).joinToString("\n") + "\n"
        val process = FakeProcess(output.toByteArray(), exitCode = 1)

        val session = RunSession(configuration(), process, "gradle :test --tests demo.DemoTest")
        session.start()
        waitUntil { session.exitCode != null }

        assertEquals("gradle :test --tests demo.DemoTest", session.consoleLines.first())
        assertEquals(listOf("gradle :test --tests demo.DemoTest", "hello stdout", "BUILD FAILED"), session.consoleLines.toList())
        assertEquals(1, session.tree.classes.size)
        val methods = session.tree.classes.single().methods
        assertEquals(listOf("passes", "fails"), methods.map { it.name })
        assertEquals(TestStatus.PASSED, methods[0].status)
        assertEquals(TestStatus.FAILED, methods[1].status)
        assertEquals("boom", methods[1].message)
        assertEquals("at demo.DemoTest.fails", methods[1].stackTrace)
        assertEquals(listOf("inside"), methods[1].output.toList())
        assertEquals(1, session.tree.passed)
        assertEquals(1, session.tree.failed)
        assertEquals(1, session.exitCode)
        assertFalse(session.isRunning)
    }

    @Test
    fun `writes stdin lines and echoes them until the process ends`() {
        val process = FakeProcess(blocking = true)
        val session = RunSession(
            RunConfiguration(name = "MainKt", workingDir = temp, kind = RunKind.APPLICATION, command = listOf("run")),
            process,
            "run",
        )
        session.start()

        assertTrue(session.writeInput("hello"))
        waitUntil { process.stdin == "hello\n" && session.consoleLines.contains("hello") }

        session.stop()
        waitUntil { !session.isRunning }
        assertFalse(session.writeInput("after"))
    }

    @Test
    fun `keeps a line the writer could not deliver`() {
        val process = FakeProcess(blocking = true, failStdin = true)
        val session = RunSession(
            RunConfiguration(name = "MainKt", workingDir = temp, kind = RunKind.APPLICATION, command = listOf("run")),
            process,
            "run",
        )
        session.start()

        assertTrue(session.writeInput("hello"))
        waitUntil { session.undeliveredInput == "hello" }

        assertFalse(session.consoleLines.contains("hello"))
        session.clearUndeliveredInput()
        assertNull(session.undeliveredInput)
        session.stop()
    }

    @Test
    fun `stop destroys the process even when toHandle is unsupported`() {
        val process = FakeProcess(blocking = true)

        val session = RunSession(configuration(), process, "gradle :test")
        session.start()
        assertTrue(session.isRunning)

        session.stop()

        assertTrue(process.destroyed)
        waitUntil { !session.isRunning }
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMillis}ms")
    }
}
