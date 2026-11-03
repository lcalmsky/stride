package stride.app.editor

import stride.core.services.Project
import stride.core.vfs.VFile

class TextEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VFile): Boolean = !file.isDirectory

    override fun createEditor(project: Project, file: VFile): FileEditor =
        TextFileEditor(file, readOnly = file.length() > TextFileEditor.MAX_HIGHLIGHTED_SIZE)
}
