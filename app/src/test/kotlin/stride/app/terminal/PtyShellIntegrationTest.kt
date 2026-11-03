package stride.app.terminal

import com.pty4j.PtyProcessBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PtyShellIntegrationTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a pty shell writes its output into the terminal model`() {
        assumeTrue(System.getenv("CI") == null, "PTY tests are skipped on CI")
        assumeTrue(Files.isExecutable(Path.of("/bin/zsh")), "/bin/zsh is required")
        val probe = runCatching { PtyShell.start(temp, 80, 24) }
        assumeTrue(probe.isSuccess, "PTY is not available: ${probe.exceptionOrNull()}")
        probe.getOrNull()?.let {
            it.destroyForcibly()
            it.waitFor(2, TimeUnit.SECONDS)
        }

        val process = PtyProcessBuilder(arrayOf("/bin/zsh", "-lc", "printf hello"))
            .setEnvironment(HashMap(System.getenv()).apply { put("TERM", "xterm-256color") })
            .setDirectory(temp.toString())
            .setInitialColumns(80)
            .setInitialRows(24)
            .start()
        val project = Project(temp)
        try {
            val session = project.service<TerminalSession>()
            session.start(PtyTtyConnector(process))
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && session.lines().none { it.contains("hello") }) {
                Thread.sleep(20)
            }
            assertTrue(
                session.lines().any { it.contains("hello") },
                "expected hello in ${session.lines()}",
            )
        } finally {
            runCatching { process.destroyForcibly() }
            process.waitFor(2, TimeUnit.SECONDS)
            project.dispose()
        }
    }
}
