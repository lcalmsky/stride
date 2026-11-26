package stride.app.files

import stride.app.editor.FileEditorManager
import stride.app.navigation.BookmarkManager
import stride.app.tree.ProjectTreeModel
import stride.app.vcs.GitService
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path

/** One project whose file writes go to real files under [root] and a fake Trash under [trash]. */
class FileOperationsFixture(root: Path, trash: Path) : AutoCloseable {
    val project: Project = Project(root)
    val editors: FileEditorManager = project.service()
    val tree: ProjectTreeModel = ProjectTreeModel(VFile(project.root), emptyList())
    val bookmarks: BookmarkManager = BookmarkManager()
    val git: GitService = project.service()
    val trash: Path = trash

    val files: FileOperations = FileOperations(project.root, editors, tree, bookmarks, git).also {
        it.trashSupported = { true }
        it.moveToTrash = { path ->
            Files.move(path, this.trash.resolve(path.fileName))
            true
        }
    }

    fun write(relative: String, content: String = ""): Path {
        val path = project.root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    fun directory(relative: String): Path = Files.createDirectories(project.root.resolve(relative))

    /** Drops the tree's cached root children, for files written after the fixture was built. */
    fun refreshTree() {
        tree.handleEvents(listOf(VFileEvent.Created(VFile(project.root))))
        if (!EventQueue.isDispatchThread()) EventQueue.invokeAndWait {}
    }

    override fun close() {
        project.dispose()
    }
}
