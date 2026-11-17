package stride.app.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.find.FindOptions
import stride.core.vfs.VFile
import stride.search.IndexedFile
import java.nio.file.Files
import java.nio.file.Path

class FindInPathScannerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `scan reports line column and body and skips big and binary files`() {
        val hits = indexed("hits.txt", "alpha foo\nbeta\nfoo again\n".toByteArray())
        val miss = indexed("miss.txt", "nothing here\n".toByteArray())
        val big = indexed("big.txt", ByteArray((2 * 1024 * 1024) + 1) { 'a'.code.toByte() })
        val binary = indexed("binary.dat", byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))

        val results = FindInPathScanner.scan(listOf(hits, miss, big, binary), "foo", FindOptions())

        assertEquals(2, results.size)
        assertEquals("hits.txt", results[0].relativePath)
        assertEquals(1, results[0].line)
        assertEquals(7, results[0].column)
        assertEquals("alpha foo", results[0].lineText)
        assertEquals(6, results[0].start)
        assertEquals(9, results[0].end)
        assertEquals(3, results[1].line)
        assertEquals(1, results[1].column)
        assertEquals("foo again", results[1].lineText)
    }

    @Test
    fun `a file that is not valid UTF-8 is still searched`() {
        val legacy = indexed("legacy.txt", "foo café\n".toByteArray(Charsets.ISO_8859_1))
        val valid = indexed("valid.txt", "foo\n".toByteArray())

        val hits = FindInPathScanner.scan(listOf(legacy, valid), "foo", FindOptions())

        assertEquals(listOf("legacy.txt", "valid.txt"), hits.map { it.relativePath })
        assertEquals(listOf(1, 1), hits.map { it.column })
    }

    @Test
    fun `limit stops after the requested number of hits`() {
        val file = indexed("hits.txt", "foo\nfoo\n".toByteArray())

        assertEquals(1, FindInPathScanner.scan(listOf(file), "foo", FindOptions(), limit = 1).size)
    }

    @Test
    fun `whole word skips matches inside a larger identifier`() {
        val file = indexed("words.txt", "foo foobar barfoo foo\n".toByteArray())

        val all = FindInPathScanner.scan(listOf(file), "foo", FindOptions())
        val whole = FindInPathScanner.scan(listOf(file), "foo", FindOptions(wholeWord = true))

        assertEquals(4, all.size)
        assertEquals(listOf(1, 19), whole.map { it.column })
    }

    @Test
    fun `should stop produces no hits`() {
        val file = indexed("hits.txt", "foo\n".toByteArray())

        assertEquals(0, FindInPathScanner.scan(listOf(file), "foo", FindOptions()) { true }.size)
    }

    @Test
    fun `an overlay text is scanned instead of the disk bytes`() {
        val file = indexed("A.kt", "on disk foo\n".toByteArray())
        val overlay: (Path) -> String? = { path -> if (path == file.file.path) "unsaved foo foo\n" else null }

        val hits = FindInPathScanner.scan(listOf(file), "foo", FindOptions(), textOf = overlay)
        val disk = FindInPathScanner.scan(listOf(file), "foo", FindOptions())

        assertEquals(2, hits.size)
        assertEquals(listOf(8, 12), hits.map { it.start })
        assertEquals("unsaved foo foo", hits.first().lineText)
        assertEquals("foo", hits.first().matchText)
        assertEquals(1, disk.size)
        assertEquals("on disk foo", disk.single().lineText)
    }

    private fun indexed(name: String, content: ByteArray): IndexedFile {
        val path = temp.resolve(name)
        Files.write(path, content)
        return IndexedFile(VFile(path), name)
    }
}
