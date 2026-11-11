package stride.app.run

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.core.services.service
import java.nio.file.Path

/** Starts the test under the selected editor's caret. Returns `false` when there is none. */
fun runAtCaret(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val target = TestLocator.locate(editor.value.text, editor.value.selection.start) ?: return false
    if (!runTarget(session, editor.file.path, target)) return false
    state.toolWindows.activate("run")
    return true
}

fun runTarget(state: AppState, file: Path, target: TestLocator.TestTarget): Boolean {
    val session = state.session ?: return false
    return runTarget(session, file, target)
}

fun runTarget(session: ProjectSession, file: Path, target: TestLocator.TestTarget): Boolean {
    val configuration = testConfiguration(session, file, target) ?: return false
    session.project.service<RunManager>().run(configuration)
    return true
}

/** The test run for [target], or null when [file] is not in a Gradle project. */
fun testConfiguration(session: ProjectSession, file: Path, target: TestLocator.TestTarget): RunConfiguration? {
    val gradlePath = TestLocator.gradleProjectPath(file, session.project.root) ?: return null
    val filter = target.methodName?.let { "${target.className}.$it" } ?: target.className
    return RunConfiguration(
        name = filter,
        workingDir = session.project.root,
        args = GradleCommand.testArgs(gradlePath, filter),
        kind = RunKind.TEST,
    )
}

/** Starts [file]'s entry point. Returns `false` when the file cannot be run. */
fun runMain(session: ProjectSession, file: Path, target: MainLocator.MainTarget?): Boolean {
    val configuration = mainConfiguration(session, file, target) ?: return false
    session.project.service<RunManager>().run(configuration)
    return true
}

/**
 * The run for [file]'s entry point. Scripts run directly even inside a Gradle project;
 * Kotlin and Java use `strideRunMain` when they belong to one.
 */
fun mainConfiguration(
    session: ProjectSession,
    file: Path,
    target: MainLocator.MainTarget?,
): RunConfiguration? {
    val root = session.project.root
    if (RunCommands.isScript(file)) {
        val command = RunCommands.direct(file) ?: return null
        return RunConfiguration(
            name = file.fileName?.toString() ?: file.toString(),
            workingDir = root,
            kind = RunKind.APPLICATION,
            command = command,
        )
    }
    if (target == null) return null
    val extension = file.extension()
    val gradlePath = TestLocator.gradleProjectPath(file, root)
    if (gradlePath != null) {
        return RunConfiguration(
            name = simpleName(target.className),
            workingDir = root,
            args = RunCommands.gradleMainArgs(gradlePath, target.className, RunCommands.sourceSetFor(file)),
            kind = RunKind.APPLICATION,
        )
    }
    if (extension != "java") return null
    val command = RunCommands.direct(file) ?: return null
    return RunConfiguration(
        name = simpleName(target.className),
        workingDir = root,
        kind = RunKind.APPLICATION,
        command = command,
    )
}

/** The runnable entries of the selected file: its main, then the test at the caret. */
fun fileRunConfigurations(state: AppState): List<RunConfiguration> {
    val session = state.session ?: return emptyList()
    val editor = session.editors.selected as? TextFileEditor ?: return emptyList()
    val configurations = mutableListOf<RunConfiguration>()
    val main = MainLocator.locate(editor.value.text, editor.file.name)
    mainConfiguration(session, editor.file.path, main)?.let(configurations::add)
    val test = TestLocator.locate(editor.value.text, editor.value.selection.start)
    if (test != null) testConfiguration(session, editor.file.path, test)?.let(configurations::add)
    return configurations
}

/** Opens the ⌥↩ menu for the caret; `false` when there is no editor to act on. */
fun openContextActions(state: AppState): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val manager = session.project.service<RunManager>()
    val actions = ContextActions.collect(
        text = editor.value.text,
        fileName = editor.file.name,
        caretOffset = editor.value.selection.start,
        lastRunName = manager.lastRun?.name,
        runningName = manager.current?.takeIf { it.isRunning }?.configuration?.name,
    )
    if (actions.isEmpty()) {
        state.statusMessage = "No context actions here"
        return true
    }
    state.contextActions = actions
    state.contextActionsVisible = true
    state.statusMessage = null
    return true
}

/** Opens the run configuration chooser with the file's entries and the recent runs. */
fun openRunConfigurationChooser(state: AppState): Boolean {
    val session = state.session ?: return false
    val current = fileRunConfigurations(state)
    val recent = session.project.service<RunManager>().recent.filter { candidate ->
        current.none { it.name == candidate.name }
    }
    val choices = current + recent
    if (choices.isEmpty()) {
        state.statusMessage = "Nothing to run here"
        return true
    }
    state.runConfigurationChoices = choices
    state.runConfigurationsVisible = true
    state.statusMessage = null
    return true
}

/** Runs [kind] again by looking the target up at the caret. */
fun runContextAction(state: AppState, kind: ContextActionKind): Boolean {
    val session = state.session ?: return false
    val editor = session.editors.selected as? TextFileEditor ?: return false
    val manager = session.project.service<RunManager>()
    val started = when (kind) {
        ContextActionKind.RUN_MAIN ->
            runMain(session, editor.file.path, MainLocator.locate(editor.value.text, editor.file.name))
        ContextActionKind.RUN_TEST, ContextActionKind.RUN_TEST_CLASS -> {
            val found = TestLocator.locate(editor.value.text, editor.value.selection.start) ?: return false
            val target = if (kind == ContextActionKind.RUN_TEST_CLASS) found.copy(methodName = null) else found
            runTarget(session, editor.file.path, target)
        }
        ContextActionKind.RERUN -> manager.rerun() != null
        ContextActionKind.STOP -> {
            manager.stop()
            true
        }
    }
    if (started) state.toolWindows.activate("run")
    return started
}

private fun simpleName(className: String): String = className.substringAfterLast('.')

private fun Path.extension(): String = fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
