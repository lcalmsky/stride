package stride.app.files

import stride.app.editor.FileEditor
import stride.app.editor.FileEditorManager
import stride.app.navigation.BookmarkManager
import stride.app.tree.ProjectTreeModel
import stride.app.vcs.GitCommand
import stride.app.vcs.GitService
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import java.awt.Desktop
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** One entry of the session's file clipboard: the path and whether Cut put it there (P10). */
data class FileClipboardEntry(val path: Path, val cut: Boolean)

/** What one file operation left behind. */
sealed interface FileOperationResult {
    /** The P13 success or error text; null when the operation has nothing to say. */
    val message: String?

    /** The operation left [path] on disk: created, moved, copied, revealed or deleted. */
    data class Success(val path: Path, override val message: String? = null) : FileOperationResult

    data class Failure(override val message: String) : FileOperationResult

    /**
     * Paste of a copied path whose name is taken: the caller opens the Copy popup on [path] with
     * [message] as its error line.
     */
    data class OpenCopyPopup(val path: Path, override val message: String) : FileOperationResult
}

/** The six kinds of the New › Kotlin Class/File name page, in its order (P7). */
enum class KotlinFileKind { CLASS, FILE, INTERFACE, DATA_CLASS, ENUM_CLASS, OBJECT }

/**
 * The project tree's file writes (P5–P10): New, Rename, Move, Delete, Copy, Cut/Copy/Paste and
 * Reveal in Finder, plus the facts their popups read. A write checks again right before it runs,
 * then refreshes the tree and git.
 *
 * It touches no Compose UI and no focus; opening a created editor and moving the keyboard stay
 * with the caller.
 */
class FileOperations(
    root: Path,
    private val editors: FileEditorManager,
    private val tree: ProjectTreeModel,
    private val bookmarks: BookmarkManager,
    private val git: GitService,
) {
    private val rootKey: Path = root.toAbsolutePath().normalize()

    /** Test seam: git's write state; production reads [GitService.busy]. */
    internal var busyProvider: () -> String? = { git.busy }

    /** Test seam for the Trash; production asks the desktop and [moveToTrash] moves. */
    internal var trashSupported: () -> Boolean = { defaultTrashSupported() }
    internal var moveToTrash: (Path) -> Boolean = { defaultMoveToTrash(it) }

    /** Test seam for Reveal in Finder; production runs `open -R` or the desktop's browser. */
    internal var revealSupported: () -> Boolean = { defaultRevealSupported() }
    internal var launcher: (List<String>) -> Unit = { defaultLaunch(it) }

    /** Test seam for the Delete popup's file count; production keeps [MAX_COUNTED_FILES]. */
    internal var maxCountedFiles: Int = MAX_COUNTED_FILES

    /** The session's one clipboard entry; P10's Cut, Copy and Paste share it. */
    var clipboard: FileClipboardEntry? = null
        private set

    /** P6: the error line for one name; `/` is a segment separator and so is refused. */
    fun nameError(name: String): String? =
        if (isValidName(name) && '/' !in name) null else "$name is not a valid name"

    /** P6: the error line for a relative path such as `a/b/C.kt`, or null when it is valid. */
    fun relativePathError(text: String): String? =
        if (isValidRelativePath(text)) null else "$text is not a valid name"

    /** P7: the error line for a Kotlin file name; a trailing `.kt` is dropped first. */
    fun kotlinNameError(name: String): String? =
        if (IDENTIFIER.matches(name.removeSuffix(".kt"))) null else "$name is not a valid Kotlin name"

    /** P6: the refusal for the root or a path outside it, or null when the path may be written. */
    fun insideRootError(path: Path): String? = if (isInsideRoot(path)) null else ROOT_MESSAGE

    /** P6: `<name> already exists` when [path] exists (links not followed), else null. */
    fun existsError(path: Path): String? =
        if (Files.exists(path.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS)) {
            "${path.fileName} already exists"
        } else {
            null
        }

    /** P7: the package of a directory under `src/<set>/kotlin` or `src/<set>/java`, or null. */
    fun packageFor(directory: Path): String? {
        val dir = directory.toAbsolutePath().normalize()
        var ancestor: Path? = dir
        while (ancestor != null) {
            val name = ancestor.fileName?.toString()
            if ((name == "kotlin" || name == "java") && ancestor.parent?.parent?.fileName?.toString() == "src") {
                val relative = ancestor.relativize(dir)
                val segments = relative.iterator().asSequence().map { it.toString() }.toList()
                if (segments.isEmpty() || !segments.all { IDENTIFIER.matches(it) }) return null
                return segments.joinToString(".")
            }
            ancestor = ancestor.parent
        }
        return null
    }

    /** P7: creates an empty file, missing directories included; the name may contain `/`. */
    fun createFile(directory: Path, name: String): FileOperationResult =
        create(directory, name, text = null, directoryTarget = false)

    /** P7: creates a directory, missing directories included; the name may contain `/`. */
    fun createDirectory(directory: Path, name: String): FileOperationResult =
        create(directory, name, text = null, directoryTarget = true)

    /** P7: creates `<name>.kt` with [kind]'s template under [packageFor]'s package line. */
    fun createKotlin(directory: Path, name: String, kind: KotlinFileKind): FileOperationResult {
        kotlinNameError(name)?.let { return FileOperationResult.Failure(it) }
        val simpleName = name.removeSuffix(".kt")
        val packageLine = packageFor(directory)?.let { "package $it\n\n" } ?: ""
        val text = packageLine + template(kind, simpleName)
        return create(directory, "$simpleName.kt", text, directoryTarget = false)
    }

    /** P8: Rename and Move — one move of a file or directory; same-parent moves read as a rename. */
    fun move(source: Path, targetPath: Path): FileOperationResult {
        val from = source.toAbsolutePath().normalize()
        val to = targetPath.toAbsolutePath().normalize()
        val name = from.fileName?.toString() ?: from.toString()
        val targetName = to.fileName?.toString() ?: to.toString()
        val rename = from.parent == to.parent
        val operation = if (rename) "Rename" else "Move"
        if (!isInsideRoot(from) || !isInsideRoot(to)) return FileOperationResult.Failure(ROOT_MESSAGE)
        if (from == to) return FileOperationResult.Success(from)
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$name no longer exists")
        }
        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS) && to.startsWith(from)) {
            return FileOperationResult.Failure("Cannot move $name into itself")
        }
        val caseOnly = isCaseOnlyRename(from, to)
        if (!caseOnly && Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$targetName already exists")
        }
        busyProvider()?.let { return FileOperationResult.Failure("Git is busy: $it") }
        try {
            if (caseOnly) {
                // Files.move is a no-op when both names are the same file on a case-insensitive
                // volume, so the rename goes through a sibling name that cannot exist yet.
                val temporary = from.resolveSibling(".${from.fileName}.stride-rename-${System.nanoTime()}")
                Files.move(from, temporary)
                try {
                    Files.move(temporary, to)
                } catch (e: IOException) {
                    runCatching { Files.move(temporary, from) }
                    throw e
                }
            } else {
                Files.move(from, to)
            }
        } catch (e: IOException) {
            return FileOperationResult.Failure("$operation failed: ${reason(e)}")
        }
        followMove(from, to)
        val message = if (rename) {
            buildString {
                append("Renamed ${relative(from)} to ${relative(to)}")
                if (caseOnly && isTracked(from)) append("; git still tracks ${relative(from)}")
            }
        } else {
            "Moved $name to ${relativeDisplay(to.parent)}"
        }
        return FileOperationResult.Success(to, message)
    }

    /** P9: moves [target] to the Trash, closing the tabs under it; nothing changes on refusal. */
    fun delete(target: Path): FileOperationResult {
        val key = target.toAbsolutePath().normalize()
        val name = key.fileName?.toString() ?: key.toString()
        if (!isInsideRoot(key)) return FileOperationResult.Failure(ROOT_MESSAGE)
        if (!Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$name no longer exists")
        }
        busyProvider()?.let { return FileOperationResult.Failure("Git is busy: $it") }
        if (!trashSupported()) return FileOperationResult.Failure(NO_TRASH_MESSAGE)
        val trashed = try {
            moveToTrash(key)
        } catch (e: Exception) {
            false
        }
        if (!trashed) return FileOperationResult.Failure("Delete failed: could not move $name to the Trash")
        for (editor in editors.openFiles.filter { isAtOrBelow(it.file.path, key) }) editors.closeFile(editor.file)
        tree.handleEvents(listOf(VFileEvent.Deleted(VFile(key))))
        git.refresh()
        return FileOperationResult.Success(key, "Moved $name to the Trash")
    }

    /** P10: copies [source] to [targetPath] recursively, links as links, without replacing. */
    fun copy(source: Path, targetPath: Path): FileOperationResult {
        val from = source.toAbsolutePath().normalize()
        val to = targetPath.toAbsolutePath().normalize()
        val name = from.fileName?.toString() ?: from.toString()
        val targetName = to.fileName?.toString() ?: to.toString()
        if (!isInsideRoot(from) || !isInsideRoot(to)) return FileOperationResult.Failure(ROOT_MESSAGE)
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$name no longer exists")
        }
        if (to == from || to.startsWith(from)) {
            return FileOperationResult.Failure("Cannot move $name into itself")
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$targetName already exists")
        }
        busyProvider()?.let { return FileOperationResult.Failure("Git is busy: $it") }
        try {
            copyTree(from, to)
        } catch (e: IOException) {
            return FileOperationResult.Failure("Copy failed: ${reason(e)}")
        }
        tree.handleEvents(listOf(VFileEvent.Created(VFile(to))))
        tree.reveal(VFile(to))
        tree.select(VFile(to))
        git.refresh()
        return FileOperationResult.Success(to, "Copied $name to ${relative(to)}")
    }

    /** P10: remembers [path] for Paste and reads `Cut <name>`. */
    fun cut(path: Path): FileOperationResult {
        val key = path.toAbsolutePath().normalize()
        clipboard = FileClipboardEntry(key, cut = true)
        return FileOperationResult.Success(key, "Cut ${displayName(key)}")
    }

    /** P10: remembers [path] for Paste and reads `Copied <name>`. */
    fun copy(path: Path): FileOperationResult {
        val key = path.toAbsolutePath().normalize()
        clipboard = FileClipboardEntry(key, cut = false)
        return FileOperationResult.Success(key, "Copied ${displayName(key)}")
    }

    /**
     * P10: pastes the clipboard into [targetDirectory]. Null when the clipboard is empty; a copy
     * whose name is taken returns [FileOperationResult.OpenCopyPopup], a cut conflict the P13 text.
     */
    fun paste(targetDirectory: Path): FileOperationResult? {
        val entry = clipboard ?: return null
        val source = entry.path.toAbsolutePath().normalize()
        val directory = targetDirectory.toAbsolutePath().normalize()
        val name = displayName(source)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            clipboard = null
            return FileOperationResult.Failure("$name no longer exists")
        }
        val target = directory.resolve(name).normalize()
        if (entry.cut) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return FileOperationResult.Failure("$name already exists in ${relativeDisplay(directory)}")
            }
            val result = move(source, target)
            if (result is FileOperationResult.Success && result.path != source) clipboard = null
            return result
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.OpenCopyPopup(target, "$name already exists")
        }
        return copy(source, target)
    }

    /** P9: the Delete popup's file count, links not followed, `10000+ files` past the cap. */
    fun fileCountText(target: Path): String {
        val key = target.toAbsolutePath().normalize()
        if (!Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)) return countText(1)
        var count = 0
        Files.walkFileTree(key, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                count++
                return if (count > maxCountedFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                if (count > maxCountedFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        })
        return countText(count)
    }

    /** P9: the open tabs at or below [target], the Delete popup's `Closes <n> tabs`. */
    fun openTabCount(target: Path): Int = editorsUnder(target).size

    /** P9: of [openTabCount]'s tabs, the ones whose text is unsaved. */
    fun unsavedTabCount(target: Path): Int = unsavedEditorCount(target)

    /** P10: the open editors under [target] with unsaved text, the Copy popup's warning. */
    fun unsavedEditorCount(target: Path): Int {
        val key = target.toAbsolutePath().normalize()
        return editorsUnder(key).count { it.isModified }
    }

    /** P11: Reveal in Finder through [launcher] on macOS, the desktop elsewhere. */
    fun revealInFinder(target: Path): FileOperationResult {
        val key = target.toAbsolutePath().normalize()
        if (!revealSupported()) return FileOperationResult.Failure(NO_REVEAL_MESSAGE)
        return try {
            if (isMac()) launcher(listOf("open", "-R", key.toString())) else defaultBrowse(key)
            FileOperationResult.Success(key)
        } catch (e: Exception) {
            FileOperationResult.Failure("Reveal failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun create(directory: Path, name: String, text: String?, directoryTarget: Boolean): FileOperationResult {
        relativePathError(name)?.let { return FileOperationResult.Failure(it) }
        val dir = directory.toAbsolutePath().normalize()
        val target = dir.resolve(name).normalize()
        val operation = if (directoryTarget) "New directory" else "New file"
        if (!isInsideRoot(target)) return FileOperationResult.Failure(ROOT_MESSAGE)
        val targetName = target.fileName?.toString() ?: name
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return FileOperationResult.Failure("$targetName already exists")
        }
        busyProvider()?.let { return FileOperationResult.Failure("Git is busy: $it") }
        val createdParents = ArrayList<Path>()
        try {
            var missing: Path? = target.parent
            while (missing != null && !Files.exists(missing)) {
                createdParents.add(0, missing)
                missing = missing.parent
            }
            for (parent in createdParents) Files.createDirectory(parent)
            if (directoryTarget) {
                Files.createDirectory(target)
            } else {
                Files.createFile(target)
                if (!text.isNullOrEmpty()) Files.writeString(target, text)
            }
        } catch (e: IOException) {
            return FileOperationResult.Failure("$operation failed: ${reason(e)}")
        }
        tree.handleEvents(createdParents.map { VFileEvent.Created(VFile(it)) } + VFileEvent.Created(VFile(target)))
        tree.reveal(VFile(target))
        tree.select(VFile(target))
        if (directoryTarget) tree.expand(VFile(target))
        git.refresh()
        return FileOperationResult.Success(target, "Created ${relative(target)}")
    }

    private fun followMove(from: Path, to: Path) {
        for (editor in editors.openFiles.filter { isAtOrBelow(it.file.path, from) }) {
            val oldPath = editor.file.path.toAbsolutePath().normalize()
            editors.replaceFile(editor, VFile(to.resolve(from.relativize(oldPath)).normalize()))
        }
        editors.replacePaths(from, to)
        tree.moved(from, to)
        bookmarks.move(from, to, rootKey)
        tree.handleEvents(listOf(VFileEvent.Deleted(VFile(from)), VFileEvent.Created(VFile(to))))
        git.refresh()
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectory(to.resolve(from.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, to.resolve(from.relativize(file)), LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** P5: on a case-insensitive volume the target resolves to the source file itself. */
    private fun isCaseOnlyRename(from: Path, to: Path): Boolean {
        if (from == to || from.parent != to.parent) return false
        val name = from.fileName?.toString() ?: return false
        if (!name.equals(to.fileName?.toString(), ignoreCase = true)) return false
        if (name == to.fileName?.toString()) return false
        return Files.exists(to, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.isSameFile(from, to) }.getOrDefault(false)
    }

    private fun isTracked(path: Path): Boolean = runCatching {
        // git matches pathspecs relative to the working directory, where the command runs.
        val pathSpec = rootKey.relativize(path).toString().ifEmpty { path.fileName?.toString() ?: return@runCatching false }
        git.executor.run(
            GitCommand(
                args = listOf("ls-files", "--error-unmatch", "--", pathSpec),
                workDir = rootKey,
                timeout = GIT_TIMEOUT,
            ),
        ).ok
    }.getOrDefault(false)

    private fun editorsUnder(target: Path): List<FileEditor> =
        editors.openFiles.filter { isAtOrBelow(it.file.path, target) }

    private fun isAtOrBelow(path: Path, ancestor: Path): Boolean {
        val key = path.toAbsolutePath().normalize()
        return key.startsWith(ancestor)
    }

    private fun isInsideRoot(path: Path): Boolean {
        val key = path.toAbsolutePath().normalize()
        return key != rootKey && key.startsWith(rootKey)
    }

    private fun displayName(path: Path): String = path.fileName?.toString() ?: path.toString()

    private fun relative(path: Path): String = rootKey.relativize(path.toAbsolutePath().normalize()).toString()

    /** A root-relative directory for the P13 texts; the root itself reads as `.`. */
    private fun relativeDisplay(path: Path): String {
        val text = relative(path)
        return text.ifEmpty { "." }
    }

    private fun countText(count: Int): String = when {
        count > maxCountedFiles -> "${maxCountedFiles}+ files"
        count == 1 -> "1 file"
        else -> "$count files"
    }

    private fun template(kind: KotlinFileKind, name: String): String = when (kind) {
        KotlinFileKind.CLASS -> "class $name {\n}\n"
        KotlinFileKind.FILE -> ""
        KotlinFileKind.INTERFACE -> "interface $name {\n}\n"
        KotlinFileKind.DATA_CLASS -> "data class $name()\n"
        KotlinFileKind.ENUM_CLASS -> "enum class $name {\n}\n"
        KotlinFileKind.OBJECT -> "object $name {\n}\n"
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '\u0000' !in name

    private fun isValidRelativePath(text: String): Boolean {
        if (text.isEmpty() || text.startsWith("/") || '\u0000' in text) return false
        return text.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun reason(e: IOException): String = e.message ?: e.javaClass.simpleName

    private fun defaultTrashSupported(): Boolean {
        if (defaultEffectsDisabled()) return true
        return runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
        }.getOrDefault(false)
    }

    private fun defaultMoveToTrash(path: Path): Boolean {
        if (defaultEffectsDisabled()) return false
        return runCatching {
            val desktop = Desktop.getDesktop()
            Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                desktop.moveToTrash(path.toFile())
        }.getOrDefault(false)
    }

    private fun defaultRevealSupported(): Boolean {
        if (isMac()) return true
        return runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)
        }.getOrDefault(false)
    }

    private fun defaultLaunch(command: List<String>) {
        if (defaultEffectsDisabled()) throw IOException("disabled for tests")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) {
            if (!finished) process.destroyForcibly()
            throw IOException("exit code ${if (finished) process.exitValue().toString() else "timeout"}")
        }
    }

    private fun defaultBrowse(path: Path) {
        if (defaultEffectsDisabled()) throw IOException("disabled for tests")
        if (!revealSupported()) throw IOException(NO_REVEAL_MESSAGE)
        Desktop.getDesktop().browseFileDirectory(path.toFile())
    }

    private fun defaultEffectsDisabled(): Boolean =
        System.getProperty(DISABLE_DEFAULT_EFFECTS) == "true"

    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    companion object {
        /** P9: the Delete popup counts files up to this many and reads `10000+` past it. */
        const val MAX_COUNTED_FILES: Int = 10_000

        private const val DISABLE_DEFAULT_EFFECTS = "stride.files.disableDefaultEffects"
        private const val ROOT_MESSAGE = "The project root cannot be changed here"
        private const val NO_TRASH_MESSAGE = "This system has no Trash: delete it in the terminal"
        private const val NO_REVEAL_MESSAGE = "Reveal is not supported on this system"
        private const val LAUNCH_TIMEOUT_SECONDS = 10L
        private val GIT_TIMEOUT = 5.seconds
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
