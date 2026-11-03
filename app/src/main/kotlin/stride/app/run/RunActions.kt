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
    if (TestLocator.gradleProjectPath(editor.file.path, session.project.root) == null) return false
    if (!runTarget(session, editor.file.path, target)) return false
    state.toolWindows.activate("run")
    return true
}

fun runTarget(state: AppState, file: Path, target: TestLocator.TestTarget): Boolean {
    val session = state.session ?: return false
    return runTarget(session, file, target)
}

fun runTarget(session: ProjectSession, file: Path, target: TestLocator.TestTarget): Boolean {
    val gradlePath = TestLocator.gradleProjectPath(file, session.project.root) ?: return false
    val filter = target.methodName?.let { "${target.className}.$it" } ?: target.className
    val configuration = RunConfiguration(
        name = filter,
        workingDir = session.project.root,
        args = GradleCommand.testArgs(gradlePath, filter),
        kind = RunKind.TEST,
    )
    session.project.service<RunManager>().run(configuration)
    return true
}
