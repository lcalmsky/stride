package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.problems.CompilerMessage
import stride.lang.ProblemSeverity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
        assertFalse(session.writeInput("later"), "stdin stays closed once a write failed")
        session.clearUndeliveredInput()
        assertNull(session.undeliveredInput)
        assertFalse(session.consoleLines.contains("later"))
        session.stop()
    }

    @Test
    fun `the console keeps only the newest lines`() {
        val last = RunSession.MAX_CONSOLE_LINES + 19
        val output = (0..last).joinToString("\n") { "line-$it" } + "\n"
        val process = FakeProcess(output.toByteArray())

        val session = RunSession(configuration(), process, "gradle :test")
        session.start()
        waitUntil { session.exitCode != null }

        assertTrue(session.consoleLines.size <= RunSession.MAX_CONSOLE_LINES)
        assertEquals("line-$last", session.consoleLines.last())
        assertFalse(session.consoleLines.contains("line-0"), "the oldest lines must be dropped")
        assertFalse(session.consoleLines.contains("gradle :test"), "the command line was the oldest line")
    }

    @Test
    fun `a finished session hands its compiler messages to the callback`() {
        val path = temp.resolve("A.kt").toAbsolutePath().normalize()
        val uri = path.toUri().toString()
        val output = "hello\ne: $uri:3:2 boom\nw: $uri:4:1 unused\n"
        var received: List<CompilerMessage>? = null

        val session = RunSession(configuration(), FakeProcess(output.toByteArray(), exitCode = 1), "gradle :test") {
            received = it
        }
        session.start()
        waitUntil { received != null }
        waitUntil { session.exitCode != null }

        val messages = requireNotNull(received)
        assertEquals(2, messages.size)
        assertEquals(path, messages[0].path)
        assertEquals(2, messages[0].line)
        assertEquals(1, messages[0].column)
        assertEquals(ProblemSeverity.ERROR, messages[0].severity)
        assertEquals("boom", messages[0].message)
        assertEquals(ProblemSeverity.WARNING, messages[1].severity)
    }

    @Test
    fun `a stopped session does not hand its compiler messages`() {
        val process = StreamingProcess()
        var called = false

        val session = RunSession(configuration(), process, "gradle :test") { called = true }
        session.start()
        process.write("e: file:///work/A.kt:1:1 boom\n")
        Thread.sleep(100)
        session.stop()
        waitUntil { !session.isRunning }

        assertFalse(called)
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

/** A process whose output the test writes, so the reader can be stopped mid-stream. */
private class StreamingProcess : Process() {
    private val writer = PipedOutputStream()
    private val reader = PipedInputStream(writer)

    @Volatile
    private var destroyed = false

    fun write(line: String) {
        writer.write(line.toByteArray(Charsets.UTF_8))
        writer.flush()
    }

    override fun getOutputStream(): java.io.OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): java.io.InputStream = reader

    override fun getErrorStream(): java.io.InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        while (!destroyed) Thread.sleep(10)
        return -1
    }

    override fun exitValue(): Int = -1

    override fun destroy() {
        destroyed = true
        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}
