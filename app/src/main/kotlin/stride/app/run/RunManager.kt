package stride.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service

class StartedProcess(val process: Process, val displayCommand: String)

fun interface ProcessStarter {
    fun start(configuration: RunConfiguration): StartedProcess
}

object DefaultProcessStarter : ProcessStarter {
    override fun start(configuration: RunConfiguration): StartedProcess {
        val command = configuration.command
            ?: GradleCommand.build(configuration.workingDir, configuration.args, GradleInitScript.materialize())
        val process = ProcessBuilder(command)
            .directory(configuration.workingDir.toFile())
            .redirectErrorStream(true)
            .start()
        return StartedProcess(process, GradleCommand.display(command))
    }
}

/**
 * One project's run sessions. Each distinct configuration gets its own session (and Run tool
 * window tab), so starting a build does not stop a running application. Owned by the project,
 * so closing it stops every child process.
 */
@Service(Service.Level.PROJECT)
class RunManager(private val project: Project) : Disposable {
    var starter: ProcessStarter = DefaultProcessStarter

    var lastRun: RunConfiguration? by mutableStateOf(null)
        private set

    /** One entry per concurrently existing configuration, oldest first. */
    private val runSessions = mutableStateListOf<RunSession>()

    val sessions: List<RunSession> get() = runSessions

    /** The selected tab's session. */
    var current: RunSession? by mutableStateOf(null)
        private set

    private val recentConfigurations = mutableStateListOf<RunConfiguration>()

    /** Last [MAX_RECENT] runs, most recent first, one entry per configuration. */
    val recent: List<RunConfiguration> get() = recentConfigurations

    val isRunning: Boolean get() = current?.isRunning == true

    fun run(configuration: RunConfiguration): RunSession {
        lastRun = configuration
        record(configuration)
        val existing = runSessions.indexOfFirst { it.configuration.identity == configuration.identity }
        if (existing >= 0) runSessions.removeAt(existing).stop()
        val started = starter.start(configuration)
        val session = RunSession(configuration, started.process, started.displayCommand)
        if (existing >= 0) runSessions.add(existing, session) else runSessions.add(session)
        current = session
        session.start()
        return session
    }

    /** Makes [session] the tab that [stop] and [rerun] act on. */
    fun select(session: RunSession) {
        if (runSessions.any { it === session }) current = session
    }

    fun rerun(): RunSession? = (current?.configuration ?: lastRun)?.let(::run)

    fun stop() {
        current?.stop()
    }

    override fun dispose() {
        runSessions.forEach { it.stop() }
    }

    private fun record(configuration: RunConfiguration) {
        recentConfigurations.removeAll { it.identity == configuration.identity }
        recentConfigurations.add(0, configuration)
        while (recentConfigurations.size > MAX_RECENT) recentConfigurations.removeAt(recentConfigurations.lastIndex)
    }

    companion object {
        const val MAX_RECENT = 10
    }
}
