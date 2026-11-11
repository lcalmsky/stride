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

/** One project's run sessions. Owned by the project, so closing it stops the child process. */
@Service(Service.Level.PROJECT)
class RunManager(private val project: Project) : Disposable {
    var starter: ProcessStarter = DefaultProcessStarter

    var lastRun: RunConfiguration? by mutableStateOf(null)
        private set

    var current: RunSession? by mutableStateOf(null)
        private set

    private val recentConfigurations = mutableStateListOf<RunConfiguration>()

    /** Last [MAX_RECENT] runs, most recent first, one entry per name. */
    val recent: List<RunConfiguration> get() = recentConfigurations

    val isRunning: Boolean get() = current?.isRunning == true

    fun run(configuration: RunConfiguration): RunSession {
        if (isRunning) stop()
        lastRun = configuration
        record(configuration)
        val started = starter.start(configuration)
        val session = RunSession(configuration, started.process, started.displayCommand)
        current = session
        session.start()
        return session
    }

    fun rerun(): RunSession? = lastRun?.let(::run)

    fun stop() {
        current?.stop()
    }

    override fun dispose() {
        stop()
    }

    private fun record(configuration: RunConfiguration) {
        recentConfigurations.removeAll { it.name == configuration.name }
        recentConfigurations.add(0, configuration)
        while (recentConfigurations.size > MAX_RECENT) recentConfigurations.removeAt(recentConfigurations.lastIndex)
    }

    companion object {
        const val MAX_RECENT = 10
    }
}
