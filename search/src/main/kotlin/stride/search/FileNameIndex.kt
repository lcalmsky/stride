package stride.search

import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service
import stride.core.vfs.VFS_CHANGES
import stride.core.vfs.VFile
import stride.core.vfs.VFileEvent
import stride.core.vfs.VfsListener
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/** A file found by [FileNameIndex], with the path relative to the project root. */
data class IndexedFile(val file: VFile, val relativePath: String) {
    val name: String get() = file.name
}

/**
 * In-memory list of files under the project root.
 *
 * The initial scan runs on a daemon thread; afterwards [VFS_CHANGES] events keep the list
 * current. Directories named `.git`, `build`, `.gradle`, `node_modules`, `.idea` and `out`
 * are never scanned or indexed, and neither is a file named `.git` (a gitdir pointer in
 * worktrees and submodules).
 */
@Service(Service.Level.PROJECT)
class FileNameIndex(private val project: Project) : Disposable {
    private val LOG = Logger.getLogger("stride.search.FileNameIndex")

    private val root: Path = project.root.toAbsolutePath().normalize()
    private val files = ConcurrentHashMap<String, IndexedFile>()
    private val ready = CountDownLatch(1)
    private val scanThread = Thread({ scan() }, "stride-file-name-index").apply { isDaemon = true }

    @Volatile
    private var disposed = false

    init {
        project.messageBus.connect(this).subscribe(VFS_CHANGES, object : VfsListener {
            override fun after(events: List<VFileEvent>) = onEvents(events)
        })
        scanThread.start()
    }

    /** `true` once the initial scan has finished, whether or not it found any files. */
    val isReady: Boolean get() = ready.count == 0L

    /** Waits for the initial scan; returns `false` if it did not finish within [timeoutMillis]. */
    fun awaitReady(timeoutMillis: Long = WAIT_TIMEOUT_MILLIS): Boolean =
        ready.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Current files sorted by relative path. */
    fun snapshot(): List<IndexedFile> = files.values.sortedBy { it.relativePath }

    override fun dispose() {
        disposed = true
        scanThread.interrupt()
    }

    private fun scan() {
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName?.toString() in EXCLUDED_DIRS) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isDirectory) add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            LOG.log(Level.WARNING, "Failed to scan $root", e)
        } catch (t: Throwable) {
            LOG.log(Level.WARNING, "Unexpected failure while scanning $root", t)
        } finally {
            ready.countDown()
        }
    }

    private fun onEvents(events: List<VFileEvent>) {
        if (disposed) return
        for (event in events) {
            when (event) {
                is VFileEvent.Created -> if (event.file.isDirectory) addDirectory(event.file.path) else add(event.file.path)
                is VFileEvent.Deleted -> remove(event.file.path)
                is VFileEvent.Changed -> Unit
            }
        }
    }

    private fun add(path: Path) {
        if (disposed) return
        val absolute = path.toAbsolutePath().normalize()
        if (isExcluded(absolute, isDirectory = false)) return
        val relativePath = relativePath(absolute) ?: return
        files[absolute.toString()] = IndexedFile(VFile(absolute), relativePath)
    }

    private fun addDirectory(directory: Path) {
        val absolute = directory.toAbsolutePath().normalize()
        if (isExcluded(absolute, isDirectory = true)) return
        try {
            Files.walkFileTree(absolute, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != absolute && dir.fileName?.toString() in EXCLUDED_DIRS) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isDirectory) add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            LOG.log(Level.FINE, "Failed to index new directory $directory", e)
        }
    }

    private fun remove(path: Path) {
        val key = path.toAbsolutePath().normalize().toString()
        files.remove(key)
        val prefix = key + File.separator
        files.keys.removeIf { it.startsWith(prefix) }
    }

    private fun isExcluded(absolute: Path, isDirectory: Boolean): Boolean {
        val segments = try {
            root.relativize(absolute).map { it.toString() }
        } catch (e: IllegalArgumentException) {
            return true
        }
        if (segments.isEmpty() || segments.any { it == ".." }) return true
        if (!isDirectory && segments.last() == ".git") return true
        val directorySegments = if (isDirectory) segments else segments.dropLast(1)
        return directorySegments.any { it in EXCLUDED_DIRS }
    }

    private fun relativePath(absolute: Path): String? {
        val relative = try {
            root.relativize(absolute)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (relative.toString().isEmpty() || relative.any { it.toString() == ".." }) return null
        return relative.joinToString("/") { it.toString() }
    }

    companion object {
        const val WAIT_TIMEOUT_MILLIS: Long = 5_000L
        private val EXCLUDED_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea", "out")
    }
}
