package stride.app.terminal

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Files
import java.nio.file.Path

class TerminalSessionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `plain output lands on successive lines with the jediterm cursor`() {
        val project = project("plain")
        try {
            val session = project.service<TerminalSession>()
            session.start(FakeTtyConnector("hello\r\nworld"))
            waitUntil { session.lines().getOrNull(1) == "world" }
            assertEquals("hello", session.lines()[0])
            assertEquals("world", session.lines()[1])
            assertEquals(5, session.cursorX)
            assertEquals(2, session.cursorY)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `ansi color becomes an indexed foreground style`() {
        val project = project("color")
        try {
            val session = project.service<TerminalSession>()
            session.start(FakeTtyConnector("\u001b[31mred\u001b[0m"))
            waitUntil { session.lines().firstOrNull()?.startsWith("red") == true }
            val style = requireNotNull(session.buffer.getStyleAt(0, 0))
            assertEquals(1, style.foreground?.colorIndex)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `carriage return overwrites the line from its left edge`() {
        val project = project("carriage")
        try {
            val session = project.service<TerminalSession>()
            session.start(FakeTtyConnector("abc\rX"))
            waitUntil { session.lines().firstOrNull() == "Xbc" }
            assertEquals("Xbc", session.lines()[0])
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `erase display clears the screen before the next character`() {
        val project = project("erase")
        try {
            val session = project.service<TerminalSession>()
            session.start(FakeTtyConnector("a\u001b[2Jb"))
            waitUntil { session.lines().firstOrNull()?.contains("b") == true }
            assertFalse(session.lines().first().contains("a"))
            assertTrue(session.lines().first().contains("b"))
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `resize reaches the connector and the text buffer`() {
        val project = project("resize")
        try {
            val session = project.service<TerminalSession>()
            val fake = FakeTtyConnector()
            session.start(fake)
            session.resize(40, 10)
            assertTrue(fake.resizes.contains(40 to 10))
            assertEquals(40, session.buffer.width)
            assertEquals(10, session.buffer.height)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `write sends its text to the connector`() {
        val project = project("write")
        try {
            val session = project.service<TerminalSession>()
            val fake = FakeTtyConnector()
            session.start(fake)
            session.write("ls\n")
            waitUntil { fake.written.toString() == "ls\n" }
            assertEquals("ls\n", fake.written.toString())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `sendKey encodes enter and the up arrow`() {
        val project = project("keys")
        try {
            val session = project.service<TerminalSession>()
            val fake = FakeTtyConnector()
            session.start(fake)
            assertTrue(session.sendKey(Key.Enter, false, false, false, false, null))
            assertTrue(session.sendKey(Key.DirectionUp, false, false, false, false, null))
            assertTrue(session.sendKey(Key.L, false, false, false, false, 'l'))
            waitUntil { fake.written.toString() == "\r\u001b[Al" }
            assertEquals("\r\u001b[Al", fake.written.toString())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `disposing the project closes the connector`() {
        val project = project("dispose")
        val session = project.service<TerminalSession>()
        val fake = FakeTtyConnector("$ ")
        session.start(fake)
        project.dispose()
        assertTrue(fake.closed)
    }

    @Test
    fun `processing output bumps the revision`() {
        val project = project("revision")
        try {
            val session = project.service<TerminalSession>()
            val before = session.revision
            session.start(FakeTtyConnector("one\ntwo\n"))
            waitUntil { session.revision > before }
            assertTrue(session.revision > before)
        } finally {
            project.dispose()
        }
    }

    private fun project(name: String): Project {
        val root = Files.createDirectories(temp.resolve(name))
        return Project(root)
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met within ${timeoutMillis}ms")
    }
}
