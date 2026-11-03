package stride.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service

class StartedProcess(val process: Process, val displayCommand: String)

fun interface ProcessStarter {
    fun start(configuration: RunConfiguration): StartedProcess
}

object GradleProcessStarter : ProcessStarter {
    override fun start(configuration: RunConfiguration): StartedProcess {
        val initScript = GradleInitScript.materialize()
        val command = GradleCommand.build(configuration.workingDir, configuration.args, initScript)
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
    var starter: ProcessStarter = GradleProcessStarter

    var lastRun: RunConfiguration? by mutableStateOf(null)
        private set

    var current: RunSession? by mutableStateOf(null)
        private set

    val isRunning: Boolean get() = current?.isRunning == true

    fun run(configuration: RunConfiguration): RunSession {
        if (isRunning) stop()
        lastRun = configuration
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
}
