package stride.app.ui

import stride.app.keys.KeyRouter
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

/**
 * M5: wraps [choose] so the menu bar knows its modal loop is up; a click reaching a row while the
 * chooser is open is not a choice the user made.
 */
class FolderChooserGuard(private val dialog: () -> Path?) {
    var isOpen: Boolean = false
        private set

    fun choose(): Path? {
        isOpen = true
        try {
            return dialog()
        } finally {
            isOpen = false
        }
    }
}

/** Shows the platform folder chooser and returns the selected directory, or `null`. */
fun chooseFolder(): Path? = if (KeyRouter.isMacOs()) {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = FileDialog(null as Frame?, "Open Folder", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file
        val directory = dialog.directory
        when {
            name != null && directory != null -> Paths.get(directory, name)
            directory != null -> Paths.get(directory)
            else -> null
        }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
} else {
    val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Open Folder"
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}
