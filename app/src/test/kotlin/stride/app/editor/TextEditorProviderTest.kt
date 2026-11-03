package stride.app.editor

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application
import stride.core.services.Project
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class TextEditorProviderTest {
    @TempDir
    lateinit var temp: Path

    private val provider = TextEditorProvider()

    @Test
    fun `file at the size limit stays editable`() {
        val file = writeOfSize("limit.txt", TextFileEditor.MAX_HIGHLIGHTED_SIZE)

        val editor = provider.createEditor(Project(temp), VFile(file)) as TextFileEditor

        assertFalse(editor.isReadOnly)
    }

    @Test
    fun `file one byte above the size limit is read-only`() {
        val file = writeOfSize("over.txt", TextFileEditor.MAX_HIGHLIGHTED_SIZE + 1)

        val editor = provider.createEditor(Project(temp), VFile(file)) as TextFileEditor

        assertTrue(editor.isReadOnly)
    }

    private fun writeOfSize(name: String, size: Long): Path {
        val path = temp.resolve(name)
        Files.write(path, ByteArray(size.toInt()) { 'a'.code.toByte() })
        return path
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(TextEditorProviderTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
