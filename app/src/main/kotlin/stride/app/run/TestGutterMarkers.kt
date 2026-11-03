package stride.app.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import stride.app.ProjectSession
import stride.app.editor.GutterMarker
import stride.app.editor.TextFileEditor
import stride.app.ui.toolwindow.ToolWindowManager

/** Puts a ▶ in the gutter of every test declaration of the selected test source. */
@Composable
fun TestGutterMarkers(session: ProjectSession, toolWindows: ToolWindowManager) {
    val editor = session.editors.selected as? TextFileEditor
    val text = editor?.value?.text
    LaunchedEffect(editor, text) {
        if (editor == null || text == null) return@LaunchedEffect
        val path = editor.file.path.toString().replace('\\', '/')
        if (!editor.file.name.endsWith(".kt") || !path.contains("/src/test/")) {
            editor.gutterMarkers = emptyList()
            return@LaunchedEffect
        }
        editor.gutterMarkers = TestLocator.markers(text).map { marker ->
            GutterMarker(marker.line) {
                runTarget(session, editor.file.path, marker.target)
                toolWindows.activate("run")
            }
        }
    }
}
