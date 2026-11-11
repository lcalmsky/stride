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

/**
 * Kotlin and Java declarations under the project root, parsed with [DeclarationParser].
 *
 * The initial scan runs on a daemon thread; afterwards [VFS_CHANGES] events re-parse changed
 * files and drop deleted ones. Directories named `.git`, `build`, `.gradle`, `node_modules`,
 * `.idea` and `out` are skipped, exactly like [FileNameIndex].
 */
@Service(Service.Level.PROJECT)
class DeclarationIndex(private val project: Project) : Disposable {
    private val LOG = Logger.getLogger("stride.search.DeclarationIndex")

    private val root: Path = project.root.toAbsolutePath().normalize()
    private val byFile = ConcurrentHashMap<String, List<Declaration>>()
    private val ready = CountDownLatch(1)
    private val scanThread = Thread({ scan() }, "stride-declaration-index").apply { isDaemon = true }

    @Volatile
    private var disposed = false

    init {
        project.messageBus.connect(this).subscribe(VFS_CHANGES, object : VfsListener {
            override fun after(events: List<VFileEvent>) = onEvents(events)
        })
        scanThread.start()
    }

    /** `true` once the initial scan has finished, whether or not it found any declarations. */
    val isReady: Boolean get() = ready.count == 0L

    /** Waits for the initial scan; returns `false` if it did not finish within [timeoutMillis]. */
    fun awaitReady(timeoutMillis: Long = WAIT_TIMEOUT_MILLIS): Boolean =
        ready.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Current declarations ordered by path, line and name. */
    fun snapshot(): List<Declaration> = byFile.values.flatten()
        .sortedWith(compareBy({ it.relativePath }, { it.line }, { it.name }))

    override fun dispose() {
        disposed = true
        scanThread.interrupt()
    }

    private fun scan() {
        try {
            walk(root)
        } catch (e: IOException) {
            LOG.log(Level.WARNING, "Failed to scan $root", e)
        } catch (t: Throwable) {
            LOG.log(Level.WARNING, "Unexpected failure while scanning $root", t)
        } finally {
            ready.countDown()
        }
    }

    private fun walk(start: Path) {
        if (start.toAbsolutePath().normalize() != root && isExcludedDirectory(start)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != start && dir.fileName?.toString() in EXCLUDED_DIRS) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isDirectory) index(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    private fun onEvents(events: List<VFileEvent>) {
        if (disposed) return
        for (event in events) {
            when (event) {
                is VFileEvent.Created -> if (event.file.isDirectory) walk(event.file.path) else index(event.file.path)
                is VFileEvent.Deleted -> remove(event.file.path)
                is VFileEvent.Changed -> index(event.file.path)
            }
        }
    }

    private fun index(path: Path) {
        if (disposed) return
        val absolute = path.toAbsolutePath().normalize()
        val fileName = absolute.fileName?.toString().orEmpty()
        if (!DeclarationParser.isSourceFile(fileName)) return
        if (isExcluded(absolute)) return
        val relativePath = relativePath(absolute) ?: return
        val size = try {
            Files.size(absolute)
        } catch (e: IOException) {
            return
        }
        if (size > MAX_FILE_SIZE) return
        val text = try {
            Files.readString(absolute)
        } catch (e: IOException) {
            return
        }
        val file = VFile(absolute)
        byFile[absolute.toString()] = DeclarationParser.parse(fileName, text)
            .map { Declaration(it.name, it.kind, file, relativePath, it.line, it.container) }
    }

    private fun remove(path: Path) {
        val key = path.toAbsolutePath().normalize().toString()
        byFile.remove(key)
        val prefix = key + File.separator
        byFile.keys.removeIf { it.startsWith(prefix) }
    }

    private fun isExcluded(absolute: Path): Boolean {
        val segments = segmentsOf(absolute) ?: return true
        if (segments.isEmpty() || segments.any { it == ".." }) return true
        return segments.dropLast(1).any { it in EXCLUDED_DIRS }
    }

    private fun isExcludedDirectory(absolute: Path): Boolean {
        val segments = segmentsOf(absolute) ?: return true
        return segments.isEmpty() || segments.any { it == ".." } || segments.any { it in EXCLUDED_DIRS }
    }

    private fun segmentsOf(absolute: Path): List<String>? = try {
        root.relativize(absolute).map { it.toString() }
    } catch (e: IllegalArgumentException) {
        null
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
        const val MAX_FILE_SIZE: Long = 1024L * 1024L
        private val EXCLUDED_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea", "out")
    }
}
