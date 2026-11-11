package stride.app.run

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RunTabsTest {
    private fun configuration(name: String = "demo.DemoTest") = RunConfiguration(
        name = name,
        workingDir = Path.of("."),
        args = emptyList(),
        kind = RunKind.TEST,
    )

    private fun running(): RunSession =
        RunSession(configuration(), FakeProcess(blocking = true), "fake").apply { start() }

    private fun finished(): RunSession {
        val session = RunSession(configuration(), FakeProcess(), "fake")
        session.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (session.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return session
    }

    @Test
    fun `one running session keeps the tab strip hidden`() {
        val session = running()
        try {
            assertFalse(showRunTabs(listOf(session)))
        } finally {
            session.stop()
        }
    }

    @Test
    fun `one finished session keeps its close tab`() {
        val session = finished()
        try {
            assertTrue(showRunTabs(listOf(session)))
        } finally {
            session.stop()
        }
    }

    @Test
    fun `several sessions always show the strip`() {
        val sessions = listOf(running(), running())
        try {
            assertTrue(showRunTabs(sessions))
        } finally {
            sessions.forEach { it.stop() }
        }
    }
}
