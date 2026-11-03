package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor

object FakeFileType : LanguageFileType(
    Language("fake"),
    name = "Fake",
    description = "Fake file",
    defaultExtension = "fake",
)

class FileTypeRegistryTest {
    private fun area(): ExtensionArea {
        val area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(
                FileTypeRegistry.FILE_TYPE_EP,
                null,
                FileTypeBean::class.java.name,
                "stride.core",
            )
        )
        area.registerExtension(
            FileTypeRegistry.FILE_TYPE_EP,
            ExtensionElement(
                "test",
                mapOf(
                    "extension" to "fake",
                    "language" to "fake",
                    "implementationClass" to FakeFileType::class.java.name,
                ),
                null,
            )
        )
        return area
    }

    @Test
    fun `known extension resolves to the registered file type`() {
        assertSame(FakeFileType, FileTypeRegistry.getFileType("Example.fake", area()))
    }

    @Test
    fun `extension matching ignores case and uses the last dot`() {
        assertSame(FakeFileType, FileTypeRegistry.getFileType("Example.FAKE", area()))
        assertSame(FakeFileType, FileTypeRegistry.getFileType("archive.1.fake", area()))
    }

    @Test
    fun `unknown extension and extensionless names fall back to plain text`() {
        assertSame(PlainTextFileType, FileTypeRegistry.getFileType("notes.xyz", area()))
        assertSame(PlainTextFileType, FileTypeRegistry.getFileType("README", area()))
        assertSame(PlainTextFileType, FileTypeRegistry.getFileType(".gitignore", area()))
        assertSame(PlainTextFileType, FileTypeRegistry.getFileType("trailing.", area()))
    }

    @Test
    fun `bean exposes the parsed language`() {
        val bean = FileTypeRegistry.EP.forKey("fake", area()).single()
        assertEquals("fake", bean.extension)
        assertEquals(Language("fake"), bean.language)
        assertEquals("Fake", bean.fileType().name)
    }
}
