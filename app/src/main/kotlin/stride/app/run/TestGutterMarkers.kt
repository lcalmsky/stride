package stride.app.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stride.app.ProjectSession
import stride.app.editor.GutterMarker
import stride.app.editor.TextFileEditor
import stride.app.ui.toolwindow.ToolWindowManager

/** Puts a ▶ in the gutter of every test declaration and of the file's `main` function. */
@Composable
fun TestGutterMarkers(
    session: ProjectSession,
    toolWindows: ToolWindowManager,
    onStatus: (String?) -> Unit = {},
) {
    val editor = session.editors.selected as? TextFileEditor
    val text = editor?.value?.text
    LaunchedEffect(editor, text) {
        if (editor == null || text == null) return@LaunchedEffect
        val fileName = editor.file.name
        if (!fileName.endsWith(".kt") && !fileName.endsWith(".java")) {
            editor.gutterMarkers = emptyList()
            return@LaunchedEffect
        }
        val path = editor.file.path.toString().replace('\\', '/')
        val markers = withContext(Dispatchers.Default) {
            val found = mutableListOf<GutterMarker>()
            if (fileName.endsWith(".kt") && path.contains("/src/test/")) {
                found += TestLocator.markers(text).map { marker ->
                    GutterMarker(marker.line) {
                        if (runTarget(session, editor.file.path, marker.target)) {
                            onStatus(null)
                            toolWindows.activate("run")
                        }
                    }
                }
            }
            MainLocator.locate(text, fileName)?.let { main ->
                found += GutterMarker(main.line) {
                    if (runMain(session, editor.file.path, main)) {
                        onStatus(null)
                        toolWindows.activate("run")
                    } else {
                        onStatus(mainConfigurationProblem(session, editor.file.path, main))
                    }
                }
            }
            found
        }
        editor.gutterMarkers = markers
    }
}
