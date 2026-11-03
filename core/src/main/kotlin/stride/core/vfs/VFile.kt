package stride.core.vfs

import stride.core.messages.Topic
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class VFile(val path: Path) {
    val name: String
        get() = path.fileName?.toString() ?: path.toString()

    val extension: String?
        get() {
            val fileName = name
            val dot = fileName.lastIndexOf('.')
            return if (dot <= 0 || dot == fileName.length - 1) null else fileName.substring(dot + 1)
        }

    val isDirectory: Boolean
        get() = Files.isDirectory(path)

    fun children(): List<VFile> {
        if (!isDirectory) return emptyList()
        return try {
            Files.list(path).use { stream -> stream.map { VFile(it) }.toList() }
                .sortedWith(
                    compareBy<VFile> { !it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy { it.name }
                )
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun readText(): String = Files.readString(path)

    fun writeText(s: String) {
        Files.writeString(path, s)
    }

    fun length(): Long = try {
        Files.size(path)
    } catch (e: IOException) {
        0L
    }

    override fun toString(): String = "VFile($path)"
}

sealed interface VFileEvent {
    val file: VFile

    data class Created(override val file: VFile) : VFileEvent
    data class Deleted(override val file: VFile) : VFileEvent
    data class Changed(override val file: VFile) : VFileEvent
}

interface VfsListener {
    fun after(events: List<VFileEvent>)
}

val VFS_CHANGES: Topic<VfsListener> = Topic("VFS changes", VfsListener::class)
