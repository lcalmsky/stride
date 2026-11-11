package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Path

class RunManagerTest {
    @TempDir
    lateinit var temp: Path

    private fun configuration(name: String = "demo.DemoTest") = RunConfiguration(
        name = name,
        workingDir = temp,
        args = GradleCommand.testArgs("", name),
        kind = RunKind.TEST,
    )

    @Test
    fun `run records the configuration and calls the starter`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            var calls = 0
            var recorded: RunConfiguration? = null
            manager.starter = ProcessStarter { configuration ->
                calls++
                recorded = configuration
                StartedProcess(FakeProcess(), "fake")
            }
            val configuration = configuration()

            manager.run(configuration)

            assertEquals(1, calls)
            assertEquals(configuration, recorded)
            assertEquals(configuration, manager.lastRun)
            assertNotNull(manager.current)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `running a different configuration keeps the previous session alive`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            val first = FakeProcess(blocking = true)
            val second = FakeProcess(blocking = true)
            val processes = ArrayDeque(listOf(first, second))
            manager.starter = ProcessStarter { StartedProcess(processes.removeFirst(), "fake") }

            manager.run(configuration("one"))
            manager.run(configuration("two"))

            assertTrue(manager.isRunning)
            assertTrue(manager.sessions.any { it.configuration.name == "one" && it.isRunning })
            assertEquals(2, manager.sessions.size)
            assertEquals("two", manager.current?.configuration?.name)
            assertEquals(false, first.destroyed)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `running the same configuration replaces its tab and stops the old process`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            val first = FakeProcess(blocking = true)
            val second = FakeProcess(blocking = true)
            val processes = ArrayDeque(listOf(first, second))
            manager.starter = ProcessStarter { StartedProcess(processes.removeFirst(), "fake") }
            val configuration = configuration("one")

            manager.run(configuration)
            val same = manager.run(configuration.copy(name = "one renamed"))

            assertTrue(first.destroyed)
            assertEquals(1, manager.sessions.size)
            assertSame(same, manager.current)
            assertEquals("one renamed", manager.current?.configuration?.name)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `stop only stops the selected tab`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            val first = FakeProcess(blocking = true)
            val second = FakeProcess(blocking = true)
            val processes = ArrayDeque(listOf(first, second))
            manager.starter = ProcessStarter { StartedProcess(processes.removeFirst(), "fake") }

            manager.run(configuration("one"))
            manager.run(configuration("two"))
            val firstSession = manager.sessions.first { it.configuration.name == "one" }

            manager.select(firstSession)
            manager.stop()

            assertTrue(first.destroyed)
            assertEquals(false, second.destroyed)
            assertEquals("one", manager.current?.configuration?.name)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `rerun reruns the selected tab's configuration`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            val started = mutableListOf<RunConfiguration>()
            manager.starter = ProcessStarter { configuration ->
                started += configuration
                StartedProcess(FakeProcess(blocking = true), "fake")
            }
            manager.run(configuration("one"))
            manager.run(configuration("two"))

            manager.rerun()

            assertEquals(listOf("one", "two", "two"), started.map { it.name })
            assertEquals(2, manager.sessions.size)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `rerun needs a previous run`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            assertNull(manager.rerun())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `recent keeps the last ten distinct configurations, most recent first`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

            for (index in 1..12) manager.run(configuration("run-$index"))
            manager.run(configuration("run-5"))

            val names = manager.recent.map { it.name }
            assertEquals(10, names.size)
            assertEquals("run-5", names.first())
            assertEquals(1, names.count { it == "run-5" })
            assertEquals(listOf("run-5", "run-12", "run-11", "run-10", "run-9", "run-8", "run-7", "run-6", "run-4", "run-3"), names)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `recent keeps configurations that only differ in their arguments`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

            manager.run(configuration("same"))
            manager.run(configuration("same").copy(args = listOf("other")))

            assertEquals(2, manager.recent.size)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `disposing the project destroys every child process`() {
        val project = Project(temp)
        val manager = project.service<RunManager>()
        val first = FakeProcess(blocking = true)
        val second = FakeProcess(blocking = true)
        val processes = ArrayDeque(listOf(first, second))
        manager.starter = ProcessStarter { StartedProcess(processes.removeFirst(), "fake") }

        manager.run(configuration("one"))
        manager.run(configuration("two"))
        project.dispose()

        assertTrue(first.destroyed)
        assertTrue(second.destroyed)
    }
}
