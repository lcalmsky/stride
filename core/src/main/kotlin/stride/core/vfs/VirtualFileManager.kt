package stride.core.vfs

import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.logging.Level
import java.util.logging.Logger

@Service(Service.Level.PROJECT)
class VirtualFileManager(private val project: Project) : Disposable {
    private val LOG = Logger.getLogger("stride.core.vfs")

    private val lock = Any()
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null
    @Volatile
    private var running = false

    fun start() {
        synchronized(lock) {
            if (running) return
            val service = FileSystems.getDefault().newWatchService()
            val keys = HashMap<WatchKey, Path>()
            registerRecursively(project.root.toAbsolutePath().normalize(), service, keys)
            running = true
            watchService = service
            watchThread = Thread({ runLoop(service, keys) }, "stride-vfs-watcher").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun findFile(path: Path): VFile? {
        val resolved = if (path.isAbsolute) path else project.root.resolve(path)
        val normalized = resolved.normalize()
        return if (Files.exists(normalized)) VFile(normalized) else null
    }

    private fun runLoop(service: WatchService, keys: MutableMap<WatchKey, Path>) {
        while (running) {
            val key = try {
                service.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: ClosedWatchServiceException) {
                return
            }
            val directory = keys[key] ?: key.watchable() as? Path ?: continue
            val events = ArrayList<VFileEvent>()
            for (event in key.pollEvents()) {
                if (event.kind() === OVERFLOW) continue
                val context = event.context() as? Path ?: continue
                val path = directory.resolve(context)
                when (event.kind()) {
                    ENTRY_CREATE -> {
                        events.add(VFileEvent.Created(VFile(path)))
                        if (Files.isDirectory(path)) registerRecursively(path, service, keys)
                    }
                    ENTRY_DELETE -> events.add(VFileEvent.Deleted(VFile(path)))
                    ENTRY_MODIFY -> events.add(VFileEvent.Changed(VFile(path)))
                }
            }
            if (!key.reset()) keys.remove(key)
            if (events.isNotEmpty()) {
                try {
                    project.messageBus.syncPublisher(VFS_CHANGES).after(events.toList())
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "VFS listener failed", t)
                }
            }
        }
    }

    private fun registerRecursively(root: Path, service: WatchService, keys: MutableMap<WatchKey, Path>) {
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { directory ->
                    try {
                        val key = directory.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                        keys[key] = directory
                    } catch (e: IOException) {
                        LOG.log(Level.FINE, "Failed to watch $directory", e)
                    }
                }
            }
        } catch (e: IOException) {
            LOG.log(Level.WARNING, "Failed to register watcher for $root", e)
        }
    }

    override fun dispose() {
        val (service, thread) = synchronized(lock) {
            if (!running) return
            running = false
            val service = watchService
            val thread = watchThread
            watchService = null
            watchThread = null
            service to thread
        }
        try {
            service?.close()
        } catch (e: IOException) {
            LOG.log(Level.FINE, "Failed to close watch service", e)
        }
        thread?.interrupt()
    }
}
