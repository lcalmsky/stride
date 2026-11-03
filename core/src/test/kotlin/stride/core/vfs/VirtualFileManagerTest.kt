package stride.core.vfs

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VirtualFileManagerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `publishes created and deleted events for watched files`() {
        Application.resetForTests()
        val project = Project(tempDir)
        val manager = project.service<VirtualFileManager>()
        val received = Collections.synchronizedList(ArrayList<VFileEvent>())
        val created = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        val connection = project.messageBus.connect(project)
        connection.subscribe(VFS_CHANGES, object : VfsListener {
            override fun after(events: List<VFileEvent>) {
                received.addAll(events)
                if (events.any { it is VFileEvent.Created && it.file.name == "new.txt" }) created.countDown()
                if (events.any { it is VFileEvent.Deleted && it.file.name == "new.txt" }) deleted.countDown()
            }
        })
        manager.start()

        val file = tempDir.resolve("new.txt")
        Files.writeString(file, "hello")
        assertTrue(created.await(5, TimeUnit.SECONDS), "Created event not received within 5s: $received")

        Files.delete(file)
        assertTrue(deleted.await(5, TimeUnit.SECONDS), "Deleted event not received within 5s: $received")

        project.dispose()
    }

    @Test
    fun `modifying a watched file publishes a changed event`() {
        Application.resetForTests()
        val file = tempDir.resolve("watched.txt")
        Files.writeString(file, "one")
        val project = Project(tempDir)
        try {
            val manager = project.service<VirtualFileManager>()
            val changed = CountDownLatch(1)
            project.messageBus.connect(project).subscribe(VFS_CHANGES, object : VfsListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileEvent.Changed && it.file.name == "watched.txt" }) {
                        changed.countDown()
                    }
                }
            })
            manager.start()

            Files.writeString(file, "one with more content")

            assertTrue(changed.await(5, TimeUnit.SECONDS), "Changed event not received within 5s")
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `a new subdirectory is watched and a file created inside publishes Created`() {
        Application.resetForTests()
        val project = Project(tempDir)
        try {
            val manager = project.service<VirtualFileManager>()
            val directoryCreated = CountDownLatch(1)
            val fileCreated = CountDownLatch(1)
            project.messageBus.connect(project).subscribe(VFS_CHANGES, object : VfsListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        if (event !is VFileEvent.Created) continue
                        if (event.file.name == "nested" && event.file.isDirectory) directoryCreated.countDown()
                        if (event.file.name == "inside.txt" && event.file.path.parent?.fileName?.toString() == "nested") {
                            fileCreated.countDown()
                        }
                    }
                }
            })
            manager.start()

            Files.createDirectories(tempDir.resolve("nested"))
            assertTrue(directoryCreated.await(5, TimeUnit.SECONDS), "Created event for the new directory not received")

            Files.writeString(tempDir.resolve("nested/inside.txt"), "x")
            assertTrue(fileCreated.await(5, TimeUnit.SECONDS), "Created event for a file in the new directory not received")
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `dispose stops the watch thread and publishes nothing more`() {
        Application.resetForTests()
        val project = Project(tempDir)
        try {
            val manager = project.service<VirtualFileManager>()
            val received = Collections.synchronizedList(ArrayList<VFileEvent>())
            project.messageBus.connect(project).subscribe(VFS_CHANGES, object : VfsListener {
                override fun after(events: List<VFileEvent>) {
                    received.addAll(events)
                }
            })
            val before = watcherThreads()
            manager.start()
            val watcher = (watcherThreads() - before).single()

            manager.dispose()

            assertTrue(
                awaitUntil { !watcher.isAlive },
                "watch thread was still alive 5s after dispose",
            )
            Files.writeString(tempDir.resolve("after-dispose.txt"), "x")
            Thread.sleep(500)
            assertTrue(received.isEmpty(), "events published after dispose: $received")
        } finally {
            project.dispose()
        }
    }

    private fun watcherThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "stride-vfs-watcher" && it.isAlive }.toSet()

    private fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
