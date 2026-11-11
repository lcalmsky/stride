package stride.app.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import stride.app.ProjectSession
import stride.app.editor.GutterMarker
import stride.app.editor.TextFileEditor
import stride.app.ui.toolwindow.ToolWindowManager

/** Puts a ▶ in the gutter of every test declaration and of the file's `main` function. */
@Composable
fun TestGutterMarkers(session: ProjectSession, toolWindows: ToolWindowManager) {
    val editor = session.editors.selected as? TextFileEditor
    val text = editor?.value?.text
    LaunchedEffect(editor, text) {
        if (editor == null || text == null) return@LaunchedEffect
        val path = editor.file.path.toString().replace('\\', '/')
        val markers = mutableListOf<GutterMarker>()
        if (editor.file.name.endsWith(".kt") && path.contains("/src/test/")) {
            markers += TestLocator.markers(text).map { marker ->
                GutterMarker(marker.line) {
                    runTarget(session, editor.file.path, marker.target)
                    toolWindows.activate("run")
                }
            }
        }
        MainLocator.locate(text, editor.file.name)?.let { main ->
            markers += GutterMarker(main.line) {
                runMain(session, editor.file.path, main)
                toolWindows.activate("run")
            }
        }
        editor.gutterMarkers = markers
    }
}
