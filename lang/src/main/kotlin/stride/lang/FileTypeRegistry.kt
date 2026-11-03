package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionBean
import stride.core.extensions.ExtensionArea
import stride.core.services.Application

class FileTypeBean : ExtensionBean() {
    override val key: String? get() = attributes["extension"]?.lowercase()

    val extension: String? get() = attributes["extension"]

    val language: Language? get() = attributes["language"]?.let(::Language)

    fun fileType(): FileType = instance(FileType::class)
}

object FileTypeRegistry {
    const val FILE_TYPE_EP = "stride.fileType"

    val EP: BeanExtensionPointName<FileTypeBean> = BeanExtensionPointName(FILE_TYPE_EP)

    fun getFileType(fileName: String, area: ExtensionArea = Application.extensionArea): FileType =
        getFileTypeByExtension(extensionOf(fileName), area)

    fun getFileTypeByExtension(extension: String?, area: ExtensionArea = Application.extensionArea): FileType {
        val normalized = extension?.lowercase() ?: return PlainTextFileType
        return EP.forKey(normalized, area).firstOrNull()?.fileType() ?: PlainTextFileType
    }

    private fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1)
    }
}
