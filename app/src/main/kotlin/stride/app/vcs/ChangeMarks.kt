package stride.app.vcs

import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.core.services.Project

/**
 * Gutter change marks; lane G replaces the bodies. [attach] runs when a text editor opens and
 * [detach] when its tab closes, both from [stride.app.editor.FileEditorManager].
 */
object ChangeMarks {
    fun attach(project: Project, editor: TextFileEditor) {}

    fun detach(editor: TextFileEditor) {}
}

fun rollbackLines(state: AppState): Boolean {
    state.statusMessage = "Rollback Lines is not implemented yet"
    return true
}

fun gotoChange(state: AppState, forward: Boolean): Boolean {
    state.statusMessage = if (forward) "Next Change is not implemented yet" else "Previous Change is not implemented yet"
    return true
}
