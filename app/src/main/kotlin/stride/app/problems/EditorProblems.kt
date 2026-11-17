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
 * changes. It writes the result to the editor and to the project's records; it never moves the
 * caret or the scroll.
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
        val problems = withContext(Dispatchers.Default) {
            model.compute(current.file.path, current.file.name, currentText)
        }
        current.problems = problems
        model.record(current.file.path, problems)
    }
}
