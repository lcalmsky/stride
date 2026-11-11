package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `run stops the previous session first`() {
        val project = Project(temp)
        try {
            val manager = project.service<RunManager>()
            val first = FakeProcess(blocking = true)
            val second = FakeProcess()
            val processes = ArrayDeque(listOf(first, second))
            manager.starter = ProcessStarter { StartedProcess(processes.removeFirst(), "fake") }

            manager.run(configuration("one"))
            assertTrue(manager.isRunning)

            manager.run(configuration("two"))

            assertTrue(first.destroyed)
            assertEquals("two", manager.lastRun?.name)
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
    fun `recent keeps the last ten distinct names, most recent first`() {
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
    fun `disposing the project destroys the child process`() {
        val project = Project(temp)
        val manager = project.service<RunManager>()
        val process = FakeProcess(blocking = true)
        manager.starter = ProcessStarter { StartedProcess(process, "fake") }

        manager.run(configuration())
        project.dispose()

        assertTrue(process.destroyed)
    }
}
