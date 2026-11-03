package stride.app.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import stride.core.Disposable
import stride.core.services.Project
import stride.core.vfs.VFile

/** Extension point name for [FileEditorProvider] implementations. */
const val FILE_EDITOR_PROVIDER_EP: String = "stride.fileEditorProvider"

interface FileEditorProvider {
    fun accept(project: Project, file: VFile): Boolean

    fun createEditor(project: Project, file: VFile): FileEditor

    companion object {
        const val EP_NAME: String = FILE_EDITOR_PROVIDER_EP
    }
}

/**
 * UI editor for one file. State that must survive a tab switch (text, selection,
 * highlighter) lives in the editor object, not in [Content].
 */
interface FileEditor : Disposable {
    val file: VFile

    val isModified: Boolean

    fun save()

    @Composable
    fun Content(modifier: Modifier)
}
