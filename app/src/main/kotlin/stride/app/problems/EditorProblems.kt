package stride.app.problems

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.core.services.service

private const val PROBLEMS_DEBOUNCE_MILLIS: Long = 300

/**
 * Recomputes the selected editor's problems after its text has been quiet for
 * [PROBLEMS_DEBOUNCE_MILLIS], once the build source changed, and when the selected editor
 * changes. After a build it also recomputes every other open text editor without a debounce, so
 * their records carry the current build version. It writes the result to the editor and to the
 * project's records; it never moves the caret or the scroll.
 */
@Composable
fun EditorProblems(session: ProjectSession) {
    val editor = session.editors.selected as? TextFileEditor
    val model = session.project.service<ProblemsModel>()
    val text = editor?.value?.text
    LaunchedEffect(editor, text, model.buildVersion) {
        val current = editor ?: return@LaunchedEffect
        val currentText = text ?: return@LaunchedEffect
        delay(PROBLEMS_DEBOUNCE_MILLIS)
        val version = model.buildVersion
        val problems = withContext(Dispatchers.Default) {
            model.compute(current.file.path, current.file.name, currentText)
        }
        current.problems = problems
        model.record(current.file.path, problems, version)
    }
    val openEditors = session.editors.openFiles.filterIsInstance<TextFileEditor>()
    LaunchedEffect(model.buildVersion, openEditors) {
        for (open in openEditors) {
            if (open === editor) continue
            val openText = open.value.text
            val version = model.buildVersion
            val problems = withContext(Dispatchers.Default) {
                model.compute(open.file.path, open.file.name, openText)
            }
            open.problems = problems
            model.record(open.file.path, problems, version)
        }
    }
}
